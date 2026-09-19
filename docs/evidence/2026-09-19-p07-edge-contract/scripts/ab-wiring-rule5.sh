#!/usr/bin/env bash
# check-audit-gates-wiring 规则 5（豁免锚点校验）A/B。
# 沙箱里复制 package.json + .github + scripts 三件套，门禁 root 自动落到沙箱，
# 删/改 CI 锚点看是否变红；绝不触碰真仓库。
export PATH="/usr/bin:/bin:$PATH"

REPO="C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet"
SB="/c/Users/cwx/AppData/Local/Temp/p07-wiring-sandbox"

setup() {
  rm -rf "$SB"; mkdir -p "$SB"
  cp "$REPO/package.json" "$SB/"
  cp -r "$REPO/.github" "$SB/"
  cp -r "$REPO/scripts" "$SB/"
}

run() {   # $1=标签
  local out rc
  out=$( cd "$SB" && node scripts/check-audit-gates-wiring.mjs 2>&1 ); rc=$?
  printf '%-46s rc=%d\n' "$1" "$rc"
  printf '%s\n' "$out" | sed 's/^/        | /'
}

echo "=========== 规则 5（豁免锚点）A/B ==========="
setup

echo; echo "--- 基线：沙箱与真仓库一致（期望绿）---"
run "baseline"

echo; echo "--- 漂移①：CI 删掉 'pnpm check:nav-perms'（check:shared 的锚点之一）---"
setup
sed -i '/pnpm check:nav-perms/d' "$SB/.github/workflows/ci.yml"
run "drift1 drop pnpm check:nav-perms"

echo; echo "--- 漂移②：CI 删掉 'pnpm check:audit-gates'（聚合门禁步骤整个消失）---"
setup
sed -i '/run: pnpm check:audit-gates$/d' "$SB/.github/workflows/ci.yml"
run "drift2 drop pnpm check:audit-gates"

echo; echo "--- 漂移③：把锚点命令改名（'pnpm build:packages' → 'pnpm build:pkgs'）---"
setup
sed -i 's#run: pnpm build:packages#run: pnpm build:pkgs#' "$SB/.github/workflows/ci.yml"
run "drift3 rename build:packages"

rm -rf "$SB"
echo; echo "=========== done ==========="
