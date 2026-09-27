# 定时任务 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「系统 → 定时任务」**单页执行真源**。比 [`SYSTEM_FULL_BROWSER_UAT.md`](./SYSTEM_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。新增/编辑/立即执行/批停/批跑→取消；**禁止**拨动行内启停开关（无确认硬写）；内置不可删。  
> **源码**：`clients/admin-vue/src/views/system/ScheduledTaskView.vue`  
> **API**：`GET/POST/PUT/DELETE …/scheduled-tasks` · `…/enabled` · `…/run`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/scheduled-tasks/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/scheduled-tasks/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/scheduled-tasks/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 · SKIP 1（行开关硬写） |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「定时任务」· hint · 批量启用/停用/执行（有选中）· **新增** |
| 筛 | 关键词 · 查询/重置 |
| 表 | 名称/标识/分组/调度/状态开关/最近执行/结果说明/备注 · 空态「暂无定时任务」 |
| 行操作 | 立即执行（registryBound）· 编辑 · 删除（非内置 overflow） |
| 弹层 | 新增/编辑定时任务 |
| 工具 | 导出 · 刷新 |

---

## 2. 用例（ST-*）

| ID | 步骤 | 期望 |
|----|------|------|
| ST-01 | 打开 `/scheduled-tasks` | 共 N；最近执行中文 |
| ST-02 | 关键词无命中 → 重置 | 暂无定时任务 → 恢复 |
| ST-03 | 新增 → 取消 | 「新增定时任务」→ 取消 |
| ST-04 | 编辑 → 取消 | 任务标识 disabled → 取消 |
| ST-05 | 立即执行 → 取消 | MessageBox → 取消 |
| ST-06 | 删除 | 本环境无自定义 → SKIP |
| ST-07 | 批量停用 → 取消 | MessageBox → 取消 |
| ST-08 | 批量执行 → 取消 | MessageBox → 取消 |
| ST-09 | 行内启停开关 | **不拨**（硬写无确认） |
| ST-10 | 导出 / 刷新 | CSV · 可点 |
| ST-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 共 32；筛；新增编辑执行批停批跑均取消；开关未拨；导出
- [x] UX 900/1366/1920
- [x] BUTTONS / FINDINGS / Changelog；SYSTEM SYS-TASK-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **32**（页 20）· 补偿/优惠券/一致性巡检… |
| 筛 | 无命中空态 · 重置 32 |
| 写路径 | 新增取消 · 编辑 `compensation-process` disabled · 「立即执行「补偿任务处理」？」取消 |
| 批 | 「批量停用选中的 1 个」取消 · 「批量执行选中的 1 个」取消 |
| 删除 | SKIP（本页均为内置 registryBound） |
| 开关 | 可见 · **未拨** |
| 工具 | 导出 `定时任务_20260927_111900.csv` · 刷新 ✓ |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
