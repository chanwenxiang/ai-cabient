# 四端审计 R18 报告（2026-11-12）

> **基线**：`9657f90c`（R17 收口）→ HEAD `a8a61845`，共 13 个提交（`9657f90c..a8a61845`）。
> **方法**：四路只读子代理按提交面分工（库存原子化 / 集群心跳与缓存广播 / 事故修复 / 客户端与门禁），
> 主审对**全部 P1/P2 与关键 P3 逐条重读源码复核**（表内标 ✅=主审亲验、◐=子代理提出、主审未逐条复验）。
> **总账**：新发现 **12 条 = P1×2 + P2×4 + P3×6**；另有 6 条待复核项单列 §三。
> **性质**：本轮与 R15 同型——「从未扫过的机制层」扩面轮（库存并发写 / 集群租约 / 事故修补丁），
> 面内问题集中，不代表既有面回归。

---

## 一、发现总览

| ID | 区域 | 级别 | 一句话 | 验证 |
|---|---|---|---|---|
| R18-1 | 开门状态机 | **P1** | 网关 15s watchdog 早于 trade 新 60s 开门窗口，慢回执会话被标 FAILED，而 FAILED 不在迟到兜账范围 | ✅ |
| R18-2 | 库存批次 | **P1** | 批次扣减/回补仍是「读→算→写」，`DeviceSkuLot` 无 version，与下架/报损/盘点 ADJ 并发会丢扣 | ✅ |
| R18-3 | 通知 | P2 | `dispute_created` 模板从未种库，兜账承诺的消费者触达静默失效（debug 级） | ✅ |
| R18-4 | 库存汇总 | P2 | 建档竞态 catch `DuplicateKeyException` 后继续发 SQL，PG 事务已 aborted → 回补整笔 500 | ✅ |
| R18-5 | 库存汇总 | P2 | 原子 UPDATE 不递增 version 且设计上刻意不增，`syncAggregateInventory` 的实体 CAS 对算术扣减失明 | ✅ |
| R18-6 | 集群租约 | P2 | `finish` 无 try/finally + 心跳只校验锁 owner、不校验任务线程存活 → 写库失败时锁被钉到上限 | ✅ |
| R18-7 | 集群租约 | P3 | `renew` 把「Redis 瞬断」与「锁已易主」并成 false，一次抖动即永久停租 | ✅ |
| R18-8 | 集群租约 | P3 | 心跳句柄按 taskKey 存取、不绑定身份，上一轮失败分支会摘掉新一代心跳 | ◐ |
| R18-9 | 商户待办 | P3 | `LATE_RECOGNITION` 不进争议卡归类，徽标 ≠ 四卡合计（MA-030 同形） | ✅ |
| R18-10 | 缓存 | P3 | 失效只广播 L1、不防对端 loader 回灌旧值，L2 仍以完整 TTL 提供旧配置 | ◐ |
| R18-11 | 文档/门禁 | P3 | 归档后 14 处文档链接指向已移动路径；`check-ledger-evidence` 只认反引号 token | ◐ |
| R18-12 | 基建 | P3 | `full.yml` 11 个常驻服务无日志上限；CI 无 `docker compose config` 校验 | ◐ |

---

## 二、逐条证据

### R18-1（P1）gateway watchdog 15s 与 trade 开门窗口 60s 错位 —— 事故损失面未闭合

- `services/jiangyi-gateway/.../tracker/SessionWatchdog.java:31`：`OPEN_TIMEOUT_MS = 15_000L`，10s 一轮扫描。
- `services/jiangyi-gateway/.../api/OpenDoorController.java:68`：**所有**开门（消费 + 补货）都 `commandTracker.register(Kind.OPEN, …)`。
- `services/trade-service/src/main/resources/application.yml:364`：`opening-seconds: ${SESSION_OPENING_EXPIRE_SECONDS:60}`（本次调大）。
- `SessionOpenService.java:118-124`：`postOpenFailed` 只对 `CREATED/OPENING` 生效 → 15~25s 时把会话置 **FAILED**。
- `VisionResultIngestService.java:97-111`：迟到兜账只救 `stateBefore == CANCELLED`；`FAILED` 走 `reportLateRecognition`（HIGH 异常留痕），**不建单不结算**。

