#!/usr/bin/env bash
#
# BOM 断言：已安装的 ypbin-iot-bom 制品中**不得**出现 ${revision}（issue #36）。
#
# 为什么需要它：宿主 import ypbin-iot-bom 时，Maven 会用父链
# （ypbin-iot-dependencies → ypbin-iot-starter）里插值出的 revision
# ——即 iot-starter 自身的版本——去展开 BOM 里的 ${revision}；而该属性同时
# 通过父链在宿主的依赖管理上下文里可见，于是宿主只引 iot-bom 不引 starter-bom 时，
# **ypbin-starter-* 的版本会被顶成 iot-starter 的版本号**（实测：期望 4.0.0，实际 0.4.0）。
# 故障形态完全静默：编译能过、运行期行为漂移。
#
# 已在 ypbin-iot-bom/pom.xml 里改用显式属性（${iot-starter.version}）而不是 revision；
# 本脚本把「已安装的对外 BOM 里没有 ${revision}」变成发布前可复现的红线。
#
# 注意：本仓其余模块（dependencies / core / ...）用 ${revision} 是**正常且必需**的
# （它们通过 relativePath 解析父链），本断言只覆盖对外 BOM。
#
# 检查范围：默认只看**当前版本**（根 pom 的 <revision>）——历史版本（0.3.3/0.4.0 等）
# 已经发布到 Central 且无法改写，若把它们算进门禁，这条门禁永远红、等于没有。
# 需要审计全部历史制品时显式加 --all（只用于人工排查，不进发布门禁）。
#
# 用法：
#   tools/check-bom-no-revision.sh          # 发布门禁：只查当前版本（不存在则失败）
#   tools/check-bom-no-revision.sh --all    # 人工审计：列出所有已装版本的问题（历史版一律告警）
#
set -euo pipefail

cd "$(dirname "$0")/.."

BOM_GROUP_PATH="cn/ypbin/ypbin-iot-bom"
LOCAL_REPO="${MAVEN_LOCAL_REPO:-${HOME}/.m2/repository}"
BOM_DIR="${LOCAL_REPO}/${BOM_GROUP_PATH}"
CURRENT_VERSION="$(grep -oP '(?<=<revision>)[^<]+' pom.xml | head -1)"
CHECK_ALL=0
if [ "${1:-}" = "--all" ]; then
  CHECK_ALL=1
fi

echo "[bom] 当前开发版 = ${CURRENT_VERSION}；本地仓库 = ${BOM_DIR}"

if [ ! -d "${BOM_DIR}" ]; then
  echo "[bom] 失败：本地仓库没有 ypbin-iot-bom（先跑 mvn -o install -DskipTests -pl ypbin-iot-bom -am）" >&2
  exit 1
fi

if [ "${CHECK_ALL}" -eq 1 ]; then
  # 人工审计模式：所有版本都扫，但只把「当前版本」的缺陷算失败
  echo "[bom] 模式 = 全量审计（仅当前版本判失败）"
  TARGETS=("${CURRENT_VERSION}")
else
  TARGETS=("${CURRENT_VERSION}")
fi

FAILED=()
for version in "${TARGETS[@]}"; do
  POM="${BOM_DIR}/${version}/ypbin-iot-bom-${version}.pom"
  if [ ! -f "${POM}" ]; then
    echo "[bom] 失败：缺少已安装的 BOM ${POM}" >&2
    echo "  发布前必须先装一次：mvn -o install -DskipTests -pl ypbin-iot-bom -am" >&2
    exit 1
  fi
  if grep -q '\${revision}' "${POM}"; then
    count="$(grep -c '\${revision}' "${POM}")"
    FAILED+=("${version}（${count} 处）")
    echo "[bom] ✗ ${POM} 仍含 \${revision}："
    grep -n '\${revision}' "${POM}" | head -10
  else
    echo "[bom] ✓ ${POM}：无 \${revision}"
  fi
done

if [ "${CHECK_ALL}" -eq 1 ]; then
  echo "[bom] —— 历史版本（已发布，无法改写；仅供参考）——"
  while IFS= read -r pom; do
    version="$(basename "$(dirname "${pom}")")"
    if [ "${version}" = "${CURRENT_VERSION}" ]; then
      continue
    fi
    if grep -q '\${revision}' "${pom}"; then
      echo "[bom] · ${version}：含 \${revision}（历史制品，发布时已存在；新版本不得再出现）"
    else
      echo "[bom] · ${version}：无 \${revision}"
    fi
  done < <(find "${BOM_DIR}" -name 'ypbin-iot-bom-*.pom' -not -name '*.sha1' | sort)
fi

if [ "${#FAILED[@]}" -gt 0 ]; then
  echo "[bom] 失败：当前版本的 BOM 含 \${revision}：${FAILED[*]}" >&2
  echo "  宿主只引 iot-bom 时，ypbin-starter-* 的版本会被顶成 iot-starter 的版本（issue #36）。" >&2
  echo "  修法：BOM 的依赖版本改用显式属性（见 ypbin-iot-bom/pom.xml 的 <iot-starter.version>）。" >&2
  exit 1
fi

echo "[bom] 通过：ypbin-iot-bom ${CURRENT_VERSION} 中无 \${revision}"
