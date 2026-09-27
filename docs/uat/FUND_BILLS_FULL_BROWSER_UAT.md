# 资金账单 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 资金账单」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。本页**无硬写**（筛选/导出/刷新只读）。  
> **源码**：`clients/admin-vue/src/views/finance/FundBillView.vue`  
> **API**：`GET …/fund/daily-bills` · `GET …/fund/ledger` · `GET …/fund/daily-bills/export`  
> **视口**：主测 **1366×768**（DPR=1）。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/fund-bills/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/fund-bills/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/fund-bills/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「资金账单」· T+1 提示 · 导出/刷新 |
| 筛选 | 账期（≤90 天）· 关键词 · 查询 |
| Tab | 日资金账单 · 账务明细 |
| 日账单 | 账期/商户/实付/抽成/通道费/已入账/待入账/笔数/固化 |
| 明细 | 财务类型 · 收支 · 金额 ¥ · 订单/货柜/商户/时间 |

---

## 2. 用例（FB-*）

| ID | 步骤 | 期望 |
|----|------|------|
| FB-01 | 打开 `/fund-bills` | T+1 可见；共 N↔API；金额 `¥` |
| FB-02 | 日账单无匹配关键词 | 共 0 + 关键词 hint |
| FB-03 | 关键词 `MCH-DEFAULT` | 共≥1 + hint |
| FB-04 | 账期跨度 >90 天 | toast「不能超过 90 天」 |
| FB-05 | 刷新 | 可点 |
| FB-06 | 导出日账单 | 下载 +「已导出日账单」 |
| FB-07 | Tab 账务明细 | 共↔API；¥；收支中文 |
| FB-08 | 财务类型下拉 | 中文（订单支付/平台抽成…） |
| FB-09 | 类型筛选 | total 变化 |
| FB-10 | 收支下拉 | 「收入」「支出」无 IN/OUT |
| FB-11 | 明细无匹配关键词 | 共 0 |
| FB-12 | 导出明细 | CSV 触发 |
| FB-U-01 | 900 | 无整页横滚 |
| FB-U-03 | 结束 | 视口 1366×768 DPR=1 |

---

## 3. 结案清单

- [x] 日账单共 **5** ↔ API；明细共 **62**；金额 ¥；固化中文
- [x] 关键词 hint；账期 90 天门控；双路径导出
- [x] 类型/收支中文筛；无硬写；窄视口无横滚
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 册深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 日资金账单 | 共 **5** · ¥3.50 样例 · 实时/已固化混排 |
| 账务明细 | 共 **62** · 类型/收支中文 · 筛类型后 22 |
| 门控 | 账期>90 天 toast；关键词空 hint |
| 导出 | `fund-daily-bills.csv` · `资金账务明细_*.csv` |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
