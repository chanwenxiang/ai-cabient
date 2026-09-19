# P0-11 — `edge/android-app` 测试基建（并修好它编译不过的既有缺陷）

日期：2026-09-19　提交：见本批 commit　基线 HEAD（改前）：`729b0b13`

> ## ⚠️ 状态更正（2026-09-19 稍晚，P0-12 接续本目录 —— 读前必看）
>
> 本目录下的两个漂移脚本（`scripts/ab-drift.py`、`scripts/gate-drift.mjs`）**在 P0-12 被扩展**，
> 所以**下文正文里的这些数字已过期**，请以下列当前值为准：
>
> | 项 | 下文（P0-11 当时） | **当前** |
> |---|---|---|
> | 测试用例 | 6 文件 / 40 条 | **10 文件 / 89 条**（P0-12 补入 4 个 Robolectric 测试类） |
> | A/B 注入漂移 | 8/8 | **17 条 / 17 全过** |
> | 门禁漂移 | G1–G8 / 8/8 | **G1–G10 / 10 全过** |
>
> P0-12 的结论（Robolectric 下 `AndroidKeyStore` **不可用** ⇒ 密钥**静默回退明文**；
> 抓出门禁自身「注释里的 `@Test`」假绿形态③；A/B 抓到我自己一条判据在注入下仍绿）
> 见 → **`docs/evidence/2026-09-19-p12-robolectric/README.md`**。
> 本文 P0-11 部分**保留为当时的快照**，不作为当前状态。

## 0. 一句话结论

`edge/android-app` 是**唯一零测试资产**的端，长期被记为「不可构建」（无 `ANDROID_HOME`、无 `gradlew`、
Gradle Kotlin DSL 不在 Maven reactor 里）。本批取证后证明：**它其实跑得起来**，而且——它此前
**根本编译不过**。本批做了三件事：

1. 取证并修掉 **2 个既有编译错误**（该端在进仓以来从未被任何 CI job 编译过）；
2. 建起 SDK + Gradle 单测链路，补 **6 个测试文件 / 40 条用例**；
3. 新增 `scripts/check-android-tests-wired.mjs`（聚合链第 4 位）+ CI job `edge-android`，
   把「有人守着」也钉住。

## 1. 最重要的发现：主源码编译不过（既有缺陷，非本批引入）

第一次跑 `:app:testMockDebugUnitTest` 直接在 `compileMockDebugKotlin` 失败：

```
e: .../hal/serial/ChzhSerialPort.kt:26:22 None of the following functions can be called with the arguments supplied:
public constructor SerialPort(p0: File, p1: Int) defined in android.serialport.SerialPort
public constructor SerialPort(p0: File, p1: Int, p2: Int, p3: Int, p4: Int) ...
public constructor SerialPort(p0: File, p1: Int, p2: Int, p3: Int, p4: Int, p5: Int) ...
e: .../mqtt/MqttDeviceClient.kt:169:48 Val cannot be reassigned
```

原始日志留档：`build-failed-preexisting-compile-errors.txt`

**归属取证**：这两处文件本批未改动（`git status` 只有 `ci.yml` / `build.gradle.kts` /
`ChzhLockDriver.kt` / `package.json` 四个已改文件 + 新增文件）；且 `git log --name-status deba9239`
显示 `deba9239` 是**整仓首次导入提交** ⇒ 错误随首次进仓就在。此前无任何 job 编译过该模块，
所以没人发现。

### 1.1 根因一：`ChzhSerialPort.kt` 用了不存在的 3 参构造器

```kotlin
serialPort = SerialPort(device, baudRate, 0)   // ← 编译不过
```

对 `com.licheedev:android-serialport:2.1.2` 的 AAR 解出 `classes.jar` 后 `javap` 实证：
类里只有 `(File,int)`、`(File,int,int,int,int)`、`(File,int,int,int,int,int)` 三种构造器，
**没有 3 参形式**。再看字节码，2 参构造器委托为
`dataBits=8, parity=0, stopBits=1, flags=0` —— 正好是注释所写的 **19200 8N1**。

⇒ 改为 `SerialPort(device, baudRate)`，**与原先「8N1 + flags=0」的意图逐项等价**。

### 1.2 根因二：`MqttDeviceClient.kt` 的 Kotlin 作用域遮蔽

```kotlin
private fun publishNow(topic: String, payload: ByteArray, qos: Int): Boolean {
    ...
    val msg = MqttMessage(payload).apply { qos = 1 }   // ← "Val cannot be reassigned"
```

`MqttMessage` 是 `org.eclipse.paho.client.mqttv3.MqttMessage`，`javap` 实证它**有**
`public int getQos()` 与 `public void setQos(int)` ⇒ 本该是 `var`，库没问题。

真因是 Kotlin 的简单名解析顺序：`apply { … }` 里写裸 `qos`，会**优先绑定外层函数的局部名字**
（`publishNow` 的形参 `qos: Int`），形参是 val ⇒ 报「Val cannot be reassigned」。
这是**作用域遮蔽**，不是库 API 变更。

