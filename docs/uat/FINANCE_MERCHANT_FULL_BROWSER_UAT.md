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

ID 前缀：`FIN-*` · `MCH-*`

---

## 1. 关键检查

| ID | 页 | 期望 |
|----|-----|------|
| FIN-FB-01 | 资金账单 | 有行或中文空态；金额 `¥`；筛选/刷新生效 |
| MCH-01 | 商户与分账 | 列表有演示商户；分账开关可见；禁误点写路径无确认 |
| MCH-ONB-01 | 进件 | 有待办或「暂无」诚实 |
| MCH-LM-01 | 线长钱包 | 列表/空态中文 |
| MCH-WD-01 | 商户提现 | 列表或空态；审批写路径须确认 |
| FIN-REC-01 | 对账 | 有跑批入口或结果表 |
| FIN-CON-01 | 一致性 | 巡检结果或空态 |
| FIN-RCH-01 | 充值 | 列表有数或空态 |
| FIN-BR-01 | 余额退款 | 列表；写路径二次确认 |
| FIN-INV-01 | 开票 | 列表或空态 |
| FIN-USR-01 | 用户余额 | 可搜；余额展示分→元 |

执行日志：`docs/uat-screenshots/2026-09-26/finance-merchant/FINDINGS.md`  
按钮清点：`docs/uat-screenshots/2026-09-26/finance-merchant/BUTTONS.md`

## Done

- [x] 关键 ID 均有 PASS/SKIP + 证据  
- [x] 调账/对账/新建写路径确认后取消  
- [x] **2026-09-26 复测**：附录 B + `fm-*.png`；一致性全部通过  
