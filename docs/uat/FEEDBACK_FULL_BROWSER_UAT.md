# 用户反馈 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「增长风控 → 用户反馈」**单页执行真源**。比 [`GROWTH_RISK_FULL_BROWSER_UAT.md`](./GROWTH_RISK_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。回复→取消；删除确认→取消。  
> **源码**：`clients/admin-vue/src/views/feedback/FeedbackView.vue`  
> **API**：`GET /api/v2/ops/feedback` · `POST …/{id}/reply` · `DELETE …/{id}`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/feedback/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/feedback/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/feedback/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「用户反馈」· hint（回复为运营备注，不推送用户） |
| 筛 | 状态（dict `feedback_status`）· 查询 · 重置；URL `?status=` 深链 |
| 表 | 反馈编号/类型/内容/用户链/设备链/评分/状态/时间 · 空态「暂无反馈」 |
| 行操作 | 回复（仅待处理）· 删除（有确认） |
| 弹层 | 「回复反馈」：原文 + 回复内容 |
| 工具 | 导出 CSV · 刷新 · 列设置 |

---

## 2. 用例（FB-*）

| ID | 步骤 | 期望 |
|----|------|------|
| FB-01 | 打开 `/feedback` | 共 N↔API；类型/状态中文 |
| FB-02 | 状态筛选项 | 中文（待处理/已回复/已关闭） |
| FB-03 | 筛「待处理」 | 共 N · URL `?status=PENDING` |
| FB-04 | 重置 | 清状态 · 恢复 |
| FB-05 | 深链 `?status=PENDING` | 列表与筛同步 |
| FB-06 | 回复 → 取消 | 「回复反馈」弹层 → 取消 |
| FB-07 | 删除 → 取消 | MessageBox「确认删除反馈 #N」→ 取消 |
| FB-08 | 用户 / 设备链 | 跳转 `/users?keyword=` · `/devices/{id}` |
| FB-09 | 导出 / 刷新 | CSV 可下；刷新可点 |
| FB-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 共 8↔API（均待处理·建议）；状态中文筛/深链/重置
- [x] 回复取消 · 删除取消；用户链 · 设备链 CAB-001
- [x] 导出 `用户反馈_*.csv`；UX 900/1366/1920
- [x] BUTTONS / FINDINGS / Changelog；GROWTH_RISK GR-FB-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **8** · 类型「建议」· 状态「待处理」· 用户 10001 |
| 筛/深链 | 待处理 8 · `?status=PENDING`；重置回全部 |
| 写路径 | 回复取消 · 删 #1 取消 |
| 深链 | 用户 → `/users?keyword=10001`；设备 → `/devices/CAB-001` |
| 导出 | `用户反馈_20260927_012602.csv` · 已导出 8 条 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
