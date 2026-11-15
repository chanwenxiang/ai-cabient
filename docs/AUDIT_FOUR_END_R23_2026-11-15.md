# 四端全量审计 R23（独立轮）— 2026-11-15

> **锚点**：`fdf5f3e4`（`dev` == `origin/dev`；工作树干净，仅他人未跟踪报告）。
> **独立性声明**：本报告**未阅读** `docs/module-audit/R23/*` 与 `docs/AUDIT_R23_INDEPENDENT_2026-11-15.md`，全部结论在当前 HEAD 源码 + 只读 SQL 上重新取证，用于与其他两轮 R23 相互印证。
> **交叉复核**：事后对两份 R23 报告的逐条复核见 [AUDIT_R23_CROSSCHECK_2026-11-15.md](AUDIT_R23_CROSSCHECK_2026-11-15.md)；其中 R23Q-23 为交叉复核阶段新发现。
> **方法**：6 条独立轴并行 —— 资金/结算全链（主审）、权限/越权、并发/幂等/事务、时间语义/调度、前端契约、将邑/设备链路。子代理结论**逐条回读源码复核**，误报单列 §5。
> **证据分级**：✅ 代码 + 运行库数据双证；◐ 代码路径成立、运行态未触发；○ 代码推断，需运行期/真机证据。
> **范围**：`services/trade-service`、`services/jiangyi-gateway`、`clients/admin-vue`、`packages/shared-*`、`infra/`；未做 UI 真机走查（本轮不声称）。

> 🔴 **2026-11-15 二次复核勘误**：R23Q-06（月结期初）与 R23Q-07（退款并发双记）**由 P1 降为 P2**。理由：前者仅「上期未关账」时触发（`prevSheetCounted` 命中即短路），后者需同一 `refund_id` 并发重投（微信串行重投会被外层查重命中）。缺陷真实，但不满足 P1。**本报告 P1 实为 5 条（R23Q-01…05），P2 实为 12 条**；下表为发现时原判，保留以便追溯。另：§2 P1-08 的「15 个运营写端点」应为 **17 个**（22 个漏枚举 = Admin* 6 + 非 Admin 16）。

## 1. 总览

| 级别 | 条数 | 编号 |
|---|---|---|
| P1 | 7 → **5**（复核降级） | R23Q-01…07；R23Q-06/07 降 P2 |
| P2 | 10 → **12**（含降级 2 条） | R23Q-08…16、R23Q-23、（R23Q-06/07） |
| P3 / 观察 | 6 | R23Q-17…22 |
| 复核后撤回/降级 | 3 | §5 |

一句话结论：**资金写路径的「点状修复」已很密，剩余高风险集中在三类系统性缺口**——① 运营端「功能权限通过、数据范围未过」（设备/定价/视频/财务 4 处同族）；② 月结/月账单的**账期与费用源**口径分叉；③ 失败/半失败路径的**幂等与补偿**（提现双重解冻、退款回调竞态、分账收尾中断）。

---

## 2. P1

### R23Q-01 提现单「打款失败已解冻」后，运营点「取消」会**再解冻一次**
**证据等级：◐**（代码路径成立；当前 dev 库无 FAILED 单，未触发）
**位置**
- `services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantWithdrawService.java:728-732`（打款失败：`status=FAILED` + `releaseFrozen`）
- 同文件 `:465-477`（`cancelFailedLocked`：仅校验 `status=FAILED`，再次 `releaseFrozen`）
- 镜像：`LineWithdrawService.java:489-492` 与 `:250-260`
- 原语无幂等：`service/wallet/WalletEngine.java:323-336`（`releaseFrozen` 只校验 `frozen >= amount`，不查 ref 流水）
- DB 兜底被显式排除：`V379__wallet_ledger_unique_ref.sql:87` 的 partial unique index 条件含 `ref_type <> 'WITHDRAW'`

**路径**：打款失败 → `finalizePayout` 解冻 A（100）→ 运营台仍见 `FAILED`，点「取消解冻」（`MerchantWithdrawView.vue:1383` 可直达）→ 再 `releaseFrozen` 100。
- 若该商户/线长**无其他在途单**：第二次直接 412「冻结金额不足」，取消功能对该单永久不可用；
- 若另有在途单 B（冻结 100）：A 的第二次解冻吃掉的是 B 的冻结额 → B 渠道打款成功后 `consumeFrozenSplit` 412 回滚 → **钱已出账、本地余额未扣、单卡 PAYING**，形成资损。
- `failStalePayingWithdraws`（`:791-796`，MOCK 超时自动 FAILED+解冻）产出同样的「已解冻的 FAILED 单」，同样可被取消二次解冻。