**为什么是缺陷**：本次修复的目标场景是「弱网 14~40s 才回执」，但网关在 15s 就判死；随后回执即使到达，
`applyRestockDoorOpened` 也只放行 `OPENING/SHOPPING`（`SessionDoorService.java:141-146`），FAILED 一律拒绝。
而 FAILED 会话：`LATE_RECOGNITION` 不在资金处置白名单（`OpsExceptionService.java:460-462`）、消费者无法自行建争议
（`DisputeService.java:259-261` 需 COMPLETED/DISPUTED）⇒ 货已拿走但无单可结，与 1794452035347297629828 同型。

**修法**：网关 OPEN watchdog 阈值改由配置下发且 ≥ `trade.opening-seconds + 10s`；或把
`FAILED && failReason~OPEN_TIMEOUT` 纳入与 CANCELLED 同级的自动转 DISPUTED 分支。
**验证**：日志同一 sessionId 出现 `watchdog OPEN timeout` 后紧跟 `restock door-open ack … applied=false`；
SQL `select count(*) from shopping_session where state='FAILED' and fail_reason like '%无设备回执%'` 与
`ops_exception where type='LATE_RECOGNITION'` 对账，应无「有异常无工单」。

### R18-2（P1）批次账扣减仍是读改写，无 version / 无 CAS

- `InventoryLotService.java:300-312`（`deductBatchAcrossLots`）、`:338-346`（`restoreToBatch`）、
  `:260-267`（跨批 FEFO 收尾）均为 `lot.setQuantity(lot.getQuantity() ± n); lotRepository.save(lot)`。
- `DeviceSkuLot.java` 实体无 `version` 字段（grep 0 命中）⇒ `save` 是绝对覆盖，无 CAS。

**为什么是缺陷**：本批把**汇总行**改成了单语句原子扣（`DeviceSkuInventoryMapper:116-128`），但批次账没跟上。
同一 SKU 的 FEFO 扣减与下架/报损/盘点 ADJ 并发（正是 P-1 要防的锁租约过期窗口）时，后写者用旧快照覆盖，
已扣量丢失 → 账面虚高、可再卖；V457 的 CHECK 只拦负数，拦不住「少扣」。

**修法**：这三处改用 `deductQuantityAtomic` / `increaseQuantityAtomic` 同款单语句（批次表补两条条件 UPDATE），
或给 `DeviceSkuLot` 加 version 并全量走 CAS。**验证**：并发 IT——FEFO 扣 5 与 writeOff 3 同时提交，终量必须为 2。

### R18-3（P2）`dispute_created` 模板未种库，消费者站内信静默丢弃

- `DisputeService.java:242`：`notificationService.notifyConsumer(uid, "dispute_created", …)`。
- 全仓迁移无该模板：`grep -rn dispute_created services/trade-service/src/main/resources/db/migration/*.sql` → 0；
  既有模板种子见 `V163/V165/V228/V325/V354`。
- `NotificationService.java:129-133`：模板找不到 → `log.debug` 后 `return`。

**为什么是缺陷**：本次兜账的产品目标是「三端可见」，消费者侧实际触达为 0，且失败只有 debug 日志、无告警。
**修法**：新增 Flyway 种 `dispute_created`（title/body 带 `{disputeNo}`），并加「代码引用的 templateCode 必须在迁移中种过」门禁。
**验证**：`select 1 from notification_template where template_code='dispute_created'`；
`select count(*) from notification_log where template_code='dispute_created'` 应随兜账增长。

### R18-4（P2）建档竞态 catch 后继续发 SQL → PG 事务 aborted

- `InventoryService.java:290-304`：
  `catch (DuplicateKeyException) { }` 之后同事务内直接 `increaseQuantityAtomic(...)`。

