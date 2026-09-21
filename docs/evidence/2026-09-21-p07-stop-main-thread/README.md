# P0-7（同族）· `CabinetService.onDestroy()` 的主线程阻塞

> 批次日期：2026-09-21 ｜ 上游：`2026-09-19-p07-edge-contract`（P0-7 ②③）、
> `2026-09-21-p07-prefs-main-thread`（P0-7 ① 的 `start()` 侧）

## 结论

`CabinetService.onDestroy()` 曾在**主线程**同步调用 `CabinetController.stop()`。
`stop()` 是一条阻塞链，最长可卡 **30s（Paho quiesce）+ 10s（DISCONNECT_TIMEOUT）+ 串口 close()**。
已修：停机移到**独立作用域**的异步块里执行，并由静态门禁规则④ + 3 条单测守住。

⚠️ **不能**简单写成 `serviceScope.launch { stop() }` —— `onDestroy()` 的下一句就是
`serviceScope.cancel()`，它会取消「刚提交、尚未被调度到」的子协程 ⇒ `stop()` **静默不执行**
（不报错、不打日志；服务停了但串口没释放、MQTT 没断开）。这是本次最容易踩错的一步，
由 `ServiceShutdownTest` 的负向用例钉住。

## 一、缺陷：证据链（全部 `文件:行`）

```
CabinetService.onDestroy()                    ← Android 保证主线程
 └─ CabinetForegroundService.getController(…).stop()
     ├─ offlineQueue.stop()            → executor.shutdownNow()                  [非阻塞]
     ├─ mqtt.disconnect()              → Paho 1.2.5
     │     MqttClient.disconnect()
     │       → MqttAsyncClient.disconnect()
     │         → disconnect(30000L /*QUIESCE_TIMEOUT*/, …)     ← javap ldc2_w 30000
     │         → IMqttToken.waitForCompletion()                ← 无参 = **无超时等待**
     └─ lockDriver.shutdown()          → 串口 serial?.close()                    [阻塞 I/O]
```

- 停机链路：`edge/android-app/app/src/main/java/com/aicabinet/edge/service/CabinetController.kt:73-81`
- Paho 常量取自 **jar 字节码**，非文档引用：
  ```
  javap -p -constants …/org.eclipse.paho.client.mqttv3-1.2.5.jar \
        org.eclipse.paho.client.mqttv3.MqttAsyncClient
    private static final long QUIESCE_TIMEOUT = 30000l;
    private static final long DISCONNECT_TIMEOUT = 10000l;
  ```
  `MqttClient.disconnect()` 的字节码：`MqttAsyncClient.disconnect()` → `IMqttToken.waitForCompletion()`；
  `disconnect(Object, IMqttActionListener)` 里是 `ldc2_w 30000l`。

## 二、修法

| 文件 | 改动 |
|---|---|
| `service/ServiceShutdown.kt`（新增） | `schedule(shutdownScope, onError, stop)`：在独立作用域上异步执行停机，跑完**自毁**该作用域 |
| `service/CabinetService.kt:31` | 新增 `private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` |
| `service/CabinetService.kt:52-71` | `onDestroy()` 改为：取 controller（可空）→ `ServiceShutdown.schedule(shutdownScope, …) { controller.stop() }` → 再 `serviceScope.cancel()` |

顺序**刻意不变**（先停机、再取消业务作用域），只把阻塞本身异步化 —— 最小改动。

## 三、判据：分三层（各守一段，互补）

| 层 | 判据 | 守什么 | 可证明性 |
|---|---|---|---|
| 语义 | `app/src/test/.../service/ServiceShutdownTest.kt`（3 例） | 非主线程执行 / 失败也自毁 / **传已取消作用域 ⇒ 确定性不执行** | ✅ 本次实跑 |
| 结构 | `scripts/check-edge-queue-threading.mjs` 规则④ | `onDestroy`/`onCreate` 里的 `.start()/.stop()` 必须在异步块内；**在 onDestroy 内提交的**不得挂在被 `cancel()` 的作用域上 | ✅ A/B 注入漂移 |
| 接线 | 同上，守卫 | 扫不到调用点（锚点失效）时**不许静默变绿** | ✅ A/B 注入漂移 |

为什么必须分两层：单测覆盖不到 `onDestroy()` 本身的接线（它要真 Service 生命周期 + 真 broker + 真串口），
所以把「能测的语义」抽成 `ServiceShutdown`，把「接线对不对」交给静态门禁。

