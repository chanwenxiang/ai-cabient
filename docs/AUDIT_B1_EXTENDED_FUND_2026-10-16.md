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

### F-6 🟡 整数除法分摊不闭合
`rent/days + data/days` 逐设备整除，每设备每月最多少摊 `days-1` 分，无月末清算行。

### F-7 🟠 冲回「递增已提交、钱没扣」的半状态（V361 独有）
`CommissionClawbackService:145/:205` 事务内先 `upsertClawback` 递增累计，再 `debitIfAbsentAllowNegative`。
后者返回 `false`（同 refId 已存在）**不触发回滚** ⇒ 已冲回额虚增而钱没扣，
下次 `net = gross - already` 把差额吃掉 ⇒ 永久少冲。头注「事务化避免半状态」只覆盖了抛异常路径。
处置：`false` 时抛异常回滚递增（线长/补货员两处对称改，`CommissionClawbackService:145/:205`）；
单测 `debitSkippedRollsBackClawbackIncrement` 钉住「跳过负扣 ⇒ 事件数 0 且事务 rollback」，
随 `CommissionClawbackServiceTest` 7/7 绿。

### F-8 🟡 线长侧按「当前费率」重算历史差额
`bps`/`fixed` 取实时配置（代码注释自标「费率事后变更是已接受的近似」）⇒ 费率上调后的窗内退款会按新费率多冲回，员工侧多扣。量化：`Δbps × 窗内 GMV 差额`。

## 3. 对 `93bf2697` 自报 6 项的复核

| 自报 | 复核结论 |
|---|---|
| 🔴1 台账死路 → 新增 Controller | **未解**：补了后端，三端零入口（F-2） |
| 🔴2 DAILY_FEE 真幂等 | 语义成立（`debitIfAbsentAllowNegative` + 可负），但**库级兜底缺失**（F-1） |
| 🔴3 押金月退超退 | 本轮未审（属 V360，已由 V376 关到默认关） |
| 🔴4 三任务七处同步 | 本轮未审（调度注册面，另列） |
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
