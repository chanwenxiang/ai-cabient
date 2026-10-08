# APK 正式签名与发布流程（B3/G7 执行手册）

> 目的：把 B3「签名证书怎么走」的最后一步（= G7 正式发布密钥生成）压成
> 「照本手册在离线机器上敲 10 分钟命令」。手册本身**不含任何密钥材料**，可入仓库。
>
> 决策依据：AOSP 官方 `source.android.com/docs/core/ota/sign_builds`
> （详见 `docs/TODO_BLOCKED_2026-10-07.md` §2026-10-07 16:30 段）——
> **自建发布密钥 + 公钥内置 + 验签，不需第三方托管**。
> OTA 验签已强制落地：`OtaInstaller.kt:66`（V317），失败直接拒绝、无降级。

## 一、为什么必须自建专用密钥（30 秒版）

| 用 debug key 签名 | 后果 |
|---|---|
| 无法商用上架 | debug key 公开，任何人可签同签名包 |
| OTA 验签形同虚设 | `ApkSignatureVerifier` 校验的是「与已装应用同签名」——debug key 挡不住真攻击者 |
| 证书指纹不稳定 | 每台机器 debug key 不同 ⇒ 指纹 paper trail 失效 |

## 二、生成正式密钥（离线机器执行，一次性）

```bash
keytool -genkeypair -v \
  -keystore aicabinet-release.keystore \
  -alias aicabinet-release \
  -keyalg RSA -keysize 3072 \
  -validity 10950 \
  -storetype PKCS12
```

- **RSA 3072**：不低于 2048，OTA 场景密钥要用十年以上，取高不取低。
- **有效期 10950 天（30 年）**：证书过期 = 所有存量设备拒收 OTA 包。
  Android v3 签名体系支持密钥轮换（key rotation），但那是救火手段，
  首选「一个密钥用到底 + 永不丢失」。
- 密码要求：store 密码与 key 密码 **各自独立、≥20 位、密码管理器存储**，
  不写在任何文件里。
- 组织信息：真实填写（公司名/部门），指纹会进发布记录，别留 test/123。

## 三、备份规范（🔴 丢失 = 存量设备永远无法 OTA）

1. `aicabinet-release.keystore` + 两个密码 **分开存放**：
   - keystore 文件：加密压缩包（密码另设）→ U 盘 A（日常保险柜）+ U 盘 B（异地/家里）
   - 两个密码：密码管理器（1Password/Bitwarden 等）
2. **恢复演练一次**：用备份 U 盘在另一台机器 `keytool -list` 能看到指纹才算备份成立。
3. **绝不**：提交进 git（`.gitignore` 已覆盖 `*.jks`/`*.keystore`）、
   放 CI 明文变量、放网盘明文、发给任何人（包括「帮忙看看」）。

## 四、gradle 接线（CI / 本地通用，凭据走环境变量）

`app/build.gradle.kts` 追加（凭据全部读环境变量，**不硬编码**，铁律 27）：

```kotlin
signingConfigs {
    create("release") {
        val kstoreFile = System.getenv("KSTOREFILE")
        if (kstoreFile != null) {
            storeFile = file(kstoreFile)
            storePassword = System.getenv("KSTOREPWD")
            keyAlias = System.getenv("KEYALIAS")
            keyPassword = System.getenv("KEYPWD")
        }
        // KSTOREFILE 未设置时不配置 release 签名 —— 构建 fail 而不是静默用 debug key
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

CI（GitHub Actions 示例）：

```yaml
- run: ./gradlew assembleRelease
  env:
    KSTOREFILE: ${{ secrets.OTA_KSTORE_FILE_B64 }}   # base64 后 step 里先 decode
    KSTOREPWD: ${{ secrets.OTA_KSTORE_PWD }}
    KEYALIAS: aicabinet-release
    KEYPWD: ${{ secrets.OTA_KEY_PWD }}
