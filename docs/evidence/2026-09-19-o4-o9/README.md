# O4 协议治理 + O9 Flyway 治理（2026-09-19 第十七轮）

> 路线图：`docs/COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md` 的 **O4** / **O9** 两条
> （此前状态 ❌ 未动，判定来源见 `.workbuddy/memory/2026-09-19.md` 第五轮）。
> 本轮目标不是「写点代码」，而是**把三份互相矛盾的协议描述拉平，并留下会红的判据**。

---

## 0. 一句话结论

- **O4**：抓到一个**幽灵门禁** —— `check:edge-cloud-mqtt-contract` 被测试 Javadoc 与证据文档
  反复引用，**但脚本根本不存在**。已实现（含它承诺过的两半：MQTT 线协议 + 内部 HTTP 端点），
  并修掉协议本身 **6 处**不一致。
- **O9**：把「种子 / 结构分离」落成**只向前生效**的策略 + 门禁 + 自测；**不动任何已应用迁移**
  （改写 = checksum 漂移）。

---

## 1. O4：先取证，再动手

### 1.1 三份描述，互不一致（实测）

| 侧面 | 权威来源 | 实测集合 |
|---|---|---|
| 下行命令 | `CabinetConstants.MQTT_CMD_*` | `OPEN_DOOR` `SET_TARGET_TEMP` `LOCK` `UNLOCK` `REBOOT`（5） |
| 上行事件 | `CabinetConstants.MQTT_EVENT_TYPE_*` | `DOOR` `HEARTBEAT` `ACK`（+ 仅局部定义的 `ALERT`） |
| topic | `MqttTopics` | `cabinet/{id}/cmd`、`cabinet/{id}/evt`、`cabinet/+/evt`、`$share/aicabinet/cabinet/+/evt` |
| 端侧硬编码 | `MqttDeviceClient.kt` | 发 `cabinet/$deviceId/evt`（`type` ∈ DOOR/ACK/ALERT/HEARTBEAT）；订阅 `cabinet/$deviceId/cmd` |
| **proto** | `proto/cabinet.proto` | 下行 oneof `open_door` `force_close` `ota_upgrade`；上行 oneof `door` `ack` `heartbeat` `video_chunk` |

### 1.2 六处漂移（全部给到 `文件:行`）

1. 🔴 **幽灵门禁**：`EdgeCloudMqttContractTest.java:46,208` 与
   `docs/evidence/2026-09-19-p07-edge-contract/README.md:83` 都称 Kotlin 侧由
   `check:edge-cloud-mqtt-contract` 守护 —— `ls scripts/` **没有这个脚本**，
   `package.json` 也没有 `check:edge-cloud-mqtt-contract`。属门禁失效**形态①「没人调」**
   的极端版：**判据不存在**。（我自己的记忆 §11.18／§9.1 也把它当既有事实转述过，已更正。）
2. proto 下行缺 4 条真实命令（`SET_TARGET_TEMP`/`LOCK`/`UNLOCK`/`REBOOT`），多 2 条**不存在**的
   （`force_close`/`ota_upgrade`）。
3. proto 上行缺 `ALERT`（端侧真发、服务端真处理），多 `video_chunk`（无人实现）。
4. `MqttTopics.ALL_HEARTBEATS = "cabinet/+/evt/heartbeat"` —— **值本身是错的**：
   `MqttDeviceClient.publishHeartbeat()` 发往 `cabinet/{id}/evt`，心跳靠
   `payload.type=HEARTBEAT` 区分，该 filter 永远匹配不到任何报文。且全仓零调用。
5. `MqttTopics.videoChunk()` —— 全仓零调用，视频分片走 MQTT 的通道从未实现（`VideoChunkMeta` 亦同）。
6. `"ACK"` **两处定义**（`CabinetConstants.MQTT_EVENT_TYPE_ACK` 与 `MqttEventListener`
   的私有 `EVENT_TYPE_ACK`），`"ALERT"` 只在局部定义 ⇒ 改一处忘另一处即静默不符。

### 1.3 改了哪些

