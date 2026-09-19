#!/usr/bin/env bash
# 装一份**最小可用** Android SDK 给 AGP 编译 + JVM 单测用（无需 sdkmanager、无需 platform-tools）。
#
# 取证依据（本机实测）：AGP 编译与 testMockDebugUnitTest 只依赖三样——
#   1. $SDK/platforms/android-34/android.jar    （编译期 classpath）
#   2. $SDK/build-tools/34.0.0/{aapt2,d8,...}   （资源编译 / dex）
#   3. $SDK/licenses/android-sdk-license        （接受许可）
# 所以直接按下发清单 URL 拉 zip 解压即可，省掉 153MB 的 cmdline-tools。
# 唯一漏项 platform-tools 不必手工装：AGP 的 sdkDownload 默认为 true，只要 licenses 目录
# 里有 android-sdk-license，它会在首次构建时自己认出「缺 Android SDK Platform-Tools v37.0.1」
# 并下载安装（实测日志：`Preparing "Install Android SDK Platform-Tools v.37.0.1"` → finished）。
#
# ⚠️ 两个坑（都实测踩过）：
#   a) 下发清单里 build-tools/34.0.0 的 url 指向 **linux** 包；Windows 要用
#      build-tools_r34-windows.zip。清单是给 sdkmanager 按 host-os 挑的，手工下载必须自己选。
#   b) 解压后内层目录名不是版本号（build-tools 是 `android-14`，platform 是 `android-34`），
#      必须重命名成 `34.0.0` / `android-34`，否则 AGP 按目录名找不到组件。
#   c) curl 的 `--retry` 在没有 `-C -` 时**会截断重下**：慢链路下 15 分钟超时会白下 138MB。
#      要么去掉 --retry/给足 -m，要么加 `-C -` 续传。
set -euo pipefail

SDK="${ANDROID_SDK:-$LOCALAPPDATA/Temp/aicabinet-android-sdk}"
export ANDROID_SDK="$SDK" # 供下面的 python heredoc 读取
mkdir -p "$SDK"
cd "$SDK"

fetch() { # url 输出名
  local url="$1" out="$2"
  if [ -s "$out" ]; then
    echo "skip  $out 已存在（$(stat -c %s "$out") bytes）"
    return
  fi
  echo "get   $url"
  curl -fL -C - -m 3600 -o "$out" "$url"
}

fetch "https://dl.google.com/android/repository/build-tools_r34-windows.zip" "build-tools_r34-windows.zip"
fetch "https://dl.google.com/android/repository/platform-34-ext7_r03.zip" "platform-34-ext7_r03.zip"

echo "== 解压 build-tools（内层 android-14 ⇒ 34.0.0）=="
rm -rf _bt_extract "build-tools/34.0.0"
python - <<'PY'
import os, zipfile
sdk = os.environ.get("ANDROID_SDK") or os.path.join(os.environ["LOCALAPPDATA"], "Temp", "aicabinet-android-sdk")
zipfile.ZipFile(os.path.join(sdk, "build-tools_r34-windows.zip")).extractall(os.path.join(sdk, "_bt_extract"))
PY
mv "_bt_extract/android-14" "build-tools/34.0.0"
rmdir _bt_extract

echo "== 解压 platform（内层 android-34）=="
rm -rf _plat_extract "platforms/android-34"
python - <<'PY'
import os, zipfile
sdk = os.environ.get("ANDROID_SDK") or os.path.join(os.environ["LOCALAPPDATA"], "Temp", "aicabinet-android-sdk")
zipfile.ZipFile(os.path.join(sdk, "platform-34-ext7_r03.zip")).extractall(os.path.join(sdk, "_plat_extract"))
PY
mkdir -p platforms
mv "_plat_extract/android-34" "platforms/android-34"
rmdir _plat_extract

echo "== 接受许可 =="
mkdir -p licenses
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > licenses/android-sdk-license
printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' > licenses/android-sdk-preview-license

echo "== 结果 =="
ls -la platforms/android-34/android.jar build-tools/34.0.0/aapt2.exe licenses/android-sdk-license
