# UAT R19 · `settlement-bill`（商户月度结算单）触发入口方案设计

> **本轮性质**：纯只读分析 + 方案设计。**未改任何产品代码、未造/未改任何数据、未写活库**。
> **环境**：`aicabinet_uat`（容器 `ai-cabinet-postgres-1`）；trade-service 靶端 `127.0.0.1:18080`（容器内 8080）；XXL-JOB admin 容器在跑。
> **锚点**：`dev` HEAD `f5fd66bf`（2026-11-08）。工作树里 peer 的 4 个在制文件未触碰（`docs/PAYSCORE_*`、`docs/figures/payscore-*`、`scripts/templates/payscore-*`、`docs/uat/HANDOVER-R15-R19.md`）。
> **用户拍板前提（2026-11-08）**：**不造数据、如实登记不可测**；R19 只回答「触发入口为什么缺、有哪些补法、推荐哪个」。

---

## 一、结论（先看这）

| 问题 | 结论 |
|---|---|
| 结算单由谁生成？ | `MerchantSettlementBillService.rebuildMonth(YearMonth)`；唯一调用方是 `MerchantSettlementBillScheduler.rebuildMonthlyBills()` 的 `@Scheduled`（每日 02:40），一次重建「上月 + 本月」两张账期 |
| 当前有没有触发入口？ | **有一条，但只有自动的**：`@Scheduled` cron 在跑（实库执行记录 SUCCESS）。**手动/运维触发面不存在** |
| 「无触发入口」的真因 | 不是没有调度、也不是路由被禁用，而是 **`settlement-bill` 没登记进 `ScheduledTaskRegistry`** ⇒ 运营台「立即执行」按钮不渲染（`registryBound=false`），通用 REST `POST /api/v2/ops/admin/scheduled-tasks/settlement-bill/run` 返回 **404「任务不存在」** |
| 它归谁调度？ | **Spring 常驻**：不在 `XxlJobManagedTasks.KEYS` ⇒ 即使 `xxljob.enabled=true`，内置 `@Scheduled` 也不让位、继续跑；同时 **不在超期看护范围**（看护只扫 KEYS）⇒ 停摆不会被告警 |
| 报表为什么恒空？ | 数据形态：`order_revenue_split` 实库 3 行**全 VOIDED**，被 `buildRows()` 的 `isReversed` 口径排除 ⇒ 0 商户。**与触发入口无关**；补入口也变不出金额（除非有非 VOIDED 实单，但用户已拍板不造） |
| 推荐方案 | **A1：只补 `ScheduledTaskRegistry` 登记**（保持 Spring 常驻、不并入 XXL），复用现有通用「立即执行」端点；资金面零变化，改动最小（1 个构造参数 + 1 行 `register`） |

一句话：**「无触发入口」精确指「无手动触发面 / 无运维可触达面」，不是「任务不跑」**。自动链是通的、执行记录是真的、0 商户是口径正确的结果。

---

## 二、现状（写链 / 读链 / 调度面）

### 2.1 写链（唯一生成路径，无 HTTP 写入口）

```
@Scheduled(cron = "${aicabinet.settlement-bill.scheduled-cron:0 40 2 * * *}",
           zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
MerchantSettlementBillScheduler.rebuildMonthlyBills()          // :33-59
  ├─ taskService.tryBegin("settlement-bill", 1800)             // :38 启用开关 + job:settlement-bill 分布式锁
  ├─ rebuildMonth(上月) + rebuildMonth(本月)                    // :44-47
  │    └─ MerchantSettlementBillService.rebuildMonth            // :81-92
  │         ├─ distributedLockService.tryLock("settlement-bill:rebuild:<yyyyMM>",120,5)  // :83
  │         └─ doRebuildMonth(month)                            // :94-130
  │              ├─ select order_revenue_split
  │              │    created_at ∈ [月初, 次月初) Asia/Shanghai
  │              │    AND settlement_batch_no IS NOT NULL       // :95-100
  │              ├─ buildRows(month, splits, now)               // :153-194
  │              │    ├─ 跳过 merchantId 空 / isReversed(VOIDED|REVERSED)  // :160, :72-74
  │              │    ├─ settled_cents ← status='SUCCESS'       // :168-169
  │              │    ├─ pending_cents ← 其它有效状态           // :170-171
  │              │    └─ failed_count ← WECHAT_FAILED|FAILED    // :173-175
  │              ├─ 存在则按 (merchant_id, period_month) 刷新金额，**不动 status/confirmed_at**  // :104-124
  │              └─ deleteStaleRows(本月已无有效 split 的商户行) // :132-147
  └─ taskService.finish("settlement-bill", SUCCESS|FAILED, summary, start)  // :52/:56
```