| 文件 | 改动 |
|---|---|
| `proto/cabinet.proto` | **按真实协议重写**：下行 oneof 对齐 5 条命令、上行对齐 4 类事件；文件头写明**权威性口径**（线上协议权威在 Java 常量，proto 不参与代码生成，只被门禁拿来做对账） |
| `MqttTopics.java` | 删 `ALL_HEARTBEATS`（错值 + 零调用）与 `videoChunk()`（零调用），原位留注释说明「为什么删、要加回该同时补什么」 |
| `CabinetConstants.java` | 补 `MQTT_EVENT_TYPE_ALERT = "ALERT"`（端侧与服务端都在用的取值，此前无常量） |
| `MqttEventListener.java` | 删局部 `EVENT_TYPE_ACK/ALERT` 副本，统一用 `CabinetConstants.MQTT_EVENT_TYPE_*` |
| `scripts/check-edge-cloud-mqtt-contract.mjs` | **新增**（实现那个幽灵门禁） |
| `scripts/check-flyway-seed-separation.mjs` | **新增**（O9） |

### 1.4 门禁判据设计（刻意「机械可推导」）

不维护任何手工映射表（手工表本身会成为**第四处**漂移源），只做机械变换：

- **C1/C2**：proto `oneof` 字段名 **upper_snake 后逐字等于** `MQTT_CMD_*` / `MQTT_EVENT_TYPE_*` 取值，
  集合**相等**（双向：缺一条红、多一条也红）。
- **C3/C4**：edge 上行/下行的 `payload.type` 取值 ⊆ 对应常量集合。
- **C5**：edge 硬编码 topic 形状（`cabinet/{deviceId}/…`）必须能在 `MqttTopics` 里找到声明。
- **C6**：`MqttTopics` 每个成员必须在**别处**被引用，否则「死声明」红。
- **C7**：`TradeServiceClient`／`DeviceSimulator` 的 `.uri("/internal/…")` 必须能由对端
  `@RequestMapping` + `@XxxMapping` 拼出来（拼不上 = **运行期静默 404**）。
- 🔴 每个解析结果都断言**非空**：解析器一旦打滑，空集合互比会恒绿（形态③）⇒ 直接报错。

---

## 2. O9：种子 / 结构分离（只向前）

### 2.1 现状取证

- 277 个迁移**全在一个模块**：`services/trade-service/src/main/resources/db/migration`；
- 种子以 `V` 编号混在结构里：`V11/V25/V28/V56/V134/V135/V222/V223/V252/V275__*seed*`；
- 无任何 `R__` 可重复迁移；`check:migration-safety` 已存在（**diff-based**，只拦 PR 增量）。

### 2.2 策略与门禁

| | 位置 | 前缀 | 语义 |
|---|---|---|---|
| 结构 | `db/migration/` | `V*` | 一次性、checksum 不可变 |
| 种子 | `db/seed/` | `R__seed_*` | **可重复** ⇒ **必须幂等** |

- `application.yml`：`locations: classpath:db/migration,classpath:db/seed`
  （目录初始为空 ⇒ **零行为变化**；prod 仅覆盖 `placeholders.seed_env`，不动 locations）。
- 门禁 `check:flyway-seed-separation`：
  - 新增 `V*.sql` 含数据写语句 ⇒ **必须显式声明** `MIGRATION_KIND: schema|backfill`；
    声明 `seed` ⇒ 直接红；声明 `schema` 却写数据 ⇒ 红。
  - 🔴 **不猜语义**：判据不去判断「这条 INSERT 算种子还是 backfill」—— 那是模糊判断，
    猜错就是**假红**，假红会让人绕过门禁再把它删掉。改为**强制显式声明**。
  - `R__seed_*.sql` 必须幂等（`ON CONFLICT` 或 `WHERE NOT EXISTS`），禁 `TRUNCATE` / 无条件 `DELETE`。
- 历史 277 个迁移 **grandfather**：门禁按「相对基线新增」判定；**不改写已应用迁移**
  （改动 = checksum 漂移，需每台环境 `flyway repair`，收益为零）。
- 进 CI 的两个步骤**用 PR 基线 ref**，故**不进聚合链**（与 `check:migration-safety` 同性质；
  进链会在 PR 里拿 `origin/dev` 基线 → 假红）。

---

