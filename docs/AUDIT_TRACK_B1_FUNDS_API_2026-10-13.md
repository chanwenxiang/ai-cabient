# Track B-1 — 资金域接口契约审计（2026-10-13）

> 范围：Payment/Order/Refund/Wallet/Withdraw×3/PlatformFund/Invoice/Account/LineManager 出款面。
> 基线：worktree 入库基线；⚠️ 主目录有未提交的 MerchantWithdrawService 等 13 文件改动，**提现/解冻相关结论待该批落库后复核**。
> 方法：只读子代理逐端点七项矩阵 + 主会话对 F-01/F-02 源码复核（已确认属实）。

## ① Controller × 七项矩阵（列序：①鉴权语义 ②越权/BOLA ③参数校验 ④幂等 ⑤状态机/事务 ⑥错误语义/泄漏 ⑦DTO 越面）

| Controller/端点组 | ① | ② | ③ | ④ | ⑤ | ⑥ | ⑦ |
|---|---|---|---|---|---|---|---|
| OrderController 全部 | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✘F-08 |
| PaymentController recharge/prepay/list/get/cancel | ✔ | ✘F-10 | ✘F-03 | ✔ | ✔ | ✔ | ✘F-08 |
| WeChatNotify / AlipayNotify | ✔(免登录+验签) | ✔ | ✔ | ✔(nonce/notify_id) | ✔ | ✘F-09 | ✔ |
| ConsumerBalanceRefund apply/listMine | ✔ | ✔ | ✔ | ✔(V343) | ✔ | ✘F-05 | ✘F-11 |
| AdminBalanceRefund list/review | ✔ | ✔ | ✔ | ✔ | ✔ | ✘F-05 | ✔ |
| MerchantWallet list/ledgers | ✔ | ✔ | ✘F-07 | NA | ✔ | ✔ | ✔ |
| MerchantWallet adjust | ✔ | ✔ | ✔(已修) | ✘F-06 | ✔ | ✔ | ✔ |
| MerchantWallet withdraw(代提) | ✘F-12 | ✔ | ✔ | ✘F-01 | ✔ | ✔ | ✔ |
| MerchantWithdraw list/payout-mode/review/payout | ✔ | ✔ | ✔ | ✘F-01 | ✘F-02 | ✘F-05 | ✘F-08 |
| MerchantWithdraw cancel | ✔ | ✔ | ✔ | ✘F-01 | ✘F-02 | ✔ | ✔ |
| LineWithdraw 全部 / LineManager/{id}/withdraw | ✘F-12 | ✔ | ✘F-09 | ✘F-01 | ✘F-02 | ✘F-05 | ✔ |
| OpsPlatformFund accounts/recharge/sweep/adjust | ✔ | NA | ✘F-04 | ✘F-06 | ✔ | ✔ | ✔ |
| InvoiceAdmin list/issue/reject/tax-profile | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔(税档脱敏好) |
| AccountController 全部 | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| AdminDashboard adjustBalance / recharge refund / order refund | ✔ | ✔ | ✘F-03b | ✔ | ✔ | ✘F-05 | ✔ |
| DevMock* 三个 | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔（mock-enabled 双闸门）|

## ② 问题清单

