# 设备报表 · 全量浏览器 UAT（单页深测册）

> **地位**：「概览 → 设备报表」**单页执行真源**。比 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §4.5 更细。  
> **工具铁律**：Playwright 真实打开/点击；三端口径只认 mp-weixin。  
> **源码**：`clients/admin-vue/src/views/reports/DeviceReportView.vue`  
> **API**：`GET …/ops/admin/reports/devices`  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 环境 | Docker gateway `:80` / trade `:18080` |
| 视口 | 1366×768 + 窄视口表横滚 + 1920 |
| 账号 | 运营 `13900000001`；商户 mp `13800138001` |
| 截图 | `docs/uat-screenshots/2026-09-26/device-report/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/device-report/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/device-report/FINDINGS.md) |
| 统计 | PASS 筛选/KPI/深链/详情/UX/三端 / FAIL 0 / BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 页头 | 「设备经营报表」 |
| KPI×4 | 设备数（清在线筛选）· 离线设备（切换 OFFLINE）· 累计营收 · 今日营收（本页合计提示） |
| 筛选 | 柜机下拉 · 关键词 · 状态（在线/离线）· 查询 · 重置 |
| 表 | 编号/名称深链 · 状态中文 · 商户/线路/地址 · 停售 · 温/固件 · 累计/今日订单营收客单 · 会话 |
| 行操作 | 「详情」→ `/devices/{id}` |
| 导出 | CSV（有 `ops:report:export`） |

---

## 2. 用例（DR-*）

| ID | 步骤 | 期望 |
|----|------|------|
| DR-01 | 打开 `/reports` | 标题；表或空态中文；KPI hydrated |
| DR-02 | 关键词/状态 + 查询 | 列表过滤；total 变 |
| DR-03 | 重置 | 条件清空重载 |
| DR-04 | URL `?online=OFFLINE` | 状态下拉=离线；列表仅离线 |
| DR-05 | KPI 离线设备 | 切到/取消离线筛选 |
| DR-06 | 行「详情」/设备名 | → `/devices/{id}` 首屏 |
| DR-07 | 详情 Tab 抽检 | 可切换无崩 |
| DR-08 | 金额列 | `¥` |
| DR-X | 今日营收合计 vs 工作台/分析 | 量级可对（注意本页合计 vs 全库） |
| DR-U | 窄视口 | 表内横滚，非整页白卡裁切 |

---

## 3. 结案清单

- [x] 筛选/重置/URL 深链
- [x] KPI 点击筛选
- [x] 行详情落地
- [x] ¥ / 中文状态
- [x] UX
- [x] BUTTONS / FINDINGS

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 关键词/重置 | PASS；`7777`→1 行；重置→3 行 |
| `?online=OFFLINE` / KPI 离线 | PASS；共 2 台；设备数 KPI 清除筛选 |
| 详情 | `/devices/330449777078`；Tab 可切 |
| 口径 | 今日营收 ¥3.50 = 工作台 |
| UX | 窄/1366/1920 无整页横滚 |
| 三端 | 商户 `pages/devices/devices` |
|