## 3. 验证（全部为实跑输出）

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 + 单测 | `mvn -B clean test -pl services/common/common-core,services/device-service -am` | **BUILD SUCCESS / EXITCODE=0**；device-service **48 个 `<testcase>`、0 failure/error**；`edge/device-simulator`（消费 `MqttTopics` 的模块）一并 SUCCESS |
| O4 门禁 | `node scripts/check-edge-cloud-mqtt-contract.mjs` | OK（proto 5 命令 / 4 事件；topic 4；内部端点 19 个逐个对账） |
| O4 A/B | `node docs/evidence/2026-09-19-o4-o9/scripts/ab-drift-mqtt-contract.mjs` | **9/9**，每例：注入 → 红 → 还原 → sha256 逐字节一致且回绿 |
| O9 门禁 | `node scripts/check-flyway-seed-separation.mjs` | OK（无新增迁移；锚点与配置已校验） |
| O9 自测 | `node scripts/check-flyway-seed-separation.test.mjs` | **10/10** 方向正确，且不残留临时迁移 |
| 看门人 | `node scripts/check-audit-gates-wiring.mjs` | OK；聚合链 **29→30**、`check-*.mjs` **36→38**、`check:*` **37→39**、全部可达 |
| 聚合链 | `node scripts/run-audit-gates.mjs` | **30 个门禁 / 0 失败** |
| 行尾 | `node scripts/check-line-endings.mjs` | OK |
| 格式 | `prettier --check` + `eslint`（4 个新脚本） | 全绿 |

### A/B 用例（9 条，全部「注入后红 + 还原后字节一致」）

D1 edge `payload.type` 改名｜D2 edge topic 改段｜D3 proto oneof 字段改名｜D4 Java 常量值改动｜
D5 proto **注释掉**声明（形态③）｜D6 新增零调用死 topic｜D7 topic 解析器失效（解析到 0）｜
D8 type 解析器失效（解析到 0）｜D9 内部端点漂移。

---

## 4. 本轮自己踩的坑（值得留档）

1. 🔴 **A/B 用例自己写错两次**：D7 只替换了 `/evt` 没替换 `/cmd` ⇒ 解析到 1 个 topic 而非 0，
   测不到「解析到 0」那条防空转断言；D8 用「多加几个空格」做变异，而门禁正则用 `\s+`，
   多空格照样匹配 ⇒ **恒绿**。两条都是「变异没真正生效」，A/B 会变成假的。
   **教训：A/B 必须断言「变异确实产生了不同输入」，否则等于没注入。**
2. 🔴 **门禁假红**：C7 第一版要求方法级注解必须带 `("...")`，而
   `OpsAlertInternalController.java:31` 是**无参** `@PostMapping` ⇒ 误报 `/internal/v1/ops-alerts`
   为漂移。**假红与假绿同害** —— 若不复核就当成「真漂移」上报，等于自己造了一个缺陷。
3. 🔴 **proto 注释未剥离**：门禁最初直接在原文上跑正则，`// AlertEvent alert = 13;`
   会被当成真声明 ⇒ 删掉声明也能恒绿（形态③）。已加 `stripProtoComments`，并用 **D5** 钉死。
4. **测试自测文件也要接线**：新增 `check-flyway-seed-separation.test.mjs` 后，
   `check-audit-gates-wiring` 立刻红（规则 2：每个 `check-*.mjs` 必须被引用）——
   看门人工作正常。按 `.github/workflows/ci.yml` 里既有先例接进 CI。

---

## 5. 未覆盖 / 遗留

- **C7 只比路径**，不比 HTTP 方法、不比请求体形状；方法不匹配仍需端到端测试兜。
- **报文体字段**（如 `alertType`、`doorState`）不在门禁覆盖面内 —— Javadoc 已如实标注。
- `device-simulator` 仍**硬编码** `"X-Internal-Api-Key"` 字面量（4 处），未改用
  `InternalApiConstants.API_KEY_HEADER`；不改行为，属遗留整洁性问题，未纳入本批。
- `db/seed/` 目前**只有约定与 README，没有真实种子文件** —— 首次使用时会走一遍完整路径，
  届时才能验证 `R__` 在真实 Flyway 上的行为（本机无 dev 栈，未实跑 Flyway）。
- 历史 277 个种子迁移**仍在 `db/migration`**，这是**有意为之**（只向前治理）。