**最小修法**：`releaseFrozen` 增加 ref 级幂等（写 `WITHDRAW_RELEASE:<requestId>` 并先查重），或 `cancelFailed` 对「已释放过」的单改为只改状态不动物理冻结。两种都要覆盖商家/线长两条镜像链。

---

### R23Q-02 交易视频播放地址**无对象级授权**：任一运营可遍历 `id` 看任意柜机录像
**证据等级：◐**
**位置**：`api/AdminJiangyiController.java:128-133`（仅 `@RequiresPermissions("ops:device:video")`）；`service/JiangyiOrderVideoService.java:194-200`（`playUrls(long id)` 方法签名**不含 operatorId**，直接 `selectById` 后签名返回）。
**为何是 P1**
- `V339__jiangyi_video_play_permission.sql:15-20` 把 `ops:device:video` 同时授予 **role 1（超管）与 role 2（运营人员）**；
- role 2 非 admin ⇒ 按 DC-14 `isGlobalScope=false`，`MerchantScopeService` 才是数据范围真源，但本方法完全绕开它；
- 视频桶私有、返回的是**可用预签名 URL**，拿到即可离线留存。行业团标《智能柜用户交易视频隐私保护规范》把交易视频列为单列受约束对象，DC-11 隐私约束在此形同虚设。
**最小修法**：`playUrls(long id, Long operatorId)`，取 row 后 `merchantScopeService.requireDeviceAccess(operatorId, row.getDeviceId())`；`playByOrderNo` 的商业入口同样补范围校验。

---

### R23Q-03 单柜改价 / 批量重置价格**无设备范围校验**
**证据等级：✅**（代码 + 权限种子）
**位置**
- `api/AdminDevicePricingController.java:46-55`（改价）、`:57-63`（`pricing/reset` 批量清覆盖价）
- `service/OpsDevicePricingService.java:101-104`（`requirePermission("ops:pricing:edit")` + `requireDevice()` 只查存在，`:212-216`）
- 对照正确范式：`service/OpsDeviceAdminService.java:511-514`（`updateDevice` 同时 `requireDeviceAccess`）
**为何是 P1**
- `V350__device_sku_price_enable_and_min_price.sql:29-33` 把 `ops:pricing:edit` 授予 **role 2（运营人员）**；
- 运营人员非全局 ⇒ 可对**范围外商户的柜机**改价（打到下限）或一键清空全部覆盖价，直接影响交易金额与商户分成；
- 同文件 `listPricing`（`:75-77`）也只校验 `ops:device:list`，可读他商户价格。
**最小修法**：两个写端点 + 读端点统一补 `merchantScopeService.requireDeviceAccess(operatorId, deviceId)`。

---

### R23Q-04 设备 HTTP 凭据签发/吊销**无设备范围校验**，可拿任意设备明文 secret
**证据等级：✅**
**位置**：`api/DeviceHttpCredentialController.java:46-53`（签发，`@RequiresPermissions("ops:device:edit")`）；`service/DeviceHttpCredentialService.java:54-58`（`issue` 只做 12 位格式 + 设备存在校验）；MQTT 同构 `DeviceMqttCredentialService.java:57`。
**为何是 P1**
- `V118__tighten_demo_role_perms.sql:21` 把 `ops:device:edit` 授予 **operator 角色**（非 admin）；
- 响应体直接回**明文 secret**（`:58-61`）；持 secret 即以该设备身份调用设备面内部端点（OTA check / screen-content / ad-play 等），跨商户冒充设备；
- 与 `updateDevice` 的 `requireDeviceAccess` 口径不一致，属同族漏闸。
**最小修法**：`issue`/`revoke`/`status` 增加 `Long operatorId` 并 `requireDeviceAccess`；`operatorId` 目前只用于审计落库。

---