```

> 🔴 加一道保险（建议）：release 构建前断言
> `signingConfig.storeFile != null`，否则显式失败——
> 防止「环境变量没配 → 拿 debug key 签了个 release 包发出去」。

## 五、发布 checklist（每次发版走一遍）

1. [ ] 本次 APK 用 release 密钥签名：`apksigner verify --print-certs app-release.apk`
2. [ ] 输出的 SHA-256 指纹 == 登记指纹（见下表）；不一致 **立即停发**
3. [ ] APK 文件 SHA-256 登记进发布记录（防传输篡改的另一半，与 OTA 验签互补）
4. [ ] OTA 服务端上传的包与本地构建产物字节数一致

### 证书指纹登记表（生成后立即填写）

| 项 | 值 | 填写日期 |
|---|---|---|
| SHA-256 指纹 | `0A:E6:11:E5:CC:AC:A9:AD:0E:4D:EF:2B:C5:38:37:95:DA:ED:50:38:8F:A0:64:1D:7C:FC:B8:93:86:A9:6D:DF` | 2026-10-08 |
| 别名 | `aicabinet-release` | 2026-10-08 |
| 有效期 | **2026-10-08 20:12:03 ～ 2056-09-30 20:12:03**（10,950 天 ≈ 30 年） | 2026-10-08 |
| 备份位置 | ✅ 已完成（2026-10-08，用户确认；介质：加密 U 盘 ×2） | 2026-10-08 |
| 恢复演练 | ✅ 已完成（2026-10-08，用户确认，备份介质指纹核对通过） | 2026-10-08 |

> gradle 接线已于 2026-10-08 完成（`app/build.gradle.kts`：signingConfigs 走环境变量 +
> Release 任务 doFirst 断言空凭据显式失败）；配置阶段经 Gradle 8.9 `help --offline` 验证通过。

## 六、与既有机制的关系

| 机制 | 状态 | 本手册补什么 |
|---|---|---|
| `ApkSignatureVerifier`（V317） | ✅ 已落地且强制（失败拒装、无降级） | 提供被校验的「正确签名」 |
| 下载校验 SHA-256 | ✅ 已有（`downloadAndVerify`） | 防传输损坏/篡改；签名防「攻击者连哈希一起改」 |
| G6 真机验证 | ⬜ 未做 | 密钥生成后才能测「旧包可升级 + 自签包被拒」两条 |

## 七、完成标准（B3/G7 销项判据）

- [ ] keystore 已生成（离线机器），指纹填入上表
- [ ] 备份 2 份介质 + 密码入密码管理器 + 恢复演练通过
- [ ] `build.gradle.kts` signingConfig 接线 + 空凭据显式失败
- [ ] G6 真机：release 包 → OTA 升级成功；自签第三方包被拒

## 八、G6 真机双验证流程（等真机到位照做，2026-10-08 备好）

> 🔴 **先懂一个坑再动手**：`ApkSignatureVerifier.verifyMatchesInstalled` 校验的是
> 「新包签名 == 设备上已装应用的签名」，且 Android 系统本身也拒绝签名不一致的
> 覆盖安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。所以：
> - **debug 包 → release 包不能走 OTA**（签名切换，会被正确拒绝——不是 bug）
> - 首次切到正式签名包必须 **adb uninstall + adb install**
> - OTA 链路只在**同签名**的版本间工作（这也正是验签要防的场景）

### 前置

- 真机一台（柜机或测试安卓设备），已预置 DeviceOwner（静默安装依赖它，见 `OtaInstaller.isDeviceOwner`）
- 有 Android SDK 的构建环境（本机 2026-10-08 探测无 SDK；用有 SDK 的机器/CI）
- 四个环境变量：`KSTOREFILE`/`KSTOREPWD`/`KEYALIAS=aicabinet-release`/`KEYPWD`

### ① 正向：同签名 OTA 升级成功

1. 构建 release 包：
   `KSTOREFILE=... KSTOREPWD=... KEYALIAS=aicabinet-release KEYPWD=... ./gradlew :app:assembleDeviceRelease`
2. `adb install` 装上设备（首次切换，卸载旧 debug 包后装）
3. 用**同一密钥**改 `versionCode` 再构建 v2，上传 OTA 服务端，触发下发
4. **通过判据**：设备静默升级到 v2；`OtaInstallResult` 非 FAILED；应用数据保留

### ② 负向：自签/篡改包被拒

1. 用 **debug key**（或任意其它密钥）签一个 versionCode 更高的包，走 OTA 下发
2. **通过判据**：设备拒绝安装，OTA 结果上报 `FAILED`，消息为
   「安装包签名与当前应用不一致（疑似非本方发布的包），已拒绝安装」（`OtaInstaller.kt:69`）
3. 补充：改包字节但保留原签名 → SHA-256 下载校验先一步拒绝（两道防线各验一次）
