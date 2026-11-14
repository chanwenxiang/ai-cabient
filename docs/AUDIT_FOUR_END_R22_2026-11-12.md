# 四端审计 R22（2026-11-12）——出站集成失败语义 + 数据生命周期 + 时间/单位口径

> 锚点：`4e230651`（R21 报告提交，本轮起点）。
> 本轮**只发现问题，不改代码**（用户口径：「你只管发现问题，我让其他人修改」）。
> 方法：按 R19/R20 的深读法——整方法体读到边 + 只读 SQL 取证，不做清单式 grep 计数。
> 子代理：**只开一个**（用户指令）。第一次派发因额度中断、输出 0 token（无内容可用）；
> 第二次派发同一轴跑完并交付 6 条 + 3 待定。「出站失败语义」轴由子代理产出、主审逐条亲验；
> 「数据生命周期」「时间/单位口径」两轴由主审自查。

---

## 〇、结论速览

| 编号 | 级别 | 一句话 | 来源轴 | 亲验 |
|------|------|--------|--------|------|
| R22-1 | P2 | 微信**充值**退款进 PROCESSING 后永不被对账推进（写入用空格、解析按 `#`） | 出站 | 对 |
| R22-2 | P2 | 短信**先烧冷却/小时额度、后发送**；provider 失败即白扣额度 | 出站 | 对 |
| R22-3 | P2 | 补货后自动同步货道**硬编码 floorNum=1** 且异常只 warn（与手动面板可选 1–10 层不对称） | 出站 | 对 |
| R22-4 | P2 | 商户账单按日聚合用 **UTC 日**（`to_char` 未 `AT TIME ZONE`）；月界整笔丢、月合计少计 | 时间口径 | 对（实测 4 笔跨日） |
| R22-5 | P2 | 通用数据管理通道可**物理删除** `recharge_order`（充值页常驻删除按钮）与全部资金流水表 | 生命周期 | 对 |
| R22-6 | P2 | `order.unpaid.auto_cancel_hours=0` 连带**短路** CHARGE_PENDING 资金未知态巡检 | 出站/配置 | 对 |
| R22-7 | P3 | 将邑柜内采集**无本地会话留痕**：§7.15 读超时 / §7.16–7.18 555 后无从收尾 | 出站 | 对（定档待厂商行为） |
| R22-8 | P3 | 支付宝渠道**无自动对账**，且 `AlipayPlatformBillProvider` 空账单静默返回（与微信 fail-fast 相反） | 生命周期/资金核对 | 对（危害面含前提） |
| R22-9 | P3 | `device_temperature_reading` 无保留策略、无去重：实测 4 机 11 天已 55,287 行 | 生命周期 | 对 |
| R22-10 | P3 | 4 处业务日窗口用 `ZoneId.systemDefault()` 而非 `ScheduleZones.ZONE`（依赖容器 `TZ`） | 时间口径 | 对（当前无害，漂移隐患） |

可派修：**10 条**（P2×6、P3×4）。

---

## 一、P2 明细

### R22-1｜微信充值退款 PROCESSING 流水永远无法被对账推进