### R23Q-05 「日毛利锁」固化：**只读权限可写全局日键**，零范围账号写入全 0
**证据等级：✅**
**位置**
- `api/OpsGapFeaturesController.java:134-141`：`@RequiresPermissions("ops:finance:view")`
- `service/FundBillService.java:409-418`：服务层只 `requireAnyPermission(finance:view, fund:list)`（均为读码）
- `:424-431` + `:565-575`：`deviceIds = allowedDeviceIds(operatorId)`，**空集直接返回 0**，随后 `:433-442` 以 `bizDate` 为 `@TableId` **upsert 全局行**（`FinanceMarginDailyLock.java:17-19`）
**路径**：`finance` 角色（`V118:55-60` 授 `ops:finance:view`）或任何零绑定运营账号，`POST /finance/margin-locks/solidify?bizDate=昨日` → 该日全平台 revenue/cogs/margin 被 **0 或该账号子集覆盖**，且后写覆盖先写（无版本/无条件更新）。
**最小修法**：写操作单独权限点（如 `ops:finance:marginsolidify`）+ 仅全局范围可固化；scoped 账号按设备范围写入带 device 维度的行，或直接 403。

---

### R23Q-06 月结「期初盘点」按文档口径**取错一期**（prevLabel vs periodLabel）
**证据等级：◐**（代码与自述注释直接矛盾；无单测钉住）
**位置**：`service/WarehouseMonthlyCloseService.java:153-154`
```java
Map<String, Integer> prevFlowStocktake = prevFromSheet
        ? Map.of() : flowStocktakeBySku(prevLabel, wh);
```
而同文件 `:434-439` 的导入口径自述：
> 导入口径：N 期末盘点所属月份填**下一账期**——所以本期期末盘点在 `nextLabel`，
> 本期期初（=上期末盘点）在 `periodLabel`。

期末侧 `:160-162` 用的正是 `nextLabel`（与注释一致）；期初侧却用 `prevLabel`（=上上期期末盘点）。
**影响**：`expected`、`gap`、差异金额、索赔台账全部随错位盘点值漂移；且 `openingFromPeriod`（`:155`）会被误标成 `PERIOD_CLOSE`，掩盖「期初实为账面值」的告警。
**最小修法**：改为 `flowStocktakeBySku(periodLabel, wh)`；补单测断言「期初 label 与期末 label 恰好相差 1 期」。

---

### R23Q-07 微信「商户平台手工退款」同步回调：**去重与累加分属两个事务**，并发重投双记 `refunded_cents`
**证据等级：◐**
**位置**：`service/PaymentService.java:477-505`
```java
if (paymentOperationRepository.findByIdempotencyKey(idemKey).isPresent()) return;
...
balanceLedgerService.change(userId, -refundCents, "RECHARGE_REFUND", orderId, idemKey, ...);
self.finalizeChannelPartialRefund(orderId, refundCents);   // 内部 already + refundCents，无条件累加
```
- 账本侧幂等（`BalanceLedgerService.java:84-90`：同键返回旧流水、不抛错）；
- 单据侧 `PaymentService.java:867-880`：`already = order.getRefundedCents(); setRefundedCents(already + refundCents)`。
**交错**：同一 `refund_id` 两次投递，T2 在 T1 提交前通过首查 → 阻塞在用户余额锁 → 拿到锁后账本按幂等返回 → 仍进 `finalizeChannelPartialRefund` → 再累加一次。
**后果**：本地已退额 > 渠道实退；后续 `prepareChannelPartialRefund` 的「可退 = 面额 − 已退」被虚高值占掉（合法退款被拒），分账/对账基数同步失真。不产生双退（渠道侧幂等），但会产生**少退 + 账实不符**。
**最小修法**：`finalizeChannelPartialRefund` 改为条件累加（`UPDATE ... SET refunded=refunded+? WHERE refunded < amount`，影响行数=0 即视为重放），或把「查重 + 入账 + 累加」收进同一事务并对同一 `refund_id` 加唯一约束。

---

## 3. P2

### R23Q-08 一致性修复「退多收」**不回算分账**（商户被多分）
**位置**：`service/DataConsistencyService.java:1617-1632`（单行对齐后退多收）、`:1704-1725`（`PAYMENT_AMOUNT` 修复），均直接 `orderPaymentService.refundOrder(order, over, ...)`。
**证明**：`refundOrder` 只累加 `refundedCents`（`OrderPaymentService.java:885` / `:1086`），**不调用** `adjustSplitAfterPartialRefund`。`CommissionBaseCalc.netCents` 用 `冻结毛额 − refunded`（`CommissionBaseCalc.java:41-49`），而分账行/商户钱包仍停在修复前的毛额。订单状态保持 `PAID`，因此连 `checkRefundAmountConsistency`（只扫 `REFUNDED/PARTIAL_REFUNDED`，`DataConsistencyService.java:661-669`）都不会看它。
**修法**：退多收成功后按同一订单重算分账（`adjustSplitAfterPartialRefund(order,false)`），并把订单状态置 `PARTIAL_REFUNDED`；巡检新增「split.gross ≠ netCents」判据。