⇒ 改为 `apply { this.qos = 1 }`，并留注释说明 `this.` 不能删。

### 1.3 修后验证

`BUILD SUCCESSFUL`（`gradle-baseline-pass.txt`）⇒ `edge/android-app` 现在**能编译**。

## 2. SDK：最小可用集（省掉 153MB）

AGP 编译 + JVM 单测只需要三样：`platforms/android-34/android.jar`、
`build-tools/34.0.0/*`、`licenses/android-sdk-license`。所以没装 `cmdline-tools`（153MB），
直接从下发清单 `repository2-3.xml` 取 URL 拉 zip 解压。

- 复现脚本：`scripts/install-android-sdk.sh`
- 装到非 OneDrive 的 `%LOCALAPPDATA%\Temp\aicabinet-android-sdk`（构建产物同理，避免 OneDrive 拖慢）

实测踩到的三个坑（都写进脚本注释）：

| 坑 | 实证 |
|---|---|
| 清单里 `build-tools;34.0.0` 的 url 指向 **linux** 包 | 手工下载必须自己选 `build-tools_r34-windows.zip` |
| 解压后内层目录名不是版本号 | build-tools 内层是 `android-14`、platform 内层是 `android-34` ⇒ 必须重命名成 `34.0.0` / `android-34` |
| `curl --retry` 无 `-C -` 会**截断重下** | 慢链路下 15 分钟超时把已下的 138MB 冲掉，只剩 3.6MB |

补一条**结论修正**：`platform-tools` 不必手工装 —— AGP 的 `sdkDownload` 默认开启，
首次构建自己认出「缺 Android SDK Platform-Tools v37.0.1」并下载
（日志：`Preparing "Install Android SDK Platform-Tools v.37.0.1"` → `finished`）。

## 3. 交付的测试（6 文件 / 40 条）

| 文件 | 条数 | 守的是什么 |
|---|---|---|
| `mqtt/OutboundMqttQueueCriticalTest.kt` | 13 | 队列满时**丢哪条**：判错类型名 ⇒ 关门/开锁/告警信令在弱网下被静默丢弃 |
| `hal/chzh/ChzhLockDriverFeedbackTest.kt` | 11 | 门磁回包 → 门状态，是 `CabinetController` 判「用户关门没」的**唯一**真相来源 |
| `config/KeystoreCipherTest.kt` | 5 | `getSecret` 全靠这一个判定分流：密文当明文用 ⇒ broker 拒鉴权且无报错线索 |
| `config/EdgeRuntimeConfigTest.kt` | 4 | `isPlaceholderDeviceId` 判宽/判严的后果：多机撞号 / 覆盖现场手工编号 |
| `video/VideoClipJsonTest.kt` | 4 | 上报给 trade-service 的双摄报文，字段错不会在端侧报错 |
| `video/RecordingResultTest.kt` | 3 | `fusionMode` 边界 `>= 2`，判错 ⇒ 双摄柜子以单摄上报，服务端永远等不到第二路 |

为可测性做了一处**行为零变化**的抽取：`ChzhLockDriver.parseDoorFeedback` 里的判定逻辑
提到 `doorStateFromFeedback(text): DoorState?`（`internal`），原方法改为
`val next = doorStateFromFeedback(text) ?: return`。

## 4. A/B 注入漂移：**8/8 符合期望**

脚本 `scripts/ab-drift.py`（注入到**临时构建副本**，不动仓库；跑完全部文件 sha256 逐字节回到基线）。

| 用例 | 注入 | 结果 |
|---|---|---|
| D1 | `CRITICAL_TYPES` 去掉 `DOOR` | 红，含预期用例 |
| D2 | 取消 payload.type 分支（只按 topic 判） | 红 5 条 |
| D3 | 门磁解析调换分支顺序 | 红，**恰好 1 条**（`关闭分支优先于打开分支`） |
| D4 | `capturedAt` 写成常量 `0` | 红 |
| D5 | `isPlaceholderDeviceId` 加 trim | 红，恰好 1 条 |
| D6 | `fusionMode` 边界改 `> 2` | 红，恰好 1 条 |
| D7 | 密文前缀 `enc:v1:` → `enc:v2:` | 红 3 条 |
| R1 | **反假红**：只加注释 | **仍绿** ✓ |

### 4.1 A/B 抓到了我自己的一条恒真判据（形态③）

D4 **第一版**的注入是「把 `now` 挪进 map，逐条取 `System.currentTimeMillis()`」——
结果**整场仍绿**（原始输出留档 `ab-drift-v1-D4-vacuous.txt`）：

```
FAIL  D4 capturedAt 改为逐条取 now ⇒ 红
      exit=0 失败用例=[]  ← 形态③：注入漂移后仍绿
```

原因：同一毫秒内两次 `currentTimeMillis()` 取值相同，于是断言
「两条 `capturedAt` 相等」**恒真**——它根本不可能失败。

处置（两件都做了）：

