#!/usr/bin/env bash
# 在**非 OneDrive 临时目录**里跑 edge/android-app 的 JVM 单测。
#
# 为什么不能在仓库目录里直接跑：
#   1. AGP 会在 app/build/ 下写上百个文件，OneDrive 同步会拖慢甚至卡死构建（本仓已踩过
#      vite 写 178 文件卡死的前例）；
#   2. 本机没有 gradle wrapper（仓库不含 gradlew / gradle-wrapper.jar），用的是
#      ~/.gradle/wrapper/dists 里已解压的 gradle 8.9 发行版 —— 与 CI 里
#      `gradle/actions/setup-gradle@v4` 指定的 8.9 同版本。
#
# 前置（首次需执行 install-android-sdk.sh；AGP 只用 platforms/ + build-tools/ + licenses/）：
#   ANDROID_SDK 指向 SDK 根，目录结构形如：
#     $ANDROID_SDK/platforms/android-34/android.jar
#     $ANDROID_SDK/build-tools/34.0.0/aapt2(.exe)
#     $ANDROID_SDK/licenses/android-sdk-license
#
# 判据：不只看 gradle 退出码 —— 无测试源时 gradle 同样 exit 0（NO-SOURCE）。
#       必须数 TEST-*.xml 里的 <testcase> 元素（见脚本末尾）。
set -euo pipefail

# <repo>/docs/evidence/<批次>/scripts/run-android-unit-tests.sh ⇒ 上溯 4 层到仓库根
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"

# ⚠️ $LOCALAPPDATA 在 Windows 上是 `C:\Users\…\AppData\Local`（反斜杠）。直接拼 `/Temp/…`
# 会得到 `C:\…\Local/Temp/…` 这种混合形式 —— 环境的 safe-delete 垫片会把它按相对路径
# 解析到 cwd 下、进而 fail-closed 拒绝删除。故先统一成 POSIX 正斜杠。
_tmpbase="$(printf '%s' "${LOCALAPPDATA:-$TEMP}" | tr '\\' '/')"
ANDROID_SDK="${ANDROID_SDK:-$_tmpbase/Temp/aicabinet-android-sdk}"
BUILD_DIR="${BUILD_DIR:-$_tmpbase/Temp/aicabinet-android-build}"
GRADLE_BIN="${GRADLE_BIN:-$HOME/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle}"

[ -x "$GRADLE_BIN" ] || {
  echo "RED  找不到 gradle 8.9 可执行文件：$GRADLE_BIN"
  exit 1
}
[ -f "$ANDROID_SDK/platforms/android-34/android.jar" ] || {
  echo "RED  SDK 缺 platforms/android-34/android.jar：$ANDROID_SDK"
  exit 1
}

echo "== 同步源码到 $BUILD_DIR（排除 .gradle/build，避免旧产物假绿）=="
rm -rf "$BUILD_DIR"
# 清空必须**确证**成功：残留 app/build 会让「编译产物是否真被重新生成」失去判据。
[ -e "$BUILD_DIR" ] && {
  echo "RED  无法清空 $BUILD_DIR（残留旧产物会让本次构建失去意义）"
  exit 1
}
mkdir -p "$BUILD_DIR"
tar -C "$REPO_ROOT/edge/android-app" --exclude=.gradle --exclude=build --exclude=app/build -cf - . |
  tar -C "$BUILD_DIR" -xf -

# sdk.dir 只写在临时副本里，不进仓库（本机各人 SDK 路径不同）。
printf 'sdk.dir=%s\n' "$(echo "$ANDROID_SDK" | sed 's|\\|/|g')" > "$BUILD_DIR/local.properties"

echo "== gradle :app:testMockDebugUnitTest =="
set +e
(
  cd "$BUILD_DIR"
  "$GRADLE_BIN" :app:testMockDebugUnitTest --console=plain --stacktrace
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
