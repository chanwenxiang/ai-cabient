# B1 Phase B 设计稿：补缴链路拆段（渠道 HTTP 移出 DB 事务）

> 状态：**评审完成；A0 已落地（2026-11-16，零行为抽取 `chargeChannelOnce` 三态）；B1-B（markPaid 三段 + forceCancel 409 + reconciler）待实施**（Q1–Q4 结论与提前修正见 §6）。
> 实施前先按 §6 的修订点改设计，再落码；实施批次含形态钉 + 三态负向 + 真栈弱网回归。

## 1. 现状与问题

`UnpaidOrderService.collect` / `collectByUser`（均为 `@Transactional`）→ `markPaid`(:413) → `chargeOrder(order, channel)`：

```
@Transactional collect/collectByUser          ← 外层事务
  └ findByIdForUpdate(orderId)                ← 订单行锁（PG row lock）
  └ markPaid
      └ 净额三分支判断（读）
      └ applyBestCouponForCollect（写：占券/核销）
      └ chargeOrder(order, channel)           ← ★ 渠道 HTTP（分钟级超时）
      └ setStatus(PAID) + freezePaidAmount    ← 写
      └ save
  └ recordSplit
```

问题（MA-274 Phase A 后的残留半边）：**订单行锁横跨整个渠道 HTTP**。弱网/渠道慢时：

- 该订单的所有并发入口（结算 finalize、余额冲抵、运营 collect）被阻塞至 HTTP 超时；
- 锁等待堆积 → 连接池被占满 → 拖垮同库其它写入；
- Redis 支付锁有心跳续租（Phase A），但 **DB 行锁没有续租机制**，事务多久锁就多久。

Phase A 已把 `chargeOrder` 自身去事务化（HTTP 零行锁），但调用方自带事务时 HTTP 仍包在**外层**事务里——去事务化对 join 进来的外层无解。

## 2. 目标形态：markPaid 拆三段

```
collect/collectByUser（去 @Transactional，改 Redis 锁内编排）
  │
  ├─ 段1 prepare（tx1，短事务）
  │    findByIdForUpdate → 状态/净额三分支判断 → **选券**（applyBestCouponForCollect 只算券，不核销）
  │    → 置 CHARGE_PENDING 痕迹（recordChargePending，已有）→ 提交（锁短暂持有，毫秒级）
  │
  ├─ 段2 渠道 HTTP（无事务，零行锁）
  │    chargeChannel(...)（从 chargeOrder 抽出纯渠道调用段）
  │    超时/异常 → 段3 的 fail 分支
  │
  └─ 段3 finalize（tx2，短事务）
       ├─ 成功：markPaid 收口（setStatus PAID / freezePaidAmount / save / recordSplit）
       ├─ 失败-可重试：清 CHARGE_PENDING 痕迹（finalizeChargePending 已有幂等），
       │   返回业务 409/502，订单回 PENDING 可重新发起
       └─ 失败-结果未知（超时但渠道可能已扣）：**不动状态**，保留 CHARGE_PENDING 痕迹，
           交既有 STALE 清扫腿（alertStaleChargePendingOps → 渠道查询对账）收敛
```

## 3. 补偿语义（关键决策点）

### 3.1 状态收敛矩阵

| HTTP 结果 | 段3 动作 | 残留 | 收敛路径 |
|---|---|---|---|
| 明确成功 | 正常收口 | 无 | — |
| 明确失败（渠道返回失败码） | 清痕迹 + 释放占券（反向核销） | 无 | 订单回 PENDING |
| **超时/连接中断（结果未知）** | **不动**（保留 CHARGE_PENDING + 占券） | 券被占 + 痕迹 | 既有 stale 清扫：渠道查询→成功则补收口 / 失败则清痕迹+还券 |
| 段3 自身崩溃 | 痕迹在 | 同上 | 同上 |

> 🔴 **2026-11-16 评审修正（Q3）**：上表「既有 stale 清扫」**不存在**——`alertStaleChargePendingOps`
> 只发 ops 异常，不查渠道、不 finalize、不退券。UNKNOWN 的自动收敛必须新增
> `ChargePendingReconciler`（见 §5.3），60 分钟只是告警阈值，不是收敛通道。

