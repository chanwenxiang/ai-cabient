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

### R20-6 ✅ 争议改单缺订单状态前置：CANCELLED 可扣款、PARTIAL_REFUNDED 可重复收款
- `SettlementConfirmDisputeService.java:168` 只挡 REFUNDED；`:213` 仅当原状态为 DISPUTED 才改 PAID；
  `OrderPaymentService.java:916-935` 的差额扣款不校验订单状态；`SettlementOrderSupport.java:186` 全量重算 total。
- 后果：CANCELLED 单 CONFIRM ⇒ 扣到钱但单仍取消（收入消失）；已部分退的单 CONFIRM ⇒ delta≈已退额 ⇒ **重复收款**。
- **修法**：CONFIRM 白名单 `{PAID, PENDING, DISPUTED}` 且 `refundedCents=0`；finalize 必置 PAID。

### R20-7 ✅ 购物中（SHOPPING）可被运营直接免单取消，且不释放预授权
- `OpsSessionOrderQueryService.java:218-226` 只挡终态/识别态，SHOPPING 放行 → 置 CANCELLED；无 `releaseIfFrozen`（对照 `SessionService.java:366` 的正常路径），也不建争议单；
  而 FAILED 冻结清扫器只扫 FAILED（`SessionExpireService.java:160`）⇒ CANCELLED 的 FROZEN 悬挂无人清。
- 后果：用户已取货 → 免单 + 预授权永久冻结。
- **修法**：SHOPPING 强制转 DISPUTED（与既有 F2 口径一致）并释放 hold；清扫器覆盖 CANCELLED+FROZEN。

### R20-8 ✅ 支付分回调可把终态订单改回 PAID
- `PayScoreOrderNotifyService.java:109-123` 只判「已是 PAID 就跳过」，DONE 可把 REFUNDED/CANCELLED 改成 PAID；`DisputeService.java:372` 又只挡 REFUNDED ⇒ 改回后可二次退款。
- **修法**：状态单向机 + 终态收到 DONE 只记 HIGH 异常人工处置。

### R20-9 ✅ 分账回退单槽覆盖，多轮部分退后全额退会少回退（**已修，见 §二十 / V463**）
- `RevenueSplitService.java:276-283/354-360/380-383` 每轮覆盖 `wechat_pending_return_no/cents`；`WeChatProfitSharingService.retrySingleFailedReturn:350-370` 只提交最后一轮。
- 后果：100 元单两轮部分退（30/20）再全额退 ⇒ 渠道只回退 70，商户多留 30。
- **判据**：`Σ渠道回退额 = 初始 merchant_cents − 现 merchant_cents`。

### R20-10 ✅ MQTT 回调线程内同步转发门磁事件且无超时（超时已补；异步化见 §二十一）
- `MqttEventListener.java:345` + `TradeServiceClient.java:28,38-50`：Paho 回调线程内 3 次重试、无 requestFactory 超时 ⇒ trade 抖动即阻塞 MQTT 线程、消息不 ACK，全设备门磁积压。
- **现状**：客户端超时已在 §十 补齐；剩余「回调线程内同步 HTTP」由 §二十一 收口（门磁/告警/心跳三类转发全部移到有界池，成功才 ACK 的权责随任务走）。本节保留原始发现记录。

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
2. **R20-4 / R20-10**：把渠道与内部 HTTP 全部移出事务并加超时（这条同时是 R20-1 的放大器：网关卡死时行锁与连接被占）。**R20-10 的线程面已由 §二十一 完成；此处 R20-4 的事务语义仍是独立待办。**
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
| R20-10（半 → ✅ 见 §二十一） | `device-service/…/TradeServiceClient` | 补 2s 连接 / 5s 响应超时，把 MQTT 回调线程单次阻塞上限钉死；**该轮未改异步化**（见十.2；后由 §二十一 单独一轮完成） | 构造器实读 |

**验证**：`mvn -o -pl services/trade-service -am test` 定向 63 例全绿；负向验证（拆掉 R20-3/R20-5/R20-6 三处判据）→ 5 例转红，还原后 md5 逐字节一致 + 全绿。

### 十.2 本轮**有意不改**（3 点，需拍板或需拆结构）

1. **R20-2 支付分未实收即分账**：修法要求「分账/发积分/统计一律挪到实收确认后 + 取消路径 void+clawback」。这不是补闸，是**结算主流程重排**（`tryChargeSettledOrder` 成功与否要与 PENDING 状态解耦），且与「免密支付分先享后付」的产品口径绑定 ⇒ 需先定：支付分单在 DONE 之前，商户钱包是否允许**影子入账**（LEDGER_ONLY 待确认）。**建议**：分两刀——① 先做 `MemberService.onOrderPaid` 的 orderId 去重（同时覆盖 P3-3）；② 再做钱包/分账延后。
2. **R20-9 分账回退单槽覆盖**：`wechat_pending_return_no/cents` 是**单值列**，多轮部分退需要「多条回退腿」的账。合并成一个新 `out_return_no` 有双退风险（前一条可能已在途）。**建议**：新建 `order_split_return_leg(split_id, out_return_no, cents, status)`，补偿器按腿提交/查单，判据 `Σ腿 = 初始 merchant − 现 merchant`。这是加表，不该顺手做。
3. **R20-4 移出事务**：先补超时（本轮已做）已把「无上限阻塞」消掉；把渠道调用移出 `@Transactional` 会改动 `chargeOrder` 的提交语义（现在靠事务回滚保证金货两清），需单独一轮带停机窗口验证。**R20-10 异步化已从本条拆出**，由 §二十一 单独一轮完成——它只动 MQTT 线程边界，不碰事务，与 R20-4 不是同一约束。

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

> **收口状态（2026-11-12）**：批 A 4/4、批 B 4/4、批 C 4/4、批 D 4 修 + 1 判不成立 ⇒ 16 条可派修**全部处置完毕**；
> 另 P2-9 判不成立、P2-12 已先行落地。
>
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

### 批 C｜会员/券/活动口径（5 条，P2-9 不成立、P2-12 已修；**其余 4 条已全清**）

| # | 落点 | 改法要点 | 判据 |
|---|---|---|---|
| **P2-10 ✅** | `MemberService:321-326` | 扣回积分用**发放时**倍率（流水里存 rate 或按 EARN 行反算），不用当前等级 | 单测：升级后退款按旧倍率扣 |
| **P2-11 ✅** | `MemberService:116-123` | `total_spent` 全额退款后可回收（净额口径）或至少在等级判定里改用净额 | 单测：买满升级→全退 ⇒ 等级回落（需产品确认是否允许降级） |
| **P2-13 ✅** | `PromotionPricingService:131-148` | 限购计数排除 PENDING/CANCELLED + 支持件数上限；`budget_cents` 死旋钮要么接预算要么删 | 校验 mapper 谓词（本轮待补验）；单测：取消单不占名额 |
| **P2-3/P3-3 ✅** | `MemberService.onOrderPaid` | **orderId 去重**（同 R20-2 第一刀），与积分腿同口径 | 单测：同一单两条补缴路径只记一次 |

### 批 D｜N+1 与全表扫描（5 条，**4 条已清 + P2-18 实测判不成立**）

| # | 落点 | 改法要点 |
|---|---|---|
| **P2-14 ✅** | `OpsSessionOrderQueryService:261-262` | 导出改批量 `findByOrderIdsIn`（同文件 `:285` 已有范式） |
| **P2-15 ✅** | `MarketingRoiService:142-152` | 三层 N+1 全部批量化（固定 5 次查询）；`findAll()` 改为**列下推**（只选 6 列）——**按 status/时间筛活动不做**，与 OBS-006「无券活动也入表」冲突（见 §十六.2） |
| **P2-16 ✅** | `NearbyDeviceService:40`、`SiteContractService:50/66` | 全表载入 `device_info` 改 SQL 过滤（地理/状态条件下推） |
| **P2-17 ✅** | `FootfallAnalyticsService:67,71-72` | 每设备 count → 单条 group by 聚合 |
| **P2-18 ✖** | `InvoiceRequestMapper.xml:14-22` | ~~锁查询带 `status IN` 谓词，或补 `(order_id)` 索引~~ ⇒ **谓词早已存在（2026-10-03）+ partial unique 索引已覆盖，带上谓词实测 `Index Scan using uk_invoice_order_active`；报告的 EXPLAIN 漏写 status 谓词才 Seq Scan。不加冗余索引**（见 §十六.5） |

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

