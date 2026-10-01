# P3-1 / P3-2 评估报告：双钱包栈收敛 · 营销三轨收敛

> **日期**：2026-10-01 ｜ **性质**：评估级（不直接改码），对应 `SYSTEM_COMPARISON_EASYGO_VS_AICABINET_2026-10-01.md` §14.1 的 P3-1/P3-2。
> **纪律**：全部现状经源码核实（`文件:行号`）；结论给出建议与分期，实施需另开单。

---

## Part A · P3-1 商户/线长双钱包栈

### A1. 重复度量化（已核实）

**服务面（9 个方法一一对应，语义逐行同构）**：

| 能力 | MerchantWalletService | LineWalletService |
|------|----------------------|-------------------|
| 开户/查户 | `ensureAccount :67` | `ensureAccount :59` / `findAccount :67` |
| 入账（幂等） | `creditIfAbsent :109` | `creditIfAbsent :124` |
| 入账冲正 | `reverseCreditIfPresent :137` | —（线长侧无冲正） |
| 普通入账/扣减 | `credit :195` / `debitIfAbsent :167` / `debit :236` | `credit :103` / `debit :164` |
| 提现冻结/释放/核销 | `freezeForWithdraw :285` / `releaseFrozen :334` / `consumeFrozen :381` | `freezeForWithdraw :213` / `releaseFrozen :262` / `consumeFrozen :309` |
| 账户锁 | `walletLockKey`+`tryLock :440` | 同型 `:368` |
| 流水带余额快照 | ledger `balance_after/frozen_after` | 同列 |

**表结构（除主键类型外逐列相同）**：`merchant_wallet_account/ledger`（V139:49-74，PK VARCHAR）vs `line_wallet_account/ledger`（V133:81-102，PK BIGINT）——账户（余额+冻结）与流水（entry_type/amount/balance_after/frozen_after/ref）两表完全同构。

**提现侧**：`MerchantWithdrawService`（payout :319、mockEnabled :387、PAYING 超时兜底 F3 :476-513）与 `LineWithdrawService`（:266 同款 mockEnabled）复制同一套生命周期语义；测试也成对复制（6 个 wallet/withdraw 测试类）。

**调用方半径（小，利于迁移）**：商户侧 2 个（`RevenueSplitService`、`MerchantWithdrawService`）；线长侧 4 个（`LineCommissionJob`、`LinePromoTaskService`、`LineManagerService`、`LineWithdrawService`）。

### A2. 方案分级

| 方案 | 内容 | 收益 | 风险 | 判断 |
|------|------|------|------|------|
| **A. 泛型账本原语** | 抽 `FundsLedger` 公共实现（owner 适配器），两域服务变薄壳（public API 不动，调用方零改） | 消 9 方法×2 重复，语义漂移根除 | 资金路径重构，须全链 A/B；泛化 owner 类型引入抽象税 | **暂缓**——仅当出现第三个钱包域时启动 |
| **B. 提现生命周期编排抽取** | 只抽 freeze→payout→超时 sweep→consume/release 公共编排（含 F3 语义单点化），账本服务各自保留 | 消最危险的那份重复（F3 双份实现是双重支出风险的温床） | 中；两域提现仍有差异字段（商户多费率/多商户绑定） | **建议下个迭代开单**（编号 P3-1b） |
| **C. 巡检兜底（立即）** | 一致性巡检加两条：商户钱包/线长钱包「余额=Σ流水」公式断言（同 P1-2 前置的 WAREHOUSE_LEDGER 模式） | 立即获得漂移可见性，零行为风险 | 无 | **建议立即做**（半小时级） |

### A3. 结论

重复是真的，但**不要现在做 A**：两域语义正在分化（商户侧有冲正/费率/多商户绑定，线长侧有佣金日结/赏金），过早统一会互相拖拽——旧系统「19 支付渠道一套 Service」的大而全正是反面教材。**建议 C 立即、B 下迭代、A 观望**。

---

## Part B · P3-2 营销三轨收敛

### B1. 三轨语义比对（已核实）

| | promotion_activity（V66） | ad_campaign（V174） | line_promo_task（V133） |
|---|---|---|---|
| 业务对象 | 消费者券/充值营销活动 | 媒体素材投放（柜机屏+小程序轮播） | 线长人力赏金任务 |
| 生命周期 | 创建→launch→claim（**预留预算**+分布式锁，`PromotionService.java:137-162`）→核销/过期 | DRAFT→RUNNING→STOPPED（素材+档期+播放打点去重 V191） | OPEN→DONE/CANCELLED（`LinePromoTaskService.java:20-22`）→**钱包入账**（BOUNTY→LineWallet） |
| 资金流 | 发券成本/充值补贴（无直接现金） | 无现金（媒体统计） | 真金入账线长钱包 |
| 打点 | 领取/核销 | IMPRESSION/COMPLETE/CLICK | 任务完成入账 |

### B2. 结论：**不收敛（关闭 P3-2）**

三者只是表层都「有状态字段和时间窗」，业务对象、生命周期、资金流向完全不同——收敛只会造出一个谁都要改的 god-domain（同旧系统教训）。唯一真实共享点是**预算管控**：目前仅 promotion 有 claim 预留预算，ad 与 line_promo_task 无预算概念；若未来要统一预算，抽一个独立「预算闸」小原语即可，不必合并三轨。

---

## 附：建议动作汇总

| 动作 | 编号 | 时机 |
|------|------|------|
| 钱包余额公式巡检 ×2（C 兜底） | P3-1a | 立即（可并入任意批次） |
| 提现生命周期编排抽取 | P3-1b | 下个迭代开单 |
| 泛型账本原语 | P3-1(A) | 出现第三钱包域再评估 |
| 营销三轨收敛 | P3-2 | **关闭**（不收敛；预算闸原语按需另议） |
