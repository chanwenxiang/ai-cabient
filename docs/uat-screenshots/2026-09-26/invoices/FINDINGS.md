# 开票申请 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`INVOICES_FULL_BROWSER_UAT.md`](../../../uat/INVOICES_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

列表共 **0**，「暂无开票申请」诚实。页头/Alert 明确**仅状态流转**（不生成税控 PDF、不发邮件）。状态下拉中文四档；批量开具/驳回无勾选 disabled。行开具/驳回因无 PENDING SKIP。无 CSV 导出（源码未挂）。结束视口 **1366×768**。

## 数据对照

| 面 | UI |
|----|-----|
| 全部 / 待开具 / 已开具 / 已驳回 | 共 **0** |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **开具 = 仅改 ISSUED**：产品有意未对接税控；Alert 已明示。 |
| 2 | 关键词前端过滤；`total` 取服务端（有数据时需防假绿，本机空未触发）。 |
| 3 | 状态记忆 `localStorage ops_invoice_status_tab`。 |
| 4 | 有 PENDING 时须补测：开具 confirm 取消、驳回 prompt 取消。 |
| 5 | 小程序开票入口：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md) §1 #12（先探有无 C 端入口）。 |

## 证据

`inv-01`…`inv-10` · `inv-ux-*` · `inv-99-end`
|