---

## 十五、批 C 修复回执（2026-11-12，会员/券/活动口径 4/4）

> 四条落地。**两条是按产品拍板收口**（P2-11 等级只升不降、P2-13 预算只服务券），
> 一条**修正报告原判方向**（P2-10 报告说「用发放时倍率」，实现是「流水里存倍率」而不是「反算」——
> 反算在数学上不成立，见下）。**本轮新增迁移 1 个：V462**。

### 十五.1 P2-10｜扣回积分用发放时倍率（流水存 rate，不反算）

`MemberService.doClawbackPointsOnRefund` 原来用**退款当时**的会员等级倍率，而 `earnPoints` 用的是
**支付当时**的倍率。两者在「下单后升级、再退款」时背离：按下单时 1.0 倍发了 100 分，升到 2.0 倍后
全额退款要扣 200 分——多扣的 100 分是用户从没拿到的，而可用积分不够时会被扣到 0，
用户连原有积分一起赔进去。

改法：**V462 给 `member_points_log` 加 `earn_rate`**，`earnPoints` 落发放倍率，扣回读它；
历史行（列为 NULL）回落当前等级倍率（旧行为，不比修前更差）。

**为什么不是「按 EARN 行反算」**（报告的建议之一）：返积分是 `floor(rate × 金额)`，
用 `分 / 金额` 反算会把 1.5 倍算成 1.005（199 分订单只返 2 分时），再乘退款额就是系统性少扣。
所以必须**存**而不能**推**。也**不做回填**：回填只能填近似值，比 NULL 更危险（看起来有值、实际算错）。

判据：`clawback_usesEarnRateInsteadOfCurrentLevelRate`（1.0 倍发放、2.0 倍退款 ⇒ 扣 5 分不是 10 分）、
`clawback_fallsBackToCurrentRateWhenLegacyEarnRowHasNoRate`。

### 十五.2 P2-11｜累计消费按净额回收，等级**只升不降**（产品拍板）

旧实现 `total_spent` 只增不减：买满升级后全额退款，消费额与等级都留在原地——权益白拿，
也是刷单升级的入口。**2026-11-12 产品拍板**：消费额回收（净额口径），但**等级不降级**。

改法：`doClawbackPointsOnRefund` 里新增 `rollbackSpentQuietly(member, refundCents)`
（`total_spent -= 退款额`，夹到 0——跨期退款可能大于当前记账值，负数会污染等级判定与后台展示），
**刻意不调 `applyLevelIfChanged`**。代码里写明「这里像是漏了、实际是刻意跳过」，
避免下次审计或重构把它当缺陷补上（补上就等于改产品口径）。

判据：`refund_rollsBackTotalSpent`（100 元全退 ⇒ 0）、`refund_neverDrivesTotalSpentNegative`（1 元订单退 5 元 ⇒ 0）、
`refund_doesNotRecomputeLevel`（GOLD 退到 0 元仍是 GOLD，且**从未调用**等级重算查询）。

### 十五.3 P2-13｜限购计数排除 PENDING/CANCELLED + 预算只服务券（产品拍板）

**前半段（真缺陷）**：`CabinetOrderLineMapper` 的两条计数 SQL 只要行上有 `activity_id` 就计数，
于是「下单未支付」「下单后取消」都白占名额——`userLimit=1` 的用户只要留一张未支付单，
就再也享受不到该活动价，而这张单要等关门超时才转 CANCELLED，中间正是自助结算高峰。
改成 `JOIN cabinet_order o … AND o.status NOT IN ('PENDING','CANCELLED')`，两条 SQL 同口径。

**后半段（死旋钮，产品拍板「预算只服务券」）**：`budget_cents` 的唯一消费者是
`reserveBudgetOnClaim`/`releaseBudget`，两者都只由 `CouponService` 调用；折扣类活动的折扣在定价时
直接生效、**从不校验预算**。而后台把「预算/剩余预算/预算已满」显示在所有活动上——运营看到
「剩余 5000」会以为折扣有封顶。收口方式：

- 写入侧 `PromotionService.normalizeBudgetForType`：折扣类活动预算一律按 0 落库（不抛 400，
  存量活动再编辑不会失败），留 `log.info` 痕迹；券类照旧。
- 展示侧：`PromotionsView` 的预算/已使用/剩余预算三列与「预算(元)」表单项、`MarketingRoiView`
  的预算两列，对折扣类一律显示 `—`；`isBudgetExhausted` 对折扣类恒 false（否则会展示「预算已满」却照常打折）。
- **为什么不给折扣类接预算**：折扣额只落在订单级 `activity_discount_cents`，同一单多活动时无法
  拆分到活动，聚合必然失真；且每候选活动一次聚合会拖慢开门计价。折扣类控量走
  `userLimit` / `ruleConfig.totalOrderLimit`（单数口径）。

**件数上限**：判为**有意不同**——注释自证「每人最多 N **单**／活动总共 N **单**」，是单数口径；
活动价按件打折，件数上限属另一种商品（同 PR 不引入）。

判据：`PromotionUsageLimitPredicateTest`（反射读 `@Select` **真值**断言谓词，不用 grep——
源码 javadoc 里也写着 PENDING/CANCELLED，按文本搜会被自己的注释骗成假绿）、
`create_skuActivity_dropsBudget`、`create_couponActivity_keepsBudget`。

### 十五.4 P2-3/P3-3｜累计消费腿补上 orderId 去重（与积分腿同口径）

`onOrderPaid` 里只有积分腿幂等（`existsByMemberAndSource(…,"ORDER",orderId)` + V166 唯一索引），
消费额/订单数腿无条件累加 ⇒ 同一订单走「结算落单支付 + 欠款补缴」两条路径时 `total_spent` 与
`order_count` 双计（并通过 `applyLevelIfChanged` 推高等级）。

改法：先判 ORDER 行是否存在，存在则整段跳过。为此 `earnPoints` 在「返 0 分」时也落这一行
（`points=0`、**不置 `expire_at`** 因而不被积分过期扫描拾取，明细列表按 `points<>0` 过滤后用户看不见），
否则金额不足 1 分的单永远没有该行、去重判据失效。

判据：`onOrderPaid_whenOrderAlreadyCounted_skipsStatsAndPoints`（不再 save、消费额与订单数不动）、
`onOrderPaid_firstTime_accumulatesStats`（去重不能把正常单挡掉）、
`earnPoints_zeroPoints_stillWritesMarkerRowWithoutExpiry`。

### 十五.5 判据与验证

- 定向 **73 例全绿**（11 个类）：`MemberPointsClawbackIdentityTest`(8) / `MemberOnOrderPaidConcurrencyTest`(5)
  / `MemberStatsConcurrencyTest`(3) / `MemberServiceDepositLevelTest`(9) / `MemberLevelAdminConcurrencyTest`(2)
  / `PointsExpiryPartialTest`(2) / `PointsRedeemServiceTest`(10) / `PromotionPricingServiceTest`(13)
  / `PromotionServiceTest`(15) / `PromotionBudgetConcurrencyTest`(2) / `PromotionUsageLimitPredicateTest`(2, 新增)。
- 负向验证（铁律 4，五轮独立注入、各自转红、还原后 md5 逐字节一致）：
  ① 扣回退回「只读当前等级倍率」⇒ P2-10 用例转红（strict-stubs 直指读取点）；
  ② 注释掉 `rollbackSpentQuietly` ⇒ P2-11 两例转红（`expected: <0> but was: <-1>`）；
  ③ `existsByMemberAndSource` 前加 `false &&` ⇒ P2-3 去重用例转红；
  ④ 删掉两条 SQL 的 `status NOT IN` ⇒ `PromotionUsageLimitPredicateTest` 2 例转红；
  ⑤ `normalizeBudgetForType` 退回 `requireNonNegativeBudget` ⇒ `create_skuActivity_dropsBudget` 转红。
- 附带：`check-flyway-seed-separation` / `check-mapper-xml-binding` / `check-mybatis-null-clear` /
  `check-admin-table-align` / `check-test-mockito-pitfalls` 全绿；admin-vue `type-check` 通过。

### 十五.6 部署注意

