# 四端审计 R20 报告 · 业务规则与资金全路径（2026-11-12）

> **性质**：全量，但**换轴**——R19 打的是授权矩阵/持久层/校验/定时任务/前端/基建；本轮打「业务规则与钱的流向」。
> **六轴**：A 交易状态机 · B 资金全路径 · C 营销（券/活动/会员/积分）· D 跨端一致性 · E 安全纵深 · F 性能与容量。
> **执行方式**：A/B/C/F 四路只读子代理；D/E 因额度限制由主审用 grep 自查（**覆盖面小于子代理，已在各节标注边界**）。
> **标记**：✅=主审亲验（读码/实跑）｜◐=子代理提出、主审未逐条复验｜⚠=与 R19 重叠或需拍板。
> **复验回执（同日补做）**：P1×10 与 P2×18 已**逐条亲验**，结果见 §八——P1 全部坐实；P2 中 1 条改判（R20-P2-9 疑不成立）、1 条降为待定（P2-12）、1 条措辞订正（P2-18 索引）。。
> **总账**：报出 **38 条 = P1×10 + P2×18 + P3×10**；另 6 条待定。
> **复验后有效数**：P1 **10/10 成立**；P2 **15 成立 + 1 部分成立（P2-13）+ 1 降待定（P2-12）+ 1 改判不成立（P2-9）= 18**；P3 已补验：**9/10 成立，1 条降为说明**（P3-2 的 `CANCELLED→DISPUTED` 是注释写明的刻意设计）。⇒ 可直接派修的为 **P1×10 + P2×15 + P3×9 = 34 条**（P2-13 需先补验 mapper 谓词）。

---

## 一、P1（10 条）

### R20-1 ✅ 渠道退款返回 ABNORMAL，本地仍记「已退」
- `OrderPaymentService.java:1145-1158` 解析出 `ABNORMAL` 并 `opsAlertDispatcher.send("WECHAT_REFUND_ABNORMAL")` 后 **把状态原样 return**；
  `:677-680` 无条件 `self.finalizeLiveOrderRefund(prep, refundStatus)`；
  `finalizeLiveOrderRefund(prep, String)`（`:846-863`）内**只有 `isCompleted(idemKey)` 幂等闸，没有任何按 refundStatus 的分支**，直接 `recordOperation(..., REFUND, ...)` 记 COMPLETED 并累加 `refundedCents`。
- 后果：钱没退到用户手里，但订单已 REFUNDED/部分退、分账已冲减、余额腿已扣；`WeChatRefundReconciler` 只扫 `refund:PROCESSING`，ABNORMAL 无人兜底。
- **修法**：仅 SUCCESS/PROCESSING 才 finalize；ABNORMAL → 记 `REFUND_FAILED` + 保留 INITIATED 痕迹 + 进人工队列。
- **判据**：`SELECT count(*) FROM payment_operation WHERE operation_type='REFUND' AND status='COMPLETED' AND coalesce(reason,'') LIKE '%ABNORMAL%'` 恒 0。

### R20-2 ✅ 支付分（免密）单未实收即分账/入商户钱包/发积分，取消不回滚
- `SettlementOrderFinalizeService.java:109-147`：`tryChargeSettledOrder` 里 `tryCompleteWeChatPayScore` 成功即把订单置 **PENDING + WECHAT** 并返回 true（无真实入账），主流程随后照样 `freezePaidAmount`→`recordSplit`→`onOrderPaid`。
- `UnpaidOrderService.cancelSingleExpiredOrder:341-373` 只 `setStatus(CANCELLED)`，**无 voidSplit / 无积分追回**。
- 后果：LEDGER_ONLY 下商户钱包在用户一分钱没付时就入账并可提现；用户白得积分与消费统计。
- **修法**：分账/发积分/统计一律挪到「实收确认」（DONE 回调或补缴成功）之后；取消路径补 void+clawback。

### R20-3 ✅ 手动用券绕过「活动价禁券」
- `CouponService.java:334-361`（用户端 `POST /api/v2/coupons/use`）只拦 DISPUTED / 状态不可用 / 已用券 / 已核销其他券，**没有 `activityDiscountCents > 0` 闸**；
  而结算 `SettlementOrderFinalizeService.java:86-88` 与补扣 `UnpaidOrderService.java:464-469` 都拦。
- 利用：活动价单挂 PENDING → 手动用券 → 实付再减一张券额，可重复。
- **判据**：`SELECT count(*) FROM cabinet_order WHERE activity_discount_cents>0 AND coupon_id IS NOT NULL` 恒 0。

### R20-4 ✅ 网关支付客户端完全无超时，且在事务+行锁内同步调用
- `WeChatPayV3Client.java:42`、`AlipayOpenApiClient.java:33` 均 `RestClient.create()`（无 requestFactory、无 connect/response timeout）。
- `OrderPaymentService.java:126/147` 的 `chargeOrder` 是 `@Transactional`，内部持 Redis 锁 + `findByIdForUpdate` 行锁 → 网关一卡就是「行锁 + 1 个 Hikari 连接」被占满整个网络等待（Hikari 20 ⇒ 20 个并发卡点即打满全库）。
- **修法**：渠道 HTTP 移出事务（先落 INITIATED 痕迹再调，回调/定时 finalize）；客户端设 3s/10s。
- **判据**：`pg_stat_activity` 中 `state='idle in transaction'` 峰值为 0；`hikaricp_connections_active/max < 0.8`。

### R20-5 ✅ 积分过期「部分结转」不标记来源行 → 同一笔 EARN 被反复过期
- `PointsExpiryScheduler.java:140-171`：`actualExpire=min(available,Σ)`，但 `:165-169` 跳过 `pts > remaining` 的整条日志不写 `expired_at`。
- 例：EARN=100、当前余额 10 → 本轮只过期 10 且日志未标记；用户新赚 100 后下一轮再过期 100，**累计扣 110 > 应扣 100**。
- ⚠ 与 R19 P3#9 同一条：营销轴复算后**升级为 P1**（直接扣用户资产）。
- **修法**：按 `min(pts, remaining)` 部分标记；判据：单测「同一 EARN 行累计过期 ≤ 其 points」。

### R20-6 ◐ 争议改单缺订单状态前置：CANCELLED 可扣款、PARTIAL_REFUNDED 可重复收款
- `SettlementConfirmDisputeService.java:168` 只挡 REFUNDED；`:213` 仅当原状态为 DISPUTED 才改 PAID；
  `OrderPaymentService.java:916-935` 的差额扣款不校验订单状态；`SettlementOrderSupport.java:186` 全量重算 total。
- 后果：CANCELLED 单 CONFIRM ⇒ 扣到钱但单仍取消（收入消失）；已部分退的单 CONFIRM ⇒ delta≈已退额 ⇒ **重复收款**。
- **修法**：CONFIRM 白名单 `{PAID, PENDING, DISPUTED}` 且 `refundedCents=0`；finalize 必置 PAID。

### R20-7 ◐ 购物中（SHOPPING）可被运营直接免单取消，且不释放预授权
- `OpsSessionOrderQueryService.java:218-226` 只挡终态/识别态，SHOPPING 放行 → 置 CANCELLED；无 `releaseIfFrozen`（对照 `SessionService.java:366` 的正常路径），也不建争议单；
  而 FAILED 冻结清扫器只扫 FAILED（`SessionExpireService.java:160`）⇒ CANCELLED 的 FROZEN 悬挂无人清。
- 后果：用户已取货 → 免单 + 预授权永久冻结。
- **修法**：SHOPPING 强制转 DISPUTED（与既有 F2 口径一致）并释放 hold；清扫器覆盖 CANCELLED+FROZEN。

### R20-8 ◐ 支付分回调可把终态订单改回 PAID
- `PayScoreOrderNotifyService.java:109-123` 只判「已是 PAID 就跳过」，DONE 可把 REFUNDED/CANCELLED 改成 PAID；`DisputeService.java:372` 又只挡 REFUNDED ⇒ 改回后可二次退款。
- **修法**：状态单向机 + 终态收到 DONE 只记 HIGH 异常人工处置。

