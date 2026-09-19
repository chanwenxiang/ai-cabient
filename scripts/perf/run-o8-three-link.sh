#!/usr/bin/env bash
# O8 三链路压测运行器（2026-09-19）
#
# 跑两条**可压**的链路，并把结果与摘要落进 docs/uat-screenshots/<日期>/o8-three-link/。
#   · 轮询链路   poll_scale.jmx         —— 高并发读，产出真正的容量基线
#   · 开门+结算  open_settle_cycle.jmx  —— 闭环写路径，产出风控上限与端到端延迟
#
# 「三链路」里的「开门」「结算」在同一次闭环迭代中各占一个采样器，故用一份 jtl 记录、
# 汇总时按 label 分开看（见末尾输出）。
#
# 为什么不用 bin/jmeter.bat：Git Bash 下 .bat 的路径/引号转换易出问题，而
# `java -jar bin/ApacheJMeter.jar` 是官方支持的非 GUI 入口，跨 shell 行为一致。
#
# ⚠️ 本机坑（本批实际踩到）：Git Bash 里把 POSIX 绝对路径 `/c/Users/...` 交给
#    **Windows 原生程序**（node / java），会被当成相对路径拼成 `C:\c\Users\...`
#    ⇒ ENOENT。所有传给 node / java 的路径都必须先经 `win()` 转成 `C:/Users/...`。
#    （bash 自己的 mkdir/ls/cp 用 POSIX 形式即可。）
#
# 用法：
#   bash scripts/perf/run-o8-three-link.sh
# 可覆盖的环境变量：JMETER_HOME / BASE_HOST / BASE_PORT / POLL_USERS / POLL_RAMP /
#                  POLL_DURATION / WAIT_MS / DEVICE_ID / OUT_DIR / JAVA_HEAP
# 注意：闭环迭代次数**不在这里配**（JMeter 的 <intProp> 只吃整数字面量，见 jmx 头部注释）。
set -uo pipefail

JMETER_HOME="${JMETER_HOME:-C:/Users/cwx/OneDrive/Desktop/apache-jmeter-5.6.3}"
BASE_HOST="${BASE_HOST:-127.0.0.1}"
BASE_PORT="${BASE_PORT:-18080}"
DEVICE_ID="${DEVICE_ID:-330449777078}"
POLL_USERS="${POLL_USERS:-1000}"
POLL_RAMP="${POLL_RAMP:-60}"
POLL_DURATION="${POLL_DURATION:-120}"
WAIT_MS="${WAIT_MS:-3000}"
# 1000 VU 用 JMeter 默认堆（1g）会 OOM。PERF-1 当年是靠 bin/jmeter.bat 尊重预设的
# HEAP 环境变量抬到 4g；这里直调 java -jar，故显式给堆参数。
JAVA_HEAP="${JAVA_HEAP:--Xms1g -Xmx4g}"

# POSIX 绝对路径 → Windows 绝对路径（只给 Windows 原生程序用）
win() {
  local p="$1"
  if [[ "$p" =~ ^/([a-zA-Z])/(.*)$ ]]; then
    printf '%s:/%s' "${BASH_REMATCH[1]^}" "${BASH_REMATCH[2]}"
  else
    printf '%s' "$p"
  fi
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/docs/uat-screenshots/2026-09-19/o8-three-link}"
JMX_DIR="$ROOT/scripts/perf"
SUM="$ROOT/scripts/perf/summarize-jtl.mjs"
JMETER_JAR="$JMETER_HOME/bin/ApacheJMeter.jar"

POLL_DIR="$OUT_DIR/poll"
OPEN_DIR="$OUT_DIR/open-settle"
mkdir -p "$POLL_DIR" "$OPEN_DIR"

POLL_DIR_W="$(win "$POLL_DIR")"
OPEN_DIR_W="$(win "$OPEN_DIR")"
JMX_DIR_W="$(win "$JMX_DIR")"
SUM_W="$(win "$SUM")"

OPEN_LOOPS="$(grep -o '<intProp name="LoopController.loops">[0-9]*' "$JMX_DIR/open_settle_cycle.jmx" | head -1 | grep -o '[0-9]*$')"