### 3.2 幂等键矩阵（全部已有，零新增）

- 渠道幂等：`CHARGE:<orderId>:<amount>`（restoreCompletedCharge）——重放不双扣 ✓
- 痕迹：`recordChargePending` / `finalizeChargePending`（REQUIRES_NEW 自提交，Phase A 已就位）✓
- 券核销：applyBestCouponForCollect 的占券幂等（重放不重复占）——**需在实施时核实**占券是否有幂等键（Q1，见 §6）

### 3.3 券的补偿（Q1 结论：**不需要“还券”**）

- **现状**：`applyBestCouponForCollect` 只选券、只写订单字段；真正的 `markUsed` 在渠道成功之后（当前在 `chargeOrder` 返回后），且 `markUsed` 已按「订单+券」幂等（同单同券已 USED 直接返回）。
- **修订**：段1 **不占券**，`markUsed` 留在段3 finalize（渠道成功之后）。UNKNOWN 时券仍是 UNUSED，无券可还；段3 失败分支也不需要“反向核销”。
- **必须补的**：`ChargePendingReconciler` 查到渠道 SUCCESS 后补收口时，**必须同时调用 `markUsed`**，否则「渠道已扣、券仍可用」会被二次折扣。段3 崩溃后的补偿同样走 reconciler。
- 若未来确实需要段1 预占，必须新增 RESERVED 状态+释放路径（新状态机），不在本批次。

### 3.4 余额冲抵分支不动

净额三分支的「冲抵已覆盖全额」支（alreadyPaidCents >= total）**无渠道 HTTP**，保持现状（同事务收口），仅随外层去 @Transactional 后由编排层重组。

## 4. 外层去事务化后的隔离性代价（如实）

拆段后 prepare/finalize 之间（HTTP 窗口）订单行**无行锁保护**，并发入口可能读到中间态：

| 并发入口 | 读到中间态的后果 | 防护 |
|---|---|---|
| 结算 finalize | 状态仍 PENDING → 正常拒绝（结算只收已支付）| 无需 |
| 另一次 collect | CHARGE_PENDING 痕迹判据 → 409「支付处理中」（已有语义）✓ | 既有 |
| 运营强取消 | CHARGE_PENDING 判据 → 拒绝或排队 | 实施时核实 forceCancel 对 CHARGE_PENDING 的行为（Q2）|
| 余额冲抵（钱包） | 竞态双冲抵 | `netCompletedCents` 在 finalize 重读（段3 判据重算）✓ |

Redis 支付锁（`runWithOrderPaymentLock` + LockHeartbeat 续租）横跨三段全程持有——**这是拆段后的主并发防线**，DB 行锁只保护两个短事务自身。Redis 锁续租机制 Phase A 已验证（LockHeartbeatTest）。

## 5. 实施步骤（一次批次收口）