**本轮有迁移 V462** ⇒ 必须 `mvn -o -pl services/trade-service -am -DskipTests package`
+ `up -d --no-deps --force-recreate trade-service`（jar 内迁移不随 restart 更新）；admin 产物需重建
（两个 vue 文件改动）。存量数据不回填：历史 EARN 行无 `earn_rate` 时走旧口径，历史 SKU 活动若带非零预算，
再次保存即按 0 收敛、展示侧已不再显示。

---

## 十六、批 D 修复回执（2026-11-12，N+1 与全表扫描 4/5；**P2-18 判不成立**）

> 四条落地，**一条实测证伪**（P2-18）。本轮**无新增迁移**。批 D 是性能族，
> 判据必须钉在「哪些过滤/取数发生在 SQL 侧」，因为 Java 侧返回值不变——见 §十六.6。

### 十六.1 P2-14｜按行导出：一次批量取，且**行序不变**

`exportOrdersCsv(byLines)` 在订单循环里 `orderLineRepository.findByOrderId(o.getOrderId())` ——
一页 `EXPORT_LIMIT` 单就是同量级查询（同文件 `:285` 早就有批量范式，这处没跟上）。

改法：循环外 `loadOrderLinesByOrderIds(pageOrderIds)` 一次 `IN` 取回再分组。

**但批量取有个不许丢的细节**：原实现的逐单版按 `line id ASC` 排序，而内联的
`selectList(in(...))` 不带 `ORDER BY` ⇒ 导出 CSV 的行顺序会随执行计划漂移。这是一条
**导出产物**，顺序变了用户下载下来的 diff 就变了。所以：
- `loadOrderLinesByOrderIds` 内部改用 `findByOrderIds`（内建 `orderByAsc(id)`）；
- 分组容器换 `LinkedHashMap`（`groupingBy` 默认 `HashMap`，桶内顺序虽保留但跨单顺序不可依赖）。

判据：`exportOrdersCsv_byLines_fetchesAllLinesInOneQueryAndKeepsOrder`
（一次 `findByOrderIds` + `findByOrderId` **从未**调用 + 同单内 `id` 小的行先出现 + 三行内容逐字段比对）。

### 十六.2 P2-15｜ROI 三层 N+1 → 固定 5 次查询

原实现（每个活动/每个券定义各一次）：
1. `activityRepository.findAll()` —— `SELECT *`，把 `rule_config` 等大字段一并拉回，ROI 一行都用不到；
2. 每活动一次 `findByActivityId`；
3. 每券定义**三次**：`countByCouponDefId`、`countByCouponDefIdAndStatus(USED)`、`findByCouponDefId`（拉全部实例只为拿 id 集合）；
4. 每活动一次 `findByCouponIdInAndCreatedAtAfter`（大 `IN` 查订单实体，再在内存 stream 求和）。

改法（固定 5 次，与活动数无关）：`findAllForRoi()`（只选 6 列）→ `findByActivityIdIn` →
`countGroupByCouponDefIds`（一条 group-by 出「领取/已用」）→ `sumCouponOrdersByActivitySince`
（一条 join + group-by 出「单数/营收/券面额」）→ 内存按活动归并。

**两处口径细节**：
- 订单聚合用内层 `SELECT DISTINCT` 再外层 `COUNT(*)`/`SUM`，而不是 `COUNT(DISTINCT)+SUM`：
  join 后若同一订单匹配多行，前者让「单数」与「金额」出自同一批行，后者会金额翻倍而单数正常
  ——这种分叉比崩溃难查得多。
- **报告说「`findAll()` 改筛选下推」，这一半没做**：ROI 接口只有 `days` 一个参数，按 `status`
  或时间筛活动会与 OBS-006（无券活动也要入表）冲突，运营会看到活动凭空消失。真正能下推的是
  **列**（`SELECT *` → 6 列），已做。口径变化为零。

判据：`list_shouldAggregateClaimRedeemAndRevenuePerActivity`（含 6 条 `verify(never)`：`findAll` /
`findByActivityId` / `countByCouponDefId` / `countByCouponDefIdAndStatus` / `findByCouponDefId` /
`findByCouponIdInAndCreatedAtAfter`，任何一条复活即红）、`list_sumsCouponDefsOfSameActivity_andTreatsMissingAggregateAsZero`
（多券求和 + 无聚合行的券按 0）、`list_keepsActivityWithoutCoupons`。

### 十六.3 P2-16｜附近柜机与合同列表：全表载入 → 条件下推

**（a）`NearbyDeviceService.listNearby`**：`findAllOrderByDeviceIdAsc()` 全表载回内存，
再逐个 `hasCoordinates` + 生命周期过滤 + haversine。改成分两段：
- SQL 粗筛：外接矩形 `latitude BETWEEN … AND …`（经度同理）+ 坐标非空 + 生命周期投放态 + `is_deleted = false`；
- 内存精筛：haversine 判半径（矩形是圆的超集，不会漏）。

三个坑都写进代码注释：
1. **经度跨度要除 `cos φ`**（1 度经度的米数随纬度收缩）。写反会漏掉东西向的柜机——判据专门断言
   `lonSpan > latSpan`（上海 φ≈31.23° ⇒ 0.210 > 0.180）。
2. **跨 ±180 不能硬套 `BETWEEN`**：日界线附近 `lo < -180` 或 `hi > 180` 时传 `minLon=null`，
   退化成只按纬度过滤（仍砍掉绝大部分），由 haversine 保精度。宁可少用一次索引，也不能漏柜机。
3. **`lifecycle_status IS NULL` 必须显式放行**：SQL 三值逻辑里 `NULL NOT IN (...)` 是 `NULL`，
   单写 `NOT IN` 会把 lifecycle 为空的历史柜机**整批滤掉**（`normalizeLifecycle(null)=DEPLOYED`，
   它们本该算投放中）。归一化也用 `UPPER(TRIM(...))` 与内存版 `isExcludedLifecycle` 逐字对齐。
   另：注解 SQL 不受 `@TableLogic` 管，`is_deleted = false` 必须手写。

**（b）`SiteContractService.list/listPage`**：为拿「柜机名」把整张 `device_info` 载入，两处各一次。
改成先取合同、再用当页的 `deviceId` 集合 `findByDeviceIdIn`；查不到的仍回落成 `deviceId`
（与旧 `getOrDefault` 同行为），一页空合同时连设备表都不碰。

判据：`findNearbyCandidates_pushesCoordinatesLifecycleAndBox`（反射读 `@Select` 断言 5 类谓词）、
`listNearby_pushesBoundingBoxDownToSql`（捕获矩形参数：`±半径` 的纬度总跨度 0.1799°、经度 0.2104°、
矩形以查询点为心）、`listNearby_sortsByDistance`（+ `never findAllOrderByDeviceIdAsc`）、
`listPage_loadsDeviceNamesOnlyForDevicesOnThisPage`、`listPage_withoutContracts_skipsDeviceQuery`。

**未做（明确边界）**：`listNearby` 里 `getDeviceStatus` + `listProducts` 仍是逐台调用（上限 50 ⇒
最多 100 次）。它们的批量版需要 `DeviceCatalogService` 提供「按柜机批量算商品/可用态」的门面
（含活动价与货道校验），属独立改造，不在本批塞入；报告也未点此条。

### 十六.4 P2-17｜客流：每台一次 count → 一条 group-by

`FootfallAnalyticsService.analytics` 对**每台柜机**调 `countByDeviceIdAndCreatedAtAfter`
（线下几百台就是几百次往返），且 `findAllOrderByDeviceIdAsc()` 调了**两次**（overview 计数 + 设备循环）。

改法：柜机清单取一次；新增 `ShoppingSessionMapper.countOpensGroupByDeviceSince(since)` 一条
group-by 取回 `device_id → opens`；聚合缺失的柜机按 0（照旧上榜，不能因为没开门就消失），
`device_id` 为空的脏行不进桶（不凭空造柜机）。时间窗与逐台版同口径（`created_at > since`）。

判据：`analytics_shouldComputeOverviewDevicesHourlyAndSkus`（+ `never countByDeviceIdAndCreatedAtAfter`
+ `findAllOrderByDeviceIdAsc` 只调用 1 次）、`analytics_deviceWithoutOpenRows_isStillListedWithZeroOpens`、
`countOpensGroupByDeviceSince_isSingleGroupByAggregate`（SQL 判据：group by / 别名 `opens` / 时间谓词 / 非空设备）。

