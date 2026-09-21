# D1 · F5 储值等级：竞品对比与选型（含逐行取证）

> 批次：2026-09-21 ｜ 上游：`docs/COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md` F5、
> `docs/ROADMAP_DECISIONS_2026-09-21.md` **D1**。
> **口径**：本文现状一律回源码/现网**重新取证**（附 `文件:行`），不采信任何文档的既有状态标注。

---

## 0. 结论（先给答案）

**选 A：复用 `member_level_rule`，新增一维「累计充值」门槛，与既有「累计消费」维度**取高**。**

两个子决策：

1. **按累计充值（流量），不按余额（存量）** —— 理由见 §2，这条决定了改动是不是「小」。
2. **同一套等级框架、取高，不建第二套** —— 与既有 `pointsRate` / `priceDiscountPct` 权益直接复用，运营台一张表维护。

---

## 1. 竞品 / 行业对比

| 对象 | 升级依据 | 保级 / 降级 | 给什么权益 | 来源 |
|---|---|---|---|---|
| **便利蜂**（无人货架） | 储值（**充 100 送 20**） | 未披露 | 赠金；储值金额占比 38%，储值用户复购周期缩短至 3 天 | 人人文库《2026 中国无人便利店技术方案与运营模式对比研究报告》 |
| **友宝**（售货机） | 集团充值到「友宝钱包」 | — | 余额 + 免费券 / 代金券 | `uboxol.com/help/recharge.html` |
| **丰e足食**（顺丰系 AI 柜） | 企业免费投放，走 B 端福利代充 | — | 企业代充 | `feng1.com/zh-CN/join` |
| **无人零售 SaaS 通行解**（DOZZON 等） | 储值卡 + 赠送金**双账户** | — | 折扣 / 免密额度 / 新品优先体验 | `dozzon.com/article-114.html` |
| **京东到家 钻石会员** | **年消费额**（>5000 元） | — | 折扣率平均低 15% | 同上报告 |
| **储值会员制度行业通行解** | **累计充值**升级（**500 银 / 1000 金 / 3000 黑金**） | **消费额保级**（如金卡每季储值消费满 300） | 折扣 / 生日礼 / 优先预约 | 软盟《储值会员制度设计全案》`news.softunis.com/73111.html` |
| **本项目（现状取证）** | 仅 `totalSpent`（累计消费）：`MemberService.java:107-123` | **无降级**（`applyMemberStatsDelta:92` 只加不减） | `pointsRate` 积分倍率、`priceDiscountPct` 会员价（上限 9 折，`MemberService.java:288`） | — |

### 三条可直接落地的行业共识

1. **储值维度用「累计充值」而非「余额」** —— 「储值即升级、消费才保级」。
2. **赠金不参与积分** —— 通行规则「本金计分、赠金不计分」，否则等于折上折。
3. **等级折扣与储值折扣只取其一** —— 避免优惠穿透毛利底线。

---

## 2. 为什么按「累计充值」而不是「余额」

| | 余额（存量） | **累计充值（流量）** |
|---|---|---|
| 单调性 | 会随消费下降 ⇒ 每次扣款后都要重算 | 单调递增 |
| 与现有语义 | **冲突**：现有等级**只升不降** | **一致** |
| 必须新增的机制 | **保级 / 降级**（＝选项 B 的复杂度） | 无 |
| 行业口径 | 少见；通常用于**保级**条件，不用作**升级**条件 | **通行解** |

🔴 关键判断：选「余额」会把 D1 从「小改动」悄悄变成「引入保级机制」——
那正是**为了规避才否掉选项 B** 的复杂度。所以：**按累计充值**。

---

## 3. 参数设计（与既有门槛对齐）

现网种子门槛（`V99__member_marketing_consumer.sql:57-61`）：
`NORMAL 0` / `SILVER 1000` / `GOLD 5000` / `PLATINUM 10000`（累计消费，元）。

储值门槛取**消费门槛的一半** —— 预付款的现金价值高于已发生的消费，理应更易达标：

| 等级 | `min_spent`（已有） | `min_recharge`（新增，建议值） |
|---|---|---|
| NORMAL | 0 | 0 |
| SILVER | 1000 | **500** |
| GOLD | 5000 | **2000** |
| PLATINUM | 10000 | **5000** |

量级依据：便利蜂「充 100 送 20」是**入门促销**档、不是等级档；美业通行 500/1000/3000 面额更高（客单价更高）。
本方取 500/2000/5000，对齐「办公场景白领月消费 ≈ 400–600 元」的实际量级。**该值运营台可调，仅作初值。**

---

## 4. 🔴 必须同时提示运营的风险：赠金 × 会员价 = 双让利

- 充值赠送开关：`SystemConfigService.java:52` `recharge.bonus.percent`（**默认 0 = 关闭**，`:667`）。
- 会员价折扣：`MemberService.java:288` `priceDiscountPct`（上限 9 折）。
- 两者**当前可同时生效**，即「充 100 送 20」+「会员 95 折」= 折上折。

行业解是「**只取其一**，或浅叠加（折上再 95 折）」。
⇒ 本设计**不新增任何折扣权益**，只让用户**更快达到**既有权益；并在运营台明示二者叠加对毛利的影响。

