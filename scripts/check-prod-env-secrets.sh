#!/usr/bin/env bash
# 生产环境密钥前置校验（上线清单 P0「不得保留仓库默认密钥」的部署期兜底）。
#
# 背景：infra/docker-compose.yml 给 postgres/redis/minio/kafka/grafana 都写了
# `${KEY:-开发默认值}`。生产链若漏配 .env.production，容器会静默用 dev 默认密码，
# 而 deploy-prod.sh 原样 build+up，没有任何一处会红。
#
# 用法：
#   bash scripts/check-prod-env-secrets.sh infra/.env.production
#   bash scripts/check-prod-env-secrets.sh --self-test
#
# 纪律：只输出键名，绝不打印值。
set -uo pipefail

TAG='[check-prod-env-secrets]'
TMP_DIR=''
FILE="${1:-infra/.env.production}"

# 必需项：缺失或仍是开发默认值都判红
REQUIRED_KEYS='POSTGRES_PASSWORD REDIS_PASSWORD MINIO_ROOT_PASSWORD'
# 开发默认值 denylist（键名|默认值，可多值用空格分隔）
DENY_LIST='POSTGRES_PASSWORD|aicabinet
REDIS_PASSWORD|devredis
MINIO_ROOT_PASSWORD|minioadmin
KAFKA_TRADE_PASSWORD|dev-kafka-trade
KAFKA_ADMIN_PASSWORD|dev-kafka-admin
GRAFANA_ADMIN_PASSWORD|admin
JWT_SECRET|ci-openapi-check-jwt-secret-32bytes-min dev-jwt-secret-change-me
INTERNAL_API_KEY|dev-internal-key-change-me ci-internal-api-key
AICABINET_OPS_ADMIN_PASSWORD|123456 admin'
# 至少 32 字符的密钥
MIN_LEN_KEYS='JWT_SECRET JIANGYI_JWT_SECRET'

read_key() {
  # 同名键取最后一次（dotenv 语义），去 CR 与首尾空白
  grep -E "^[[:space:]]*$1[[:space:]]*=" "$2" 2>/dev/null |
    tail -1 |
    sed -E "s/^[^=]*=//" |
    tr -d '\r' |
    sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

check_file() {
  local file="$1"
  local fail=0
  for key in $REQUIRED_KEYS; do
    if [ -z "$(read_key "$key" "$file")" ]; then
      echo "$TAG ❌ $key 为空或未配置"
      fail=1
    fi
  done
  while IFS='|' read -r key defaults; do
    [ -z "$key" ] && continue
    local val
    val="$(read_key "$key" "$file")"
    [ -z "$val" ] && continue # 非必需项留空 = 功能关闭，不算默认值泄露
    for d in $defaults; do
      if [ "$val" = "$d" ]; then
        echo "$TAG ❌ $key 仍是开发默认值"
        fail=1
      fi
    done
  done <<EOF
$DENY_LIST
EOF
  for key in $MIN_LEN_KEYS; do
    local val
    val="$(read_key "$key" "$file")"
    [ -z "$val" ] && continue
    if [ "${#val}" -lt 32 ]; then
      echo "$TAG ❌ $key 长度 <32"
      fail=1
    fi
  done
  return "$fail"
}

self_test() {
  local tmp
  tmp="$(mktemp -d)"
  TMP_DIR="$tmp"
  trap 'rm -rf "${TMP_DIR:-}"' EXIT
  local ok=0 bad=0

  cat > "$tmp/good.env" <<'ENV'
POSTGRES_PASSWORD=prod-pg-pass-9f2
REDIS_PASSWORD=prod-redis-pass-7a1
MINIO_ROOT_PASSWORD=prod-minio-pass-3c8
JWT_SECRET=prod-jwt-secret-0123456789abcdefghijklmn
JIANGYI_JWT_SECRET=prod-jy-jwt-secret-0123456789abcdefgh
ENV
  if check_file "$tmp/good.env" >/dev/null; then ok=$((ok + 1)); else echo "$TAG self-test: good.env 误判红"; fi

  for case in \
    'POSTGRES_PASSWORD=aicabinet' \
    'REDIS_PASSWORD=devredis' \
    'MINIO_ROOT_PASSWORD=minioadmin' \
    'JWT_SECRET=ci-openapi-check-jwt-secret-32bytes-min' \
    'INTERNAL_API_KEY=dev-internal-key-change-me'; do
    key="${case%%=*}"
    val="${case#*=}"
    cat > "$tmp/bad.env" <<ENV
POSTGRES_PASSWORD=prod-pg-pass-9f2
REDIS_PASSWORD=prod-redis-pass-7a1
MINIO_ROOT_PASSWORD=prod-minio-pass-3c8
$key=$val
ENV
    if check_file "$tmp/bad.env" >/dev/null; then
      echo "$TAG self-test: $key 默认值未判红"
    else
      bad=$((bad + 1))
    fi
  done

  cat > "$tmp/empty.env" <<'ENV'
POSTGRES_PASSWORD=
REDIS_PASSWORD=prod-redis-pass-7a1
MINIO_ROOT_PASSWORD=prod-minio-pass-3c8
ENV
  if check_file "$tmp/empty.env" >/dev/null; then
    echo "$TAG self-test: 空 POSTGRES_PASSWORD 未判红"
  else
    bad=$((bad + 1))
  fi

  if [ "$ok" -eq 1 ] && [ "$bad" -eq 6 ]; then
    echo "$TAG OK：基线绿 + 6 类红场景逐个命中"
    return 0
  fi
  echo "$TAG FAIL：self-test 结果不符（ok=$ok bad=$bad）"
  return 1
}

if [ "${1:-}" = '--self-test' ]; then
  self_test
  exit $?
fi

if [ ! -f "$FILE" ]; then
  echo "$TAG ❌ 缺 $FILE（生产环境必须显式提供密钥）"
  exit 2
fi

if check_file "$FILE"; then
  echo "$TAG OK：生产密钥必需项齐全且非开发默认值"
  exit 0
fi
echo "$TAG FAIL：修复上方键名后重试（本脚本只读、不打印值）"
exit 1
