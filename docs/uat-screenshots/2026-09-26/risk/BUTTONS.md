# 风控 · 按钮清点 · 2026-09-27

> `/risk` · Playwright **1366×768**（DPR=1）  
> [`RISK_FULL_BROWSER_UAT.md`](../../../uat/RISK_FULL_BROWSER_UAT.md)  
> 规则：加入/移出黑名单一律 **打开→取消**；禁止真写。

---

## A 风险事件 Tab

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开 · 双 Tab | ✓ | 「风控」· 风险事件/黑名单 · 共 24 · `rk-01-events.png` |
| A-02 | 处置中文 | ✓ | 待处置 等 |
| A-03 | 用户深链 | ✓ | → `/users?keyword=10001` · `rk-02-user-link.png` |
| A-04 | 导出事件 / 刷新 | ✓ | `risk-events.csv` · `rk-08`/`rk-09` |

## B 黑名单 Tab

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 切 Tab / 空态 | ✓ | `?tab=blacklist` · 暂无黑名单 · `rk-03-blacklist.png` |
| B-02 | 加入黑名单 → 取消 | ✓ | 用户 ID 未预填 · 原因 · `rk-04`/`rk-05` |
| B-03 | 移出 → 取消 | — SKIP | 无行 |
| B-04 | 深链 `?tab=blacklist` | ✓ | 激活黑名单 · `rk-07-deeplink-blacklist.png` |
| B-05 | 导出黑名单 | ✓ | `risk-blacklist.csv` · `rk-10-export-blacklist.png` |

## C UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| C-01 | 900 / 1366 / 1920 | ✓ | `rk-ux-*` · `rk-99-end.png` |

## 结案

- [x] FAIL 0；加黑取消；移出 SKIP
|
