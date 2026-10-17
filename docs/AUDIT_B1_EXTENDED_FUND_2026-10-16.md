# B-1 扩展审 · V355 / V359 / V361 资金域（2026-10-16）

> 纪律：不采信 commit `93bf2697` 的内联自评（那是「上线前审计 🔴 修复（6 项阻断）」的自我申报），
> 每条结论都要 **盘上源码 + pg 实查 + 真库测试**。取证环境：本地 dev @ 与 origin/dev 同点（0/0），
> 共享 dev 库只读 SELECT；负向测试用 Testcontainers 真库（不碰共享栈）。

## 0. 一句话结论

三条链共用同一个**根因**：幂等只活在应用层（`findByRef` 查重 + Redisson 锁），
库里 `(owner, ref_type, ref_id)` **没有唯一索引**，而锁的 `unlock` 发生在 `@Transactional`
方法体内、早于事务提交 ⇒ 并发第二笔看不到未提交流水 ⇒ 双扣/双入账无人拦。
另有一处**口径未闭合**（费用台账扣了钱仍按 PENDING 催缴锁柜，而三端没有任何结清入口）。

## 1. 实测证据

| 检查 | 实测结果 |
|---|---|
| `pg_indexes`（staff/line/merchant_wallet_ledger） | 只有 `*_pkey` + 时间/owner 普通索引；merchant 侧 `idx_..._ref` 是 **非唯一** btree；staff/line 侧**连 ref 索引都没有** |
| dev 库重复 ref 行数 | staff/line/merchant 三表均 **0** 组重复 ⇒ 加唯一索引不需要先去重 |
| dev 库三表行数 | `0 / 0 / 1` ⇒ 索引锁窗口可忽略 |
| `merchant_fee_ledger` | **0 行**；`device_info` 里 `rent_cents>0 或 data_fee_cents>0` 的设备 **0 台**、`coop_mode=RENTAL` **0 台** ⇒ F-2 今天未引爆 |
| 三端对台账端点的引用 | `grep -rn "merchant-fee-ledgers" clients/`（admin-vue / merchant-mp / consumer-mp）**零命中** |
| `scheduled_task` | `merchant-daily-income-notify` / `line-commission` / `machine-payee-bill` 均 `enabled=t`（V376 只关 refund-retry 与 deposit-monthly-refund） |

## 2. 发现清单

### F-1 🔴 钱包幂等无库级兜底（跨 V355/V359/V361 的同一根因）
- 代码：`StaffWalletService.payoutIfAbsent/creditIfAbsent/debitIfAbsentAllowNegative`、
  `MerchantWalletService.debitIfAbsentAllowNegative`、`LineWalletService` 同族 ——
  形态一致：`@Transactional` → `runWithWalletLock`（tryLock 60s/等 5s）→ `findByRef` → 改余额 → `appendLedger` → finally `unlock`。
- 时序缺陷：`unlock` 在方法内、提交前。并发第二线程拿到锁后 `findByRef` 在**新事务 READ COMMITTED**
  下看不见未提交行 ⇒ 通过查重；`reload()` 的 `FOR UPDATE` 行锁发生在查重**之后**，救不了这一步。
- 暴露面排序：
  1. **V359 提现标记**（`OpsStaffWalletController:47`，`requestNo` 幂等纯应用层）——运营双击/网络重试 = 员工余额被双扣，**直接对个人资损**；
  2. V355 每日批扣（refId=日期，靠 `tryBegin` 租约互斥；租约过期后重触发可双扣）；
  3. V361 冲回——refId 每次递增本无重复可言，且其累加行 `commission_clawback` 有唯一键会撞键回滚 ⇒ 实际有兜底。