- **服务依赖只有三件**：`OrderRevenueSplitMapper`、`MerchantSettlementBillMapper`、`DistributedLockService`（`MerchantSettlementBillService.java:50-60`）⇒ **重建全过程不碰钱包、不写账本、不打款**，只在 `merchant_settlement_bill` 里写/删快照行。
- 表结构：`V330__merchant_settlement_bill.sql:19`，联合主键 `(merchant_id, period_month)`（`:33`），`bill_no` 唯一（`:36`）；状态列默认 `PENDING`，注释写「CONFIRMED = 人工确认」，但 **HEAD 里没有任何代码把状态改成 CONFIRMED**（服务只刷新金额；mapper 仅 `findByMerchantAndPeriod` / `listByMerchantsAndPeriodBetween` 两个读方法）。「已 CONFIRMED 行只刷新金额」是**为将来留的行为**，不是现存写面。
- 调度种子行：`V334__scheduled_task_settlement_bill.sql:9-12`（`task_key='settlement-bill'`，`task_name='商户月度结算单重建'`，`schedule_desc='每日 02:40'`）⇒ **运营台列表里看得到它**（这是 `finish()` 能落执行记录的前提）。
- 单元测试：`MerchantSettlementBillRebuildTest`（6 例）覆盖 `buildRows` 分组/排除冲正/大小写、`rebuildMonth` 插入/刷新保 CONFIRMED/删陈旧行/锁忙返回 0；**没有任何「触发入口」性质**的测试。

### 2.2 读链（商户端有、运营台没有）

| 面 | 位置 | 说明 |
|---|---|---|
| 商户端 API | `MerchantPortalController.java:558-567` → `GET /api/v2/merchant/settlements/monthly-bills?from=yyyy-MM&to=yyyy-MM`（权限 `merchant:settlements:view`） | 实现 `MerchantFinanceService.java:384-418`：默认近 12 个月、区间上限 24 个月、按商户 feature pack 过滤 |
| 商户小程序 | `clients/merchant-mp/src/pages/settlements/settlements.vue:157-188` | 有「月度结算单」区块 + 空态「暂无月度结算单」 |
| 运营后台 | **无** | `clients/admin-vue` 全树 grep `monthly-bills / MerchantSettlementBill` 0 命中；2026-10-23 全测驱动（`.tmp/full-test-20261023/drivers/g2-step10.mjs:269-281`）当时的结论同样是「商户可见、运营不可见」 |

> 读面为空不是接口坏了：`merchant_settlement_bill` 实库 **0 行**（见 §六），接口返回 `[]` 是正确结果。

### 2.3 调度 / 运维面（缺口所在）

| 面 | 事实 | 证据 |
|---|---|---|
| 自动调度 | 在跑 | `scheduled_task` 行 `enabled=true`，`last_run_at=2026-11-08 09:04:21 (+08)`，`last_result=SUCCESS`，`last_message=结算单重建 2026-10 / 2026-11 共 0 商户` |
| 运营台「立即执行」 | **不显示** | UI 只在 `row.registryBound` 为真时渲染该按钮（`ScheduledTaskView.vue:321-329`）；实探该行 `registryBound=false` |
| 通用执行 REST | **404** | `ScheduledTaskController.java:104-110`：`registry.get(taskKey).orElseThrow(404 "任务不存在: ...")`；实探 `POST .../settlement-bill/run` → `404 {"message":"任务不存在: settlement-bill"}` |
| 注册表 | **未登记** | `ScheduledTaskRegistry.java:52-199` 构造器没有注入 `MerchantSettlementBillScheduler`，全文无 `register("settlement-bill", …)` |
| XXL 托管 | **不在清单** | `XxlJobManagedTasks.KEYS`（`:47-92`）无此 key；`ScheduledTaskXxlJobHandler` 无具名 handler，通用 `runScheduledTask` 也会被 `runKey` 的 `isManaged()` 直接拒（`:234-246`）；XXL 库 `xxl_job_info` 实查无 settlement 任务 |
| 超期看护 | **不覆盖** | `ScheduledTaskStaleMonitor.scan()` 只遍历 `XxlJobManagedTasks.KEYS`（`:203-226`）⇒ 该任务停摆不会产生 `NOT_REGISTERED/OVERDUE` 告警 |
| 停启开关 | ✅ 生效 | `tryBegin` 会读 `scheduled_task.enabled`（`ScheduledTaskService.java:195-198`），运营台「编辑/停用」可用 |
| 执行记录 | ✅ 落库 | 行存在 ⇒ `finish()` 能更新（否则静默丢弃，`ScheduledTaskService.java:239-252`） |

