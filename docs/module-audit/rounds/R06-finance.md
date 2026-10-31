# R6 审计：财务商户（16 页，分 3 批）

- 执行 AI / 日期：Qoder（本会话）/ 2026-10-31
- 代码基线：HEAD `ecf45bb2`（起）/ 逐批记录修复锚
- 环境：本地 docker（trade `:18080`、nginx `:80`）；库 `aicabinet` @15433
- 账号：超管 `13900000001`（受限角色留 R8）
- 纪律：**只读优先**；写操作只开弹窗验确认闸、不提交；支付/资金一律 dev mock，不碰真实资金；金额分/元与台账↔流水↔批次逐层对

## 分批

| 批次 | 页面 | 状态 |
|------|------|------|
| 6a-1（资金与商户） | `/fund-bills` `/platform-fund` `/device-rental` `/merchants` `/merchant-onboarding` `/merchant-fee-ledgers` | ✅ 本轮完成 |
| 6a-2（线长/机器账单/对账） | `/line-managers` `/line-routes` `/machine-payee-bills` `/reconciliation` `/consistency` | 未开始 |
| 6b（提现与用户资金） | `/merchant-withdraw` `/recharges` `/balance-refunds` `/invoices` `/users` | 未开始 |

## 本轮基线（2026-10-31，R3/R4/R5 清理后）

设备 3（全离线）· SKU 15（在架 3）· 订单 8（¥5.00）· 会话 64 · 商户 2（MCH-DEFAULT / BMCH-OTHER）· `wallet_ledger` 3 行（ADJUST 50000 + SPLIT_CREDIT 150/350，全 MCH-DEFAULT）· `platform_fund_account` 1 / `platform_fund_flow` 2 · `device_rental_bill` 1 / `device_rental_deposit` 1 · 进件 3 · 商户费用台账 0。

## 逐页结果（6a-1）

### 页面：资金账单（`/fund-bills`）｜选项卡：日资金账单(1) / 账务明细(0→6)

- 日资金账单 1 = SQL `merchant_settlement_bill` 1（账期 2026-10-22 · MCH-DEFAULT · 订单实付 **¥5.00** · 平台抽成 ¥0.00 · 通道费 **¥0.03（估）** · 已入账 ¥5.00 · 待入账 ¥0.00 · 2 笔 · 已固化）——与「平台分账归 0（V382）」和「订单 8 笔里实付 ¥5.00」两条口径**同时吻合** ✅；通道费带「（估）」标注，未冒充实收。
- 账务明细：账期留空 ⇒ `共 0 条`；加 10 月账期 ⇒ **6 条**（3 笔分录 × 2 单：ORDER_PAYMENT 350 / CHANNEL_FEE 2 / MERCHANT_CREDIT 350 …）与 `wallet_ledger` 的 SPLIT_CREDIT 150/350 能对上，通道费 2/1 分合计 ¥0.03 = 日账单通道费 ✅。
- 发现：**MA-014（P3，已修）**——账期留空时后端只看**近 7 天**（`FundBillService.listLedger:332-333`），页面不交代 ⇒ 库里 10 月的流水全不显示、空态「暂无流水」误导。修复：`range-hint` 补「留空＝『账务明细』只看近 7 天」、空态改「近 7 天暂无流水（账期留空时只看近 7 天；可选账期查看更早）」。**默认行为未动**。源码 `87e6beb1` + 产物 `56d58377`；活栈复验两处文案均已渲染。
- 观察项：日资金账单 tab 不受 7 天窗口限制（账期留空也能看到 10-22 的账单）——已在提示里刻意只对「账务明细」声明，避免误伤。

### 页面：平台资金池（`/platform-fund`）

- 列表 1 = SQL `platform_fund_account` 1 ✅；行操作 充值 / 归集 / 更多操作（写操作，本轮只验入口）；工具栏 新建账户。
- 口径文案准确：「渠道账户备付台账（CB-033）；余额只回答『今天够不够付』，以渠道实盘为准」「提现打款前会校验对应渠道账户备付余额；未登记账户为**软启动**（只告警不拦截）」「充值=公司入金，归集=商户销售额转入，调整为差错更正（可正可负）」——与 `platform_fund_flow` 2 条的类型一致。
- console 0 error。

