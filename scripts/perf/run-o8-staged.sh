#!/usr/bin/env bash
# O8 轮询链路**分级升压**运行器（2026-09-19）
#
# 为什么要有这个（而不是直接跑 run-o8-three-link.sh 的 1000 VU）：
#   2026-09-19 首次 O8 直接上 1000 VU，结果**把本机 Docker Desktop 引擎打崩**
#   （jtl 时间轴：前 40s 15,499 请求 100% 成功；t=60s ramp 跑满瞬间起 38,080 条
#    `Connection refused`，之后 0 成功；随后 docker API 全程 500）。
#   `Connection refused`（TCP RST）是**连接层**故障，不是应用过载（过载是读超时）。
#   ⇒ 单点盲打 1000 VU 量到的是**本机端口代理/引擎的天花板**，不是应用容量。
#   分级升压才能把「应用拐点」与「宿主机天花板」分开。
#
# 策略：逐级升压，每级之间**健康检查 + 静默期**；一旦
#   · 健康检查失败（服务不可达）→ 立即中止（说明打崩了宿主机，继续加压无意义）
#   · 或该级错误率 > ERR_MAX / p95 > P95_MAX → 记为该级的拐点，停止升级
#
# 用法：
#   bash scripts/perf/run-o8-staged.sh
# 可覆盖环境变量：POLL_STAGES（默认 "100 200 400"）/ STAGE_DURATION(60) / STAGE_RAMP(10)
#                  / SETTLE_SEC(20) / P95_MAX(800) / ERR_MAX(0.001)
#                  / JMETER_HOME / BASE_HOST / BASE_PORT / DEVICE_ID / OUT_DIR / JAVA_HEAP
set -uo pipefail

JMETER_HOME="${JMETER_HOME:-C:/Users/cwx/OneDrive/Desktop/apache-jmeter-5.6.3}"
BASE_HOST="${BASE_HOST:-127.0.0.1}"
BASE_PORT="${BASE_PORT:-18080}"
DEVICE_ID="${DEVICE_ID:-330449777078}"
POLL_STAGES="${POLL_STAGES:-100 200 400}"
STAGE_DURATION="${STAGE_DURATION:-60}"
STAGE_RAMP="${STAGE_RAMP:-10}"
SETTLE_SEC="${SETTLE_SEC:-20}"
P95_MAX="${P95_MAX:-800}"
ERR_MAX="${ERR_MAX:-0.001}"
JAVA_HEAP="${JAVA_HEAP:--Xms512m -Xmx2g}"

# POSIX 绝对路径 → Windows 绝对路径（Git Bash 里交给 node/java 必须转，否则被拼成 C:\c\...）
win() {
  local p="$1"
  if [[ "$p" =~ ^/([a-zA-Z])/(.*)$ ]]; then
    printf '%s:/%s' "${BASH_REMATCH[1]^}" "${BASH_REMATCH[2]}"
  else
    printf '%s' "$p"
  fi
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/docs/uat-screenshots/2026-09-19/o8-three-link/poll-staged}"
JMX="$ROOT/scripts/perf/poll_scale.jmx"
SUM="$ROOT/scripts/perf/summarize-jtl.mjs"
JMETER_JAR="$JMETER_HOME/bin/ApacheJMeter.jar"
mkdir -p "$OUT_DIR"

OUT_W="$(win "$OUT_DIR")"
SUM_W="$(win "$SUM")"
JMX_W="$(win "$JMX")"

echo "==================== O8 分级升压（轮询链路）===================="
echo "JMeter   : $JMETER_JAR"
echo "目标      : http://$BASE_HOST:$BASE_PORT  device=$DEVICE_ID"
echo "级别      : $POLL_STAGES（每级 ${STAGE_DURATION}s，ramp ${STAGE_RAMP}s，级间静默 ${SETTLE_SEC}s）"
echo "判据      : p95 <= ${P95_MAX}ms 且 错误率 <= ${ERR_MAX}"
echo "输出      : $OUT_DIR"
echo

if [ ! -f "$JMETER_JAR" ]; then
  echo "✗ 找不到 $JMETER_JAR（用 JMETER_HOME 指定安装目录）" >&2
  exit 1
fi

health_ok() {
  local h
  h="$(curl -s -m 5 "http://$BASE_HOST:$BASE_PORT/actuator/health" 2>/dev/null)"
  printf '%s' "$h" | grep -q '"status":"UP"'
}

