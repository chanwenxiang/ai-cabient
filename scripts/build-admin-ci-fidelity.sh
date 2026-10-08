#!/usr/bin/env bash
# 在容器（node:24.18.0，对齐 ci.yml setup-node）内**用干净检出**重建 admin 产物并回传。
#
# 🔴 为什么必须 clone 而不是打包工作区（2026-10-08 实测，CI admin-artifacts 门禁真红）：
#   产物字节取决于**构建输入的每一个字节**，包括 git 不会管的本地文件。
#   本机 `clients/admin-vue/.env.local`（被 .gitignore）里有
#     VITE_DEV_PROXY=http://127.0.0.1:18080
#     VITE_DEV_ORIGIN=http://localhost
#   vite 构建时会把 `VITE_*` **内联进产物** ⇒ 用工作区构建出来的产物与
#   `git checkout` 出来的源码构建产物**内容哈希全不同** ⇒ CI 门禁必然报红。
#   同理 `packages/shared-types/dist`（generated，被 gitignore）也不该进构建输入。
#
#   正解：**从 git 检出重建**（git clone → pnpm install → build-admin.mjs），
#   与 CI 的 admin-artifacts job 走同一条路径 ⇒ 产物天然同源。
#
# 🔴 铁律 30（绝不把宿主 node_modules 挂进容器跑包管理器）：本脚本 clone 宿主仓库为
#   独立目录、依赖装在容器内，**只**通过 docker cp 单向回传产物，不挂载宿主依赖目录。
#
# 用法：bash scripts/build-admin-ci-fidelity.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/.tmp/build-admin-ci-fidelity.log"
OUT_REL="services/trade-service/src/main/resources/static/admin"
STAGE="admin-ci-fidelity-$$"
NODE_TAG="${NODE_IMAGE_TAG:-24.18.0}"   # 必须与 ci.yml setup-node 一致
PNPM_VER="9.15.9"

mkdir -p "$ROOT/.tmp"
: > "$LOG"
ROOT_WIN="$(pwd -W)"

command -v docker >/dev/null 2>&1 || { echo "❌ 未找到 docker"; exit 1; }
docker version --format '{{.Server.Version}}' >/dev/null 2>&1 || { echo "❌ Docker daemon 不可用"; exit 1; }

cat > "$ROOT/.tmp/admin-ci-fidelity-container.sh" <<'CONTAINER_EOF'
set -euo pipefail
npm install -g "pnpm@${PNPM_VER}" >/dev/null 2>&1
echo "[容器] node=$(node -v) pnpm=$(pnpm --version)"

git clone --quiet --no-hardlinks /src /w/repo
cd /w/repo
echo "[容器] HEAD=$(git rev-parse --short HEAD)"
# 自检：干净检出里不得存在被 gitignore 的 .env.local（它会内联进产物）
if [ -f clients/admin-vue/.env.local ]; then
  echo "[容器] ❌ 干净检出里竟有 .env.local ⇒ clone 没做到干净检出"
  exit 8
fi
pnpm install --frozen-lockfile
node scripts/build-admin.mjs
test -f "${OUT_REL}/index.html" || { echo "[容器] ❌ 产物缺 index.html"; exit 4; }
echo "[容器] 产物文件数: $(find "${OUT_REL}" -type f | wc -l)"
CONTAINER_EOF

echo "[1/3] 容器内从干净检出重建（node:${NODE_TAG}）"
MSYS_NO_PATHCONV=1 docker run --name "$STAGE" \
  --memory "${BUILD_MEMORY:-4g}" \
  -e npm_config_registry=https://registry.npmmirror.com \
  -e npm_config_script_shell=/bin/bash \
  -e PNPM_VER="$PNPM_VER" -e OUT_REL="$OUT_REL" \
  --mount "type=bind,source=$ROOT_WIN,target=/src,readonly" \
  --mount "type=bind,source=$ROOT/.tmp/admin-ci-fidelity-container.sh,target=/tmp/build.sh,readonly" \
  -i "node:${NODE_TAG}" bash /tmp/build.sh >>"$LOG" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
  echo "❌ 容器构建失败（rc=$RC），日志尾部："; tail -30 "$LOG"
  docker rm -f "$STAGE" >/dev/null 2>&1; exit 1
fi

echo "[2/3] 回传产物"
# ⚠️ 用 mv 不用 rm -rf（rm 会触发 safe-delete 守卫，且拦截退出码会被掩盖，
#    导致旧产物+新产物叠加成两倍文件数 —— build-admin-docker.sh 已踩过）。
STALE="$ROOT/.tmp/stale-admin-ci-$$"
mkdir -p "$STALE"
if [ -d "$ROOT/$OUT_REL" ]; then
  mv "$ROOT/$OUT_REL" "$STALE/admin" || { echo "❌ mv 旧产物失败"; RC=1; }
fi
if [ $RC -eq 0 ] && docker cp "$STAGE:/w/repo/$OUT_REL" "$ROOT/$OUT_REL" 2>>"$LOG"; then
  NEW_N=$(find "$ROOT/$OUT_REL" -type f | wc -l | tr -d ' ')
  echo "      ✅ 已回传 $NEW_N 个文件"
  if [ "$NEW_N" -gt 400 ]; then
    echo "      ❌ 产物数异常（$NEW_N > 400），疑似叠加 ⇒ 回滚"
    mv "$ROOT/$OUT_REL" "$ROOT/.tmp/bad-admin-ci-$$" 2>/dev/null || true
    mv "$STALE/admin" "$ROOT/$OUT_REL"
    RC=1
  fi
else
  echo "❌ docker cp 失败，见 $LOG"
  [ -d "$STALE/admin" ] && mv "$STALE/admin" "$ROOT/$OUT_REL"
  RC=1
fi

echo "[3/3] 本地校验（CI 同款判据）"
CHANGED="$(git status --porcelain -uall -- "$OUT_REL")"
if [ -n "$CHANGED" ]; then
  echo "      待提交差异："
  printf '%s\n' "$CHANGED" | head -20
  echo "      共 $(printf '%s\n' "$CHANGED" | wc -l) 处"
else
  echo "      ✅ 与提交产物一致（无需提交）"
fi

docker rm -f "$STAGE" >/dev/null 2>&1
rm -f "$ROOT/.tmp/admin-ci-fidelity-container.sh"
rm -rf "$STALE"
if [ $RC -ne 0 ]; then exit $RC; fi
echo "✅ 完成。提交命令：git add $OUT_REL && git commit"