**落点**
- 写入侧：[PaymentService.java:975-991](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/PaymentService.java#L975)（`recordRechargeRefundOperation`）、[PaymentService.java:836-837](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/PaymentService.java#L836)（`refundWeChatPartial` 传 reason）
- 读取侧：[WeChatRefundReconciler.java:94-107](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/WeChatRefundReconciler.java#L94)（`resolveOutTradeNo`）、[:46-61](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/WeChatRefundReconciler.java#L46)（过滤条件与跳过分支）

**证据**
```java
// 写入：order_id 置空（V98 FK），充值单号用「空格」拼在 reason 尾部
op.setOrderId(null);
String withRechargeId = base.contains(order.getOrderId()) ? base : (base + " " + order.getOrderId());
// refundWeChatPartial 的 reason = reasonOrDefault(reason) + " [refund:" + refundStatus + "]"
```
```java
// 读取：order_id 为空时只按「#」解析
int hash = reason.lastIndexOf('#');
if (hash >= 0 && hash + 1 < reason.length()) { ... }
return null;   // ⇒ log.warn("cannot resolve out_trade_no") + continue
```
`WeChatRefundReconciler` 类注释自证契约是「从 reason 尾部 **`#orderId`** 解析」（[:21](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/WeChatRefundReconciler.java#L21)），而写入侧用的是空格 ⇒ **同一份代码两侧对同一约定不一致**，不是文档臆测。

**触发条件**：一笔微信充值走原路退款（`refundRecharge`/`refundWeChatPartial`），`createRefund` 返回 `PROCESSING`（零钱/信用卡异步退款常见）。

**后果（资金方向对用户不利）**
- 顺序是「先短事务扣余额 → 调渠道 → 推进单据 REFUNDED」（[PaymentService.java:620-639](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/PaymentService.java#L620)），所以 PROCESSING 期间**用户余额已扣、单据已显示已退**。
- 若渠道最终 `ABNORMAL`（钱没退回微信），唯一的自动兜底就是这个查单器；它对该行**恒跳过** ⇒ 既不会把 reason 里的标记推进，也不会触发 [:71-75](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/WeChatRefundReconciler.java#L71) 的 `WECHAT_REFUND_ABNORMAL` 告警 = **幽灵扣款且无人知晓**。
- 订单退款腿不受影响（`order_id` 非空走首分支）——缺陷只在**充值**退款。

**最小复现**：真实（或 mock 到返回 PROCESSING 的）微信退款一笔 PAID 充值 ⇒ `payment_operation` 出现 `order_id IS NULL AND channel='WECHAT' AND reason LIKE '%[refund:PROCESSING]%' AND gateway_trade_no IS NOT NULL`；跑 `RefundRetryScheduler.run()`（或对账调度）→ 该行状态不变，日志刷 `cannot resolve out_trade_no`。
**建议方向**：让 `resolveOutTradeNo` 兼容「空格分隔的尾部充值单号」，或给充值退款流水写一个 `#orderId` 尾巴；无论哪种，**两侧必须同源**。别只加日志。

---

### R22-2｜短信在真正下发前就消费了 60s 冷却与小时额度

**落点**：[SmsCodeService.java:61-77](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/sms/SmsCodeService.java#L61)

**证据**
```java
@Transactional
public void sendCode(String phoneNumber) {
    assertSendAllowed(normalized);          // 读 Redis 冷却键 + 小时计数
    String code = generateCode();
    persistCode(normalized, code, expiresAt);   // DB：异常会回滚
    markSendSucceeded(normalized);              // 🔴 Redis 冷却 + 小时计数：不回滚
    ...
    smsSender.send(normalized, code);           // 真实 HTTP，失败抛 IllegalStateException
}
```
`markSendSucceeded` 写的是 Redisson bucket/atomiclong（[:144-152](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/sms/SmsCodeService.java#L144)），`assertSendAllowed` 读同一组键并抛 429（[:132-141](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/sms/SmsCodeService.java#L132)）。方法名叫 "SendSucceeded" 却在发送**之前**调用。

**触发条件**：`AliyunSmsSender`（HTTP≥400 或 `Code!=OK`，含流控 `isv.BUSINESS_LIMIT_CONTROL`）/ `RoutingSmsSender` 未配置 provider 时抛异常。

**后果**：一条都没发出去的码，照样把用户锁进 60s 冷却；provider 抖动期间连点 5 次即触发小时封禁（`SEND_MAX_PER_HOUR=5`）⇒ **消费者登录/开门拿不到验证码**。DB 侧不残留待校验行（`persistCode` 随事务回滚），这点是对的。

**最小复现**：非 mock、阿里云凭据失效；连续 6 次调用发送端点 → 前 5 次各自报错但把小时计数加到 5，第 6 次返回 429 `SMS_SEND_LIMIT`，而 `sms_verification_code` 里没有对应行。
**建议方向**：把额度消费移到发送成功之后（或对失败做补偿删除冷却键/回退计数），并保证「同一手机号一次失败重试」不被自己上一次的额度挡住。

---

### R22-3｜补货后自动同步将邑货道：层号硬编码 1 + 失败只 warn

**落点**：[JiangyiRestockService.java:186-205](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiRestockService.java#L186)

**证据**
```java
public void syncAfterRestock(String deviceId) {
    if (!autoRestockSync) return;
    try {
        ...
        Preview preview = preview(deviceId);
        List<SubmitLine> lines = preview.lines().stream()
                .map(l -> new SubmitLine(l.barCode(), l.stockNum(), l.salePriceCents())).toList();
        submit(0L, deviceId, new SubmitCommand(1, lines, true));   // 🔴 floorNum 恒为 1
    } catch (Exception e) {
        log.warn("jiangyi auto restock sync skipped deviceId={}: {}", ...);   // 🔴 无告警入队
    }
}
```
三条独立证据链把它钉成缺陷而非「设计如此」：
1. `SubmitCommand` 第一个字段就是 `floorNum`（[:82](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiRestockService.java#L82)）。
2. **手动**面板允许运营选 1–10 层，并在确认文案里写明「向将邑提交**第 N 层**共 X 行最终库存；**该层未列出的商品会被下架**」（[DeviceJiangyiRestockPanel.vue:98](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/components/device/DeviceJiangyiRestockPanel.vue#L98)、[:149](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/components/device/DeviceJiangyiRestockPanel.vue#L149)）。⇒ 我方代码自己承认 §7.11 是**按层全量覆盖 + 未列出即下架**语义。
3. `preview()` 的取数**完全没有层维度**（只按 `device_sku_inventory` 全柜 SKU 聚合，[:88-118](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiRestockService.java#L88)），而厂商侧货架模型是分层的（客户端解析 `totalFloorNum`/`floorNum`，`JiangyiRestockClient` VendorShelf）。

**后果**：
- 多层柜：每次补货后自动把**整柜库存**写成「第 1 层最终库存」，其余层商品被厂商侧下架 ⇒ 厂商在售面与我方台账背离，且**只有一条 warn**。
- 单层柜（现网真柜形态）：写覆盖语义正确，但**失败仍然静默**——厂商 5xx/超时/`identifier` 未登记时，两侧货道从此不一致，运营台无任何待办可追（同文件其余路径都走 `opsAlertDispatcher`/`opsExceptionService`，这是范式偏离）。

**最小复现**：把 `jiangyi.auto_restock_sync=true`，stub §7.11 返回 555 或让 `submit` 抛异常，补一次货 → 日志仅 warn，`ops_exception` 无新行。
**建议方向**：①失败必须进运营异常/告警（对齐同文件范式）；②层号要么按柜型取真实层、要么显式声明「本通道仅支持单层柜」并在非单层时拒绝+告警，而不是静默写第 1 层。

---

### R22-4｜商户账单按日聚合使用 UTC 日：日错位 + 月界整笔丢失

**落点**
- SQL：[CabinetOrderLineMapper.xml:221](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/resources/mapper/CabinetOrderLineMapper.xml#L221)、[:235](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/resources/mapper/CabinetOrderLineMapper.xml#L235)（销售额按日）、[:242](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/resources/mapper/CabinetOrderLineMapper.xml#L242)、[:257](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/resources/mapper/CabinetOrderLineMapper.xml#L257)（退款按日）
- Java：[MerchantFinanceService.java:265-267](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantFinanceService.java#L265)（窗口）、[:297-319](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantFinanceService.java#L297)（按日骨架累加）

**证据**
```sql
SELECT to_char(o.created_at, 'YYYY-MM-DD') AS c0, ... GROUP BY to_char(o.created_at, 'YYYY-MM-DD')
```
```java
Instant start = ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();   // 上海月窗口
...
LocalDate d = ym.atDay(1);
while (!d.isAfter(ym.atEndOfMonth())) {          // 上海日骨架
    String key = d.toString();
    Object[] s = salesByDay.get(key);            // 🔴 桶标签是 UTC 日
    ... orderCount += dayOrders; salesCents += daySales;   // 合计只从骨架来
}
```
PostgreSQL 会话时区实测为 **UTC**（`SHOW timezone` → `UTC`），故对 `timestamptz` 做无 `AT TIME ZONE` 的 `to_char` 得到的是 **UTC 日**。同仓已有正确写法可对照：[SmsDeliveryProbeScheduler.java:46](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/SmsDeliveryProbeScheduler.java#L46) 用的是 `created_at at time zone 'Asia/Shanghai'`。

**实库取证（只读，dev 栈 `aicabinet` 库）**
```
 utc_day  |   sh_ts
----------+------------------
 2026-11-02 | 2026-11-03 07:11   ← 4 笔订单：上海 11-03 07:11–07:22，被标成 11-02
```
即**每笔上海 00:00–07:59 的成交都会记到前一天**。

**后果（两档）**
1. **错日归属**（已在实测数据发生，4/36 笔）：商户看账单时把前一天的钱算进当天，日行金额与真实经营日不符。
2. **月界丢数**（机制已确认，当前数据未命中）：上海每月 1 日 00:00–07:59 的成交，桶标签落在**上月最后一天**，不在骨架 `[本月1日, 月末]` 内 ⇒ `salesByDay.get(key)` 取不到 ⇒ 这些单**既不进日行也不进月合计**（`orderCount/salesCents/cogsCents/refund` 全部只在骨架循环里累加）⇒ 商户月度账单**系统性少计**。退款腿同理。
   - 实测本月边界窗口 `boundary_orders=0`，所以「今天没有丢」——**这是机制缺陷而非当前数据事故**，派修时按机制判级即可。

**最小复现**：造一笔 `created_at = 2026-12-01 00:30+08` 的 PAID 订单（金额非 0），调商户账单 `month=2026-12` → 日行无该单、月合计不含该单；把时间改成 `08:30+08` 则正常出现。
**建议方向**：SQL 侧统一 `to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD')`；Java 侧把 `ZoneId.systemDefault()` 换成 `ScheduleZones.ZONE`（见 R22-10），两者必须同源，只改一边会把错位从「跨日」变成「跨日+跨窗」。

---

### R22-5｜通用数据管理通道可物理删除充值单与全部资金流水表（且删除侧审计不留原值）

**落点**
- 判据：[AdminDataManageService.java:184-208](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/AdminDataManageService.java#L184)（`deleteProtectedTables` 只把「删除可能被下游 RESTRICT 挡住」的**父表**判为不可删）、[:329-343](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/AdminDataManageService.java#L329)（`deleteRow`）
- UI：[RechargeListView.vue:51](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/views/recharges/RechargeListView.vue#L51)（`manage-table="recharge_order"`）+ [CrudTable.vue:572-583](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/components/CrudTable.vue#L572)（`canDataDelete` 为 true 就常驻「删除数据」）

**实库取证（只读，直接复刻 `deleteProtectedTables` 的递归 CTE）**
```
UI 挂了的 8 张表里，判为「可删」：ops_exception / recharge_order / repair_ticket / user_account
（user_account 由 EXCLUDED_TABLES 兜住，前端 caps 取不到 ⇒ 不渲染按钮 —— 这点是 fail-closed，正确）

按「资金/账本」语义过滤，未进入保护集（= 可被 DELETE /api/v2/ops/admin/data/{table}/{id}）的表 25 张，含：
payment_operation、wallet_ledger、order_revenue_split、commission_clawback、member_points_log、
balance_refund_request、balance_refund_allocation、merchant_settlement_bill、merchant_fee_ledger、
machine_payee_bill、line_commission_daily、staff_commission_daily ...
```

**后果（三条，按可达性排序）**
1. **有 UI 入口的那条最要紧**：`recharge_order` 无任何子表外键指向它 ⇒ 判为可删，充值记录页常驻删除按钮，`ops:data:manage` 持有者点一下就物理消失。删掉之后：
   - 会员储值等级口径 `MemberService.netRechargeOf` 是**实时 SUM(recharge_order)**（[:146-156](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/MemberService.java#L146)）⇒ 累计充值凭空变小；
   - 对账规则「充值退款合计不得超过原单」的上限就是 `r.amount_cents`（[DataConsistencyService.java:680-693](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/DataConsistencyService.java#L680)）⇒ 原单没了，这条**超退检测对象随之消失**；
   - 而 `payment_operation`/`wallet_ledger` 未动 ⇒ 余额↔流水一致性检查**照常绿**。即删凭证不会报警。
2. **API 面（无 UI 也可达）**：任何持权限者可删 `payment_operation`/`wallet_ledger`/`member_points_log`。其中 `member_points_log` 的「**禁止 DELETE**」是被代码写死的不变量——[GrowthLogArchiveScheduler.java:58](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/GrowthLogArchiveScheduler.java#L58) 注释「与 `member.available_points` 对账依赖完整流水，禁止 DELETE」——但**没有任何东西在删除通道上执行它**（不在 `EXCLUDED_TABLES`、无 FK 保护）。同文件 `deleteInBatches` 还留着一条永不被调用的 `member_points_log` 删除分支（[:95-111](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/GrowthLogArchiveScheduler.java#L95)）= 一把上了膛的枪。
3. **删除不可还原**：`DATA_UPDATE` 已按 CB-021 记 before/after（[:354-377](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/AdminDataManageService.java#L354)），而 `DATA_DELETE` 只记 `pk=xxx` ⇒ 事后既不知道删了多少钱、也无法重建。

**建议方向**（不在本轮实现）：资金/账本类表加入 `EXCLUDED_TABLES`，或按语义前缀（`*ledger`/`*operation`/`*split`/`*_bill`/`*_points_log`）推导；删除侧审计补 before 全值（复用 `selectRowAsText`）；`deleteInBatches` 的积分分支删掉，避免下次有人顺手接上。

---

### R22-6｜`auto_cancel_hours=0` 会连带关掉「渠道扣款未知态」巡检

**落点**：[UnpaidOrderService.java:237-264](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/UnpaidOrderService.java#L237)

**证据**
```java
public int autoCancelExpired() {
    int hours = systemConfigService.getInt(UNPAID_AUTO_CANCEL_HOURS, 48);
    if (hours <= 0) {
        return 0;                         // 🔴 直接返回，后面的巡检不执行
    }
    ... 关单循环 ...
    // L2-1：顺带巡检渠道扣款痕迹（CHARGE_PENDING 是 H41 事务外痕迹，正常几秒内收口）
    try { alertStaleChargePendingOps(); } catch (Exception ex) { log.warn(...); }   // 第 260 行
    return n;
}
```
- 字典把 `0` 定义成**受支持的运营配置**：`system_config` 实测 `order.unpaid.auto_cancel_hours = 48`，描述字面「待支付订单超时自动关单小时数, **0=关闭**」（种子见 [SystemConfigService.java:886](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java#L886)）。
- `alertStaleChargePendingOps()` 是 CHARGE_PENDING（渠道扣款结果未知）的**唯一自动出口**：满 60 分钟未收口即报 `CHARGE_PENDING_STALE` HIGH 异常转人工（[:311-338](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/UnpaidOrderService.java#L311)），调用方只有这一处。
- **同族对照证明这是遗漏而非设计**：催缴腿遇到 `hours<=0` 时写了兜底——「自动关单已关闭 ⇒ 单子会一直 PENDING。此时仍需催缴」并回落 72h（[:279-284](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/UnpaidOrderService.java#L279)）；巡检腿没有同样处理。
- 调度本身每 15 分钟跑（[UnpaidOrderScheduler.java:42-53](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/UnpaidOrderScheduler.java#L42)），不受该开关影响，所以现象是「任务 SUCCESS、summary 却只字未提巡检没跑」。

**后果**：运营为「先别自动关单」把值调成 0（很自然的动作），资金未知态告警同时停摆，且**无任何信号**提示两者相关。渠道回执丢失/进程中断的单子从此只能靠人碰运气发现。

**最小复现**：`UPDATE system_config SET config_value='0' WHERE config_key='order.unpaid.auto_cancel_hours'`（测试库）+ 造一条 `status='CHARGE_PENDING'` 且 `created_at` 早于 60 分钟前的流水 → 跑若干轮 unpaid-cancel 任务 → `ops_exception` 无 `CHARGE_PENDING_STALE`；把值改回 48 立刻出现。
**建议方向**：把 `alertStaleChargePendingOps()` 从 `autoCancelExpired()` 里拆出来独立调度（或至少移到 `hours<=0` 早退之前），语义无关的兜底别搭另一条腿的车。

---

## 二、P3 明细

### R22-7｜将邑柜内采集无本地会话留痕 ⇒ 超时/555 后无法收尾

- [JiangyiCabinetCaptureService.java:78-83](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiCabinetCaptureService.java#L78)：`start()` 先调厂商、**成功后才** `appendLog`；`finish`/`cancel`（[:91-104](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiCabinetCaptureService.java#L91)）无任何 catch。全程不落本地表（类设计即「解析 + 透传 + 审计」）。
- [JiangyiRestockClient.java:301-308](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/client/JiangyiRestockClient.java#L301)：§7.15 请求体只有 `{deviceIdentifier, productId}`，**无 requestId/幂等键**（对照同文件 §7.11 `submitRestock` 必带 `requestId`，[:199-238](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/client/JiangyiRestockClient.java#L199)）。
- [JiangyiRestockClient.java:340-374](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/client/JiangyiRestockClient.java#L340)：`execute` 只对 401/403 重试，其余 `status!=200`（含已知厂商 555）原样抛。
- 后果：§7.15 已被厂商受理但我方读超时（`InternalRestClientFactory` 10s/30s）⇒ sessionId 从没落到任何地方，而厂商侧「同一柜只允许一个采集会话、约 15 分钟占用」；运营再点「开始」被单会话约束挡住，又拿不到第一条 sessionId 去取消 ⇒ 采集通道被锁到自然超时。555 结束/取消失败同形。
- **待定**（决定定档）：厂商 §7.15 对「重复 start」究竟是**拒绝**还是**幂等返回同一 session**。若幂等返回，本条降为说明；需厂商答复或真机双点验证。

### R22-8｜支付宝：无自动对账 + 空账单静默

- [ReconciliationScheduler.java:57](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/ReconciliationScheduler.java#L57) 硬编码 `runDaily(null, yesterday, "WECHAT")`；ALIPAY 只能运营手工触发（`OpsCommercialFacade`/`OpsFinanceAdminService`）。
- 渠道**确实可达**：支付宝开门落地页恒定该渠道（[CabinetOpenLandingController.java:81](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/api/CabinetOpenLandingController.java#L81)），代扣在有配置时被选中（[OrderPaymentService.java:821](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/OrderPaymentService.java#L821)）。
- 实库取证：`payment_reconciliation` 只有 `WECHAT`(13 次) 与 `BALANCE`(1 次)，**ALIPAY 从未有行**；其中 WECHAT 13 次里 **9 次两侧全为 0**（`ledger_total=0 AND platform_total=0`）却记 `status=MATCHED`。
- [AlipayPlatformBillProvider.java:47-51](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/reconciliation/AlipayPlatformBillProvider.java#L47)：拿不到 `bill_download_url` 时 `log.warn` + **`return List.of()`**；而微信侧同名 provider 明确 fail-fast（[WeChatPlatformBillProvider.java:43-47](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/reconciliation/WeChatPlatformBillProvider.java#L43) + 注释「Empty bill download is disabled to avoid silent MISMATCH」）。⇒ 两个 provider 对「账单没出来」的语义相反。
- 后果：支付宝启用收款后没有任何自动核对；且真去跑时，账单缺失会伪装成两种形态——当日无流水 ⇒ `MATCHED` 假绿；当日有流水 ⇒ 巨大假差异（平台 0 vs 台账 X）把「文件还没出」误报成资金丢失。**「空对空算通过」本身是 `doReconcile` 的状态判定缺陷**（[ReconciliationService.java:220-221](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/ReconciliationService.java#L220)）：它不区分「核对过且相等」与「两侧都没数据」。
- 判级说明：当前 `consumer.pay_channel_select.enabled=false`、ALIPAY 成交 0 笔 ⇒ 属**上线支付宝前必须收口**的前提类缺陷，不是现网事故。

### R22-9｜`device_temperature_reading` 无界增长（遥测表无保留策略）

- 写入：每次心跳带温度就存一行，无去重/无降采样（[DevicePresenceService.java:108-117](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/DevicePresenceService.java#L108)）。
- 读取只需要近窗：商户温度历史 `pointLimit ≤ 5000`、按小时取点（[MerchantDevicePortalService.java:377-386](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantDevicePortalService.java#L377)）。
- 全库唯一保留任务 `growth-log-archive` 只清 `notification_log`（[GrowthLogArchiveScheduler.java:47-58](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/GrowthLogArchiveScheduler.java#L47)）。
- 实库取证：`55,287` 行 / `4` 台设备 / 跨度 `2026-11-01 → 11-12` ⇒ **约 1,250 行/机/天**；表体积 10 MB（已是全库最大表），索引只有 `(device_id, reported_at DESC)`。按 100 台柜一年 ≈ 4,500 万行。
- 后果：无报错、无功能缺陷，属容量与运维成本；但它是「测试环境已长到最大表」的实测信号，扩容/备份/ vacuum 窗口都会受影响。
- 派修口径建议：给遥测类表加保留窗（例如 90 天）+ 按 `reported_at` 分批删（复用 `deleteInBatches` 范式），别复用 `notification_log` 的 dedup 语义。

### R22-10｜业务日窗口使用 `ZoneId.systemDefault()`

- [MerchantFinanceService.java:266-267](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantFinanceService.java#L266)（月账单窗口）、[DeviceSlotService.java:575-577](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceSlotService.java#L575)、[DeviceRentalService.java:319](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceRentalService.java#L319) 与 `:348`、[InventoryLotService.java:861](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/InventoryLotService.java#L861)；另有批次号直接 `LocalDate.now()`（[CommercialFlowService.java:88](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/CommercialFlowService.java#L88)、[InventoryLotService.java:377](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/InventoryLotService.java#L377)）。
- 与同仓主流写法不一致：`ScheduleZones.ZONE`（`Asia/Shanghai`）被 `CommissionClawbackService`、`LineCommissionJob`、`FundBillService`、`DeviceAvailabilityKpiService` 等普遍采用。
- **当前无害，已实测**：trade-service 容器 `TZ=Asia/Shanghai`，应用日志偏移 `+08:00`（同一时刻 `date -u` 为 UTC）⇒ JVM 默认区就是上海，`systemDefault()` 与 `ZONE` 等价。
- 危害面：一旦部署侧漏配 `TZ`（`docker-compose.apps.yml` 里没有 `TZ:` 字面，值来自 [Dockerfile.java-service:12-13](file:///D:/ai-generated%20code/ai-cabinet/Dockerfile.java-service#L12) 的 `ENV TZ` + `/etc/localtime` 符号链接），这些窗口会**整体平移 8 小时**，且与 R22-4 的 SQL 桶叠加后错位方向不可预判。属「依赖环境前提的隐性配置项」，建议统一到 `ScheduleZones.ZONE`，并把「容器必须有 TZ」写进部署门禁而不是依赖镜像自带。
  - 跨轮对账（补记时核实）：peer 在制改动（工作树有、HEAD 尚无）已给 ENTRYPOINT 补 `-Duser.timezone=Asia/Shanghai`（R19-17，理由＝换/精简 base image 会让 `ENV TZ` + tzdata 静默退回 UTC）⇒ 本条「镜像层漂移」的风险已减半；但业务代码仍不该把镜像参数当判据来源，故本条不撤。

---

## 三、主审亲验回执（逐条）

| 编号 | 判定 | 我怎么验的 |
|------|------|-----------|
| R22-1 | **对** | 两侧方法体逐行读完；写入用 `base + " " + orderId`、解析只认 `#`；类注释自证契约；确认过滤条件确实命中该类行 |
| R22-2 | **对** | 读 `sendCode`/`assertSendAllowed`/`markSendSucceeded` 三个方法体，确认额度在 Redis 且发送在其后 |
| R22-3 | **对（并加严）** | 补了两条子代理没取到的证据：手动面板可选 1–10 层 + 文案自证「未列出即下架」；`preview()` 无层维度 |
| R22-4 | **对** | 主审自查轴：`SHOW timezone`=UTC；实库对比两种桶标签，命中 4 笔跨日；月界丢数为机制推导（当前 0 笔，如实标注） |
| R22-5 | **对** | 直接复刻 `deleteProtectedTables` 的递归 CTE 得到 25 张暴露表；逐一核 UI 挂载面（8 页），确认 `recharge_order` 有常驻删除按钮；核对下游消费方（会员 SUM、对账上限、审计只记 pk） |
| R22-6 | **对** | 读到 `return 0` 在 `alertStaleChargePendingOps()` 之前；确认调用方唯一；对照催缴腿的 72h 兜底证明这是遗漏；字典描述确认 `0=关闭` 是受支持配置 |
| R22-7 | **对，定档待定** | 读了 `start/finish/cancel` 与 `execute`；确认 §7.15 无幂等键、成功后才留痕；厂商重复 start 行为无本地证据，保留为待定 |
| R22-8 | **对** | 主审自查轴：硬编码渠道、ALIPAY 0 行、WECHAT 9/13 双侧全 0、两个 provider 语义相反（逐文件读） |
| R22-9 | **对** | `pg_stat_user_tables` + 时间跨度 + 索引与体积实测；读写入方法与读取上限 |
| R22-10 | **对（当前不发作）** | 容器 `TZ` + 日志偏移实测，确认与 `ScheduleZones.ZONE` 等价 ⇒ 只作为漂移隐患报 |

**子代理报告的偏差纠正 1 处**：R22-1 的影响表述「该对账是唯一自动兜底」需要限定——`PaymentService.java:718-724` 自己写明「WeChatRefundReconciler **不覆盖余额切片号**」，所以它是「订单退款 PROCESSING 的兜底」而非全局兜底；本条的缺陷范围是**充值退款行**。判级不变。

---

## 四、与 R21 的对账（跨轮一致性）

R21 报的「出站 HTTP 无超时 6 处」现状（HEAD=`4e230651` vs 工作树）：

| 站点 | HEAD | 工作树 | 结论 |
|------|------|--------|------|
| `WeChatPayV3Client` | 无 | `setConnectTimeout(3_000)`/`setReadTimeout(10_000)`，下载 60s | **peer 在制修复（未提交）** |
| `AlipayOpenApiClient` | 无 | 3s/10s | **peer 在制修复（未提交）** |
| device `TradeServiceClient` | 无 | 已含 Timeout | **peer 在制修复（未提交）** |
| `WeChatMiniAppClient`（登录 code2session） | 无 | 无 | ~~🔴 **仍未修**~~ ✅ **R21-1 已修**（2026-11-14 复核订正：裸建已替换为 OutboundHttp，源码仅剩 `:51` 处「为什么不能再用裸 RestClient」的历史说明注释） |
| `IdentityVerifyClient` | 无 | 无 | ~~🔴 **仍未修**~~ ✅ **R21-1 已修**（2026-11-14 复核订正：同上，`:49` 为历史说明注释） |
| `JiangyiModelSyncService` | 无 | `RestClient.create()` 原样（[:59](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiModelSyncService.java#L59)） | ~~🔴 **仍未修**~~ ✅ **R21-1 已修**（2026-11-14 复核订正：同上，`:60` 为历史说明注释） |

⇒ 派修者请注意：这三处与本轮 R22-1/R22-2 是**同一批链路**（登录/实名/模型下发 + 退款/短信），若只修超时不修「先烧额度/结果未知不收口」，现象会从「挂住线程」变成「快速失败但状态残留」。
<br>

> **2026-11-14 复核订正**：上表三行「🔴 仍未修」已过期——R21-1 随后把三处裸建全部替换为 OutboundHttp 工厂（当前源码中 `RestClient.create()` 仅剩历史说明注释，无实际调用）。复核时曾误报「仍未修」，订正防 R23 重复报。

---

## 五、待定（缺什么证据才能定档）

> ⚠️ 读数口径：下面 4 项里 **1–3 是「缺证据」**、**第 4 项是「缺拍板」**。
> 提交后已逐项再取证，回执与结论见 **§八**（其中 8.2 已结案为部署约束、8.3 已钉到结构层）。

1. **厂商 §7.15 重复 start 的行为**（R22-7 定档）：拒绝 or 幂等返回同一 sessionId。需真机连点两次，或厂商明确答复。
2. **jiangyi-gateway 多实例下的开门指令路由**：`DeviceWebSocketHandler.sessions` 是进程内 `ConcurrentHashMap`，而 `CommandTracker` 用 Redis。若 trade 的开门 POST 被负载均衡到「设备没连着的那台」gateway，`isOnline()=false` ⇒ 恒 503「设备不在线」。需确认部署形态（单实例？nginx 按 identifier 粘连？）——**若确为多实例则是 P1**，这条只能由部署侧回答。
3. **现网将邑柜是否存在多层柜型**（R22-3 定档）：若有多层，「整柜库存写成第 1 层」是确定性错误下发；若只有单层，本条只剩「失败静默」这一半。
4. **`payment_reconciliation` 的 MATCHED 语义**（R22-8）：「两侧皆 0 ⇒ MATCHED」是产品认可（当日确实无交易就是平的）还是必须区分「未核对」？需要拍板口径，不建议修的人自己选。

---

## 六、检查过且判为无缺陷（附判据，避免下一轮重复劳动）

子代理覆盖（主审抽查其中 4 条判据成立）：
- 微信 V3 下单/退款/查单、支付宝 OpenAPI 的异常封装：不吞异常，统一上抛（超时项见 §四 的 peer 在制状态）。
- 微信支付分主链：置 PENDING 等回调 DONE→PAID，超时不盲判成功；欠款闸 + 催缴兜底。
- 退款幂等族：M02「订单+金额」键 + AU-175 行集指纹键 + 确定性退款号 + 「ABNORMAL 不记已退」+ AU-140 切片查单三态。
- 回调验签/防重：`WeChatPayNotifyService` 先验签再占 nonce 槽、5min 时间窗、mchid 校验；`AlipayNotifyService` RSA2 + app_id/seller_id + notify_id 去重（**fail-closed**，主审读了 `isConfigured` 缺失即抛的分支）。
- 实名认证 `IdentityVerifyClient`：`isOk()` 严格要求 `result_code==0000 && checkresult==1`，无「异常即放行」。
- 将邑采集 client `JiangyiGatherClient.post`：仅 401/403 触发重登重试一次（对写操作安全，401 意味厂商侧未执行）。
- 网关侧 `SessionWatchdog`/`CommandTracker`/`DeviceReportController`：失败不 evict、可重试；原始报文 finally 留档；复核闸 fail-closed。
- OSS STS / 视频读路径：凭证 inline Policy 收窄，SSRF/key 越界返回 null，播放失败回 `playable=false + reason`。

主审自查补充：
- **`notification_log` 保留策略不会导致重复通知**：`alreadySent`/`alreadySentType` 的 `bizId` 恒为「昨天日期」（[MerchantDailyIncomeNotifyScheduler.java:148](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantDailyIncomeNotifyScheduler.java#L148)、[:243](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantDailyIncomeNotifyScheduler.java#L243)），日期永不复现 ⇒ 6 个月后删行不可能造成重发。查重条件本身无时间界，但不构成缺陷。
- **`notification_log` 无下游外键**：`pg_constraint` 查询命中 0 行 ⇒ 归档删除不会被 RESTRICT 挡住（该任务曾因时间运算 100% 失败过，这次形态是安全的）。
- **`user_account` 的删除入口是 fail-closed**：虽在递归 CTE 里判为「可删」，但 `listManagedTables()` 先按 `EXCLUDED_TABLES` 过滤 ⇒ `deleteCapabilities()` 里根本没有这个键，前端 `caps[tbl] === true` 取不到 ⇒ 按钮不渲染（实测推断成立）。
- **对账 provider 的「未配置」路径**：微信/支付宝都在未配置时抛异常而非返回空账单，注释明确「avoid silent MISMATCH」——只有 R22-8 的「配置了但账单缺失」是漏的。
- **金额单位**：`AlipayPayClient.formatAmount` = `BigDecimal.valueOf(cents, 2)`（分→元两位小数，正确）；`WeChatPayV3Client`/`WeChatPayScoreClient` 直接送分（正确）。未发现元/分混用。
- **对账文件解析时区**：`WeChatBillCsvParser:19`、`AlipayBillCsvParser:26` 均显式 `Asia/Shanghai` ⇒ 与 R22-4 的 SQL 侧不一致，但解析侧自身没错。

---

## 七、本轮方法边界（诚实声明）

- 子代理只跑了「出站集成失败语义」一条轴；它列的未覆盖面（识别归一化 `RecognitionNormalizer` 金额合计逐行、`GatherNotifyController`/`JiangyiProductStateService`/`JiangyiOrderVideoService` 写侧 upsert、`WebhookSmsSender` 体、支付分创单与签约回调全文、trade→gateway 侧 `JiangyiGatewayClient`、SKU 图 MinIO 读路径、device-simulator）本轮**无人覆盖**。
- 主审两条轴（数据生命周期、时间/单位口径）是**读码 + 只读 SQL**，未做真实运行验证：R22-4 的月界丢数、R22-5 的删除后果、R22-6 的开关短路都属**机制推导 + 局部实证**（跨日 4 笔、暴露表清单、字典文案是实测；丢数/删除后果未造数复现，因为要动活库资金表，本轮口径是「只发现不修复」）。
- dev 栈是小样本 mock 数据（44 单 / 67 条 `payment_operation` 全 COMPLETED），所以「残留中间态」类判据在本库为空集 ≠ 代码正确。

---

## 八、待定项回执（§五 的 4 项，逐项收口）

> 补记于报告提交后，由主审自查取得证据后追加；**不定级变更、不改代码**，只把「已知/未知」的边界钉死。
> 另修正 §五 标题口径：4 项里 3 项是「缺证据」，第 4 项（MATCHED 语义）是「缺拍板」，不该与待定混列。

### 8.1 厂商 §7.15「重复 start」行为 —— 仍待现场 30 秒动作

**已取证**
- 我方代码注释（来自厂商文档语义）：[JiangyiRestockClient.java:301](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/client/JiangyiRestockClient.java#L301)「同一台柜同时只能有一个会话」；[:322](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/client/JiangyiRestockClient.java#L322) 与 [JiangyiCabinetCaptureService.java:19-20](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiCabinetCaptureService.java#L19)「未结束会话占用该柜约 15 分钟，未结束会自释放」。
- 真机实测（[JIANGYI_INTERFACE_CHECKLIST.md:959-960](file:///D:/ai-generated%20code/ai-cabinet/docs/JIANGYI_INTERFACE_CHECKLIST.md#L959)）：厂商 555 修复后，**过期会话**回 `503「采集会话不存在或已过期」`——即「会话不存在」是一条**业务错误文案**，不是静默新建。

**未取证**：对**仍在进行中**的会话再次 `§7.15 start`，返回的是「拒绝（业务错误）」还是「幂等回同一 sessionId」。本仓资料里没有 §7.15 的原文段落可查（`将邑对接资料/doc_full_text.txt` 是 V16 SaaS 5.x 段，`extracted_text.txt` 无 `cabinetCapture`），只能问厂商或真机验。

**收口动作**：同柜连点两次「开始」（间隔 <10 秒）。
- 回同一 sessionId ⇒ R22-7 降为说明（运营可自愈），仅保留「无本地留痕」的可观测性问题；
- 回业务错误 ⇒ R22-7 维持 P3；若错误文案不含 sessionId，运营仍无法取消 ⇒ 建议按原文的「失败进运营待办并带 sessionId」修。

### 8.2 jiangyi-gateway 单/多实例 —— **结案：当前单实例，不构成现存缺陷；转为部署约束**

**已取证（配置面穷举）**
- `infra/gateway/nginx.conf:14-16`：`upstream jiangyi_gateway { server host.docker.internal:18084; }`（单 server）。
- `infra/gateway/nginx-full.conf:157,174` 与 `nginx.compose.conf:161,178`：`set $jiangyi_upstream jiangyi-gateway:8083;`（单目标）。
- `docker-compose.apps.yml:272` / `.full.yml:391` / `.production.yml:99`：单 service 定义，无 `replicas`/`deploy`。
- `docker-compose.ha.yml`：只扩 `trade-service`（文件头用法即 `--scale trade-service=2`），**没有** gateway 的 scale 入口。

**结论**：`DeviceWebSocketHandler.sessions` 的进程内 `ConcurrentHashMap` 在现网形态下等价于全局表，R22 §五-2 担心的「恒 503」**现在不会发生**，该条**不升 P1**。

**转为部署约束（建议写进 HA/部署文档与门禁）**：`CommandTracker:25` 与 `SessionWatchdog:28` 的注释只说「多实例由 Redis 锁互斥」——那只覆盖**定时扫描**；**WS 会话路由不可跨实例**。若将来 scale gateway，必须二选一：① 明确禁止（门禁拦截 compose 里 gateway 的 replicas）；② 按 `identifier` 做一致性哈希/粘连，或把在线表挪到 Redis。

### 8.3 多层柜 —— 已钉到结构层：**我方模型没有层维度**

**已取证（schema 面穷举）**
- `information_schema.columns` 中名字含 `floor` / `layer` 的列：**0 个**（全库 179 张表）。
- 无任何 `%shelf%` / `%floor%` 表。
- `device_info` 无层数/门数列，只有 `cabinet_type`、`door_state`、`lock_state` 等；现网 5 台设备 `cabinet_type` 全为 `VISION`。
- `preview()` 取数源是 `device_sku_inventory`（全柜 SKU 聚合），[:88-118](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiRestockService.java#L88) 无层维度 ⇒ **即使厂商分层，我方也取不到「按层清单」**。

**结论**：R22-3 不是「多层柜时可能错」，而是「**多层柜时必然错**」——`floorNum=1 + 整柜清单` 会把其他层全部下架。单层柜下写覆盖语义正确。

**收口动作**：确认目标柜型是否分层（设备详情→将邑货架面板的「层号」列 / `totalFloorNum`）。
- 单层 ⇒ R22-3 降为「失败静默 + 无告警」这半边（仍 P2 的告警面）；
- 多层 ⇒ 维持 P2，且需先补层维度（`device_slot` 已有货道概念，可据此派生层）再谈自动同步。

### 8.4 「两侧皆 0 ⇒ MATCHED」 —— **不是待定，是拍板项**

**已取证**：`doReconcile` 的终态判定只比较三个量（`diff==0 && unmatched==0 && ledgerOnly.isEmpty()`，[ReconciliationService.java:220-221](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/ReconciliationService.java#L220)），不区分「核对过且相等」与「两侧都没有数据」；实库 `WECHAT` 13 次对账里 **9 次两侧全 0** 却记 `MATCHED`。

**待拍板**（两条路，代价不同）：
- **A（现状）**：无交易之日平就是平。代价：账单文件缺失/渠道未出账时也显示平 ⇒ 假绿可覆盖真实缺口。
- **B**：引入 `NO_DATA`/`NOT_READY` 状态（平台侧无行且台账无行时不判 MATCHED）。代价：对账页多一种状态，需同步运营口径与告警规则；好处是「没核对」与「核对通过」不再同一个词。

建议：若支付宝渠道要启用（R22-8），先落 B——因为支付宝空账单当前就是 `List.of()` 静默路径，与 A 叠加会形成「永远平」的假象。

---

## 九、修复回执（2026-11-12 当日，可派修 10 条全清）

| 编号 | 处置 | 提交 |
|------|------|------|
| R22-1 ✅ | 写入侧改拼 `#orderId` 对齐查单器契约；读取侧兼容存量空格尾巴（纯数字尾 token）——在途 PROCESSING 行恢复可推进 | `23c57e09` |
| R22-2 ✅ | `markSendSucceeded` 挪到 `smsSender.send` 成功之后（mock 分支保留前置计冷却）——provider 失败不再白烧冷却/小时额度 | `23c57e09` |
| R22-3 ✅ | 失败进 ops_exception（新类型 `JIANGYI_AUTO_RESTOCK_SYNC_FAIL`，字典已登记）；`floorNum=1` 以 javadoc 钉死「本通道仅单层柜」——**多层柜时必然错**（§8.3 已钉到结构层），手动面板是多层柜唯一正确通道 | `23c57e09` |
| R22-4 ✅ | 账单 SQL 四处 `to_char(... AT TIME ZONE 'Asia/Shanghai')` + Java 月窗口 `ScheduleZones.ZONE`，两侧同源 | `23c57e09` |
| R22-5 ✅ | 资金/账本表入删除保护（语义后缀零误伤 + 点名 5 张；KPI 聚合表刻意豁免）；`deleteRow` 审计补 before 全值；`GrowthLogArchiveScheduler` 的 `member_points_log` 删除分支清除。AdminDataManage 既有 44 用例全绿 | `23c57e09` |
| R22-6 ✅ | `alertStaleChargePendingOps()` 挪到 `hours<=0` 早退之前——开关只关自动关单，不再连带关巡检 | `23c57e09` |
| R22-7 ✅（代码面） | start 受理前落意图留痕、finish/cancel 失败补 FAILED 审计行（含 sessionId）；**定档仍悬**（§8.1 现场动作）——若厂商幂等返回同一 session 则降说明 | `382c0769` |
| R22-8 ✅（静默面） | 空账单改 fail-fast 对齐微信；自动对账开洞与「两侧皆 0 ⇒ MATCHED」**归拍板**（§8.4），本修只消灭静默路径 | `382c0769` |
| R22-9 ✅ | 温度遥测 90 天保留窗（`ops.log_retention.temperature_days`，0=关闭），分批删复用归档范式（主键 `reading_id`、时间列 `reported_at` 实库核实） | `382c0769` |
| R22-10 ✅ | 4 处业务日窗口 + 2 处批次号统一 `ScheduleZones.ZONE`；grep 门禁未立（与 R21-2 全量清扫同批并入常规） | `382c0769` |

**真正悬着的只剩两个现场动作**（不阻塞代码，只影响两条的最终定档）：
1. **同柜连点两次「开始」**（间隔 <10s）→ 定 R22-7 是「降为说明」还是「维持 P3」；
2. **看一层号**（设备详情→将邑货架面板「层号」列 / `totalFloorNum`）→ 定 R22-3 是「只剩告警面半边」还是「维持 P2」。

**修复时顺手清掉的两处共享门禁红**（非本轮条目）：`PayScoreOrderNotifyService` 新异常类型缺字典登记（`check:exception-type-dict` 红 → 已补 `PAYSCORE_DONE_ON_TERMINAL_ORDER`）；两份 2026-11-12 审计文档把「建议立」的门禁名写成反引号锚点（`check:ledger-evidence` 红 → 措辞改「待立」）。另：`PaymentService` 同文件存在并发会话在制改动，本修复按 hunk 拆分提交（`git apply --cached`），未携带他人改动。