---

## 三、真因（四条，逐条可证）

**真因 = 「注册表漏登记」，不是「没有调度」「没有路由」或「定义被禁用」。**

1. **没有 Controller 写入口**：全仓 `rebuildMonth` 的唯一调用方是 `MerchantSettlementBillScheduler`（grep `rebuildMonth` 仅命中 Service 自身与 Scheduler）；`api/` 下的 settlement 路由全是读（`MerchantPortalController.java:535-588`）。所以不存在「有路由但没接上」这回事。
2. **有调度，但调度没有「手动面」**：通用执行端点按 `taskKey` 查注册表，`settlement-bill` 未登记 ⇒ 404；UI 同理隐藏按钮。这是**运维可触达性**缺口，不是运行缺口。
3. **也没进 XXL**：所以生产多实例下它仍是「Spring 常驻 + Redis 锁（每台空转抢锁）」，且**不在看护清单**——与 `refund-retry` 的定位相似（刻意不托管），但 `refund-retry` **有注册表登记**（`ScheduledTaskRegistry.java:133-134`），`settlement-bill` 两样都没有。
4. **门禁为什么没拦**：
   - `check-scheduled-task-seed.mjs` 的四条规则只校验「注册表 → 种子」「种子 → 代码字面量」「tryBegin → （注册表 或 种子）」——**「有 @Scheduled + tryBegin + 种子、但没注册表」恰好落在规则的缝隙里**（`scripts/check-scheduled-task-seed.mjs` 规则一~四，`:117-210`）。
   - `check-xxl-job-wiring.mjs` 的校验范围是 `XxlJobManagedTasks.KEYS`（脚本头注释与实现），**不在 KEYS 的任务天然不在射程**。
   - `check-scheduled-task-trybegin.mjs` 只检查「已注册任务的方法体含 tryBegin」，方向相反。
   - ⇒ 这不是门禁「判错」，是**判据集合没覆盖「未托管的 Spring 常驻任务是否可手动触发」**。

**历史旁证（说明这是已知豁免、不是新发现）**：2026-10-23 全测驱动 `g8-scheduled-live-align.mjs:53` 已把 `settlement-bill` 与 `session-failed-hold-clear` 一起列为「只记账豁免项」，`:154` 断言「两个只记账豁免项确实不在注册表里」。本轮实探这两条正是全系统**仅有的 2 个 `registryBound=false`**（43 行中 41 true / 2 false）。R19 相当于把这条旧豁免重新拿到台面上。

---

## 四、可选触发路径与利弊

> 资金口径总前提：`merchant_settlement_bill` 是**快照表**，重建不动钱；金额只来自**非 VOIDED/REVERSED 的 split**。**任何方案都不应通过造单/造假 split 来「凑出金额」**（用户已拍板）。

### A1. 补 `ScheduledTaskRegistry` 登记（保持 Spring 常驻，不并入 XXL）**【推荐】**

- 做法：`ScheduledTaskRegistry` 构造器注入 `MerchantSettlementBillScheduler`，加一行
  `register("settlement-bill", "商户月度结算单重建", "TRADE", ScheduleZones.desc("每日 02:40"), 1800, scheduler::rebuildMonthlyBills);`
- 立即获得：运营台「立即执行」按钮（`registryBound=true` 自动出现，**无需前端改动/产物重建**）、`POST /scheduled-tasks/settlement-bill/run`、审计 `SCHEDULED_TASK_RUN`、走 `tryBegin` 的启停开关 + `job:settlement-bill` 锁 + 执行记录。
- 利：改动最小（1 构造参数 + 1 行注册）；触发面与人工路径**复用同一套已审计的通用设施**，不新增 API/权限码/openapi 形状；资金面零变化（同 cron 同一方法、只重建上月+本月）；先例现成：`refund-retry`、`scheduled-task-stale-monitor` 都是「注册但刻意不托管 XXL」。
- 弊/风险：手动触发能跑的时间窗与 cron 相同（上月+本月），不能补任意历史账期（**这正是我们要的收敛**）；若遇到 `job:settlement-bill` 被占（cron 正在跑），返回 SKIPPED（安全）；重建会执行 `deleteStaleRows`（**已有语义**，手动触发不放大它）；任务仍不在看护范围（停摆不告警——如需，另立 A2）。
- 门禁影响：`check-scheduled-task-trybegin`（action 方法体内已有 `tryBegin`，`:38` ✅）、`check-scheduled-task-seed`（种子行已在 V334 ✅）、`check-xxl-job-wiring`（KEYS 不变 ✅）；无前端改动 ⇒ 不触发 admin 产物配对重建。

