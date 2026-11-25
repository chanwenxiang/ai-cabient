#!/usr/bin/env bash
# 生产一键部署：本地与 GitHub Actions deploy-prod workflow 共用同一条路径。
#
# 流程（2026-10-13 实战验证版）：
#   1. git archive HEAD 打源码归档（工作区未提交内容不会进部署，先检查干净）
#   2. scp 上传 → 服务器原地解包（目录 bind mount inode 不变，无需重建 gateway）
#   3. 清理 admin 陈旧哈希资产（解包不删文件，历次部署会积攒旧 js）
#   4. 服务器现场按 SHA 构建 trade/device/jiangyi → 按副本数重建并逐个验镜像与健康
#   5. 重建 gateway/HAProxy → 验证 admin 与业务可达
#
# 铁律防护：
#   - 只动应用与网关，绝不重建 postgres/redis/minio（memory 铁律 69）
#   - 单文件 bind mount 改动必须 force-recreate，reload 无效（铁律家族）
#   - 工作区必须干净（archive 只打包 HEAD，脏工作区会造成「部署的不是你以为的代码」）
#
# 用法：
#   bash scripts/deploy-prod.sh                 # 部署本地 HEAD
#   PROD_HOST=1.2.3.4 PROD_USER=root bash ...   # 覆盖目标
# 环境变量：PROD_HOST / PROD_USER / PROD_DIR / PROD_SSH_KEY / PROD_PORT
#           TRADE_REPLICAS / DEVICE_REPLICAS / JIANGYI_REPLICAS（默认 1，正式 HA trade=2）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROD_HOST_EXPLICIT="${PROD_HOST:-}"
PROD_USER_EXPLICIT="${PROD_USER:-}"
PROD_HOST="${PROD_HOST:-120.25.246.179}"
PROD_USER="${PROD_USER:-root}"
PROD_DIR="${PROD_DIR:-/data/apps/ai-cabinet}"
PROD_SSH_KEY="${PROD_SSH_KEY:-$HOME/.ssh/aicabinet-prod.pem}"
PROD_SSH_KEY="${PROD_SSH_KEY/#\~/$HOME}"   # Actions 场景传 ~ 开头路径，变量内不会自动展开
PROD_PORT="${PROD_PORT:-22}"
TRADE_REPLICAS="${TRADE_REPLICAS:-1}"
DEVICE_REPLICAS="${DEVICE_REPLICAS:-1}"
JIANGYI_REPLICAS="${JIANGYI_REPLICAS:-1}"

if [ -z "$PROD_HOST_EXPLICIT" ] || [ -z "$PROD_USER_EXPLICIT" ]; then
  echo "⚠️ 使用内置默认目标 ${PROD_USER}@${PROD_HOST}（当前为测试服务器）。生产部署必须显式传 PROD_HOST/PROD_USER；生产口径定案前不得把默认值当生产地址。"
fi

# 注意：ssh 端口是小写 -p，scp 是大写 -P（scp -p=保留时间戳，端口会被当成本地文件）
SSH_OPTS=(-i "$PROD_SSH_KEY" -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new -o BatchMode=yes)
SSH_CMD=("$PROD_USER@$PROD_HOST")

echo "── [0/7] 前置检查 ──"
[ -f "$PROD_SSH_KEY" ] || { echo "❌ SSH 私钥不存在: $PROD_SSH_KEY"; exit 1; }
# AU-185：compose 链引用的每个 -f 文件必须已入库（2026-10-13 部署时 prod-server.yml
# 只存在于服务器，仓内缺失导致不可复现；现为入库文件，此处校验防回归）
for f in docker-compose.yml docker-compose.apps.yml docker-compose.production.yml docker-compose.prod-server.yml; do
  [ -f "infra/$f" ] || { echo "❌ compose 链文件缺失: infra/$f（deploy-prod.sh 引用了它）"; exit 1; }
