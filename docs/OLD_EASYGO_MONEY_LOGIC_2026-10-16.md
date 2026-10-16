# 老系统（easygo）收费与资金逻辑全景 —— 2026-10-16 一手取证

> 触发：用户 2026-10-16 追问「这些逻辑在老代码里都有的，你看看关于收费和钱相关的逻辑，我也不知道我说的对不对」。
> 纪律：四路子代理分片扫（账户原语 / 结算分成 / 提现闭环 / 费用目录），**关键结论我逐行复核**；
> 未复核的行号一律标「子代理自报」，不得据此下设计结论。
> 本文只做事实与对照，拍板口径见 [`AUDIT_F2_F3_F4_DECISION_BRIEF_2026-10-16.md`](AUDIT_F2_F3_F4_DECISION_BRIEF_2026-10-16.md)。

## 0. 一句话

用户对「0 就是不收 / 没有」的记忆**为真**，老系统里它有两种实现（金额为 0 显式跳过、配置开关 status=N 不进循环），
押金甚至把 **0 元做成一档合法配置**。但用户说的「这些逻辑老代码里都有」**只对了一半**：
欠费台账、欠费锁柜、提现审核、统一余额流水、按销售分账收平台费——这五样老系统都没有，是我们的新增设计，
文档与竞品台账里凡写过「老系统同款」的地方都得勘误。

## 1. 资金原语（我亲验）

| 面 | 事实 | 证据 |
|---|---|---|
| 余额改动唯一原语 | `addMoneyToAccount(userId, 增量)`：读账户→`cashAvailable = 现值 + 增量`→save，乐观锁 `@Version` 冲突重试 3 次（每次 sleep 1s），返回 `!isSaveBlocked` | `ego-automat-domain/.../opertion/domain/service/OptAccountService.java:1473,1482-1485,1491-1505` |
| 第二个扣款原语 | `deductMoneyFromAccount(userId, 负增量)` 在费用基类里另有一份，逻辑同上，但**返回 `AccountRecord`**（余额前值/后值） | `.../servicefee/timer/ServiceCharge.java:199-244` |
| 余额可负 | 两个原语都**没有任何下限校验**，商户侧扣款传负数是常规用法 | `OptAccountService.java:1482`、`ServiceCharge.java:211`；押金负扣 `.../equipment/service/EquipmentService.java:1175` |
| 余额改动不落流水 | `AccountRecord` 只是 POJO（**无 `@Entity`/`@Table`**），`deductMoneyFromAccount` 把它 return 出去但从不持久化；`addMoneyToAccount` 更是什么都不写。⇒ 老系统**没有统一余额流水表**，只有各业务自己的账单行 | `.../servicefee/view/AccountRecord.java:3-5`；`ServiceCharge.java:234-244` |
| 负余额的代价 | 只挡**提现**：`if (0 > cashRemaining …) → BALANCE_LIMIT`；另有运营手设的 `whetherWithdrawals="NO"` → 423 LOCKED | `.../withdrawal/controller/WithdrawalController.java:199-204,209-214` |
| 负余额不挡消费 | 消费者侧「余额不足,请充值」是**另一套**（C 端充值余额），与商户运营余额无关 | 子代理自报 `.../onlinepay/controller/OnlinePayController.java:110,158`（未复核） |

## 2. 入账与分成（我亲验）

| 事实 | 证据 |
|---|---|
| **入账是毛额**：日结算把当日销售额汇总直接加进余额，**不扣任何平台费** | `.../opertion/domain/BillGenerationTask.java:213`（`cashAvailable = 余额 + amountIncomeTotal - hasAddedAmount`） |
| **平台收入来自「提现手续费」**：`transFee = 提现额 × 千分比 / 1000`，`ROUND_CEILING`，从**打款额**里扣；余额按**提现全额**扣 | `WithdrawalController.java:221,223,242` |
| 费率可按商户覆盖，标准值 6‰；运营商账户免手续费 | `.../opertion/domain/utils/ConstantUtils.java:7`（`DEDUCTION_PER_MILLAGE_RATE = 6`）、`WithdrawalController.java:217-218` |
| 重跑同一天的幂等 = **差额补记**：先查 `ego_opt_bill_settlement_daily` 拿「已入账额」`hasAddedAmount`，再 `+ 本额 - 已入账` ⇒ 同日重跑会把余额**修正到最新汇总**，而不是跳过 | `BillGenerationTask.java:154-158,213` |
| 幂等的 DB 兜底 = 复合唯一键 | `ego_opt_bill_settlement_daily` `UNIQUE(user_id,date)`、`ego_opt_machine_mobilecard_charge` `UNIQUE(machine_id,date)`、`ego_opt_machine_basicfee` `UNIQUE(user_id,date,fee_type)`：`doc/db/opt_9th.sql:39`、`doc/db/M9_optimization.sql:5-38`（后者我亲验） |