### A2. A1 + 并入 XXL 托管（七处同步）

- 做法：在 A1 基础上把 key 加进 `XxlJobManagedTasks.KEYS`，补具名 `@XxlJob("merchantSettlementBillJob")`、`ScheduleZones.XXL_CRON_BY_TASK`、`MAX_SILENCE_BY_TASK`、`infra/xxl-job/seed_aicabinet_jobs.sql`。
- 利：生产多实例下由调度中心单选派发（省掉每台空转抢锁）；失败重试/路由/触发历史在调度台可见；进入超期看护。
- 弊/风险（**比 A1 大一个量级**）：一旦 `xxljob.enabled=true` 而调度中心**没有这个 job 行/执行器路由异常**，内置 `@Scheduled` 会**让位且无人兜底** ⇒ 任务永久静默停跑（`check-xxl-job-wiring.mjs` 头注释里记的「停跑约 19 小时无人发现」就是这类）。上线前必须保证生产 XXL 库已 seed 该行；UAT 实测 XXL 库当前**没有** settlement 任务。⇒ 本批不做，作为独立决策项。

### B. 新增专用写端点（如 `POST /api/v2/ops/admin/settlement-bills/rebuild?month=yyyy-MM`）

- 利：可选账期、语义直白；不依赖「定时任务」这层抽象。
- 弊/风险：**新增资金邻近写面**（任意历史月重建会重算并**删除陈旧行**，含已 CONFIRMED 行的删除分支），爆炸半径明显大于 A1；需要新权限码 + openapi/shared-types 重生成 + admin 产物；与通用「立即执行」功能重叠。若坚持要端点，**必须钳制 month ∈ {上月, 本月}**，但那与 A1 等价、成本更高。⇒ 不推荐。

### C. 只靠 cron（维持现状）

- 利：零改动。
- 弊：运维侧「现在就想重跑一次」无正路（只能等次日 02:40）；UAT 想复验金额时也点不动。⇒ 不满足本任务诉求，仅作基线对照。

### D. 运维旁路（直接改库/手工插任务）

- 例如手工 `UPDATE scheduled_task` 或直接改 `merchant_settlement_bill`。**明确否决**：改快照表等于伪造账单；插行不改注册表也仍然 404；两者都绕审计。

---

## 五、推荐方案（A1）落地清单与验收

### 5.1 改动面（**本轮不实施，仅登记**）

| # | 文件 | 改动 | 说明 |
|---|---|---|---|
| 1 | `services/trade-service/.../ScheduledTaskRegistry.java` | 构造器加 `MerchantSettlementBillScheduler` 参数 | 与现有 40 个任务同构；依赖链 `registry → scheduler → ScheduledTaskService →(@Lazy) registry` 已被 `@Lazy` 打断，不会成环 |
| 2 | 同文件 | 加一行 `register("settlement-bill", "商户月度结算单重建", "TRADE", ScheduleZones.desc("每日 02:40"), 1800, scheduler::rebuildMonthlyBills)` | 租约与 cron 方法里的 `tryBegin(…,1800)` 一致 |
| 3 | （可选）单测 | 断言 `registry.get("settlement-bill").isPresent()`，并注入一个「action 被调用一次」的探针 | 防回潮；现有 `MerchantSettlementBillRebuildTest` 不覆盖触发面 |

**不新增**：Controller / DTO / 权限码 / 前端 / 迁移 / openapi。种子行已在 V334，不要重复插。

### 5.2 部署后如何验（能验什么 / 不能验什么）

| 判据 | 期望 | 手段 |
|---|---|---|
| 运营台出现「立即执行」 | 按钮可见，`registryBound=true` | admin 浏览器或 `GET /scheduled-tasks` 看该行 |
| 手动触发链通 | `POST /scheduled-tasks/settlement-bill/run` → `TRIGGERED`，`last_message=结算单重建 yyyy-MM / yyyy-MM 共 0 商户`，审计新增 `SCHEDULED_TASK_RUN/settlement-bill` | API + `admin_audit_log` + `scheduled_task.last_run_at` |
| 启停开关生效 | 停用后手动触发返回 `SKIPPED「任务已停用」`，cron 也跳过 | API |
| **金额正确性** | **本轮仍不可验**：无非 VOIDED split ⇒ 0 商户是正确输出 | 用户已拍板不造数据；如要实证，唯一正路是**真实业务产生一笔无退款实单**后重跑（不是造数据） |

