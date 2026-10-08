#!/usr/bin/env bash
# 只读诊断：在容器（node:24 + pnpm@9.15.9）内**复刻 CI admin-artifacts job 的构建**，
# 再与 git HEAD 中提交的产物逐文件比对，打印具体差异清单。
#
# 🔴 本脚本**不覆盖** static/admin、不改工作区：容器产物导出到 .tmp/admin-diag-out/ 后即删。
#    目的 = 定位 CI「Verify admin UI artifacts are up-to-date」为何报红。
#
# 🔴 三个实测踩坑（2026-10-08，写在这里防止重犯）：
#   ① 路径转换**只能用 `cd <dir> && pwd -W`**，不能用 cygpath：
#      - `cygpath -w` 输出的反斜杠再喂给 `tr '\\' '/'`，反斜杠会在写入链路被吃掉
#        ⇒ 变成 `tr '\' '/'` ⇒ bash 引号不配对、脚本直接语法错；
#      - `cygpath -m` 在 Git Bash 下把 D:/ 误重写成 /tmp 对应的 C:/Users/.../Temp/...，
#        docker --mount 的 source 变空 ⇒ docker 报 invalid value 退出。
#   ② 挂载**必须用 `--mount "type=bind,source=...,target=..."`**：
#      `-v "D:/含 空格/x.tar:/src:ro"` 会被 Docker 按空格拆成多个参数，
#      静默挂成**空目录**（容器内 tar 报 "Cannot read: Is a directory"）。
#   ③ 容器**默认直连** registry.npmmirror.com（实测 rc=0）。
#      旧脚本硬编码的 host.docker.internal:10751 代理已失效 ⇒ pnpm 静默卡死、
#      容器零输出。代理只在宿主确实有监听时才传：PROXY_PORT=xxxx bash 本脚本。
#   ④ Git Bash 会把**以 / 开头的参数**改写成 Windows 路径（MSYS path conversion）：
#      `bash /tmp/build.sh` ⇒ `bash C:/Users/cwx/AppData/Local/Temp/build.sh`
#      ⇒ 容器内 "No such file or directory"。
#      正解：调用 docker 前设 `MSYS_NO_PATHCONV=1`（--mount 的 target 不受影响）。
#
# 用法：
#   bash scripts/diag-admin-artifact-diff.sh
#   PROXY_PORT=10751 bash scripts/diag-admin-artifact-diff.sh   # 宿主代理可用时
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/.tmp/admin-diff-diag.log"
TAR="$ROOT/.tmp/admin-diag-src.tar"
OUT_REL="services/trade-service/src/main/resources/static/admin"
DIAG="$ROOT/.tmp/admin-diag-out"
STAGE="admin-diag-$$"
PNPM_VER="9.15.9"

mkdir -p "$ROOT/.tmp"
: > "$LOG"

# ── 代理（默认不传，见踩坑③）────────────────────────────────
PROXY_ARGS=()
if [ -n "${PROXY_PORT:-}" ]; then
  PROXY_ARGS=(-e "http_proxy=http://host.docker.internal:${PROXY_PORT}"
              -e "https_proxy=http://host.docker.internal:${PROXY_PORT}")
  echo "[代理] 使用 host.docker.internal:${PROXY_PORT}"
else
  echo "[代理] 不传代理，容器直连 registry.npmmirror.com"
fi

# ── 1/4 打包源码（Python，带 arcname 自校验）─────────────────
echo "[1/4] 打包源码"
if ! python "$ROOT/scripts/pack-admin-src.py" "$TAR" scripts >>"$LOG" 2>&1; then
  echo "❌ 打包失败："; tail -10 "$LOG"; exit 1
fi
TAR_DIR_W="$(cd "$(dirname "$TAR")" && pwd -W)"   # 见踩坑①
TAR_WIN="$TAR_DIR_W/$(basename "$TAR")"
if [ ! -f "$TAR_WIN" ]; then
  echo "❌ 源 tar 不存在：$TAR_WIN"; exit 1
fi
echo "      tar: $TAR_WIN"