### 十六.5 P2-18｜**判不成立**：谓词与索引都已具备，报告的 EXPLAIN 少写了谓词

报告 §八 的口径是「实为 partial unique `(order_id) WHERE status IN (PENDING,ISSUED)`；
`EXPLAIN … WHERE order_id='x' FOR UPDATE` 实测 `Seq Scan`」。亲验：

1. `InvoiceRequestMapper.xml` 的 `findActiveByOrderIdForUpdateRaw` **早就带** `AND i.status IN ('PENDING','ISSUED')`
   （`git log -S` 追溯：2026-10-03 的 `f030f09f` 引入，早于本次审计）。
2. 库里实测（`invoice_request` 0 行）：
   `EXPLAIN SELECT i.* FROM invoice_request i WHERE i.order_id='X' AND i.status IN ('PENDING','ISSUED') ORDER BY i.invoice_id LIMIT 1 FOR UPDATE`
   ⇒ **`Index Scan using uk_invoice_order_active`**（`Index Cond: order_id = 'X'`）。

即报告的 EXPLAIN 没带 `status` 谓词，而 partial index 的谓词是 `status IN (PENDING,ISSUED)`——
**查询谓词不蕴含索引谓词，Postgres 当然用不了它**，于是 Seq Scan。带上谓词即走索引。

⇒ **不采纳「再加一个普通 `(order_id)` 索引」**：partial unique 索引已经是该查询的最优索引，
同列同谓词再加一条普通索引只会带来写放大（发票表是低频写，但没有收益的冗余索引仍不该加）。
**本条不改代码、不加迁移。**

### 十六.6 判据与验证

- 定向 **23 例全绿**（7 个类）：`OpsSessionOrderQueryServiceTest`(5) / `MarketingRoiServiceTest`(5)
  / `NearbyDeviceServiceTest`(2) / `FootfallAnalyticsServiceTest`(2) / `SiteContractServiceTest`(4)
  / `SiteContractConcurrencyTest`(2) / `QueryPushdownPredicateTest`(3, 新增)。
- 负向验证（铁律 4，**六轮**独立注入、各自转红、还原后 md5 逐字节一致）：
  ① 导出回到逐单 `findByOrderId` ⇒ `exportOrdersCsv_byLines_…` 转红（CSV 只剩表头）；
  ② `findAllForRoi` 退回 `findAll()` ⇒ ROI 3 例转红（NPE / 期望 1 得 0）；
  ③ 附近柜机回到 `findAllOrderByDeviceIdAsc` ⇒ 2 例转红（含矩形参数捕获失败）；
  ④ 合同列表回到全表载入 ⇒ 2 例转红（设备名回落、空页仍查设备）；
  ⑤ 客流回到逐台 count ⇒ 2 例转红（开门数 0 + 多余 stub）；
  ⑥ 删 `is_deleted = false` 与 `SELECT DISTINCT` ⇒ `QueryPushdownPredicateTest` 2 例转红。
- 门禁：`check-mapper-xml-binding` / `check-mybatis-null-clear` / `check-test-mockito-pitfalls` 全绿。
- **未新增静态门禁**（明确说明）：这类缺陷是「过滤位置」问题，静态扫「循环体内调用 repository」
  需要控制流分析，简单正则假红率极高（合法的逐条重试、事务内两段式读都会被误报）。
  本轮把判据落在两层：SQL 谓词的**反射断言**（`QueryPushdownPredicateTest`，删谓词即红）
  + 服务层的 `verify(never)` 行为断言（回退即红）。SQL 注解读真值而非 grep，理由同 §十五.3。

### 十六.7 部署注意

本轮**无新增迁移**（纯代码 + 测试）⇒ `mvn -o -pl services/trade-service -am -DskipTests package`
+ `up -d --no-deps --force-recreate trade-service`。前端无改动。
存量数据无影响：四条都是取数方式变化，返回结果与改前逐字段一致（判据已按此校验）。

---

## 十七、剩余项盘点（2026-11-12，批 D 收口后复核）

> 本文档 §一 里 R20-6/7/8 原标 ◐，实为 `1a352188`（P1 首批 8 条）已修未回写标记；本轮回写为 ✅。
> 下面按「现在还剩什么」重列，判据是**代码现状**（`git log -S` 溯源 + 读现状），不是报告自我声称。

### 十七.1 剩余 P1：盘点时 2 条 → **R20-9 §二十 / R20-10 §二十一 均已收口，实剩 0 条**

| # | 状态 | 现状与剩余部分 |
|---|---|---|
| R20-9 | ~~**零落点**~~ **✅ 已修（§二十 / V463 / CB-085）** | 新建 `order_split_return_leg` 腿表，`applyWeChatReturnOutcome` 改按 `out_return_no` 幂等 upsert，六个消费点全部按腿；补偿任务 `txId` 改 `LEG:<legId>`（多腿各有独立调度槽）。守恒判据与真库 SQL 演练见 §二十。 |
| R20-10 | ~~**半修**~~ **✅ 已修（§二十一）** | 超时已补：`TradeServiceClient` 走 `OutboundHttp.callbackThreadRpc()`（2s/5s 档，出站客户端统一治理批次）。异步化本轮落地：`MqttEventListener` 三类转发（门磁/告警/心跳）全部移到 8 线程 + `ArrayBlockingQueue(256)` + `AbortPolicy` 的专用池；ACK 与幂等键随任务在工作线程执行（Paho 手动 ACK 跨线程已实证安全），队列满留未 ACK 等重投。**不采用「先 ACK + 补偿表」**，故无需加表。 |

### 十七.2 剩余 P2：0 条

18 条全部处置完：16 条实修（批 A 4 / 批 B 4 / 批 C 4 / 批 D 4），P2-9 改判不成立、P2-18 实测证伪（谓词本就存在，`Index Scan using uk_invoice_order_active`）、P2-12 已按用户裁定「不叠加/取优」落地。

### 十七.3 剩余 P3：5 条（P3-2 判说明不派修、P3-3 已由批 C 覆盖、P3-5/6/10 已由 §十八 收口）

| # | 一句话 | 派修提示 |
|---|---|---|
| 1 | 核心老表（`cabinet_order` 等）状态列无 CHECK，越界串可落库、报表静默漏行 | 新表已有 `chk_*_status`，按「老表补 CHECK」执行，需先扫存量脏值 |
| 3 | ~~补缴双路径 `onOrderPaid` 统计不幂等~~ **已修（批 C 顺带覆盖）** | `MemberService:216` 的 `existsByMemberAndSource(…,"ORDER",orderId)` 早退闸落在 `:220 applyMemberStatsDelta` **之前**且直接 `return`（整段跳过，含消费额/订单数腿）；凭据是 `earnPoints` 连返 0 分也落的那行占位。**不作为剩余项** |
| 4 | 无主/渠道不符回调静默 200，无告警痕迹 | 微信 `:359-369`、支付宝 `:501-508` 仅 `log.warn`；补 `OpsAlertDispatcher` 落痕 |
| 5 | ~~活动窗端点开/闭三处不一致~~ **已修（§十八.1）** | 以 SQL 为准；另发现并修掉「NULL 起止的活动在列表消失但能领」 |
| 6 | ~~券过期按发放时刻而非日末~~ **已修（§十八.2）** | 改发放日 + N 天的日末，`Asia/Shanghai` |
| 7 | 配置/促销策略每单 N 次查库（一单 N 行 ≥5N 查询） | 加本地缓存（Caffeine/`@Cacheable`），需定失效策略 |
| 8 | commonPool 跑阻塞 HTTP；XXL executor 默认 200 线程 vs Hikari 20 | `DeviceCommandTracker:238` 给专用 executor；`XxlJobConfig` 显式设线程数 |
| 9 | 图片无 OSS 缩略参数 + 上传上限 10MB ⇒ 移动端首屏带宽 | `product-image.ts` 加 `x-oss-process`；上传上限另议 |
| 10 | ~~`UNPAID` 文案与字典族~~ **已修（§十八.3，报告一半不成立）** | 「待付/未付」是两个业务视角，不得强行统一；只改 1 处真错 + 字典驱动 + 补族 |

> P3-2 **不要改**：`CANCELLED→DISPUTED` 是兜账 v2 刻意保留的唯一救援边（§九 #2、§十.3）。改了就堵死「迟到识别上报转争议后无钱可扣」的路径。