### R20-9 ◐ 分账回退单槽覆盖，多轮部分退后全额退会少回退
- `RevenueSplitService.java:276-283/354-360/380-383` 每轮覆盖 `wechat_pending_return_no/cents`；`WeChatProfitSharingService.retrySingleFailedReturn:350-370` 只提交最后一轮。
- 后果：100 元单两轮部分退（30/20）再全额退 ⇒ 渠道只回退 70，商户多留 30。
- **判据**：`Σ渠道回退额 = 初始 merchant_cents − 现 merchant_cents`。

### R20-10 ◐ MQTT 回调线程内同步转发门磁事件且无超时
- `MqttEventListener.java:345` + `TradeServiceClient.java:28,38-50`：Paho 回调线程内 3 次重试、无 requestFactory 超时 ⇒ trade 抖动即阻塞 MQTT 线程、消息不 ACK，全设备门磁积压。

---

## 二、P2（18 条）

| # | 轴 | 位置 | 问题（一句话） | 验证 |
|---|---|---|---|---|
| 1 | A | `ReplenishmentService.java:612-627` | 只挡 COMPLETED，CANCELLED 任务发运后被打回 IN_PROGRESS（重复履约、柜机重新冻结） | ◐ |
| 2 | A | `ReplenishmentTimeoutScheduler.java:82-105` | 用扫描快照直写 CANCELLED，与 completeTask 并发把 COMPLETED 覆盖 | ◐ |
| 3 | A | `DisputeService.java:259` | 争议/结案链无 `isRestockSession` 闸 ⇒ 补货会话可被建消费单并扣补货员余额 | ◐ |
| 4 | A | `PaymentService.java:1032-1039` | 充值 PENDING→CANCELLED 不查渠道；之后已付款回调在 `doCreditRecharge:1229` 抛 409，渠道无限重试、钱到账无余额 | ◐ |
| 5 | B | `PayScoreOrderNotifyService.java:74-91` | 事件无状态机，DONE 后迟到 USER_PAYING/EXPIRED 会倒退并误发催缴 | ◐ |
| 6 | B | `PaymentService.java:461-476`→`:797-811` | 微信退款通知重复投递时单据推进不幂等 ⇒ `refunded_cents` 累加两次 | ◐ |
| 7 | B | `ReconciliationService.java:328-339` | 单渠道对账的充值合计/退款合计均**无 channel 过滤** ⇒ 假差异可抵消真差异 | ◐ |
| 8 | B | `PaymentService.debitForRechargeRefund:1044-1058` | 整单退充值只看 status=PAID，不扣减已退额 ⇒ 可超退 | ◐ |
| 9 | C | `MemberService.java:313` + `:312 doClawbackPointsOnRefund` | **改判（原报不成立）**：回扣 `sourceId = orderId + ":" + refundKey`，按行部分退传的是含行集指纹的键 ⇒ 同额两笔不会互相吞；仅当调用方传 null 才回落 `"default"` 撞键 | ❌→待定 |
| 10 | C | `MemberService.java:321-326` | 回扣用**当前**等级倍率（发放时是旧倍率）⇒ 升级后退款多扣 | ◐ |
| 11 | C | `MemberService.java:116-123` | `total_spent` 只增不减 ⇒ 买满升级后全额退款，等级折扣永久保留 | ◐ |
| 12 | C | `CouponService.java:681-687` | **降为待定**：现场读到的 `reconcilePromotionBudgetDelta(def, max(0, reserved − actual))` 是**差额对账**写法，与「重复释放 R」的指控不符；需逐调用点确认是否还有全额 release | ⚠待定 |
| 13 | C | `PromotionPricingService.java:131-148` | 限购只数订单、不排 PENDING/CANCELLED、无件数上限；SKU 活动不占预算（`budget_cents` 死旋钮） | ◐ |
| 14 | F | `OpsSessionOrderQueryService.java:261-262` | 导出按单循环 `findByOrderId`：5000 单 = 5001 次查询（同文件 `:285` 已有批量写法） | ◐ |
| 15 | F | `MarketingRoiService.java:142-152` | `findAll()` 活动 × 每券定义 3 查 + 全量物化 ⇒ 500 券定义 ≈ 1500+ 查询 | ◐ |
| 16 | F | `NearbyDeviceService.java:40`、`SiteContractService.java:50/66` | 全表载入 `device_info` 再内存过滤（附近柜机接口每请求扫全表） | ◐ |
| 17 | F | `FootfallAnalyticsService.java:67,71-72` | 每设备 1 次 count + 两次全表 ⇒ 2k 柜 = 2001 查询/次 | ◐ |
| 18 | F | `InvoiceRequestMapper.xml:14-22` + `uk_invoice_order_active` | **措辞订正**：并非「无索引」，而是只有 partial unique `(order_id) WHERE status IN (PENDING,ISSUED)`；按 order_id 的锁查询不带该谓词 ⇒ EXPLAIN 实测 `Seq Scan on invoice_request` | ✅ |

---

## 三、P3（10 条）

| # | 位置 | 问题 |
|---|---|---|
| 1 | `V2__user_order_sku.sql:26`、`V3:12`、`V4:8`、`V9:99` | 状态列均 VARCHAR 无 CHECK，越界串可落库、报表静默漏行 |
| 2 | `SessionState.java:55` + `DisputeService.java:846` | CANCELLED→DISPUTED 对全部调用方开放，超出「迟到兜账」意图 |
| 3 | `UnpaidOrderService.java:427` + `SettlementOrderFinalizeService.java:144` | 补缴双路径 `onOrderPaid` 统计不幂等 ⇒ `total_spent/order_count` 翻倍 |
| 4 | `PaymentService.java:357-369`、`:499-519` | 无主/渠道不符回调静默 200，无告警痕迹 |
| 5 | `PromotionActivityMapper.java:32` vs `ConsumerMarketingService.java:277` vs `:61-63` | 活动窗端点开/闭三处不一致；DB `now()` 与 `Instant.now()` 混用 |
| 6 | `CouponService.java:216` | 券过期按发放时刻而非日末 ⇒「到期当天」少用近一天 |
| 7 | `SystemConfigService.java:564`、`MerchantSkuPricingService.java:86` | 配置/促销策略每次查库（一单 N 行 = 7N 查询），无本地缓存 |
| 8 | `DeviceCommandTracker.java:238`、`XxlJobConfig.java:48` | commonPool 跑阻塞 HTTP；XXL executor 默认 200 线程 vs Hikari 20 |
| 9 | `packages/shared-uni/src/product-image.ts:4-10` | 图片无 OSS 缩略参数 + 上传上限 10MB ⇒ 移动端首屏带宽 |
| 10 | `clients/admin-vue/src/views/finance/DeviceRentalView.vue:49` vs `views/system/OrgSitesView.vue:242` | 同一个 `UNPAID` 值一处写「未付」一处写「待付」（客服对不上）；`supplier_payable`/`compensation_task` 状态族未进字典 |

---

## 四、安全纵深（主审自查，覆盖面有限）

**结论：本轮未查出 P0/P1 级安全问题**，只有一条 P3：

- `SessionCookieService.java:145-146`：`HttpOnly=true`、`Secure` 走配置，但**未显式设置 SameSite**（依赖浏览器默认 Lax）。写操作有 `X-Requested-With` 要求作为 CSRF 缓解（历轮已建），故仅 P3：建议显式 `SameSite=Lax`（或 Strict）并加断言。

已核对无问题（主审实读）：
- CORS：`WebConfig.java:96-113` 用显式 origins 白名单 + `allowCredentials(true)`，空串过滤后为空即不放宽；`ProductionStartupValidator:359-363` 生产拒 localhost origin。
- 口令哈希：`PasswordEncoderConfig.java:13` BCrypt。
- 登录爆破：`AuthService` 挂 `LoginThrottleService`（按手机号计数，成功后 `clearFailures`）。
- 支付回调验签/时效/nonce：`WeChatPayNotifyService:45-77`、`AlipayNotifyService:35-66`（子代理复核）。