1. `chargeOrder` 内抽出 `chargeChannelOnce(order, requestedChannel)`（纯渠道段，返回三态结果枚举：SUCCESS / FAILED / UNKNOWN）——不动现有签名，旧 chargeOrder 保留给非补缴调用方（支付分 DONE 回调等无外层事务路径）。
2. `markPaid` 拆三段；collect/collectByUser 去 `@Transactional`，编排移入锁体。
3. **券核销**：`markUsed` 留在段3 finalize（渠道成功之后）；**不新增反向核销**。`ChargePendingReconciler` 成功收口时同样调用 `markUsed`（订单+券幂等，重放安全）。
4. **强取消拦截（Q2）**：`SessionService.doForceCancelForOperations` 在加载会话后，若 `session.getOrderId()` 存在未收口的 `CHARGE_PENDING` 痕迹 ⇒ 409「支付处理中，请等渠道结果」；超过 10 分钟的陈旧痕迹由 reconciler 先收敛（成功补收口/失败清痕迹），运营不得用强取消绕。
5. **UNKNOWN 收敛（Q3）**：新增 `ChargePendingReconciler`（照 `RefundRetryScheduler`）：2 分钟静默后按 `out_trade_no=orderId` 查渠道；SUCCESS→补收口（CHARGE 流水 + PAID + freeze + recordSplit + markUsed，持订单支付锁）；NOTPAY/CLOSED→清痕迹允许重试；UNKNOWN→下一轮。告警 15 分钟首告、60 分钟升级。
6. **recordSplit 位置（Q4）**：保持在段3 finalize 事务内，**不**移到编排层尾部——保「订单 PAID + 分账」原子性；订单行已在段1 提交，REQUIRED join 可见 FK；配合 NESTED savepoint 防 DKE 打爆段3。
7. 形态钉测试：collect/collectByUser 无 @Transactional；markPaid 内 HTTP 段方法无事务注解；痕迹两腿 REQUIRES_NEW（已有钉保活）；forceCancel 对 CHARGE_PENDING 409；reconciler 三态。
8. 三态负向：外层复挂 @Transactional ⇒ 红；HTTP 段塞回事务 ⇒ 红；forceCancel 去守卫 ⇒ 红。
9. 真栈弱网回归：mock 渠道延迟 > 行锁观测窗口，验证 `pg_locks` 无长持锁；UNKNOWN → reconciler 收口。
10. 台账 MA-274 彻底关闭 + 报告 §20。

## 6. Q1–Q4 评审结论（2026-11-16，已按源码复核）

### Q1 占券幂等性 —— **前提修正：没有“段1 占券”**
- `applyBestCouponForCollect` 只选券/写订单字段，不核销；`markUsed` 在渠道成功之后，且已按「订单+券」幂等（同单同券已 USED 直接返回）。
- **结论**：`markUsed` 留在段3；UNKNOWN 时券仍 UNUSED，无需还券；**reconciler 成功收口必须补 `markUsed`**。不新增 RESERVED 状态。

### Q2 强取消对 CHARGE_PENDING —— **现状不拦，必须加**
- `SessionService.forceCancelForOperations` 只判会话 ACTIVE + 释放预授权，拿的是会话锁，不查订单/CHARGE_PENDING。
- **结论**：`doForceCancelForOperations` 对存在 `CHARGE:PENDING:<orderId>` 痕迹的订单返回 409；>10 分钟的陈旧痕迹交 reconciler 收敛，运营不得绕。

### Q3 UNKNOWN 清扫阈值 —— **60min 只是告警，不是收敛**
- `CHARGE_PENDING_ALERT_MINUTES=60`；`alertStaleChargePendingOps` 只报 ops 异常，不查单/不 finalize/不退券。
- **结论**：新增 `ChargePendingReconciler`（2min 静默查单；SUCCESS 补收口 / NOTPAY·CLOSED 清痕迹 / UNKNOWN 下轮）；15min 首告、60min 升级。设计稿 §3.2「零新增」对 UNKNOWN 路径不成立。

### Q4 recordSplit 在 REQUIRED 下的 join 复核 —— **不要移出段3**
- `SettlementOrderFinalizeService` 刻意不 catch `recordSplit`（失败随结算回滚）；移出编排层尾部会丢这条不变量，失败只剩 `REVENUE_SPLIT_MISSING` 巡检。
- 段3 时订单行已在段1 提交，REQUIRED join 可见 FK；配合 `NESTED` savepoint（`RevenueSplitService.insertSplitNested`）DKE 不会打爆段3，`findSplitAfterRace` 能读回并发已提交行。
- **结论**：按 §2 放段3；§6 原 Q4 的“移到编排层尾部”作废（§2 与 Q4 原表述矛盾，以 §2 为准）。

## 7. 验收标准

- 结算/补缴链全部单测绿（含既有 58 例支付套件）+ 新增拆段用例；
- 形态钉 + 三态负向全过（含 forceCancel 409、reconciler 三态、券不双核销）；
- 真栈：`pg_locks` 在渠道 HTTP 期间（延迟 5s+）无订单行锁；UNKNOWN 后 reconciler 能补收口/清痕迹；
- CI build + integration 全绿；预检门禁全绿。