done
DIRTY="$(git status --porcelain | grep -v '^??' || true)"
if [ -n "$DIRTY" ]; then
  echo "❌ 工作区有未提交改动（archive 只打包 HEAD，部署的会不是你看到的代码）："
  echo "$DIRTY"
  echo "   先 commit（或 stash）再部署。"
  exit 1
fi
SHA="$(git rev-parse HEAD)"
SHA_SHORT="$(git rev-parse --short HEAD)"
echo "✅ 工作区干净，部署 HEAD = $SHA_SHORT ($SHA)"

echo "── [1/7] 打源码归档 ──"
TARBALL=".tmp/prod-src-${SHA}.tar.gz"
mkdir -p .tmp
git archive --format=tar.gz -o "$TARBALL" HEAD
echo "✅ $(du -h "$TARBALL" | cut -f1) → $TARBALL"

echo "── [2/7] 上传 ${PROD_HOST} ──"
# 2026-11-22：GitHub runner→服务器路由偶发劣化到 ~16KB/s（271MB 包 50 分钟只传 18%，
# deploy job 撞 50min 超时被取消，孤儿 scp 残留）。加「同 SHA 同大小⇒跳过上传」：
# 服务器已有完整同尺寸包（如人工从本机预传/上次部署残留完整包）时直接复用；
# 尺寸不符（半截包）则覆盖重传，fail-closed 不认残包。
REMOTE_SIZE=$(ssh "${SSH_OPTS[@]}" -p "$PROD_PORT" "${SSH_CMD[0]}" "stat -c %s /data/apps/prod-src-${SHA}.tar.gz 2>/dev/null || echo 0")
LOCAL_SIZE=$(stat -c %s "$TARBALL")
if [ "$REMOTE_SIZE" = "$LOCAL_SIZE" ] && [ "$LOCAL_SIZE" != "0" ]; then
  echo "✅ 服务器已有同 SHA 同大小（${LOCAL_SIZE} 字节）的源码包，跳过上传"
else
  echo "上传 ${LOCAL_SIZE} 字节（服务器现有 ${REMOTE_SIZE}）…"
  scp "${SSH_OPTS[@]}" -P "$PROD_PORT" "$TARBALL" "${SSH_CMD[0]}:/data/apps/prod-src-${SHA}.tar.gz"
  echo "✅ 上传完成"
fi

echo "── [3/7] 服务器解包 + 清理陈旧 admin 资产 ──"
ADMIN_REL="services/trade-service/src/main/resources/static/admin"
ssh "${SSH_OPTS[@]}" -p "$PROD_PORT" "${SSH_CMD[0]}" bash -s -- "$SHA" "$PROD_DIR" << 'REMOTE'
set -euo pipefail
SHA="$1"; DIR="$2"
cd "$DIR"
tar -xzf "/data/apps/prod-src-${SHA}.tar.gz"
ADMIN_REL="services/trade-service/src/main/resources/static/admin"
tar -tzf "/data/apps/prod-src-${SHA}.tar.gz" "${ADMIN_REL}/" \
  | sed "s#^${ADMIN_REL}/##" | grep -v '^$' | sort > /tmp/admin-keep.txt
cd "$ADMIN_REL"
find . -type f | sed 's#^\./##' | sort > /tmp/admin-have.txt
STALE=$(comm -23 /tmp/admin-have.txt /tmp/admin-keep.txt | wc -l)
if [ "$STALE" -gt 0 ]; then
  comm -23 /tmp/admin-have.txt /tmp/admin-keep.txt | xargs -d '\n' rm -f
  echo "已清理 ${STALE} 个陈旧 admin 资产"