> ⚠️ 不要把「手动触发返回 0 商户」包装成「结算金额已验证」。它只证明**触发链与执行记录**通了，**不证明金额口径**。

### 5.3 回滚

改动可逆：删掉注册行/参数即回到现状（自动 cron 不受影响——注册表只加「手动面」，不改调度源）。

---

## 六、明确不做

1. **不造数据**：不插 split / 不造订单 / 不改 `order_revenue_split.status` 来让账单出数（用户 2026-11-08 拍板）。
2. **不新增带 `month` 参数的任意账期重建端点**（会扩大历史账期重算/删除的爆炸半径）。
3. **不新增「确认结算单（CONFIRMED）」写接口**：当前无生产者，属另一件事；先有真实账单再谈确认。
4. **本批不并入 XXL 托管**（A2 独立决策，需生产 XXL seed + 看护阈值）。
5. **不改口径**：不动 `isReversed`（VOIDED/REVERSED 排除）、不动 `deleteStaleRows`、不动账期半开窗口、不动 `settlement_batch_no IS NOT NULL` 过滤。
6. **不为「运营台看结算单」新建 admin 页面**（现状是「商户可见、运营不可见」，是缺功能不是配置问题，另立需求）。
7. 不顺手改 `session-failed-hold-clear`（同族缺口，见 §七.3），避免一轮多维。

---

## 七、只读证据

### 7.1 活库（全部 `count(*)` / 只读 SELECT）

```sql
-- 账单表：0 行
select count(*) from merchant_settlement_bill;                         -- 0

-- 唯一金额来源：3 行全 VOIDED（时间均为 Asia/Shanghai）
select status, count(*) from order_revenue_split group by status;       -- VOIDED=3
--  1793976576182560827200 | VOIDED | 2026-11-06 22:49:36
--  1794017491816632472266 | VOIDED | 2026-11-07 10:11:31
--  1794019755246994590246 | VOIDED | 2026-11-07 10:49:15

-- 调度登记行：启用、最近一次 SUCCESS
select task_key, enabled, last_run_at, last_result, last_message
from scheduled_task where task_key='settlement-bill';
-- settlement-bill | true | 2026-11-08 09:04:21+08 | SUCCESS | 结算单重建 2026-10 / 2026-11 共 0 商户
```

应用日志（容器内，`+08:00`）：

```
2026-11-08T09:04:21.494+08:00 INFO ...MerchantSettlementBillService - settlement bill rebuilt month=2026-10 merchants=0 window=[2026-09-30T16:00:00Z, 2026-10-31T16:00:00Z)
2026-11-08T09:04:21.498+08:00 INFO ...MerchantSettlementBillService - settlement bill stale row removed month=2026-11 merchant=MCH-DEFAULT
2026-11-08T09:04:21.498+08:00 INFO ...MerchantSettlementBillService - settlement bill rebuilt month=2026-11 merchants=0 window=[2026-10-31T16:00:00Z, 2026-11-30T16:00:00Z)
2026-11-08T09:04:21.501+08:00 INFO ...MerchantSettlementBillScheduler - scheduled settlement bill rebuild completed: 结算单重建 2026-10 / 2026-11 共 0 商户
```

> 「stale row removed」证明 `deleteStaleRows` 在真实数据上被执行过（此前确有 MCH-DEFAULT 的 2026-11 行，split 冲正后重建把它删掉了）。

### 7.2 API 实探（只读 + 一次必然 404 的 POST；经网关 `http://127.0.0.1` → trade-service）

```
GET  /api/v2/ops/admin/scheduled-tasks            → 43 行；registryBound=true 41，false 2
     settlement-bill 行：enabled=true, lastRunAt=2026-11-08T01:04:21Z,
                         lastResult=SUCCESS, registryBound=false
POST /api/v2/ops/admin/scheduled-tasks/settlement-bill/run
                                                  → 404 {"code":404,"message":"任务不存在: settlement-bill"}
UNBOUND = ["session-failed-hold-clear","settlement-bill"]
```

> POST 在 `registry.get()` 为空时于 `ScheduledTaskController.java:108-109` 直接抛 404，**不会执行任何动作、不写审计**。

