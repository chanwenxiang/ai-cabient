# 决策简报：`POINTS_IDENTITY` 的积分扣回口径（2026-10-18）

> 取证已完成、**一行代码未改**：见
> [evidence/points-identity-investigation-2026-10-18.md](evidence/audit-2026-10-13/trackC-admin/points-identity-investigation-2026-10-18.md)。
> 本简报只解决「按哪个口径修 + 修到哪一层」，请拍板后再动代码。

## 1. 问题（一句话）

「退款扣回积分」只减 `available_points`，既不记 `used_points` 也不动 `total_points`
⇒ 三角恒等式 `total = available + used + expired` 必破（隔离栈实测 `member 1`：181 ≠ 149，差 32 = 4 笔扣回 × 8）；
而巡检的自动修复 `fixPointsIdentity` 方向相反——它把 `total_points` **下调**去凑三桶，等于让运营一键改掉「累计发放」的语义。

## 2. 关键事实（已实证，作为选项的前提）

| # | 事实 | 出处 |
|---|---|---|
| F-1 | 积分流水的 `points_type` 只有三个取值：`EARN` / `USE` / `EXPIRE` | `MemberService:270`、`:323`、`PointsRedeemService:152`、`PointsExpiryScheduler:177` |
| F-2 | 三条合法消耗/发放路径都是**成对动两列**，只有扣回是单列 | 证据文档 §3 表 |
| F-3 | `POINTS_BALANCE`（可用 vs Σ 流水）实测为绿 ⇒ 余额侧没坏，坏的是「消耗归集」侧 | 证据文档 §2 |
| F-4 | 等级升级判定只看 `min_spent`/`max_spent`/`min_recharge`，**不看积分** | `MemberService:150-174` |
| F-5 | `min_points` 唯一作用 = H5「距下一等级还差 N 分」的分母；`max_points` 全仓零读取 | `PointsRedeemService:69-70`、`MemberLevelsView.vue:71` |
| F-6 | 上线库 = 全新空库 Flyway 从零 ⇒ 历史脏行**不必回填**（无真实退款扣回会被重放） | `PROJECT_KNOWLEDGE` 上线口径 |
| F-7 | 竞品台账 `docs/COMPETITOR_BENCHMARK.md` **没有任何积分条目** ⇒ 动写侧记账前须先补检索（铁律 `competitor-benchmark-before-code`） | grep 「积分」0 命中 |

## 3. 选项

### A. 扣回归入 `used_points`（推荐）

- 改法：`doClawbackPointsOnRefund` 在 `setAvailablePoints(available - actual)` 旁补
  `setUsedPoints(used + actual)`；恒等式由构造成立。
- 为什么这不是"顺手一塞"：**三桶正好对应流水 `points_type` 的三个值**（F-1）——
  `used_points` 的既有语义就是「Σ USE 流水」，兑换(USE)与退款扣回(USE)本就是同一桶；
  现在只有扣回没记账，是这条规则的**遗漏**，不是新口径。与本轮刚修完的钱包族/净额族同一类病（lessons #326）。
- 影响面：1 个 Java 文件 1 处 + 用例；**无迁移、无 DTO/openapi 变化**（`usedPoints` 字段已存在且已在 DTO/前端）。
- 副作用要说清：H5「已用」与后台会员详情「已用」会把退款扣回计入（口径=已消耗）。若不接受这条措辞，见 C 或把文案改「已消耗」。

### B. `total_points` 随扣回下调（重定义为「净累计发放」）

- 改法：扣回时 `total -= actual`（available 侧不变）。恒等式同样成立。
- 代价与风险：把「累计获得」从**发放累计**改成**净额**，属于对用户可见口径的语义改写；
  H5 的升级进度 gap 会因此变大（对用户更不利，F-4 说明等级其实不受影响 ⇒ 纯展示口径）；
  且 `earnPoints` 一侧的 `total` 语义注释、`ConsumerMemberFacade`、竞品对照都要跟着改说明。