fi
rm -f /tmp/admin-keep.txt /tmp/admin-have.txt
echo "✅ 解包完成，admin 资产 $(find . -type f | wc -l) 个文件"
# 2026-10-14 教训：解包只叠加，上一版部署遗留的旧 .java/.sql 会进服务器编译
# （本次：要货下线删掉的旧测试文件让现场 mvn 编译挂）。按 tar 清单清理源码树残留。
# 2026-11-02 修：不要依赖 comm 的目录/排序；逐文件 grep -Fxq，兼容空格文件名。
# 🔴 2026-11-06 修：上面 admin 清理把 cwd 切到了 $ADMIN_REL 且**从没切回来** ⇒ 本段 find 在
#    static/admin 里找 services/clients/… 一颗都不命中（stderr 又被 2>/dev/null 吞掉），
#    于是「陈旧源码清理」自 10-14 加入以来一直静默空转：本次 23 个货道退场孤儿让现场 mvn 编译失败。
cd "$DIR"
tar -tzf "/data/apps/prod-src-${SHA}.tar.gz" | grep -v '/$' | grep -v '^$' | sort -u > /tmp/src-keep.txt
find services clients packages scripts edge proto -type f \( -name '*.java' -o -name '*.sql' -o -name '*.xml' -o -name '*.ts' -o -name '*.vue' -o -name '*.mjs' -o -name '*.py' \) -print0 2>/dev/null > /tmp/src-scan.bin
SRC_SCANNED=$(tr -cd '\0' < /tmp/src-scan.bin | wc -c)
# 扫到 0 个文件 = cwd/根目录写错，必须红：否则「清理干净」与「根本没扫」长得一模一样
if [ "$SRC_SCANNED" -eq 0 ]; then
  echo "❌ 源码树清理扫到 0 个文件（cwd=$(pwd)）——拒绝把空转当完成"
  exit 1
fi
SRC_STALE=0
while IFS= read -r -d '' f; do
  if grep -Fxq -- "$f" /tmp/src-keep.txt; then
    :
  else
    echo "陈旧源码: $f"
    rm -f -- "$f"
    SRC_STALE=$((SRC_STALE + 1))
  fi
done < /tmp/src-scan.bin
rm -f /tmp/src-keep.txt /tmp/src-scan.bin
[ "$SRC_STALE" -gt 0 ] && echo "已清理 ${SRC_STALE} 个陈旧源码文件（扫描 ${SRC_SCANNED} 个）"
echo "✅ 服务器源码就绪（扫描 ${SRC_SCANNED} 个源文件）"
# Windows 检出的 Git 工作区可能不保留 executable bit；服务器钩子必须可执行，且部署后自检。
chmod +x scripts/backup-database.sh scripts/backup-offsite-oss.sh
test -x scripts/backup-database.sh
test -x scripts/backup-offsite-oss.sh
echo "✅ 备份钩子可执行权限已校验"
REMOTE
echo "✅ 服务器源码就绪"

echo "── [4/7] Flyway checksum 漂移对账（红=中止）──"
# 为什么要这一步：`check:migration-immutable` 允许「带理由豁免」去改**已应用**的迁移，
# 而部署链此前没有任何 repair 环节 ⇒ 目标库 flyway_schema_history 存旧 checksum，
# 容器起来即 `Migration checksum mismatch ... Validate failed` 拒服。
# 2026-11-13 dev 库实测 8 条漂移（V165/182/200/201/217/459/465/479）就是这么来的。
#
# 分工：服务器只导出四列（凭据留在 postgres 容器自己的 env 里，不打印不落盘），
# 比对在本地仓内迁移文件上做——服务器既没有 node 也没有 JDK（现场构建全在 Docker 里），
# 而这里必须在 `compose build` **之前**判掉，不能让 19 分钟的构建白跑。
APPLIED_TMP="$(mktemp)"
trap 'rm -f "$APPLIED_TMP"' EXIT
ssh "${SSH_OPTS[@]}" -p "$PROD_PORT" "${SSH_CMD[0]}" bash -s -- "$PROD_DIR" > "$APPLIED_TMP" << 'DRIFT'
set -euo pipefail
DIR="$1"
cd "$DIR"
C="docker compose -p ai-cabinet --env-file infra/.env.production -f infra/docker-compose.yml -f infra/docker-compose.apps.yml -f infra/docker-compose.production.yml -f infra/docker-compose.prod-server.yml -f infra/docker-compose.xxljob.yml --profile apps"
# SQL 里刻意不用任何单引号（heredoc + sh -c 双层引号嵌套是这里最容易踩的坑）：
# 用 where version is not null 直接排除 R__ 可重复迁移，NULL checksum 由 psql -tA 打成空串。
$C exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F"|" -c "select installed_rank, version, type, checksum from flyway_schema_history where version is not null order by installed_rank"'
DRIFT
APPLIED_LINES=$(grep -c '|' "$APPLIED_TMP" || true)
if [ "${APPLIED_LINES:-0}" -lt 50 ]; then
  # 导出几乎为空 = 采集失败（库名/容器/compose 任一不对），绝不能当成"没有漂移"放行
  echo "❌ 目标库导出仅 ${APPLIED_LINES} 行，不足以判定漂移（fail-closed，中止部署）"
  exit 1
