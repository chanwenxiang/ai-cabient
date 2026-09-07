# Pass 3E · 商户资金（分账 / 钱包 / 提现）深读笔记

> **日期**：2026-09-07  
> **范围**：订单分账入账、商户钱包余额/冻结、提现审核打款、线路钱包对照、微信分账回退补偿  
> **源码**：`RevenueSplitService`、`MerchantWalletService`、`MerchantWithdrawService`、`MerchantWithdrawPayoutService`、`WeChatProfitSharingService`、`LineWalletService`、`ProfitSharingReturnCompensationService`  
> **前置**：[PASS_3A_MONEY.md](PASS_3A_MONEY.md) · [PASS_3B_DISPUTE.md](PASS_3B_DISPUTE.md) · [PASS_3D_INVENTORY.md](PASS_3D_INVENTORY.md)

---

## 1. 资金主链路

```
订单 PAID / 补缴成功
  → RevenueSplitService.recordSplit
       ├─ 无微信接收方 → status=LEDGER_ONLY → 立刻 creditIfAbsent 入钱包
       └─ 有接收方 / API 未就绪 → status=ACCRUED（本地钱包通常不入账，走微信分账或待提交）
争议全额退 / WAIVE
  → voidSplitOnFullRefund → 微信回退(若已提交) + reverseCreditIfPresent → VOIDED
争议改单 / 部分退
  → adjustSplitAfterOrderChange / adjustSplitAfterPartialRefund
       ├─ 应付↑ → resync（账本增额 / 已提交微信则告警人工补分）
       └─ 应付↓ → 冲正差额 / 微信部分回退
提现
  → freeze → PENDING_REVIEW|APPROVED → PAYING → PAID(consumeFrozen) | FAILED(冻结保留)
```

结算调用点（`SettlementService`）：`finalizeOrder` → `recordSplit`；全额退 → `voidSplit`；部分退 → `adjustSplitAfterPartialRefund`；CONFIRM → `adjustSplitAfterOrderChange`。

---

## 2. 分账状态机

| 状态 | 含义 | 本地钱包 |
|------|------|----------|
| `LEDGER_ONLY` | 无微信接收方；份额记平台账本 | **创建账**（`SPLIT` / splitId） |
| `ACCRUED` | 待提交微信分账 | 一般不入账 |
| `WECHAT_SUBMITTED` / `SUCCESS` | 已走微信 | 本地冲正不走 wallet credit（回退走微信 return） |
| `SETTLED` | 运营确认仅记账完结 | 已入账（confirm 再幂等 credit） |
| `VOIDED` / `REVERSED` | 全额冲正 | reverse / 不再改 |
| `WECHAT_FAILED` | 提交失败 | 定时重试 |

分成公式：`platform = gross * platformRateBps / 10000`，`merchantShare = gross - platform`。  
`settleAfter = today+1`、`settlementBatchNo = MS-{date}-{merchantId}`（批次展示用）。

锁：`order:split:{orderId}`（60s/5s）+ DB `findByOrderIdForUpdate`。

### 2.1 改单 / 部分退对钱包

仅当 status ∈ `{LEDGER_ONLY, SETTLED}`：

| 方向 | 动作 | ref |
|------|------|-----|
| 商户份额↓ | `debitIfAbsent` `SPLIT_PARTIAL_REVERSE` | `SPLIT_PARTIAL_REV` / `{splitId}:g{newMerchant}` |
| 商户份额↑ | `creditIfAbsent` `SPLIT_PARTIAL_CREDIT` | `SPLIT_PARTIAL` / 同上 |

已 `WECHAT_SUBMITTED|SUCCESS`：本地重算金额；减额走 `returnMerchantShare`（`PSR…` / `PSR-FULL-…`）；失败写 `failureReason` + `ProfitSharingReturnCompensationService` 延迟重试 + 告警。增额不自动补提交（告警人工）。

### 2.2 与消费者支付关系（3A）

当前购物默认余额扣款 → 多数生产路径为 **LEDGER_ONLY 立刻入钱包**。微信分账 API 仍是骨架/开关路径；测本地钱包时优先 LEDGER_ONLY。

---

## 3. 商户钱包 `MerchantWalletService`

| 字段 | 含义 |
|------|------|
| `balanceCents` | 账面总余额 |
| `frozenCents` | 提现冻结 |
| **可用** | `balance - frozen` |

| API | 幂等键 | 行为 |
|-----|--------|------|
| `creditIfAbsent` | refType+refId | 已存在流水 → false |
| `debitIfAbsent` | 同上 | 可用不足 → 412 |
| `reverseCreditIfPresent` | 原 SPLIT 存在且 SPLIT_REV 不存在 | 冲正扣款 |
| `freezeForWithdraw` | 流水 `WITHDRAW_FREEZE`（**非** ref 幂等拦重） | 冻增加，余额不变 |
| `releaseFrozen` | `WITHDRAW_RELEASE` | 驳回/取消失败单 |
| `consumeFrozen` | `WITHDRAW_PAID` | 打款成功：余额与冻结同减 |