### 页面：设备租赁（`/device-rental`）｜选项卡：月租账单(1) / 押金台账(1)

- 月租账单 1 = SQL `device_rental_bill` 1 ✅；押金台账 1 = SQL `device_rental_deposit` 1 ✅；筛选 账期月/状态（未付/已付/已作废）/设备。
- 口径文案：「押金收据化 + 月租应收账单（CB-034）；ONLINE 从商户余额真扣、OFFLINE 只做收据化登记」「押金是负债不是收入：登记→扣款抵欠（累计不超过本金）→退租结算返还；月租逾期未付自动锁柜」——把负债/收入与自动锁柜讲清楚。
- 写操作：生成月租账单 / 登记押金（未提交）；console 0 error。

### 页面：商户与分账（`/merchants`）｜选项卡：组织树 / 商户列表(2) / 运营配置 / 分账明细(2)

- 组织树 2 个商户（MCH-DEFAULT 设备 2 · BMCH-OTHER 设备 1）= SQL `merchant` 2 ✅；商户列表 2 = 同上 ✅（表头含 抽成/现场作业/经营工具/团队设置/商户改货道/商户改价/设备数）；
- 分账明细 2 = 与 2 笔实付订单一一对应 ✅；页面标注「分账联调 Mock 已启用（不调用微信 API）」「仅记账 / 记账模式余额扣款订单通常为『仅记账』：商户份额已入钱包，不调用微信分账」——与 V382 平台抽成 0 一致。
- 运营配置 tab：组织级备货/理货策略（盘点必须带 `photoEvidenceUrl`、并发订单上限等）；console 0 error。
- 观察项：组织树说明「上级可见下级货柜、子商户按自身抽成结算，**非自动再抽成**」——与「平台分账归 0」不矛盾（抽成比例本身为 0），记录备查。

### 页面：进件工作台（`/merchant-onboarding`）

- 列表 3 = SQL `merchant_payment_onboarding` 3 ✅；筛选 商户编号/渠道（微信/支付宝/支付分）/状态（草稿/已提交/已生效/已驳回）；工具栏 批量通过/批量驳回/新建进件/导出。
- 口径文案与实现一致：「仅登记外部门店号与进件状态，**不推送**到支付渠道」「不调用微信/支付宝进件 OpenAPI」——与 `merchant_payment_onboarding` 的定位吻合，避免把它当成真实进件通道。
- 写操作：批量通过/驳回（未提交）；console 0 error。

### 页面：商户费用台账（`/merchant-fee-ledgers`）

- 列表 0 = SQL `merchant_fee_ledger` 0 ✅（空态「暂无数据」）；工具栏含「按日期重跑日扣」（写操作，未提交）；筛选 状态/费用类型（空=全部）。
- 数据缺口：无日扣记录 ⇒ 欠费催缴与锁柜口径**未演练**（留 R11）。

## 逐页结果（6a-2）

### 页面：线长钱包（`/line-managers`）｜选项卡：线长成员(0) / 提现审核(0) / 地推任务(0)

- 三个 tab 全 0：`line_route` 0 / `line_route_member` 0 / 线长域钱包流水 0 ⇒ 一致性成立 ✅。
- 表头核对：线长成员（经理编号/姓名/手机/组织/绑定用户/微信 OpenID/余额/冻结/绑柜/佣金比例/固定分每单）；提现审核（单号/业务单/线长/手机/收款方/金额/手续费/状态/通道/回执/渠道对账…）。
- 数据缺口：无线长 ⇒ 佣金计算、提现审核回调、地推奖励全链**未演练**（R11 造数）。console 0 error。

### 页面：线路与提成（`/line-routes`）｜选项卡：线路管理 / 月合计（财务对账）/ 补货员钱包

- 三 tab 全 0 = SQL `line_route` 0 / `line_route_member` 0 / 钱包 0 ✅；工具栏 编辑总负责人/新建线路；console 0 error。
- 数据缺口同上（无线路即无提成数据）。

