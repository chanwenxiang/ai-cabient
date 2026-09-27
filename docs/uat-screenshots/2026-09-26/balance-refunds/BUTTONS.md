# 余额退款 · 按钮清点 · 2026-09-26

> `/balance-refunds` · Playwright **1366×768**（DPR=1）  
> [`BALANCE_REFUNDS_FULL_BROWSER_UAT.md`](../../../uat/BALANCE_REFUNDS_FULL_BROWSER_UAT.md)  
> 规则：通过/驳回一律 **prompt→取消**；禁止真退款。

---

## A 头 / Tab

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 待审核空态 | ✓ | 共 0 · `br-01-pending.png` |
| A-02 | 批量通过/驳回 | ✓ | 无勾选 disabled |
| A-03 | Tab 全部 | ✓ | 共 0 · `br-03-all.png` |
| A-04 | Tab 已退款 | ✓ | 共 0 · `br-04-refunded.png` |
| A-05 | Tab 已驳回 / 失败 | ✓ | `br-04b` / `br-04c` |

## B 筛选 / 写

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 关键词无匹配 | ✓ | `br-02-kw-empty.png` |
| B-02 | 通过 → 取消 | — SKIP | 无待审行 |
| B-03 | 驳回 → 取消 | — SKIP | 无待审行 |
| B-04 | 用户深链 | — SKIP | 无行 |

## C 工具 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| C-01 | 导出 | ✓ | `br-08-export.png` |
| C-02 | 刷新 | ✓ | `br-09-refresh.png` |
| C-03 | 900 / 1366 / 1920 | ✓ | `br-ux-*.png` · `br-99-end.png` |

## 结案

- [x] FAIL 0；空态诚实；行审核 SKIP
|