### 十七.4 剩余待定：5 条（6 条中 1 条已拍板落地）

1. 争议改单跌破门槛时券不返还但仍 USED（`SettlementOrderSupport:222-225`）——是否比照 CB-045 退券。
2. `ReconciliationService.runDaily:119-137` 删除重建当日对账行、人工处置仅存 audit——重跑是否允许覆盖。
3. 分账 `WECHAT_FAILED` 同号重提是否依赖微信侧幂等（`WeChatProfitSharingService:481-503`）。
4. Ops「重试」是否可能落到补货会话（取决于异常记录来源）。
5. `device_sku_price` 的 `FOR UPDATE` 只按 `device_id` 索引 ⇒ 锁该柜全部 SKU 行，并发冲突面需数据判定。

### 十七.5 建议顺序

1. ~~**P3-5 / P3-6 / P3-10**~~ **已完成（§十八）**。
2. ~~**R20-9（加表 + 按腿回退）**~~ **已完成（§二十）**——V463 腿表 + 六处按腿 + 守恒判据/真库演练。
3. ~~**R20-10 后半（异步化 + ACK 语义）**~~ **已完成（§二十一）**——复核发现「等另一个会话收口」并非硬前提（对方只改 `TradeServiceClient` 构造器，本批零文件交集、不依赖 `OutboundHttp`），故直接落地。
4. P3-1/4/7/8/9 与 5 条待定项随迭代（R20 的 P1/P2 至此全部收口）。

---

## 十八、P3 首批修复回执（2026-11-12，P3-5 / P3-6 / P3-10）

> 三条都是「口径」类，但**没有一条能照报告的建议直接改**——每处都先读了消费点与字典权威来源。

### 十八.1 P3-5｜活动窗：报告只说端点，实际还有一处更重的缺陷

| 落点 | 改法 |
|---|---|
| `PromotionActivityMapper.findActiveInWindow` | 原 `findByStatusAndStartTimeBeforeAndEndTimeAfter` 是 MP 派生的 `lt(start,now).gt(end,now)`。改为显式 `(start IS NULL OR start <= now) AND (end IS NULL OR end > now)`，与 `findPricedCandidates` 的注解 SQL **逐字同口径**；调用点 `PromotionService.listCurrentlyRunning` 同步换签名。 |
| `ConsumerMarketingService.doClaimCampaign` | `now.isAfter(end)` 改 `!now.isBefore(end)`：SQL 的 `end_time > now()` 是「到点即止」，原写法在 `now == end` 那一瞬仍可领。start 侧 `now.isBefore(start)` 本来就与 SQL 的 `<=` 一致，不动。 |

**报告没写、实读才发现的第三处**：MP 的 `lt/gt` 对 NULL 列得 NULL ⇒ 该行被整个排除；而 SQL 侧 `(col IS NULL OR ...)` 是**保留**。`PromotionActivityValidator.validateForSave` 只对 SKU 级活动强制起止，**发券类活动允许不设期限**。于是「不设起止的发券活动」在运营的「进行中活动」列表里整条消失，但 `doClaimCampaign` 对 NULL 放行 ⇒ 用户照领、运营看不见。这比端点那一秒的分歧严重得多。

**有意不改**：报告提的「DB `now()` 与 `Instant.now()` 混用」。把 `now()` 换成应用时钟传参要连带评估两处 SQL 的索引计划与 DB 会话时区，且本轮三条端点/NULL 缺陷与之无关 ⇒ 按「一次只改一个变量」留待单独一轮。

### 十八.2 P3-6｜券有效期按自然日日末

`CouponService` 新增 `expireAtEndOfDay(...)`：`LocalDateTime.now(Asia/Shanghai)` 取发放日 → `plusDays(validityDays)` → `atTime(LocalTime.MAX)` → `atZone(ZONE).toInstant()`。判据用**固定发放日 + 不同发放时刻**重载，避免测试跨日抖动。

有效期列 `validity_days INT NOT NULL DEFAULT 30` 且保存时校验 `>= 1`，无 0/负分支。`expireAt` 全仓只有这一处赋值（另两处 `setExpireAt` 属于积分流水，口径不同，不动）。

### 十八.3 P3-10｜报告**一半不成立**：「未付/待付」是刻意的两个业务视角

先查后端字典权威 `SysDictBootstrap`，结论与报告相反：

| 页面 | 业务对象 | 字典族 | 权威文案 | 原页面 | 判定 |
|---|---|---|---|---|---|
| `DeviceRentalView:49` | 设备租赁**账单** | `device_rental_bill_status` | 待付 | 未付 | ❌ 真错（且 V436 注释已记过同族手写 `VOIDED` 的坑） |
| `OrgSitesView:242` | 站点租金/流量费账单 | `site_rent_bill_status` 等 | 待付 | 待付 | ✔ 已对 |
| `WarehouseView:224` | 供应商**应付账款** | `supplier_payable_status` | **未付** | 未付 | ✔ 已对，报告误判 |

「待付」= 等**对方**付款（应收账单），「未付」= **我方**尚未付款（应付账款）。财务动作视角不同，**强行统一反而丢语义**。报告还称 `supplier_payable` 未进字典 —— 实测 `SysDictBootstrap:295` 与 `shared-dict/src/index.ts` 都早已有 `supplier_payable_status`，这条指控也不成立。

实际改动：

1. 三处手写 `el-option` 统一改 `useDictOptions(...)` 渲染（复用既有响应式 composable，遵循「勿在 setup 顶层 `dictOptions(...)`」的约定），从源头消掉漂移；`DeviceRentalView` 的文案随之自动变「待付」。
2. 补真缺口：`compensation_task_status`（`PENDING/PROCESSING/COMPLETED/FAILED`，取值来自 `CompensationTaskScheduler`）同时落后端 `SysDictBootstrap` 与前端 `shared-dict`。
3. **顺带修同族第 4 处**：`V453` 已把盘点方式遗留码 `VISIBLE` 从字典表删掉、后端权威是 `OPEN`，但前端 `shared-dict` 仍写 `VISIBLE` ⇒ `displayLabel('stocktake_mode','OPEN')` 回落「未知」。已对齐为 `{ BLIND:'盲盘', OPEN:'明盘' }`（该族只有展示消费点，无下拉回传，故无双标签风险）。

> 结论行：**不采纳**报告「统一 UNPAID 文案」的建议；**采纳**「补字典族」的部分。判据把这两种视角钉成刻意（见下）。

### 十八.4 判据与验证

| 层 | 判据 | 结果 |
|---|---|---|
| Java | `PromotionActivityWindowParityTest`（3 例）：真调 default 方法捕获 `selectList` 的 wrapper，断**生成出的 SQL 片段**（`IS NULL`×2 / `<=` / `>` / 单个 now / ACTIVE） | 3/3 绿 |
| Java | `CouponExpiryDayEndTest`（4 例）：日末时刻、自然日而非 24h 窗、同一天不同发放时刻结果一致、次日下午仍可用 | 4/4 绿 |
| 前端 | `p3-10-dict-parity.test.ts`（5 例）：三族账单=待付、应付=未付（钉住刻意差异）、`OPEN=明盘`/`VISIBLE` 回落未知、补偿任务四值、三个下拉族非空 | 5/5 绿 |
| 静态门禁 | `scripts/check-p3-window-and-dict-parity.mjs`：① 活动窗 wrapper 与 `@Select` 比较符**自动对齐**（不写白名单）；② 6 个字典族的 Java↔TS 键值自动比对（常量引用走 `CabinetConstants` 解析）；③ 三个下拉不得再出现手写中文 label | 正向绿 |
| 负向自测 | 同一脚本 `--self-test`：9 个沙箱化内存变异（删 IS NULL / `le→lt` / SQL `>→>=` / 端点退回 `isAfter` / 字典回 `VISIBLE` / 删补偿族 / 加回手写 label / **注释里写旧写法不得假红**） | 9/9 符合预期（该红则红、该绿则绿） |

**注释抹除是硬要求**：本轮每个落点都写了复述旧写法的成因注释，不 `maskComments()`（替换为等长空格、保行号）就会被自己的注释骗成假红/假绿；`--self-test` 第 9 例专门钉这一点。