- 处置：**V379** 给三张流水表补 partial unique
  `(owner, ref_type, ref_id) WHERE ref_type IS NOT NULL AND ref_id IS NOT NULL AND ref_type <> 'WITHDRAW'`。
  两点非显然的口径（写迁移前逐一核过全部 `ref_type` 调用点）：
  · NULL ref（手工调账）不约束；
  · **`ref_type='WITHDRAW'` 必须豁免**——一笔提现请求天然写 `WITHDRAW_FREEZE / RELEASE / PAID / FEE`
    多行，且「打款失败释放 → 重试再冻结」（`MerchantWithdrawService:605`、`LineWithdrawService:421`）
    会产生**第二条同 entry_type、同 ref 的 FREEZE 行** ⇒ 把 `entry_type` 放进唯一键也照样误伤提现。
    对应 IT 用例 `withdrawLifecycleRowsMayRepeatRef` 把这条豁免钉住，防日后收紧。
- 实测（铁律 4 闭环）：负向轮 `.tmp/wallet-it-round2.log` ⇒ `Tests run: 4, Failures: 2`
  （`walletLedgersHaveUniqueRefIndex` 报 `staff_wallet_ledger 缺少…唯一索引[]`；
  `duplicateRefRowIsRejectedAtDbLevel` 报「第二行竟然插入成功」）⇒ F-1 是真缺口，不是纸面推断。
  加 V379 后 `.tmp/wallet-it-round3.log` MVN_EXIT=0，surefire 报告
  `WalletRefIdempotencySqlIT tests=5 failures=0`、`CommissionClawbackServiceTest tests=7 failures=0`，
  同轮 Flyway 从空库一路应用到 `now at version v379`（375 migrations）。
  共享 dev 库建索引前实测重复组=0、三表行数 0/0/1 ⇒ 增量应用无需去重。
- 未做的可选加固：把 `DuplicateKeyException` 映射成 `false`（现在会 500）。刻意不加——
  唯一键冲突会回滚整个事务，余额不会被改，只是运营看到一次失败；等真出现误伤再补，避免为不会发生的场景写兜底。

### F-2 🔴 费用台账「钱已真扣 + 仍按 PENDING 催缴锁柜」，且三端无结清入口 —— **需拍板**
- 链路：`chargeDailyPeriodFees` 每日从商户钱包余额**真扣**（可负）→ 同额再写一条 `PENDING` 台账
  （V355 头注「两处双记录」= 2026-10-14 拍板）→ `checkOverdueAndRemind` 用 `pendingFeeTotal`
  作催缴与锁柜判据，阈值 `monthlyFeeTotal × lock_months`（默认 1）→ 把名下柜机打上「费用欠缴锁」。
- 唯一能把行置 `SETTLED` 的是 `MerchantFeeLedgerService.settle`，只有两个后端端点，**没有任何前端入口**
  ⇒ 事实上无人可结清 ⇒ 配了租金的商户约一个月后必然触发锁柜，而费用其实早已从余额扣走。
- 今天没炸只因库里 0 台账行、0 台计费设备 —— 运营在设备台账填一次 `rent_cents` 就开始累计。
- 两个口径（业务判断，不由我选）：
  - **(a) 余额扣即视为已收**：扣款同事务把台账置 `SETTLED`，催缴/锁柜只对「扣款失败/余额扣不动且充值未到」生效；
  - **(b) 余额不扣、只登记**（回到老系统「只登记不扣款」），`SETTLED` 才代表真收到钱；
  - 两案都需要补运营台页面（否则台账只写不查，对账死路仍在）。

### F-3 🟠 计费基准 ≠ 阈值基准（同一函数族里两套算法）
`monthlyFeeTotal` 对**所有**设备累加 `dataFeeCents`（不看 `coopMode`）；
`chargeDailyPeriodFees` 只在 `coopMode=RENTAL && rentCents!=null` 分支内才计流量费。
⇒ 免费投放设备、以及 RENTAL 但 `rent_cents` 为 null 的设备的流量费**永不摊**，却仍抬高锁柜阈值
（该锁的锁不到、该收的收不着）。

### F-4 🟠 「单商户失败次日重跑补扣」的注释不成立
两处 `catch` 注释都写「失败商户次日重跑补扣」（:146/:185），但幂等 `refId=昨日日期` ⇒
次日跑的是**新一天**的费用，失败那天永久漏扣，只 `log.warn`，无告警、无补账入口。

