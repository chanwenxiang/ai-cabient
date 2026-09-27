# 部门管理 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「系统 → 部门管理」**单页执行真源**。比 [`SYSTEM_FULL_BROWSER_UAT.md`](./SYSTEM_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。新建/编辑/成员→取消；批量启停确认→取消。  
> **源码**：`clients/admin-vue/src/views/system/DepartmentManageView.vue`  
> **API**：`GET/POST …/departments` · `PUT …/departments/{id}` · `GET/PUT …/{id}/members`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/departments/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/departments/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/departments/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「部门管理」· hint（组织树+成员；数据范围在运营账号）· 批量启用/停用 · 新增部门 |
| 筛 | 无关键词筛（整表拉取+前端分页） |
| 表 | 编码/名称/上级/成员数/排序/状态/备注 · 空态「暂无部门」 |
| 行操作 | 编辑 · 成员 |
| 弹层 | 新增/编辑；部门成员 Transfer（可选账号 ↔ 部门成员） |
| 工具 | 刷新 · 列设置（**无导出**） |

---

## 2. 用例（DP-*）

| ID | 步骤 | 期望 |
|----|------|------|
| DP-01 | 打开 `/departments` | 共 N↔列表；状态中文 |
| DP-02 | 新增部门 → 取消 | 编码/名称/上级… → 取消 |
| DP-03 | 编辑 → 取消 | 编码 disabled → 取消 |
| DP-04 | 成员 → 取消 | Transfer 弹层 → 取消 |
| DP-05 | 批量停用 → 取消 | 勾选后 MessageBox → 取消 |
| DP-06 | 刷新 | 可点；无导出 |
| DP-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 共 6；新建·编辑·成员·批停均取消
- [x] 无导出仅刷新；UX 900/1366/1920
- [x] BUTTONS / FINDINGS / Changelog；SYSTEM SYS-DEPT-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **6** · HQ 总部 / FINANCE 财务部 / PROCUREMENT / MANAGER / 联调组 / L3-UAT |
| 写路径 | 新建取消 · 编辑编码 disabled · 成员「总部」Transfer 取消 · 批停「确认将选中的 1 个部门设为「停用」？」取消 |
| 工具 | 刷新 ✓ · **无导出** |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
