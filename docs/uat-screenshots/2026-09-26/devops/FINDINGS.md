# DevOps 中心 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`DEVOPS_FULL_BROWSER_UAT.md`](../../../uat/DEVOPS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

本环境 **未起 Grafana / Prometheus / Sonar**（与 Overview「不含 devops」一致）。四工具卡中文名与状态 Tag 正确；「重跑 Sonar」**disabled**；Grafana 区诚实空态「Grafana 未启动。请先运行 .\docker-up.ps1（Grafana 在全栈中）」且无 iframe。PromQL×8 经 stub `window.open` 生成正确 `:9090/graph` URL。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI |
|----|-----|
| Grafana / Prometheus / Sonar | 未检测到服务 |
| GitHub Actions | 外部链接 |
| Grafana 嵌入 | 空态 · iframe=0 |
| Sonar 重跑 | disabled |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 观测栈未起时「未检测到服务」+ Grafana 空态为诚实预期，非坏页。 |
| 2 | 「新窗口打开」/ PromQL 本轮 stub，避免 OS 弹窗；URL 已取证。 |
| 3 | Sonar 在线时须确认→取消；本环境禁用故未开确认框。 |
| 4 | 深测 iframe 实嵌依赖 `infra` Grafana（`:13000`）另起后再验。 |

## 证据

`dv-01`…`dv-07` · `dv-ux-*` · `dv-99-end`
|