**未覆盖（下轮补）**：文件上传的类型判定链（`FileAttachmentService`/`MediaAssetService` 只列了文件未逐行读）、预签名 URL 时长与 scope、多商户导出隔离、`.env`/备份文件可达性。

---

## 五、跨端一致性（主审自查，覆盖面有限）

- 比对族：订单状态、争议状态。后端 `cabinet_order.status` 实际写入值 = `PENDING/PAID/COMPLETED/CANCELLED/REFUNDED/PARTIAL_REFUNDED/DISPUTED`，字典 `SysDictBootstrap` 全部覆盖 ⇒ **无漂移**（此前 grep 命中的 `UNPAID/PROCESSING` 属 `supplier_payable`/`compensation_task`，不是订单表，未误报）。
- 真问题：`supplier_payable`/`compensation_task` 的状态族**未进字典**，admin 两处页面各写各的中文（见 P3#10）。
- **未覆盖**：退款/提现/补货任务/设备状态族的逐值比对、时间口径（+08）跨端比对、金额字段三端同源比对。

---

## 六、待定（6 条，需数据或产品口径）

1. ~~会员价与活动价是否叠加~~ **已拍板并落地（2026-11-12，用户裁定「不叠加/取优」）**：`SettlementOrderSupport.java:126-161` 原实现是「先会员价再以其为 base 算活动价」（折扣型活动＝双重优惠），与 `PROJECT_KNOWLEDGE §6.3` 矛盾。现改为活动价按**目录价**算、再与会员价取更低者（`PromotionPricingService.resolveBest/ForOrder`），竞品对照入台账 **CB-084**，回执见 §十一。
2. 争议改单跌破门槛时券不返还但仍 USED（`SettlementOrderSupport.java:222-225`），是否比照 CB-045 退券。
3. `ReconciliationService.runDaily:119-137` 删除重建当日对账行，人工处置仅存 audit——重跑是否允许覆盖。
4. 分账 `WECHAT_FAILED` 同号重提是否依赖微信侧幂等（`WeChatProfitSharingService:481-503`）。
5. Ops「重试」是否可能落到补货会话（取决于异常记录来源）。
6. `device_sku_price` 的 `FOR UPDATE` 只按 device_id 索引 ⇒ 锁该柜全部 SKU 行，并发冲突面需数据判定。

---

## 七、建议修复顺序（本轮只出报告）

1. **R20-1 / R20-2 / R20-9**：三条都是「账面说钱退了/收了，渠道没动」——同一批修，判据用守恒 SQL。
2. **R20-4 / R20-10**：把渠道与内部 HTTP 全部移出事务并加超时（这条同时是 R20-1 的放大器：网关卡死时行锁与连接被占）。
3. **R20-3 / R20-5 / R20-6 / R20-7 / R20-8**：用户资产直接受损（券叠加、积分多扣、重复收款、免单旁路、终态复活）。
4. P2 表按轴分四批；P3 与待定项随迭代。
5. 建议给「同一笔钱的守恒」加一条**可跑的门禁**：`Σ收入 − Σ退款 − Σ分账 − Σ提现 = 各账户余额变动`，用 dev 库全量对账跑，红即阻断。


---

## 八、亲验回执（2026-11-12 补做，主审逐条读码/实跑）

判据口径：**对**=按引用行读到所述代码且推理成立；**复核不到**=现场读到的写法与指控不符或需更多调用点；**错**=原报有误（已改判）。

### P1（10/10 判「对」）

| # | 判 | 亲验到的关键证据 |
|---|---|---|
| R20-1 | 对 | `finalizeLiveOrderRefund(prep, refundStatus)`（`:846-863`）内只有 `isCompleted(idemKey)` 幂等闸，**无任何按 refundStatus 的分支**，直接 `recordOperation(REFUND …)` |
| R20-2 | 对 | `SettlementOrderFinalizeService.java:109-118`：`if (!tryChargeSettledOrder(...)) return;` 之后无条件 `freezePaidAmount`→`recordSplit`→`onOrderPaid`；`UnpaidOrderService.cancelSingleExpiredOrder` 段内只 `setStatus(CANCELLED)`，grep `voidSplit/clawback` = 0 命中 |
| R20-3 | 对 | `CouponService` 手动用券段的 5 个 throw 分别是「缺订单号/非本人/DISPUTED/状态不可用/已用券/已核销其他券」，**无 activityDiscount 闸** |
| R20-4 | 对 | `WeChatPayV3Client.java:42`、`AlipayOpenApiClient.java:33` 均 `RestClient.create()`；grep `timeout/requestFactory` 在这两个类 0 命中 |
| R20-5 | 对 | `PointsExpiryScheduler` 的 `actualExpire=min(available,Σ)` 与「`pts>remaining` 整条跳过不写 `expired_at`」并存 ⇒ 同一 EARN 行可被反复过期 |
| R20-6 | 对 | `SettlementConfirmDisputeService.java:166-168` 只挡 REFUNDED；`:213` 仅 DISPUTED→PAID ⇒ CANCELLED 单扣完钱仍 CANCELLED；`SettlementOrderSupport.java:188-191` 用重算值覆盖 `total/original/activity` |
| R20-7 | 对 | `OpsSessionOrderQueryService.java:216-226` 两个 EnumSet 黑名单不含 SHOPPING/OPENING；该段无 `releaseIfFrozen`（对照 `SessionService.java:366` 有）；清扫器 `SessionExpireService.java:160` 只 `List.of(SessionState.FAILED)` |
| R20-8 | 对 | `PayScoreOrderNotifyService.handlePaid:102` 早退条件只有 `"PAID".equals(status)` ⇒ REFUNDED/CANCELLED 收到 DONE 被改回 PAID |
| R20-9 | 对 | 写侧 `setWechatPendingReturnNo/Cents` 为**覆盖赋值**；读侧 `retrySingleFailedReturn:354` 只读 `getWechatPendingReturnCents()` 单值 ⇒ 两轮失败叠加时前一轮额度丢 |
| R20-10 | 对 | `TradeServiceClient.java:28` `RestClient.builder().baseUrl(...).build()` 无超时；`MqttEventListener` 在回调线程内同步 `notifyDoorEvent` 且失败不 ACK |

### P2（18 条：14 对 / 1 改判 / 1 降待定 / 2 部分）