fi
if ! node scripts/flyway-drift-check.mjs --applied-file "$APPLIED_TMP"; then
  echo "❌ 目标库存在 checksum 漂移：先对该库执行 flyway repair"
  echo "   （scripts/devops/flyway-repair/README.md；🔴 locations 必须同时含 db/migration 与 db/seed，"
  echo "     只指 migration 会把 3 条已应用 R__ 种子误标成已删除），VALIDATE=PASS 后重跑本部署"
  exit 1
fi
rm -f "$APPLIED_TMP"
trap - EXIT
echo "✅ 目标库 checksum 与本次部署的迁移文件逐条一致"

echo "── [5/7] 构建 SHA 镜像 + 按副本数重建应用 ──"
ssh "${SSH_OPTS[@]}" -p "$PROD_PORT" "${SSH_CMD[0]}" bash -s -- \
  "$PROD_DIR" "$SHA" "$TRADE_REPLICAS" "$DEVICE_REPLICAS" "$JIANGYI_REPLICAS" << 'REMOTE2'
set -euo pipefail
DIR="$1"; SHA="$2"; TRADE_REPLICAS="$3"; DEVICE_REPLICAS="$4"; JIANGYI_REPLICAS="$5"
cd "$DIR"
[ -f infra/docker-compose.prod-server.yml ] || { echo "❌ 服务器缺 infra/docker-compose.prod-server.yml（解包异常？）"; exit 1; }
sha256sum infra/docker-compose.yml infra/docker-compose.apps.yml infra/docker-compose.production.yml infra/docker-compose.prod-server.yml infra/docker-compose.xxljob.yml
bash scripts/check-prod-env-secrets.sh infra/.env.production
C=(docker compose -p ai-cabinet --env-file infra/.env.production
  -f infra/docker-compose.yml -f infra/docker-compose.apps.yml
  -f infra/docker-compose.production.yml -f infra/docker-compose.prod-server.yml
  -f infra/docker-compose.xxljob.yml --profile apps)
export IMAGE_TAG="$SHA"
"${C[@]}" build trade-service device-service jiangyi-gateway 2>&1 | tail -5
"${C[@]}" up -d --no-deps --force-recreate --scale "trade-service=$TRADE_REPLICAS" trade-service 2>&1 | tail -3
"${C[@]}" up -d --no-deps --force-recreate --scale "device-service=$DEVICE_REPLICAS" device-service 2>&1 | tail -3
"${C[@]}" up -d --no-deps --force-recreate --scale "jiangyi-gateway=$JIANGYI_REPLICAS" jiangyi-gateway 2>&1 | tail -3
"${C[@]}" up -d --no-deps --force-recreate trade-lb 2>&1 | tail -3

