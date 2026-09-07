# Pass 3B · 争议退款深读笔记

> **日期**：2026-09-07  
> **范围**：工单生命周期、三端结案、运营/消费者退款、库存回库策略、SLA  
> **源码**：`DisputeService`、`RefundInventoryPolicy`、`SettlementService`（confirm/waive/partial）、`DisputeController` / `ConsumerDisputeController`、admin/merchant/consumer 争议页  
> **前置**：[PASS_3A_MONEY.md](PASS_3A_MONEY.md) · [CODEBASE_INVENTORY.md](../CODEBASE_INVENTORY.md)

---

## 1. 工单状态机

```
OPEN ──resolve(KEEP|WAIVE|CONFIRM|ADJUST*)──► RESOLVED ──close──► CLOSED
  ▲                                              │
  └────────────── reopen ◄───────────────────────┘
                 （已 REFUNDED 订单禁止 reopen）
```

| 状态 | 含义 |
|------|------|
| OPEN | 待处理；可 claim / resolve / reply |
| RESOLVED | 已结案；可 close；可 reopen |
| CLOSED | 终态归档；可 reopen |

\* **ADJUST** 仅运营；商户仅 KEEP / WAIVE / CONFIRM。

锁：`dispute:ticket:{ticketId}`（认领/结案）；退款走 `order:payment:{orderId}`。

---

## 2. 开单入口

| 入口 | 方法 | 前置 | 副作用 |
|------|------|------|--------|
| 识别结算 escalate | `createTicket(session, recognition, reason)` | 同 session 已有单则回放 | OPEN + 归档录像；category=RECOGNITION；reviewCode 由视觉推导 |
| 识别超时 | `createTimeoutTicket` | — | 同上，modelVersion=timeout |
| 消费者申诉 | `fileByConsumer` | session COMPLETED/DISPUTED；无 OPEN 单 | OPEN + 绑证据；session→DISPUTED；PAID 订单→DISPUTED |
| 全额退款附带 | `ensureDisputeTicketForFullRefund` | — | 无单则新建 USER_APPEAL；已结案则 reopen 同单承载退款 |

**消费者申诉冲突**：

- 已有 OPEN → `DISPUTE_ALREADY_EXISTS`
- 已有 RESOLVED/CLOSED → `DISPUTE_APPEAL_CLOSED`（须运营 reopen 后再申诉，或走订单退款）

### reviewCode（筛选用）

| 条件 | code |
|------|------|
| gravity-mismatch / 文案含「视觉与重力」 | GRAVITY_MISMATCH |
| mock/fallback / 「模拟」「非生产精度」 | MOCK |
| gravity-fill / 「仅有重力」 | GRAVITY_FILL |
| 空 items 但有 detectedClasses | UNMAPPED |
| 空 / 「未识别」 | EMPTY |
| 「置信」「阈值」 | LOW_CONF |
| 「白名单」等 | WHITELIST |
| 其他 | NEED_REVIEW |

---

## 3. 认领 claim

| 角色 | 权限 | 规则 |
|------|------|------|
| 运营 | `ops:dispute:resolve` | 可强抢已认领工单 |
| 商户 | `merchant:disputes:resolve` | 不可抢走他人 assignee；需设备范围 |

仅 OPEN 可认领。两端都做 `merchantScopeService.requireDeviceAccess`。

---

## 4. 结案 resolve 决策表

公共前置：OPEN；设备范围；已 REFUNDED 且非 KEEP/WAIVE → 冲突。

结案后统一：

1. `opsExceptionService.resolveOpenForSession`（同步关异常）  
2. session → **COMPLETED**  
3. `alignOrderStatusAfterDisputeResolve`

### 4.1 KEEP（维持原账单）

- **不改**订单金额、**不退款**、**不改库存**
- ticket RESOLVED，resolutionItems=原建议行
- 订单若 DISPUTED：按净支付对齐 → PAID / PENDING / PARTIAL_REFUNDED

### 4.2 WAIVE（免单）

- `RefundInventoryPolicy.resolve(flag, ticket.reason, defaultIfUnknown=true)`  
- `settlementService.waiveAndRefund(session, restore)` → 退 netCompleted；回库或「货已离柜」流水；void 分账  
- 订单对齐 → REFUNDED  
- 无净扣款时 message「已免单，无需扣款」