## 四、A/B 注入漂移（规则④）

自变量**只有「有没有规则④」**，其余条件全同（同一份 `stripComments` 修复、同一批源码、同一行尾）：

- 旧臂 = 工作区门禁脚本**删掉规则④ 整段**
- 新臂 = 工作区门禁脚本（含规则④）

| 注入 | 源行尾 | 旧臂 | 新臂 | 期望 | 新臂的诊断 |
|---|---|---|---|---|---|
| `none`（工作区真实源） | CRLF | 绿 | 绿 | 0/0 ✅ | —（正确实现，不许误报） |
| `stop-sync`（换回裸同步调用） | CRLF | 绿 | **红** | 0/1 ✅ | 第 65 行 `.stop()` 是**同步调用** |
| `stop-service-scope`（`serviceScope.launch { stop() }`） | CRLF | 绿 | **红** | 0/1 ✅ | 第 65 行挂在 `serviceScope` 上，**会被紧随的 cancel 静默吃掉** |
| `rename-stop`（锚点失效） | CRLF | 绿 | **红** | 0/1 ✅ | 只解析出 1 处调用 ⇒ **守卫拦住「静默变绿」** |

三格「旧绿新红」且**诊断各不相同、各自指向真实原因** ⇒ 规则④ 是**真增量**，
不是把已有判别力换个写法。（只看退出码不算证据，故逐格核对了 stderr 首行。）

证据日志：`logs/gate-matrix/`（4 注入 × 2 臂 = 8 份）。

## 五、计数（本次实跑）

- edge 全量：**94 例 / 0 失败 / BUILD SUCCESSFUL**（12 份报告；上次 91 → 本批 +3 = `ServiceShutdownTest`）
  · 判据只看 `TEST-*.xml` 的 `<testcase>` 元素个数，不看退出码
  · 日志：`logs/edge-stop-full.log`
- 门禁链 `scripts/run-audit-gates.mjs`：34/34，失败 0
  · ⚠️ 本机必须预载 `nopipe` 垫片，否则 34/34 全红 + `exit=null` = 环境假红
  · 日志：`logs/gates-with-nopipe.log`
- ⚠️ 首次跑聚焦测试时编译失败（`CabinetService.kt:21` 我漏了 `videoRecorder` 的类型注解）——
  日志留在 `logs/edge-stop-focused-first-run-compile-error.log`，因为「一次通过」不是本项要证明的事。

## 六、复现

```bash
# 1) 单测（聚焦 → 全量；脚本要求 BUILD_DIR 不存在）
bash docs/evidence/2026-09-21-p07-stop-main-thread/scripts/run-stop-unit-tests.sh \
     '--tests=com.aicabinet.edge.service.ServiceShutdownTest'
BUILD_DIR="<fresh-dir>" bash docs/evidence/2026-09-21-p07-stop-main-thread/scripts/run-stop-unit-tests.sh

# 2) 规则④ 的 A/B 矩阵
python docs/evidence/2026-09-21-p07-stop-main-thread/scripts/ab-stop-gate.py

# 3) 门禁链（须预载 nopipe）
NODE_OPTIONS="--require=<repo>/.tmp/tools/nopipe.cjs" node scripts/run-audit-gates.mjs
```

## 七、已知边界（未验 / 未做）

- 🔴 **运行期未验**：本机无 emulator / 真机，「停机不再卡主线程」的实际观感未在设备上验证。
  本批能证明的是**静态结构**（门禁）+ **语义**（单测），不是运行期行为。
- ⚠️ `onDestroy` 后进程若被系统立即杀掉，异步停机可能来不及跑完 —— 但那种情况下 OS 会回收
  串口/MQTT 连接本身，无额外危害；「用户主动停服务」时进程仍存活，异步停机能跑完。
- ⚠️ **OTA 检查可能从未生效**：`OtaChecker.checkOnStartup()` 是主线程同步 OkHttp 请求，
  若抛 `NetworkOnMainThreadException` 会被 `catch (e: Exception)` 吞成一行 `OTA check skipped`
  （`ota/OtaChecker.kt`）。`start()` 已移出主线程后应恢复正常，但**需真机确认**。
- `CabinetService` 里的 `TAG` 常量本轮新增（此前无日志点）。
