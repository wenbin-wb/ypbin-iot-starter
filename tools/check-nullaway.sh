#!/usr/bin/env bash
#
# NullAway 空值语义门禁（含「门禁是否真的执行过」自检）。
#
# 为什么需要自检：Error Prone / NullAway 只在 javac 真正执行时生效。
# 若在上一步已经编译过，`mvn compile` 会直接输出「Nothing to compile」并成功返回 ——
# 此时「0 违规」是假象（母仓踩过这个坑）。因此本脚本：
#   ① 强制带 clean，保证真的重新编译；
#   ② 断言输出里出现每个模块的「Compiling N source files」；
#   ③ 出现「Nothing to compile」即判失败。
#
# 用法：tools/check-nullaway.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

MODULES=(
  ypbin-iot-core
  ypbin-iot-runtime
  ypbin-iot-transport
  ypbin-iot-spring-boot-starter
  ypbin-iot-protocol-tcp
  ypbin-iot-protocol-modbus
  ypbin-iot-protocol-mqtt
  ypbin-iot-protocol-opcua
)
LIST="$(IFS=,; echo "${MODULES[*]}")"
LOG="$(mktemp)"

echo "[nullaway] 参与模块（${#MODULES[@]} 个）：${LIST}"
set +e
MAVEN_OPTS="${MAVEN_OPTS:--Xmx768m}" mvn -B -ntp -Pnullaway -pl "$LIST" -am -DskipTests clean compile > "$LOG" 2>&1
STATUS=$?
set -e

if grep -q "Nothing to compile" "$LOG"; then
  echo "[nullaway] 失败：出现「Nothing to compile」—— 门禁没有真正执行，本次结果不可信" >&2
  tail -30 "$LOG" >&2
  exit 1
fi

# 每个参与模块都必须留下编译记录，否则它可能根本没被编译（被上游失败跳过）
MISSING=()
for module in "${MODULES[@]}"; do
  if ! grep -q "Compiling [0-9]* source files" <(awk -v m="$module" '
        /--- .*@ /{ if ($0 ~ ("@ " m " ---")) found=1; else found=0 }
        found && /Compiling [0-9]+ source files/{print}' "$LOG"); then
    MISSING+=("$module")
  fi
done

if [ "${#MISSING[@]}" -gt 0 ]; then
  echo "[nullaway] 失败：以下模块没有编译记录（可能被上游失败跳过，其「0 违规」不可信）：${MISSING[*]}" >&2
  tail -30 "$LOG" >&2
  exit 1
fi

if [ "$STATUS" -ne 0 ]; then
  echo "[nullaway] 失败：构建未通过" >&2
  grep -E "\[NullAway\]|ERROR" "$LOG" | head -40 >&2
  exit 1
fi

echo "[nullaway] 通过：${#MODULES[@]} 个模块均实际编译，0 违规"
rm -f "$LOG"