### F-5 🟡 扣费失败时日结通知虚高
`catch` 后 `feeCents=0` ⇒ 通知给商户显示 `fee 0.00 / net=收入全额`。
⇒ **2026-10-17 已修**（§5.1 / 台账 CB-056）。补一句：本条原描述只抓到「显示 0」这一条路径，实际还有两条更隐蔽的——**扣款成功但随后写台账抛异常 ⇒ 钱真被扣了、通知说 0**，以及**该日已被运营重跑扣过 ⇒ 幂等跳过仍按重算值展示，同一天费用数两遍**。修法把口径整体换成「读钱包流水实扣额」，三条路径一并归一。

### F-6 🟡 整数除法分摊不闭合
`rent/days + data/days` 逐设备整除，每设备每月最多少摊 `days-1` 分，无月末清算行。
⇒ **2026-10-17 判定「暂不修」**（量级上界、两种修法各自的新风险见 §5.2；属口径选择，非资金正确性缺陷）。

### F-7 🟠 冲回「递增已提交、钱没扣」的半状态（V361 独有）
`CommissionClawbackService:145/:205` 事务内先 `upsertClawback` 递增累计，再 `debitIfAbsentAllowNegative`。
后者返回 `false`（同 refId 已存在）**不触发回滚** ⇒ 已冲回额虚增而钱没扣，
下次 `net = gross - already` 把差额吃掉 ⇒ 永久少冲。头注「事务化避免半状态」只覆盖了抛异常路径。
处置：`false` 时抛异常回滚递增（线长/补货员两处对称改，`CommissionClawbackService:145/:205`）；
单测 `debitSkippedRollsBackClawbackIncrement` 钉住「跳过负扣 ⇒ 事件数 0 且事务 rollback」，
随 `CommissionClawbackServiceTest` 7/7 绿。

### F-8 🟡 线长侧按「当前费率」重算历史差额
`bps`/`fixed` 取实时配置（代码注释自标「费率事后变更是已接受的近似」）⇒ 费率上调后的窗内退款会按新费率多冲回，员工侧多扣。量化：`Δbps × 窗内 GMV 差额`。
⇒ **2026-10-17 已修**（§5.1 / 台账 CB-056）：冲回基数改为**发佣行自身的已入账佣金快照**，线长侧与原本就按行的补货员侧口径归一。

## 3. 对 `93bf2697` 自报 6 项的复核

| 自报 | 复核结论 |
|---|---|
| 🔴1 台账死路 → 新增 Controller | **未解**：补了后端，三端零入口（F-2） |
| 🔴2 DAILY_FEE 真幂等 | 语义成立（`debitIfAbsentAllowNegative` + 可负），但**库级兜底缺失**（F-1） |
| 🔴3 押金月退超退 | 本轮未审（属 V360，已由 V376 关到默认关）→ **2026-10-17 补审完成**：`runMonthlyRefunds` 由零用例补到 6 个回归用例（§5.3），`DeviceRentalServiceTest` 28/28 绿 |
| 🔴4 三任务七处同步 | 本轮未审（调度注册面，另列）→ **2026-10-17 补审完成**：三任务七处齐全、两道门禁绿；并勘误——「七处同步无门禁」的说法不成立（§5.4） |
| 🔴5 提现 requestNo 幂等 | 成立但纯应用层（F-1）；`body` 未传时退化为 `System.nanoTime()`，**跨请求不幂等** |
| 🔴6 台账/钱包同事务 | 成立（`LineCommissionJob:304` `transactionTemplate`） |
| 🟡4 催缴模板码 | 已修（`alreadySentType:374` 实证走 `merchant_fee_reminder`） |

## 4. 本轮已落 / 待拍板

