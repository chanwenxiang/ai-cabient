# 七册全量浏览器 UAT · 收口总览 · 2026-09-26

> 账号 `13900000001` · Playwright MCP · 视口 1366×768  
> 分册底稿：`docs/uat/*_FULL_BROWSER_UAT.md` · 证据：`docs/uat-screenshots/2026-09-26/`  
> **侧栏复测**：履约 → 设备商品 → 仓储 → 财务 → 增长 → 系统 已按概览同款附录 B 重跑（同日）

| Phase | 分册 | 冒烟 | 按钮级 | 关键修复 / 债 |
|-------|------|------|--------|----------------|
| 1 | Overview | Done | Done | 大屏/工作台口径等 |
| 2 | Fulfillment | Done | **复测 Done** | #195–#197；`ff-*.png` |
| 3 | Device/SKU | Done | **复测 Done** | 含识别映射；`ds-*.png` |
| 4 | Warehouse | Done | **复测 Done** | #198/#200；含补货员效率 |
| 5 | Finance/Merchant | Done | **复测 Done** | 一致性 **全部通过** |
| 6 | Growth/Risk | Done | **复测 Done** | 发券/加黑/回复均取消 |
| 7 | System + DevOps/日志 | Done | **复测 Done** + **2026-09-27 单页深测 Done** | Grafana 未启诚实空；十四入口 `*_FULL_BROWSER_UAT`；OBS hint F1 已修 |

## 2026-09-27 系统单页深测收口

| 入口 | 深测册 | SYS-*-02 |
|------|--------|----------|
| operators…audit | 各 `*_FULL_BROWSER_UAT.md` | 已登 SYSTEM |
| `/devops` | `DEVOPS_FULL_BROWSER_UAT.md` | SYS-DEVOPS-02 |
| `/observability` | `OBSERVABILITY_FULL_BROWSER_UAT.md` | SYS-OBS-02（F1 已修） |

七册 Admin 侧栏深测（冒烟 + 按钮复测 + 系统单页深测）**至此收口**。  
**2026-09-27 观测栈在线复核**：Grafana/Prometheus + Loki 已起 → iframe 实嵌 PASS（须 Grafana 登录一次）；见 [`system/OBS_STACK_ONLINE.md`](./system/OBS_STACK_ONLINE.md)。残留：小程序回补见 `MINIPROGRAM_BACKLOG.md`。

## 一致性

立即巡检 → **全部通过**（UI 修复库存/积分 + DB 退款对齐/删孤儿争议/补 SALE 流水）。见 `finance-merchant/FINDINGS.md`。

## DevOps 边界

本轮 Docker **未起 Grafana/Prometheus/Sonar**：`/devops`、`/observability` 页可开，空态/禁用诚实；iframe 实嵌留待观测栈环境。证据：`system/DEVOPS_BUTTONS.md` + `sys-obs-01.png`。

## 易复发索引

| # | 主题 |
|---|------|
| 195–197 | 滞留会话 / 订单行 JsonView / 按行退款确认 |
| 198 | SLA 仅投放柜 + 开门时长可读 |
| 199 | 大屏排行仅投放柜 |
| 200 | 弹窗取消须等 enter 动画结束 |
