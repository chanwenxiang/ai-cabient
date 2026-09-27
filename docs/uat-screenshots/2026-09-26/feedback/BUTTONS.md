# 用户反馈 · 按钮清点 · 2026-09-27

> `/feedback` · Playwright **1366×768**（DPR=1）  
> [`FEEDBACK_FULL_BROWSER_UAT.md`](../../../uat/FEEDBACK_FULL_BROWSER_UAT.md)  
> 规则：回复 / 删除一律 **打开→取消**；禁止真回复、真删。

---

## A 头 / 列表

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开 · 共 8 | ✓ | 建议·待处理 · `fb-01-home.png` |
| A-02 | hint | ✓ | 回复不推送用户 |

## B 筛选 / 深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 状态选项中文 | ✓ | 待处理/已回复/已关闭 · `fb-02-status-opts.png` |
| B-02 | 筛待处理 | ✓ | 共 8 · `?status=PENDING` · `fb-03-pending.png` |
| B-03 | 重置 | ✓ | URL 清 status · `fb-04-reset.png` |
| B-04 | 深链 `?status=PENDING` | ✓ | `fb-05-deeplink.png` |

## C 写路径 / 行链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 回复 → 取消 | ✓ | 「回复反馈」· textarea · `fb-06`/`fb-07` |
| C-02 | 删除 → 取消 | ✓ | 「确认删除反馈 #1？」· `fb-08-delete-confirm.png` |
| C-03 | 用户链 | ✓ | `10001` → `/users?keyword=10001` · `fb-09-user-link.png` |
| C-04 | 设备链 | ✓ | `CAB-001` → `/devices/CAB-001` · `fb-09b-device-link.png` |

## D 工具 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 导出 CSV | ✓ | `用户反馈_*.csv` · 已导出 8 条 · `fb-10-export.png` |
| D-02 | 刷新 | ✓ | `fb-11-refresh.png` |
| D-03 | 900 / 1366 / 1920 | ✓ | `fb-ux-*` · `fb-99-end.png` |

## 结案

- [x] FAIL 0；FINDING 0；回复/删除均取消未提交
|