# ── 取 token（JWT 落文件，走 __FileToString 读入；-J 传参会被截断）────────────
TOKEN_FILE="$OUT_DIR/token.txt"
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
" "$(win "$TOKEN_FILE")"; then
    TOKEN_OK=1
    break
  fi
  echo "  第 $i 次取 token 失败，重试…"
  sleep 3
done
if [ "$TOKEN_OK" != "1" ]; then
  echo "✗ 取 token 失败 —— dev 栈未就绪" >&2
  exit 1
fi
echo "  token 已写入"
echo

# ── 基线健康检查（不绿即中止，绝不在坏基线上加压）────────────────────────────
echo "---- 基线健康检查 ----"
if ! health_ok; then
  echo "✗ 加压前健康检查未通过 ⇒ 中止（先修复环境）" >&2
  exit 1
fi
echo "  OK"
echo

SUMMARY_ROWS=()
ABORTED=""
KNEE=""

for U in $POLL_STAGES; do
  SD="$OUT_DIR/stage-$U"
  mkdir -p "$SD"
  JTL="$SD/results.jtl"
  rm -f "$JTL"

  echo "==================== 级别 USERS=$U ===================="
  # shellcheck disable=SC2086
  java $JAVA_HEAP -jar "$JMETER_JAR" -n \
    -t "$JMX_W" -l "$(win "$JTL")" -j "$(win "$SD/jmeter.log")" \
    -JUSERS="$U" -JRAMP="$STAGE_RAMP" -JDURATION="$STAGE_DURATION" \
    -JBASE_HOST="$BASE_HOST" -JBASE_PORT="$BASE_PORT" \
    -JTOKEN_FILE="$OUT_W/token.txt" -JDEVICE_ID="$DEVICE_ID"
  RC=$?
  echo "  JMeter exit=$RC"

  if [ -s "$JTL" ]; then
    node "$SUM_W" "$(win "$JTL")" "$(win "$SD/summary.json")" || true
  else
    echo "  ⚠️ jtl 为空（JMeter 可能未产出样本）"
  fi

  # 读回该级汇总（summarize-jtl.mjs 输出 JSON；用 node 取值避免 shell 解析 JSON）
  if [ -s "$SD/summary.json" ]; then
    LINE="$(node -e "
const s=require('$(win "$SD/summary.json")');
console.log([s.total,s.ok,s.bad,(s.errorRate*100).toFixed(2),s.p50,s.p95,s.p99,s.pass].join('\t'));
" 2>/dev/null)"
    TOTAL="$(printf '%s' "$LINE" | cut -f1)"
    ERRP="$(printf '%s' "$LINE" | cut -f4)"
    P95="$(printf '%s' "$LINE" | cut -f6)"
    PASS="$(printf '%s' "$LINE" | cut -f8)"
    echo "  样本=$TOTAL 错误率=${ERRP}% p95=${P95}ms pass=$PASS"
    SUMMARY_ROWS+=("$U|$TOTAL|$ERRP|$P95|$PASS")

    # 判定：这一级是否已达拐点
    if [ "$PASS" = "false" ]; then
      echo "  ⇒ 该级未达判据（p95 ${P95}ms / 错误率 ${ERRP}%），记为本轮拐点，停止升级"
      KNEE="$U"
      break
    fi
  fi

  echo "---- 级间检查：静默 ${SETTLE_SEC}s 后健康检查 ----"
  sleep "$SETTLE_SEC"
  if health_ok; then
    echo "  健康 OK，继续下一级"
  else
    echo "  ✗ 健康检查失败 ⇒ 本机/引擎已被打崩，中止升级（该级数据已落盘）" >&2
    ABORTED="1"
    break
  fi
  echo
done

echo
echo "==================== 分级结果 ===================="
printf '%-8s %-10s %-10s %-12s %s\n' "USERS" "样本" "错误率%" "p95(ms)" "pass"
for r in "${SUMMARY_ROWS[@]}"; do
  IFS='|' read -r a b c d e <<<"$r"
  printf '%-8s %-10s %-10s %-12s %s\n' "$a" "$b" "$c" "$d" "$e"
done
[ -n "$KNEE" ] && echo "拐点级别      : $KNEE VU（该级起未达 p95<=${P95_MAX}ms / 错误率<=${ERR_MAX}）"
[ -n "$ABORTED" ] && echo "中止原因      : 宿主机/引擎被打崩（非应用容量结论）"
echo "结果目录      : $OUT_DIR"
exit 0
