#!/usr/bin/env node
/**
 * 配置元数据聚合导出（含漂移门禁）。
 *
 * 为什么由构建产物生成、并把结果提交进仓库：
 * 手工维护的配置清单一定会漂移（母仓的旧人工快照就漏过配置项）。
 * 本项目没有站点消费方，因此不做站点同步；这里的价值是：
 *   ① 给出一份稳定的、机器可读的配置参考（下游工具/文档可直接用）；
 *   ② `--check` 作为漂移门禁：源码改了但没重新生成时构建失败。
 *
 * 用法：
 *   node tools/export-config-metadata.mjs          # 生成
 *   node tools/export-config-metadata.mjs --check  # 校验未漂移（CI 用）
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { globSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const outputPath = join(repoRoot, 'tools/generated/iot-config-metadata.json');
const checkOnly = process.argv.includes('--check');

/** 参与聚合的模块（与 nullaway 门禁的参与模块保持一致） */
const modules = [
  'ypbin-iot-core',
  'ypbin-iot-runtime',
  'ypbin-iot-transport',
  'ypbin-iot-spring-boot-starter',
  'ypbin-iot-protocol-tcp',
  'ypbin-iot-protocol-modbus',
  'ypbin-iot-protocol-mqtt',
  'ypbin-iot-protocol-opcua',
];

const properties = [];
const modulesWithMetadata = [];

for (const module of modules) {
  const file = join(repoRoot, module, 'target/classes/META-INF/spring-configuration-metadata.json');
  if (!existsSync(file)) {
    continue;
  }
  modulesWithMetadata.push(module);
  const parsed = JSON.parse(readFileSync(file, 'utf8'));
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

if (modulesWithMetadata.length === 0) {
  console.error(
    '[config-metadata] 没有找到任何模块的配置元数据 —— 请先执行 `mvn -DskipTests compile`。'
    + '（不生成空文件：那会让漂移门禁变成永远通过的假门禁）',
  );
  process.exit(1);
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

if (checkOnly) {
  if (!existsSync(outputPath)) {
    console.error(`[config-metadata] 缺少 ${outputPath} —— 请执行 node tools/export-config-metadata.mjs`);
    process.exit(1);
  }
  const current = readFileSync(outputPath, 'utf8');
  if (current !== serialized) {
    console.error(
      '[config-metadata] 配置元数据已漂移：源码变更后未重新生成。\n'
      + '  请执行：node tools/export-config-metadata.mjs 并提交结果。',
    );
    process.exit(1);
  }
  console.log(`[config-metadata] 未漂移（${result.moduleCount} 个模块 / ${result.propertyCount} 个配置项）`);
} else {
  writeFileSync(outputPath, serialized, 'utf8');
  console.log(`[config-metadata] 已生成 ${outputPath}（${result.moduleCount} 个模块 / ${result.propertyCount} 个配置项）`);
}
