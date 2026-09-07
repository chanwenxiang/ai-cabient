# Pass 3A · 金钱正确性深读笔记

> **日期**：2026-09-07  
> **范围**：开门 → 预授权 → 关门结算 → 扣款 / 争议 / 退款；充值回调验签  
> **源码**：`SessionService`、`ConsumerPreauthService`、`SettlementService`、`OrderPaymentService`、`PaymentService`、`WeChatPayNotifyService`、`AlipayNotifyService`  
> **前置**：[CODEBASE_FOUNDATION.md](../CODEBASE_FOUNDATION.md) · [CODEBASE_INVENTORY.md](../CODEBASE_INVENTORY.md)

本文回答「精确测试该断言什么」：每条分支对应期望状态、金额与锁行为。

---

## 1. 端到端资金状态机（摘要）

```
createSession
  ├─ idempotencyKey 命中 → 校验同人同柜回放
  └─ lock session:open:{deviceId}
       → validate user/device/inflight
       → save CREATED (+ flush 幂等键)
       → freezeForOpen（余额路径）或跳过（免密/补货/运维）
       → OPENING + MQTT openDoor

门 OPEN  → OPENING→SHOPPING
门 CLOSED →（先 commit 门状态，再 settle）
  ├─ OPS_REMOTE → COMPLETED（不结算）
  ├─ RESTOCK → 快照 / WAITING_UPLOAD / RECOGNIZING→快照
  ├─ WAITING_UPLOAD → 等 attachVideo 再 settle
  └─ RECOGNIZING → settleAfterClose
       ├─ vision-async → 等 Kafka 回调
       └─ settle() → processRecognitionResult
            ├─ 争议 → DISPUTED + 释放预授权 + DisputeTicket（不扣款）
            ├─ finalizeOrder → 扣库存 → charge / PENDING
            └─ COMPLETED
```

**关键设计（已读证）**：

1. **关门与结算拆事务**：`handleDoorEvent` 先 `applyDoorEvent` 提交，再 `settleAfterClose`，避免 vision 失败回滚「已关门」。  
2. **争议不扣款**：`escalateToDispute` 先 `releaseIfFrozen` 再开票。  
3. **预授权按会话 hold**：多会话并发只动本会话 `ConsumerPreauthHold`，钳制账户 `frozenCents`。  
4. **扣款幂等**：`CHARGE:{orderId}:{amount}` + `order:payment:{orderId}` 锁。

---

## 2. 预授权 `ConsumerPreauthService`

| 状态 | 含义 |
|------|------|
| NONE | 未冻结（免密/运维/补货/金额≤0） |
| FROZEN | 账户 frozen += hold；账本 PREAUTH_FREEZE |
| CAPTURED | 结算冲抵后；可能仍有 remainDebit |
| RELEASED | 取消/超时/争议释放 |

### 2.1 freezeForOpen 分支

| 条件 | 行为 |
|------|------|
| null session / 补货 / OPS_REMOTE / 运营 userId | clear，不冻结 |
| passwordFreeReady（支付分/代扣就绪） | clear，不冻结 |
| 已有 FROZEN hold | 幂等 sync session |
| available &lt; amount | **412** 可用余额不足 |
| 正常 | frozen+=amount，写 hold，ledger |

额度来源：`device.depositCents` → 系统配置 `CHECKOUT_PREAUTH_CENTS` → `CheckoutProperties`。

锁：`preauth:user:{userId}`（60s/5s）。

### 2.2 captureForCharge（结算时）

```
usableHold = min(account.frozen, session.held)
capture = min(orderAmount, usableHold)
balance -= capture
frozen  -= usableHold
remainDebit = orderAmount - capture  → 由 OrderPayment 再扣可用余额
```

### 2.3 释放点（必须测「冻结不泄漏」）

| 触发 | 调用方 |
|------|--------|
| 用户取消 OPENING | `SessionService.doCancelSession` |
| 运维强制取消 / 超时扫 OPENING·SHOPPING | SessionService expire* |
| 争议 escalate | `SettlementService.escalateToDispute` |
| 零元单 / 支付分扣款成功 | `OrderPaymentService.releaseSessionPreauth` / `tryPayScoreCharge` |
| 待支付关单 | `releaseBySessionId` |

**补测缺口**：同用户两会话先后开门冻结；一争议释放、另一仍 FROZEN；断言 `frozenCents == 剩余 hold 之和`。

---

## 3. 会话开门 / 关门 `SessionService`

### 3.1 createSession

| 分支 | 期望 |
|------|------|
| 幂等键已存在 | 同 user+device 回放 DTO；否则冲突 |
| 用户不可开门 / 柜不可用 / 并发超限 | 业务异常，无 MQTT |
| 成功 | CREATED→OPENING，预授权按上节，MQTT open |

锁：`session:open:{deviceId}`。先 `saveAndFlush` 再开门（防双开）。

### 3.2 关门后 settleSession 结果表

