#!/usr/bin/env bash
#
# 发布前门禁一次性总检。
#
# 为什么需要它：根 pom 的 dev-only profile 是 activeByDefault，
# **只要显式激活任一 profile（-Pnullaway / -Pdep-convergence / -Psbom）它就会失效**，
# 架构约束门禁（含 ModulePublishingTest）随之不跑。因此发布前必须用本脚本
# 按正确的顺序把几道门禁各跑一遍，而不是合并成一个 -P 组合。
#
# 用法：tools/preflight.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx768m}"

step() { echo; echo "===== $* ====="; }

step "1/4 全量构建与单元测试（不带任何 -P：这是唯一会跑架构约束门禁的方式）"
mvn -B -ntp clean test

step "2/4 NullAway 空值语义门禁（含执行自检）"
tools/check-nullaway.sh

step "3/4 依赖版本收敛"
mvn -B -ntp -Pdep-convergence validate

step "4/4 SBOM 生成（顺带验证 sbom profile 能带回架构测试模块）"
mvn -B -ntp -Psbom verify -DskipTests

echo
echo "全部门禁通过。"
