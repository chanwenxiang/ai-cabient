# P0-7 子项①：edge `PrefsJsonQueue` 主线程 commit —— 根因修复 + 判据加固

日期：**2026-09-21**（第四十七轮）
范围：`edge/android-app`（Kotlin/Gradle 端）+ 1 个门禁脚本。对应竞品路线图 **P0-7**
「edge 端基础测试与风险排除：`PrefsJsonQueue` 主线程 commit、MQTT 集成测、模拟器↔device↔trade 契约测试」
中的**第 1 项**（后两项已于 2026-09-19 收口，见 `docs/evidence/2026-09-19-p07-edge-contract/`）。

## 一句话结论

**这是一个真缺陷，不是理论风险。** `CabinetService.onCreate()`（Android 保证跑在主线程）**直接同步**
调用 `CabinetController.start()`，而该函数里：

| # | 阻塞操作 | 位置 |
| --- | --- | --- |
| 1 | OkHttp 同步 `execute()`（OTA 检查） | `ota/OtaChecker.kt:37` ← `service/CabinetController.kt:55` |
| 2 | 串口 `open()` | `hal/chzh/ChzhLockDriver.kt:24-31` ← `CabinetController.kt:50` |
| 3 | Paho `MqttClient.connect()`（阻塞等待，最长 `connectionTimeout=10s`） | `mqtt/MqttDeviceClient.kt:64` ← `CabinetController.kt:69` |
| 4 | **`PrefsJsonQueue.save()` 的同步 `commit()`** | `queue/PrefsJsonQueue.kt` ← `MqttDeviceClient.kt:67 → OutboundMqttQueue.drain() → store.mutate()` |

第 4 条就是 P0-7 点名的那一项，而且 `flushOutbound()` 是**无条件**调用 `drain()`：

```kotlin
private fun flushOutbound() {
    if (!::client.isInitialized || !client.isConnected) return
    val pending = outboundQueue.size()
    if (pending > 0) { Log.i(TAG, "flushing mqtt queue size=$pending") }
    outboundQueue.drain(          // ← 队列为空也照调 ⇒ drain 内部必然 mutate ⇒ 必然 commit
        publish = { ... }, onAbandon = { ... }
    )
}
```

⇒ **每次 App 启动都把一次同步刷盘落在主线程上**（不是偶发）。

**修复**：整段启动移出主线程。

```kotlin
serviceScope.launch(Dispatchers.IO) {
    CabinetForegroundService.getController(applicationContext).start()
}
```

**加固**：`PrefsJsonQueue` 新增主线程写检测（WARN + 可注入回调 `onMainThreadWrite`），
配 **两向** Robolectric 判据 —— 让这类回归能被判据抓住，而不是靠人读调用链。

**顺带揪出并根治了另一处判据缺陷**（详见下方矩阵 ②）：既有门禁
`check-edge-queue-threading` 的 `stripComments()` 在 **CRLF 检出下整片空转** ⇒
任何 `//` 注释里提到过队列 API 名的文件都被当成真调用点 ⇒ **本机假红 / CI 假绿**。
本次改动正是踩中了它，才暴露出来。

## 证据清单

| 文件 | 内容 | 关键数字 |
| --- | --- | --- |
| `logs/edge-baseline.log` | 改前全量 `gradle :app:testMockDebugUnitTest`（模块根 fresh 副本） | `testcases=89`（10 份报告）／`BUILD SUCCESSFUL` |
| `logs/edge-after.log` | 改后全量同命令 | `testcases=91`（11 份报告）／`BUILD SUCCESSFUL` |
| `logs/ab-main-thread.log` | 矩阵①驱动输出 | 3 状态全部符合预期 |
| `logs/ab-baseline.log` / `ab-drift-never.log` / `ab-drift-always.log` | 矩阵①各状态 gradle 日志 | 见矩阵① |
| `logs/ab-queue-threading-gate.log` | 矩阵②驱动输出（旧臂 vs 新臂 × 9 注入） | 18 格全部符合预期 |
| `logs/gate-matrix/gate-{old,new}-*.log` | 矩阵②逐格门禁 stdout/stderr | 18 份 |
| `logs/gates-with-nopipe.log` | `node scripts/run-audit-gates.mjs` 聚合 | **34 个门禁，失败 0 个** |
| `logs/gates-without-nopipe-false-red.log` | 同命令**不加** nopipe 预载 | 34/34 全红、`exit=null`、2 秒 ⇒ 本机**环境假红**（已知口径） |
| `scripts/ab-main-thread-guard.py` | 矩阵①驱动（可重跑） | — |
| `scripts/ab-queue-threading-gate.py` | 矩阵②驱动（可重跑） | — |