| # | 判 | 亲验到的关键证据 |
|---|---|---|
| 1 补货终态复活 | 对 | `ReplenishmentService` 该段只有 `if (STATUS_COMPLETED.equals(...))` 早退，随后 `if (!IN_PROGRESS) setStatus(IN_PROGRESS)` ⇒ CANCELLED 被打回 |
| 2 超时取消无重查 | 对 | `ReplenishmentTimeoutScheduler:80-105` 段内 grep `findByIdForUpdate` = 0 命中，直接 `task.setStatus(CANCELLED)` |
| 3 争议入口无补货闸 | 对（可达性待定） | `DisputeService:259` 只判 `state != COMPLETED && != DISPUTED`，无 `isRestockSession/replenishment_task_id` 判定 |
| 4 充值取消不查渠道 | 对 | 取消段只 `STATUS_PENDING` 判定即置 CANCELLED；回调侧 `doCreditRecharge` 对非 PENDING 抛 `CONFLICT ORDER_NOT_PENDING` ⇒ 渠道侧持续重试 |
| 5 支付分事件无状态机 | 对 | `:74` `po.setOrderState(state)` 无条件覆盖，`switch(state)` 只做分发 |
| 6 退款通知重复推进 | 对（口径收窄） | 余额腿键 `channel-refund-sync:{refundId}` 幂等；但 `finalizeChannelPartialRefund(orderId, refundCents)` 无同键幂等 ⇒ 同 refundId 重复投递时 `refundedCents` 可累加，受 `already+refund > amount` 超退闸限制 |
| 7 对账混渠道 | 对 | `sumLedger` 内 `sumPaidAmountBetween(start,end)` 与 `sumGatewayRechargeRefundBetween(start,end)` 均**不传 channel**，而 `sumRechargeRefundByChannel(start,end,channel)` 传 ⇒ 毛额侧混渠道 |
| 8 整单退充值不扣已退 | 对 | `debitForRechargeRefund` 判据只有 `status=PAID` 与 `balance >= amount`，扣的是 `-order.getAmountCents()`，全段无 `refundedCents` |
| 9 积分回扣键 | **改判** | `doClawbackPointsOnRefund` 的 `sourceId = orderId + ":" + refundKey`，按行退传指纹键 ⇒ 原报「同额两笔第二笔不回扣」不成立；仅 `refundKey=null` 回落 `"default"` 时才会撞键 |
| 10 回扣用当前倍率 | 对 | `MemberService:321-326` 用 `findByLevelCode(member.getMemberLevel())` 的**当前** rule 算 clawback |
| 11 total_spent 只增 | 对 | 该文件内 `setTotalSpent` 仅两处：初始化 ZERO 与 `.add(orderAmount)`，无减 |
| 12 预算重复释放 | **降待定** | 读到 `reconcilePromotionBudgetDelta(def, max(0, reserved − actual))` 为差额对账写法，与「各释放 R」不符；需逐调用点复核 |
| 13 活动限购口径 | 部分 | 注释自证「每人最多 N **单**／总共 N **单**」⇒ 无件数上限成立；是否排除 PENDING/CANCELLED 未读到 mapper 谓词 |
| 14 导出 N+1 | 对 | `for (CabinetOrder o : page.getContent()) { orderLineRepository.findByOrderId(...) }`（同文件另有批量写法可照抄） |
| 15 MarketingRoi | 对 | `activityRepository.findAll()` → 嵌套 `for (CouponDefinition def : defs)` → `userCouponRepository.findByCouponDefId(...)` 全量物化 |
| 16 附近柜机全表 | 对 | `for (DeviceInfo d : deviceRepository.findAllOrderByDeviceIdAsc())` 内做 haversine + 半径过滤 |
| 17 Footfall | 对 | `findAllOrderByDeviceIdAsc()` 被调用两次（一次只为 `.size()`），循环内每设备 `countByDeviceIdAndCreatedAtAfter` |
| 18 invoice_request | 对（措辞订正） | 实为 partial unique `(order_id) WHERE status IN (PENDING,ISSUED)`；`EXPLAIN … WHERE order_id='x' FOR UPDATE` 实测 `Seq Scan on invoice_request` |

> 修的人请注意：**P2-9 与 P2-12 不要按原报去修**（一条不成立、一条需先复核调用点）；P2-18 的修法是加普通 `(order_id)` 索引或让锁查询带上 status 谓词，而不是「补一个缺失的索引」。


---

## 九、P3 亲验回执（2026-11-12 补做）

| # | 判 | 亲验到的证据 |
|---|---|---|
| 1 | 对 | `V2__user_order_sku.sql:26` 原文 `status VARCHAR(16) NOT NULL DEFAULT 'PAID'` 无 CHECK；全库 grep 显示**新表普遍有** `chk_*_status`（V192/193/194/255…），核心老表（cabinet_order 等）没有 ⇒ 措辞按「老表缺、新表有」执行 |
| 2 | **降为说明** | `SessionState.java:52-56` 的 `case CANCELLED -> target == DISPUTED;` 上方注释写明是 2026-11-12 兜账 v2 的**唯一出边、刻意保留**；真正需要关注的只是 `DisputeService` 重开路径复用了这条边（订单侧只在 PAID/COMPLETED 才回写 DISPUTED，不会误改 CANCELLED 单）⇒ 不作为缺陷派修 |
| 3 | 对 | `MemberService.onOrderPaid:205-217`：`applyMemberStatsDelta(+amount)` **无 orderId 去重**，只有 `earnPoints(...,orderId)` 有幂等 ⇒ 与 R20-2 同根因（未实收先记账，回调/补缴再记一次） |
| 4 | 对 | 微信侧 `:359-369`、支付宝侧 `:501-508`：channel mismatch 与 missing amount 都是 `log.warn` + `return`（对外仍 200、无告警）；但支付宝金额不符会抛 409 ⇒ 「静默」仅限这两类 |
| 5 | 对 | `PromotionActivityMapper.java:32` `end_time > now()`（排除端点）vs `ConsumerMarketingService.java:277` `now.isAfter(endTime)` 才拒（**含端点**）⇒ 同一秒 SQL 判过期、Java 判有效 |
| 6 | 对 | `CouponService.java:216` `uc.setExpireAt(Instant.now().plus(validityDays, DAYS))` ⇒ 按发放时刻而非当日 23:59:59，「到期当天上午即失效」成立 |
| 7 | 对 | `SystemConfigService:560` 直接 `repository.findById(key)` 无缓存注解；`MerchantSkuPricingService:89-94` 单次 `loadPromoPolicy` ≥5 个 getInt（子代理报 7，实测 ≥5）⇒ 一单 N 行 = 5N+ 次配置查 |
| 8 | 对 | `DeviceCommandTracker:238` `CompletableFuture.runAsync(...)` 无 executor（commonPool）；`XxlJobConfig:46-52` 只设 adminAddresses/appname/ip，未设执行线程数 ⇒ 默认值远大于 Hikari 20 |
| 9 | 对 | `packages/shared-uni/src/product-image.ts` grep `x-oss-process/resize/thumbnail` = 0 命中；`application.yml:17-18` `max-file-size: 10MB / max-request-size: 12MB` ⇒ 原图直出 + 10MB 上限 |
| 10 | 对 | 实测两处：`DeviceRentalView.vue:49` `label="未付" value="UNPAID"`、`OrgSitesView.vue:242` `label="待付" value="UNPAID"`；字典 `SysDictBootstrap` 无 `supplier_payable`/`compensation_task` 状态族 |

> 派修提示：P3-2 不要改（改了会把兜账 v2 的救援路径堵死）；P3-5 的统一方向建议**以 SQL 为准（不含端点）**，因为活动窗是「到点即止」的运营语义，Java 侧含端点会让最后一秒出现「列表可见但领取报活动已结束」。

---

## 十、修复回执（2026-11-12，P1 首批 8 条，本地未推）

> 口径：只动「容器内可局部修」的判据；凡涉及**资金口径拍板**或**拆事务/拆表**的两条（R20-2、R20-9）本轮**不猜着改**，见 §十.2。

### 十.1 已修（8 条）

