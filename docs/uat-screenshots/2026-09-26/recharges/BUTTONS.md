# 充值管理 · 按钮清点 · 2026-09-26

> `/recharges` · Playwright **1366×768**（DPR=1）  
> [`RECHARGES_FULL_BROWSER_UAT.md`](../../../uat/RECHARGES_FULL_BROWSER_UAT.md)  
> 规则：退款一律 **prompt→取消**；禁止真退款。

---

## A 列表

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开页 | ✓ | 共 3 · ¥ · `rch-01-home.png` |

## B 筛选

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 状态选项中文 | ✓ | 8 项 · `rch-02-status-opts.png` |
| B-02 | 筛已支付 | ✓ | 共 2 · `rch-03-status-filter.png` |
| B-03 | 重置 | ✓ | `rch-04-reset.png` |
| B-04 | 关键词非整数 | ✓ | toast 正整数 · `rch-05-kw-invalid.png` |
| B-05 | userId 无匹配 | ✓ | 共 0 · `rch-06-kw-empty.png` |

## C 写 / 工具

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 退款 → 取消 | ✓ | `rch-07-refund-prompt.png` |
| C-02 | 导出 CSV | ✓ | `充值_*.csv` · `rch-09-export.png` |
| C-03 | 刷新 | ✓ | `rch-10-refresh.png` |

## D 深链 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | `?status=PAID` | ✓ | 已支付 · 共 2 · `rch-08-deeplink-paid.png` |
| D-02 | 900 无整页横滚 | ✓ | `rch-ux-narrow.png` |
| D-03 | 1366 / 1920 | ✓ | `rch-ux-*.png` |
| D-04 | 结束视口 1366 | ✓ | `rch-99-end.png` |

## 结案

- [x] FAIL 0；退款取消；状态深链 OK
|
