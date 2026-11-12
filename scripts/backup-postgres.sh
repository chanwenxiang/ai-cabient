#!/usr/bin/env bash
# 生产/测试环境 PostgreSQL 定时备份（pg_dump 自定义格式）。
#
# 用法：
#   bash scripts/backup-postgres.sh                 # 默认写 /data/backups，保留 7 天
#   BACKUP_DIR=/data/backups KEEP_DAYS=14 bash ...  # 覆盖
#
# 约定：
#   · 容器名可用 PG_CONTAINER 覆盖（默认 ai-cabinet-postgres-1）
#   · 先写 .tmp 再 mv：避免 dump 中途失败留下半个文件被当成可用备份
#   · 文件小于 64KB 视为异常（空库也应有 schema），保留 .tmp 并退出非零
#   · 异地副本：检测到同机 MinIO 容器时自动流式上传（mc pipe），失败退出非零；
#     MINIO_CONTAINER / MINIO_BUCKET / OFFSITE_KEEP_DAYS 可覆盖
#
# P2-r19-28（2026-11-12）备份告警与新鲜度：
#   此前本脚本失败只写 stderr ⇒ cron 若没配 MAILTO，失败就是**完全静默**。
#   连续失败 7 天后旧备份被 retention 清空，结果是「零备份」而无人知晓——
#   这是最贵的假绿（比没备份更危险，因为所有人都以为有）。
#   现在：
#     · 任何非零退出都走 alert()，默认打印 stderr，配 BACKUP_ALERT_CMD 即接告警通道
#       （钩子签名：<cmd> <LEVEL> <MESSAGE>；钩子自身失败不影响备份结论）
#     · 每次成功写 .last-success-epoch；开场若距上次成功超过 STALE_HOURS（默认 30h，
#       容忍「晚点跑」不误报），先发 WARN —— 让「昨天就没成功」在今晚失败前就被看见
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-ai-cabinet-postgres-1}"
PG_USER="${PG_USER:-aicabinet}"
PG_DB="${PG_DB:-aicabinet}"
BACKUP_DIR="${BACKUP_DIR:-/data/backups}"
KEEP_DAYS="${KEEP_DAYS:-7}"
MIN_BYTES=$((64 * 1024))
MINIO_CONTAINER="${MINIO_CONTAINER:-ai-cabinet-minio-1}"
MINIO_BUCKET="${MINIO_BUCKET:-db-backups}"
OFFSITE_KEEP_DAYS="${OFFSITE_KEEP_DAYS:-30}"
STATE_FILE="${STATE_FILE:-}"
STALE_HOURS="${STALE_HOURS:-30}"
ALERTED=0

# 告警出口：默认只写 stderr（cron 有 MAILTO 时会邮件）；配了钩子就交给平台。
# 钩子失败**不**改变备份结论——监控链路不通不应掩盖「备份本身成没成」。
alert() {
  local level="$1"; shift
  local msg="$*"
  echo "[backup] ALERT[$level] $msg" >&2
  if [ -n "${BACKUP_ALERT_CMD:-}" ]; then
    # shellcheck disable=SC2086
    "$BACKUP_ALERT_CMD" "$level" "$msg" || echo "[backup] WARN: BACKUP_ALERT_CMD 执行失败" >&2
  fi
}

on_exit() {
  local code=$?
  if [ "$code" -ne 0 ] && [ "$ALERTED" -eq 0 ]; then
    alert CRITICAL "数据库备份失败（exit=$code，目录=$BACKUP_DIR）—— 本次没有可用备份"
  fi
  exit "$code"
}
trap on_exit EXIT

mkdir -p "$BACKUP_DIR"
[ -n "$STATE_FILE" ] || STATE_FILE="$BACKUP_DIR/.last-success-epoch"

# 开场新鲜度检查：昨晚就没成功的话，今晚失败前先叫一声。
if [ -f "$STATE_FILE" ]; then
  LAST="$(cat "$STATE_FILE" 2>/dev/null || echo 0)"
  case "$LAST" in ''|*[!0-9]*) LAST=0 ;; esac
  if [ "$LAST" -gt 0 ]; then
    NOW_S="$(date +%s)"
    AGE_H=$(( (NOW_S - LAST) / 3600 ))
    if [ "$AGE_H" -gt "$STALE_HOURS" ]; then
      ALERTED=1
      alert WARN "距上次成功备份已 ${AGE_H} 小时（阈值 ${STALE_HOURS}h）—— 请查前几日 cron 日志"
      ALERTED=0
    fi
  fi
fi

TS="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/aicabinet-$TS.dump"
TMP="$OUT.tmp"

docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" -Fc > "$TMP"

SIZE=$(wc -c < "$TMP")
if [ "$SIZE" -lt "$MIN_BYTES" ]; then
  ALERTED=1
  alert CRITICAL "dump 仅 $SIZE 字节（阈值 ${MIN_BYTES}B），保留 $TMP 待查"
  exit 1
fi

mv "$TMP" "$OUT"
echo "[backup] ok $OUT ($((SIZE / 1024)) KB)"

DELETED=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'aicabinet-*.dump' -mtime "+$KEEP_DAYS" -print -delete | wc -l)
echo "[backup] retention=${KEEP_DAYS}d deleted=$DELETED"

# 异地副本：MinIO 在本机（卷不同、可被单独备份/同步）；容器不在则明确告警跳过。
if docker inspect "$MINIO_CONTAINER" >/dev/null 2>&1; then
  MU="$(docker exec "$MINIO_CONTAINER" printenv MINIO_ROOT_USER)"
  MP="$(docker exec "$MINIO_CONTAINER" printenv MINIO_ROOT_PASSWORD)"
  docker exec "$MINIO_CONTAINER" mc alias set backup-local http://127.0.0.1:9000 "$MU" "$MP" >/dev/null
  docker exec "$MINIO_CONTAINER" mc mb --ignore-existing "backup-local/$MINIO_BUCKET" >/dev/null
  NAME="$(basename "$OUT")"
  if docker exec -i "$MINIO_CONTAINER" mc pipe "backup-local/$MINIO_BUCKET/$NAME" < "$OUT" >/dev/null; then
    echo "[backup] offsite ok minio://$MINIO_BUCKET/$NAME"
  else
    ALERTED=1
    alert CRITICAL "本机备份已生成（$OUT），但 MinIO 上传失败 —— 异地副本缺失，单机故障即全失"
    exit 1
  fi
  docker exec "$MINIO_CONTAINER" mc rm --recursive --force --older-than "${OFFSITE_KEEP_DAYS}d"     "backup-local/$MINIO_BUCKET/" >/dev/null 2>&1 || true
else
  echo "[backup] WARN: 未发现 $MINIO_CONTAINER，跳过异地副本（单机故障即备份全失）" >&2
fi

# 只有真正走完全部步骤才更新时间戳：新鲜度检查据此判断「昨天到底成没成」。
date +%s > "$STATE_FILE"
echo "[backup] finished, state=$STATE_FILE"