| # | 落点 | 改法 | 判据 |
|---|---|---|---|
| R20-1 | `OrderPaymentService.finalizeLiveOrderRefund` | 按渠道状态分支：仅 SUCCESS/PROCESSING 入账；**ABNORMAL 只记 `REFUND_FAILED` + 保留 INITIATED 痕迹 + HIGH 告警**，并返回 false；`refundOrder` 收到 false 立即抛 502 中止（不累加 `refundedCents`、不回扣积分、不冲分账） | `OrderPaymentRefundRetryTraceTest.finalize_abnormalStatus_…`（断言 `refundedCents==0` 且不 `updateById`）；SQL 判据沿用 `REFUND+COMPLETED+reason LIKE '%ABNORMAL%'` 恒 0 |
| R20-3 | `CouponService.requireOrderEligibleForCoupon` | 补 `activityDiscountCents>0` 拒绝（与结算 `:86`、补扣 `:464` 同口径） | `useCoupon_shouldReject_whenOrderHasActivityDiscount` |
| R20-4（半） | `WeChatPayV3Client` / `AlipayOpenApiClient` | `RestClient.create()` → 3s 连接 / 10s 响应（对账单下载单独 60s 客户端）；**「渠道 HTTP 移出事务」未动**（见十.2） | 两客户端构造器实读；`OpsAlertDispatcher`/`AgreementChargeClient` 同范式 |
| R20-5 | `PointsExpiryScheduler` + `V459` + `MemberPointsLog.expiredPoints` | 新增 `expired_points` 列（回填历史整额过期行），过期按 `min(行内剩余, 预算余量)` 部分标记，整行耗尽才置 `expired_at`；预算与提醒都改用「剩余部分」求和 | `PointsExpiryPartialTest`（两轮累计 ≤ 面额；已过期 60 的行预算只算 40） |
| R20-6 | `SettlementConfirmDisputeService.prepareConfirmDispute` | 白名单 `{PAID,PENDING,DISPUTED}` **且** `refundedCents==0`，其余一律 409 | `cancelledOrder_conflicts` / `partiallyRefundedOrder_conflicts` |
| R20-7 | `OpsSessionOrderQueryService.cancelSession` | SHOPPING 不再直接置 CANCELLED：先 `releaseIfFrozen` → 转 DISPUTED → 建争议工单（照 C09/开门超时范式）；CREATED/OPENING 取消也补释放 hold | 代码面（该路径无 DTO 级测试）；审计日志码改为 `SESSION_CANCEL_TO_DISPUTE` |
| R20-8 | `PayScoreOrderNotifyService.handlePaid` | 终态集 `{REFUNDED,PARTIAL_REFUNDED,CANCELLED}` 收到 DONE 不回写 PAID，改落 `PAYSCORE_DONE_ON_TERMINAL_ORDER`(HIGH) 交人工 | `doneEvent_onTerminalOrder_doesNotResurrect` |
| R20-10（半） | `device-service/…/TradeServiceClient` | 补 2s 连接 / 5s 响应超时，把 MQTT 回调线程单次阻塞上限钉死；**未改异步化**（见十.2） | 构造器实读 |

**验证**：`mvn -o -pl services/trade-service -am test` 定向 63 例全绿；负向验证（拆掉 R20-3/R20-5/R20-6 三处判据）→ 5 例转红，还原后 md5 逐字节一致 + 全绿。

### 十.2 本轮**有意不改**（3 点，需拍板或需拆结构）

1. **R20-2 支付分未实收即分账**：修法要求「分账/发积分/统计一律挪到实收确认后 + 取消路径 void+clawback」。这不是补闸，是**结算主流程重排**（`tryChargeSettledOrder` 成功与否要与 PENDING 状态解耦），且与「免密支付分先享后付」的产品口径绑定 ⇒ 需先定：支付分单在 DONE 之前，商户钱包是否允许**影子入账**（LEDGER_ONLY 待确认）。**建议**：分两刀——① 先做 `MemberService.onOrderPaid` 的 orderId 去重（同时覆盖 P3-3）；② 再做钱包/分账延后。
2. **R20-9 分账回退单槽覆盖**：`wechat_pending_return_no/cents` 是**单值列**，多轮部分退需要「多条回退腿」的账。合并成一个新 `out_return_no` 有双退风险（前一条可能已在途）。**建议**：新建 `order_split_return_leg(split_id, out_return_no, cents, status)`，补偿器按腿提交/查单，判据 `Σ腿 = 初始 merchant − 现 merchant`。这是加表，不该顺手做。
3. **R20-4 移出事务 / R20-10 异步化**：先补超时（本轮已做）已把「无上限阻塞」消掉；把渠道调用移出 `@Transactional` 会改动 `chargeOrder` 的提交语义（现在靠事务回滚保证金货两清），需单独一轮带停机窗口验证。

### 十.3 一条**不采信**报告建议（重要）

> R20-7 原修法写「清扫器覆盖 CANCELLED+FROZEN」。**这条不采信**：`SessionState` 的 `CANCELLED→DISPUTED` 是 2026-11-12 兜账 v2 刻意保留的救援边（见 §九 #2），其扣款来源正是会话上那笔 `FROZEN` 预授权。若把 CANCELLED 的 hold 当「悬挂」清掉，迟到识别上报转争议后**无钱可扣**，等于用清扫器把救援路径拆了。因此本轮只堵「取消入口」（不再产生 CANCELLED+FROZEN），清扫器仍只覆盖 FAILED。

### 十.4 部署注意

- `V459` 是新增迁移 + 回填 ⇒ 需 `mvn -pl services/trade-service -am -DskipTests package` 后 `docker compose … up -d --no-deps --force-recreate trade-service`（种子/迁移在 jar 内，`build` 与 `up` 拆两步）。
- 若某个环境曾手改过 `member_points_log`，上线前先 `SELECT count(*) FROM member_points_log WHERE expired_at IS NOT NULL AND points > 0 AND expired_points = 0`（应为 0，迁移会清零）。


---

## 十一、P2-12 拍板与落地回执（2026-11-12，会员价 × 活动价「不叠加、取优」）

> **用户裁定**：不叠加、取优（低价优先）。此前代码是「先会员价、再以其为 base 算活动价」——
> 对 `SKU_REDUCE`/`SKU_DISCOUNT` 两类活动等于**在会员价上再打一次折**，与 §6.3 自相矛盾。

### 十一.1 改法（3 个文件，口径收敛到一处）

| 落点 | 改法 |
|---|---|
| `PromotionPricingService` | 新增 `resolveBest(...)` / `resolveBestForOrder(...)` 门面：活动价**以目录价为基价**计算，结果再与会员价比较，`>= 会员价` 一律 `Optional.empty()`（＝未命中：不核销限购、不写活动留痕）；返回的 `discountPerUnitCents` 重算为 **会员价 − 活动价**（相对应付基准的少收额）。原 `resolveUnitPrice*` 保留为「活动原语」，javadoc 明写「base 必须是目录价，否则即双重优惠」。 |
| `SettlementOrderSupport.applyItemsToOrder` | 改调 `resolveBestForOrder(..., listUnitPrice, memberUnitPrice)`；`activity_discount_cents` 落门面返回的**相对会员价**增量 ⇒ 与 `member_discount_cents`（目录价−会员价）、`gross` 一起维持 `gross − member − activity − coupon = total`。改单沿用原单活动（`PreservedActivity`）不动——它存的就是同一增量。 |
| `DeviceCatalogService.listProducts` | 商品读面改调 `resolveBest(...)`（同样以目录价为基价），保证**显示价＝实付**；活动价不优于会员价时 `activityPriceCents` 下发 `null`（卡片只显示会员价）。 |

### 十一.2 判据（钉测 + 负向验证）

- 新增/改钉测：`PromotionPricingServiceTest.resolveBest_*` 3 例（命中且留痕＝会员价−活动价／活动 ≥ 会员价一律不命中，含**相等**／折扣型活动按目录价打折，900×… 若错成会员价再打 8 折会得 744）；`DeviceCatalogServiceTest.listProducts_activityNotBetterThanMember_isIgnored`；`SettlementMemberDiscountOnceTest.memberAndActivityTogether_bestOfTwoNotStacked`（stub 断言结算侧传的是**目录价 400** 而非会员价 392）。
- 负向验证（铁律 4）：同时注入三处——① 门面 `.filter(...)` 改成恒真；② 结算侧传 `memberUnitPrice` 当活动基价；③ 展示侧同样叠加 ⇒ **4 例转红**（`resolveBest_activityNotBetterThanMember_isIgnored`、两个 `DeviceCatalogServiceTest`、`SettlementMemberDiscountOnceTest`），还原后 md5 逐字节一致再全绿。

### 十一.3 连带文档