### R23Q-09 分账「毛额 vs 净额」无巡检，运行库已有**1 笔真实漂移**
**证据等级：✅**
- `DataConsistencyService.java:935-956` 只查 `platform+merchant=gross`；`:965-988` 只查「已付缺分账」。**没有**任何检查比较 `split.gross_cents` 与 `CommissionBaseCalc.netCents(order)`。
- dev 库实查（只读）：`order 1793669053437918137747` → `status=PAID`、`refunded_cents=293`、`paid_amount_cents=586`、split `gross=586/merchant=586`、`wallet_ledger` 已入账 586。按净额口径商户应收 293，**多记 293 分**。
- 该单操作链：`CHARGE 586` → `ADJUST_CHARGE 293` → `REFUND 293:L:<指纹>`，退款完成但本地收尾未完成（状态仍 PAID）。
**结论**：`PAID + refunded_cents>0` 是「部分退款半收尾」的可靠指纹，当前三处巡检都不覆盖；应把它做成一条独立检查 + 半自动补收尾入口。

### R23Q-10 商户月账单的退款按**订单创建日**分日，跨月退款进不了当月
**位置**：`services/trade-service/src/main/resources/mapper/CabinetOrderLineMapper.xml:244-262`（`GROUP BY to_char(created_at ...)`，`WHERE created_at`），`MerchantFinanceService.java:274-277`。
**后果**：6 月下单、7 月退款 → 6 月账单（重算时）利润变小、7 月账单看不到这笔退款；关账后重算会改历史月。订单级 `refunded_cents` 快照也无法按退款日期分摊（一笔单多次退款只留累计值）。
**修法**：改按 `refunded_at`/退款流水（`payment_operation REFUND COMPLETED.created_at`）分日；多笔退款时按流水逐笔。

### R23Q-11 商户月账单的费用源已过期：流量费恒 0、租金完全不计
**位置**：`MerchantFinanceService.java:279-289`（读 `device_data_fee_bill` 月结表）；`:325-328`（利润 = 销售 − 退款 − 成本 − 流量费）。
**矛盾**：`OpsFeeBillJob.java:51-53` 与 `SystemConfigService.java:985-987` 已把 `feebill.auto_generate.enabled` 默认置 `false`（改日扣），该表不再产生新行；日扣实际写 `merchant_fee_ledger` 的 `REN`/`DATA`（`MerchantDailyIncomeNotifyScheduler.java:485-489`），而本账单**完全不读**该表。
**后果**：商户月账单利润虚高（少扣每日租金 + 每日流量费），与钱包实扣结果对不上，必然引发商户对账争议。

### R23Q-12 场地租金出账**不看合同起止日**，会提前/过期继续出账
**位置**：`service/SiteRentBillService.java:99-114`（只过滤 `ACTIVE/EXPIRING`）；`:175-195`（`doGenerateForContract` 只对 **分账规则** 做 `isEffectiveInMonth`，从不读 `SiteContract.startDate/endDate`）。
**后果**：对外付款——未生效月份提前出账、合同到期后每月继续出账；发票/凭证已按「已付」留痕，撤销成本高。
**修法**：`monthEnd < startDate` 或 `monthStart > endDate` 直接跳过（必要时按天折算）。

### R23Q-13 `chargeOrder` 在**数据库事务内**发起渠道 HTTP，且同时持会话行锁与库存行锁
**位置**：`service/OrderPaymentService.java:126-152`（`@Transactional`）→ `:154-176` → `:283-318`（`submit.get()` 调支付分/微信/支付宝，超时 10s/30s）；上层 `SettlementOrderFinalizeService.java:94-100` 已 `findByIdForUpdate(session)` + `deductForOrder`。
**风险**：单次结算按住连接并锁住同设备库存行最长 ~30s；Redis 订单锁 TTL 60s 无续租，HTTP 抖动叠加 DB 抖动即失去互斥，二次进入只靠唯一键兜底并向上抛 500。
**注**：退款链已显式改为「事务外 HTTP」（`refundOrder` 注释），扣款链未对齐——属**同族不对称**。