> ✅ **接链状态（§二十 已收口）**：当时 `package.json` 被另一个会话的「出站客户端统一治理」批次占用，本批未改它；后续 §二十 在 `.github/workflows/ci.yml` 的 `build` job 加了 4 个真实 CI step（本门禁正向 + `--self-test`、R20-9 守恒门禁正向 + `--self-test`），以 CI 直调完成可执行接线，`node scripts/check-audit-gates-wiring.mjs` 全绿（143 个 `check-*.mjs` 全部已接线）。待那个会话收口后可再并进 `check:audit-gates` 聚合链，CI 直调保持不动即可。

### 十八.5 部署注意

- **无新增迁移**（纯代码 + 字典数据）。
- `packages/shared-dict/dist/{index.js,index.d.ts}` 已 `pnpm --filter @aicabinet/shared-dict build` 重建并随批提交（`check:shared-dist-fresh` 绿）。
- admin 三个 `.vue` 有改动 ⇒ 上线前需重建前端产物。`build:admin:typecheck`、`format:check`（针对改动文件）、`check:no-inline-dict-display` 均绿。
- 存量数据无影响：`compensation_task_status` 只是补字典展示，不回填、不改状态值；券过期口径只影响**新发**的券，历史券 `expire_at` 原样保留。

---

## 十九、R20-9 实施蓝图（2026-11-12 先出设计；**已按 §二十 落地**，本节保留为设计依据）

> 顺序里 ② 是「单独一轮带守恒 SQL 验证」。本轮把链路全读了一遍，确认它不是「补两行」，
> 先落蓝图，避免在资金路径上边做边猜。

### 十九.1 实测：单值列被 6 个类约 15 处消费

`order_revenue_split.wechat_pending_return_no / _cents` 的读写入口：

| 类 | 用途 |
|---|---|
| `RevenueSplitService.applyWeChatReturnOutcome` | **唯一写入点**，PROCESSING/FAILED 都是 `setXxx` 覆盖赋值；部分退（`resyncSplitAfterPartialRefund`）与全额退都调它 |
| `OrderRevenueSplitMapper.findPendingReturnSplits` | 扫「有 pending 回退」的 split |
| `WeChatProfitSharingService` | `refreshPendingReturn` 查单、`retrySingleFailedReturn` 取 `getWechatPendingReturnCents()` 单值、`applyReturnOutcome` 成功后清列 |
| `CompensationTaskScheduler.processProfitSharingReturnTask` | 3 处读单值判「还有没有 pending」 |
| `ProfitSharingReturnCompensationService.scheduleReturnRetry` | 判单值 + `existsPending(splitId, TASK_TYPE)` 去重 |
| `ProfitSharingReturnAlertService` | 告警金额取单值 |

**比报告更重的一处**：`scheduleReturnRetry` 以 `splitId` 作 `CompensationTask.txId`，并用 `existsPending(splitId, TASK_TYPE)` 去重 ⇒ **同一个 split 同时只允许一个 PENDING 补偿任务**。多轮部分退时第二轮即使覆盖了单值，也排不进新的补偿任务 ⇒ 那轮回退根本没人提交/查单。报告只说「金额覆盖」，实际连调度槽也被吞。

### 十九.2 表设计（V463）

```sql
CREATE TABLE IF NOT EXISTS order_split_return_leg (
    leg_id          BIGSERIAL PRIMARY KEY,
    split_id        VARCHAR(64) NOT NULL REFERENCES order_revenue_split(split_id) ON DELETE CASCADE,
    out_return_no   VARCHAR(128) NOT NULL UNIQUE,   -- 微信协议幂等键，禁纯数字业务单号（lesson #223）
    cents           BIGINT NOT NULL CHECK (cents > 0),
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/PROCESSING/SUCCESS/FAILED
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_split_return_leg_open
    ON order_split_return_leg (split_id) WHERE status IN ('PENDING','PROCESSING','FAILED');
```

🔴 **不把多轮合并成一个 `out_return_no`**：前一条腿可能已在渠道在途，合并后按新单号重提 = 双退（§十.2 已论证）。

### 十九.3 六步改法

1. `applyWeChatReturnOutcome` 改为按 `out_return_no` **upsert 一条腿**（不再覆盖 split 单值）；单值列暂时保留为「最老一条未完结腿」的派生投影，供尚未迁移的读侧兜底，全部迁完后标废弃。
2. `findPendingReturnSplits` 改为从腿表 join 出「有未完结腿的 split」，不再依赖单值列。
3. 查单/重试/清列全部按**腿**循环：`refreshPendingReturn(split, leg)`、`returnMerchantShare(..., leg.getOutReturnNo(), leg.getCents())` —— 金额只从腿读，绝不从 split 单值读。
4. `CompensationTask.txId` 从 `splitId` 改为 `legId`（或 `splitId + ':' + outReturnNo`），`existsPending` 按腿去重 ⇒ 多腿各有独立补偿任务，互不吞。**⚠ 存量 PENDING 任务的 `txId` 是 `splitId`，迁移必须处理这段兼容**（建议保留旧任务跑完，新腿用新键，调度器两种键都认）。
5. `ProfitSharingReturnAlertService` 按腿聚合未完结金额。
6. **存量回填**：`INSERT INTO order_split_return_leg(split_id, out_return_no, cents, status, …) SELECT split_id, wechat_pending_return_no, wechat_pending_return_cents, … FROM order_revenue_split WHERE wechat_pending_return_no IS NOT NULL AND wechat_pending_return_no <> ''`，`ON CONFLICT (out_return_no) DO NOTHING`；上线前先 `SELECT count(*)` 核对应迁移行数。

### 十九.4 守恒判据（这一轮的验收线）

```
Σ(leg.cents WHERE status = 'SUCCESS' AND split_id = :id)
    = 初始 merchant_cents − 现 merchant_cents
```

- 单测覆盖报告给的场景：100 元单两轮部分退（30/20）再全额退 ⇒ 三条腿 30/20/50，渠道合计 100，商户留 0；**修前**只会回退 70。
- 负向验证：把 `applyWeChatReturnOutcome` 改回单值覆盖，守恒用例必须转红（铁律 4）。
- dev 库全量守恒 SQL 作为离线门禁，红即阻断（报告 §七 #5 的同一笔钱守恒思路，先在这一族落地）。
- 部署：`V463` 是**新增表 + 回填** ⇒ `mvn -o -pl services/trade-service -am -DskipTests package` 后 `up -d --no-deps --force-recreate trade-service`，两步拆开（种子/迁移在 jar 内，铁律 41）。

### 十九.5 出蓝图时不动代码的理由（**已解除，见 §二十**）

① 资金路径，6 个消费点必须一次改齐，留半成品会让 `mvn compile` 与运行时同时挂；② 补偿任务 `txId` 语义变更需要存量兼容方案与真库演练；③ 另一个会话此刻正在工作区改 17 个 client + 多条门禁链，未收口前在同一批文件上叠加资金改动，冲突与归属都不可控。
本轮复核：待改的 7 个 Java 文件与那个会话改动的 17 个文件**无交集**（它改 client/payment 客户端与若干 service，不碰 `RevenueSplitService` / `WeChatProfitSharingService` / 补偿族 / 两个调度器），因此按蓝图独立实施，只保留 `package.json` 门禁链不动（见 §二十.5）。

---

## 二十、R20-9 修复回执（2026-11-12，**唯一剩余资损类 P1 收口**）

### 二十.1 落地范围

