#!/usr/bin/env bash
# 生产一键部署：本地与 GitHub Actions deploy-prod workflow 共用同一条路径。
#
# 流程（2026-10-13 实战验证版）：
#   1. git archive HEAD 打源码归档（工作区未提交内容不会进部署，先检查干净）
#   2. scp 上传 → 服务器原地解包（目录 bind mount inode 不变，无需重建 gateway）
#   3. 清理 admin 陈旧哈希资产（解包不删文件，历次部署会积攒旧 js）
#   4. 服务器现场 compose build trade-service → up --no-deps --force-recreate
#   5. 等健康检查 + 验证 admin 静态可达
#
# 铁律防护：
#   - 只动 trade-service，绝不重建 postgres（memory 铁律 69）
#   - 单文件 bind mount 改动必须 force-recreate，reload 无效（铁律家族）
#   - 工作区必须干净（archive 只打包 HEAD，脏工作区会造成「部署的不是你以为的代码」）
#
# 用法：
#   bash scripts/deploy-prod.sh                 # 部署本地 HEAD
#   PROD_HOST=1.2.3.4 PROD_USER=root bash ...   # 覆盖目标
# 环境变量：PROD_HOST / PROD_USER / PROD_DIR / PROD_SSH_KEY / PROD_PORT
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROD_HOST="${PROD_HOST:-120.25.246.179}"
PROD_USER="${PROD_USER:-root}"
PROD_DIR="${PROD_DIR:-/data/apps/ai-cabinet}"
PROD_SSH_KEY="${PROD_SSH_KEY:-$HOME/.ssh/aicabinet-prod.pem}"
PROD_SSH_KEY="${PROD_SSH_KEY/#\~/$HOME}"   # Actions 场景传 ~ 开头路径，变量内不会自动展开
PROD_PORT="${PROD_PORT:-22}"

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
scp "${SSH_OPTS[@]}" -P "$PROD_PORT" "$TARBALL" "${SSH_CMD[0]}:/data/apps/prod-src-${SHA}.tar.gz"
echo "✅ 上传完成"

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
C="docker compose -p ai-cabinet --env-file infra/.env.production -f infra/docker-compose.yml -f infra/docker-compose.apps.yml -f infra/docker-compose.production.yml -f infra/docker-compose.prod-server.yml --profile apps"
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

echo "── [5/7] 重建 trade-service 镜像 + 容器 ──"
ssh "${SSH_OPTS[@]}" -p "$PROD_PORT" "${SSH_CMD[0]}" bash -s -- "$PROD_DIR" << 'REMOTE2'
set -euo pipefail
DIR="$1"
cd "$DIR"
# AU-185：服务器侧预检 + 指纹留痕（解包后应与仓库一致；sha 打进部署日志可追漂移）
[ -f infra/docker-compose.prod-server.yml ] || { echo "❌ 服务器缺 infra/docker-compose.prod-server.yml（解包异常？）"; exit 1; }
sha256sum infra/docker-compose.yml infra/docker-compose.apps.yml infra/docker-compose.production.yml infra/docker-compose.prod-server.yml
# 上线清单 P0：拒绝开发默认密钥（base compose 的 `${KEY:-dev默认}` 会静默生效）
bash scripts/check-prod-env-secrets.sh infra/.env.production
C="docker compose -p ai-cabinet --env-file infra/.env.production -f infra/docker-compose.yml -f infra/docker-compose.apps.yml -f infra/docker-compose.production.yml -f infra/docker-compose.prod-server.yml --profile apps"
$C build trade-service 2>&1 | tail -2
$C up -d --no-deps --force-recreate trade-service 2>&1 | tail -1
echo "等待健康检查…"
for i in $(seq 1 40); do
  H=$(docker inspect --format '{{.State.Health.Status}}' ai-cabinet-trade-service-1 2>/dev/null || echo starting)
  if [ "$H" = "healthy" ]; then echo "✅ trade healthy（${i}0s 内）"; exit 0; fi
  if [ "$H" = "unhealthy" ]; then
    echo "❌ trade unhealthy，最近日志："
    docker logs ai-cabinet-trade-service-1 --tail 30 2>&1
    exit 1
  fi
  sleep 10
done
echo "❌ 200s 内未转 healthy，最近日志："
docker logs ai-cabinet-trade-service-1 --tail 30 2>&1
exit 1
REMOTE2

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
JY=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' "$GW/jiangyi/api/domainConfig/getConfigStatusByIdentifier")
echo "✅ admin 入口 $IDX_JS = 200；业务 API = 200；domainConfig 探针 = $JY（401=路由通、待设备 token）"
REMOTE3

echo "── [7/7] 完成 ──"
echo "✅ 生产已部署 $SHA_SHORT；服务器归档与 .tmp 本地归档可手动清理"
echo "   回滚方式：git checkout <旧SHA> && 重新跑本脚本"
