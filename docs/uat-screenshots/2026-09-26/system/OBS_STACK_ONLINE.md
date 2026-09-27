# DevOps / 日志 · 观测栈在线复核 · 2026-09-27

> 在 Phase7 单页深测（离线诚实空）之后，本机拉起 Grafana/Prometheus + `observability.ps1 on`，复核 iframe 实嵌。  
> 视口 **1366×768** · Playwright MCP · `13900000001`

## 环境

| 组件 | 状态 |
|------|------|
| Grafana | Up · hub `online=true` · Tag「在线」 |
| Prometheus | Up · Tag「在线」 · `/-/healthy` |
| Loki / Tempo / promtail | Up（`observability.ps1 on`） |
| SonarQube | 仍未检测到（预期） |

## DevOps `/devops`

| 项 | 结果 | 证据 |
|----|------|------|
| 状态卡 | Grafana/Prometheus **在线** | `dv-online-02-after-grafana-login.png` |
| iframe | 有；空态消失 | 运营概览：设备在线 **1**/总数 **3**；MISMATCH **0** |
| 未登录 Grafana | iframe 内为 Welcome/Log in | `ob-online-01`（同实例） |
| 登录后 | `POST /devops/grafana/login` admin/admin → 看板可读 | `dv-online-02` |
| 文案 F2 | 「无需单独登录」与 M12 关匿名冲突 → 已改为「需先登录一次」 | `dv-online-03-hint-fix.png` |

## 日志中心 `/observability`

| 项 | 结果 | 证据 |
|----|------|------|
| 离线 Alert | **消失** | — |
| 新窗口打开 | 可见 | — |
| iframe ×6 | 均挂载；流页有网关 access log | `ob-online-03-after-login.png` |
| 运营概览签 | 开门成功率 / 设备在线 / MISMATCH | `ob-online-04-overview.png` |

## 判定

| ID | 判定 |
|----|------|
| SYS-DEVOPS-03 | **PASS**（在线 + 实嵌；须 Grafana 会话） |
| SYS-OBS-03 | **PASS**（六签实嵌；告警消失） |
| F2 | **已修**（DevOps hint） |

## 口径

1. M12：`GF_AUTH_ANONYMOUS_ENABLED=false` ⇒ 嵌入前需 Grafana 登录一次；cookie 同源后 iframe 共用。  
2. 网关 `auth_basic` 配置存在；本机实测无凭据仍可打到 Grafana 登录页（以实嵌入为准，Basic 层另查）。  
3. 看完可 `infra/observability.ps1 off`；Grafana/Prometheus 可按需停以省内存。
|
