# 三域钱包统一 · 运行期回归（隔离栈就地升级，2026-10-18）

栈：`docker compose --env-file .env.aicabuat -f docker-compose.full.yml -f docker-compose.uat-isolated.yml -p aicabinet-uat`
（宿主 28080 gateway / 25433 postgres / 26379 redis；**未碰共享栈** `ai-cabinet-*`）。
基线：该栈原为全新空库从 V1 建到 **V386**，本轮把 trade-service 换成 dev 头 `d5dee737` 镜像后**就地升级**，
⇒ 首次在**有真实数据**的库上执行 V388 六表回填 + V389 删表。

## 1. 就地升级留痕

```
Migrating schema "public" to version "387 - drop staff wallet frozen cents"
Migrating schema "public" to version "388 - unify three domain wallets"
Migrating schema "public" to version "389 - drop six legacy wallet tables"
Successfully applied 3 migrations from "386" to "389" ... now at version v389
```

升级前/后各导出一次钱包面（`before_legacy.txt` 38 行 / `after_unified.txt` 38 行，
商户名 `merchant_name` → `owner_snapshot_name`、`WITHDRAW` 重复 ref 15 行/6 个 ref 走部分唯一索引豁免），
按**归一化多重集**比对：**双向差 0**。

> 归一化列顺序必须两侧一致——第一轮误报 37 缺 / 37 多，是我把名字列只在一侧保留造成的，非迁移缺陷。

## 2. F2–F8 驱动器复跑（同一份数据，升级后）

```
"pass": 34, "fail": 0, "skip": 2, "blocked": 0
```
两条 SKIP 均为既有说明（F6 费用锁需要人造 PENDING 行；F2 依赖 F1 前置）。F4 提现链在统一表上逐态核对：
冻结 −60000 → approve 拆 `WITHDRAW_PAID −59640` + `WITHDRAW_FEE −360`（合计=毛额）→ reject `WITHDRAW_RELEASE +60000`
→ 同 requestNo 重复 409；流水 37→44 行，账户余额 91544 == 末条 `balance_after`，`frozen_cents` 归 0。

## 3. 生产巡检 SQL 真跑（`POST /api/v2/ops/admin/consistency/run`）

整轮巡检跑到底（日志「开始数据一致性巡检」→「数据一致性巡检结束」，无「中断」；
最后一步 `checkCrossLinkConsistency` 有报警 ⇒ 前面的钱包判据确实执行且未抛）。
`failCount = 8`，其中**钱包四条判据全静默**：`MERCHANT_WALLET` / `LINE_WALLET` /
`MERCHANT_WALLET_LEDGER_SUM` / `LINE_WALLET_LEDGER_SUM`（`data_consistency_record` 里 `%WALLET%` 零行）。

改写过的新 SQL 原文在库上直跑（`consistency-sql.sql`）：生产版 0 行；去掉 `HAVING` 的对照版
`MCH-DEFAULT expected=91544 actual=91544 ok=t`。

### 3.1 🔴 静默 ≠ 有效：LINE 探针做负向证明

本栈 `wallet_account` 只有 **1 个账户（domain=MERCHANT）**，LINE/STAFF **0 行** ⇒ 上面的「LINE 静默」
本身是**空转绿**，不构成证据。于是在隔离栈播一个线长探针（remark=`UAT-RECON-PROBE`）：

| 步 | 数据 | 巡检结果 |
|---|---|---|
| 播一致账 | 账户 800/冻结 800；流水 `COMMISSION +500(ba 500)`、`COMMISSION +300(ba 800)`、`WITHDRAW_FREEZE −800(ba 800)` | failCount 仍 8，**无 LINE 报警** ⇒ 备忘行剔除口径在统一表上生效（朴素 Σ 会算成 0≠800 恒假阳性） |
| 故意破坏 | 账户余额 800 → 1000 | **failCount 10**，新增两条：`LINE_WALLET 1000 vs 800`、`LINE_WALLET_LEDGER_SUM 1000 vs 800` |
| 清理探针 | 删 3 流水 + 1 账户 | failCount 回到 8；两条记录由 `resolveStaleFailuresIfComplete` 自动置 **FIXED** |

