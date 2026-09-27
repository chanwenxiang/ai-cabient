# 资金账单 · 按钮清点 · 2026-09-26

> `/fund-bills` · Playwright **1366×768**（DPR=1）  
> [`FUND_BILLS_FULL_BROWSER_UAT.md`](../../../uat/FUND_BILLS_FULL_BROWSER_UAT.md)  
> 本页**无硬写**；筛选/导出/刷新只读。

---

## A 日资金账单

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开页 · T+1 | ✓ | 共 5 · ¥ · `fb-01-home.png` |
| A-02 | 无匹配关键词 | ✓ | 共 0 · hint · `fb-02-keyword-empty.png` |
| A-03 | 关键词 MCH-DEFAULT | ✓ | 共 5 · hint · `fb-03-keyword-hit.png` |
| A-04 | 账期>90 天 | ✓ | toast「不能超过 90 天」· `fb-04-range90.png` |
| A-05 | 刷新 | ✓ | `fb-05-refresh.png` |
| A-06 | 导出日账单 | ✓ | `fund-daily-bills.csv` · `fb-06-export-bills.png` |

## B 账务明细

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | Tab 账务明细 | ✓ | 共 62 · ¥ · `fb-07-ledger.png` |
| B-02 | 财务类型选项 | ✓ | 订单支付/平台抽成/通道费/商户入账 · `fb-08-type-options.png` |
| B-03 | 类型筛选 | ✓ | total→22 · `fb-09-type-filter.png` |
| B-04 | 收支选项 | ✓ | 收入/支出 · `fb-10-dir-options.png` |
| B-05 | 收支筛选 | ✓ | `fb-11-dir-filter.png` |
| B-06 | 明细无匹配关键词 | ✓ | 共 0 · `fb-12-ledger-kw-empty.png` |
| B-07 | 导出明细 | ✓ | `资金账务明细_*.csv` · `fb-13-export-ledger.png` |
| B-08 | 回日账单 Tab | ✓ | `fb-14-back-bills.png` |

## C UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| C-01 | 900 无整页横滚 | ✓ | `fb-ux-narrow.png` |
| C-02 | 1366 / 1920 | ✓ | `fb-ux-*.png` |
| C-03 | 结束视口 1366 | ✓ | `fb-99-end.png` |

## 结案

- [x] FAIL 0；无硬写；视口结束 1366
|
