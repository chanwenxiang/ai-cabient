# Android（edge/android-app）构建配方

> **2026-10-07 实测打通**。此前本仓 Android 代码**无法本地编译**（无 gradle wrapper + 无 SDK），
> 导致 V317 的 OTA 签名校验只有「人工核对」没有编译证据 —— 详见本文末尾的教训。

## 环境现状（本机）

| 项 | 值 | 备注 |
|---|---|---|
| JDK | `C:\Program Files\Java\jdk-17.0.17` | AGP 8.2 **要求 17**，本机已装 |
| Gradle | `C:\Users\cwx\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat` | **本仓无 wrapper**，用这个缓存 |
| Android SDK | `C:\Users\cwx\android-sdk` | 2026-10-07 新装（platform 34 + build-tools 34.0.0） |
| AGP / Kotlin | 8.2.2 / 1.9.22 | 见 `edge/android-app/build.gradle.kts` |

## 构建命令

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.17"
$env:ANDROID_HOME = "C:\Users\cwx\android-sdk"
$env:ANDROID_SDK_ROOT = "C:\Users\cwx\android-sdk"
$g = "C:\Users\cwx\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat"
cd "D:\ai-generated code\ai-cabinet\edge\android-app"

& $g :app:compileMockDebugKotlin --offline     # 编译 Kotlin
& $g :app:testMockDebugUnitTest --offline     # 单元测试
```

### 🔴 flavor 有两个，任务名必须写全

`device` / `mock` 两个 flavor ⇒ `compileDebugKotlin` 会报
`task 'compileDebugKotlin' is ambiguous`。可用任务：
`compileMockDebugKotlin`、`compileDeviceDebugKotlin`、`testMockDebugUnitTest` 等。

### 🔴 别只看退出码
gradle 在**无测试源**时同样 `exit 0`（NO-SOURCE）。判据要看 surefire 产物：
```
app/build/test-results/testMockDebugUnitTest/*.xml → grep tests="N"
```
（这与 CI `edge-android` job 里那句注释是同一件事。）

## 首次安装 SDK（换机器时）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.17"
$sdk = "C:\Users\cwx\android-sdk"
New-Item -ItemType Directory -Force -Path "$sdk\cmdline-tools" | Out-Null
Set-Location $sdk
curl.exe -L -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
Expand-Archive cmdline-tools.zip -DestinationPath "$sdk\cmdline-tools" -Force
# ⚠️ PowerShell 里**不要用 cmd /c**（工具会拦）⇒ 用管道喂 y
1..30 | ForEach-Object { "y" } | & "$sdk\cmdline-tools\bin\sdkmanager.bat" --sdk_root="$sdk" --licenses
& "$sdk\cmdline-tools\bin\sdkmanager.bat" --sdk_root="$sdk" `
    "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

⚠️ **不要挂载宿主目录进容器跑包管理器**（gradle 会判定「模块目录需清空重装」
从而**删除宿主的 `node_modules`/缓存**）。本配方全程在宿主上跑，不涉及容器。

## 🔴 2026-10-07 的教训：人工核对 Android 代码 = 没有证据

V317 写完 `ApkSignatureVerifier` 后我做了人工核对（import 齐、`OtaInstallMode.FAILED` 存在、
`@Suppress` 位置对、同 package 无需 import），**自认为没问题**，还写进了待完成清单备注。

**编译一跑就抓到真 bug**：
```kotlin
// ❌ 我写的
val matched = candidate.any { cand -> installed.any { it.contentEquals(cand) } }
// 🔴 installed: List<Signature>，而 contentEquals 是 Array<Byte> 的扩展函数
//   ⇒ Unresolved reference + inferred type is Unit but Boolean was expected
```

🔴 **更危险的连带问题**：`Signature` 是 Java 类、**不实现 `equals`** ——
若图省事写 `it == cand` 会**永远为 false** ⇒ **所有 OTA 都会被拒**。
这不是「编译不过」的小瑕疵，而是**一个能静默把功能打死**的坑。

⇒ **纪律**：
1. Android 侧代码**没有编译证据就不算完成**，人工核对不算证据；
2. 「未验证」要**显式记进待完成**，不能含糊过去；
3. 有了工具链就**先跑一遍再提交**（本仓 CI 的 `edge-android` job 也能兜，
   但本地能跑就别等 CI）。

## 仍未验证

- 🔴 **G6 装机验证**：`ApkSignatureVerifier` 编译+单测通过，但**未在真机跑过**。
  装机必须验两件事：
  1. **旧 APK 能正常升级**（防「基线取不到就乱拒」）
  2. **用自签（不同密钥）的包被拒绝**（这才是这个检查的目的）