⇒ **架构性差异只在「入账基数」，不在「提现费的扣法」**：
老系统「下单全额入账 + 提现时收手续费」；我们「下单即分账入账（`order_revenue_split.merchant_cents` 是分账后份额）+ 提现再收手续费」。
提现这一层两边同形：申请额全额出账、到账=申请额−手续费（我们 `consumeFrozenSplit` 一次扣 net+fee，记 `WITHDRAW_PAID -net` + `WITHDRAW_FEE -fee` 两行）。
2026-10-16 勘误：本文初稿把「我方种子费率=0」写成「我们靠分账、老系统靠提现费」，容易读成「再收提现费=双重收费」——**不成立**，
入账基数不同是结算模型差异，与提现费不是同一笔钱。
**拍板**：全局 `merchant.withdraw.fee_bps` 种子 0 → **60（6‰，取老系统标准档）**，封顶留 0=不封顶
（单笔上限 ¥10000 / 单日 ¥40000 已是天花板，6‰ 最多 ¥60，配 ¥25/¥50 封顶是死参数），
按商户覆盖沿用既有 `merchant.withdraw.fee_bps.{商户号}`（V381 翻种子 + 提现页「提现费率」标签可查改）。
这条差异仍使得「我们扣了费用再挂应收 = 老系统同款」这类表述站不住。

## 3. 「费用可以为 0 = 不收 / 没有」的老系统实现（用户的判断，逐条核实）

| # | 事实 | 证据 |
|---|---|---|
| Z1 | 流量费逐台配置 `amount`，**0 原样透传**，只有**负数**才兜底 100 分 | `.../mobilecard/domain/MobileCardFeeGenerationTask.java:112-116`（`if (amount < 0) { amount = 100; }`，旁边 `amount > 200` 那条被注释掉） |
| Z2 | 0 元当天**仍写一行**：`addMoneyToAccount(userId, -0)` 返回 true，随后落 `amount=0` 的流量费行 ⇒ 行是「今天这台已核算」的存在性键，不代表欠费 | `:148` → `OptAccountService.java:1482,1504` → `:155-162` |
| Z3 | 商户日汇总当天全空也写 `amount_total=0` 行 | `MobileCardFeeGenerationTask.java:211-238` |
| Z4 | 租金/巷道费的「不收」= **配置开关**：只有 `status='Y'` 且有配置行的柜机才进循环；停收把 `status` 置 `'N'` | `.../servicefee/timer/ServiceChargeTask.java:47,127,206-223` |
| Z5 | 🔴 单笔结算入口**显式把 0 当「不收」**：`rentAmount == null \|\| rentAmount <= 0 → return false`（不扣钱、不写行） | `.../servicefee/timer/ServiceCharge.java:54-60` |
| Z6 | 押金把 **0 元做成一档合法配置**（`GUARANTEE_CASH_0 = 0`，0/500 档返还单位额为 0 ⇒ 不返还） | `.../opertion/domain/view/GuaranteeCashType.java:7,23-25` |
| Z7 | 🔴 反面教材：配置行存在但 `amount=NULL` 时 `amount < 0` 拆箱 NPE，循环无捕获 ⇒ **整批任务中断** | `MobileCardFeeGenerationTask.java:107,114`；模型 `OptMachineMobilecardChargeConfig.java:25`（`Integer amount` 无 `nullable=false`） |

⇒ 我们的实现取 Z5+Z6（0/NULL 一律「不收」，且 **NULL 必须在进扣款路径前兜住**，避 Z7），
不取 Z2/Z3 的「0 也留一行」——因为老系统留行的粒度是**逐台/逐费种**，0 行有「这台今天核算过」的意义；
我们的台账粒度是**商户×日×费用类型**，留 0 行只是噪音。（阿里云 0 元账单、Cloudflare `$0 invoice` 同理，见简报 §6。）

## 4. 提现闭环（我亲验状态机 + 失败处置）

