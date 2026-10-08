#!/usr/bin/env bash
# 只读：验证 admin 产物构建的**确定性** —— 同一容器镜像、同一输入，连跑两次，
# 比对两次产物是否逐字节一致。
#
# 为什么需要：容器重建与提交产物已验证 0 差异，但 CI 仍报红 ⇒ 怀疑产物含
# 非确定性成分（时间戳/随机 hash/环境路径等），使「重建 ≠ 提交」成为常态。
# 本脚本把变量收敛到「同一份源码 + 同一 pnpm」，排除宿主差异。
#
# 用法：bash scripts/diag-admin-build-determinism.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/.tmp/admin-determinism.log"
TAR="$ROOT/.tmp/admin-det-src.tar"
OUT_REL="services/trade-service/src/main/resources/static/admin"
STAGE="admin-det-$$"
PNPM_VER="9.15.9"

mkdir -p "$ROOT/.tmp"
: > "$LOG"

echo "[1/3] 打包源码（含 scripts 与已跟踪的 packages/*/dist）"
if ! python "$ROOT/scripts/pack-admin-src.py" "$TAR" scripts >>"$LOG" 2>&1; then
  echo "❌ 打包失败"; tail -10 "$LOG"; exit 1
fi
TAR_WIN="$(cd "$(dirname "$TAR")" && pwd -W)/$(basename "$TAR")"
[ -f "$TAR_WIN" ] || { echo "❌ 源 tar 不存在：$TAR_WIN"; exit 1; }

# 容器内构建脚本：跑 N 次，把每次产物存到 /out/runN
cat > "$ROOT/.tmp/admin-det-container.sh" <<'CONTAINER_EOF'
set -euo pipefail
npm install -g "pnpm@${PNPM_VER}" >/dev/null 2>&1
test -f /tmp/src.tar || { echo "❌ 挂载退化"; exit 9; }
mkdir -p /w /out && cd /w
tar -xf /tmp/src.tar
pnpm install --frozen-lockfile >/dev/null 2>&1
for run in 1 2; do
  echo "[容器] === build run $run ==="
  node scripts/build-admin.mjs
  rm -rf "/out/run$run"
  mkdir -p "/out/run$run"
  cp -a "/w/${OUT_REL}/." "/out/run$run/"
  # 清掉被 gitignore 的 runtime-config.json（它每次都变，含本机 key，不参与比对）
  rm -f "/out/run$run/runtime-config.json"
  echo "[容器] run $run 文件数: $(find "/out/run$run" -type f | wc -l)"
done
CONTAINER_EOF

echo "[2/3] 容器内连跑两次构建"
MSYS_NO_PATHCONV=1 docker run --name "$STAGE" \
  --memory "${BUILD_MEMORY:-4g}" \
  -e npm_config_registry=https://registry.npmmirror.com \
  -e npm_config_script_shell=/bin/bash \
  -e PNPM_VER="$PNPM_VER" -e OUT_REL="$OUT_REL" \
  --mount "type=bind,source=$TAR_WIN,target=/tmp/src.tar,readonly" \
  --mount "type=bind,source=$ROOT/.tmp/admin-det-container.sh,target=/tmp/build.sh,readonly" \
  --mount "type=bind,source=$ROOT/.tmp,target=/out" \
  -i node:24 bash /tmp/build.sh >>"$LOG" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
  echo "❌ 容器构建失败（rc=$RC）："; tail -30 "$LOG"
  docker rm -f "$STAGE" >/dev/null 2>&1; rm -f "$TAR"; exit 1
fi

echo "[3/3] 比对两次构建的产物"
python - "$ROOT/.tmp/run1" "$ROOT/.tmp/run2" <<'PY'
import hashlib, os, sys
def scan(d):
    out={}
    for dp,_,fs in os.walk(d):
        for fn in fs:
            p=os.path.join(dp,fn)
            out[os.path.relpath(p,d).replace('\\','/')]=p
    return out
def h(p):
    return hashlib.sha256(open(p,'rb').read()).hexdigest()
a,b=sys.argv[1],sys.argv[2]
A,B=scan(a),scan(b)
print(f"run1={len(A)} 文件, run2={len(B)} 文件")
only_a=sorted(set(A)-set(B)); only_b=sorted(set(B)-set(A))
diff=[r for r in sorted(set(A)&set(B)) if h(A[r])!=h(B[r])]
print(f"内容不同={len(diff)}  仅run1={len(only_a)}  仅run2={len(only_b)}")
for r in diff[:25]: print("  D",r)
for r in only_a[:10]: print("  onlyA",r)
for r in only_b[:10]: print("  onlyB",r)
if not(diff or only_a or only_b):
    print("\n✅ 两次构建逐字节一致 ⇒ 构建是确定性的，产物差异另有原因")
    sys.exit(0)
print("\n❌ 构建非确定 ⇒ 这就是 CI「重建≠提交」的根因")
sys.exit(1)
PY
DRC=$?

docker rm -f "$STAGE" >/dev/null 2>&1
rm -f "$TAR" "$ROOT/.tmp/admin-det-container.sh"
rm -rf "$ROOT/.tmp/run1" "$ROOT/.tmp/run2"
exit $DRC