| 异常 / 结果 | 会话状态 | 副作用 |
|-------------|----------|--------|
| 正常 OrderDto PAID | COMPLETED | 指标 success |
| OrderDto PENDING | COMPLETED + 异常中心 BALANCE_INSUFFICIENT | 待支付单 |
| DisputeRequiredException | DISPUTED | ops RECOGNITION_FAILED |
| BalanceInsufficientException / 412 | DISPUTED | 兼容旧路径 |
| 其他 ResponseStatusException | FAILED | failReason |
| RestClientException | FAILED | RECOGNITION_UNAVAILABLE |
| RuntimeException | FAILED | SETTLEMENT_FAILED |

### 3.3 特殊会话

| 类型 | 识别 | 关门行为 |
|------|------|----------|
| 运维远程 | idempotencyKey `OPS_REMOTE:` | COMPLETED，不结算 |
| 补货 | replenishmentTaskId / RESTOCK: | 重力快照或视觉库存快照，不购物扣款 |
| demo-close | mock 开关 | 无重力 → 零元 settleManual；有重力 → 走门事件 |

---

## 4. 结算决策树 `SettlementService.processRecognitionResultUnlocked`

锁：`session:settle:{sessionId}`。已有订单 → 直接返回（幂等）。

处理顺序（严格）：

```
1. withGravityFallback（若启用重力融合）
2. forceReviewIfMockOrMismatch（mock/mismatch/fill → needReview=true）
3. trySettleWhenReviewRequired
4. trySettleDevMock（仅 mockEnabled）
5. trySettleEmptyRecognition
6. confidenceService.reviewReasonIfNeeded → staging 重力 或 escalate
7. skuVisionEnrollment whitelist → escalate
8. finalizeOrder(items)
```

### 4.1 need_review / mock 静默扣款规则

| modelVersion 含 | blocksSilentSettle | 生产倾向 |
|-----------------|--------------------|----------|
| mock / fallback | 是 | 争议（除非 mock+重力证据 settle） |
| gravity-mismatch | 是 | 争议 |
| gravity-fill | 是 | 争议；沙箱可 gravity-fill settle |

`tryDevMockEvidenceSettle`：仅 `mockEnabled` + 有重力扣减 → 按重力 finalize（演示柜路径）。

### 4.2 空识别

| 条件 | 结果 |
|------|------|
| 重力融合且重力净空 + 有 gravity 字段 | 零元 finalize |
| SETTLEMENT_EMPTY_AUTO_NO_GRAVITY | 零元 |
| mock / staging / gravityFallbackSettle | 零元 |
| 其他 | 争议「未识别到商品」 |

### 4.3 finalizeOrder（落单扣款）

1. `buildOrder` + 选券（改应付）  
2. `detectUnpaidBeforeCharge`：非免密且余额+hold 不足 → **PENDING**，清券，扣库存仍执行  
3. `deductForOrder` → inventoryDeducted=true  
4. save order + lines  
5. unpaid → `finishUnpaidOrder`（不 charge、不分账、不核销券）  
6. 否则 `chargeOrder`；412 余额 → 转 PENDING 清券  
7. 成功：markUsed 券、`revenueSplitService.recordSplit`、会员积分、通知、视频归档  

**重要**：PENDING 也会扣库存 → 测试须覆盖「待支付关单 / 催付成功」时库存与券是否一致。

### 4.4 争议结案资金

| API | 行为 |
|-----|------|
| confirmDisputedItems | 无单则 finalize；有单则改行→校验加价余额→库存 adjust/deduct→`applyPaymentDelta`→分账调整 |
| waiveAndRefund(restore?) | netCompletedCents 退款；回库或记「货已离柜」；void 分账；REFUNDED |
| partialRefund | 行级数量；券重算；回库/不回库分行；PARTIAL_REFUNDED 或 REFUNDED |

---

## 5. 订单扣款 `OrderPaymentService`

锁：`order:payment:{orderId}`。

### 5.1 chargeOrder

| 分支 | 行为 |
|------|------|
| 运营 userId | 仅标 BALANCE，不扣 |
| amount≤0 | BALANCE + release 预授权 |
| 幂等 CHARGE 已 COMPLETED | 回填 channel，返回 |
| PayScore/代扣成功（非 balanceOnly） | 记 CHARGE + release 预授权 |
| 否则余额 | capture 预授权 + remainDebit ledger |

### 5.2 refundOrder

- 上限 = `netCompletedCents`  
- WECHAT/ALIPAY：真配置走原路；mock 退回余额并打标记文案  
- 积分 clawback 失败只打日志  

微信退款 `total` = 历史 CHARGE 合计（防改单后 total 变小）。

---

## 6. 充值回调（验签层）

### WeChatPayNotifyService

1. 配置检查  
2. timestamp ±300s  
3. nonce Redis `setIfAbsent` 10min（防重放）  
4. V3 签名验签  
5. AEAD 解密 resource  
6. mchid 匹配（有值必须匹配）  

业务入账：`PaymentService.handleWeChatNotify` → `creditRecharge`（订单幂等）。