1. 该判据**改写**为能失败的形式：逐条断言时间戳落在本次调用窗口内
   （写成常量 `0` / 秒级时间戳都会红）；
2. 类注释里**显式记录**这条性质「刻意不写成判据」及原因，避免后人再把它加回来。

⇒ 这正是「假绿与假红同害」的一次实例：**没做注入漂移，就会带着一条骗人的判据上线。**

## 5. 静态门禁 `check:android-tests-wired`（聚合链第 4 位）

`scripts/check-android-tests-wired.mjs`，6 条规则，全部落在真值上（不搜关键词）：

1. `app/src/test/**` 必须真有文件且真含 `@Test`；
2. `testImplementation(...)` 必须存在（否则测试源码根本编译不了）；
3. 从 `build.gradle.kts` **解析 `productFlavors`**，再用它核对 CI 里的 `…UnitTest` 任务名
   （flavor 改名自动跟随，不写死 `testMockDebugUnitTest`）；
4. CI 的 `edge-android` job 里必须有**可执行的** gradle 命令（先剥整行 shell 注释）；
5. 必须有「测试真跑了」的判据，且**真的去数 `<testcase>` 元素**、并从
   `app/build/test-results` 取数（只断言 Failures/Errors 为 0，在「一条都没跑」时照样绿）；
6. **CI 调 gradle 的方式必须与仓库实际入口一致** —— 本仓无 `gradlew`，故禁止 `./gradlew`，
   且必须有 `uses:` 引入 Gradle 的步骤。

门禁自证（`scripts/gate-drift.mjs`）：**G1–G8 共 8 条期望红 + G7 反假红，8/8 符合期望**，
跑完全部受控文件 sha256 还原。原始输出 `gate-drift.txt`。

> 规则 6 是**本批真实踩的坑**：CI 第一版写的就是 `./gradlew`。因为此前没有任何 job 编译过
> `android-app`，这行从未被执行，所以留在文件里没人发现。G8 专门把这条钉住。

## 6. CI 接线

新增 job `edge-android`（`runs-on: ubuntu-latest`）：

- `gradle/actions/setup-gradle@v4` 指定 **gradle 8.9**（与本地构建同版本；仓库无 wrapper，
  故不能写 `./gradlew`）；该 action 自带依赖缓存，不需要手写 `actions/cache`；
- SDK 走 `actions/cache` + 首次 `sdkmanager` 安装，并**单独一步无条件**导出 `ANDROID_HOME`
  （SDK 命中缓存时安装步被跳过，env 就再没人写了）；
- 判据与 `build`/`integration` job 同款：**只看 `<testcase>` 元素个数**，不只看退出码。

## 7. 验证汇总

| 项 | 结果 | 证据文件 |
|---|---|---|
| 修前基线（编译失败） | `compileMockDebugKotlin` 红，2 个既有错误 | `build-failed-preexisting-compile-errors.txt` |
| 修后基线 | `BUILD SUCCESSFUL`；**40 个 `<testcase>`、0 失败** | `gradle-baseline-pass.txt`、`final-baseline.txt` |
| A/B 注入漂移 | **8/8** 符合期望，逐字节还原 | `ab-drift.txt` |
| A/B 首版（抓到恒真判据） | D4 仍绿 ⇒ 判据已改写 | `ab-drift-v1-D4-vacuous.txt` |
| 门禁自证 | **8/8**（含 G8 新规则） | `gate-drift.txt` |
| 聚合门禁链 | **29 个门禁、0 失败** | `aggregate-gates.txt` |

## 8. 复现

```bash
# 1) 装最小 SDK（约 60MB + 8MB platform-tools 由 AGP 自动补）
bash docs/evidence/2026-09-19-p11-android-tests/scripts/install-android-sdk.sh

# 2) 跑单测（自动同步到非 OneDrive 临时目录，数 <testcase> 判据）
bash docs/evidence/2026-09-19-p11-android-tests/scripts/run-android-unit-tests.sh

# 3) A/B 与门禁自证
python docs/evidence/2026-09-19-p11-android-tests/scripts/ab-drift.py
node   docs/evidence/2026-09-19-p11-android-tests/scripts/gate-drift.mjs
```

## 9. 仍未覆盖 / 需注意

- 本批只覆盖**纯 JVM 逻辑**（无 Android framework 依赖的部分）。`isReturnDefaultValues = true`
  让 `android.util.Log` 等未打桩方法返回默认值，故涉及 `Context` / `SharedPreferences` /
  `AndroidKeyStore` / 串口真读写的路径**仍未覆盖**——要覆盖需引 Robolectric 或仪器化测试，另立项。
- **`edge/android-app` 之前从未在 CI 里编译过**，本批首次接入。首次真实 CI 运行仍可能有
  环境差异（如 `setup-gradle` 的版本解析、SDK 缓存 key 失效），届时以 CI 日志为准。
- `VideoClipJsonTest` 里那条「两路 capturedAt 是否共享」的性质**故意没写成判据**，
  原因见 §4.1 与测试类注释；要钉住需先把时钟做成可注入的 seam（改生产签名）。
