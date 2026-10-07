#!/usr/bin/env bash
# Windows 宿主机上重建 admin 产物（static/admin）—— Docker node:24 两段式。
#
# 为何需要这个脚本：
#   CI 的 `admin-artifacts` job 在 ubuntu 上重建并用 `git status --porcelain` 字节比对；
#   而 `scripts/pre-push-ci-preflight.mjs` 在 win32 上**主动跳过**产物比对
#   （lessons #287：Vite 内容哈希与 Linux CI 不同），只提示「提交产物请用 Docker node:24 构建」。
#   ⇒ Windows 开发者本地没有现成入口，极易漏提交产物 ⇒ CI 每次都红。
#
# 🔴 铁律 30（本脚本存在的原因，2026-10-06 曾因此弄坏三个前端）：
#   **绝不把宿主的 node_modules 挂进容器跑包管理器** —— pnpm 会判定「模块目录需清空重装」
#   从而**删除宿主的 node_modules**。
#   本脚本因此用「tar 管道送源码 + 产物用 docker cp 回传」，全程不挂载宿主目录。
#
# 三个必踩坑（都已在下面处理）：
#   ① Windows 的 /tmp 其实是宿主 Temp⇒ 任何 -v 都必须 `cygpath -w | tr '\\' '/'`；
#   ② 容器内 corepack prepare 拉不到包（宿主有 http_proxy，容器内没有）
#      ⇒ 传 `-e http_proxy=http://host.docker.internal:<port>` 并改用 `npm i -g pnpm`；
#   ③ 脚本里 `| tail -N` 会把失败静默吞掉（容器没启动却 exit 0）
#      ⇒ 日志落盘再读，且**不用 --rm**（否则产物随容器销毁没法 cp 回来）。
#
# 用法：
#   bash scripts/build-admin-docker.sh              # 构建并回传产物
#   PROXY_PORT=10809 bash scripts/build-admin-docker.sh   # 换代理端口
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/.tmp/build-admin-docker.log"
OUT_REL="services/trade-service/src/main/resources/static/admin"
mkdir -p "$ROOT/.tmp"
: > "$LOG"

PROXY_PORT="${PROXY_PORT:-10751}"
PROXY_URL="http://host.docker.internal:${PROXY_PORT}"
CIMG="aicabinet-admin-build:node24"
STAGE="admin-build-$$"

say() { printf '%s\n' "$*"; }

# ── 0. 前置检查 ───────────────────────────────────────────────
command -v docker >/dev/null 2>&1 || { echo "❌ 未找到 docker"; exit 1; }
docker version --format '{{.Server.Version}}' >/dev/null 2>&1 || {
  echo "❌ Docker daemon 不可用（先启动 Docker Desktop）"; exit 1; }

# ── 1. 打包源码（排除 node_modules 与既有产物）─────────────────
say "[1/4] 打包源码（tar 管道，**不挂载宿主目录**）"
SRC_LIST=(
  package.json pnpm-workspace.yaml pnpm-lock.yaml .npmrc
  packages clients/admin-vue
)
MISSING=()
for f in "${SRC_LIST[@]}"; do
  [ -e "$ROOT/$f" ] || MISSING+=("$f")
done
if [ ${#MISSING[@]} -gt 0 ]; then
  echo "❌ 缺少文件：${MISSING[*]}"
  exit 1
fi

# ⚠️ 显式排除 static/admin：它是**构建产物**，不能进镜像（否则等于用旧产物冒充新产物）
tar -cf - -C "$ROOT" \
  --exclude='node_modules' --exclude='dist' \
  "${SRC_LIST[@]}" > "$ROOT/.tmp/src-$STAGE.tar" 2>>"$LOG"
if [ ! -s "$ROOT/.tmp/src-$STAGE.tar" ]; then
  echo "❌ 打包失败，见 $LOG"; exit 1
fi
say "      源码包 $(du -h "$ROOT/.tmp/src-$STAGE.tar" | cut -f1)"

# ── 2. 容器内安装依赖 + 构建 ────────────────────────────────
say "[2/4] 容器内安装依赖并构建（node:24）"
docker build -t "$CIMG" - <<'DOCKERFILE' >>"$LOG" 2>&1
FROM node:24
WORKDIR /w
# 代理由运行时传入；这里只设registry
RUN npm config set registry https://registry.npmmirror.com
# corepack 在国内网络下不可靠 ⇒ 直接装 pnpm（与 pom.xml / CI 的 pnpm 9.15.9 对齐）
RUN npm install -g pnpm@9.15.9
CMD ["bash"]
DOCKERFILE
if [ $? -ne 0 ]; then
  echo "❌ 镜像构建失败，见 $LOG"; exit 1
fi

docker run --name "$STAGE" \
  -e http_proxy="$PROXY_URL" -e https_proxy="$PROXY_URL" \
  -e npm_config_registry=https://registry.npmmirror.com \
  -e npm_config_script_shell=/bin/bash \
  -i "$CIMG" bash -lc '
    set -euo pipefail
    tar -xf -
    echo "[容器] 源码 $(du -sh /w | cut -f1)"
    pnpm install --filter @aicabinet/admin-vue... --frozen-lockfile
    cd /w/clients/admin-vue
    npx vue-tsc --noEmit
    npx vite build
    echo "[容器] dist:"
    ls dist | head
  ' < "$ROOT/.tmp/src-$STAGE.tar" >>"$LOG" 2>&1
RC=$?

# ── 3. 回传产物（docker cp；不用 --rm，否则产物随容器销毁）────
if [ $RC -eq 0 ]; then
  say "[3/4] 回传产物到 $OUT_REL"
  rm -rf "$ROOT/$OUT_REL"
  if docker cp "$STAGE:/w/clients/admin-vue/dist" "$ROOT/$OUT_REL"; then
    say "      ✅ 已回传$(find "$ROOT/$OUT_REL" -type f | wc -l | tr -d ' ') 个文件"
  else
    say "      ❌ docker cp 失败，见 $LOG"; RC=1
  fi
else
  say "[3/4] 构建失败，跳过回传"
fi

# ── 4. 清理 ────────────────────────────────────────────────
docker rm -f "$STAGE" >/dev/null 2>&1
rm -f "$ROOT/.tmp/src-$STAGE.tar"
say "[4/4] 已清理临时容器与源码包"

if [ $RC -ne 0 ]; then
  echo ""
  echo "❌ 失败。日志尾部："
  tail -30 "$LOG"
  exit $RC
fi

say ""
say "✅ 完成。下一步："
say "   node scripts/check-admin-bundle-budget.mjs   # 体积预算"
say "   git status --porcelain -- $OUT_REL          # 应只有预期改动"
say "   然后把 $OUT_REL 一并提交（CI 会做字节比对）"