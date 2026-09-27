# 资金账单 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`FUND_BILLS_FULL_BROWSER_UAT.md`](../../../uat/FUND_BILLS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

双 Tab：日资金账单共 **5**、账务明细共 **62**，均与 API 对齐；金额统一 `¥`；财务类型/收支字典中文无裸码。账期跨度>90 天拦截；关键词结果 hint 诚实。双路径导出成功。本页无硬写。结束视口 **1366×768**。

## 数据对照

| 面 | UI | API |
|----|-----|-----|
| 日账单默认 | 共 **5** · ¥3.50 等 | `daily-bills` total=5 |
| 关键词无匹配 | 共 0 · hint | total=0 |
| 关键词 MCH-DEFAULT | 共 5 · hint | total=5 |
| 账务明细 | 共 **62** | `ledger` total=62 |
| 类型=订单支付 | 共 22 | financialType 筛 |
| 明细无匹配关键词 | 共 0 | total=0 |

样例日账单：`2026-09-26` · MCH-DEFAULT · 实付 350¢ · 抽成 35¢ · 通道 2¢ · 已入账 315¢ · 实时  
样例明细：ORDER_PAYMENT IN 350¢ / PLATFORM_FEE OUT 35¢（UI 显示「订单支付」「平台抽成」「收入」「支出」）

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **T+1**：当日流水通常次日入账；通道费约 0.6% 估算（页头 alert）。 |
| 2 | 账期单次跨度 **≤90 天**（可跨月）；超限仅 toast，不发请求。 |
| 3 | 关键词需点「查询」才写入 `appliedKeyword` 并出 hint；清关键词后再查可消 hint。 |
| 4 | 日账单无勾选时走**后端整单导出**；有勾选走页内 CSV。明细导出为选中优先的页内 CSV。 |
| 5 | 固化标签：当日可「实时」，历史日「已固化」。 |
| 6 | 本页无调账/冲正等写路径；资金写操作在其它财务页。 |

## 证据

`fb-01`…`fb-14` · `fb-ux-*` · `fb-99-end` · `fb-api-probe.json`
|