| 层 | 文件 | 变化 |
|---|---|---|
| 台账 | `docs/COMPETITOR_BENCHMARK.md` 新增 CB-085 | 6 家主体（微信支付/易宝/支付宝/哆啦宝/旧系统easygo/友宝UBOX，含 3 家开门柜同行），结论含采纳/不采纳/有意不同；「腿表结构、`LEG:` 键、投影口径、回填状态反推」四项明确标**自己的设计判断（无公开规格）** |
| 迁移 | `V463__order_split_return_leg.sql`（`MIGRATION_KIND: backfill`，`COMPETITOR_REF: CB-085,CB-053,CB-057`） | 新表 + `out_return_no UNIQUE` + `CHECK (cents>0)` + 状态 CHECK + 两条索引（partial 谓词完整）+ 存量回填 `ON CONFLICT DO NOTHING` |
| 域/Mapper | 新增 `OrderSplitReturnLeg` / `OrderSplitReturnLegMapper` | `findOpenLegs`（自带 `ORDER BY leg_id` + LIMIT）、`sumSuccessCents`、`sumOpenCents`、`updateOutcomeIfNotSuccess … WHERE status <> 'SUCCESS'`、`insertLegIfAbsent … ON CONFLICT DO NOTHING` |
| 服务 | 新增 `ProfitSharingReturnLegService` | **唯一写入口**：按 `out_return_no` 幂等 upsert + 重算 split 单值投影为「最老一条未完结腿」；`refreshProjection` 用 wrapper `set(col,null)` 真清列（M01） |
| 写入侧 | `RevenueSplitService.applyWeChatReturnOutcome` | 三处 `setWechatPendingReturnXxx` 覆盖赋值 → `returnLegService.recordOutcome(...)`；两处排补偿任务改传腿列表 |
| 读侧 | `WeChatProfitSharingService` | 查单/重提/清列全部按腿；金额只从 `leg.getCents()`、单号只从 `leg.getOutReturnNo()`；批量取 split 走一次 `selectBatchIds` + `LinkedHashMap`（N+1 → 一次） |
| 调度 | `CompensationTaskScheduler` / `ProfitSharingReturnCompensationService` / `ProfitSharingRetryScheduler` | `txId` 改 `LEG:<legId>`（按腿去重）；调度器 `resolveLeg` 兼容存量裸 `splitId`；定时兜底从腿表 `findOpenLegs` 扫，不再扫单值列 |
| 告警 | `ProfitSharingReturnAlertService` | 新增按腿聚合重载（`pendingReturnCents`/`openLegCount`/全部 `outReturnNo`）；旧两参数入口保留为存量文案兜底 |
| 下线 | `OrderRevenueSplitMapper` | 删 `findTop20ByWechatPendingReturnNo…` 与 `findTop20ByFailureReasonContaining…` 两个旧扫描入口（铁律 62） |

### 二十.2 相对蓝图的三处修正（都是实跑后改的，不是照抄）

1. **`split_id VARCHAR(32)` 而非 64**：dev 库实查 `order_revenue_split.split_id` 是 `VARCHAR(32)` 且为主键，腿表外键按同型落；`out_return_no` 用 `VARCHAR(128)` 留渠道单号余量。
2. **回填预期 0 行**：dev 库实跑 `COUNT(*) FROM order_revenue_split` = 36，其中 `wechat_pending_return_no` 非空 = 0，且 `cents<=0` = 0 ⇒ 本次回填 0 行；迁移 NOTES 里写明上线前须按同一 SQL 核对应迁移行数。
3. **新增「同号不同额」fail-closed**：`recordOutcome` 发现同一 `out_return_no` 已有腿但金额不同 ⇒ 抛 `IllegalStateException` 让本次退款事务回滚转人工。理由：渠道侧把同号当同一笔回退，两笔金额压进一条腿就是双退/少退。蓝图未覆盖这条，属本轮补的边界。
4. **存量兼容不靠改历史行**：新任务 `LEG:<legId>`；存量 `txId=splitId` 的任务由 `resolveLeg` 取该 split 最老开放腿跑完即止，不批量改写 `compensation_task`。
5. **守恒 SQL 的诚实边界**：`Σ成功腿 = 初始 merchant − 现 merchant` 里的「初始 merchant」**并未落列**（`merchant_cents` 会被退款重算覆盖），所以库内离线 SQL 无法直接算这一式。离线判据落地为两条真实可查的式：**① 投影一致性**（split 单值 = 最老开放腿，用 `IS DISTINCT FROM`）、**② 资金上界**（`Σ腿.cents ≤ split.gross_cents`）；守恒式本身由应用层 `ProfitSharingReturnLegConservationTest` 用内存态 fake 钉住（100 元单 30/20/50 ⇒ 三腿、合计 100、商户留 0）。**不把「库内能跑全量守恒」写成已做到**。

### 二十.3 判据与验证（数字）

- Java 定向 **59 例全绿**：新增守恒 5 例 + SQL 反射谓词 5 例，另 11 个受构造器/语义变更影响的既有测试文件共 49 例同步升级（补偿调度 7、告警 4、补偿并发 2、补偿服务 3、分账 24+2、支付分 2+1+1+3）。
- 静态门禁 `scripts/check-split-return-leg-conservation.mjs`：8 组链路判据（抹注释等长空格、方法体级判据、SQL 头注释剥离）；`--self-test` **12 个变异/防假红断言全符合 + md5 零残留**。
- SQL 真值用**反射读 `@Select`/`@Update`/`@Insert`** 断言（`OrderSplitReturnLegSqlPredicateTest`），不 grep 源码（铁律 84④）；`@Select` 的开放谓词与 V463 partial index 谓词逐字一致（铁律 88①）。
- **dev 库事务内实跑**（`BEGIN;…ROLLBACK;`，零残留）：投影不一致 0、超额 0、守恒 `10000 = 10000 → OK`；注入「删掉前两条腿」后 `10000 vs 5000 → BROKEN`，注入「投影写成最后一轮」后投影判据报 1 行；`ROLLBACK` 后腿表 0 张、样本 split 金额还原 `150|150|NULL`。
- 迁移门禁：`check-migration-safety` / `check-flyway-seed-separation` / `check-migration-number-order` / `check-mysql-isms-in-migrations` / `check-competitor-benchmark` 全绿；`check-audit-gates-wiring`（143 个门禁全部已接线）与 `check-preflight-wiring` 全绿。
- **全量回归（如实记账，未全绿）**：`mvn -o -pl services/trade-service -am test` 共 **2943 例，2939 绿 / 4 红**。4 个红是 `InventoryLotBatchDeductTest` 3 例（`409 CONFLICT` 实得 `404 NOT_FOUND` / `lot not found`）与 `JiangyiCabinetCaptureServiceTest.start_finish_cancel_areAudited`；**单跑这两个类稳定复现（7 例中 4 红）**，且这两个测试文件与其被测代码在 `git status` 里均无未提交改动、与本批 7 个 Java 文件无依赖交集 ⇒ 判为存量红（批次种子 / 审计断言），非本批引入。全量红的根因不在本轮范围内，未擅自改别人的用例。

### 二十.4 负向验证（铁律 4）

把 `ProfitSharingReturnLegService` 的 `draft.setOutReturnNo(outReturnNo.trim())` 临时改成「按 split 固定单号」（模拟旧的单槽覆盖）后跑守恒用例：**5 例中 4 例 error**（`IllegalStateException: 分账回退腿写入后读不到 outReturnNo=…`，是同号不同额 fail-closed 守卫先拦下），`BUILD FAILURE` ⇒ 判据能红。还原后 md5 `f33bfc8c8102ee5f5cbdbcbab80ecb66` 与基线逐字节一致、`grep "PSR-LEGACY-"` 全局 0 命中、复跑 59 例全绿。
⚠ 如实记账：这一注入的红来自**守卫异常**而非守恒断言本身；断言级的负向由 §二十.3 的真库 SQL 演练给出（`OK → BROKEN`），两者互补，不混为一谈。

### 二十.5 部署与遗留

- **V463 含回填 INSERT**：必须 `mvn -o -pl services/trade-service -am -DskipTests package` 后 `up -d --no-deps --force-recreate trade-service`，两步拆开（迁移在 jar 内，铁律 41）；上线前按迁移 NOTES 的 SQL 核对回填行数。
- **补偿任务双键并存**：新任务 `LEG:<legId>`，存量 `splitId` 任务由调度器兼容跑完，不需要数据迁移脚本。
- **门禁接线改用 CI 直调，不碰 `package.json`**：另一会话仍占用 `package.json`（5 insertions / 2 deletions + 多个未跟踪脚本），整文件提交会带走别人半成品。本批在 `.github/workflows/ci.yml` 的 `build` job 加 4 个真实 step（R20 P3 门禁 + 自测、R20-9 守恒门禁 + 自测），顺带把 §十八 遗留的 P3 门禁未接线一并收口。`node scripts/check-audit-gates-wiring.mjs` 现全绿（143 个 `check-*.mjs` 全部已接线）；负向夹具按新版规则 3 抽成具名 `selfTest()` 并显式调用（剥注释后同时出现 `selfTest(` 与 `--self-test`）。等那个会话收口后，可再把这 4 步并进 `check:audit-gates` 聚合链，CI 直调保持不动即可。
- ~~**R20-10 后半仍未做**~~ **已由 §二十一 收口**：本条当时写的「需等那个会话收口」经复核并非硬前提——对方只改 `TradeServiceClient` 构造器，本批零文件交集且不依赖 `OutboundHttp`，故直接落地。
- `check-migration-immutable` 当前对 `V459` 报红，是**本轮之前**其他会话改写已应用迁移所致，与本次 V463 无关；按铁律 50 不重写、不 `flyway repair`，需由该会话自行收口。