- `PROJECT_KNOWLEDGE §6.3` 第 160 行「先会员价、再活动价」改为「不叠加、取优」，并新增两条硬约束（活动基价必须是目录价；`activity_discount_cents` 记相对会员价的增量）。
- `COMPETITOR_BENCHMARK.md` 新增 **CB-084**（思迅/星宇收银/云上铺 三家收银服务商 + 金蝶 cross 补充）：思迅明写「低价优先，不支持在会员优惠基础上再参与特价/折扣」，星宇把「取两者较低价」做成默认开关，云上铺点名「先会员后促销」只适合清货期。门禁 `check-competitor-benchmark` **83 条 / 497 URL / exit 0**；负向验证（把 CB-084 取证日期改成 2030-01-01）⇒ exit 1 并点名「禁止编造未来日期」，还原后 md5 一致再 exit 0。
- 为落该条，门禁主体表登记 `星宇收银`/`云上铺` 为 peer（与既有 `思迅` 同档：便利店/超市收银 SaaS 服务商），未放宽任何判据阈值。

### 十一.4 部署注意

本轮**无新增迁移**（纯代码 + 文档）⇒ 照常 `mvn -o -pl services/trade-service -am -DskipTests package` + `up -d --no-deps --force-recreate trade-service` 即可，无需 Flyway 干预。历史单不回填：已成交订单按当时的单价落库，取优口径只对**新成交/新改单/新展示**生效（成交即定价）。

---

## 十二、P2 派修单（2026-11-12，按轴四批；**实际 18 条不是 25 条**）

> 口径订正：报告 §二 的 P2 是 **18 条**（不是 25 条）。其中 **P2-9 复验不成立**（幂等键含行集指纹）、
> **P2-12 已拍板并落地**（§十一）⇒ 本轮**可派修 16 条**。四批按「同一根因族一起改」切，避免同一文件多轮动刀。

### 批 A｜补货/争议的状态机与并发覆盖（4 条，**已全清**）

| # | 落点 | 改法要点 | 判据 |
|---|---|---|---|
| **P2-1 ✅** | `ReplenishmentService:612-627` | 发运后打回 IN_PROGRESS 的判据从「!= COMPLETED」扩成「只允许 {PENDING,IN_PROGRESS,ARRIVED?} 这些未终结态」，CANCELLED 一律 409 | 单测：CANCELLED 任务再发运 ⇒ 冲突且状态不变 |
| **P2-2 ✅** | `ReplenishmentTimeoutScheduler:82-105` | 用**扫描快照直写** CANCELLED → 改成条件更新（`WHERE status=<快照态>`），更新 0 行即放弃 | 单测：并发把 COMPLETED 写回后再跑清扫 ⇒ 不清 |
| **P2-3 ✅** | `DisputeService:259` | 建消费单前加 `isRestockSession` 闸（补货会话不得进争议/结案扣款链） | 单测：补货会话 ⇒ 409，且不写钱包 |
| **P2-4 ✅** | `PaymentService:1032-1039` | 充值 PENDING→CANCELLED 前先查渠道单状态（有实付即拒绝取消），避免「已付款回调撞 409 无限重试」 | 单测：渠道已付 ⇒ 拒绝取消并可补入账 |

### 批 B｜资金幂等与对账口径（4 条，**已全清**）

| # | 落点 | 改法要点 | 判据 |
|---|---|---|---|
| **P2-5 ✅** | `PayScoreOrderNotifyService.handleOrderNotify` | 状态机只进不退：判据命中即整段忽略（不写库/不催缴/不记欠款/不倒退 `order_state`）——① 快照已 `DONE`；② 订单已 `PAID`（快照可能是历史脏数据，实收才是权威）。早退前移的另一个理由：旧实现先 `setOrderState` 再分支，`DONE` 会被迟到的 `EXPIRED` 覆盖 | `expiredEvent_afterDone_doesNotEscalateNorRegressState`、`userPayingEvent_afterDone_doesNotRemind`、`revokedEvent_whenOrderAlreadyPaid_doesNotEscalate` |
| **P2-6 ✅** | `PaymentService.syncChannelSideRefund` | 以账本幂等键（`channel-refund-sync:<refund_id>`）是否已存在为闸，整段跳过 ⇒ 单据推进与余额同幂等 | `handleWeChatRefundNotify_duplicateDelivery_doesNotDoubleCountRefunded`｜**提交说明**：代码被并发会话的 `23c57e09`（R22 P2×6）连带提交，该提交信息未提及本条；测试随本轮提交补上（实现先于测试入库，属并发会话连带提交的后果） |
| **P2-7 ✅** | `ReconciliationService.sumLedger/collectLedgerOrderIds` + `RechargeOrderMapper`（含 XML） | 求和与取号同 scope：`WECHAT/ALIPAY` 只算本渠道（毛额与单号都要过滤），`MOCK` 保持全量——Mock 账单由 `MockPlatformBillProvider` 用三渠道拼成，是聚合视图，按 MOCK 过滤会把充值滤空 | `runDaily_wechatIgnoresOtherChannelRechargeRefund`（修前 1450 / 修后 2250）、`runDaily_mockKeepsAggregateScopeForRecharge`、`RechargeOrderMapperXmlTest`（离线解析 XML 断言谓词真下推） |
| **P2-8 ✅** | `PaymentService.debitForRechargeRefund` | 可退上限改为 `min(面额 − 已退, 余额 − 剩余赠送)`，已退满直接 409；与 `prepareChannelPartialRefund` 的「可退 = 面额 − 已退」收敛到同一口径 | `refundRecharge_afterPartialRefund_onlyRefundsRemainingAmount`、`refundRecharge_alreadyFullyRefunded_rejects` |

> **P2-7 修正报告原判**：缺陷比报告写的多一层。除 `sumPaidAmountBetween` 外，`findPaidOrderIdsBetween`
> 也不带渠道（会让别家充值单全被判 `LEDGER_ONLY`），且 `sumLedger` 用「扣全部网关退款 + 加回本渠道」
> 配对——单渠道下等于把别家退款也扣进本账。三处必须一起改，只补一维仍出假差异。
> **口径变更需知晓**：历史 `payment_reconciliation` 行是按旧口径算的，重跑同一日期会得到不同数字（新数字才是对的），不回填历史。

### 批 C｜会员/券/活动口径（5 条，P2-9 不成立、P2-12 已修）

| # | 落点 | 改法要点 | 判据 |
|---|---|---|---|
| P2-10 | `MemberService:321-326` | 扣回积分用**发放时**倍率（流水里存 rate 或按 EARN 行反算），不用当前等级 | 单测：升级后退款按旧倍率扣 |
| P2-11 | `MemberService:116-123` | `total_spent` 全额退款后可回收（净额口径）或至少在等级判定里改用净额 | 单测：买满升级→全退 ⇒ 等级回落（需产品确认是否允许降级） |
| P2-13 | `PromotionPricingService:131-148` | 限购计数排除 PENDING/CANCELLED + 支持件数上限；`budget_cents` 死旋钮要么接预算要么删 | 校验 mapper 谓词（本轮待补验）；单测：取消单不占名额 |
| P2-3/P3-3 | `MemberService.onOrderPaid` | **orderId 去重**（同 R20-2 第一刀），与积分腿同口径 | 单测：同一单两条补缴路径只记一次 |

### 批 D｜N+1 与全表扫描（5 条）

| # | 落点 | 改法要点 |
|---|---|---|
| P2-14 | `OpsSessionOrderQueryService:261-262` | 导出改批量 `findByOrderIdsIn`（同文件 `:285` 已有范式） |
| P2-15 | `MarketingRoiService:142-152` | `findAll()` 改筛选下推 + 券定义批量取 |
| P2-16 | `NearbyDeviceService:40`、`SiteContractService:50/66` | 全表载入 `device_info` 改 SQL 过滤（地理/状态条件下推） |
| P2-17 | `FootfallAnalyticsService:67,71-72` | 每设备 count → 单条 group by 聚合 |
| P2-18 | `InvoiceRequestMapper.xml:14-22` | 锁查询带 `status IN (PENDING,ISSUED)` 谓词（与 partial unique 对齐），或补 `(order_id)` 普通索引 |

> 排序建议：B → A → C → D（B 批直接对钱，A 批是重复履约与免单旁路）。每批落地后各跑一次
> `mvn -o -pl services/trade-service -am test -Dtest='<受影响类>'`，并各做一次负向验证（铁律 4）。