- 已落（本轮，均已实测）：
  - F-1 → [`V379__wallet_ledger_unique_ref.sql`](../services/trade-service/src/main/resources/db/migration/V379__wallet_ledger_unique_ref.sql)
    三张流水表 partial unique（含重复检出 DO 块 + `WITHDRAW` 豁免）+
    [`WalletRefIdempotencySqlIT`](../services/trade-service/src/test/java/com/aicabinet/trade/integration/WalletRefIdempotencySqlIT.java)
    5/5 绿（负向轮 2 红见 F-1 末段）；
  - F-7 → [`CommissionClawbackService.java:145`](../services/trade-service/src/main/java/com/aicabinet/trade/service/CommissionClawbackService.java)
    与 `:205` 两处对称改为「跳过负扣即抛异常回滚累计行」，新增单测
    `debitSkippedRollsBackClawbackIncrement`，`CommissionClawbackServiceTest` 7/7 绿。
  - 门禁：`check-migration-safety` / `check-flyway-seed-separation` / `check-competitor-benchmark` 对 V379 全 EXIT=0
    （索引类迁移按门禁规则免竞品引用；首版头注引用 `cabinet_order.session_id` 触发过一条 DROP INDEX 热表
    WARN，已去掉表名字面量后转纯绿）。
  - 踩坑入册：`docs/engineering/lessons-learned.md` #302（`target/classes` 孤儿文件让本地 IT 假红）、
    #303（提现生命周期 ref 天然多行 ⇒ 唯一索引必须豁免）。
- 待拍板：F-2 口径二选一（(a)/(b) + 是否补 UI）、F-3 流量费到底对谁收、F-4 漏扣要不要补偿任务。
- 未审（下一轮）：🔴4 七处同步一致性、V347 对账导入、V356 机器账单、押金 V360/V365 链的负向测试。
  （⇒ 2026-10-17 更新：🔴4 与押金 V360/V365 链已补审，见 §5.3/§5.4；V347 对账导入的**形态**已由台账
  CB-055 取证并给出结论，V356 机器账单仍无人审。）

## 5. 2026-10-17 复审判定与落地（F-5/F-8 已修、F-6 暂不修、🔴3/🔴4 补审）

> 用户 2026-10-17 对「B-1 剩余三条 🟡」的授权原话：「F-5…F-6 分摊不闭合 / F-8 冲回取实时费率 这个你看竞品和老代码看修不修」「三任务七处同步 → 补审」。
> ⇒ F-5/F-8 **修**（竞品+老代码都支持），F-6 **暂不修**（修法各自引入更坏的新风险，见 §5.2），🔴3/🔴4 **补审**（§5.3/§5.4）。判定依据入台账 [CB-056](COMPETITOR_BENCHMARK.md)。

### 5.1 已修：F-8（`da72804a`）+ F-5（`4bfc0de6`）

| 面 | 改法 | 钉死的用例 |
|---|---|---|
| F-8 线长佣金冲回 | 冲回基数由「实时 `line_manager.commission_rate_bps` + `commission_fixed_cents`」改为**该行已入账佣金快照**：`冲回 = commissionCents × GMV减少额 / gmvCents`（[CommissionClawbackService.java:120-143](../services/trade-service/src/main/java/com/aicabinet/trade/service/CommissionClawbackService.java)），类头注同步改写。数学上冲回额 ≤ 已入账额 ⇒ 只少扣不超扣。两条**保守不冲**边界：`gmvBase<=0`（V361 前存量行无快照）、档案已删除（保持老边界，不为「补齐」新造扣钱路径） | `managerRowClawsBackByPostedCommissionSnapshot`（档案费率故意设 800bps 也不参与）、`managerRowClawbackCappedByPostedCommission`、`managerRowWithoutGmvBaseIsNotClawedBack`、`deletedManagerRowIsNotClawedBack`；另把原本自相矛盾的 `fullyRefundedOrderStaysOutOfBase`（断言 0 却写全额退）改成真实全额冲回断言。`CommissionClawbackServiceTest` 10/10 绿 |
| F-5 日结通知费用栏 | 费用取**钱包流水实扣额** `MerchantWalletService.debitedCentsByRef(merchantId,"SETTLEMENT_FEE",日期)`（[MerchantWalletService.java:196](../services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantWalletService.java)，只读、无锁）；`chargeDailyPeriodFees` 返回值语义收紧为「**本次**实扣」⇒ 幂等跳过报 0（`:366-374`），消除「收入商户循环 + 全量循环」同日双计；失败数经 `NotifyOutcome.feeChargeFailed` 冒泡进定时任务执行记录（`:106-130`），不再只 `log.warn` | `notifyShowsWalletDebitedAmountNotRecomputedFee`（重算 100 分 / 流水 250 分 ⇒ 显示 2.50）、`feeChargeFailureIsCountedAndFeeShowsActualZero`（sent=1、feeChargeFailed=1、fee="0.00"）、`alreadyDebitedDayReportsZeroNewChargeButLedgerRowStillWritten`。`MerchantDailyIncomeNotifySchedulerTest` 16/16 绿 |

