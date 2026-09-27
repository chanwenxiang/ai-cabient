# 开票申请 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 开票申请」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。开具/驳回仅确认→取消。  
> **源码**：`clients/admin-vue/src/views/finance/InvoiceListView.vue`  
> **API**：`GET …/invoices` · `POST …/invoices/{id}/issue|reject`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/invoices/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/invoices/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/invoices/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「开票申请」· hint（仅状态流转、不税控）· 批量开具/驳回 |
| Alert | 开具只改状态，不生成 PDF/邮件 |
| 筛 | 状态（全部/待开具/已开具/已驳回）· 关键词（前端滤）· 查询 · 重置 |
| 表 | 申请号/订单/用户/抬头/税号/邮箱/金额¥/状态/驳回原因/时间 · 无 CSV |

---

## 2. 用例（INV-*）

| ID | 步骤 | 期望 |
|----|------|------|
| INV-01 | 打开 `/invoices` | 标题·仅状态 Alert；共 N↔列表 |
| INV-02 | 批量开具/驳回 | 无勾选 disabled |
| INV-03 | 状态下拉 | 全部/待开具/已开具/已驳回（无 PENDING 裸码） |
| INV-04 | 筛待开具 | 共↔API 或空态 hint |
| INV-05/06 | 开具/驳回 → 取消 | 有 PENDING 则确认取消；本机 **SKIP** |
| INV-07/08 | 已开具 / 已驳回 | 共↔筛选 |
| INV-09 | 关键词无匹配 | 空态 |
| INV-10 | 刷新 | 可点 |
| INV-11 | 导出 | 源码未挂 CSV（口径） |
| INV-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 空态诚实；状态中文筛；批量 disabled；行开具/驳回 SKIP
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **0** · 暂无开票申请 |
| 写路径 | 开具/驳回 SKIP（无 PENDING） |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