---

## 5. 现状取证（全部重新复核）

| 项 | 结论 | 证据 |
|---|---|---|
| 充值赠送 | ✅ 已落 | `PaymentService.java:1099` `applyRechargeBonus`；幂等键 `recharge-bonus:<orderId>`（`:1104`）；按分向下取整 `:1114` |
| 充值主入账 | ✅ 已落 | `PaymentService.java:1065` `balanceLedgerService.change(..., "RECHARGE", …, "recharge-credit:<orderId>", …)` |
| 余额优先支付 | ✅ 已端到端 | `PayScoreService.java:397-398`（`userPref == BALANCE` 短路）、`:394`（金额 ≤0 也走余额） |
| 等级框架 | ✅ 已存在 | `MemberLevelRule.java:10-32`；命中逻辑 `MemberService.java:107-123`；驱动点 `MemberService.java:131` `onOrderPaid` |
| **「储值等级」** | ❌ **仍 0 命中** | 全仓唯一出现处＝竞品文档自身 |

### ⚠️ 顺带发现（既有问题，非本批引入）

**dev 库 `member_level_rule` 当前 0 行**：

```
docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -tA -c \
  "select count(*) from member_level_rule;"     → 0
```

⇒ `calculateMemberLevel` 恒落 `NORMAL` ⇒ **会员等级 / 积分倍率 / 会员价在 dev 实际并未生效**。
根因：种子是 `INSERT … ON CONFLICT (level_code) DO NOTHING`（`V99:57`），**只在首次生效**；行一旦被删（迁移不会补）就永久缺失。
`V163:30-33` 那四条 `UPDATE … WHERE level_code = …` 也**不会**在 0 行时造出数据。

⚠️ 这直接影响 D1 的落地判据：若只写 `UPDATE … SET min_recharge = …`，在 0 行的库上**什么都不会发生**（＝「写了没跑」）。
⇒ 迁移需按「列 + 默认值」两步走，且默认值填充要写成**不覆盖运营已调值**的形式。

---

## 6. 改造点（待实施）

1. **`V283__member_level_deposit_dimension.sql`**
   - `ALTER TABLE member_level_rule ADD COLUMN IF NOT EXISTS min_recharge NUMERIC(12,2)`（**可空**＝未启用）
   - `ALTER TABLE member ADD COLUMN IF NOT EXISTS total_recharged NUMERIC(12,2) NOT NULL DEFAULT 0`
   - 填默认档：`UPDATE member_level_rule SET min_recharge = <档位> WHERE level_code = '<X>' AND min_recharge IS NULL`
     （**`AND min_recharge IS NULL` 是硬要求**：绝不复写运营已调过的值）
   - `COMMENT ON COLUMN` 双语说明（对齐 `V237` 的列注释口径）
2. `MemberLevelRule.java` 加 `minRecharge`；`Member.java` 加 `totalRecharged`。
3. `MemberService`：
   - `calculateMemberLevel(totalSpent)` → 双维度**取高**（两条各按 `sortorder` 取大）
   - 新增 `onRechargePaid(userId, amountCents, orderId)`：`runWithMemberUserLock` + `findByUserIdForUpdate` 累加 `total_recharged` 并刷新等级（与 `onOrderPaid:131` 同款）
4. `PaymentService.java:1066` 主入账之后调用 `memberService.onRechargePaid(...)`（**放在 `applyRechargeBonus` 前后需与「订单置 PAID 之前」的既有次序约束一致**：失败即连带抛出，订单保持 PENDING，重试凭幂等键补记）。
5. `MemberLevelRuleDto`（`common-core:5-15`）加 `minRecharge` ＋ 运营台 `clients/admin-vue/src/views/growth/MemberLevelsView.vue` 加一列
   → ⚠️ **改 `.vue` 必须重建 `static/admin`**（产物名＝哈希），与并发会话有争用，见 §7。
6. 单测：消费高 / 储值高**各一例**取高；幂等；`min_recharge IS NULL` 不参与；0 元与负值不触发；不改动 `sortorder` 语义。

---

## 7. 实施时的两个外部约束（不是设计问题）

1. **`static/admin` 争用**：第 5 步要改 admin-vue 并重建产物，而当前有并发会话正在改 `admin-vue` 且刚重建过 `static/admin`
   （`git status` 里成片的 `D services/…/static/admin/assets/*` ＋ 新哈希文件）。并发重建共享产物 ⇒ 归属会混。
2. **`clients/admin-vue/public/__mock-sw.js` 让 `check:line-endings` 红**（该文件是并发会话的临时 Mock SW，CRLF、未跟踪）
   ⇒ 全量门禁链 **34 项通过 33、失败 1**，失败项**与 D1 / G9 无关**。

---

## 8. 来源

- 人人文库《2026 中国无人便利店技术方案与运营模式对比研究报告》`m.renrendoc.com/paper/533906112.html`
- 软盟资讯《储值会员制度设计全案：预付费模式如何既回笼现金流，又不引发退款纠纷》`news.softunis.com/73111.html`
- 道中创新《无人零售的「复购密码」》`dozzon.com/article-114.html`
- 友宝《集团充值》`uboxol.com/help/recharge.html`；丰e足食官网 `feng1.com`