wait_healthy() {
  local service="$1" expected="$2" image="$3"
  local i id actual expected_id all
  local -a ids
  if [ -n "$image" ]; then
    expected_id="$(docker image inspect "$image" --format '{{.Id}}')"
  else
    expected_id=""
  fi
  for i in $(seq 1 40); do
    mapfile -t ids < <("${C[@]}" ps -q "$service")
    if [ "${#ids[@]}" -eq "$expected" ]; then
      all=1
      for id in "${ids[@]}"; do
        actual="$(docker inspect --format '{{.Image}}' "$id")"
        if [ -n "$expected_id" ] && [ "$actual" != "$expected_id" ]; then all=0; fi
        if [ "$(docker inspect --format '{{.State.Health.Status}}' "$id")" != "healthy" ]; then all=0; fi
      done
      if [ "$all" -eq 1 ]; then
        echo "✅ $service ${expected}/${expected} healthy，镜像 $image"
        return 0
      fi
    fi
    sleep 10
  done
  echo "❌ $service 未在 400s 内达到 ${expected}/${expected} healthy 或镜像不匹配"
  mapfile -t ids < <("${C[@]}" ps -q "$service")
  for id in "${ids[@]}"; do
    docker inspect --format '{{.Name}} image={{.Image}} health={{.State.Health.Status}}' "$id"
    docker logs "$id" --tail 30 2>&1 || true
  done
  return 1
}

wait_healthy trade-service "$TRADE_REPLICAS" "ai-cabinet/trade-service:$SHA"
wait_healthy device-service "$DEVICE_REPLICAS" "ai-cabinet/device-service:$SHA"
wait_healthy jiangyi-gateway "$JIANGYI_REPLICAS" "ai-cabinet/jiangyi-gateway:$SHA"
wait_healthy trade-lb 1 "haproxy:2.9-alpine@sha256:3e29449a6beed63262e36104adf531b4e41b359f61937303f5ea8607987b3748"
REMOTE2

echo "── [5b/7] recreate gateway（校验文件挂载）──"
# gateway 无 build；recreate 让新 conf、trade-lb 上游与 mp-verify 挂载生效。
ssh "${SSH_OPTS[@]}" -p "$PROD_PORT" "${SSH_CMD[0]}" bash -s -- "$PROD_DIR" << 'REMOTE4'
set -euo pipefail
DIR="$1"
cd "$DIR"
C=(docker compose -p ai-cabinet --env-file infra/.env.production
  -f infra/docker-compose.yml -f infra/docker-compose.apps.yml
  -f infra/docker-compose.production.yml -f infra/docker-compose.prod-server.yml
  -f infra/docker-compose.xxljob.yml --profile apps)
for f in infra/gateway/mp-verify/aHNmrbOhxm.txt infra/gateway/mp-verify/859bd3a9053ea8be5fbfae876b4a709f.html infra/gateway/trade-lb.cfg; do
  [ -f "$f" ] || { echo "❌ 网关文件缺失: $f"; exit 1; }
done
"${C[@]}" up -d --no-deps --force-recreate gateway 2>&1 | tail -3
echo "✅ gateway 已 recreate（新 conf/trade-lb/mp-verify 生效）"
REMOTE4
echo "✅ jiangyi-gateway 已重建"

echo "── [6/7] 部署后验证 ──"
ssh "${SSH_OPTS[@]}" -p "$PROD_PORT" "${SSH_CMD[0]}" bash -s -- << 'REMOTE3'
set -euo pipefail
# 2026-10-20 修正：宿主 nginx 接管 80/443（easygovm vhost）后，gateway 容器改发布在
# 127.0.0.1:8180——探针必须打 8180 直连 gateway，打 80 会被宿主 nginx 默认 server 404
# （当次部署实际成功，仅探针口径过时造成假失败）。
GW="http://127.0.0.1:8180"
IDX_JS=$(curl -sf "$GW/admin/" | grep -o 'index-[^"]*\.js' | head -1) || true
[ -n "$IDX_JS" ] || { echo "❌ $GW/admin/ 未返回 index.html"; exit 1; }
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$GW/admin/assets/$IDX_JS")
[ "$CODE" = "200" ] || { echo "❌ admin 入口资产 $IDX_JS → $CODE"; exit 1; }
API=$(curl -s -o /dev/null -w '%{http_code}' "$GW/api/v2/auth/captcha")
[ "$API" = "200" ] || { echo "❌ 业务 API /auth/captcha → $API"; exit 1; }
# 校验文件探针（MA-362/365 巡检项）：/o/ 与根路径都必须 200 直出（404=微信可回查取消发布）
OTXT=$(curl -s -o /dev/null -w '%{http_code}' "$GW/o/aHNmrbOhxm.txt")
[ "$OTXT" = "200" ] || { echo "❌ 微信校验文件 /o/aHNmrbOhxm.txt → $OTXT"; exit 1; }
OHTML=$(curl -s -o /dev/null -w '%{http_code}' "$GW/859bd3a9053ea8be5fbfae876b4a709f.html")
[ "$OHTML" = "200" ] || { echo "❌ 支付宝校验文件（根路径）→ $OHTML"; exit 1; }
# jiangyi-gateway 探针带重试（Spring Boot 启动 30-60s；nginx upstream 未就绪=502/504）
JY=000
for i in $(seq 1 18); do
  JY=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' "$GW/jiangyi/api/domainConfig/getConfigStatusByIdentifier" || echo 000)
  if [ "$JY" != "000" ] && [ "$JY" != "502" ] && [ "$JY" != "504" ]; then break; fi
  sleep 5