---

## 二十一、R20-10 后半修复回执（2026-11-12，MQTT 回调线程零阻塞）

> 结论：**R20 的 P1 剩余项全部收口**。上一轮把「等另一个会话收口」写成硬前提；本轮复核发现该前提不成立（二十一.1），故按 §十七.5 第 3 项落地。

### 二十一.1 为什么可以不等那个会话（推翻上一轮的保守判断）

- 复核 `git diff -- services/device-service/…/TradeServiceClient.java`：并发会话只改了**构造器**一行——把裸 `SimpleClientHttpRequestFactory` 换成 `OutboundHttp.callbackThreadRpc()`（2s/5s 档）；`notifyDoorEvent` 的方法签名、3 次退避重试、`Thread.sleep(200·attempt)` **均未动**。
- 本批**完全不碰** `TradeServiceClient`，也不 import `OutboundHttp`；改动只在 `device-service/mqtt` 包 + `DeviceMqttMetrics`。两个会话零文件交集。
- 提交后 CI 检出的是本批版本：`common-core` 里没有 `OutboundHttp`（对方未提交）、`TradeServiceClient` 是基线版（裸 factory）、`MqttEventListener` 不依赖它 ⇒ **编译自洽**；对方后续提交 `OutboundHttp` + `TradeServiceClient` 也不影响本批代码。
- **Paho 线程约束实证**：`javap -c` 反编译 1.2.5 的 `CommsCallback.messageArrivedComplete(int,int)`，qos=1 分支只做 `clientComms.internalSend(new MqttPubAck(messageId), new MqttToken(...))`，**不碰回调线程的 `messageQueue`、无「必须在回调线程」断言** ⇒ 「工作线程发 ACK」安全，C11 的不丢消息语义可保留，因此**不需要**「先 ACK + 落补偿表」。device-service 无 JDBC，也就不必为此加表。

### 二十一.2 落地范围（修「这一类」，不是只修门磁）

| 面 | 改动 |
|---|---|
| 阻塞面 | `MqttEventListener.defaultForwardExecutor()`：8 线程 / `ArrayBlockingQueue(256)` / **AbortPolicy**（Paho QoS1 `maxInflight=10`，并发 8 留 2 余量）；`@PreDestroy` 温和 `shutdown()` |
| 唯一提交口 | `submitForward(topic, message, task)` 只做 `forwardExecutor.execute(task)`；捕获 `RejectedExecutionException` ⇒ 记 `device.mqtt.forward{result=rejected}`、**不 ACK、不计 `deliveryFailures`**（背压不是处理失败，避免连续 3 次被推到「ACK 丢弃」阈值） |
| 门磁 | `handleDoorEvent` 只做解析/设备号校验/去重/限流/构造 DTO；`forwardDoorEvent` 在工作线程里 `HTTP → deduplicator.mark → ACK`，失败走 `onProcessingFailure` 不 ACK |
| 告警 | `handleAlert` 只做校验；`forwardAlert` 在工作线程里 `notifyOpsAlert → ACK`，失败不 ACK |
| 心跳 | `handleHeartbeat` 在回调线程解析字段；任务里 `forwardHeartbeat`（自身吞 trade 不可达）→ ACK |
| 构造器 | 8 参生产构造器显式 `@Autowired`（铁律 71），新增 9 参包级重载供测试注入 `Executor` |
| 指标 | `DeviceMqttMetrics.recordForwardRejected()` + `device.mqtt.forward{result=rejected}` |

> `CallerRunsPolicy` **刻意不用**：它会把 HTTP 压回 Paho 回调线程，等于没修；队列满的正确行为是「留未 ACK 等 broker 重投」。

### 二十一.3 判据与验证（数字）

- **Java 定向 34 例全绿**；`mvn -o -pl services/device-service -am test` **全量 62 例全绿 / BUILD SUCCESS**（另 common 13 例）。
- 新增 `MqttEventListenerAsyncForwardTest` 6 例：
  1. **核心线程边界**：用 latch 卡住 `notifyDoorEvent`，断言 `messageArrived` 所在 `Future.get(1s)` 已返回、HTTP 线程名为 `mqtt-forward-test`——改回同步必然超时；
  2. ACK 严格排在 HTTP 成功之后；
  3. 转发失败不 ACK（`spy(DeviceMqttMetrics)` + `timeout(2000)` 先等工作线程进 catch，避免判据自身竞态）；
  4. 队列满：3 次拒绝都不 ACK、不调 trade、`rejected=3`、`failure=0`；
  5. 门磁/心跳/告警三类都提交到 executor（同族覆盖，铁律 75）；
  6. 生产池硬约束（8/8 线程、`ArrayBlockingQueue` 256、`AbortPolicy`）——**只跑 direct executor 的旧用例时改回同步照样全绿，这条专门防「只钉一侧」**。
- 既有 `MqttEventListenerDoorTest`(14) / `EdgeCloudMqttContractTest`(10) / `EdgeCloudMqttIntegrationIT` 三个构造点注入 `Runnable::run`，保持原有语义用例的同步确定性；`TradeServiceClientRetryTest`(4) 的 3 次重试行为**未改**（重试仍在客户端，只是换线程跑）。
- 静态门禁 `scripts/check-mqtt-callback-no-blocking-rpc.mjs`：8 组判据（抹注释等长空格 + 方法体级判据），`.github/workflows/ci.yml` 加 2 个直调 step 接线（不碰并发会话占用的 `package.json`）。`--self-test` **12 个变异 + 防假红 + md5 零残留**；每条变异都要求命中**指定判据关键词**，且变异本身保持语法完整（防「把源码改崩导致假红」，铁律 3）。

### 二十一.4 负向验证（铁律 4，真实源码注入）

把 `submitForward` 里的 `forwardExecutor.execute(task)` 临时改成 `task.run();`（模拟实现退回同步）：

- 静态门禁 **exit 1**，精准报 `submitForward 必须真的把任务交给 executor，不能用 task.run() 同步执行`；
- `MqttEventListenerAsyncForwardTest` **6 例中 3 例红**，含 `r20_10_callbackThreadReturnsWhileDoorHttpStillBlocked`（1.017s 超时，正是回调线程被 HTTP 卡住）⇒ 断言级与判据级都能红。
- 还原后 md5 `f9ab89d8179e3215ef6799b6e5a3a1c9` 与注入前逐字节一致、`grep PSR20-10-NEGATIVE-INJECTION` 全局 **0 命中**、门禁/自测/6 例复跑全绿。

### 二十一.5 遗留与部署

- **无新表、无迁移、无配置键**：纯代码 + 测试 + 门禁，部署只需重建 `device-service` 镜像（`up -d --no-deps --force-recreate device-service`），不涉及 Flyway。
- **3 次重试仍在 `TradeServiceClient`**：单次最坏 3×(2s+5s)+0.6s ≈ 21s，但占的是**转发工作线程**而非 Paho 回调线程；8 条并发在途 + 队列满留未 ACK，不会再造成全设备门磁积压。是否把重试收敛为「单次 + broker 重投」是独立产品口径，本轮不动。
- **`check-audit-gates-wiring` 当前红**：失败列表只有并发会话新增的 4 个半成品脚本（`check-bare-localdate*`、`check-ledger-immutability*`）「没有可执行接线」；本批新门禁不在其中 ⇒ 接线与负向夹具均已被识别。按铁律 61 不代修对方半成品。
- **`package.json` 仍被并发会话占用**（22:07 复核 mtime 21:50），本批继续用 CI 直调；等其收口后可把 §十八 / §二十 / §二十一的 6 个 step 并进 `check:audit-gates` 聚合链，CI 直调保持不动即可。
- 剩余非 P1 项见 §十七.3（P3 5 条）与 §十七.4（待定 5 条）；**R20 的 P1/P2 至此全部收口**。