---

## 十三、批 B 修复回执（2026-11-12，资金幂等与对账口径 4/4）

> 四条全部落地并负向验证。两处**修正报告原判**（P2-7 缺陷比写的多一层）、一处**提交归属异常**（P2-6/P2-8 代码被并发会话连带提交，见 十三.4）。

### 十三.1 P2-5｜支付分事件状态机只进不退

`handleOrderNotify` 原来**先** `po.setOrderState(state)` **再**分支，于是 DONE 之后微信补投的
`USER_PAYING/EXPIRED/REVOKED` 会：① 把快照从 DONE 倒退回 EXPIRED（查单/对账据此误判钱到没到）；
② 走 `remindBySms` 发催缴短信；③ 走 `recordUnpaidAndEscalate` 记欠款 + 升级阶梯——**用户已被扣款成功，
却被按欠款处置，还会被欠款闸拦下一次开门**。

改法：早退闸前移到 `setOrderState` 之前，两条判据任一命中即整段忽略：

- ① 快照已 `DONE`（钱已到账，迟到事件是异步补偿投递）；
- ② 订单已 `PAID`（快照可能是历史脏数据，**实收**才是权威）。

保留 `DONE→DONE` 重投的正常路径（`handlePaid` 自身有 `CHARGE:{orderId}:{amount}` 幂等键 + `PAID` 早退）。

### 十三.2 P2-7｜单渠道对账的渠道过滤（**比报告多两处**）

报告只指出 `sumPaidAmountBetween` 缺渠道。实读后发现同族三处，缺任一处仍出假差异：

| 位置 | 问题 | 改法 |
|---|---|---|
| `RechargeOrderMapper.sumPaidAmountBetween`（XML） | 无渠道谓词 ⇒ 单渠道对账把别家渠道充值毛额算进本账 | 加 `channel` 参数，非空时 `UPPER(r.channel)=UPPER(#{channel})` |
| `RechargeOrderMapper.findPaidOrderIdsBetween`（XML） | 同上，且后果更隐蔽：别家充值单号进入本账集合 ⇒ 平台账单里查不到 ⇒ **全被判 `LEDGER_ONLY`**（合计对得上、明细全错） | 同 scope 加谓词 |
| `ReconciliationService.sumLedger` | 用「扣**全部**网关退款 + 加回本渠道」配对 ⇒ 单渠道下多扣别家退款（WECHAT 对账扣了支付宝的退款），假差异会与真差异互相抵消 | 按 scope 对齐：单渠道只扣本渠道，相消后 = 购物净流水 + 本渠道充值毛额 |

**MOCK 必须保持全量**：`MockPlatformBillProvider.GATEWAY_PAY_CHANNELS` 是 WECHAT/ALIPAY/MOCK 三渠道，
Mock 账单是**聚合视图**；若跟着过滤成 MOCK，充值（只可能记在 WECHAT/ALIPAY）会全被滤空、每笔都变差异。
故显式区分 `isAggregateBillChannel(MOCK)`，并加钉测防止后续「一刀切统一过滤」。

**口径变更需知晓**：历史 `payment_reconciliation` 行按旧口径落库，重跑同日会得到不同数字（新数字才对），不回填。

### 十三.3 P2-8｜整单退的可退上限扣掉已退

`debitForRechargeRefund` 原来 `refundCents = min(面额, 余额 − 剩余赠送)`，完全不看 `refunded_cents`。
而部分退款成功后单据**可能仍是 PAID**（`finalizeChannelPartialRefund` 只在退满时置 REFUNDED），
此时再走整单退会按完整面额再退一次；同时 `prepareChannelPartialRefund` 另有「可退 = 面额 − 已退」——
两套口径并存即渠道超退。现收敛为 `min(面额 − 已退, 余额 − 剩余赠送)`，已退满直接 409。

### 十三.4 提交归属异常（必须记一笔）

`PaymentService.java` 的 P2-6 幂等闸与 P2-8 净额判据，被**并发会话的 `23c57e09`（R22 P2×6）连带提交**——
该提交信息只写了 R22-1 的 reason 拼接，未提及这两条（磁盘上它们与 R19-2 改动同文件，对方 `git add` 整文件带走）。
后果与处置：实现已在 HEAD（已回读核实 `refundableAmount`/`alreadyRefunded`/409 分支/幂等闸四段均完整），
但**测试没被带走**，本轮把 `PaymentServiceTest` 的 5 个用例与其余改动一起补提交。
教训：多会话同仓下，「我的改动还在工作区」这个前提随时会被别人的整文件 `git add` 打破，
判据要落在 `git log -S <特征串>` 而不是 `git diff`。

### 十三.5 判据与验证

- 定向 58 例全绿：`PaymentServiceTest`(32) / `PayScoreOrderNotifyTest`(9) / `ReconciliationServiceTest`(12)
  / `ReconciliationConcurrencyTest`(2) / `RechargeOrderMapperXmlTest`(3)。
- 负向验证（铁律 4）：同时拆掉 P2-5 早退闸 + P2-7 scope + P2-8 已退判据 ⇒ **5 例转红**
  （3 条 P2-5、1 条 P2-7、1 条 P2-8）；单独拆 XML 谓词 ⇒ **2 例转红**（`RechargeOrderMapperXmlTest`）。
  两次还原后 md5 逐字节一致、`neg:` 注释零残留，复跑全绿。
- **新增的判据层**：`RechargeOrderMapperXmlTest` 离线解析 mapper XML（`XMLMapperBuilder` + `getBoundSql`，
  不连库不要 Docker），断言渠道谓词**真下推**——单测里 mock 掉的 mapper 只能证明「调用方传了渠道」，
  证明不了「SQL 用了它」，谓词漏写会静默退化成全渠道求和（正是本缺陷的形态）。

---

## 十四、批 A 修复回执（2026-11-12，补货/争议的状态机与并发覆盖 4/4）

> 四条全部落地并负向验证。两处**修正报告原判**（P2-1 多一层入口、P2-3 闸位应在扣款汇聚点），
> 一处把判据从「单侧」补成「双侧」（P2-4 取消侧 + 回调侧）。**本轮无新增迁移**。

### 十四.1 P2-1｜任务状态机只进不退（不是只补一处判据）

报告只点了 `generateLinesFromOutbound`（发运后打回 IN_PROGRESS）。复核发现**同一判据有两个消费点**，
两处都写成「只挡 COMPLETED」：

- `generateLinesForTask`（发运事件）：`if (COMPLETED) return;` ⇒ 发运时把**已取消**的任务
  重新生成明细并打回 IN_PROGRESS。而「按空任务取消」正是运营用来解冻柜机的手段（冻结判据=
  `status=IN_PROGRESS 且 check_in_at≠null`）⇒ 一次发运悄悄撤销解冻，柜机重新对消费者停售。
- `doSubmitTaskLines`（用户改量提交）：`if (COMPLETED) 409;` ⇒ 已取消任务提交明细会把状态打回
  IN_PROGRESS 并重建未上架行。

改法：抽 `isFulfillableStatus(status)`＝**白名单** {PENDING, IN_PROGRESS, PLANNED}（PLANNED 是历史脏值，
按待处理同义；COMPLETED/CANCELLED 一律不推进，未知状态也 fail-closed）。两个入口按各自语义处置：

- 发运是仓储侧已经发生的物理事实 ⇒ **跳过 + `log.warn`（带 outboundId/device，提示「在途货需人工核对」）**，
  不抛错。抛 409 会把出库单卡在在途、货却在车上。
- 用户提交 ⇒ **409 `REPLENISHMENT_TASK_FINISHED`**（COMPLETED 保留原文案 `ALREADY_COMPLETED`）。

判据：`generateLinesFromOutbound_skipsCancelledTask`（不生成明细、不改状态、连货都不读）、
`generateLinesFromOutbound_skipsCompletedTask`、`submitTaskLines_rejectsCancelledTask`（409 + 不删旧行 + 不写新行）。