### R23Q-14 支付分订单态通知「读-判-写」无锁，DONE 可被迟到的 EXPIRED 覆盖并误记欠款
**位置**：`service/PayScoreOrderNotifyService.java:81-131`（`selectList` 无 `FOR UPDATE`，`:114-115` 无条件 `updateById`）。
**交错**：DONE 与 EXPIRED 同时读到 `USER_PAYING/PENDING` → 双双通过 R20-P2-5 早退判据 → DONE 置 PAID 后，EXPIRED 把 `order_state` 覆写为 EXPIRED 并 `recordUnpaidAndEscalate`（`UnpaidDunningService.java:81` 无幂等）。
**后果**：钱已到账却记欠款、升级催缴、可能阻断下次开门；`order_state` 回退使对账误判。
**修法**：`SELECT ... FOR UPDATE` 或条件更新 `WHERE order_state <> 'DONE'`；欠款升级按订单幂等。

### R23Q-15 分账锁在事务内、**提交前解锁**；唯一键冲突后的「读回已有行」在 PG 里必失败
**位置**：`service/RevenueSplitService.java:80-91`（`@Transactional` 内 `runWithOrderSplitLock`）、`:711-724`（`finally unlock` 早于提交）、`:154-159`（`catch (DuplicateKeyException)` 后同事务 `findByOrderIdForUpdate`）。
**机理**：T1 解锁未提交 → T2 读不到行 → insert 被 `uk_order_revenue_split_order_id`（`V207:31-32`）挡住 → `catch` 里再查询，事务已 aborted（25P02）→ 500。
**后果**：并发分账入账 500；支付分 DONE 回调路径会放大为渠道重投。资金不双记（幂等键/唯一键兜底），但可用性受损。
**修法**：锁移到事务外（对齐 `BalanceLedgerService` 的「锁外包事务」）；冲突分支用 `REQUIRES_NEW` 重读。

### R23Q-16 温控下发 / 将邑关门·重启**无设备范围**（跨商户运维操作）
**位置**：`service/DeviceTempPlanService.java:134-139`（`applyNow` 仅 `ops:device:edit`）；`api/AdminJiangyiController.java:313-318`、`:331-336`；`service/JiangyiDeviceOpsService.java:90-107`（`closeDoor`/`reboot` 仅 `ensureMaintenanceCommandAllowed`，无操作员范围校验）。
**后果**：受限运营可对范围外柜机下发关门/重启/温控计划（营业中断），非资损但属跨租户越权。`ops:device:edit` 已授 operator 角色（V118:21）。
**修法**：同 R23Q-03，统一补 `requireDeviceAccess`。

---

### R23Q-23 「流量费月结开关」同时门控了**场地租金**出账，默认关闭 ⇒ 场地租金不再自动出账
**证据等级：✅**（代码 + 默认值；交叉复核新发现，两份 R23 报告均未覆盖）
**位置**
- `service/OpsFeeBillJob.java:40-55`：`TASK_KEY=ops-fee-bill-monthly`；开关 `feebill.auto_generate.enabled=false` ⇒ 直接 `finish(SKIPPED)` 并 return；
- 同方法 `:60` `rentBillService.autoGenerate(month)` —— **场地租金**（付给房东）月度账单的**唯一自动入口**（另一入口 `generateForAllActive` 需运营手动点）；
- `service/SystemConfigService.java:985-986`：默认 `"false"`，描述却只写「**流量费**月度自动出账开关：日扣模式下默认停用（避免重复计费）」。
**后果**：2026-10-14 流量费改日扣后，这个开关默认关闭；被它连带关掉的还有场地租金月结。`scheduled_task.last_run_at` 会是 `SKIPPED`（R19-24 已保证不是静默），但**账单本身不会生成**——对外付款缺口的发现依赖运营想起手动点。
**最小修法（需先拍板）**：若场地租金仍应自动出账，把 `:60` 从该开关分支拆出（或新开关 `site.rent.auto_generate.enabled`）；若本就该人工出账，把 `:60` 从被禁路径移除并改文案。

---

## 4. P3 / 观察

