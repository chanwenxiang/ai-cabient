#!/usr/bin/env bash
# P0-10：在**最小 node 容器**里跑两端 mp 单测（Linux / CI 等价环境）。
#
# 为什么不在宿主机直接跑：
#   1. 本机 `.npmrc` 声明 `engine-strict=true`，engines 要求 node >= 24.18.0，而本机
#      bash 工具默认 node 是 22.x；`pnpm` 的 corepack shim 实测输出异常（`pnpm --version`
#      打出 `}` + `Node.js v24.18.0`，不是版本号）。
#   2. 宿主 `clients/*/node_modules` 是 **Windows 平台构建**的（esbuild/rollup 二进制为
#      win32-x64），Linux 容器无法复用；反之在容器里原地 install 会把宿主 node_modules
#      改写成 Linux 版，破坏本机开发环境。
#   ⇒ 所以：容器内**复制源码到 /build**（不挂载 node_modules），在容器内 install，
#      宿主 node_modules 一个字节都不动。
#
# 用法（仓库根目录）：
#   bash docs/evidence/2026-09-19-p10-mp-vitest/scripts/run-mp-unit-tests.sh [输出目录]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
OUT="${1:-${OUT_DIR:-$ROOT/.tmp/p10-mp-vitest}}"
mkdir -p "$OUT"

echo "[info] repo=$ROOT"
echo "[info] out=$OUT"

MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$ROOT:/src:ro" \
  -v "$OUT:/out" \
  node:24-slim sh -c '
mkdir -p /build/packages /build/clients
cp /src/package.json /src/pnpm-workspace.yaml /src/pnpm-lock.yaml /src/.npmrc /build/
tar -C /src/packages --exclude=node_modules -cf - . | tar -C /build/packages -xf -
for c in admin-vue consumer-mp merchant-mp; do
  mkdir -p /build/clients/$c
  tar -C /src/clients/$c --exclude=node_modules --exclude=output --exclude=dist -cf - . \
    | tar -C /build/clients/$c -xf -
done
cd /build
corepack enable >/dev/null 2>&1 || true
corepack prepare pnpm@9.15.9 --activate >/dev/null 2>&1 || true
pnpm config set registry https://registry.npmmirror.com >/dev/null
node -v
pnpm -v
pnpm install --no-frozen-lockfile > /out/install.log 2>&1; echo "INSTALL_EXIT=$?" | tee -a /out/install.log
pnpm --filter @aicabinet/consumer-mp test > /out/consumer-test.log 2>&1; echo "CONSUMER_EXIT=$?" >> /out/consumer-test.log
pnpm --filter @aicabinet/merchant-mp test > /out/merchant-test.log 2>&1; echo "MERCHANT_EXIT=$?" >> /out/merchant-test.log
cp /build/pnpm-lock.yaml /out/pnpm-lock.yaml
echo "=== consumer ==="; tail -25 /out/consumer-test.log
echo "=== merchant ==="; tail -25 /out/merchant-test.log
'

echo "[done] 日志在 $OUT"
