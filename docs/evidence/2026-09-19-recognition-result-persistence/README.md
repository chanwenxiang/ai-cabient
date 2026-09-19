# 识别结果落库（`recognition_result`）· 证据包

日期：2026-09-19（第三十三轮）
范围：把平台采纳的识别结果写入 `recognition_result`，补齐该表自 `V1__init_schema.sql` 起的「有表无写者」断链。

---

## 1. 断链证据（先取证，再动手）

| 判据 | 实测 | 取证方式 |
| --- | --- | --- |
| 表存在 | `V1__init_schema.sql:37-48` `CREATE TABLE IF NOT EXISTS recognition_result` | 读迁移 |
| 全仓无写者 | java/xml 中 `recognition_result` 命中 **0** | `grep -rn … services/` |
| 线上真实行数 | `SELECT count(*) FROM recognition_result` = **0** | psql |
| 有 demo 种子却仍 0 行 | `V222:112` INSERT … `WHERE NOT EXISTS`；本库未命中任何 COMPLETED/SETTLING 会话（或被 `wipe-business.sql` 清过） | 读迁移 + psql |
| 表约束 | `task_id VARCHAR(64) PK`；`session_id` FK → `shopping_session`；`items JSONB NOT NULL`；`model_version VARCHAR(32)`；`fusion_mode NOT NULL DEFAULT 'VISION'` | `V1` + `V70` + psql 查 `pg_constraint` |

> ⚠️ 上一轮（P0-1）记录里只写了「表 0 行、grep 0 命中」；复核时**补记**：V222 里有对 `recognition_result` 的 demo seed，本库未命中才仍是 0 行。
> ⚠️ 另发现 **seed 与运行期的 items JSON 键不一致**：V222 写 `qty`+`skuName`，而 Kafka 报文解析（`VisionRecognitionListener:61`）与端侧直报 DTO（`VisionRecognitionResultDto.Item`）都用 `quantity`。照着 seed 写会让消费者取不到数量 ⇒ 运行期一律写 `quantity`，并加断言守住（见 §4.1）。

---

## 2. 关键发现（决定设计）

### 2.1 🔴 默认配置下只有同步路径是活的 ⇒ 只挂异步通道等于没落库

`aicabinet.vision-async.enabled` 在 `application.yml:155-156` **硬编码 `false`**，`application-{dev,staging,prod}.yml` 三个 profile **均无覆盖**；全仓仅临时覆盖（`.tmp/compose.vision-async.yml`）与本批 e2e 脚本提到它。

受影响的是 4 个 `@ConditionalOnProperty(prefix="aicabinet.vision-async", name="enabled", havingValue="true")` bean：

- `config/KafkaEnableConfig`
- `config/KafkaTopicConfig`
- `messaging/VisionRecognitionListener`
- `messaging/VisionRecognitionProducer`

⇒ 发布口径走的是**同步关门**路径（`SessionDoorService:175` → `settleAfterClose` → `settle`）。若把写入点挂在 `doCompleteAsyncRecognition`（P0-1 那条路），表在**任何已发布配置**下都仍会是 0 行——即「修了但没跑」，属假绿。

### 2.2 唯一收敛点：`SettlementRecognitionService.processRecognitionResultUnlocked`

| 路径 | 链路 |
| --- | --- |
| 同步关门 | `SessionSettleService.settleSession` → `SettlementService.settle` → `SettlementSettleOrchestrator.settle` → `processRecognitionAfterVision` → `processRecognitionResultUnlocked` |
| 异步回调 | `SessionService.completeAsyncRecognition` → `SessionSettleService.doCompleteAsyncRecognition` → `SettlementService.processRecognitionResult` → … → `processRecognitionResultUnlocked` |
| 开发上传 | `SessionSettleService.completeDevUploadRecognition` → … → `processRecognitionResultUnlocked` |

一处覆盖三条路径；挂在各入口会各自漂移。写入点在**归一之后**（已含 `withGravityFallback` / `forceReviewIfMockOrMismatch`），即平台据此决策的那一份。

### 2.3 🔴 外键锁：独立事务（`REQUIRES_NEW`）会与外层 `FOR UPDATE` 互锁

`recognition_result.session_id` 外键 → `shopping_session(session_id)`（`V1` 建表 + `V70` 重加 `ON DELETE CASCADE`，psql 已核）。
插入子行时 PostgreSQL 会对父行执行 `SELECT 1 FROM ONLY shopping_session … FOR KEY SHARE`，而结算事务持有该行 `FOR UPDATE`。