| 编号 | 位置 | 现象 | 建议 |
|---|---|---|---|
| R23Q-17 | `PaymentService.java:555-557`；`AlipayNotifyService.java:20,55-66`；`WeChatPayNotifyService.java:120-131` | 回调「先占位、后入账」：入账因余额锁 409 失败时占位已写，重投被 replay 拒；**有自愈**（`autoCancelExpiredPending` 每 5min 先查单同步，`PaymentService.java:648-677`），用户最长约 35min 才到账，且 replay 分支只有 warn 无告警 | 占位改「入账成功后写」或失败时删占位；replay 分支补一条 LOW 告警 |
| R23Q-18 | `DeviceRentalService.java:154-157,187-193` | 租机押金登记/扣款无 `requireDeviceAccess`，扣的是目标柜机商户的钱包 | **当前仅 admin（role 1）持 `ops:rental:manage`（V342:87-90）⇒ 今日不可利用**；建议补闸防未来扩权 |
| R23Q-19 | `MerchantDailyIncomeNotifyScheduler.java:469` | 月租按 `rentCents / daysInMonth` 逐日整数除截断，每台每月少收 0~29 分 | 按累计应扣−已扣补差，月末补记尾差 |
| R23Q-20 | `MerchantSettlementBillService.java:95-100` vs `MerchantFinanceService.java:267` | 结算单按 `split.created_at` 归月、月账单按 `order.created_at` 归月；PENDING 单跨月补缴时两张表永远差一笔 | 归月统一按订单支付完成时间，落 `biz_date` 快照 |
| R23Q-21 | `jiangyi/api/DeviceReportController.java:321-342` | `response != null` 即视为「已转发」并注销 CLOSE watchdog；`accepted=false`（TOO_EARLY/ALREADY_HANDLED）也算成功 | 只在 `accepted=true` 或 `ALREADY_HANDLED` 时注销；TOO_EARLY 保留 watchdog（当前有购物超时→DISPUTED 兜底，故非 P1） |
| R23Q-22 | `RevenueSplitService.java:711-724` | `runWithOrderSplitLock` 把一切非 `ResponseStatusException` 包成 500，`confirmLedgerOnly` 的 `IllegalStateException`（「仅 LEDGER_ONLY 可确认」）也变 500 而非 409 | 状态不合法场景显式抛 409 |

---

## 5. 复核后撤回 / 降级（子代理初报 → 复核结论）

1. **「回调重投 24h 不到账」→ 降为 P3（R23Q-17）**：复核发现充值单有 5 分钟级 `autoCancelExpiredPending → syncPendingOrder` 自愈通道（`PaymentService.java:648-677`、`RechargeOrderScheduler.java:24-34`），最坏约 30–35 分钟而非 24 小时，且无资损。
2. **「租机押金可跨商户扣款」→ 降为 P3（R23Q-18）**：`ops:rental:manage` 当前仅授予 admin（`V342:87-90`），受限账号不可达；保留为「扩权前必须补闸」的防守项。
3. **「分账冲正/部分增额幂等键 `splitId:g<newMerchantCents>` 会撞键」→ 撤回**：净额 `gross−refunded` 单调不增（`CommissionBaseCalc.java:47-49`），同一 `newMerchantCents` 不可二次到达；不构成缺陷。

---

## 6. 已核对、未发现问题的面（避免重复报告）

- **内部 DTO 契约**：`TradeInternalClient.RecognitionReportRequest` / `DoorStateRequest` / `SignalRequest` / `OrderFile…` 与 `JiangyiInternalController` 的 record 字段逐字一致（gateway `:345-350` ↔ trade `:448-467`）。
- **钱包写原语**：`WalletEngine` 的锁 + 行锁 + `refType/refId` 幂等、`consumeFrozenSplit` 两行流水与 `balance_after/frozen_after` 链式自洽（`WALLET_LEDGER_CHAIN` 亦覆盖）。
- **退款链路预估/写侧**：`estimatePartialRefundCents` 与 `preparePartialRefund` 共用 `proportionalCouponDiscount` / `scaleDiscountForPartialRefund`，比例摊口径一致（`SettlementPartialRefundService.java:396-411` ↔ `SettlementOrderSupport.java:248-263`）。
- **消费者自助退款限额**：柜机策略、时限、单笔上限、日次数四闸齐全（`RefundPolicyService.java:74-106`），日界用 `Asia/Shanghai`。
- **edge-results 入站**：会话-设备绑定、confidence 值域（含 NaN）、quantity 上界、modelVersion 必填均在入口 fail-closed（`VisionResultIngestService.java:70-111,212-262`）。
- **权限码前后端**：admin-vue 独有的 30 个 `*:export/*:import` 均落在前端本页 CSV（`CrudTable.vue:1158-1169`），不构成服务端缺口。
- **金额单位前端**：`/100` 命中集中在 bps 百分比与「单价=金额/数量」，未见「Cents 当元」或双重换算；`check:money-yuan-prefix` 门禁仍在（本轮未新增前端缺陷）。

