# 录像上传 · 按钮清点 · 2026-09-26

> `/upload-queue` · Playwright **1366×768**  
> [`UPLOAD_QUEUE_FULL_BROWSER_UAT.md`](../../../uat/UPLOAD_QUEUE_FULL_BROWSER_UAT.md)

---

## A 头 / 说明 / 工具

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 首页空态 | ✓ | 共 0 · `uq-01-home.png` |
| A-02 | 上传说明 / 收起 | ✓ | `uq-02-help.png` |
| A-03 | 导出 / 刷新 | ✓ | 可见 |
| A-04 | `/videos` → 队列 | ✓ | 重定向 |
| A-05 | `/uploads` → 队列 | ✓ | 重定向 |

## B 筛选 / 深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 仅滞留 | ✓ | `stuck=1` · `uq-03-stuck.png` |
| B-02 | 上传状态「上传失败」 | ✓ | `uq-05-status-failed.png` |
| B-03 | 状态下拉中文 | ✓ | 无需上传/待上传/… · `uq-18-status-options.png` |
| B-04 | 选「无需上传」 | ✓ | `uploadStatus=NONE` · `uq-19-status-pick.png` |
| B-05 | 无匹配关键词 | ✓ | 共 0 · `uq-07-keyword-empty.png` |
| B-06 | 重置 | ✓ | `uq-08-reset.png` |
| B-07 | `?stuck=1` | ✓ | 勾选回显 · `uq-09-deeplink-stuck.png` |
| B-08 | `?deviceId=CAB-001` | ✓ | 输入回显 · `uq-10-deeplink-device.png` |
| B-09 | `?sessionId=` | ✓ | 输入回显 · `uq-17-deeplink-session.png` |
| B-10 | 滞留空态文案 | ✓ | `uq-20-stuck-empty.png` |

## C 行操作

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 会话深链 | — SKIP | 本机队列无行 |
| C-02 | 设备深链 | — SKIP | 本机队列无行 |
| C-03 | 播放 | — SKIP | 本机队列无行 |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 900 无整页横滚 | ✓ | `uq-ux-narrow.png` |
| D-02 | 1366 / 1920 | ✓ | `uq-ux-*.png` |
| D-03 | 结束视口 1366 | ✓ | `uq-99-end.png` |

## 结案

- [x] FAIL 0；无硬写；视口结束 1366
|