echo "==================== O8 三链路压测 ===================="
echo "JMeter   : $JMETER_JAR"
echo "目标      : http://$BASE_HOST:$BASE_PORT  device=$DEVICE_ID"
echo "轮询参数  : USERS=$POLL_USERS RAMP=${POLL_RAMP}s DURATION=${POLL_DURATION}s"
echo "闭环参数  : LOOPS=$OPEN_LOOPS（jmx 内字面量，非 -J 传入） WAIT=${WAIT_MS}ms"
echo "输出目录  : $OUT_DIR"
echo

if [ ! -f "$JMETER_JAR" ]; then
  echo "✗ 找不到 $JMETER_JAR（用 JMETER_HOME 指定安装目录）" >&2
  exit 1
fi

# ── 取 token：JWT 必须落文件再用 __FileToString 读入（走 -J 传参会被截断）──────
echo "---- 取 token ----"
TOKEN_OK=0
for i in 1 2 3 4 5; do
  if node --input-type=module -e "
import fs from 'fs';
const r = await fetch('http://$BASE_HOST:$BASE_PORT/api/v2/auth/password-login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ phoneNumber: '13800138000', password: '123456' }),
});
const j = await r.json();
if (j.code !== 0 || !j.data?.token) { console.error(JSON.stringify(j)); process.exit(1); }
fs.writeFileSync(process.argv[1], j.data.token);
" "$(win "$POLL_DIR/token.txt")"; then
    TOKEN_OK=1
    break
  fi
  echo "  第 $i 次取 token 失败，重试…"
  sleep 3
done
if [ "$TOKEN_OK" != "1" ]; then
  echo "✗ 取 token 失败 —— dev 栈可能未就绪" >&2
  exit 1
fi
cp "$POLL_DIR/token.txt" "$OPEN_DIR/token.txt"
echo "  token 已写入（长度 $(wc -c < "$POLL_DIR/token.txt") 字节）"
echo

run_jmeter() {
  local name="$1" plan="$2" out="$3" jtl="$4"
  shift 4
  echo "---- 跑 [$name] ----"
  rm -f "$jtl"
  # shellcheck disable=SC2086  # JAVA_HEAP 需要按空格拆成多个 JVM 参数
  java $JAVA_HEAP -jar "$JMETER_JAR" -n \
    -t "$(win "$plan")" -l "$(win "$jtl")" -j "$(win "$out/jmeter.log")" "$@"
  local rc=$?
  echo "  JMeter exit=$rc"
  return $rc
}

POLL_RC=0
run_jmeter "轮询链路" "$JMX_DIR/poll_scale.jmx" "$POLL_DIR" "$POLL_DIR/results.jtl" \
  -JUSERS="$POLL_USERS" -JRAMP="$POLL_RAMP" -JDURATION="$POLL_DURATION" \
  -JBASE_HOST="$BASE_HOST" -JBASE_PORT="$BASE_PORT" \
  -JTOKEN_FILE="$POLL_DIR_W/token.txt" -JDEVICE_ID="$DEVICE_ID" || POLL_RC=$?
echo

OPEN_RC=0
run_jmeter "开门+结算闭环" "$JMX_DIR/open_settle_cycle.jmx" "$OPEN_DIR" "$OPEN_DIR/results.jtl" \
  -JWAIT_MS="$WAIT_MS" \
  -JBASE_HOST="$BASE_HOST" -JBASE_PORT="$BASE_PORT" \
  -JTOKEN_FILE="$OPEN_DIR_W/token.txt" -JDEVICE_ID="$DEVICE_ID" || OPEN_RC=$?
echo

# ── 汇总：复用既有 summarize-jtl.mjs（阈值语义面向读路径；写路径只取数字）──────
echo "==================== 汇总 ===================="
if [ -s "$POLL_DIR/results.jtl" ]; then
  node "$SUM_W" "$POLL_DIR_W/results.jtl" "$POLL_DIR_W/summary.json" || true
else
  echo "⚠️ 轮询 results.jtl 为空，跳过汇总"
fi
echo
if [ -s "$OPEN_DIR/results.jtl" ]; then
  node "$SUM_W" "$OPEN_DIR_W/results.jtl" "$OPEN_DIR_W/summary.json" || true
else
  echo "⚠️ 闭环 results.jtl 为空，跳过汇总"
fi

echo
echo "==================== 完成 ===================="
echo "轮询     : $POLL_DIR（results.jtl / summary.json）"
echo "开门结算 : $OPEN_DIR（results.jtl / summary.json）"
echo "JMeter 退出码：轮询=$POLL_RC 闭环=$OPEN_RC（闭环非 0 多半是风控 429 被断言记为失败，属预期）"
exit 0
