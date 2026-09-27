# 日志中心 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`OBSERVABILITY_FULL_BROWSER_UAT.md`](../../../uat/OBSERVABILITY_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

本环境 Grafana 未起：顶部 warning + 面板 `el-empty` 同文「Grafana 未启动，无法嵌入看板」；iframe=0；「新窗口打开」正确隐藏。六主题页签均可切且空态一致。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

页头 hint 已改为「6 个页签（含 ERROR 计数与运营概览）」；`build-admin` + `admin-static` 挂载后 Playwright 复核通过（`ob-fix-hint6.png`）。

## 数据对照

| 面 | UI |
|----|-----|
| Grafana | 未启动 |
| 页签 | **6**（流/错误/速率/ERROR 计数/追踪/运营概览） |
| hint | **6 个页签**（与签数一致） |
| 新窗口打开 | 离线隐藏 |
| Tips | 3 |

## 本轮缺陷

无开放 FAIL / FINDING。

### 已关闭

| # | 原级别 | 现象 | 处置 |
|---|--------|------|------|
| F1 | FINDING | hint「5 个页面」vs 签「6」 | 改 `ObservabilityView.vue` hint → 「6 个页签…」；重建 admin；复核 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 无 Grafana/Loki 时空态为诚实预期，非坏页。 |
| 2 | iframe 实嵌依赖 `infra\observability.ps1 on` + Grafana，另起后再验。 |
| 3 | 本地改 admin 后须 `build-admin` + `docker-compose.admin-static.yml`（或等价挂载）才加载新 hash。 |

## 证据

`ob-01`…`ob-10` · `ob-ux-*` · `ob-99-end` · `ob-fix-hint6`
|
