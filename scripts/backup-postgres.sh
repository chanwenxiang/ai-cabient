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
    echo "[backup] FAIL: 本机备份已生成，但 MinIO 上传失败" >&2
    exit 1
  fi
  docker exec "$MINIO_CONTAINER" mc rm --recursive --force --older-than "${OFFSITE_KEEP_DAYS}d"     "backup-local/$MINIO_BUCKET/" >/dev/null 2>&1 || true
else
  echo "[backup] WARN: 未发现 $MINIO_CONTAINER，跳过异地副本" >&2
fi
