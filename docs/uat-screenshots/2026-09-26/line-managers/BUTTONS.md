# 线长钱包 · 按钮清点 · 2026-09-26

> `/line-managers` · Playwright **1366×768**（DPR=1）  
> [`LINE_MANAGERS_FULL_BROWSER_UAT.md`](../../../uat/LINE_MANAGERS_FULL_BROWSER_UAT.md)  
> 规则：写路径一律 **确认→取消**；禁止真创建/调账/打款/驳回。

---

## A 头 / Alert / 空态

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开页 · 线长成员 | ✓ | 共 0 · 暂无线长 · `lm-01-managers.png` |
| A-02 | payout-mode Alert | ✓ | 记账打款 · fee=0 |
| A-03 | 新建线长 | ✓ | 可见 |
| A-04 | 刷新 | ✓ | `lm-13-refresh.png` |

## B 成员筛选 / 写

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 状态选项中文 | ✓ | 启用/停用 · `lm-04-status-opts.png` |
| B-02 | 关键词无匹配 | ✓ | 共 0 · `lm-05-kw-empty.png` |
| B-03 | 新建线长 → 取消 | ✓ | `lm-02-create-open.png` · `lm-03-create-cancel.png` |
| B-04 | 行调账 | — SKIP | 无行 |
| B-05 | 行绑柜 / 代提现 | — SKIP | 无行 |

## C 提现审核

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | Tab 提现审核 | ✓ | 暂无提现申请 · `lm-08-withdraws.png` |
| C-02 | 状态选项中文 | ✓ | 待审核…失败 · `lm-08b-wd-status.png` |
| C-03 | 批量通过/驳回 | ✓ | 无勾选 disabled · `lm-08c-batch-disabled.png` |
| C-04 | 行通过并打款 | — SKIP | 无待审行 |

## D 地推任务

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| D-01 | Tab 地推任务 | ✓ | 暂无地推任务 · `lm-10-promo.png` |
| D-02 | 新建任务（无线长） | ✓ | toast 门控 · `lm-11-promo-gate.png` |

## E 深链 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| E-01 | `?tab=withdraws` | △ FINDING | URL 保留 · Tab 仍「线长成员」· `lm-12-deeplink-withdraws.png` |
| E-02 | 900 无整页横滚 | ✓ | `lm-ux-narrow.png` |
| E-03 | 1366 / 1920 | ✓ | `lm-ux-*.png` |
| E-04 | 结束视口 1366 | ✓ | `lm-99-end.png` |

## 结案

- [x] 软写取消 / 门控；行写 SKIP；FINDING 1（深链 tab）
|
