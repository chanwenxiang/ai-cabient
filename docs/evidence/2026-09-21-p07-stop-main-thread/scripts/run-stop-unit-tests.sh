#!/usr/bin/env bash
# 在**非 OneDrive 临时目录**里跑 edge/android-app 的 JVM 单测。
#
# 与 `docs/evidence/2026-09-19-p11-android-tests/scripts/run-android-unit-tests.sh` 同源
# （同一套 SDK / gradle 8.9 / 临时副本策略），只多一个能力：把 `--tests <类>` 透传给 gradle，
# 用于「先聚焦验新用例，再跑全量」的两段式流程（全量一趟好几分钟，编译错会白等）。
#
# 用法：
#   bash docs/evidence/2026-09-21-p07-stop-main-thread/scripts/run-stop-unit-tests.sh
#   bash docs/evidence/2026-09-21-p07-stop-main-thread/scripts/run-stop-unit-tests.sh \
#        '--tests=com.aicabinet.edge.service.ServiceShutdownTest'
#
# 判据同旧脚本：不只看退出码（无测试源时 gradle 也 exit 0），必须数 <testcase> 元素。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"

# ⚠️ $LOCALAPPDATA 在 Windows 上是反斜杠路径；直接拼 `/Temp/…` 会被 safe-delete 垫片
# 按相对路径解析并 fail-closed 拒绝。先统一成 POSIX 正斜杠。
_tmpbase="$(printf '%s' "${LOCALAPPDATA:-$TEMP}" | tr '\\' '/')"
ANDROID_SDK="${ANDROID_SDK:-$_tmpbase/Temp/aicabinet-android-sdk}"
BUILD_DIR="${BUILD_DIR:-$_tmpbase/Temp/aicabinet-android-stop}"
GRADLE_BIN="${GRADLE_BIN:-$HOME/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle}"
GRADLE_BAT="${GRADLE_BIN}.bat"

[ -x "$GRADLE_BIN" ] || {
  echo "RED  找不到 gradle 8.9：$GRADLE_BIN"
  exit 1
}
[ -f "$ANDROID_SDK/platforms/android-34/android.jar" ] || {
  echo "RED  SDK 缺 platforms/android-34/android.jar：$ANDROID_SDK"
  exit 1
}

# 清空必须**确证**成功：残留 app/build 会让「产物是否真被重新生成」失去判据。
# （这里不做 `rm -rf`：交给调用方给全新目录名，避免误删与本机 safe-delete 垫片纠缠。）
if [ -e "$BUILD_DIR" ]; then
  echo "RED  BUILD_DIR 已存在（$BUILD_DIR）—— 请换一个全新目录名，或手动清理后重跑"
  exit 1
fi

echo "== 同步源码到 $BUILD_DIR（排除 .gradle/build，避免旧产物假绿）=="
mkdir -p "$BUILD_DIR"
tar -C "$REPO_ROOT/edge/android-app" --exclude=.gradle --exclude=build --exclude=app/build -cf - . |
  tar -C "$BUILD_DIR" -xf -

printf 'sdk.dir=%s\n' "$(echo "$ANDROID_SDK" | sed 's|\\|/|g')" > "$BUILD_DIR/local.properties"

EXTRA_ARGS=("$@")
# gradle 本体是 POSIX sh 脚本，Windows 上直接 spawn 会 WinError 193；而 Git Bash 可以
# 直接执行同目录的 `gradle.bat`（无需再套 `cmd /c`，也避免从 Bash 调 cmd.exe 的额外限制）。
if [ -f "$GRADLE_BAT" ]; then
  GRADLE_CMD="$GRADLE_BAT"
else
  GRADLE_CMD="$GRADLE_BIN"
fi
echo "== gradle :app:testMockDebugUnitTest ${EXTRA_ARGS[*]:-（全量）}（入口 $GRADLE_CMD）=="
set +e
(
  cd "$BUILD_DIR"
  "$GRADLE_CMD" :app:testMockDebugUnitTest --console=plain "${EXTRA_ARGS[@]}"
)
GRADLE_EXIT=$?
set -e
echo "GRADLE_EXIT=$GRADLE_EXIT"

echo "== 判据：数 <testcase> 元素（不看退出码）=="
REPORT_DIR="$BUILD_DIR/app/build/test-results/testMockDebugUnitTest"
shopt -s nullglob
REPORTS=("$REPORT_DIR"/TEST-*.xml)
if [ ${#REPORTS[@]} -eq 0 ]; then
  echo "RED  没有测试报告（$REPORT_DIR）⇒ 用例根本没执行"
  exit 1
fi
TOTAL=0
for report in "${REPORTS[@]}"; do
  n=$(grep -o '<testcase ' "$report" | wc -l || true)
  TOTAL=$((TOTAL + n))
  if grep -qE '<failure|<error' "$report"; then
    echo "RED  $(basename "$report") 含 failure/error"
    exit 1
  fi
done
echo "ok   testcases=$TOTAL（${#REPORTS[@]} 份报告）"

exit "$GRADLE_EXIT"
