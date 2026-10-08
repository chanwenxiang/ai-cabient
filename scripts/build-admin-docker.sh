#!/usr/bin/env bash
# Windows 宿主机上重建 admin 产物（static/admin）—— Docker node:24。
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
#   本脚本因此用「送源码进容器独立目录 → 依赖装在容器内 → 产物用 docker cp 回传」。
#
# 🔴 三个实测踩坑（本脚本已规避）：
#   ① **`tar -cf -` 通过 Git Bash 管道给 docker stdin 会得到 "This does not look like a tar archive"**
#      —— stdin 字节流在 Git Bash → docker 的边界上被破坏（宿主 `tar -tf` 却能正常读）。
#      正解：**把 tar 挂载进容器**（`-v <win路径>:/tmp/src.tar:ro`），不用 stdin。
#      另：路径含空格必须 `cygpath -w | tr '\\' '/'`（铁律 30坑①）。
#   ② **arcname 必须是相对仓库根的路径**。含空格路径下曾产出
#      "ai-generated code/ai-cabinet/package.json" ⇒ 容器里多一层目录 ⇒ pnpm "No projects found"。
#      正解：用 `scripts/pack-admin-src.py` 打包，它带**自校验**（必需条目 + 顶层白名单）。
#   ③ **容器内 corepack prepare 拉不到包**（宿主有 proxy，容器内没有）
#      ⇒ 传 `-e http_proxy=http://host.docker.internal:<port>` 并改用 `npm i -g pnpm@<ver>`。
#   另：脚本里 `| tail -N` 会把失败静默吞掉（容器没启动却 exit 0）⇒ 日志落盘再读。
#
# 用法：
#   bash scripts/build-admin-docker.sh                    # 构建并回传产物
#   PROXY_PORT=10809 bash scripts/build-admin-docker.sh    # 换代理端口
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/.tmp/build-admin-docker.log"
TAR="$ROOT/.tmp/admin-src.tar"
OUT_REL="services/trade-service/src/main/resources/static/admin"
mkdir -p "$ROOT/.tmp"
: > "$LOG"

PROXY_PORT="${PROXY_PORT:-10751}"
PROXY_URL="http://host.docker.internal:${PROXY_PORT}"
CIMG="aicabinet-admin-build:node24"
PNPM_VER="9.15.9"
STAGE="admin-build-$$"

say() { printf '%s\n' "$*"; }

# ── 0. 前置检查 ───────────────────────────────────────────────
command -v docker >/dev/null 2>&1 || { echo "❌ 未找到 docker"; exit 1; }
docker version --format '{{.Server.Version}}' >/dev/null 2>&1 || {
  echo "❌ Docker daemon 不可用（先启动 Docker Desktop）"; exit 1; }

# ── 1. 打包源码（Python，带自校验）────────────────────────────
say "[1/4] 打包源码（scripts/pack-admin-src.py，自校验 arcname）"
if ! python "$ROOT/scripts/pack-admin-src.py" "$TAR" 2>&1 | tee -a "$LOG"; then
  echo "❌ 打包失败（见上，含自校验报错）"; exit 1
fi
# Windows 路径 → 容器可挂载格式（铁律 30 坑①：/tmp 其实是宿主 Temp）
TAR_WIN="$(cygpath -w "$TAR" | tr '\\' '/')"
[ -f "$TAR_WIN" ] || TAR_WIN="$TAR"
say "      tar 挂载点: $TAR_WIN"

# ── 2. 镜像（pnpm 版本与 pom.xml / CI 对齐）────────────────────
say "[2/4] 准备镜像 node:24 + pnpm@$PNPM_VER"
docker build -t "$CIMG" - <<'DOCKERFILE' >>"$LOG" 2>&1
FROM node:24
WORKDIR /w
RUN npm config set registry https://registry.npmmirror.com
# corepack 在国内网络下不可靠 ⇒ 直接装 pnpm（版本对齐 ci.yml 的 pnpm@9.15.9）
RUN npm install -g pnpm@9.15.9
CMD ["bash"]
DOCKERFILE
if [ $? -ne 0 ]; then
  echo "❌ 镜像构建失败，见 $LOG"; exit 1
fi

# ── 3. 容器内安装 + 构建（不用 --rm，否则产物没法 cp 回来）─────
say "[3/4] 容器内 pnpm install + vue-tsc + vite build"
docker rm -f "$STAGE" >/dev/null 2>&1
# 🔴 **--memory 是必需项，不是调优**：不加时 pnpm install 在 postinstall 阶段整批被杀，
#    报 `ELIFECYCLE exit code -2`。-2 看着像「被信号中断」，实为 **OOM kill**。
#    判据（实测，别再猜）：加 --memory=4g 后同一条命令 `PNPM_EXIT=0`，
#    且 core-js / esbuild / vue-demi 三个 postinstall 全部 `Done`
#    —— vue-demi 的脚本是 `try{require()}catch(e){}` **自带容错**却报 Failed，
#    说明它根本没执行到，也就是「进程被杀」而非「包坏了」。
#    ⚠️ 并发参数（--child-concurrency）**不用**加：已被证明与本问题无关。
docker run --name "$STAGE" \
  --memory "${BUILD_MEMORY:-4g}" \
  -e http_proxy="$PROXY_URL" -e https_proxy="$PROXY_URL" \
  -e npm_config_registry=https://registry.npmmirror.com \
  -e npm_config_script_shell=/bin/bash \
  -v "$TAR_WIN":/tmp/src.tar:ro \
  -i "$CIMG" bash -lc '
    set -euo pipefail
    mkdir -p /w && cd /w
    tar -xf /tmp/src.tar
    echo "[容器] 顶层: $(ls | tr "\n" " ")"
    test -f package.json || { echo "❌ package.json 不在归档根目录"; exit 3; }
    test -f pnpm-workspace.yaml || { echo "❌ pnpm-workspace.yaml 缺失"; exit 3; }
    pnpm install --filter @aicabinet/admin-vue... --frozen-lockfile
    cd /w/clients/admin-vue
    npx vue-tsc --noEmit
    npx vite build
    echo "[容器] dist 产出："
    ls -la dist | head -8
  ' >>"$LOG" 2>&1
RC=$?

# ── 4. 回传产物 ─────────────────────────────────────────────
if [ $RC -eq 0 ]; then
  say "[4/4] 回传产物到 $OUT_REL"
  rm -rf "$ROOT/$OUT_REL"
  if docker cp "$STAGE:/w/clients/admin-vue/dist" "$ROOT/$OUT_REL" 2>>"$LOG"; then
    say "      ✅ 已回传 $(find "$ROOT/$OUT_REL" -type f | wc -l | tr -d ' ') 个文件"
  else
    say "      ❌ docker cp 失败，见 $LOG"; RC=1
  fi
else
  say "[4/4] 构建失败，跳过回传"
fi

docker rm -f "$STAGE" >/dev/null 2>&1
rm -f "$TAR"

if [ "$RC" -ne 0 ]; then
  echo ""
  echo "❌ 失败。日志尾部（$LOG）："
  tail -35 "$LOG"
  exit "$RC"
fi

say ""
say "✅ 完成。下一步："
say "   node scripts/check-admin-bundle-budget.mjs        # 体积预算"
say "   git status --porcelain -- $OUT_REL               # 应只有预期改动"
say "   然后把 $OUT_REL 一并提交（CI 会做字节比对）"