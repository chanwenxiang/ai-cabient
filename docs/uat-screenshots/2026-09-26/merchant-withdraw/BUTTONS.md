# 商户提现 · 按钮清点 · 2026-09-26

> `/merchant-withdraw` · Playwright **1366×768**（DPR=1）  
> [`MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md`](../../../uat/MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md)  
> 规则：写路径一律 **确认→取消**；禁止真调账/代提现/打款/驳回。

---

## A 头 / Alert / 钱包

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开页 · 商户钱包 | ✓ | 共 2 · `mw-01-wallets.png` |
| A-02 | payout-mode Alert | ✓ | 记账打款 · fee=0 |
| A-03 | 刷新 | ✓ | `mw-12-refresh.png` |

## B 钱包筛选 / 写

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 关键词无匹配 | ✓ | 共 0 · 暂无商户钱包 · `mw-06-kw-empty.png` |
| B-02 | 调账 → 取消 | ✓ | `mw-02-adjust-open.png` · `mw-03-adjust-cancel.png` |
| B-03 | 流水抽屉 | ✓ | `mw-04-ledger.png` |
| B-04 | 更多→代提现 → 取消 | ✓ | `mw-05a-more-menu.png` · `mw-05-proxy-open.png` · `mw-05b-proxy-cancel.png` |

## C 提现审核

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | Tab 提现审核 | ✓ | 共 1 · 已打款 · `mw-07-withdraws.png` |
| C-02 | 批量通过/驳回 | ✓ | 无勾选 disabled · `mw-07b-batch.png` |
| C-03 | 状态选项中文 | ✓ | 待审核…失败 · `mw-08-status-opts.png` |
| C-04 | 筛已打款 | ✓ | 共 1 · `mw-09-status-filter.png` |
| C-05 | 行通过并打款 | — SKIP | 仅 PAID 终态 |

## D 深链 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | `?tab=withdraws` | △ FINDING | URL 保留 · Tab 仍「商户钱包」· `mw-11-deeplink.png` |
| D-02 | 900 无整页横滚 | ✓ | `mw-ux-narrow.png` |
| D-03 | 1366 / 1920 | ✓ | `mw-ux-*.png` |
| D-04 | 结束视口 1366 | ✓ | `mw-99-end.png` |

## 结案

- [x] 软写取消；行审核 SKIP；FINDING 1（深链 tab）
|