### AlipayNotifyService

1. RSA2 验签  
2. app_id / seller_id  
3. notify_id Redis 24h 防重  

---

## 7. 锁与幂等键一览（本 Pass）

| Key | 用途 |
|-----|------|
| `session:open:{deviceId}` | 同柜开门 |
| `session:life:{sessionId}` | 门事件/取消/结算入口 |
| `session:settle:{sessionId}` | 识别落单 |
| `preauth:user:{userId}` | 冻结/冲抵/释放 |
| `order:payment:{orderId}` | 扣款/退款/改单差价 |
| `recharge:idem:{key}` | 充值预下单 |
| `CHARGE:{orderId}:{cents}` | 扣款幂等 |
| `REFUND:{orderId}:{cents}:{reasonKey}` | 退款幂等 |
| `aicabinet:wxpay:notify:nonce:*` | 微信通知防重 |
| `aicabinet:alipay:notify:id:*` | 支付宝通知防重 |

---

## 8. 已有测试 vs 缺口

### 已有（保留复用）

| 测试 | 覆盖 |
|------|------|
| SessionOpenConcurrencyTest / SessionLifeConcurrencyTest | 开门/生命周期锁 |
| ConsumerPreauthConcurrencyTest | 预授权并发 |
| SettlementSessionSettleConcurrencyTest | 结算锁 |
| SettlementDisputeTest / ConfirmDispute / PartialRefund | 争议与退款 |
| BalanceInsufficientSettlementTest | 余额不足 |
| SettlementConfidenceServiceTest / GravitySettlementHelperTest | 置信度与重力 |
| OrderPaymentConcurrencyTest / IdempotencyTest | 扣款幂等 |
| PaymentServiceTest / WeChatNotifyIntegrationTest | 充值/回调 |
| e2e-shopping / e2e-fund-safety / e2e-*-refund* | 脚本级 |

### 建议补测（按优先级）

| ID | 场景 | 断言要点 | 落地 |
|----|------|----------|------|
| M1 | 双会话同用户预授权 | 释放 A 后 B 仍 FROZEN；frozen= B.hold | ✅ `ConsumerPreauthConcurrencyTest#releaseSessionA_keepsSessionBFrozen…` |
| M2 | 关门 commit 后 vision 抛错 | 会话已非 SHOPPING（RECOGNIZING/FAILED），可再次开门策略符合产品 | |
| M3 | need_review + 生产 mock 关 | 必 DISPUTED，无 CHARGE 流水，预授权 RELEASED | ✅ `SettlementDisputeTest#needReview_production_escalatesAndReleasesPreauth` |
| M4 | 高置信 finalize → PAID | 库存扣减、券 markUsed、分账一行、预授权 CAPTURED | ✅ `SettlementDisputeTest#highConfidenceFinalize…`（charge 已调用；CAPTURED 在 OrderPayment 路径） |
| M5 | detectUnpaid → PENDING | 已扣库存、未 markUsed 券、无 CHARGE；后续 pay 成功才核销 | ✅ `SettlementDisputeTest#unpaidPending_deductsInventory_skipsCouponMarkAndCharge` |
| M6 | charge 中途 412 → PENDING | 与 M5 一致；会话 COMPLETED | ✅ `SettlementDisputeTest#chargeMidwayInsufficient_convertsToPending_clearsCoupon` |
| M7 | waive 不回库 | 库存不变 + REFUND 流水 + 分账 void | （库存侧见 I4） |
| M8 | 微信 notify 重放 nonce | 第二次拒绝；余额只加一次（credit 幂等） | ✅ `WeChatPayNotifyServiceTest` + `PaymentServiceTest#handleWeChatNotify_nonceReplay…` |
| M9 | 支付分 charge 成功 | 预授权未冻结或已 release；channel≠BALANCE | |
| M10 | 补货/OPS 关门 | 无购物订单、无消费者扣款 | |

脚本侧：`e2e-fund-safety.ps1` 已覆盖部分；**SettlementDecision** 已接入 escalate 文案（`humanReviewReason`）；余 D*/Q*。

---

## 9. 优化建议（有测后再动）

1. **SettlementService 更深接入 SettlementDecision**：`trySettleWhenReviewRequired` 入口可用 `classify().requiresHumanReview()` 替代裸 `needReview()`（注意 forceReview 顺序）。  
2. **PENDING 与库存事务边界**：评估「待支付是否应延迟扣库存」——属产品决策，改前用 M5 锁住现状。  
3. **IdempotencyService 孤儿**：与 CHARGE 幂等并行存在；统一或删除避免两套语义。  
4. **异步识别路径**：`completeAsyncRecognition` 与同步 `settleSession` 异常映射略有差异（CONFLICT→DISPUTED），对齐文档与指标。

---

## 10. 下一 Pass

- **3B 争议退款 UI+API**：DisputeService 全部分支 + 三端页面契约  
- **3C MQTT**：门事件乱序、去重、ACK 超时与 Session 状态  

完成 3B/3C 后回写本目录索引。