| ID | file:line | 描述 | 攻击/复现路径 | 判级 | 台账 |
|---|---|---|---|---|---|
| F-01 | MerchantWithdrawService.java:422-425；LineWithdrawService.java:292-294 | requestNo 幂等命中仅按全局 requestNo，不校验归属/金额 | 商户 B 传商户 A 的 requestNo → `findByRequestNo` 命中 → 直接返回 **A 的提现 DTO**（金额、状态、payeeAccountName、payoutRef），且 B 自己的提现被静默吞掉 | **P1**（主会话复核属实）| AU-006 |
| F-02 | MerchantWithdrawService.java:380-399、LineWithdrawService.java:225-244 | cancelFailed 双重缺陷：`@Transactional` 内取分布式锁（锁先于事务提交释放）；锁内状态守卫用**锁前快照** `request.getStatus()`(L386/L229) | 并发 payout()（markPaying→PAID 扣冻结）与 cancel()：cancel 按陈旧快照过 FAILED 守卫 → `releaseFrozen` 把 PAID 已消费冻结再释放 → 可提现余额虚增，**可复现资损** | **P1**（主会话复核属实）| AU-007 |
| F-05 | BalanceRefundService.java:702-703、MerchantWithdrawService.java:923-924、OpsMemberFinanceAdminService.java:~157 | 锁包装器把内部异常 `e.getMessage()` 原样塞进 500，GlobalExceptionHandler:31-38 透传 | DB 唯一键冲突回出 `duplicate key value violates unique constraint "..."` 约束名 | **P2** | AU-017 |
| F-03 | PaymentController.java:75 | `amountCents` 仅 `@Min(1)` 无 `@Max`；服务端上限 `RECHARGE_MAX_CENTS` ≤0 即整体失效(PaymentService:142-146) | 配置误设 0 → 单笔充值无上限 | P2（判级取决于存疑②）| AU-018 |
| F-03b | AdjustBalanceRequest.java:7 | `deltaCents int` 无限幅——商户侧 WalletAdjustRequest 已修（3dd6152f），消费者侧同类运营调账未同步收紧 | 凭证泄露时单笔可注入 ±2147 万元 | **P2** | AU-019 |
| F-04 | OpsPlatformFundController.java:46-89 | 裸 `Map` 接收金额：字符串→CCE 500；缺失按 0 再 400；无 @Max，recharge 负数无拦截 | 负数"充值"=无备注约束的变相扣减，与 adjust 审计口径不一致 | P2 | AU-020 |
| F-06 | MerchantWalletController.java:52-59 / OpsPlatformFund 写端点 | 调账/充值登记**无幂等键**（refId 每次随机）| 运营双击/网关重试 → 双倍调账 | P2 | AU-021 |
| F-07 | MerchantWithdrawService.java:162 | `ledgers(merchantId, limit)` 透传未夹逼 | limit=10^7 → 全表拖取 | P3 | AU-022 |
| F-08 | RechargeOrderDto（wxPrepayId/wxTransactionId/alipayTradeNo）+ toDto | 消费端 GET recharges/{id} 返回渠道内部凭证号 | 字段越面 | P3 | AU-023 |
| F-09 | LineManagerController.java:101-103 | `Long.parseLong(String.valueOf(body.get(...)))` null→NFE 回显原始文案；无 @Valid | 脏 400 + 轻微泄漏 | P3 | AU-024 |
| F-10 | PaymentService.java:821、831 | 他人充值单 403 vs OrderService:66 的 404 —— 存在性 oracle、口径不一 | 枚举 22 位单号（难度高）| P3 | AU-025 |
| F-11 | BalanceRefundService.java:552 | `setFailReason(cause.getMessage())` 渠道原始错误经 listMine 直达消费者 | 泄漏渠道报文 | P3 | AU-026 |
| F-12 | MerchantWalletController.java:61、LineManagerController.java:97 | 出款动作挂 `…:adjust`/`…:edit` 权限码；payout 与 review 共用 `…:review` 无职责分离 | RBAC 语义错位 | P3 | AU-027 |

## ③ 存疑（需人工确认）

1. **MerchantWithdrawService.adjust:185-192**：大额调账审批流是**事后登记**（credit/debit 先执行、approval 后 start）。"留痕复核"设计 ⇒ 降 P3；预期"先审后动钱" ⇒ P0 级门禁缺失。**→ DC-5 拍板**
2. `RECHARGE_MAX_CENTS/BALANCE_REFUND_MAX_CENTS ≤0=关闭上限` 是运营开关还是 fail-open（决定 F-03 判级）。**→ DC-6 拍板**
3. WeChat notify `findById(outTradeNo)` 未命中静默 204 吞通知（PaymentService:354）——跨环境串号时应告警而非 ACK。**→ F 轨接真前 checklist**

## 审过且干净

OrderController 全部端点（归属查库比对、状态机守卫、rate-limit、锁+行锁重查）；PaymentController prepay/cancel/get（幂等键+锁+金额一致+归属）；WeChat/Alipay notify 验签链（先验签后占 nonce、mchid/appid/seller_id 比对、notify_id 去重）；ConsumerBalanceRefund apply（V343 幂等、冻结/扣减三段式）；InvoiceAdmin（税档脱敏）；AccountController；DevMock*（双闸门）；金额全链分单位 long/int，无 double/float 参与资金运算。

## F-01/F-02 修复方向（边审边修，待用户确认后动手）

- **F-01**：幂等命中后比对 `merchantId/managerId + amountCents`——一致才视为同请求重放返回自身 DTO；不一致抛 409「requestNo 冲突」（requestNo 应命名空间化到商户，如 `{merchantId}:{no}`）。
- **F-02**：cancelFailed 锁内**重查行**（`withdrawMapper.selectById` 或 FOR UPDATE）后再判状态，禁止用锁前快照做资金守卫；并按既有「先锁后事务」规范（SettlementSettleOrchestrator:98-102 同族修法）把 `@Transactional` 移入锁回调。
- 两者均**负向验证先行**：先写会红的并发/越权测试。