顺带核清用户 10-17 的两个疑问：①日结通知扣的**不是**流量费单项，而是「租金月额÷当月天数 + 流量费(分/天)」合计；②余额**可以为负**（`debitIfAbsentAllowNegative`，老系统 L7 无下限同款，2026-10-16 F-2/F-3 拍板时已确认）。

### 5.2 暂不修：F-6 整数除法分摊不闭合

**事实（亲验）**：唯一的取整点在 [`MerchantDailyIncomeNotifyScheduler.java:350`](../services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantDailyIncomeNotifyScheduler.java) `rentDaily += d.getRentCents() / daysInMonth;`（流量费自 V380 起本来就是**分/天**，不参与除法）。
**量级上界**：单柜每月少摊 `rentCents mod daysInMonth ≤ daysInMonth-1` 分 ⇒ 31 天月 **≤ 0.30 元/柜/月**；商户级上界 = 计费柜数 × 0.30 元（10 台 ⇒ ≤ 3.00 元/月）。方向是**少收**（我方不利、商户有利），不是多收。
**为什么不修**：两种正确修法都比现状差。
- (a) **月末清算行**（把余数在月末一次补上，可复用 CB-020「尾差归最大行」）：清算额按「当月已过天数」算，而 F-4 的运营台「按日期重跑日扣」是 **exists-then-skip** 语义 ⇒ 次月初补跑某漏扣日时，当月实扣会**超过**合同月额（超收），清算行不会回溯收缩。要修得对，必须把「整月」当成一次可重算的事务 ⇒ 与现行「逐日幂等、漏扣不自动补、失败靠人工重跑」的运维模型正面冲突。
- (b) **逐日轮存余数**（把 `rentCents mod days` 分摊到前 k 天）：账面闭合，但每天扣款额不再等于「月额/天数」⇒ 商户端看到的「今天扣多少」与台账 `remark` 文案「周期费用日扣(租金月额/N天 + 流量费分/天)」自相矛盾，且历史行不可按配置重算（重跑要先还原 k）。
**接受的偏差（写清楚，别当已解决）**：日台账逐日累加 **< 合同月额**（差额=余数）。财务对账若按「柜数 × 月额」核必然对不平 ⇒ **对账口径以台账/流水实际发生额为准**（与 §5.1 F-5 同一条纪律、CB-041 结论一致）。若将来把租金改成「按日精确计费」或引入月账单出账，这条一并处理，届时须回来改本行。

### 5.3 补审：🔴3 押金 `runMonthlyRefunds`（V360/V365 链）负向测试

原状：该链**零用例**（自报「先扣台账再入钱包 + 末月退余额」无测可验）。本轮补 6 个回归用例（clock 固定 today=2026-10-12），`DeviceRentalServiceTest` 28/28 绿：
`runMonthlyRefunds_lastMonthRefundsRemainingBalance_notFixedUnit`（50000/30000 档、余额 20000 ⇒ 退**余额**、refId `1|2`、不再写 DEDUCT、状态 SETTLED）、
`_lastMonthWithReward_creditsRewardOnce`（`createdAt=2026-10-01` 使 monthFromStart==threshold==1 ⇒ 发期满奖励且 refId `1|REWARD` 幂等）、
`_middleMonthRefundsUnitAndKeepsHeld`（refId `1|3`、`never()).updateById` ⇒ 月中不置终态）、
`_beforeStartMonth_nothingMoves`（起始月前不动账）、
`_walletAlreadyCredited_restoresLedgerAndCountsNothing`（钱包已被入账 ⇒ 只回补台账、计数 0）、
`_ledgerGuardFails_doesNotCreditWallet`（台账守卫失败 ⇒ **不注入钱包**，即「钱与账必须同向」）。
另补 `settle_defaultZeroFee_returnsBalanceInFull` 钉住 V383 后的默认口径（不登记安装费 ⇒ 全额返还）。

