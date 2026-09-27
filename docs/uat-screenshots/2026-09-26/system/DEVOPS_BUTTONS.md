# DevOps / 日志中心 · 按钮清点 · 2026-09-26

> Playwright MCP · `13900000001` · 1366×768  
> 本环境 **未起 Grafana / Prometheus / Sonar**，期望诚实空态与禁用，禁止为测而硬点「重跑」。

---

## DevOps 中心 `/devops`

| 控件 | 判定 | 证据 |
|------|------|------|
| 页可开 | ✓ | 标题「DevOps 中心」；文案「监控、CI/CD 与代码质量…」 |
| 刷新状态 | ✓ | 可点 |
| Grafana / Prometheus 入口 | ✓ 诚实 | 「未检测到服务」；「新窗口打开」「下方嵌入看板」可见 |
| 下方嵌入看板 | ✓ | 可点；无服务时 iframe 仍为 0 |
| 新窗口打开 | ✓ 可见 | 未点（会开外链） |
| 重跑 Sonar | ✓ 禁用 | `disabled`（本环境无 Sonar） |
| 业务指标磁贴 | ✓ | 服务是否在线 / 设备数 / 开门速率等可点；会尝试打开 Prometheus（本机 `:9090` 未起 → 浏览器错误页，属预期） |

## 日志中心 `/observability`

| 控件 | 判定 | 证据 |
|------|------|------|
| 页可开 | ✓ | 标题「日志中心」；说明与 DevOps 共用 Grafana |
| 刷新状态 | ✓ | 可点 |
| 主题页签 ×5 | ✓ | 全栈日志流 / 错误与告警 / 日志速率 / 一次调用追踪 / 运营概览均可切 |
| 嵌入空态 | ✓ | 各主题统一「Grafana 未启动，无法嵌入看板」中文空态 |

---

## 判定

| ID | 判定 |
|----|------|
| SYS-DEVOPS-01 | **PASS***（页可用 + 无观测栈时诚实空态/禁用；未真跑 Sonar） |
| SYS-DEVOPS-02 | **PASS**（2026-09-27 单页深测：[`DEVOPS_FULL_BROWSER_UAT.md`](../../../uat/DEVOPS_FULL_BROWSER_UAT.md) · `devops/`） |
| SYS-OBS-01 | **PASS***（主题可切 + Grafana 未起空态；当时记×5） |
| SYS-OBS-02 | **PASS**（2026-09-27 单页深测：[`OBSERVABILITY_FULL_BROWSER_UAT.md`](../../../uat/OBSERVABILITY_FULL_BROWSER_UAT.md) · **6** 签 · hint F1 已修） |
| SYS-DEVOPS-03 | **PASS**（观测栈在线 iframe：[`OBS_STACK_ONLINE.md`](./OBS_STACK_ONLINE.md) · `dv-online-*`） |
| SYS-OBS-03 | **PASS**（观测栈在线六签实嵌：同册 · `ob-online-*`） |

\* 离线空态用例保留；实嵌见 SYS-*-03。

## Phase 7 附录 Done

- [x] DevOps / 日志中心按钮级清点  
- [x] 无观测栈环境不误报「坏页」
