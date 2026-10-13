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

echo "── [0/6] 前置检查 ──"
[ -f "$PROD_SSH_KEY" ] || { echo "❌ SSH 私钥不存在: $PROD_SSH_KEY"; exit 1; }
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

echo "── [1/6] 打源码归档 ──"
TARBALL=".tmp/prod-src-${SHA}.tar.gz"
mkdir -p .tmp
git archive --format=tar.gz -o "$TARBALL" HEAD
echo "✅ $(du -h "$TARBALL" | cut -f1) → $TARBALL"

echo "── [2/6] 上传 ${PROD_HOST} ──"
scp "${SSH_OPTS[@]}" -P "$PROD_PORT" "$TARBALL" "${SSH_CMD[0]}:/data/apps/prod-src-${SHA}.tar.gz"
echo "✅ 上传完成"

echo "── [3/6] 服务器解包 + 清理陈旧 admin 资产 ──"
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
REMOTE
echo "✅ 服务器源码就绪"

echo "── [4/6] 重建 trade-service 镜像 + 容器 ──"
ssh "${SSH_OPTS[@]}" -p "$PROD_PORT" "${SSH_CMD[0]}" bash -s -- "$PROD_DIR" << 'REMOTE2'
set -euo pipefail
DIR="$1"
cd "$DIR"
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

echo "── [5/6] 部署后验证 ──"
ssh "${SSH_OPTS[@]}" -p "$PROD_PORT" "${SSH_CMD[0]}" bash -s -- << 'REMOTE3'
set -euo pipefail
IDX_JS=$(curl -s http://127.0.0.1/admin/ | grep -o 'index-[^"]*\.js' | head -1)
[ -n "$IDX_JS" ] || { echo "❌ /admin/ 未返回 index.html"; exit 1; }
CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1/admin/assets/$IDX_JS")
[ "$CODE" = "200" ] || { echo "❌ admin 入口资产 $IDX_JS → $CODE"; exit 1; }
API=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/api/v2/auth/captcha)
[ "$API" = "200" ] || { echo "❌ 业务 API /auth/captcha → $API"; exit 1; }
echo "✅ admin 入口 $IDX_JS = 200；业务 API = 200"
REMOTE3

echo "── [6/6] 完成 ──"
echo "✅ 生产已部署 $SHA_SHORT；服务器归档与 .tmp 本地归档可手动清理"
echo "   回滚方式：git checkout <旧SHA> && 重新跑本脚本"
