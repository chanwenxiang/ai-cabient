# 日志中心 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「系统 → 日志中心」**单页执行真源**。比 [`SYSTEM_FULL_BROWSER_UAT.md`](./SYSTEM_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开；只读嵌入；无观测栈须诚实空态；禁止为测而起/硬改 Grafana。  
> **源码**：`clients/admin-vue/src/views/system/ObservabilityView.vue`  
> **API**：`GET …/devops/hub`（复用 DevOps 状态）  
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
| 截图 | `docs/uat-screenshots/2026-09-26/observability/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/observability/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/observability/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0（F1 已修） · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「日志中心」· hint · **刷新状态** ·（在线时）新窗口打开 |
| 告警 | Grafana 离线 → warning「Grafana 未启动，无法嵌入看板」 |
| 页签 | 全栈日志流 / 错误与告警 / 日志速率 / ERROR 计数 / 一次调用追踪 / 运营概览（**6**） |
| 嵌入 | 在线 iframe；离线 `el-empty` 同文案 |
| 其它入口 | 终端 / Explore / Loki API 三 tip |

---

## 2. 用例（OB-*）

| ID | 步骤 | 期望 |
|----|------|------|
| OB-01 | 打开 `/observability` | 标题；离线 alert+empty；iframe=0 |
| OB-02 | 刷新状态 | 仍诚实空 |
| OB-03 | 切全部页签 | 每签 empty 同文；active 正确 |
| OB-04 | 其它入口卡 | ≥3 tip |
| OB-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 六页签可切 + Grafana 诚实空；刷新；tips
- [x] UX 900/1366/1920
- [x] BUTTONS / FINDINGS / Changelog；SYSTEM SYS-OBS-02
- [x] F1 已修：hint「6 个页签」· Playwright 复核 · `ob-fix-hint6.png`

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| Alert / Empty | 「Grafana 未启动，无法嵌入看板」· iframe=0 |
| 新窗口打开 | 离线隐藏（`v-if="grafanaOnline"`） |
| 页签 | **6**：流/错误/速率/ERROR 计数/追踪/运营概览 · 各签空态一致 |
| Tips | 终端 · Explore · Loki API |
| 视口 | 窄 900 无横滚 · 结束 1366 |

---

## 5. 观测栈在线复核（同日）

见 [`../uat-screenshots/2026-09-26/system/OBS_STACK_ONLINE.md`](../uat-screenshots/2026-09-26/system/OBS_STACK_ONLINE.md)：六 iframe 实嵌日志流/概览（SYS-OBS-03）；未登录时 iframe 内为 Grafana Log in。
|