**为什么是缺陷**：PostgreSQL 唯一键冲突后事务进入 aborted（25P02），除非有 SAVEPOINT；Spring 此处无嵌套事务，
第二条 UPDATE 必抛「current transaction is aborted」⇒ 退款回补/增量整笔 500，且原本要加的增量丢失。
**修法**：改单语句 upsert（`INSERT … ON CONFLICT (device_id, sku_id) DO UPDATE SET quantity = device_sku_inventory.quantity + EXCLUDED.quantity`）
并校验影响行数。**验证**：两连接并发对「无汇总行」回补，均成功且终量=两笔之和。

### R18-5（P2）原子 UPDATE 不递增 version，实体 CAS 对其失明

- `DeviceSkuInventoryMapper.java:113-128` 注释明示「version 不在此处递增」。
- `InventoryLotService.java:519-532` `syncAggregateInventory`：读实体（带 version）→ `setQuantity(sum)` → `save`（`eq(version)` CAS）。

**为什么是缺陷**：原子扣减不会改变 version，于是「读→汇总→save」的 CAS 仍成功，把刚扣掉的量按旧快照写回。
触发面：同一 SKU 同时存在批次口径汇总与原子扣减（补货建账过渡、多实例）。设计注释只解释了「不需要」，
没有覆盖「其它路径用 version 做 CAS」这一事实。
**修法**：两条算术 UPDATE 追加 `version = version + 1`；或汇总路径改走增量而非绝对覆盖。
**验证**：事务 A 原子扣 2 提交后，事务 B 用旧 version 执行 save，影响行数应为 0。

### R18-6（P2）`finish` 无 try/finally + 心跳不校验线程存活 → 锁可被钉到上限

- `ScheduledTaskService.java:266-281`：`save(row) → cancelLeaseHeartbeat(taskKey) → unlock(...)` 裸顺序，无 finally。
- `:308-330`：心跳只判 `renew()`（锁 owner 是否还是自己），不看持锁线程是否还活着；上限 `MAX_HEARTBEAT_RENEWALS=120`（≈6.6h）。
- 调用方形态（如 `AutoReplenishmentTaskService.java:62/82/84`）：`finish` 在成功/异常分支直接调用，无二次兜底。

**为什么是缺陷**：`finish` 的 REQUIRES_NEW 写库一旦失败（本仓有间歇写失败史）或任务体以 `Error` 退出，
cancel/unlock 被跳过，而心跳仍按 owner 命中续租 → 锁在、任务不在，全集群静默跳过该任务数小时。
**修法**：`finish` 用 `try/finally` 包 cancel+unlock；心跳增加「任务体心跳位/超时」判据。
**验证**：单测 fake lock：令 `save` 抛异常，断言 `renew` 已停且 `unlock` 被调用。

### R18-7（P3）`renew` 把瞬断与易主并成 false

- `DistributedLockService.java:106-128`：`catch (Exception e) { log.error(...); return false; }`；
  上层（`ScheduledTaskService:313-318`）见 false 即 `cancelLeaseHeartbeat`。

**为什么是缺陷**：Redisson 超时/重试 3s 内一次网络抖动或主从切换，长任务剩余时间即无保护，到点可能被第二实例接管（双跑），
且只有一行 error 日志。**修法**：三态（RENEWED/LOST/TRANSIENT），TRANSIENT 再试一轮；LOST 才停。

### R18-8（P3·◐）心跳句柄未绑定身份

- `ScheduledTaskService.java:317/325/334-339`：`leaseHeartbeats.remove(taskKey)` 不比较句柄。

**为什么是缺陷**：同 JVM 内上一轮晚到的失败分支可能把新一轮的心跳摘掉，导致新一轮失去保护。
**修法**：`remove(key, myFuture)`；不适用时先比引用再 cancel。

### R18-9（P3）`LATE_RECOGNITION` 不进商户争议卡

- `clients/merchant-mp/src/utils/todo-list.ts:248`：`if (key === 'dispute') return t === 'DISPUTE' || t.startsWith('RECOGNITION')`。
  `LATE_RECOGNITION` 以 `LATE` 开头 ⇒ 不匹配；标签已在 `SysDictBootstrap.java:392` 补上。
