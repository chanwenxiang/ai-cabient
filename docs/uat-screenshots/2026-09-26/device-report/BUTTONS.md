# 设备报表 · 按钮清点 · 2026-09-26

> 工具：Playwright MCP · 账号 `13900000001` · 视口 1366×768 + 900 + 1920  
> 文档：[`DEVICE_REPORT_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_REPORT_FULL_BROWSER_UAT.md)

---

## B.1 筛选 / KPI

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| D-01 | 首屏 | ✓ | 设备数 3 · 离线 2 · 累计 ¥33.50 · 今日 ¥3.50 · `dr-01-home.png` |
| D-02 | 关键词 `7777` + 查询 | ✓ | URL `?keyword=7777` · 1 行发号柜 · `dr-02-keyword.png` |
| D-03 | 重置 | ✓ | keyword 空 · 3 行 · URL 清 · `dr-03-reset.png` |
| D-04 | URL `?online=OFFLINE` | ✓ | 状态=离线 · 共 2 台 · `dr-04-offline-url.png` |
| D-05 | KPI 离线设备 | ✓ | → `?online=OFFLINE` · `dr-05-kpi-offline.png` |
| D-06 | KPI 设备数 | ✓ | 清除筛选 → `/reports` |

## B.2 深链 / 详情

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| R-01 | 行「详情」 | ✓ | `/devices/330449777078` · `dr-06-detail.png` |
| R-02 | 设备名 link | ✓ | 同柜详情 · `dr-06b-name-link.png` |
| R-03 | 详情 Tab | ✓ | 温控/货道/投放/单据可切 · `dr-07-detail-tabs.png` |

## B.3 口径 / UX / 三端

| ID | 判定 | 证据 |
|----|------|------|
| 今日营收 KPI | ✓ | ¥3.50 = 工作台今日营收 |
| 金额列 `¥` | ✓ | ¥23.50 / ¥3.50 等 |
| 状态中文 | ✓ | 在线 / 离线（非 ONLINE 裸露） |
| 窄视口 | ✓ | docW=clientW · `dr-ux-narrow.png` |
| 1366/1920 | ✓ | `dr-ux-1366.png` / `dr-ux-1920.png` |
| 商户 mp 柜机 | ✓ | `pages/devices/devices` · `dr-mp-m-devices.png` |

## B.4 结案

- [x] 筛选/重置/URL
- [x] KPI 筛选
- [x] 行详情
- [x] ¥ / 中文
- [x] UX
- [x] 三端柜机抽样
|