对照实验（同一张表、同一个会话行，同一天）：

| 条件 | 实测 |
| --- | --- |
| 无锁插入 | `INSERT 0 1`，**6.0 ms** |
| 另一连接持 `FOR UPDATE` 时插入 | `ERROR: canceling statement due to statement timeout`（801 ms 超时）<br>`CONTEXT: while locking tuple (0,14) in relation "shopping_session"`<br>`SQL: SELECT 1 FROM ONLY "public"."shopping_session" x WHERE "session_id" = $1 FOR KEY SHARE OF x` |

⇒ 若落库走独立事务，每次写入都会阻塞直到超时/死锁；连接池 20（`DB_POOL_SIZE` 默认，实测无覆盖）不会立刻耗尽，但**每个会话都会卡住**。
⇒ **必须与结算共用同一个事务**（事务不会阻塞自己已持有的锁）。

> 这条是真实栈实验抓到的：单元测试用 no-op 事务管理器，**抓不到**。第一版实现正是 `REQUIRES_NEW`，被本实验否掉。

---

## 3. 设计

1. **写入点**：`SettlementRecognitionService.recordRecognitionResult`（唯一），覆盖 §2.2 三条路径。
2. **事务**：与结算同事务（§2.3）。**不做** `REQUIRES_NEW`，不用 savepoint。
3. **失败语义：刻意 fail-hard，不静默**。不吞异常——库错误让结算一起回滚。宁可本次结算失败并可重试，也不接受「已扣款却没有识别记录」的对账黑洞。为把 fail-hard 触发面压到近零：
   - 所有列约束在触碰数据库**之前**校验，坏输入直接返回 `INVALID`（不产生 SQL 错误）；
   - `task_id` 冲突用 `INSERT … ON CONFLICT (task_id) DO NOTHING` 化解（端侧任务号跨会话复用不该拖垮结算）。
4. **幂等两把闸**：`task_id`（主键）+ 会话维度唯一（与 V222 seed 同语义）。
5. **列宽**：`model_version` 由 `VARCHAR(32)` 放宽到 64（`V279`），入口（HTTP 400）与落库共用同一权威常量 `VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH`。不放宽的话，端侧真实固件版本号（如 `quectel-openvending-yolo-v8n-2026.09`，37 字符）会写库报错 → 被 fail-hard 放大成结算失败。

改动清单：

| 文件 | 性质 |
| --- | --- |
| `trade/domain/RecognitionResult.java` | 新增（实体，JSONB 走 `JsonStringTypeHandler`） |
| `trade/mapper/RecognitionResultMapper.java` | 新增（含 `findBySessionId` 与 `insertIgnoreConflict`） |
| `trade/service/RecognitionResultWriter.java` | 新增（校验 / 幂等 / 落库） |
| `trade/service/SettlementRecognitionService.java` | 加 1 个依赖 + 1 次调用 |
| `common/dto/VisionRecognitionResultDto.java` | 加列宽常量 |
| `trade/service/VisionResultIngestService.java` | 入口加超长 400 |
| `V279__recognition_result_model_version_widen.sql` | 新增迁移 |
| `scripts/devops/verify-recognition-result-persistence.py` | 新增（默认同步栈 e2e） |
| `scripts/devops/verify-vision-result-ingest.py` | 追加落库断言（G/H/E4） |
| `SettlementDisputeTest` / `RecognitionResultWriterTest` | 判据 |

---

## 4. 验证

### 4.1 单元测试（只认 surefire XML 里 `<testcase>` 元素个数，不认 "Tests run:" 文本）

| 测试类 | 用例数 | 失败 | 产物 |
| --- | --- | --- | --- |
| `RecognitionResultWriterTest` | 12 | 0 | `trade-service/target/surefire-reports/TEST-…RecognitionResultWriterTest.xml` |
| `SettlementDisputeTest` | 17 | 0 | `trade-service/target/surefire-reports/TEST-…SettlementDisputeTest.xml` |

判据覆盖：列映射 / `items` 键为 `quantity`（**非** `qty`）/ `task_id` 重复 / 会话级重复 /
`ON CONFLICT` 返回 0 视为重复 / `modelVersion` 为空 / `taskId` 空或超长 / `modelVersion` 超列宽 /
`items` 为空 → `[]` / **库失败必须抛出而非吞掉**（`insertFails_propagatesInsteadOfSwallowing`）/ 入参 `null`。

