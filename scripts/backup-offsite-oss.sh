#!/usr/bin/env bash
# 备份异地归档：把本地 pg_dump 传到阿里云 OSS，并校验远端字节数。
#
# 用法（由 scripts/backup-database.sh 通过 BACKUP_REMOTE_CMD 调用）：
#   BACKUP_REMOTE_CMD='/data/apps/ai-cabinet/scripts/backup-offsite-oss.sh'
#   bash scripts/backup-offsite-oss.sh /var/backups/aicabinet/20261118-172213/aicabinet.dump
#
# 环境变量（cron 里由 /etc/aicabinet-backup.env 注入）：
#   OSS_BACKUP_BUCKET       默认 ai-cabinet-by
#   OSS_BACKUP_PREFIX       默认 backups/aicabinet
#   OSS_BACKUP_REGION       默认 cn-shenzhen
#   OSS_BACKUP_ENDPOINT     默认 oss-${OSS_BACKUP_REGION}.aliyuncs.com
#   OSS_BACKUP_ACCESS_KEY_ID / OSS_BACKUP_ACCESS_KEY_SECRET
#       未设置时回退 JIANGYI_OSS_ACCESS_KEY_ID / JIANGYI_OSS_ACCESS_KEY_SECRET
#   OSS_BACKUP_ROLE_ARN      可选；设置则用 STS AssumeRole 换临时凭据再上传
#       （将邑子账号只有 AssumeRole 权限，直写任何 OSS 前缀都会 403；
#        备份策略授给角色 jiangyivideoupload 时走这条）
#   OSS_BACKUP_ROLE_SESSION  默认 aicabinet-backup
#
# 纪律：
#   - 上传成功不等于归档成功：必须再 stat 一次远端对象，字节数与本地一致才退出 0；
#   - 任何一步失败非零退出（backup-database.sh 会因此判备份失败并发告警）；
#   - 绝不打印 AK/SK，只打印 bucket/key/size。
#
# 自测：bash scripts/backup-offsite-oss.sh --self-test
set -Eeuo pipefail

TAG='[backup-offsite-oss]'
log() { echo "$TAG $*"; }
die() { echo "$TAG ERROR: $*" >&2; exit 1; }

# 与 backup-database.sh 的默认值保持一致；生产以 env 文件为准
OSS_BACKUP_BUCKET="${OSS_BACKUP_BUCKET:-ai-cabinet-by}"
OSS_BACKUP_PREFIX="${OSS_BACKUP_PREFIX:-backups/aicabinet}"
OSS_BACKUP_REGION="${OSS_BACKUP_REGION:-cn-shenzhen}"
OSS_BACKUP_ENDPOINT="${OSS_BACKUP_ENDPOINT:-oss-${OSS_BACKUP_REGION}.aliyuncs.com}"

