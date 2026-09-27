# 运营后台 ·「财务商户」全量浏览器 UAT（Phase 5）

> **地位**：第五册（侧栏「财务商户」）。工具/四维铁律同 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §1。  
> **版本**：1.1 · 2026-09-26（十一页复测）  
> **截图**：`docs/uat-screenshots/2026-09-26/finance-merchant/`

---

## 0. 范围顺序

| 序 | 路径 | 标题 |
|----|------|------|
| 1 | `/fund-bills` | 资金账单 |
| 2 | `/merchants` | 商户与分账 |
| 3 | `/merchant-onboarding` | 进件工作台 |
| 4 | `/line-managers` | 线长钱包 |
| 5 | `/merchant-withdraw` | 商户提现 |
| 6 | `/reconciliation` | 对账 |
| 7 | `/consistency` | 数据一致性 |
| 8 | `/recharges` | 充值管理 |
| 9 | `/balance-refunds` | 余额退款 |
| 10 | `/invoices` | 开票申请 |
| 11 | `/users` | 用户余额 |

| 字段 | 值 |
|------|-----|
| 结案 | **Phase 5 Done（复测）**；[`finance-merchant/BUTTONS.md`](../uat-screenshots/2026-09-26/finance-merchant/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/finance-merchant/FINDINGS.md) |
| 小程序 | Admin 深测**未**跑 mp；回补见 [`MINIPROGRAM_BACKLOG.md`](./MINIPROGRAM_BACKLOG.md) §1（充值/提现/分账/余额等 P0） |

ID 前缀：`FIN-*` · `MCH-*`

---

## 1. 关键检查

| ID | 页 | 期望 |
|----|-----|------|
| FIN-FB-01 | 资金账单 | 有行或中文空态；金额 `¥`；筛选/刷新生效 |
| FIN-FB-02 | 资金账单单页深测 | 见 [`FUND_BILLS_FULL_BROWSER_UAT.md`](./FUND_BILLS_FULL_BROWSER_UAT.md)（日5/明细62/90天/双导出） |
| MCH-01 | 商户与分账 | 列表有演示商户；分账开关可见；禁误点写路径无确认 |
| MCH-02 | 商户与分账单页深测 | 见 [`MERCHANTS_FULL_BROWSER_UAT.md`](./MERCHANTS_FULL_BROWSER_UAT.md)（四Tab/弹层取消/确认完结取消/深链） |
| MCH-ONB-01 | 进件 | 有待办或「暂无」诚实 |
| MCH-ONB-02 | 进件工作台单页深测 | 见 [`MERCHANT_ONBOARDING_FULL_BROWSER_UAT.md`](./MERCHANT_ONBOARDING_FULL_BROWSER_UAT.md)（空态/仅登记/新建取消/中文筛） |
| MCH-LM-01 | 线长钱包 | 列表/空态中文 |
| MCH-LM-02 | 线长钱包单页深测 | 见 [`LINE_MANAGERS_FULL_BROWSER_UAT.md`](./LINE_MANAGERS_FULL_BROWSER_UAT.md)（三Tab/新建取消/地推门控/深链tab FINDING） |
| MCH-WD-01 | 商户提现 | 列表或空态；审批写路径须确认 |
| MCH-WD-02 | 商户提现单页深测 | 见 [`MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md`](./MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md)（双Tab/调账代提现取消/批量disabled/深链tab FINDING） |
| FIN-REC-01 | 对账 | 有跑批入口或结果表 |
| FIN-REC-02 | 对账单页深测 | 见 [`RECONCILIATION_FULL_BROWSER_UAT.md`](./RECONCILIATION_FULL_BROWSER_UAT.md)（T+1/执行取消/渠道OK/status·keyword FINDING） |
| FIN-CON-01 | 一致性 | 巡检结果或空态 |
| FIN-CON-02 | 数据一致性单页深测 | 见 [`CONSISTENCY_FULL_BROWSER_UAT.md`](./CONSISTENCY_FULL_BROWSER_UAT.md)（空态0/类型中文/巡检全部通过） |
| FIN-RCH-01 | 充值 | 列表有数或空态 |
| FIN-RCH-02 | 充值管理单页深测 | 见 [`RECHARGES_FULL_BROWSER_UAT.md`](./RECHARGES_FULL_BROWSER_UAT.md)（共3/状态筛深链/退款取消/userId校验） |
| FIN-BR-01 | 余额退款 | 列表；写路径二次确认 |
| FIN-BR-02 | 余额退款单页深测 | 见 [`BALANCE_REFUNDS_FULL_BROWSER_UAT.md`](./BALANCE_REFUNDS_FULL_BROWSER_UAT.md)（五Tab空态/批量disabled/行审核SKIP） |
| FIN-INV-01 | 开票 | 列表或空态 |
| FIN-INV-02 | 开票申请单页深测 | 见 [`INVOICES_FULL_BROWSER_UAT.md`](./INVOICES_FULL_BROWSER_UAT.md)（空态0/仅状态Alert/批量disabled/行写SKIP） |
| FIN-USR-01 | 用户余额 | 可搜；余额展示分→元 |
| FIN-USR-02 | 用户余额单页深测 | 见 [`USERS_FULL_BROWSER_UAT.md`](./USERS_FULL_BROWSER_UAT.md)（共15/关键词空命中/调账取消/核验取消/深链/导出） |

执行日志：`docs/uat-screenshots/2026-09-26/finance-merchant/FINDINGS.md`  
按钮清点：`docs/uat-screenshots/2026-09-26/finance-merchant/BUTTONS.md`

## Done

- [x] 关键 ID 均有 PASS/SKIP + 证据  
- [x] 调账/对账/新建写路径确认后取消  
- [x] **2026-09-26 复测**：附录 B + `fm-*.png`；一致性全部通过  