- 与 A 的取舍：A 保住「累计=发了多少」这一更常见、更少歧义的语义。

### C. 新增第四桶 `clawback_points`（建模最精确，成本最高）

- 改法：加列 + 恒等式改 `total = available + used + expired + clawback`（判据、`fixPointsIdentity`、
  `SysDictBootstrap` 无需变，但 DTO / `openapi.ts` / H5 / 后台展示同步）。
- 迁移：需 `V391`（新列 ⇒ `MIGRATION_KIND: schema` + `MIGRATION_REVIEWED/LOCK_RISK/TABLES`；若被判为新建业务表还需 `COMPETITOR_REF`，
  而 F-7 表明台账里**没有**积分条目 ⇒ 必须先补 CB 条目，不许为过门禁编号）。
- 只在「运营/客服需要区分『兑换掉』与『因退款被收回』」时才值得；这是新需求，不是本轮缺陷。

### D. 无论选 A/B/C 都建议同批做：撤掉 `POINTS_IDENTITY` 的自动回写

- `fixPointsIdentity`（`DataConsistencyService:1692-1708`）把 `total_points` 当错误项抹平；
  口径未拍板前，运营点「修复」= 由运营单方面决定口径且不可逆（证据文档 §5 已实测 total 181→149、gap 319→351）。
- 改法与钱包族同款：`applyFix` 里对 `POINTS_IDENTITY`（必要时含 `POINTS_BALANCE`）返回
  `FixOutcome.fail("该类仅巡检记录，请人工核对处理")`，前端 `isWalletCheckType` 同构扩展成「仅巡检族」判定，按钮禁用。
- 附带收益：巡检页不会再出现「红了一下、累计悄悄变了」的隐性数据改写。

### E. 独立小项（可并入也可另议）：`min_points` 双口径 / `max_points` 死列

- 现状：后台「累计积分区间」两列里，`min_points` 只影响 H5 进度文案、`max_points` 改了什么都不影响（F-4/F-5）。
- 三个方向：① 把积分真正接进升级判定（改 `calculateMemberLevel`，语义变化最大）；
  ② 保留 H5 进度用途、把后台该列改成只读或删掉 `max_points` 旋钮（符合「不留死旋钮」）；
  ③ 维持现状并在字段说明里写清「仅用于消费者端进度展示」。

## 4. 我的建议

**A + D 同批**（一处写侧补记 + 撤掉自动回写），**E 选 ②**（撤 `max_points` 旋钮或改只读），
C 仅在确有「区分兑换/扣回」的运营需求时另起一轮。理由：A 是补既有规则的遗漏而非新增口径，
零迁移、零契约变化，上线空库 ⇒ 不需要回填（F-6）；D 阻止「修复动作替用户定口径」。

## 5. 拍板 A+D 后的验收计划（负向先行，per lessons #325/#326/#327）

1. **先写红的用例**：单测/IT 播「一笔 EARN + 一笔退款扣回 USE」⇒ 断言恒等式成立
   （现状必红）；再断 `applyFix(POINTS_IDENTITY)` 返回 fail 且 `total_points` 未被改动。
2. **口径 grep 常扫**：把 `used_points`/`total_points` 的所有写点列进注释或新门禁，
   防止第四个消耗路径再漏记（本轮 `check:payment-net-types-single-source` 同法）。
3. **隔离栈四段实跑**：干净库→跑一轮退款扣回→巡检必须绿（不是静默）→故意破坏 `used` 证明判据能红→复原。
4. 竞品台账补 CB 条目（F-7）后再合入代码。

## 6. 需要用户回答的三个问题

1. 修 A / B / C 哪个口径？（推荐 A）
2. D 是否同批撤掉积分族自动回写？（推荐是）
3. E 的 `max_points` 死列怎么处理？（推荐 ②撤旋钮 / 或明确另议）
