#!/usr/bin/env node
/**
 * 覆盖率快照导出（由构建产物生成，替代散落在文档里手工维护的数字）。
 *
 * 为什么需要它：覆盖率数字原先被手工抄进 README / ROADMAP / docs/PROTOCOLS.md，
 * 于是同一份数据在仓库里出现了**多个互不一致的版本**，且每次改动都不会自动跟上
 * （本次核查实测：README 写的 8 个模块里有 3 个与实际偏差超过仓库自述的 ±0.5pp）。
 * 与配置元数据同样的问题、同样的解法：**由构建产物生成一份，文档只引用它**。
 *
 * ⚠️ 为什么本文件**不做数值门禁**：
 * 覆盖率存在执行波动（同一提交、同一命令的两次全量运行实测差 0.04~0.78pp，
 * 竞争态分支的命中取决于队列满/空状态）。把波动的数字当门禁必然产生假失败，
 * 这正是本仓「不用挂钟断言复杂度」的同一条教训。
 * 因此 `--check` 只校验**确定性的部分**：
 *   ① 快照必须存在且可解析；
 *   ② 快照里的模块集合必须与当前构建产物里的模块集合**完全一致**
 *      （新增/删除模块后忘记重新生成 → 红）。
 * 数值本身只在生成时刷新，供人阅读与对比。
 *
 * 用法：
 *   node tools/export-coverage.mjs                 # 生成（禁止静默缩小模块集合）
 *   node tools/export-coverage.mjs --allow-shrink  # 确实删了模块时重新生成基线
 *   node tools/export-coverage.mjs --check         # 校验模块集合未漂移（CI 用）
 */
