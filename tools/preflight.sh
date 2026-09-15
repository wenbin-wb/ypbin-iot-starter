#!/usr/bin/env bash
#
# 发布前门禁一次性总检。
#
# 为什么需要它：根 pom 的 dev-only profile 是 activeByDefault，
# **只要显式激活任一 profile（-Pnullaway / -Pdep-convergence / -Psbom / -Pit）它就会失效**，
# 架构约束门禁（含 ModulePublishingTest）随之不跑。因此发布前必须用本脚本
# 按正确的顺序把几道门禁各跑一遍，而不是合并成一个 -P 组合。
#
# ⚠️ 步骤顺序有硬约束：**凡是会 clean 的步骤，必须排在「产出覆盖率报告」的构建步骤之前**，
# 否则 target/site/jacoco/ 会被删掉 —— CI 上真实发生过「归档步骤 success 但零产物」
# （日志：No files were found with the provided path: **/target/site/jacoco/）。
# 本脚本因此把 NullAway（内部是 clean compile）放在第 1 步，并在最后断言覆盖率报告仍在。
#
# 用法：tools/preflight.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx768m}"

step() { echo; echo "===== $* ====="; }

step "1/8 空值语义门禁（含执行自检；内部是 clean compile，必须排在覆盖率产出之前）"
tools/check-nullaway.sh

step "2/8 依赖版本收敛"
mvn -B -ntp -Pdep-convergence validate

step "3/8 全量构建与单元测试（不带任何 -P：这是唯一会跑架构约束门禁的方式）"
mvn -B -ntp clean test

step "4/8 集成测试（-Pit：it profile 已显式跳过 surefire，这里只跑 *IT.java）"
mvn -B -ntp -Pit verify

step "5/8 配置元数据漂移（含「模块集合不得静默缩小」）"
node tools/export-config-metadata.mjs --check

step "6/8 覆盖率快照的模块集合（数值不设门禁：有 0.04~0.78pp 的执行波动）"
node tools/export-coverage.mjs --check

step "7/8 SBOM 生成（顺带验证 sbom profile 能带回两个非发布模块）"
mvn -B -ntp -Psbom verify -DskipTests

step "8/8 覆盖率报告存在性断言（防止「到归档/查看时才发现报告被 clean 删了」）"
count="$(find . -path '*/target/site/jacoco/jacoco.csv' | wc -l)"
echo "jacoco.csv 数量 = ${count}（期望 >= 8：8 个既有主源码又有测试数据的模块）"
if [ "${count}" -lt 8 ]; then
  echo "失败：覆盖率报告缺失或不足（实测 ${count} 个）—— 多半是某一步执行了 clean。" >&2
  echo "  排查方向：把带 clean 的步骤移到第 3 步之前，或让该步骤不要复用默认 target/。" >&2
  exit 1
fi

echo
echo "全部门禁通过。"