### 4.2 A/B 漂移（证明判据真的会红）

脚本 `.tmp/ab-recognition-persistence.py`。每轮：先删 surefire XML（「无产物 ≠ 绿」）→ 注入漂移 → 跑该类 →
`finally` 无条件还原 → **字节级回验**。基线全绿（`12/0` + `17/0`）才继续。

| 漂移 | 注入内容 | 期望变红用例 | 实测 |
| --- | --- | --- | --- |
| A | 摘掉「会话级去重」闸 | `persist_sessionAlreadyHasResult_skips` | ✅ 12 中恰 1 红，名单唯一 |
| B | 摘掉「`task_id` 去重」闸 | `persist_sameTaskIdTwice_skipsSecondWrite` | ✅ 12 中恰 1 红，名单唯一 |
| C | 摘掉「`model_version` 列宽」闸 | `persist_modelVersionOverColumnWidth_invalidAndNeverTouchesDb` | ✅ 12 中恰 1 红，名单唯一 |
| D | 摘掉结算侧写入钩子 | `recognitionApplied_persistsResultOnce` | ✅ `SettlementDisputeTest` 17 中恰 1 红 |
| E | `items` 恒序列化为 `[]` | `persist_itemsJsonUsesQuantityKeyAndNotQty` | ✅ 2 红：该条 **+** `persist_writesRowWithAllColumnsMapped` |

收尾：`字节级还原： 全部一致`，脚本退出码 **0**（产物 `.tmp/ab-out3.txt`）。

> 两点说明，避免误读：
> 1. 漂移 E 多红一条：`persist_writesRowWithAllColumnsMapped` 也断言 items 非空。**预期用例在名单内**即判据有效；
>    多红一条是判据间的合理重叠，不是「多红一条就说明有耦合缺陷」的那种情况（其余 4 轮均为「恰 1 红」）。
> 2. 首次运行（`.tmp/ab-out2.txt`）五轮判定**全部通过**，但脚本**汇总段有一处取值下标 bug**
>    （`r[5]` 应为 `r[4]`，而元组只有 5 个元素）⇒ 在全部漂移跑完之后才崩溃、退出码 1。
>    已修正并重跑，`ab-out3.txt` 为干净产物。**记在这里是因为「产物退出码 1 / 带 traceback」本身就是会被下游误读的信号**——
>    只看文件名的后来者会以为实验失败。

### 4.3 端到端（真实栈）

两条通道各跑一次，都是**新镜像**（`ai-cabinet/trade-service:local` = `a8e23b623e72`）：

**① 默认同步口径（= 发布口径）** `scripts/devops/verify-recognition-result-persistence.py` → **10/10 通过**，退出码 0。

- A1：会话仍在 `SHOPPING` 时 `recognition_result` 行数 = **0**（不该写的不会被写）
- B1：关门后**无需外部输入**即离开识别态，终态 `DISPUTED`（证明确实跑在同步模式）
- C：该会话恰 **1** 行，`taskId=T-<sid>`、`fusion_mode=VISION`、`model_version=mock-v1`、`need_review=true`、`confidence=0.75`、
  `items=[{"skuId":"SKU-DEMO-001","quantity":1,"confidence":0.75}]`
- D1：全表无「同会话多行」

**② 异步回调（P0-1 端侧直报，需 `-D` 打开）** `scripts/devops/verify-vision-result-ingest.py` → **25/25 通过**，退出码 0。

- 端侧直报 → 回执 `accepted=true / outcome=PROCESSED`，会话终态 `COMPLETED`，订单 `PAID`、余额精确扣减
- G：该会话恰 **1** 行，`taskId=EDGE-<sid>`、`model_version=QUECTEL-EDGE-1.5.2`、`need_review=false`、`confidence=0.96`、items 键为 `quantity`
- E4：二次上报后仍 **1** 行（未重复落库）；H1：全表无「同会话多行」
- 本批新补 **F5**：`modelVersion` 65 字符（列宽 64）→ HTTP **400**（把入口闸与 V279 列宽在真实栈上闭环）

**前置取证（不采信「构建成功」）**

- `flyway_schema_history` 实测：`279 | recognition result model version widen | success=t | installed_on=2026-09-19 15:09:17`
- `information_schema.columns` 实测：`model_version = character varying(64)`（原 32）
- 容器内 `JAVA_TOOL_OPTIONS` 实测为空 ⇒ 上面的 10/10 确实跑在**发布口径**上（异步覆盖已移除）
- 容器内实测转为异步时 `JAVA_TOOL_OPTIONS = -Daicabinet.vision-async.enabled=true` ⇒ 25/25 确实跑在异步通道上