done
case "$JY" in
  200|400|401|403) : ;;  # 401=路由通、待设备 token（历史口径）
  *) echo "❌ jiangyi-gateway 探针 = $JY，最近日志："
     docker logs ai-cabinet-jiangyi-gateway-1 --tail 20 2>&1 | tail -10
     exit 1 ;;
esac
echo "✅ admin 入口 $IDX_JS = 200；业务 API = 200；校验文件 /o = $OTXT、根 = $OHTML；jiangyi 探针 = $JY"
REMOTE3

echo "── [6b/7] 公网端到端探针（宿主 nginx 层，warn-only）──"
# 宿主 nginx vhost 手写于服务器、不在仓库（2026-11-19 备忘「建议纳入 infra/」）；
# 该层失败不回滚容器（容器层已验），但要打出来让人工核对 vhost。
PUB="https://cabinet.easygovm.com"
PUB_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$PUB/o/aHNmrbOhxm.txt" || echo 000)
if [ "$PUB_CODE" = "200" ]; then
  echo "✅ 公网校验文件 $PUB/o/aHNmrbOhxm.txt = 200"
else
  echo "⚠️ 公网 $PUB/o/aHNmrbOhxm.txt → $PUB_CODE（宿主 nginx 层问题，容器层已 200；请人工核对 vhost）"
fi

echo "── [7/7] 完成 ──"
echo "✅ 生产已部署 $SHA_SHORT；服务器归档与 .tmp 本地归档可手动清理"
# 🔴 MA-318（2026-11-17）：旧文案写「git checkout <旧SHA> && 重新跑本脚本」是**错的**，
# 照做会在 §[4/7] 的 flyway-drift-check 直接红（库里已应用的新迁移在旧树里不存在，
# 报「applied not resolved locally」），而且本仓没有任何 down 迁移可回退 schema。
echo "   回滚（三选一，按是否含迁移决定）："
echo "   A. 仅代码回退（本次部署**没有**新迁移时）：git checkout <旧SHA> && bash $0"
echo "      —— drift-check 此时不会红，因为库与旧树的迁移集一致。"
echo "   B. 有新迁移：只回退**镜像**，库保持新水位（迁移只向前）："
echo "      docker compose -p ai-cabinet -f infra/docker-compose.yml -f infra/docker-compose.apps.yml \\"
echo "        -f infra/docker-compose.production.yml -f infra/docker-compose.prod-server.yml -f infra/docker-compose.xxljob.yml --profile apps"
echo "      up -d --no-deps trade-service   # 用上一个 tag 的镜像；新列/新索引对旧代码必须兼容"
echo "      前提：本次迁移是加列/加索引类（本仓约定），旧 jar 的 INSERT 不依赖它们。"
echo "   C. 破坏性迁移（改类型/删列/重建约束）无法向前兼容 ⇒ 只能恢复部署前快照："
echo "      pg_dump 已按每日 02:30 落盘（scripts/backup-postgres.sh），事故时按时间点位恢复；"
echo "      ⚠️ 恢复到点位会丢掉点位之后的业务数据，需与运营确认后再执行。"