### 页面：机器账单（`/machine-payee-bills`）｜选项卡：月度账单 / 点位费用配置

- 两 tab 全 0 = SQL `machine_payee_bill` 0 ✅（空态「暂无数据」）；工具栏 手动出账/查询配置（写操作未提交）；console 0 error。

### 页面：对账（`/reconciliation`）

- 列表 **7 批** = SQL `payment_reconciliation` 7 ✅；页面汇总「批次总数 7 / 本页差异 1 / 本页匹配 6」与库内一致（6×MATCHED + 1×**MISMATCH**：2026-10-05 BALANCE，`diff_cents=500`、未匹配 1 笔）。
- 渠道筛选**服务端**：选「微信」⇒ `?channel=WECHAT` ⇒ `共 6 条` ✅。
- 口径文案准确：「T+1 核对渠道账单与本账流水；未匹配笔数=账单对不上单号的行数；差额=渠道合计−本账合计（可为负）；二者可独立出现」。
- **已按用户拍板人工平账（2026-10-31）**：`recon_id=2`（2026-10-05 BALANCE，`detail.demo=true`）状态置 MATCHED，`diff_cents`/`unmatched_count` 归零；原始差额 500 分、未匹配 1 笔写入 `detail.manualClose`（`by=R6-audit`、原因＝演示数据无真实资金）。页面复验：**批次总数 7 / 差异 0 / 匹配 7**。留痕可直接在详情/库里查 `manualClose`。
- 写操作：执行对账（未提交）；console 0 error。

### 页面：数据一致性（`/consistency`）

- 巡检覆盖 9 类（订单/支付/库存批次/积分/券/钱包/分账/货道/仓存），页面只列**未通过项**（`data_consistency_record` 141 行里 FAIL 5 / FIXED 136，与库一致）。
- **MA-015（P2，已修）**：5 条 FAIL 里 2 条是 SALE 误报——两笔 10-22 订单的 `order_line.batch_no` 为空（当时无批次），而该柜批次账本是 10-26 后建的 ⇒ 下单时不可能有批次流水。规则 `checkCrossLinkMissingSale` 只看「设备现在有无批次」，未看「订单行是否带批次」。
  - 修复：缺 SALE 流水分支加前提「订单行存在非空 batch_no」。
  - 验证：规则 SQL 旧 2 条 → 新 0 条；容器重建后 `POST /consistency/run` → **failCount 3**（只剩争议），记录表 FAIL 5 → **3**。
- **MA-016（P3，待拍板）**：剩 3 条「争议已结案但缺少关联订单」（会话 COMPLETED 且无订单）。规则 `checkCrossLinkDispute` 对 RESOLVED/CLOSED 一律要求有单——无购买争议是否允许无单属业务口径，**未擅自改规则**。
- 修复入口：页面标注「订单金额/库存类/积分恒等式可点修复；支付/退款/分账/钱包偏差请走退款或调账」——分级处理合理；本轮未点「修复」（写操作）。
- console 0 error。

## 费率与金额正确性验证（用户 2026-10-31 要求：不能只看 0）

### 计算口径（代码锚点，先钉死再验数）

| 项 | 公式 | 代码位置 |
|----|------|----------|
| 分账基数 | `gross = 冻结实付(paid, 0 回落 total) − refunded` | `CommissionBaseCalc.netCents`（AU-182 净额口径） |
| 平台抽成 | `platform = floor(gross × platform_rate_bps / 10000)` | `RevenueSplitService.doRecordSplit`（110-112 行） |
| 商户份额 | `merchant = gross − platform`（整数除法的零头**归商户**，两段和恒等于 gross） | 同上 |
| 日账单 | `credited/pending = Σ split.merchant`（按状态）；`platform = Σ split.platform` | `FundBillService`（167-185 行） |
| 通道费 | `floor(Σgross × fund.channel_fee_bps / 10000)`，有对账实结则**实结优先**（CB-020③）；**单独展示、不从商户入账扣** | `FundBillChannelFeeRateTest` + `system_config:fund.channel_fee_bps=60` |

### 新增钉测（此前全仓没有一处用非零 `platformRateBps`）