# ── 2/4 容器内复刻 CI 构建 ──────────────────────────────────
# 关键：**完全照抄 ci.yml admin-artifacts job 的步骤**
#   pnpm install --frozen-lockfile  →  node scripts/build-admin.mjs
# 不同点仅两点（都已验证必要）：
#   a) 装 pnpm 用 `npm i -g`（容器内 corepack 在国内网络不可靠，ci.yml 注释亦提及）；
#   b) .npmrc 的 script-shell=cmd.exe 需覆盖为 /bin/bash（ci.yml job 级 env 同款）。
echo "[2/4] 容器内构建（复刻 CI，node:$(node -v | sed s/v//)）"
# 🔴 Node 小版本必须与 CI 一致：ci.yml 用 setup-node node-version: 24.18.0（精确钉），
#    而 `node:24` 是滚动 tag（实测本地解析到 24.21.0）。vite/rollup 产物对 Node 小版本
#    敏感 ⇒ 用 `node:24` 重建出的产物与 CI 不同 ⇒ 产物门禁必红。
#    判据：容器内 `node -v` 应为 v24.18.0。
echo "      期望 node=v24.18.0（对齐 ci.yml setup-node）"
docker rm -f "$STAGE" >/dev/null 2>&1

cat > "$ROOT/.tmp/admin-diag-container.sh" <<'CONTAINER_EOF'
set -euo pipefail
echo "[容器] pnpm: $(npm install -g "pnpm@${PNPM_VER}" >/dev/null 2>&1; pnpm --version)"
test -f /tmp/src.tar || { echo "[容器] ❌ /tmp/src.tar 不是文件（挂载退化，见踩坑②）"; exit 9; }
mkdir -p /w && cd /w
tar -xf /tmp/src.tar
echo "[容器] 顶层: $(ls | tr '\n' ' ')"
test -f package.json || { echo "[容器] ❌ package.json 不在归档根目录"; exit 3; }

echo "[容器] pnpm install --frozen-lockfile ..."
pnpm install --frozen-lockfile

# 🔴 不用 npx（会继承宿主 script-shell）；直接 node 调 CLI（与 build-admin.mjs 同款）
echo "[容器] node scripts/build-admin.mjs ..."
node scripts/build-admin.mjs

test -f "/w/${OUT_REL}/index.html" || { echo "[容器] ❌ 产物缺 index.html"; exit 4; }
echo "[容器] 构建完成，产物文件数: $(find "/w/${OUT_REL}" -type f | wc -l)"
CONTAINER_EOF

OUT_REL="$OUT_REL" PNPM_VER="$PNPM_VER" \
MSYS_NO_PATHCONV=1 \
docker run --name "$STAGE" \
  --memory "${BUILD_MEMORY:-4g}" \
  "${PROXY_ARGS[@]}" \
  -e npm_config_registry=https://registry.npmmirror.com \
  -e npm_config_script_shell=/bin/bash \
  -e PNPM_VER="$PNPM_VER" \
  -e OUT_REL="$OUT_REL" \
  --mount "type=bind,source=$TAR_WIN,target=/tmp/src.tar,readonly" \
  --mount "type=bind,source=$ROOT/.tmp/admin-diag-container.sh,target=/tmp/build.sh,readonly" \
  -i "node:${NODE_IMAGE_TAG:-24}" bash /tmp/build.sh >>"$LOG" 2>&1
RC=$?

if [ $RC -ne 0 ]; then
  echo "❌ 容器构建失败（rc=$RC），日志尾部："
  tail -40 "$LOG"
  docker rm -f "$STAGE" >/dev/null 2>&1
  rm -f "$TAR"
  exit 1
fi
echo "      ✅ 容器构建成功"

# ── 3/4 导出并逐文件比对 ────────────────────────────────────
echo "[3/4] 导出容器产物 → .tmp/admin-diag-out（不进 static/admin）"
mkdir -p "$DIAG"
if ! docker cp "$STAGE:/w/$OUT_REL/." "$DIAG" >>"$LOG" 2>&1; then
  echo "❌ docker cp 失败："; tail -10 "$LOG"
  docker rm -f "$STAGE" >/dev/null 2>&1; rm -f "$TAR"; exit 1
fi

# ── 4/4 差异报告 ────────────────────────────────────────────
echo "[4/4] 差异报告"
python "$ROOT/scripts/diff-admin-artifacts.py" "$DIAG"
DRC=$?

docker rm -f "$STAGE" >/dev/null 2>&1
rm -f "$TAR" "$ROOT/.tmp/admin-diag-container.sh"
rm -rf "$DIAG"
exit $DRC