## 改动清单

| 文件 | 改动 |
| --- | --- |
| `edge/android-app/app/src/main/java/com/aicabinet/edge/service/CabinetService.kt` | `start()` 移入 `serviceScope.launch(Dispatchers.IO)`（+ `import launch`） |
| `edge/android-app/app/src/main/java/com/aicabinet/edge/queue/PrefsJsonQueue.kt` | `save()` 加主线程写检测；构造器加 `onMainThreadWrite`；`companion` 加 `MAIN_THREAD_WRITE_MSG` + `isOnMainThread()` |
| `edge/android-app/app/src/test/java/com/aicabinet/edge/queue/PrefsJsonQueueMainThreadTest.kt` | **新增**，2 个用例（两向） |
| `scripts/check-edge-queue-threading.mjs` | 修 `stripComments()` 的 CRLF 空转 + 行号错位 |

## A/B 矩阵 ①：`PrefsJsonQueueMainThreadTest` 的两向断言

判据（`queue/PrefsJsonQueueMainThreadTest.kt`）：

| 用例 | 断言 |
| --- | --- |
| `主线程写_两条写路径都被检测到_且数据真的落盘` | `replaceAll` + `mutate` 各上报 1 次（回调 **和** ShadowLog 各 2 条），**且** `snapshot()==["a","b"]` |
| `后台线程写_不得误报_否则判据恒真` | 后台线程里 `mutate` ⇒ 回调 0 次、ShadowLog 0 条，**且**数据真落盘 |

注入漂移（`scripts/ab-main-thread-guard.py`，沙箱 = `%TEMP%/aicabinet-android-ab`）：

| # | 注入 | 期望 | 实测 | 挂在哪一行 |
| --- | --- | --- | --- | --- |
| 基线 | 无 | 绿 | **绿**（`testcase=2 failure=0`） | — |
| ① | `isOnMainThread()` 恒 `false` | 红 | **红**（`failure=1`） | `PrefsJsonQueueMainThreadTest.kt:72` ⇒ 主线程用例 |
| ② | `isOnMainThread()` 恒 `true` | 红 | **红**（`failure=1`） | `PrefsJsonQueueMainThreadTest.kt:93` ⇒ 后台线程用例 |

关键点：两次漂移**各自打掉的是对应用例**（`:72` vs `:93`），所以「会抓真回归」与「会抓误报」
**两件事都被证明了**，判据不是装饰。仓库文件 sha256 前后一致（脚本自证）。

## A/B 矩阵 ②：`check-edge-queue-threading` 旧臂 vs 新臂

发现的缺陷：`stripComments()` 原实现

```js
source.split('\n').map((line) => line.replace(/\/\/.*$/, ''))   // ❌
```

`edge/**` 在 Windows 检出下是 **CRLF**（`.gitattributes` 只约束 `clients/**`），每行以 `\r` 结尾；
`.` 不匹配 `\r`，而 `$`（不带 `m`）只匹配**输入串尾** ⇒ 该正则**永不匹配** ⇒ 剥离整片空转。
次要缺陷：`.filter()` 丢行 ⇒ 报出来的「第 N 行」整体错位（实测把 `PrefsJsonQueue.kt` 的第 63 行
报成第 49 行）。

`scripts/ab-queue-threading-gate.py`：旧臂 = `git show HEAD:scripts/check-edge-queue-threading.mjs`，
新臂 = 工作区；注入只发生在 `%TEMP%/aicabinet-gate-ab`。