锁：`merchant:wallet:{merchantId}`（Redisson **可重入**）。  
`MerchantWithdrawService` 外层同 key 再调 wallet 方法：依赖可重入，禁止换不可重入实现。

---

## 4. 提现状态机

```
申请(requestNo 幂等)
  amount ≥ reviewThreshold → PENDING_REVIEW + freeze + 审批流
  amount < threshold     → APPROVED + freeze → attemptPayout
审核驳回 → REJECTED + releaseFrozen
审核通过（审批实例已通过）→ APPROVED → attemptPayout
attemptPayout → PAYING → PAID | FAILED
FAILED → 可 payout 重试；或 cancelFailed → CANCELLED + releaseFrozen
```

| 约束 | 来源 |
|------|------|
| 最低金额 / 日限额 / 手续费 | `MerchantWithdrawProperties` |
| 打款 | `MerchantWithdrawPayoutService`：**mock 成功**；非 mock 微信转账 **未接入** → 恒失败 |

**关键**：`FAILED` **不解冻**。可用余额被占到 cancel 或重试成功为止。

运营调账：`adjust` → credit/debit `OPS_ADJUST`；超阈值走审批。

---

## 5. 线路钱包（对照）

`LineWalletService` / `LineWithdraw*` 与商户钱包同构（`line:wallet:{managerId}`、freeze/consume、mock payout）。入账常见：线路推广赏金（`LinePromoBountyCreditTest`）。测商户路径时勿与线路余额混断言。

---

## 6. 已有测试 vs 缺口

### 已有

| 测试 | 覆盖 |
|------|------|
| RevenueSplitServiceTest / ConcurrencyTest | 分账记账与并发 |
| MerchantWalletConcurrencyTest | 钱包并发 |
| MerchantWithdrawConcurrencyTest | 申请冻结并发 |
| ProfitSharingReturn*Test | 回退补偿/告警 |
| Settlement*Dispute / PartialRefund | 调用 void/adjust（mock split） |
| MerchantE2ETest | 端到端商户 |

### 建议补测（W1–W8）

| ID | 场景 | 断言 | 落地 |
|----|------|------|------|
| W1 | LEDGER_ONLY 支付成功 | 钱包 +merchantShare；ledger `SPLIT`/splitId；重复 recordSplit 不双入 | ✅ `RevenueSplitServiceTest#recordSplit_ledgerOnly_creditsWalletOnce_andIdempotentReplay` |
| W2 | 全额退 void | reverse 流水；余额回退；status VOIDED；二次 void 无双重扣 | ✅ `RevenueSplitServiceTest#voidSplitOnFullRefund_reversesLedgerCredit_andSecondCallIsNoop` |
| W3 | 部分退减应付 | `SPLIT_PARTIAL_REV` 差额；split.merchantCents 更新 | （已有 clawback/resync 测） |
| W4 | CONFIRM 增额（LEDGER） | 部分 credit；ref=`:g{new}` 幂等 | （已有 increaseLedgerOnly） |
| W5 | 提现低于阈值 | freeze→mock PAID→consume；可用=原-amount | （已有 apply_acquiresLockAndFreezesBalance） |
| W6 | 提现 FAILED（关 mock） | 状态 FAILED；**frozen 仍在**；cancel 后释放 | ✅ `MerchantWithdrawConcurrencyTest#apply_payoutFailed_keepsFreeze_cancelReleases` |
| W7 | 同 requestNo 重放 | 不二次 freeze | |
| W8 | WECHAT_SUBMITTED 后全额退 | 触发 return；本地 reverse 不误伤未入账钱包 | |

优先：**W1、W2、W6、W7**（入账幂等 + 失败不解冻）。

---

## 7. 优化建议（测后）

1. **真实微信转账**：`transferApiReady=false` 上线前必须接 API 或强制 mock；后台 `payout-mode` 已暴露真相。  
2. **FAILED 运营 SOP**：超时未 cancel 的冻结单监控/告警。  
3. **ACCRUED 与钱包**：文档化「有接收方时本地不入账」；避免运营按钱包余额对账微信分账。  
4. **freeze 幂等**：提现 freeze 未按 ref 防重；目前靠外层 requestNo + 锁；可补 ledger 级幂等。  
5. **双层锁**：Withdraw 与 Wallet 同 key 可重入约定写入注释（同 3D `inv:`）。

---

## 8. Pass 进度

| Pass | 状态 |
|------|------|
| 3A 金钱 | ✅ |
| 3B 争议 | ✅ |
| 3C MQTT | ✅ |
| 3D 库存 | ✅ |
| **3E 商户资金** | ✅ 本文 |
| 3F 运营神类 | ✅ [PASS_3F_OPS_GOD_CLASSES.md](PASS_3F_OPS_GOD_CLASSES.md) |
| 单测 M/D/Q/I/W | 待做 |

金钱侧（消费→库存→分账→提现）深读已齐。下一可 **Pass 3F 运营神类**，或集中落地 **W1/W2/W6 + I1/I3 + M1**。
