# DevOps 中心 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「系统 → DevOps 中心」**单页执行真源**。比 [`SYSTEM_FULL_BROWSER_UAT.md`](./SYSTEM_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开；**禁止硬点「重跑 Sonar」确认后的「开始扫描」**；外链用 stub `window.open` 取证、不弹 OS 窗。  
> **源码**：`clients/admin-vue/src/views/system/DevOpsHubView.vue`  
> **API**：`GET …/devops/hub`；`POST …/devops/sonar/scan`（本轮未触发）  
> **视口**：主测 **1366×768**（DPR=1）。  
> **版本**：1.0 · 2026-09-27

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-27 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/devops/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/devops/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/devops/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「DevOps 中心」· hint · **刷新状态** |
| 工具卡 ×4 | Grafana / Prometheus / SonarQube / GitHub Actions · 状态 Tag · 新窗口打开 |
| PromQL | 8 个中文查询按钮 → 打开 Prometheus graph（本机 `:9090`） |
| Grafana 嵌入 | 在线则 iframe；离线则中文空态 |

---

## 2. 用例（DV-*）

| ID | 步骤 | 期望 |
|----|------|------|
| DV-01 | 打开 `/devops` | 四卡中文名；状态 Tag 可读 |
| DV-02 | 刷新状态 | 可点；卡仍在 |
| DV-03 | PromQL 首项/次项 | stub 打开 `…:9090/graph?g0.expr=…` |
| DV-04 | Grafana 区 | 未起 →「Grafana 未启动…」；无 iframe |
| DV-05 | 下方嵌入看板 | 可点滚动 |
| DV-06 | 新窗口打开 | stub 捕获 URL（如 `/devops/grafana`） |
| DV-07 | 重跑 Sonar | 离线禁用；在线则确认→**取消** |
| DV-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 四工具卡 + 刷新；PromQL×8 stub；Grafana 诚实空；Sonar 禁用未硬扫
- [x] UX 900/1366/1920
- [x] BUTTONS / FINDINGS / Changelog；SYSTEM SYS-DEVOPS-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 工具卡 | Grafana/Prometheus/Sonar「未检测到服务」· GitHub「外部链接」 |
| PromQL | 8 钮；`up` / `cabinet_devices_online` stub URL ✓ |
| Grafana | 空态「Grafana 未启动。请先运行 .\docker-up.ps1（Grafana 在全栈中）」· iframe=0 |
| Sonar | **disabled**（未确认扫描） |
| 外链 | stub：`http://localhost/devops/grafana` 等 |
| 视口 | 窄 900 无横滚 · 结束 1366 |

---

## 5. 观测栈在线复核（同日）

见 [`../uat-screenshots/2026-09-26/system/OBS_STACK_ONLINE.md`](../uat-screenshots/2026-09-26/system/OBS_STACK_ONLINE.md)：Grafana/Prometheus 在线后 iframe 实嵌运营概览（SYS-DEVOPS-03）；hint 已改为「需先登录一次 Grafana」。
|