| 面 | 事实 | 证据 |
|---|---|---|
| 状态只有 3 个，**没有审核态** | `PROCESSING / SUCCESS / FAILED` | `.../opertion/domain/view/WithdrawalHistoryStatus.java:3-7` |
| 无冻结/解冻 | 账户实体只有 `amountCashAvailable/cashNoTicketTotal/cashNoAssessmentTotal/version`；申请时直接扣可用余额 | `WithdrawalController.java:242`、`OptUserAccount.java:18-42`（子代理自报行段） |
| 限额 | 微信 V2：`amount < 1000 \|\| amount > 20000`（¥10–¥200）、每日 5 次；V3：¥10–¥1 万 | `WithdrawalController.java:200,171,376`（¥4 万日累计 `:998` 为子代理自报） |
| 🔴 失败**不退款** | `NOTENOUGH` 与「其它失败」只把记录置 FAILED，**已扣的余额不退**；只有 `OPENID_ERROR`/`NAME_MISMATCH`/`V2_ACCOUNT_SIMPLE_BAN` 才 `rollbackWithdrawalInfo`（且**删除**记录行而非作废） | `WithdrawalController.java:308-333`、`:297-307`、`:985-994`（末段子代理自报） |
| 🔴 异常被吞 | 网络/证书异常只 `logger.error`，函数末尾仍 `code 200 / msg SUCCESS`，记录留在 PROCESSING | `WithdrawalController.java:334-341` |
| 重试 | 只扫 FAILED，复用同一条记录 id、**不再扣款**直接重发；单号默认重新生成，仅 `SYSTEMERROR` 复用旧单号；入口是命令行参数 `w` | `.../opertion/domain/timer/WithdrawalAutoRetryTask.java:56-57,73-74,84-86,113`、`ego-automat-bill-timer/.../Application.java:109-112` |
| 人工可任意改写 | m9 后台 `updateSettlementAmount` 直接 `setAmountCashAvailable(revisedBalance)`，**无权限注解、无下限、异常只 printStackTrace** | `m9managepro/.../user/controller/IndividualManageController.java:418-433` |

⇒ 提现这块**不能**当对标：老系统「失败不退款 + 异常仍报 200 + 人工裸改余额」三连，正是我们
`withdrawal:review` 审核链、钱包流水 `WITHDRAW_FREEZE/RELEASE`、`FUND_GUARDED_KEYS` 存在的理由。

## 5. 老系统**没有**的东西（否定式结论，须写进竞品台账）

| 我们现有/新增的能力 | 老系统实况 |
|---|---|
| 费用台账 `PENDING/SETTLED` 应收模型 | 没有：费用表**无 status 列**，扣款成功才写行（行即收据）→ 简报 §1 L4/L5 |
| 欠费自动锁柜 / 停售 | 没有：全域搜 `停售\|锁柜\|暂停销售\|sales.?lock\|欠费\|欠缴\|arrears\|停机` 只命中①管理员手点 `pauseMachineSale`（`m9managepro/.../MachineListController.java:143,149`，无费用判断）②运营商物联卡侧「到期停机/超流量停机」（`EgoIotCardInfoServiceImpl.java:359`，非平台费用计算）。扣费失败一律 `continue`，无任何停售副作用 |
| 提现审核（审批人 / 待审状态） | 没有：仅 3 态 + `whetherWithdrawals` 总开关 |
| 统一余额流水账 | 没有：`AccountRecord` 是未持久化的 POJO，`addMoneyToAccount` 不写流水 |
| 按销售分账收平台佣金 | 没有：入账给毛额，平台收入=提现手续费；运营商 2%（20‰）是**另记另加**给运营商账户，不从商户额里切（子代理自报 `BillGenerationTask.java:672-675,725`，未复核） |
| 退款冲正回退佣金 | 没有：只有 `BALANCE_REFUND` 直接减余额（无充分性校验，可负），已结算日不重算 ⇒ 无佣金/手续费冲正（子代理自报 `MachineOrderRefundComponent.java:92-96,129-135`）——我们的 V361 佣金冲正是**超出老系统**的能力 |
| 「0.00 不发结算通知」 | 老系统**全员发**（仅判「账单行存在 + 有 openId」，无金额过滤）⇒ 我们的「0 不发」是有意偏离，已在 CB-020 记录（子代理自报 `BillGenerationTask.java:354`、`TemplateMsgSendService.java:588`，未复核） |

## 6. 对当前实现的影响

1. **F-3 决策 A（分/天 + 全部柜机照收）得到加强**：老系统流量费本来就是逐台逐日、不区分 `machine_use_type`；「月额÷天数」是我们的混合发明。
2. **F-2 决策 A（扣成功即 SETTLED，0 不留行）与 Z4/Z5 同构**：老系统「不收」= 不扣钱也不写行；「扣成功」= 才写行。
3. **0 必须是一等态**：不给任何默认费率（撤回我先前「默认 1.00 元/天」），`NULL` 与 `0` 都在进扣款路径**之前**兜住（Z7 的 NPE 教训）。
4. **重跑语义要写清**：老系统日报账单用「差额补记」，而我们的日摊是 `refId=日期` 的「存在即跳过」——
   重跑**只能补没扣的日子，不会追平当天改过的费率配置**。手动重跑入口的说明文案必须写明这点，否则运营会以为能改历史。
5. 竞品台账与 `PROJECT_KNOWLEDGE` 里凡「欠费锁柜/费用台账=老系统同款」的表述一律改为「我方新增设计（无老系统先例，柜机厂商亦无公开规格）」。