### 4.3 CONFIRM / ADJUST（确认清单）

- 必须有 items（quantity>0）  
- `confirmDisputedItems`：无单则 finalize；有单则改行→余额校验→库存→`applyPaymentDelta`→分账调整  
- 商户 CONFIRM：若 body 无 items，**默认用工单建议行**（避免无选品器）  
- 运营 ADJUST：与 CONFIRM 同路径，审计 type 不同  

### 4.4 alignOrderStatusAfterDisputeResolve

仅当订单当前为 DISPUTED：

| resolution | 订单结果 |
|------------|----------|
| WAIVE | REFUNDED |
| 其他 + netPaid≤0 | PAID（金额≤0）或 **PENDING** |
| 其他 + 有退款且净付&lt;应付 | PARTIAL_REFUNDED |
| 其他 | PAID |

**测点**：识别争议未扣款时 CONFIRM 出账 → 可能 PENDING；KEEP 未支付单勿误标 PAID（已按 netPaid 处理）。

---

## 5. close / reopen

| 操作 | 规则 |
|------|------|
| close | 仅 RESOLVED→CLOSED；写 note |
| reopen | RESOLVED/CLOSED→OPEN；**订单已 REFUNDED 禁止**；session→DISPUTED；PAID→订单 DISPUTED；刷新 SLA |

异常中心手动结案可 `closeOpenTicketForSession`（只改票，不重跑资金）。

---

## 6. 退款双通道（与结案并列）

不必先走争议 UI，可直接退订单：

| API | 权限 | 方法 |
|-----|------|------|
| 运营 `ops:order:refund` | `refundByOperator` | 全额或按行 |
| 消费者 `POST /orders/{id}/refund` | `refundByConsumer` | 受 `RefundPolicyService` 限额 |

### 6.1 全额 `executeFullRefund`

允许状态：PAID / COMPLETED / DISPUTED / PARTIAL_REFUNDED  

1. 原因 ≥4 字  
2. ensure/reopen 争议票 + 证据  
3. `RefundInventoryPolicy`（见下表）  
4. `waiveAndRefund`  
5. ticket RESOLVED；session COMPLETED  

### 6.2 部分 `executePartialRefund`

→ `settlementService.partialRefund`；若结果 REFUNDED 则 session COMPLETED。

### 6.3 RefundInventoryPolicy

```text
explicit 非空 → 用 explicit
原因含「没拿/误识别/多扣…」→ 回库 true
原因含「质量/已拿走/仅退款…」→ 不回库 false
否则 → defaultIfUnknown
```

| 调用点 | defaultIfUnknown |
|--------|------------------|
| 争议结案 WAIVE（运营/商户） | **true** |
| 订单全额/部分退款 | `!operator` → 消费者 **true**，运营 **false** |

（与类注释一致：消费者自助偏误识别回库；运营未写明时偏不回库，需显式或原因文案。）

---

## 7. 三端 API / UI 对照

### 运营 admin `DisputeListView`

| 动作 | API | 权限 |
|------|-----|------|
| 列表/详情 | `GET /api/v2/ops/disputes` | `ops:dispute` |
| 认领 | `…/claim` | `ops:dispute:resolve` |
| KEEP / ADJUST / WAIVE | `…/resolve` | 同上（UI 有 ADJUST） |
| 关闭 / 重开 | `…/close` `…/reopen` | 同上 |

### 商户 merchant-mp `pages/disputes`

| 动作 | API | 权限 |
|------|-----|------|
| 列表/详情 | `/api/v2/merchant/disputes` | `merchant:disputes:list` |
| 回复 | `…/reply` | `merchant:disputes:reply` |
| 认领 | `…/claim` | `merchant:disputes:resolve` |
| KEEP / WAIVE / CONFIRM | `…/resolve` | 同上（**无 ADJUST**） |

### 消费者 consumer-mp

| 动作 | API |
|------|-----|
| 申诉 | `POST /api/v2/disputes` + evidence upload |
| 我的争议 | `GET /disputes/mine` |
| 订单退款 | `POST /orders/{id}/refund`（策略限额） |

消费者**不能**直接 resolve 工单。

---

## 8. SLA