### 5.4 补审：🔴4 三任务七处同步 + 一处勘误

**逐处亲验**（key → 七处）：`merchant-daily-income-notify` / `machine-payee-bill` / `deposit-monthly-refund` 在
①[XxlJobManagedTasks.java:70-72](../services/trade-service/src/main/java/com/aicabinet/trade/service/XxlJobManagedTasks.java) `KEYS`、
②[ScheduledTaskXxlJobHandler.java:49-61](../services/trade-service/src/main/java/com/aicabinet/trade/service/ScheduledTaskXxlJobHandler.java) 具名 `@XxlJob` handler、
③④ [ScheduleZones.java:64-66](../services/trade-service/src/main/java/com/aicabinet/trade/support/ScheduleZones.java) `XXL_CRON_BY_TASK` 与 `:119-121` `MAX_SILENCE_BY_TASK`、
⑤[ScheduledTaskRegistry.java:155,158,161](../services/trade-service/src/main/java/com/aicabinet/trade/service/ScheduledTaskRegistry.java) `register(...)`、
⑥迁移登记行 V352（每日 00:20）/V356（每月 1 日 01:10）/V360（每月 1 日 01:30，V376 改 `enabled=FALSE`）、
⑦[seed_aicabinet_jobs.sql:75,78,81](../infra/xxl-job/seed_aicabinet_jobs.sql) `merchantDailyIncomeNotifyJob`/`machinePayeeBillJob`/`depositMonthlyRefundJob`
⇒ **七处齐全**。共享库实读现状：`merchant-daily-income-notify enabled=t`、`machine-payee-bill enabled=t`、`deposit-monthly-refund enabled=f`（与 V376 拍板一致，`last_run_at` 全空=dev 库未跑过，正常）。
**门禁实测**：`check-xxl-job-wiring` OK（托管 37 ↔ handler 37 ↔ 种子 37 ↔ cron 逐条一致 ↔ 阈值 37）、`check-scheduled-task-seed` OK（注册表 39 ↔ 种子行 41 对齐、`tryBegin` 调用点 39 全部已登记、无豁免）⇒ 两条 EXIT=0。
**🔴 勘误（拍板简报 §12 第 3 行）**：`AUDIT_DECISION_BRIEF_2026-10-16.md` §12 第 3 行原写「🔴4 属调度注册面（`check-scheduled-task-seed` **只校种子齐，不校七处同步**）」——**不准确**：七处的分工明明白白写在 [`XxlJobManagedTasks.java:44-45`](../services/trade-service/src/main/java/com/aicabinet/trade/service/XxlJobManagedTasks.java)（①~④⑦ 由 `check-xxl-job-wiring` 校验，⑤⑥ 由 `check-scheduled-task-seed` 校验），本轮两条门禁实跑亦为绿。⇒ 「七处同步」**是有门禁的**，简报该行已补注更正（2026-10-17）；🔴4 的真实剩余风险不在门禁缺失，而在「门禁只保证**已登记任务之间**一致，某个 key 若一开始就漏登记（四处皆无）它检不出」——那由 `tryBegin` 调用点必须已登记这条规则兜住（本轮实测该规则生效、无豁免）。

### 5.5 全量回归（2026-10-17 实跑）

`mvn -B -pl services/trade-service -am -DskipITs test` ⇒ **Tests run: 2042, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS**。
覆盖本轮三处语义变更（`chargeDailyPeriodFees` 返回值、`sendDailyIncomeNotify` 返回 `NotifyOutcome`、线长冲回基数）。
⚠️ 过程留痕：同一命令的前一次后台跑曾报「6 failures」，核对 surefire XML 发现那份报告 mtime 早于改动（`SystemConfigRecognitionModeTest`，属 vision/DC-27 面且非本次编辑）⇒ 陈旧报告（教训「surefire 报告可能是旧的」），以本次 2042/0 为准。