**表本身的状态变化（最直接的一条）**

`SELECT count(*) FROM recognition_result`：改前 = **0**（本会话内两次实测，含 22:42 一次）；两轮 e2e（首轮 + 复跑留档轮）
之后 = **4**，每条都对应一次真实结算——**2 条同步通道（`T-` 前缀）+ 2 条异步通道（`EDGE-` 前缀）**，
且各通道两轮字段完全一致（可重复）：

| task_id | 通道 | fusion | model_version | need_review | conf | items |
| --- | --- | --- | --- | --- | --- | --- |
| `T-1789830583838929519222` | 同步 | VISION | mock-v1 | t | 0.75 | `[{"skuId":"SKU-DEMO-001","quantity":1,"confidence":0.75}]` |
| `T-1789830731176745342321` | 同步 | VISION | mock-v1 | t | 0.75 | 同上 |
| `EDGE-1789830646265281902918` | 异步 | VISION | QUECTEL-EDGE-1.5.2 | f | 0.96 | `[{"skuId":"SKU-DEMO-001","quantity":1,"confidence":0.96}]` |
| `EDGE-1789830778248602719681` | 异步 | VISION | QUECTEL-EDGE-1.5.2 | f | 0.96 | 同上 |

> **端到端判据的负向对照，如实标注**：本批**未**对 e2e 脚本本身做「改回旧代码必红」的 A/B——旧镜像在重建时已被覆盖，无法回滚采样。
> 替代证据两条：① 单元级 A/B 漂移 D（摘掉写入钩子 ⇒ `recognitionApplied_persistsResultOnce` 红）；
> ② 改前真实栈实测 0 行，而 C1/G1 断言 `count = 1` ⇒ 旧代码下必红。**后者是推断，不是本轮实测。**

### 4.4 产物清单（本目录）

| 文件 | 内容 |
| --- | --- |
| `e2e-sync-mode.txt` | 默认同步口径 e2e 原始输出（10/10） |
| `e2e-async-mode.txt` | 异步通 e2e 原始输出（25/25，含 F5） |
| `ab-drift-result.txt` | A/B 漂移原始输出（5 轮全红 + 字节级还原一致 + 退出码 0） |
| `scripts/ab-recognition-persistence.py` | A/B 漂移脚本（可复跑；字节级读写、每轮无条件还原） |
| `db-rows-after-e2e.txt` | 改后 `recognition_result` 全部 4 行 |
| `migration-and-columns.txt` | `V279` 迁移记录 + `model_version` 列宽 64 实测 |
| `audit-gates.txt` | 全量审计链输出（32 个门禁，失败 0） |

---

## 5. 未做 / 边界（明确记录，不含糊）

1. **端侧原始报文的逐字对账**不提供：本表记的是平台**归一后**用于决策的那一份。若要「端侧上报 vs 平台采纳」的比特级比对，需另建表。
2. **被丢弃的入站结果不落库**（非 RECOGNIZING 态）：其结论已由 P0-1 的显式回执（`ALREADY_HANDLED`/`TOO_EARLY`/`CANCELLED`）告知端侧。表因此无法区分「端侧没报」与「平台丢弃」。
3. **`escalateVisionUnavailable` 路径**（vision-service 不可用 → 合成 `UNAVAILABLE-<sid>`）不经 `processRecognitionResultUnlocked`，故不落库。
4. **孤儿表门禁：评估后否决**。Flyway 建表 185 张，其中 **43 张**在 `services/**/*.{java,xml,yml,mjs}` + `clients/` + `scripts/` 中零引用（23%）；判据自检可靠（真表 `shopping_session`/`cabinet_order`/`dispute_ticket`/`scheduled_task` 分别命中 6/9/4/8 个文件）。否决理由：需 43 条白名单，且判据只能区分「完全无引用」，**读-only mapper 也会被判过** ⇒ 覆盖不到「有读无写」这一真正的断链形态，属失效形态⑥「信号在骗读者」。建议独立立项，先对 43 张定策（seed-only/演示 vs 真缺写者）。
5. **`vision-async` 通道整体未启用**（§2.1）：这是发布配置问题，本批不改（改它会让关门不再同步结算，属系统行为变更）。但记入路线图，因为它同时说明 P0-1 的端侧直报端点目前**没有受理窗口**。