- 徽标与卡片走同一分类函数（同文件注释），因此徽标计数 ≠ 四卡合计，且列表里能看到一条「不属于任何卡」的待办。

**修法**：归类改 `t === 'DISPUTE' || t.includes('RECOGNITION')`，并扩 `todo-list.test.ts` 的全类型不变式。

### R18-10（P3·◐）缓存失效只广播 L1，不防 loader 回灌

- `CacheService.java:100-140/163-178`：先 Redis 后 L1；`evict` 删 L2 + 广播 L1。

**为什么是缺陷**：对端在失效窗口内已开始的 loader 会在 evict 之后把旧值写回 L2，全集群继续读到 TTL 结束。
**修法**：key 带前缀版本号，或失效后延迟二次删除 + 写前版本校验。**验证**：IT——loader 阻塞 → 提交新值 → evict → 放行 → 再读不应为旧值。

### R18-11（P3·◐）归档断链 + 防假账门禁覆盖面

- 归档（`1633ae09`）后仍有 14 处链接指向旧路径，含 `docs/PROJECT_KNOWLEDGE.md:198/309/1669/3107` 等；
- `scripts/check-ledger-evidence.mjs:82-121` 只解析反引号 token、且只查 `scripts|docs|.cursor` 前缀，
  markdown 链接与裸 `FooService:262` 形态不校验。

**修法**：链接改指 `archive/…`；门禁增加 `](…)` 采集与 `*.java:行号` 存在性校验，并补负向用例。

### R18-12（P3·◐）日志上限与 compose 校验缺口

- `infra/docker-compose.full.yml`：18 个服务仅 7 个有 `logging` 上限（trade-service/device-service/redpanda/minio/grafana/alertmanager 等 11 个缺）；
- CI 无 `docker compose config` 步骤，`fd7f1a44` 修掉的非法块此前一路绿灯到部署才炸。

**修法**：补齐 logging；CI 加 `docker compose -f infra/*.yml config -q`。

---

## 三、待复核（子代理提出、主审未逐条复验，勿据此排期）

| 候选 | 位置 | 要点 |
|---|---|---|
| ack 与锁失败竞态 | `SessionDoorService.java:136-152` / `DeviceReportController.java:112` | ack 即 OPENING→SHOPPING，随后 lock FAIL 因状态已变被丢弃，会话挂 SHOPPING 产生假争议 |
| 兜账未过滤会话类型 | `VisionResultIngestService.java:100-108` | 补货/运维会话若有 items 上报，会走建争议单路径；可达性依赖端侧是否对补货单号报 items |
| cookie 会话不判过期 | `clients/consumer-mp/src/utils/consumer-api.ts:63-65` | cookie 过期仍直发打点 → 401 噪音/丢点 |
| 跨 SKU 锁顺序 | `InventoryLotService.java:177-190` | 无 tiebreaker，PG 死锁后 500 无重试 |
| P-4 无实际受众 | `AdminDashboardController.java:527/539` | 唯一 evict 点对应 key 无读点；真实被缓存前缀无写侧失效 |
| IT 边界 | `ScheduledTaskLeaseRenewalIT.java:88/91` | `sleep(3000)` 对 2s 租约、`leaseSeconds/3` 整数除法实际 1s；慢 CI 可能假红 |

---

## 四、已核对无问题（子代理结论，主审抽验）

- 汇总行扣减谓词 + 影响行数 + 409 整单回滚；V457 不与任何代码路径冲突（dev 库实查两表负值 0、`flyway 457` success）。
- 原子扣减不引入「订单重复扣」：幂等仍由会话锁 + `inventory_deducted` 承担，本条仅指批次账并发写。
- 迟到兜账不二次扣款：结案统一 `confirmDisputedItems`，有单走差额改单、无单才 `finalizeOrder`，且受会话结算锁串行。
- 集群心跳：广播回环有 UUID 前缀过滤、锁 key 构造与 `tryLock/unlock/isLocked` 一致、daemon 线程 + `@PreDestroy` 收尾。
- `46a23b3f` 三个 IT 确实会跑（failsafe + CI `-pl` + 报告正扫）；`a3ad436a`/`2277e00b` 为纯格式。
- `fd7f1a44` 修复本身正确：旧写法 `docker compose config` 报 `volumes … logging not allowed`，现文件 `config -q` exit 0。