---

## 7. 需拍板 / 需运行期证据

| 项 | 缺什么 | 判据 |
|---|---|---|
| 月结期初口径（R23Q-06） | 导入模板对 `WATEHOUSE_STOCKTAKE.period_label` 的真实填写约定 | 取一份真实导入记录：本期期末盘的 label 是否 = nextLabel；若是，代码应为 periodLabel |
| 分账毛额漂移（R23Q-09） | 生产/测试库是否存在 `PAID + refunded>0` 或 `split.gross ≠ net` | 只读 SQL（附录 A.2）；为 0 则仅补巡检，非 0 需人工平账 |
| 提现双重解冻（R23Q-01） | 真实 FAILED 单是否已被运营「取消」过 | 统计 `merchant/line_withdraw_request status=CANCELLED` 且存在 ≥2 条 `WITHDRAW_RELEASE` 同 ref 的流水 |
| 视频越权（R23Q-02） | 是否需要按「设备范围」还是「商户范围」授权 | 产品拍板范围模型；当前代码两者都无 |

---

## 8. 建议修复批次（每批可独立验收）

1. **鉴权同族批（R23Q-02/03/04/16，+18 防守）**：统一在 service 层补 `merchantScopeService.requireDeviceAccess`；验收=受限运营账号对范围外 deviceId 全部 403（含读面）。
2. **提现解冻幂等批（R23Q-01）**：`releaseFrozen` 加 ref 幂等 + 商家/线长双镜像；验收=FAILED→取消→再取消，第二次 409/无害，且另一在途单冻结额不变。
3. **财务写权限批（R23Q-05）**：新权限点 + 全局范围闸；验收=finance 角色调用 403，超管固化结果=全平台口径。
4. **账期/费用口径批（R23Q-06/10/11/12）**：一月一改、逐条附「改前/改后同数据对比」；验收=用同一月份重算前后差异表。
5. **失败路径补偿批（R23Q-07/08/09/14）**：条件累加/条件更新 + 巡检新增三判据（`PAID+refunded>0`、`split.gross≠net`、欠款升级幂等）；验收=并发回放用例 + 巡检在残余数据上能红。
6. **事务边界批（R23Q-13/15/22）**：扣款链对齐退款链的「事务外 HTTP」，锁外包事务；验收=慢渠道注入下连接池/锁 TTL 压测。

---

## 附录 A：取证命令（只读）

### A.1 提现/钱包
```sql
select status, count(*) from merchant_withdraw_request group by status;
select entry_type, ref_type, count(*) from wallet_ledger
 where domain='MERCHANT' and ref_type='WITHDRAW' group by 1,2;
```
结果：dev 库仅 2 笔 PAID，无 FAILED/CANCELLED（故 R23Q-01 只能 ◐）。

### A.2 分账漂移 / 半收尾
```sql
select count(*) from cabinet_order where status='PAID' and coalesce(refunded_cents,0)>0;      -- 1
select s.order_id, s.status, s.gross_cents, o.status, o.paid_amount_cents, o.refunded_cents
  from order_revenue_split s join cabinet_order o on o.order_id=s.order_id
 where upper(coalesce(s.status,''))<>'VOIDED'
   and s.gross_cents <> greatest(0, coalesce(o.paid_amount_cents,0)-coalesce(o.refunded_cents,0));
```
结果：3 行命中，其中 2 行 `paid_amount_cents=0` 属 `gross=total` 回退（非漂移），1 行为真实漂移（R23Q-09）。

### A.3 代码侧定位
- 权限种子：`V118:21,55-60`、`V339:15-20`、`V342:87-90`、`V350:29-33`
- 唯一键：`V207:31-32`、`V379:87`（partial index 排除 `WITHDRAW`）
