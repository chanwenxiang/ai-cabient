# 四端审计 R20 报告 · 业务规则与资金全路径（2026-11-12）

> **性质**：全量，但**换轴**——R19 打的是授权矩阵/持久层/校验/定时任务/前端/基建；本轮打「业务规则与钱的流向」。
> **六轴**：A 交易状态机 · B 资金全路径 · C 营销（券/活动/会员/积分）· D 跨端一致性 · E 安全纵深 · F 性能与容量。
> **执行方式**：A/B/C/F 四路只读子代理；D/E 因额度限制由主审用 grep 自查（**覆盖面小于子代理，已在各节标注边界**）。
> **标记**：✅=主审亲验（读码/实跑）｜◐=子代理提出、主审未逐条复验｜⚠=与 R19 重叠或需拍板。
> **复验回执（同日补做）**：P1×10 与 P2×18 已**逐条亲验**，结果见 §八——P1 全部坐实；P2 中 1 条改判（R20-P2-9 疑不成立）、1 条降为待定（P2-12）、1 条措辞订正（P2-18 索引）。。
> **总账**：报出 **38 条 = P1×10 + P2×18 + P3×10**；另 6 条待定。
> **复验后有效数**：P1 **10/10 成立**；P2 **16 成立 + 1 部分成立 + 1 降待定 + 1 改判不成立**（P2-13 部分、P2-12 待定、P2-9 不成立）；P3 本轮未逐条复验。⇒ 可直接派修的为 **P1×10 + P2×16 = 26 条**。

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

1. 会员价与活动价是否叠加：`SettlementOrderSupport.java:126-161` 实现为「先会员价再以其为 base 算活动价」，而 `PROJECT_KNOWLEDGE §6.3` 与本轮前置约定写「不叠加、取优」——**两处矛盾**，需拍板后定级（当前实现会双重优惠）。
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