### 7.3 XXL 面实探

- trade-service 容器 `XXL_JOB_ENABLED=true`；
- `xxl_job_info` 中**没有** settlement 相关 job（按 `job_desc like '%结%'` 只有识别结算超时/周期费用月结/仓库月结/商户日结通知四条）；
- `XxlJobManagedTasks.KEYS`（`:47-92`）不含该 key；`ScheduledTaskXxlJobHandler.runKey` 对非托管 key 直接 `handleFail("非 XXL 托管任务")`（`:234-246`）。

### 7.4 源码 `file:line` 速查

| 主题 | 位置 |
|---|---|
| cron + tryBegin + finish | `services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantSettlementBillScheduler.java:33-59` |
| 锁 / 账期窗口 / 重建 / 刷新 / 删陈旧 | `MerchantSettlementBillService.java:81-92, 94-130, 132-147` |
| 口径（isReversed / settled / pending / failed） | `MerchantSettlementBillService.java:72-74, 153-194` |
| 表与唯一键 | `db/migration/V330__merchant_settlement_bill.sql:19, 33, 36` |
| 调度种子行 | `db/migration/V334__scheduled_task_settlement_bill.sql:9-12` |
| 注册表（未登记） | `ScheduledTaskRegistry.java:52-199`；对照 `refund-retry` `:133-134`、`scheduled-task-stale-monitor` `:197-198` |
| 通用执行端点 404 | `ScheduledTaskController.java:104-110` |
| 启停/锁/记录/registryBound | `ScheduledTaskService.java:190-206, 239-252, 350-363` |
| XXL 托管清单 | `XxlJobManagedTasks.java:47-92` |
| XXL 通用 handler 拒绝非托管 | `ScheduledTaskXxlJobHandler.java:30-33, 234-246` |
| 超期看护只扫 KEYS | `ScheduledTaskStaleMonitor.java:203-226` |
| 运营台按钮判据 | `clients/admin-vue/src/views/system/ScheduledTaskView.vue:319-336` |
| 商户读面 API / 实现 | `MerchantPortalController.java:558-567`；`MerchantFinanceService.java:384-418` |
| 商户端空态 | `clients/merchant-mp/src/pages/settlements/settlements.vue:157-188` |
| 门禁缝隙 | `scripts/check-scheduled-task-seed.mjs`（规则一~四）；`scripts/check-xxl-job-wiring.mjs`（范围=KEYS） |
| 单测覆盖（仅服务逻辑） | `MerchantSettlementBillRebuildTest.java:83-194` |
| 历史豁免旁证 | `.tmp/full-test-20261023/drivers/g8-scheduled-live-align.mjs:53, 154` |

---

## 八、遗留观察（不在本轮结论里，但值得记账）

1. **执行记录时刻与 cron 不符**：配置是 `02:40 Asia/Shanghai`，实库最近一次却是 `2026-11-08 09:04:21`。可证的三条负向——`admin_audit_log` 里 `target_id='settlement-bill'` 的 `SCHEDULED_TASK_RUN` 为 **0 行**、XXL 库无对应 job、日志中没有该次请求的准入 SQL（37 行同 trace 全是重建自身）——都排除了「管理台/XXL 手动跑」。代码里 `rebuildMonth` 的唯一调用方是 `@Scheduled`，故最可能是**宿主睡眠/时钟跳变后 cron 补触发**（与本机常被中断的使用习惯一致）。**不影响本方案**，但说明「last_run_at 不是调度准点性证据」。
2. **同族缺口**：`session-failed-hold-clear`（`SessionExpireService.java:139-190`，`@Scheduled(fixedRate=300000)` + tryBegin + V306 种子）与 settlement-bill 一样「有自动、无手动、无看护」。是否同样补注册，另立决定。
3. **`CONFIRMED` 无生产者**：表注释/服务注释都写了「人工确认」，但代码没有任何写 `status='CONFIRMED'` 的路径；读面会把状态透出给商户端。若产品要「确认账单」，是独立需求。
4. **未登记行可被删除**：因为 `registryBound=false`，运营台对这两行会**多显示「删除」**（`ScheduledTaskView.vue:332-334`）。删掉种子行后任务仍会跑，但 `finish()` 静默丢弃记录 ⇒ 再次变成「隐形任务」。A1 落地后该行变成内置任务、删除按钮消失；在此之前请勿在运营台删它。
5. **运营台无账单页**：财务若要在后台看月度结算单，属于缺功能（§六.6），不是本轮能顺手补的。