`RevenueSplitServiceTest` 新增 4 条，连同既有用例 **24/24 绿**：

| 用例 | 输入 | 期望 |
|------|------|------|
| 5% 正常 | gross 1000 @ 500bps | platform **50** / merchant **950**，和 = 1000 |
| 除不尽的零头 | gross 333 @ 500bps（16.65） | platform **16**（向下取整）/ merchant **317**，和 = 333（不丢分不贪分） |
| 部分退款 | total/paid 1000，退 400 ⇒ 基数 600 @ 500bps | platform **30** / merchant **570**（按净额，不是 1000） |
| 费率为 0（V382 默认） | gross 875 @ 0bps | platform **0** / merchant **875** |

### 真库三层对账（可复用脚本 `seeds/money-recheck.sql`，只读）

6 段全部通过（`mismatch = 0`）：
1. 每份分账 `platform == floor(gross×bps/10000)` 且 `platform+merchant == gross`；
2. 分账毛额 == 订单净额（`COALESCE(NULLIF(paid,0),total) − refunded`）；
3. 钱包 `SPLIT_CREDIT` 合计 == 分账 `merchant_cents` 合计（按商户）；
4. 日×商户聚合 `gross == platform + credited + pending`；
5. 通道费配置在合法区间（0–1000，超界回落 60）；
6. 逐单公式复算表（人工可读）。

**实值对照（2026-10-22）**：gross **500 分** × 60bps = **3 分**，与页面「通道费 ¥0.03（估）」一致；当日无对账实结 ⇒ 走估算 ✅；平台抽成 0（V382）⇒ 商户入账 500 = 钱包两笔 `SPLIT_CREDIT`（150 + 350）✅。三处（订单 / 分账+钱包 / 日报表）互相闭合。

### 缺口与边界（写清楚，避免扯皮）

- **非零平台抽成在活栈无样本**（所有商户费率 0）：数学正确性由单测覆盖；要「活栈也挣到非零数字」，需先把某商户 `platformRateBps` 调 >0 再产生一笔真实订单走结算 —— 属 **R11 全流程轮**（本期不擅自改共享库费率）。
- 通道费「估」与「实结」是两种口径，页面已用「（估）」区分；对账导入实结后自动优先（CB-020③，单测 `dailyBill_actualFeeFromRecon_distributedByGrossShare` 已钉）。
- 记录链：`order → order_revenue_split → wallet_ledger(SPLIT_CREDIT) → 日账单聚合`，任何一层都能用 `money-recheck.sql` 重算复现。

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 0 | — |
| P2 | 1 | MA-015（巡检对无批次历史订单误报，已修） |
| P3 | 2 | MA-014（账务明细 7 天窗口，已修）· MA-016（争议无单规则，待拍板） |

## 需用户拍板

1. ~~MA-016 争议无单规则~~ → **已拍板（2026-10-31）：规则不改，3 条演示数据人工处理**（wontfix）。
2. ~~10-05 对账差异 500 分~~ → **已拍板：人工平账留痕**，已执行并复验（页面 差异 0 / 匹配 7，原始值见 `detail.manualClose`）。
3. 6b 批（提现/充值/退款/开票/用户余额）执行中——新拍板项在此追加。

## 需用户配合（真机）

- 商户提现/打款类（6b）只走 dev mock；真实渠道对账需渠道流水文件。

## 修复批次建议

- 6a-1 无 P0/P1；MA-014 已轮内修完并活栈验证。

## 给下一轮的交接注记

- **资金口径一律先跑 `docs/module-audit/seeds/money-recheck.sql`**（只读，6 段 0 才算过），再谈页面数字；R11 造非零费率样本时复用同一脚本。
- 平台抽成零头**归商户**（整数除法），别改成四舍五入——已有钉测锁定。
- 通道费不参与商户入账（钱包按 merchant_cents 全额入），别把「通道费」从 credited 里再扣一次。
- `/fund/ledger` 默认近 7 天是**后端既定行为**，别为了「让页面有数据」去放宽窗口；要更早数据就选账期。
- 资金类页面一律先看口径文案再对数：通道费带「（估）」、平台抽成 0（V382）、分账 Mock 已启用。
