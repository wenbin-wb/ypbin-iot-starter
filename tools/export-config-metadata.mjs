#!/usr/bin/env node
/**
 * 配置元数据聚合导出（含漂移门禁 + 构建完整性前置检查）。
 *
 * 为什么由构建产物生成、并把结果提交进仓库：
 * 手工维护的配置清单一定会漂移（母仓的旧人工快照就漏过配置项）。
 * 本项目没有站点消费方，因此不做站点同步；这里的价值是：
 *   ① 给出一份稳定的、机器可读的配置参考（下游工具/文档可直接用）；
 *   ② `--check` 作为漂移门禁：源码改了但没重新生成时构建失败。
 *
 * 模块清单**自动发现**（扫描构建产物），不硬编码：
 * 硬编码会让新增模块被静默漏掉，而漂移门禁仍是绿的 ——
 * 正是本项目反复出问题的「门禁覆盖范围与声称不一致」。
 *
 * ⚠️ 自动发现的反面是「构建不完整」与「漂移」会被混为一谈，本项目两种事故都发生过：
 *   ① **假红**：只跑了局部构建（本仓文档明确要求单模块构建要带 `-am`）时，
 *      发现的模块集合会变小，于是报「已漂移」而源码一个字没改；
 *   ② **假绿**：在这种不完整状态下执行生成，会把已提交的基线**静默缩小**
 *      （例如 5 模块/52 项 → 4 模块/49 项），此后 `--check` 永远通过。
 * 因此本脚本以「**已提交基线的模块集合**」为锚，禁止集合静默缩小；
 * 确实删除了模块时，用 `--allow-shrink` 显式声明。
 *
 * 用法：
 *   node tools/export-config-metadata.mjs                 # 生成（禁止静默缩小模块集合）
 *   node tools/export-config-metadata.mjs --allow-shrink  # 确实删了模块时重新生成基线
 *   node tools/export-config-metadata.mjs --check         # 校验未漂移（CI 用）
 */
import { readFileSync, writeFileSync, existsSync, globSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const outputPath = join(repoRoot, 'tools/generated/iot-config-metadata.json');
const checkOnly = process.argv.includes('--check');
const allowShrink = process.argv.includes('--allow-shrink');

/** 读取已提交基线的模块集合（不存在或损坏时返回空数组，等价于「首次生成」）。 */
function committedModules() {
  if (!existsSync(outputPath)) {
    return [];
  }
  try {
    const parsed = JSON.parse(readFileSync(outputPath, 'utf8'));
    return Array.isArray(parsed.modules) ? parsed.modules : [];
  } catch {
    return [];
  }
}

const metadataFiles = globSync(
  'ypbin-iot-*/target/classes/META-INF/spring-configuration-metadata.json',
  { cwd: repoRoot },
).sort();

if (metadataFiles.length === 0) {
  console.error(
    '[config-metadata] 没有找到任何模块的配置元数据 —— 请先执行 `mvn -DskipTests compile`。\n'
    + '  （不生成空文件：那会让漂移门禁变成永远通过的假门禁）',
  );
  process.exit(1);
}

const modulesWithMetadata = [];
const properties = [];

for (const relative of metadataFiles) {
  const module = relative.split('/')[0];
  modulesWithMetadata.push(module);
  const parsed = JSON.parse(readFileSync(join(repoRoot, relative), 'utf8'));
  for (const property of parsed.properties ?? []) {
    properties.push({
      name: property.name,
      type: property.type ?? null,
      description: (property.description ?? '').trim(),
      defaultValue: property.defaultValue ?? null,
      sourceType: property.sourceType ?? null,
      module,
    });
  }
}

// 稳定排序：否则 Maven 模块顺序变化会造成无意义的 diff
properties.sort((a, b) => a.name.localeCompare(b.name));

const result = {
  generatedBy: 'tools/export-config-metadata.mjs',
  moduleCount: modulesWithMetadata.length,
  modules: modulesWithMetadata,
  propertyCount: properties.length,
  properties,
};

const serialized = JSON.stringify(result, null, 2) + '\n';

// ── 构建完整性前置检查 ──────────────────────────────────────────────
// 相对**已提交基线**，模块集合不得缩小。缩小只可能有两种原因：
//   ① 构建不完整（局部构建）→ 报错并给出正确做法，而不是含糊地说「已漂移」；
//   ② 确实删除了模块 → 用 --allow-shrink 显式确认，让这一次成为有意变更。
const committed = committedModules();
const missing = committed.filter((module) => !modulesWithMetadata.includes(module));

if (missing.length > 0 && !allowShrink) {
  console.error(
    `[config-metadata] 构建产物里缺少 ${missing.length} 个模块的元数据：${missing.join(', ')}\n`
    + `  已提交基线：${committed.length} 个模块；本次发现：${modulesWithMetadata.length} 个模块。\n`
    + '  这**不是「漂移」而是「构建不完整」**（多半只跑了局部构建，或某个模块没编译到）。\n'
    + '  正确做法：先跑一次全量构建（`mvn -B -ntp -DskipTests compile`）再重试。\n'
    + '  若确实删除了模块，用 `node tools/export-config-metadata.mjs --allow-shrink` 重新生成基线。',
  );
  // 用退出码 2 与「已漂移」(1) 区分：前者是前置条件不满足，后者是真的内容不一致。
  process.exit(2);
}

if (checkOnly) {
  if (!existsSync(outputPath)) {
    console.error(`[config-metadata] 缺少 ${outputPath} —— 请执行 node tools/export-config-metadata.mjs`);
    process.exit(1);
  }
  if (readFileSync(outputPath, 'utf8') !== serialized) {
    console.error(
      '[config-metadata] 配置元数据已漂移：源码变更后未重新生成。\n'
      + '  请执行：node tools/export-config-metadata.mjs 并提交结果。',
    );
    process.exit(1);
  }
  console.log(`[config-metadata] 未漂移（${result.moduleCount} 个模块 / ${result.propertyCount} 个配置项）`);
} else {
  writeFileSync(outputPath, serialized, 'utf8');
  const shrunk = allowShrink && missing.length > 0 ? `，并显式移除了 ${missing.length} 个模块（${missing.join(', ')}）` : '';
  console.log(
    `[config-metadata] 已生成 ${outputPath}`
    + `（${result.moduleCount} 个模块 / ${result.propertyCount} 个配置项${shrunk}）`,
  );
}
