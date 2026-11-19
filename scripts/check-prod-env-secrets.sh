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
# F1（收敛计划 2026-11-17）追加一条：FEISHU_WEBHOOK_URL 为空即判红。
# 理由与上面不同：它不泄露任何东西，只是**整条告警链路会静默失效**（桥对每次投递返 503，
# 而 relay/alertmanager/prometheus 全部"健康"）。要无告警上线必须显式 ALERTS_DISABLED_ACK=1，
# 让「已知情」留下痕迹，而不是让默认值替人做决定。
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
# 告警投递（F1 2026-11-17 飞书 → 2026-11-19 改 SMTP 邮件）：
# 监控栈告警经 Alertmanager 原生 email_configs 发信；这组 key 缺任一 ⇒ alertmanager 容器
# 启动时 fail-closed（见 compose 的 command 检查），告警链路不可用。
# 不给默认放行：要么配齐 SMTP 组，要么**显式**声明知情（ALERTS_DISABLED_ACK=1，打印横幅留痕）。
# 注意：应用内业务告警（ops.alert.email.* 在数据库 system_config）与本组无关，别混。
ALERT_DELIVERY_KEYS='SMTP_HOST SMTP_PORT SMTP_USER SMTP_PASS SMTP_FROM SMTP_TO'
ALERTS_ACK_ENV='ALERTS_DISABLED_ACK'
# 备份异地（F7/L-04）：BACKUP_REMOTE_CMD 为空时备份只在本机，机器/盘挂了就一起没了。
# 与告警同款：要么配置外部 OSS 钩子，要么显式 BACKUP_LOCAL_ONLY_ACK=1 声明「已知只有本地」。
BACKUP_REMOTE_KEY='BACKUP_REMOTE_CMD'
BACKUP_LOCAL_ACK='BACKUP_LOCAL_ONLY_ACK'

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
  local alert_missing=''
  for key in $ALERT_DELIVERY_KEYS; do
    [ -z "$(read_key "$key" "$file")" ] && alert_missing="$alert_missing $key"
  done
  if [ -n "$alert_missing" ]; then
    if [ "${!ALERTS_ACK_ENV:-}" = '1' ]; then
      echo "$TAG ⚠️  监控告警 SMTP 组缺键:$alert_missing，且已显式 $ALERTS_ACK_ENV=1 ——"
      echo "$TAG    本次上线期间 **监控告警无人接收**（alertmanager 会拒绝启动）。这是知情决定，不是修复。"
    else
      echo "$TAG ❌ 监控告警 SMTP 组缺键:$alert_missing：alertmanager 会 fail-closed 拒绝启动，整条监控告警链路静默失效"
      echo "$TAG    修法：按 infra/.env.production.example 配齐 SMTP_HOST/PORT/USER/PASS/FROM/TO；"
      echo "$TAG          确知要无告警上线，则显式设置 $ALERTS_ACK_ENV=1 重跑（会留横幅）"
      fail=1
    fi
  fi
  if [ -z "$(read_key "$BACKUP_REMOTE_KEY" "$file")" ]; then
    if [ "${!BACKUP_LOCAL_ACK:-}" = '1' ]; then
      echo "$TAG ⚠️  $BACKUP_REMOTE_KEY 为空，且已显式 $BACKUP_LOCAL_ACK=1 ——"
      echo "$TAG    备份只落本机，机器/磁盘故障会连同备份一起丢。这是知情决定，不是修复。"
    else
      echo "$TAG ❌ $BACKUP_REMOTE_KEY 为空：备份只有本机副本，不满足异地要求"
      echo "$TAG    修法：在 infra/.env.production 配置外部 OSS 归档命令；"
      echo "$TAG          确知只留本地，则显式设置 $BACKUP_LOCAL_ACK=1 重跑（会留横幅）"
      fail=1
    fi
  fi
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
SMTP_HOST=smtp.qiye.aliyun.com
SMTP_PORT=465
SMTP_USER=alert@example.com
SMTP_PASS=prod-smtp-auth-code
SMTP_FROM=alert@example.com
SMTP_TO=ops@example.com
BACKUP_REMOTE_CMD=aws s3 cp
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

  # 告警投递：SMTP 组缺键必须判红（隐式放行 = 监控全静默）；显式知情才放行，且必须仍算「绿」。
  cat > "$tmp/no-alert.env" <<'ENV'
POSTGRES_PASSWORD=prod-pg-pass-9f2
REDIS_PASSWORD=prod-redis-pass-7a1
MINIO_ROOT_PASSWORD=prod-minio-pass-3c8
JWT_SECRET=prod-jwt-secret-0123456789abcdefghijklmn
JIANGYI_JWT_SECRET=prod-jy-jwt-secret-0123456789abcdefgh
SMTP_HOST=
SMTP_PORT=465
SMTP_USER=alert@example.com
SMTP_PASS=prod-smtp-auth-code
SMTP_FROM=alert@example.com
SMTP_TO=ops@example.com
BACKUP_REMOTE_CMD=aws s3 cp
ENV
  if check_file "$tmp/no-alert.env" >/dev/null; then
    echo "$TAG self-test: SMTP 组缺键未判红（告警静默被放行）"
  else
    bad=$((bad + 1))
  fi

  if ALERTS_DISABLED_ACK=1 check_file "$tmp/no-alert.env" >/dev/null; then
    ok=$((ok + 1))
  else
    echo "$TAG self-test: 显式 ALERTS_DISABLED_ACK=1 仍判红（知情覆盖通道失效）"
  fi

  # 备份异地：空 BACKUP_REMOTE_CMD 必须判红；显式 BACKUP_LOCAL_ONLY_ACK=1 才放行。
  cat > "$tmp/no-backup.env" <<'ENV'
POSTGRES_PASSWORD=prod-pg-pass-9f2
REDIS_PASSWORD=prod-redis-pass-7a1
MINIO_ROOT_PASSWORD=prod-minio-pass-3c8
JWT_SECRET=prod-jwt-secret-0123456789abcdefghijklmn
JIANGYI_JWT_SECRET=prod-jy-jwt-secret-0123456789abcdefgh
SMTP_HOST=smtp.qiye.aliyun.com
SMTP_PORT=465
SMTP_USER=alert@example.com
SMTP_PASS=prod-smtp-auth-code
SMTP_FROM=alert@example.com
SMTP_TO=ops@example.com
BACKUP_REMOTE_CMD=
ENV
  if check_file "$tmp/no-backup.env" >/dev/null; then
    echo "$TAG self-test: 空 BACKUP_REMOTE_CMD 未判红（本地单副本被放行）"
  else
    bad=$((bad + 1))
  fi

  if BACKUP_LOCAL_ONLY_ACK=1 check_file "$tmp/no-backup.env" >/dev/null; then
    ok=$((ok + 1))
  else
    echo "$TAG self-test: 显式 BACKUP_LOCAL_ONLY_ACK=1 仍判红（知情覆盖通道失效）"
  fi

  if [ "$ok" -eq 3 ] && [ "$bad" -eq 8 ]; then
    echo "$TAG OK：基线绿 + 告警/备份显式知情绿 + 8 类红场景逐个命中"
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