⇒ 改写后的 LINE/剔除备忘/最近流水三条判据**能红能绿能自愈**，不是在统一表上恒 0 的死 SQL。
STAFF 域本栈同样 0 行，且 `DataConsistencyService` **本来就没有 STAFF 钱包判据**（`git show 0c7765a5^` 同样零命中，
非统一引入的覆盖缺口）⇒ 补货员侧运行期证据仍只有单元/并发用例 + F4/F8 的运营台出账探针。
> **后续（同日）**：STAFF 缺判据这条已由三域泛化补上，并在同一隔离栈上用 STAFF 探针做了四段负向证明，
> 见 [`consistency-judge-fixes-2026-10-18.md`](./consistency-judge-fixes-2026-10-18.md) §3/§4。

## 4. 本轮顺带查出的两条**既有**巡检口径缺口（与钱包统一无关；已拍板修复）

8 条报警逐条落库核对后，**6 条是判据口径落后于写侧记账**（同一族，前例 commit `28a783a8`）：

| 条 | 判据 | 事实 | 定性 |
|---|---|---|---|
| 4×`PAYMENT_AMOUNT` | `:319` 只 `in ('CHARGE','ADJUST_CHARGE','REFUND')` | 4 单全为 `pay_channel=BALANCE`，收款写的是 **`PREAUTH_CAPTURE`**：`PAID 486` 期望 486 实际 0；`REFUNDED 686` 有 `PREAUTH_CAPTURE +686` + `REFUND +686` ⇒ 实际算成 −686 | **假阳性**。与已修的 F-10（`PaymentOperationMapper:116` 补 `PREAUTH_CAPTURE`）同一处遗漏，但**巡检侧没跟着修** |
| 2×`CROSS_LINK/RFINV` | 完成退款即要求 `REFUND/REFUND_KEPT` 库存流水 | 两条报警单 `inventory_deducted = f`（F3-FIND-1 修法 B 的未扣库存退货退款），压根没扣过库存 ⇒ 无流水才是对的；对照：另两条 `deducted=t` 的单有 `REFUND_KEPT/<order>:qty=2` 未报警 | **假阳性**。判据少一个 `AND o.inventory_deducted` 守卫 |
| 1×`CROSS_LINK/dispute` | 争议 OPEN 但会话订单已 PAID | F2 驱动器留下的未结案工单 | 测试数据残留 |
| 1×`POINTS_IDENTITY` | `member` user_id=1 累计 181 ≠ available+used+expired 149 | 差 32 分，本轮未查到底（表定位/键口径待确认） | **待查** |

钱包判据不受这两条影响（口径已在统一批里对齐）；它们只说明**运营台「数据一致性」页在真实数据上有 6/8 是噪音**，
会把真问题淹在假阳性里。改的是资金巡检的判据，属钱附近 ⇒ 当时只登记、等拍板。

> **2026-10-18 拍板：两条都修**，并顺带把候选 1（三域钱包判据泛化，含补货员）+ 候选 2（流水链连续性）并入同一轮。
> 修法、负向先行用例、隔离栈实跑（failCount **8 → 2**）与 UI 实证见同目录
> [`consistency-judge-fixes-2026-10-18.md`](./consistency-judge-fixes-2026-10-18.md)。
> `POINTS_IDENTITY` 那 1 条本轮**只查不改**（仍剩 32 分差未定口径）；`paid_amount_cents` 对账另起一轮。

## 5. 诚实边界

- 就地升级只证明「V386→V389 有数据可回填」；生产上线若从更早版本追上来，回填范围另需一次同法实测。
- LINE/STAFF 运行期面靠**探针**证明判据有效，不是靠真实业务写入（本栈无真实线长/补货员资金流水）。
- F1「结算绿」仍依赖 2026-10-19 真将邑柜；隔离栈模拟器 ws `code=1006` 秒断，不能代替。
