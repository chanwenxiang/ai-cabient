# A4 · `claimForPayout` 并发抢行：活库可达性与已落地证据（2026-11-08）

> 起因：用户批准「A 分类都可以做」。A4 = `claimForPayout` 并发竞态测试。
> 本文如实记录**能证明的**与**证明不了的**，不把未跑的轮次写成已跑。

## 一、被测闸门是源码级确认（非猜测）

`claimForPayout` 是一条**单条条件 UPDATE**，是单据没有 `@Version` 时唯一的本地原子闸门：

- `services/trade-service/.../mapper/MerchantWithdrawRequestMapper.java:30-36`
  - `UPDATE merchant_withdraw_request SET status='PAYING', updated_at=? WHERE request_id=? AND status=?`
- `LineWithdrawRequestMapper.java:30-36` 同形镜像。

返回 1 = 拿到出款权；返回 0 = 状态已被别人改走，**绝不可向渠道转账**。
服务层在 `MerchantWithdrawService.java:660` 用 `== 0` 判并发重入 → 409。

## 二、已有落地证据（真实、可再跑）

| 证据 | 位置 | 作用 |
|---|---|---|
| `WithdrawClaimForPayoutSqlIT`（3 例：旧值匹配可抢 / 同旧值再抢必须 0 行 / **并发两方恰有一方赢**） | `services/trade-service/src/test/java/com/aicabinet/trade/integration/WithdrawClaimForPayoutSqlIT.java` | 真 Postgres 16 实跑该条件 UPDATE，含并发断言 |
| CI integration job 纳入 trade-service | `.github/workflows/ci.yml:505-506`（`mvn -B verify -pl services/device-service,services/trade-service,services/jiangyi-gateway -am`） | 确认该 SqlIT **在 CI 真正执行**（AU-178 修复，见 ci.yml:501-504） |

⇒ 「抢出款权」那条**mapper 生成的 SQL 被真实数据库执行过**，且并发语义在库级被钉死。
这不是单测里的 mock（mapper 为 mock 时 `update(null, lambdaUpdate()…)` 生成的 SQL 从未被 DB 执行）。

## 三、活库（UAT）剩余空白 —— 如实标为 blocked，不造数

实测 `aicabinet_uat`：

```sql
SELECT status, count(*) FROM merchant_withdraw_request GROUP BY status;
-- PAID count = 2          （request_id 1=MCH-DEFAULT ¥500.00、2=MCH-DEFAULT ¥1.00）
SELECT status, count(*) FROM line_withdraw_request GROUP BY status;
-- 0 行
```

**活库没有任何 `APPROVED` 提现单**，因此不可能在真实单据上制造「两个请求同时抢同一张 APPROVED 单」的竞态。

原因是**业务流把 APPROVED 窗口关死**（源码级，`MerchantWithdrawService.java`）：

1. `review()`（:353-364）→ `completeReview()`（:367-409）把单据置 `APPROVED`，
   紧接着**在同一次 `runWithMerchantWalletLock` 块内**调用 `executePayout(gate.requestId())`。
2. 唯一能让单据**停在 `APPROVED`** 的分支是备付闸 `platformFundService.gatePayout(...)`
   不足（:592-604）：日志 `MERCHANT_WITHDRAW_PAYOUT_BLOCKED`，注释原文
   「单据保持 APPROVED（充值登记后可再次 payout 重试）」。
3. 触发它需要**把平台资金池备付造亏**——这是资金写操作，不是测试脚本能安全外推到的「业务语义修改」。

⇒ UAT 上**无法在无损、不改资金语义的前提下制造 `APPROVED` 停留窗口**。
此为 A4 在活库上的**确切阻塞原因**，不编造跑通。

## 四、结论

| 层 | 状态 |
|---|---|
| mapper SQL 竞态（库级） | ✅ 已有真实 DB 证据 + CI 执行（`WithdrawClaimForPayoutSqlIT`） |
| 服务层状态守卫（`== 0` → 409） | ✅ 源码确认（`MerchantWithdrawService.java:660-663`）；仓库内含并发/重放单测（`MerchantWithdrawConcurrencyTest`、`MerchantWithdrawDoubleReviewTest`） |
| 活 UAT 端到端「两请求同抢一张真实 APPROVED 单」 | ⛔ 阻塞：活库无 `APPROVED`，且唯一到达路径需资金池造亏（资金写） |

⇒ **A4 记为「部分覆盖 + 活库阻塞」，0 新缺陷。** 不需要改任何业务代码；
若后续要在活库补端到端竞态，需用户明确授权「备付不足留 APPROVED」这一资金路径造数。