---

## 五、建议处置顺序

1. **R18-1**（资金损失面未闭合）+ **R18-3**（通知，同属事故修复承诺）——同一批修，改完用真机弱网复跑一次开门。
2. **R18-2 / R18-4 / R18-5**（库存一致性三连）——同一批，配并发 IT（含负向控制：换回读改写必须红）。
3. **R18-6 / R18-7 / R18-8**（租约三连）——同一批，补 fake-lock 单测三态。
4. **R18-9 ~ R18-12**（P3）——按发布节奏并入常规批。

---

## 六、处置记录（2026-11-12 当日修，P1×2 + P2×4 全清）

| ID | 处置 | 验证 |
|---|---|---|
| R18-1 ✅ | `77b8feeb`：网关 OPEN 超时配置化 `jiangyi.watchdog.open-timeout-ms`（默认 70s = trade 60s + 10s 余量），yml 注释钉联动约束；15~60s 回执正常开门、60~70s 撞 CANCELLED 由兜账 v2 救 | `SessionWatchdogTest` 4 用例（阈值=构造参数契约 + 15s 旧行为 A/B 对照 + 上报失败不出队） |
| R18-3 ✅ | `77b8feeb`：V458 种 `dispute_created`（CONSUMER/DISPUTE）；对账实证代码引用 3 码仅此缺种 | 新门禁 `check:notification-template-seeded`（代码引用 vs 迁移已种对账，三态验证过），已接 `check:audit-gates` 链 |
| R18-2 ✅ | `51916f22`：批次三处读改写改单语句原子 UPDATE（扣减走既有 `deductQuantityAtomic`，回补走新增 `increaseQuantityAtomic` + 独立 `updateStatusAndSlotById` 只写属性列） | `InventoryConsistencyR18IT`（真 PG）：双路原子并发终量 2；**负向控制**——绝对覆盖旧形态实证会覆盖已提交原子结果 |
| R18-4 ✅ | `51916f22`：`applyDelta` 增量改单语句 upsert（`ON CONFLICT DO UPDATE` 算术累加），默认值与原建档口径一致 | 同 IT：行缺失双连接并发回补双方成功、终量=两笔之和（version=1 为 INSERT+UPDATE 各一指纹） |
| R18-5 ✅ | `51916f22`：两条汇总算术 UPDATE 追加 `version=version+1`；`syncAggregateInventory` 加 CAS 冲突重试（3 轮收敛）承接显性化 409 | 同 IT：原子扣提交后旧 version 的实体 save 必失败且不覆盖；当前 version save 正常递增 |
| R18-6 ✅ | `33a1a877`：finish 改 try/finally 包 cancel+unlock；配套 `leaseHolders` 执行中标志（tryBegin 登记 / finish finally 移除 / 心跳校验） | `ScheduledTaskLeaseRenewalIT` 回归 3/3 绿 |
| R18-7 ✅ | `33a1a877`：`renew` 三态 RENEWED/LOST/TRANSIENT，心跳只对 LOST 停、TRANSIENT 下周期再试 | `DistributedLockServiceRenewTest` 5 用例（mock Redisson 钉三态映射，瞬断≠易主） |
| R18-8 ✅ | `33a1a877`：心跳失败分支带身份摘除 `remove(key, myFuture)` | 同上单测覆盖签名；摘除语义见 ScheduledTaskService 注释 |

**未处置**：R18-9 ~ R18-12（P3）按审计建议并入常规批；§三待复核 6 条不动。
**部署提醒**：V458 在 jar 内 ⇒ 验证/发布须重建 trade 镜像再 `up -d --no-deps --force-recreate`（铁律 41）；网关阈值变更须重建 jiangyi-gateway 镜像。