remote_key() {
  # 保留本地时间戳目录（20261118-175751/aicabinet.dump），否则每天覆盖同一个 key
  local name dir
  name="$(basename "$1")"
  dir="$(basename "$(dirname "$1")")"
  case "$name" in
    *[\/*]*) die "非法文件名: $name" ;;
  esac
  # 目录名必须形如 YYYYMMDD-HHMMSS，其他情况退回平铺，避免把任意路径带进 OSS
  if ! printf '%s' "$dir" | grep -qE '^[0-9]{8}-[0-9]{6}$'; then
    dir=""
  fi
  if [ -n "$dir" ]; then
    echo "${OSS_BACKUP_PREFIX%/}/$dir/$name"
  else
    echo "${OSS_BACKUP_PREFIX%/}/$name"
  fi
}

upload_and_verify() {
  local file="$1"
  [ -f "$file" ] || die "本地备份文件不存在: $file"
  local size ak sk token=""
  ak="${OSS_BACKUP_ACCESS_KEY_ID:-${JIANGYI_OSS_ACCESS_KEY_ID:-}}"
  sk="${OSS_BACKUP_ACCESS_KEY_SECRET:-${JIANGYI_OSS_ACCESS_KEY_SECRET:-}}"
  size="$(wc -c < "$file" | tr -d ' ')"
  [ "$size" -gt 0 ] || die "本地备份为空: $file"
  command -v ossutil >/dev/null 2>&1 || die "ossutil 未安装（/usr/local/bin/ossutil）"
  [ -n "$ak" ] && [ -n "$sk" ] || die "缺少 OSS 访问凭据（OSS_BACKUP_ACCESS_KEY_ID/SECRET）"

  # 子账号只有 AssumeRole 权限时，先换临时凭据（临时 AK/SK/Token 只在本次进程内存里）
  if [ -n "${OSS_BACKUP_ROLE_ARN:-}" ]; then
    command -v aliyun >/dev/null 2>&1 || die "配置了 OSS_BACKUP_ROLE_ARN 但未安装 aliyun CLI"
    command -v python3 >/dev/null 2>&1 || die "配置了 OSS_BACKUP_ROLE_ARN 但未安装 python3"
    local sts
    if ! sts="$(aliyun sts AssumeRole --RoleArn "$OSS_BACKUP_ROLE_ARN" \
        --RoleSessionName "${OSS_BACKUP_ROLE_SESSION:-aicabinet-backup}" \
        --DurationSeconds 3600 --cli-output json 2>/dev/null)"; then
      die "STS AssumeRole 失败（检查 RAM 角色信任与子账号授权）"
    fi
    ak="$(printf '%s' "$sts" | python3 -c "import sys,json;print(json.load(sys.stdin)['Credentials']['AccessKeyId'])" 2>/dev/null)" \
      || die "STS 返回无法解析"
    sk="$(printf '%s' "$sts" | python3 -c "import sys,json;print(json.load(sys.stdin)['Credentials']['AccessKeySecret'])" 2>/dev/null)" \
      || die "STS 返回无法解析"
    token="$(printf '%s' "$sts" | python3 -c "import sys,json;print(json.load(sys.stdin)['Credentials']['SecurityToken'])" 2>/dev/null)" \
      || die "STS 返回无法解析"
    [ -n "$ak" ] && [ -n "$sk" ] && [ -n "$token" ] || die "STS 临时凭据不完整"
  fi

  local auth=(-i "$ak" -k "$sk")
  [ -n "$token" ] && auth+=(-t "$token")

  local key remote
  key="$(remote_key "$file")"
  remote="oss://${OSS_BACKUP_BUCKET}/${key}"

  # 显式判退出码：函数被 if 调用时 set -e 不生效（bash 语义），不能靠它兜底
  if ! ossutil cp "$file" "$remote" -f \
    -e "$OSS_BACKUP_ENDPOINT" --region "$OSS_BACKUP_REGION" \
    "${auth[@]}" >/dev/null; then
    die "上传失败: $remote"
  fi

  # 远端字节数校验：stat 输出是 JSON，取 Content-Length
  local meta remote_size
  if ! meta="$(ossutil stat "$remote" \
    -e "$OSS_BACKUP_ENDPOINT" --region "$OSS_BACKUP_REGION" \
    "${auth[@]}" 2>/dev/null)"; then
    die "远端对象 stat 失败: $remote"
  fi
  # stat 默认输出是表格（Content-Length : 123），也兼容 JSON 形态
  remote_size="$(printf '%s' "$meta" | sed -nE 's/.*Content-Length[^0-9]*([0-9]+).*/\1/p' | head -1)"
  [ -n "$remote_size" ] || die "无法从 stat 解析 Content-Length: $remote"
  [ "$remote_size" = "$size" ] || die "远端字节数不一致 local=$size remote=$remote_size ($remote)"

  log "offsite ok bucket=$OSS_BACKUP_BUCKET key=$key size=$size"
}

self_test() {
  local tmp ok=0 bad=0
  mkdir -p .tmp
  tmp="$(mktemp -d .tmp/oss-selftest.XXXXXX)"
  # 自测临时目录不删除（safe-delete 会拦 rm -rf），挪到 .tmp/ 下留痕即可
  trap 'mv "${tmp:-}" ".tmp/trash-oss-selftest.$$" 2>/dev/null || true' EXIT
  mkdir -p "$tmp/bin"
  printf 'dumpdata' > "$tmp/aicabinet.dump"

  # 假 ossutil：cp 成功；stat 回显 Content-Length=8
  cat > "$tmp/bin/ossutil" <<'FAKE'
#!/usr/bin/env bash
case "$1" in
  cp) exit 0 ;;
  stat) echo '{"Content-Length":"8","ObjectKey":"x"}'; exit 0 ;;
  *) exit 0 ;;