- 开单时设 `slaDueAt`（配置 `DISPUTE_SLA_HOURS`）  
- `DisputeSlaScheduler` + `DisputeSlaAlertService` 告警  
- `DisputeSlaService`：逾期数、临期数、24h 合规率（无样本返回 0 非 100%）  
- reopen 时若 SLA 已过期则重置  

---

## 9. 已有测试 vs 缺口

### 已有

| 测试 / 脚本 | 覆盖 |
|-------------|------|
| SettlementDisputeTest | 识别→争议、免单等 |
| SettlementConfirmDisputeTest | confirm 改单 |
| SettlementPartialRefundTest | 部分退 |
| DisputeReviewCodeTest | reviewCode |
| DisputeTicketSyncTest | 票同步 |
| e2e-dispute-recognition / e2e-demo-smoke | 脚本 |
| three-end-dispute-ui-uat.mjs | 三端 UI |

### 建议补测（D1–D10）

| ID | 场景 | 断言 | 落地 |
|----|------|------|------|
| D1 | KEEP 未扣款争议单 | 无新 CHARGE；订单非误标 PAID（netPaid=0→PENDING 或金额0→PAID） | ✅ `DisputeTicketSyncTest#resolveTicket_keep_unpaidDisputedOrder…` |
| D2 | WAIVE 回库 vs 不回库 | 库存差 ±qty；分账 void；REFUNDED | （库存侧见 I3/I4） |
| D3 | CONFIRM 加价余额不足 | 412；库存/金额未半改（事务） | ✅ `SettlementConfirmDisputeTest#confirmDisputedItems_increase_balanceInsufficient…` |
| D4 | 商户不可 ADJUST | 400；不可强抢认领 | ✅ `DisputeTicketSyncTest#resolveAsMerchant_adjust_rejected` + `#claimAsMerchant_cannotStealAssignee` |
| D5 | 运营强抢 claim | assignee 覆盖 | ✅ `DisputeTicketSyncTest#claimTicket_opsCanOverrideAssignee` |
| D6 | reopen 已退款单 | 冲突；防重复退 | ✅ `DisputeTicketSyncTest#reopenTicket_refundedOrder_conflicts` |
| D7 | 消费者申诉 CLOSED 单 | DISPUTE_APPEAL_CLOSED | ✅ `DuplicateCallbackTest#resolvedConsumerAppeal_returnsClosedMessage` |
| D8 | 全额退款重开旧票 | 同 session 票 reopen→RESOLVED；只退一次 | ✅ `DisputeOrderRefundTest#refundByOperator_full_reopensResolvedTicket…` |
| D9 | 部分退至 REFUNDED | session COMPLETED；PARTIAL→全退路径 | ✅ `DisputeOrderRefundTest#refundByOperator_partialToRefunded…` |
| D10 | 结案同步关异常中心 | 同 session 异常 RESOLVED | ✅ `DisputeTicketSyncTest#resolveTicket_waive_syncsOpenOpsExceptions` |

优先余：库存 D2 已由 I3/I4 覆盖；争议 D* 高优已齐。下一可做 **MQTT Q*** 或 **3F PR-A**。

---

## 10. 优化建议（测后）

1. **resolveKeep 对「从未扣款」单**：显式产品文案与订单 PENDING 对齐，避免运营误以为已收款。  
2. **商户 CONFIRM 默认建议行**：审计中标明 `itemsSource=TICKET_SUGGESTED`，防误结。  
3. **DisputeService ~1350 LOC**：按「开单 / 结案 / 退款 / 商户」拆四个协作类，先用 D* 锁住行为。  
4. **close 仅运营**：商户无 close；文档与 UAT 已体现，保持。

---

## 11. 与 3A 的衔接

| 3A 概念 | 3B 用法 |
|---------|---------|
| 预授权释放 | escalate 已释放；结案不再二次 freeze |
| waiveAndRefund / partialRefund / confirm | 结案与订单退款共用 |
| PENDING 库存已扣 | CONFIRM/KEEP 后 align 可能仍 PENDING，需催付测 |

下一 Pass：**3C MQTT**（门事件乱序/去重与 Session）单测 **Q2/Q4/Q6**，或 **3F PR-A**（拆 AdminDashboard analytics）。
