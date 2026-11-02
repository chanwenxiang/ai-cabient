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
#   · 只做本机备份；异地副本（OSS/MinIO）需另行配置，见上线清单 P0 高可用项
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-ai-cabinet-postgres-1}"
PG_USER="${PG_USER:-aicabinet}"
PG_DB="${PG_DB:-aicabinet}"
BACKUP_DIR="${BACKUP_DIR:-/data/backups}"
KEEP_DAYS="${KEEP_DAYS:-7}"
MIN_BYTES=$((64 * 1024))

mkdir -p "$BACKUP_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/aicabinet-$TS.dump"
TMP="$OUT.tmp"

docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" -Fc > "$TMP"

SIZE=$(wc -c < "$TMP")
if [ "$SIZE" -lt "$MIN_BYTES" ]; then
  echo "[backup] FAIL: dump 仅 $SIZE 字节，保留 $TMP 待查" >&2
  exit 1
fi

mv "$TMP" "$OUT"
echo "[backup] ok $OUT ($((SIZE / 1024)) KB)"

DELETED=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'aicabinet-*.dump' -mtime "+$KEEP_DAYS" -print -delete | wc -l)
echo "[backup] retention=${KEEP_DAYS}d deleted=$DELETED"
