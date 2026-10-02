# F1 余额扣款竞态——设计稿与实施记录

> **日期**：2026-10-02 ｜ **来源**：三端全面审查 2026-09-27 §F1（「上线前必改」）
> **状态**：✅ 已实施并全量回归（2026-10-02；1382 测试 0 失败 0 跳过，含 27 个 Docker E2E 本地真跑）
> **关联**：`docs/THREE_END_FULL_REVIEW_2026-09-27.md` §F1/§11.3；既有护栏 `UnpaidOrderService.markPaid`（5d0c4866，2026-09-28）

---

## 1. 源码级机理（全部亲读核实）

### 1.1 事务链

```
finalizeOrder（结算事务）
 └ chargeOrder @Transactional（REQUIRED，加入同一事务）
    ├ captureForCharge @Transactional（REQUIRED，同事务）
    │   └ account FOR UPDATE：扣冻结=冲抵 capture，写 PREAUTH_CAPTURE 流水
    │     （🔴 recordFreezeOnly 落库时 operation.setOrderId(null) —— 冲抵行不挂订单）
    └ balanceLedgerService.change(-remainDebit)
        └ txTemplate（Spring Boot 自动装配，默认 REQUIRED，同事务）
          └ 余额不足 → throw ResponseStatusException(412)
```

### 1.2 对审查报告的两点修正（重要）

1. **多付在当前代码下不可达**：`change` 抛异常穿越 `chargeOrder` 的 `@Transactional` 代理 → 共享事务被标记 **rollback-only** → `tryChargeSettledOrder` 的 catch 吞掉异常后，finalize 提交时抛 `UnexpectedRollbackException` → **整个结算回滚**（capture/订单/会话状态全部回滚，门控冻结仍在）。真实症状是「**结算 500 + 假超时争议单**」（session 卡住→recognizing-expire→DISPUTED→人工），而非多付。报告的多付链是结构推断，漏了 rollback-only 语义。
2. **但多付的雷是真的，且护栏是哑的**：`markPaid` 的 F1 护栏按 `netCompletedCents(orderId)` 拦截，而 `PREAUTH_CAPTURE` 行 **order_id=NULL** 且类型不在净额统计内——**冲抵金额对净额完全不可见**。一旦任何人「顺手修」1.2-1 的 500（加 noRollbackFor / REQUIRES_NEW）而不同步修净额口径，多付立刻成立：PENDING 单挂净额 90 的隐形已付，补扣按 100 全额。护栏对真正的 F1 场景形同虚设。

### 1.3 竞态窗口

`detectUnpaidBeforeCharge`（无锁预检，`SettlementOrderFinalizeService.java:135`）通过后、`change` 的账户行锁生效前，并发扣减可用余额 → `change` 不足抛出 → 1.2-1 的 500 链。窗口 = 预检与扣款之间（毫秒级，但开门即购的并发形状真实存在）。

---

## 2. 方案：净额口径三件套 + 信号化不足

**设计原则**：让「capture 保留 + PENDING 差额待付」按业务意图达成（用户拿了货，冻结的 90 分理应保留抵扣），把不足从「毒化事务的异常」变成「业务结果信号」，并让净额看得见冲抵。

### F1-A 冲抵行挂单 + 净额可见
- `BalanceFreezeCommand` 增加 `orderId`；`PREAUTH_CAPTURE` 行 `order_id = orderId`（释放/冻结行仍为 null）；
- `netCompletedCents` 的 switch 计入 `PREAUTH_CAPTURE -> +amount`（本设计下该类型只存在于订单冲抵场景）。

### F1-B 不足信号化（不毒化事务）
- `doCaptureForCharge` 持有账户行锁，**锁内预判** `balance_after_capture >= remainDebit`：
  - 够 → 返回 remainDebit，由 `applyBalanceCharge` 调 `change`（同锁内串行，必成功；`change` 内部二次校验保留为防回归）；
  - 不够 → **不调 change**，结果标记 `remainderUnpaid=true`（无异常穿越任何代理边界，事务干净）；
- `applyBalanceCharge` 将信号上抛为 `BalanceInsufficientException`（该类 javadoc 本就声明「事务不应回滚已关门状态」——既有设计意图）；
- `chargeOrder` 加 `@Transactional(..., noRollbackFor = BalanceInsufficientException.class)`：capture 与已写流水在结算提交时保留，订单转 PENDING；
- `GlobalExceptionHandler` 映射 `BalanceInsufficientException → 412`（所有 change 调用方 UX 不降级；collect 路径的 `noRollbackFor` 已先行存在）。

### F1-C 净额补扣与取消守卫
- `applyBalanceCharge` 净额口径：`chargeAmount = total − netCompletedCents(orderId)`（fresh=0 行为不变；F1 单补差=10）；
- `markPaid`（补扣）：净额 ≥ total → 直接 PAID（冲抵已覆盖全额，无需再扣）；0 < 净额 < total → 走 chargeOrder（自动只补差额）；净额=0 → 现状；
- `cancelSingleExpiredOrder`（自动关单）：净额 > 0 的 PENDING 单**禁止自动取消**（取消=货回库但 capture 已扣，白付 90 需人工），转 `opsExceptionService.report` 转人工退款。

---

## 3. 测试计划

1. 单测（DisputeOrderRefundTest 同款纯 Mockito 模式）：部分冲抵+不足→PENDING 且净额可见；collect 补差→PAID；冲抵全额覆盖→直接 PAID；cancel 净额守卫；
2. `BalanceLedgerServiceTest`/`BalanceLedgerConcurrencyTest`：change 抛点类型变更的回归；
3. `UnpaidOrderService` 相关：markPaid 三分支；
4. 全量 mvn test（本机 1381+）+ 预检 + CI。

## 4. 决策记录

- 采报告方向 A（净额校验）并修正其前提（rollback-only 语义下先堵「500/假争议」窟窿，净额口径让未来的多付通道从结构上不存在）；
- 总开关类问题不存在（纯正确性修复）；production 无需配置变更。