### 十四.2 P2-2｜超时清扫改成条件更新（快照直写会覆盖并发完成）

`ReplenishmentTimeoutScheduler.expireOne` 原来拿**扫描快照**直接 `setStatus(CANCELLED)` + `save`。
扫描到落库之间补货员可能刚好完成 ⇒ COMPLETED 被改回 CANCELLED，还会连带关掉他的会话、
按取消收口路线、发一条「存在已上架明细」的 HIGH 告警（货真上过架，任务却算取消）。

改法：新增 `ReplenishmentTaskMapper.cancelIfStatus(taskId, expectedStatus, notes)`
（`UPDATE … SET status='CANCELLED', notes=#{notes} WHERE task_id=… AND status=#{expectedStatus}`），
**改到 0 行即整条放弃**——不关会话、不收口路线、不告警、不计入「本次取消 N 条」。
顺序也调整为「先条件更新成功，再关会话」：先关会话后判状态，抢输了会关掉不该关的会话。
附注：`notes` 走显式赋值（`updateById 跳 null` 家族），并 `TextTruncate.safeTruncate(…, 256)` 对齐列宽；
SQL **不写 `updated_at`**——`replenishment_task` 没有这一列（V9 建表）。

判据：`expire_whenTaskMovedOnConcurrently_skipsCloseAndAlert`（cancelIfStatus 返回 0 ⇒ 会话/告警/路线全不碰，
计数仍是「本次无超时补货任务」）、`expire_whenStillInProgress_cancelsAndClosesSessions`、
`expire_withAppliedLines_reportsHigh`。

### 十四.3 P2-3｜补货会话的闸放在「扣款汇聚点」，不只是投诉入口

报告落点 `DisputeService:259`（消费者申诉入口）。但扣款发生在结案：`CONFIRM/ADJUST →
SettlementConfirmDisputeService.prepareConfirmDispute`（无订单 ⇒ `finalizeOrder` 全新落单；有订单 ⇒ 补差扣款）。
迟到识别兜账（`rescueCancelledSessionWithLateRecognition`）与超时转争议**都不经过消费者入口**——
只在入口加闸挡不住「补货员开门被端侧迟到识别报到有货未结算，运营在争议单上点确认，
从**员工余额**扣出这笔货款」（货是商户自己搬进柜子的）。

改法（两处，判据同源 `DeviceValidationService.isNonConsumerSession`，覆盖补货/运维远程/盘点）：

- `prepareConfirmDispute` 开头：非消费者会话 ⇒ 409 `NON_CONSUMER_SESSION_NOT_SETTLEABLE`（**钱这一侧**）。
- `DisputeService.fileByConsumer`：同样 409（**入口这一侧**），避免建出永远结不了案的死单。

判据：`confirmDisputedItems_restockSession_rejectedWithoutOrderOrWallet`（409 + 无 `orderRepository.save`
+ 无 `applyPaymentDelta`）、`fileByConsumer_restockSession_rejectedBeforeTicketCreated`（409 + 不落单 + 不转状态）。

### 十四.4 P2-4｜充值取消与其回调是同一枚硬币的两面

旧顺序「直接关单，关失败才回查」有两个洞：① 关单接口对「刚付完还没回调」的单常返回成功/未定义错误，
本地据此置 CANCELLED；② 随后渠道的成功回调撞 `doCreditRecharge` 的状态闸（非 PENDING ⇒ 409），
微信按非 2xx **无限重投**——用户钱已扣、余额永远不到账，日志里只有一串 409。

改法：

- **取消侧**：`cancelRecharge` 先 `channelAlreadyPaid(order)`（复用 `syncPendingOrder` 查单，查到实收即入账），
  已实收 ⇒ 409 `ORDER_ALREADY_PAID` 拒绝取消，且**不再关单**（关单只会制造第二次错位）。
- **回调侧**：`doCreditRecharge` 增加 `reviveCancelled` 变体 `doCreditRechargeAfterChannelPaid`，
  只由「验签通过的成功通知」与「查单 SUCCESS」两条入口调用。命中已 CANCELLED 立刻补入账并落
  `RECHARGE_PAID_AFTER_CANCEL` 告警；**只救 CANCELLED**，REFUNDED 等状态重投仍 409（重复支付要人工判）。
  Mock 确认路径（`confirmRechargeMock`）仍走严格版，不复活。

判据：`cancelRecharge_whenChannelAlreadyPaid_creditsAndRejectsCancel`（409 + 余额已加 + `closeOrder` 从未调用）、
`doCreditRechargeAfterChannelPaid_whenCancelled_revivesAndAlerts`（PAID + 告警）、
`doCreditRecharge_whenCancelledWithoutChannelProof_stillRejects`（边界：没有渠道实收凭证不得复活）。

### 十四.5 判据与验证

- 定向 **100 例全绿**（9 个类）：`ReplenishmentServiceOutboundTest`(14) / `ReplenishmentTimeoutSchedulerTest`(3, 新增)
  / `ReplenishmentTaskConcurrencyTest`(2) / `ReplenishmentCheckInStatusGatesTest`(6) / `ReplenishmentSelfPickupShipTest`(3)
  / `MerchantSelfReplenishTest`(18) / `SettlementConfirmDisputeTest`(9) / `PaymentServiceTest`(35) / `DisputeTicketSyncTest`(10)。
- 负向验证（铁律 4，四轮独立注入、各自转红、还原后 md5 逐字节一致）：
  ① `isFulfillableStatus` 退回「只排 COMPLETED」⇒ P2-1 两例转红；
  ② 清扫强制 `rows=1`（等价无条件写）⇒ P2-2 并发用例转红；
  ③ `isNonConsumerSession` 恒 false ⇒ P2-3 两例转红（结算侧 NPE、入口侧 NoSuchElement——即「闸没了会走进去」）；
  ④ 去掉 `channelAlreadyPaid` 闸 + 禁止复活 ⇒ P2-4 两例转红（一 FAILURE 一 ERROR）。
- **新增判据层**（铁律 75：修完 bug 同轮落门禁）——`scripts/check-replenishment-checkin-contract.mjs` 扩到 11 组规则：
  - 规则 10：白名单判据必须存在且**不得退化成黑名单**；每条置 IN_PROGRESS 的路径（切片级）必须有**生效的**闸
    （`if (!isFulfillableStatus(` / 显式 CANCELLED 判定）；全文出现次数与三个已知方法体吃掉次数对齐
    （新增路径 ⇒ 门禁判红，不会静默恒绿）；清扫器不得再出现 `taskRepository.save(`/`setStatus(STATUS_CANCELLED)`；
    `cancelIfStatus` 的注解 SQL 必须同时含「写 CANCELLED」与 `WHERE status = #{expectedStatus}`。
  - 规则 11：`fileByConsumer` 与 `prepareConfirmDispute` 切片内必须有 `if (DeviceValidationService.isNonConsumerSession(`
    形态的闸（纯 `includes` 会被 `if (false && …)` 绕过，已按实际情况收紧）。
  - 门禁自身负向验证：四处判据同时改坏 ⇒ exit 1 并点出 **5 条**；还原后 exit 0。其中一次迭代抓到两个**假红/假绿**：
    `extractBracedBodies` 会把 `if (!isFulfillableStatus(x)) {` 的调用点当成「方法体」（故定义体判据改用 `some`），
    以及 `[^)]*` 无法跨过 `STATUS_COMPLETED.equals(...)` 的右括号（故改 `[^;{]*`）。

### 十四.6 部署注意

本轮**无新增迁移**（纯代码 + 测试 + 门禁）⇒ `mvn -o -pl services/trade-service -am -DskipTests package`
+ `up -d --no-deps --force-recreate trade-service` 即可。存量数据不回填：已 CANCELLED 的历史任务不会自动复活，
也不需要——本轮的判据只作用于「之后发生的发运/提交/清扫」。
