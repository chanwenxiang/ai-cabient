#!/usr/bin/env bash
# 只读诊断（**决定性**）：在容器里完整复刻 CI 的 admin-artifacts job ——
#   git clone（拿到与 CI 相同的 checkout，含已提交的 213 个产物文件）
#     → pnpm install --frozen-lockfile
#     → node scripts/build-admin.mjs
#     → git status --porcelain -uall -- services/.../static/admin   ← CI 的判据原文
# 并把该命令的**原样输出**打出来。
#
# 为什么必须 clone 而不是打包源码：
#   先前诊断把源码打进 tar，容器里 static/admin 是**空目录**再由 vite 生成；
#   而 CI 是 checkout 出**已提交的 213 个产物**再让 vite 的 emptyOutDir 覆盖。
#   若某个已提交文件本轮不再产出，CI 会报 " D"（删除），而空目录起步的复现**看不到**。
#   这是先前复现与 CI 唯一的结构性差异，必须消掉。
#
# 用法：bash scripts/diag-admin-ci-replica.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/.tmp/admin-ci-replica.log"
STAGE="admin-replica-$$"
OUT_REL="services/trade-service/src/main/resources/static/admin"
NODE_TAG="${NODE_IMAGE_TAG:-24.18.0}"
PNPM_VER="9.15.9"

mkdir -p "$ROOT/.tmp"
: > "$LOG"
ROOT_WIN="$(pwd -W)"

cat > "$ROOT/.tmp/admin-replica-container.sh" <<'CONTAINER_EOF'
set -uo pipefail
npm install -g "pnpm@${PNPM_VER}" >/dev/null 2>&1
echo "[容器] node=$(node -v)  pnpm=$(pnpm --version)"

echo "[容器] git clone 宿主仓库（模拟 actions/checkout）"
git clone --quiet --no-hardlinks /src /w/repo 2>&1 | tail -3
cd /w/repo
echo "[容器] HEAD=$(git rev-parse --short HEAD)"
echo "[容器] checkout 出的产物文件数: $(find "${OUT_REL}" -type f | wc -l)"
echo "[容器] 构建前工作区是否干净: $(git status --porcelain -uall -- "${OUT_REL}" | wc -l) 处改动"

echo "[容器] pnpm install --frozen-lockfile"
pnpm install --frozen-lockfile 2>&1 | tail -3

echo "[容器] node scripts/build-admin.mjs"
node scripts/build-admin.mjs 2>&1 | tail -6

echo "[容器] ===== CI 判据原文 ====="
echo "[容器] git status --porcelain -uall -- ${OUT_REL}"
git status --porcelain -uall -- "${OUT_REL}"
echo "[容器] ===== 判据结束（上面若为空 =绿）====="
CHANGED="$(git status --porcelain -uall -- "${OUT_REL}")"
if [ -n "$CHANGED" ]; then
  echo "[容器] ❌ 共 $(printf '%s\n' "$CHANGED" | wc -l) 处差异，明细："
  printf '%s\n' "$CHANGED"
  # 定位差异根源：对每个变动的文件，比较工作区字节与 HEAD blob
  echo "[容器] ---- 逐文件定位（工作区 vs HEAD blob）----"
  printf '%s\n' "$CHANGED" | while IFS= read -r line; do
    st="$(printf '%s' "$line" | cut -c1-2)"
    path="$(printf '%s' "$line" | cut -c4-)"
    case "$st" in
      R*|*R*) echo "  [重命名] $path" ;;
      *)
        if git cat-file -e "HEAD:$path" 2>/dev/null; then
          if cmp -s "$path" <(git cat-file blob "HEAD:$path"); then
            echo "  [内容相同!] $path  ← 疑为行尾/过滤器差异或 mtime"
            echo "      工作区 CRLF数=$(grep -c $'\r' "$path" 2>/dev/null || echo 0)"
          else
            echo "  [内容不同] $path  工作区=$(wc -c <"$path")B  HEAD=$(git cat-file -s "HEAD:$path")B"
          fi
        else
          echo "  [新增] $path  (工作区有、HEAD 无)"
        fi
        ;;
    esac
  done
  exit 1
fi
echo "[容器] ✅ 产物与提交同源 —— 本地复现为绿"
CONTAINER_EOF

echo "[容器] node:${NODE_TAG} 复刻 CI admin-artifacts ..."
MSYS_NO_PATHCONV=1 docker run --name "$STAGE" \
  --memory "${BUILD_MEMORY:-4g}" \
  -e npm_config_registry=https://registry.npmmirror.com \
  -e npm_config_script_shell=/bin/bash \
  -e PNPM_VER="$PNPM_VER" -e OUT_REL="$OUT_REL" \
  --mount "type=bind,source=$ROOT_WIN,target=/src,readonly" \
  --mount "type=bind,source=$ROOT/.tmp/admin-replica-container.sh,target=/tmp/replica.sh,readonly" \
  -i "node:${NODE_TAG}" bash /tmp/replica.sh 2>&1 | tee -a "$LOG" | grep -vE "^\+ |Progress:|^Packages:|^\.\.\." | tail -70
RC=${PIPESTATUS[0]}

docker rm -f "$STAGE" >/dev/null 2>&1
rm -f "$ROOT/.tmp/admin-replica-container.sh"
exit $RC
