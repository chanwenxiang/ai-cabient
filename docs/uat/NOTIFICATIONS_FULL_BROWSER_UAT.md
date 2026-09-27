# 消息记录 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「增长风控 → 消息记录」**单页执行真源**。比 [`GROWTH_RISK_FULL_BROWSER_UAT.md`](./GROWTH_RISK_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。发送/编辑→取消；删除/批删确认→取消。  
> **源码**：`clients/admin-vue/src/views/growth/NotificationsView.vue`  
> **API**：`GET …/growth/notifications` · `POST …/send` · `PUT/DELETE …/{id}` · `POST …/batch-delete`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/notifications/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/notifications/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/notifications/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING **2** · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「消息记录」· hint（站内信；可发消费者/商户）· 删除选中 · 发送站内信 |
| 筛 | 无关键词筛（服务端分页） |
| 表 | ID/时间/受众/标题/内容/业务/关联单号 · 空态「暂无消息记录」 |
| 行操作 | 编辑 · 删除（有确认） |
| 弹层 | 发送：受众·用户ID/商户编号·标题·内容；编辑：标题·内容 |
| 工具 | 导出 CSV · 刷新 · 列设置 |

---

## 2. 用例（NTF-*）

| ID | 步骤 | 期望 |
|----|------|------|
| NTF-01 | 打开 `/notifications` | 共 N↔API；受众/业务中文（见 FINDING） |
| NTF-02 | 发送站内信 → 取消 | 弹层；切商户见「商户编号」→ 取消 |
| NTF-03 | 编辑 → 取消 | 「编辑站内信」→ 取消 |
| NTF-04 | 行删除 → 取消 | MessageBox「确认删除消息 #N」→ 取消 |
| NTF-05 | 删除选中 → 取消 | 勾选后 MessageBox → 取消 |
| NTF-06 | 导出 / 刷新 | CSV 可下；刷新可点 |
| NTF-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 共 2↔API；发送/编辑/行删/批删均取消未提交
- [x] 导出 `消息记录_*.csv`；UX 900/1366/1920
- [x] FINDING：受众 OPS 裸码；业务 MERCHANT_REPLEN_REQUEST→未知
- [x] BUTTONS / FINDINGS / Changelog；GROWTH_RISK GR-NTF-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **2** · #1 OPS 审批提醒（要货）· #2 消费者「完整轮站内信」 |
| 写路径 | 发送取消（消费者/商户字段切换）· 编辑取消 · 删 #1 取消 · 批删 1 条取消 |
| 导出 | `消息记录_20260927_012104.csv` · toast「已导出 2 条」 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
