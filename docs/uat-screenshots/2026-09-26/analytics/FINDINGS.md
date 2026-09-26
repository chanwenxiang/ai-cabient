# 数据分析 · FINDINGS · 2026-09-26

> 与 [`BUTTONS.md`](./BUTTONS.md) / [`ANALYTICS_FULL_BROWSER_UAT.md`](../../../uat/ANALYTICS_FULL_BROWSER_UAT.md) 配套。

## 结论

**PASS · FAIL 0 · BLOCK 0** · 口径说明 1（离线分母）

数据分析页控件、KPI 深链、趋势天数、三组图型、侧栏深链、跨模块与商户 mp 三端口径均按工作台同款流程测完。

## 口径说明

| # | 现象 | 根因 | 怎么看 |
|---|------|------|--------|
| 1 | 侧栏「查看离线 **1**」，设备页离线 Tab **2** | 分析用 `stats.deviceTotal - deviceOnline`（投放口径）；设备列表含非投放 | 与大屏/工作台 lessons #193/#199 一致；非缺陷 |

## API / UI 快照（2026-09-26）

| 字段 | 值 |
|------|-----|
| revenueTodayCents | 350 → UI ¥3.50 |
| orderToday | 2 |
| doorSuccessRate24h | 1.0 → 100.0% |
| recognitionAutoRate24h | 1.0 → 100.0% |
| disputeOpen | 8 ↔ 列表共 8 条 |
| deviceOnline / deviceTotal | 0 / 1 → 离线 1 |
| finance.grossMarginRateToday | ≈0.457 → 查看 45.7% |
| 商户 mp revenueTodayCents | 350 · home ¥3.50 |

## 深链摘要

| 起点 | 落地 |
|------|------|
| 今日营收 / 毛利率 | `/finance` |
| 今日订单 | `/orders` |
| 开门成功率 | `/sessions` |
| 自动识别率 / 条待审 | `/disputes`（待审带 `status=OPEN`） |
| 查看离线 | `/devices?online=OFFLINE` |

## 证据文件

`an-01-home.png` · `an-ux-1366.png` · `an-ux-1920.png` · `an-k1`～`an-k4` · `an-s1`～`an-s3` · `an-x1-dashboard.png` · `an-x2-disputes.png` · `an-mp-m-home.png` · `an-mp-m-probe.json`
|
