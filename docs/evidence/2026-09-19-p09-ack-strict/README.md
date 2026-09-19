# P0-9：`ackPayload` 恢复「与抽取前逐字一致」+ 补齐 payload 族单测

> 日期：2026-09-19　　提交：见 `git log --grep=P0-9`
> 上承：P0-7（`3ca802af` 抽取 `SimulatorSupport` 纯函数 + 契约/集成测试）

## 一句话结论

P0-7 抽取 `SimulatorSupport` 时，`ackPayload` 把原来的 `Map.of(...)` 换成了 `LinkedHashMap` —— 标注为「唯一有意差异」。
本批**取消这个差异**（改回 `Map.of`），并给三个 payload 函数补上**原本为零**的单测（含把「严格语义」钉死的 null 断言）。

## 一、改动前的取证（不凭记忆，逐条对 `git show f8b2a108^:`）

| 函数 | 抽取前形态（`f8b2a108^` 的 `DeviceSimulator`） | P0-7 抽取后 | 判定 |
|---|---|---|---|
| `doorEventPayload` | `LinkedHashMap`（有序）`DeviceSimulator.java:746` | `LinkedHashMap` | ✅ 逐字一致 |
| `heartbeatPayload` | `LinkedHashMap`（有序）`DeviceSimulator.java:256` | `LinkedHashMap` | ✅ 逐字一致 |
| `ackPayload` | **`Map.of(...)`** `DeviceSimulator.java:730` | `LinkedHashMap` | 🔴 **有意差异（本批消除）** |

契约侧无顺序依赖：`EdgeCloudMqttContractTest#ack_fromSimulatorPayload_recordsAckWithTopicDeviceId`
只断言 `recordAck(cmd, true, DEVICE_ID)`，不比对字段顺序 ⇒ 改回 `Map.of` 不会红。

## 二、改了什么

1. **`SimulatorSupport.ackPayload`** → 改回 `Map.of(...)`，并把「为什么刻意不用 `LinkedHashMap`」写进 Javadoc：
   - `commandId == null` 抛 `NullPointerException`（fail-fast 严格语义）。**生产路径安全**：调用方传的是
     Jackson `node.path("commandId").asText()`，字段缺失返回 `""`、NullNode 返回字面量 `"null"`，不会是 Java null。
   - 键序未指定（`Map.of` 语义）。消费端一律按名取值，无语义影响。
2. **类头注释**：`唯一有意差异` → **无任何有意差异**（三个函数各自沿用抽取前的集合类型）。
3. **`SimulatorSupportTest`** 新增 `@Nested PayloadConstructionTest`（5 条）—— 此前三个 payload 函数
   **零覆盖**：
   - `ackFields`：4 个字段名/类型/值
   - 🔴 `ackKeepsStrictMapOfSemanticsOnNull`：`null` ⇒ NPE。**这条是「刻意保留严格语义」的活文档**：
     后人若把实现改回 `LinkedHashMap`，它转红，逼出一次有意识的决定（而不是顺手优化掉一个契约）。
   - `doorEventOmitsNullOptionalKeys`：可选字段为 null 时**整键不写**（最小报文恰好 4 键）
   - `doorEventNeverCarriesDeviceId`：`deviceId` 只走 topic，报文体里出现即违约
   - `heartbeatFields`：camelCase 优先分支；snake_case 是云端兼容层，模拟器**不发**

判据用 `type` 的**字面量**（`"ACK"`/`"DOOR"`/`"HEARTBEAT"`）而非共享常量 —— 这些字符串是
跨进程/跨语言契约（真机端是 Kotlin 独立实现、不共享 `CabinetConstants`），**用共享常量自证等于没证**。

## 三、A/B（3/3 符合期望，且源码 sha256 完全还原）

`ab-unit-contract.txt`（脚本 `scripts/ab-unit-contract.py`）：

| 用例 | 单测 | 契约测试 | 期望 | 结果 |
|---|---|---|---|---|
| 基线 | 28 绿 | 10 绿 | — | ✅ |
| ① `ackPayload` → `LinkedHashMap`（模拟"顺手优化"） | 🔴 `ackKeepsStrictMapOfSemanticsOnNull` | **绿** | 红/绿 | ✅ |
| ② `heartbeatPayload` `appVersion` → `appVer`（云端不认） | 🔴 `heartbeatFields` | 🔴 `heartbeat_fromSimulatorPayload_…` | 红/红 | ✅ |
| ③ 反假红：仅改注释（塞满全部关键词），逻辑不动 | 绿 | 绿 | 绿/绿 | ✅ |

**用例①是本批的关键证据**：它同时证明「新单测有效」和「新单测**非冗余**」——
契约测试对 `Map.of` 的严格语义**完全不敏感**，只有单测抓得住。

## 四、v1 A/B 为何作废（`ab-v1-invalidated.txt`，留在库里当反面教材）

v1 用一条 `mvn test -pl A,B` 同时跑两侧，结果**三个漂移用例的契约侧都是「无报告」**，而 v1 的判据把
「无报告」当成了「跑了且绿」⇒ 漂移③ 被误判「不符期望」。

真因：**单测先红 ⇒ Maven reactor 在 `device-simulator` 中止**（Reactor Summary 只到 `[3/4]`），
`device-service` **从未被执行** —— 属失效形态②「被前置失败跳过」。而「无产物」被读成「通过」
是**假绿**，与「假红」同害。

v2 的两处修正：
1. 判据把「未跑」单独判为**无效**（永远算不符期望），不再折叠进「绿」。
2. 跨模块漂移的可见性：`mvn test` 只到 `test` 阶段，故先 `install -DskipTests` 把**漂移版**装进本地仓库，
   再**单独**跑 `device-service`；每个用例结束**无条件重装正确版**，脚本开头也做一次归位
   （防上次中断把漂移产物长期留在 `~/.m2`）。

## 五、复验（均为实跑）

| 项 | 结果 | 证据 |
|---|---|---|
| `mvn clean test -pl edge/device-simulator -am` | `Tests run: 28, Failures: 0, Errors: 0` / BUILD SUCCESS | `mvn-unit-baseline.txt` |
| 契约测试（基线） | `testcase=10`，0 失败 | `ab-unit-contract.txt` |
| A/B | 3/3 符合期望，源码 sha256 `940d2aa1…` 还原一致 | `ab-unit-contract.txt` |
| `check-line-endings` | OK（扫描 3313 文件） | `aggregate-gates.txt` |
| `node scripts/run-audit-gates.mjs` | **27 个门禁，失败 0** | `aggregate-gates.txt` |

⚠️ 注意 `mvn` 日志里**根类**仍打印 `Tests run: 0`（`@Nested` 用例类的真值在子元素上）——
故 A/B 判据一律读 surefire XML 的 `<testcase>` 元素个数，不读 `*.txt` 的 `Tests run:`。

## 六、本批未做

- `edge/android-app`（23 个 `.kt`、2118 行，P0-7 点名的 `PrefsJsonQueue` 主线程 commit 项）仍零测试：
  本机**无 `gradle`、无 `ANDROID_HOME`、无 Android SDK** ⇒ 无法实跑取证，不写「跑了没验证」的测试。
- 契约测试对 `ackPayload` 的宽松性（不依赖 `Map.of` 语义）**是刻意记录**，不是缺口。
