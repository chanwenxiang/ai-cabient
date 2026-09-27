# 补货员效率 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「履约仓储 → 补货员效率」**单页执行真源**。比 [`WAREHOUSE_FULL_BROWSER_UAT.md`](./WAREHOUSE_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。本页**无硬写**（筛选/导出/刷新只读）。  
> **源码**：`clients/admin-vue/src/views/growth/ReplenishmentStaffView.vue`  
> **API**：`GET /api/v2/ops/admin/replenishment-report/staff?days=`（无分页；关键词前端过滤）  
> **视口**：主测 **1366×768**（DPR=1）。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/replenishment-staff/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/replenishment-staff/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/replenishment-staff/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「补货员效率」· hint 排班考核 · 近 7/30/90 天 |
| 筛选 | 关键词（姓名）· 查询 / 重置 |
| 表 | CrudTable：工号/姓名/手机/任务数/已完成/完成率/平均耗时/待办/日均 · 导出/刷新/列设置 |

---

## 2. 用例（RS-*）

| ID | 步骤 | 期望 |
|----|------|------|
| RS-01 | 打开 `/replenishment-staff` | 默认近 30 天；共 N↔API |
| RS-02 | 表头 | 中文「已完成」等，无 COMPLETED 泄漏 |
| RS-03 | 近 7 天 | total↔API；本机共 0 + 空态 |
| RS-04 | 近 90 天 | total↔API |
| RS-05 | 关键词命中姓名片段 | 共=行数 |
| RS-06 | 无匹配关键词 | 共 **0** +「暂无补货任务数据」（total 诚实） |
| RS-07 | 重置 | 清空关键词；恢复列表（天数保持） |
| RS-08 | 导出 / 刷新 / 列设置 | 导出 CSV；工具栏可点 |
| RS-09 | 行勾选 | 可勾选；无批量写 |
| RS-U-01 | 900 | 无整页横滚 |
| RS-U-03 | 结束 | 视口 1366×768 DPR=1 |

---

## 3. 结案清单

- [x] 默认 30 天共 **1**（完成率 60.0%）；7 天空；90 天 1
- [x] 关键词滤空 total=0；重置恢复；导出 CSV
- [x] 表头中文；无硬写；窄视口无横滚
- [x] BUTTONS / FINDINGS / Changelog；WAREHOUSE 册深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 近 30 天 | 共 **1** · 默认商户管理员 · 任务 5 · 完成 3 · **60.0%** · 待办 2 |
| 近 7 天 | 共 **0** · 空态 |
| 近 90 天 | 共 **1** ↔ API |
| 关键词 | 命中 1 / 无匹配 0（total 诚实） |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
