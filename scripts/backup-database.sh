#!/usr/bin/env bash
# AU-189（2026-10-18 批次 7）：Linux 服务器侧数据库备份（替代 backup-database.ps1 的生产职责）。
# 旧 Windows 脚本三大缺陷（第三路审计 AU-189）：① Windows-only 与 Linux 生产错配；
# ② pg_dump 失败仅 Write-Warning 不查退出码 ⇒ 可能长期「零备份」而显示成功；
# ③ 'wal' 分支只打印配置提示。
#
# 本脚本纪律：
#   - 任何一步失败立即非零退出（set -Eeuo pipefail + 显式判据）；
#   - pg_dump 产物先落临时文件并验证非空/可 gzip，成功才挪到目标目录（原子换名）；
#   - MinIO/wal-g 归属部署侧配置（BACKUP_REMOTE_CMD 钩子），未配置时告警但不静默假装成功；
#   - cron 示例：10 2 * * * /opt/ai-cabinet/scripts/backup-database.sh >> /var/log/aicabinet-backup.log 2>&1
#
# 环境变量（均可被 env 覆盖；默认与 infra compose 对齐）：
#   PG_CONTAINER   容器名（默认 ai-cabinet-postgres-1）
#   PG_USER        数据库用户（默认 aicabinet）
#   PG_DB          数据库（默认 aicabinet）
#   BACKUP_DIR     备份目录（默认 /var/backups/aicabinet）
#   KEEP_DAYS      本地保留天数（默认 7）
#   BACKUP_REMOTE_CMD  远端归档钩子（可选；收到的参数 = 本地备份文件路径）
set -Eeuo pipefail

PG_CONTAINER="${PG_CONTAINER:-ai-cabinet-postgres-1}"
PG_USER="${PG_USER:-aicabinet}"
PG_DB="${PG_DB:-aicabinet}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/aicabinet}"
KEEP_DAYS="${KEEP_DAYS:-7}"
BACKUP_REMOTE_CMD="${BACKUP_REMOTE_CMD:-}"

STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET_DIR="$BACKUP_DIR/$STAMP"
TMP_FILE="$TARGET_DIR/${PG_DB}.dump.tmp"
FINAL_FILE="$TARGET_DIR/${PG_DB}.dump"

log() { echo "[$(date '+%F %T')] $*"; }
die() { log "ERROR: $*"; exit 1; }

mkdir -p "$TARGET_DIR"

log "pg_dump container=$PG_CONTAINER db=$PG_DB -> $FINAL_FILE"
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" -Fc --no-owner > "$TMP_FILE" \
  || die "pg_dump exited non-zero —— 备份未生成（严禁吞错继续）"

SIZE="$(stat -c%s "$TMP_FILE" 2>/dev/null || stat -f%z "$TMP_FILE")"
[ "$SIZE" -ge 1024 ] || die "pg_dump 产物仅 ${SIZE} 字节，疑似空库/失败 —— 拒绝入库备份集"
gzip -t "$TMP_FILE" 2>/dev/null || true   # -Fc 自带压缩，gzip -t 仅对非 -Fc 产物有意义；失败不致命
mv "$TMP_FILE" "$FINAL_FILE"
log "local backup ok size=${SIZE}B file=$FINAL_FILE"

if [ -n "$BACKUP_REMOTE_CMD" ]; then
  log "remote archive: $BACKUP_REMOTE_CMD $FINAL_FILE"
  "$BACKUP_REMOTE_CMD" "$FINAL_FILE" || die "远端归档失败（本地备份已保留，但异地副本缺失）"
else
  log "WARN: BACKUP_REMOTE_CMD 未配置 —— 仅有本机副本；单机故障即备份全失。"
  log "WARN: 请配置异地归档（MinIO mc cp / wal-g / rsync 等）后重跑，或确认本机副本满足 RPO。"
fi

# 本地保留窗口：原子完成才清理，失败不影响本次备份有效性
find "$BACKUP_DIR" -maxdepth 1 -mindepth 1 -type d -name '20*' -mtime "+$KEEP_DAYS" -exec rm -rf {} + 2>/dev/null \
  || log "WARN: 旧备份清理失败（不影响本次备份）"

log "backup finished dir=$TARGET_DIR"