esac
FAKE
  chmod +x "$tmp/bin/ossutil"

  if ( PATH="$tmp/bin:$PATH" OSS_BACKUP_ACCESS_KEY_ID=x OSS_BACKUP_ACCESS_KEY_SECRET=y \
      upload_and_verify "$tmp/aicabinet.dump" ); then
    ok=$((ok + 1))
  else
    echo "$TAG self-test: 正常上传+字节一致被误判失败"
  fi

  # 远端字节数不一致必须判红
  cat > "$tmp/bin/ossutil" <<'FAKE'
#!/usr/bin/env bash
case "$1" in
  cp) exit 0 ;;
  stat) echo '{"Content-Length":"7"}'; exit 0 ;;
  *) exit 0 ;;
esac
FAKE
  chmod +x "$tmp/bin/ossutil"
  if ( PATH="$tmp/bin:$PATH" OSS_BACKUP_ACCESS_KEY_ID=x OSS_BACKUP_ACCESS_KEY_SECRET=y \
      upload_and_verify "$tmp/aicabinet.dump" ); then
    echo "$TAG self-test: 远端字节数不一致未判红"
  else
    bad=$((bad + 1))
  fi

  # 上传失败必须判红
  cat > "$tmp/bin/ossutil" <<'FAKE'
#!/usr/bin/env bash
case "$1" in
  cp) echo "denied" >&2; exit 2 ;;
  stat) echo '{"Content-Length":"8"}'; exit 0 ;;
  *) exit 0 ;;
esac
FAKE
  chmod +x "$tmp/bin/ossutil"
  if ( PATH="$tmp/bin:$PATH" OSS_BACKUP_ACCESS_KEY_ID=x OSS_BACKUP_ACCESS_KEY_SECRET=y \
      upload_and_verify "$tmp/aicabinet.dump" ); then
    echo "$TAG self-test: 上传失败未判红"
  else
    bad=$((bad + 1))
  fi

  # 缺凭据必须判红（不允许静默只留本地）
  if ( PATH="$tmp/bin:$PATH" OSS_BACKUP_ACCESS_KEY_ID= OSS_BACKUP_ACCESS_KEY_SECRET= \
      JIANGYI_OSS_ACCESS_KEY_ID= JIANGYI_OSS_ACCESS_KEY_SECRET= \
      upload_and_verify "$tmp/aicabinet.dump" ); then
    echo "$TAG self-test: 缺凭据未判红"
  else
    bad=$((bad + 1))
  fi

  # 配了 ROLE_ARN：应走 AssumeRole 换临时凭据后上传
  cat > "$tmp/bin/aliyun" <<'FAKE'
#!/usr/bin/env bash
echo '{"Credentials":{"AccessKeyId":"tmp-ak","AccessKeySecret":"tmp-sk","SecurityToken":"tmp-token"}}'
FAKE
  chmod +x "$tmp/bin/aliyun"
  cat > "$tmp/bin/ossutil" <<'FAKE'
#!/usr/bin/env bash
case "$1" in
  cp) exit 0 ;;
  stat) echo '{"Content-Length":"8"}'; exit 0 ;;
  *) exit 0 ;;
esac
FAKE
  chmod +x "$tmp/bin/ossutil"
  if ( PATH="$tmp/bin:$PATH" OSS_BACKUP_ACCESS_KEY_ID=x OSS_BACKUP_ACCESS_KEY_SECRET=y \
      OSS_BACKUP_ROLE_ARN=acs:ram::1:role/test upload_and_verify "$tmp/aicabinet.dump" ); then
    ok=$((ok + 1))
  else
    echo "$TAG self-test: AssumeRole 绿路径被误判失败"
  fi

  if [ "$ok" -eq 2 ] && [ "$bad" -eq 3 ]; then
    log "OK：2 条绿（直连/AssumeRole）+ 3 条红（字节不一致/上传失败/缺凭据）"
    return 0
  fi
  echo "$TAG FAIL：self-test 结果不符（ok=$ok bad=$bad）"
  return 1
}

if [ "${1:-}" = '--self-test' ]; then
  self_test
  exit $?
fi

[ $# -eq 1 ] || die "用法: $0 <本地备份文件路径>"
upload_and_verify "$1"