import { readFileSync, writeFileSync, existsSync, globSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const outputPath = join(repoRoot, 'tools/generated/iot-coverage.json');
const checkOnly = process.argv.includes('--check');
const allowShrink = process.argv.includes('--allow-shrink');

/** 门禁阈值：与 pom 里 jacoco-check 的配置保持一致（改 pom 时同步这里）。 */
const GATE = { instruction: 0.80, branch: 0.64 };

/** 从 jacoco.csv 聚合出「指令 / 分支」覆盖率（百分比，保留两位小数）。 */
function coverageOf(csvPath) {
  let instructionMissed = 0;
  let instructionCovered = 0;
  let branchMissed = 0;
  let branchCovered = 0;
  let classes = 0;
  for (const line of readFileSync(csvPath, 'utf8').split('\n')) {
    if (line === '' || line.startsWith('GROUP,')) {
      continue;
    }
    const columns = line.split(',');
    classes += 1;
    instructionMissed += Number(columns[3]);
    instructionCovered += Number(columns[4]);
    branchMissed += Number(columns[5]);
    branchCovered += Number(columns[6]);
  }
  const instructionTotal = instructionMissed + instructionCovered;
  const branchTotal = branchMissed + branchCovered;
  const round = (value) => Math.round(value * 100) / 100;
  return {
    classes,
    instructionPct: instructionTotal === 0 ? 0 : round(100 * instructionCovered / instructionTotal),
    branchPct: branchTotal === 0 ? 0 : round(100 * branchCovered / branchTotal),
  };
}

const csvFiles = globSync('ypbin-iot-*/target/site/jacoco/jacoco.csv', { cwd: repoRoot }).sort();

if (csvFiles.length === 0) {
  console.error(
    '[coverage] 没有找到任何 jacoco.csv —— 请先执行 `mvn -B -ntp clean test`。\n'
    + '  （不生成空快照：那会让文档里的覆盖率变成永远为零的假数据）',
  );
  process.exit(1);
}

const modules = [];
const withoutData = [];

for (const relative of csvFiles) {
  const module = relative.split('/')[0];
  const coverage = coverageOf(join(repoRoot, relative));
  // 没有主源码的模块（如 architecture-tests）也会被 JaCoCo 生成一个只有表头的 csv：
  // 若把它算进来会显示成「指令 0%」，误导读者，因此明确排除并列出原因。
  if (coverage.classes === 0) {
    withoutData.push(module);
    continue;
  }
  modules.push({
    module,
    classes: coverage.classes,
    instructionPct: coverage.instructionPct,
    branchPct: coverage.branchPct,
    instructionMarginPp: Math.round((coverage.instructionPct - GATE.instruction * 100) * 100) / 100,
    branchMarginPp: Math.round((coverage.branchPct - GATE.branch * 100) * 100) / 100,
  });
}

// 按分支余量升序：最薄的排在最前，让「该盯着哪个模块」一眼可见
modules.sort((a, b) => a.branchMarginPp - b.branchMarginPp);

if (modules.length === 0) {
  console.error(
    '[coverage] 所有 jacoco.csv 都没有类数据 —— 构建多半不完整（没编译到主源码）。\n'
    + '  请先执行 `mvn -B -ntp clean test` 再重试。',
  );
  process.exit(2);
}

const currentModuleSet = modules.map((entry) => entry.module).sort();

/** 读取已提交基线的模块集合（不存在或损坏时返回空数组，等价于「首次生成」）。 */
function committedModuleSet() {
  if (!existsSync(outputPath)) {
    return [];
  }
  try {
    const parsed = JSON.parse(readFileSync(outputPath, 'utf8'));
    return (parsed.modules ?? []).map((entry) => entry.module).sort();
  } catch {
    return [];
  }
}

// ── 构建完整性前置检查（与 export-config-metadata.mjs 同一套理由）────────
// 局部构建会让发现的模块集合变小；若把它当成新基线写回去，门禁基线就被永久拉低了。
const baseline = committedModuleSet();
const missingFromBuild = baseline.filter((module) => !currentModuleSet.includes(module));

if (missingFromBuild.length > 0 && !allowShrink) {
  console.error(
    `[coverage] 构建产物里缺少 ${missingFromBuild.length} 个模块的 jacoco.csv：${missingFromBuild.join(', ')}\n`
    + `  已提交基线：${baseline.length} 个模块；本次发现：${currentModuleSet.length} 个模块。\n`
    + '  这**不是「覆盖率变了」而是「构建不完整」**（多半只跑了局部构建）。\n'
    + '  正确做法：先跑一次全量构建（`mvn -B -ntp clean test`）再重试。\n'
    + '  若确实删除了模块，用 `node tools/export-coverage.mjs --allow-shrink` 重新生成基线。',
  );
  // 退出码 2 = 前置条件不满足（构建不完整），与「集合不一致」(1) 区分
  process.exit(2);
}

if (checkOnly) {
  if (!existsSync(outputPath)) {
    console.error(`[coverage] 缺少 ${outputPath} —— 请执行 node tools/export-coverage.mjs 并提交结果。`);
    process.exit(1);
  }
  const missingFromSnapshot = currentModuleSet.filter((module) => !baseline.includes(module));
  if (missingFromSnapshot.length > 0) {
    console.error(
      `[coverage] 有 ${missingFromSnapshot.length} 个模块不在快照里：${missingFromSnapshot.join(', ')}\n`
      + '  新增/改名模块后需要重新生成并提交：`node tools/export-coverage.mjs`。',
    );
    process.exit(1);
  }
  console.log(`[coverage] 模块集合一致（${modules.length} 个模块；数值不参与门禁，波动属正常）`);
} else {
  const snapshot = {
    generatedBy: 'tools/export-coverage.mjs',
    gate: { instruction: GATE.instruction, branch: GATE.branch },
    note: '覆盖率存在执行波动（同一提交两次全量运行实测差 0.04~0.78pp），本文件只记录最近一次实测值，'
      + '不参与门禁判定；阈值由 pom 的 jacoco-check 强制执行。',
    excludedNoDataModules: withoutData,
    moduleCount: modules.length,
    modules,
  };
  writeFileSync(outputPath, JSON.stringify(snapshot, null, 2) + '\n', 'utf8');
  const shrunk = allowShrink && missingFromBuild.length > 0
    ? `，并显式移除了 ${missingFromBuild.length} 个模块（${missingFromBuild.join(', ')}）`
    : '';
  console.log(`[coverage] 已生成 ${outputPath}（${modules.length} 个模块${shrunk}）`);
  for (const entry of modules) {
    console.log(
      `  ${entry.module.padEnd(30)} 指令 ${entry.instructionPct}%  分支 ${entry.branchPct}%`
      + `（分支余量 ${entry.branchMarginPp >= 0 ? '+' : ''}${entry.branchMarginPp}pp）`,
    );
  }
  if (withoutData.length > 0) {
    console.log(`  （已排除无覆盖数据的模块：${withoutData.join(', ')} —— 它们没有主源码）`);
  }
}