| # | 注入 | 源行尾 | 旧臂 | 新臂 | 读法 |
| --- | --- | --- | --- | --- | --- |
| 1 | 无 | LF | 绿 | 绿 | CI 检出（LF）下门禁本来是对的 |
| 2 | 无 | CRLF | 绿 | 绿 | — |
| 3 | `//` 注释里提到 `drain(` | LF | 绿 | 绿 | 同上 |
| 4 | `//` 注释里提到 `drain(` | **CRLF** | **红**❌ | **绿** | **缺陷本身**：LF 绿 / CRLF 假红 |
| 5 | 无（**工作区真实源**） | CRLF | **红**❌ | **绿** | **本次实际踩中的假红** |
| 6 | KDoc 里提到 `mutate(`（对照） | CRLF | 绿 | 绿 | KDoc 的 `filter` 那条路一直有效 |
| 7 | 真新增未声明 `enqueue(` | CRLF | 红 | 红 | 判据**没被削弱** |
| 8 | Scope 改 `Dispatchers.Main` | CRLF | 红 | 红 | 同上 |
| 9 | 已声明条目改名失效 | CRLF | 红 | 红 | 反向检测（清单不许腐烂）也仍在 |

⚠️ 这张矩阵的**自变量包含「源的行尾」**——判据读源码文本时，行尾会改变结论。这与
`PROJECT-REFERENCE.md` §11.32③ 是同一条律（「按行解析＋`$` 锚定遇 `\r\n` 整条不匹配」）。

修后 `check-edge-queue-threading` 输出：**接触点 12 处／已声明 10 条**，无失效条目。

## 复现步骤

```bash
# 1) edge 全量单测（判据只看 <testcase> 个数与 <failure>/<error>，不看退出码）
bash docs/evidence/2026-09-19-p11-android-tests/scripts/run-android-unit-tests.sh   # 期望 testcases=91

# 2) 两向判据 A/B（沙箱，不动仓库；跑完自证仓库 sha256 未变）
python docs/evidence/2026-09-21-p07-prefs-main-thread/scripts/ab-main-thread-guard.py

# 3) 门禁 A/B（旧臂 vs 新臂 × 9 注入，纯 node，秒级）
python docs/evidence/2026-09-21-p07-prefs-main-thread/scripts/ab-queue-threading-gate.py

# 4) 聚合门禁（本机必须预载 nopipe，否则 34/34 全红是环境假红）
NODE_OPTIONS="--require=$PWD/.tmp/tools/nopipe.cjs" node scripts/run-audit-gates.mjs
```

## 已知 / 未做

- 🔴 **真机 / 仪器化未验**：本机无 emulator、无真机，只做到「编译通过 + JVM 单测 + 静态判据」。
  「移出主线程」这一改动的**运行期**效果（启动不再阻塞 UI、OTA 检查开始真正生效）**未在设备上验证**。
- 🔴 **`CabinetService.onDestroy()` 里的 `mqtt.disconnect()` 仍是主线程阻塞**（同一族缺陷，本批**未改**）：
  Paho 的 `disconnect()` 同样是 `waitForCompletion(...)` 阻塞。改它要把关闭流程挪出被
  `serviceScope.cancel()` 立刻取消的范围（需要独立的关闭作用域），属独立决策 —— 不顺手改。
- ⚠️ **OTA 检查可能从未生效**（连带发现，未验证）：`OtaChecker.checkOnStartup` 在主线程做
  OkHttp 同步请求；若平台抛 `NetworkOnMainThreadException`，`catch (e: Exception)`
  会把它吞成一行 `OTA check skipped`（`ota/OtaChecker.kt:63-65`）。**需真机确认**，本批未改。
- ⚠️ `PrefsJsonQueue` 的主线程检测在**非 Robolectric 的纯 JVM 单测**里会退化为「不在主线程」
  （`Looper.myLooper()` 返回默认 `null`）。这是**刻意**的：宁可漏报，也不能让存储层反过来炸掉测试；
  检测只是**可观测性**，真正的保证在调用点（以及门禁 ③ 的调用点清单）。
- ⚠️ 门禁 `check-edge-queue-threading` 的正文扫描**只剥行注释与 `*` 开头的 KDoc 行**，
  「块注释里不以便宜号开头」的行仍会被当成真调用点；本批未扩大范围（保持最小改动）。
- ⚠️ `OfflineUploadQueue.enqueueSingle` 目前**零外部调用者**（门禁清单里已注明「若将来接线必须重新复核线程」）。
- 📌 **仍未提交**：工作区含并发会话（admin-vue）的成果，提交归属待拍板。本批涉及的是上述 4 个文件。
