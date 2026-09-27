# 角色管理 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「系统 → 角色管理」**单页执行真源**。比 [`SYSTEM_FULL_BROWSER_UAT.md`](./SYSTEM_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。新建/编辑/分配权限→取消；启停确认→取消。导入 OS 框 SKIP。  
> **源码**：`clients/admin-vue/src/views/system/RoleManageView.vue`  
> **API**：`GET/POST/PUT …/rbac/roles` · `GET/PUT …/roles/{id}/permissions`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/roles/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/roles/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/roles/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「角色管理」· hint（权限字符；商户码不驱动小程序导航）· 新增角色 |
| 筛 | 关键词（名称/权限字符）· 状态 · 查询 · 重置 |
| 表 | 角色编号/角色/权限字符/状态/权限数/备注 · 空态「暂无角色」 |
| 行操作 | 编辑 · 分配权限（admin 禁用）· 更多启停（admin 无） |
| 弹层 | 新增/编辑；抽屉「分配权限」权限树 |
| 工具 | 导出 · 模板 · 导入 · 刷新 |

---

## 2. 用例（RL-*）

| ID | 步骤 | 期望 |
|----|------|------|
| RL-01 | 打开 `/roles` | 共 N↔API；状态中文 |
| RL-02 | 关键词无匹配 | 「暂无角色」· 共 0 |
| RL-03 | 关键词命中 | 如 `operator` 共 ≥1 |
| RL-04 | 状态选项 | 正常/停用 |
| RL-05 | 重置 | 恢复共 N |
| RL-06 | 新增角色 → 取消 | 弹层取消 |
| RL-07 | 编辑 → 取消 | 权限字符 disabled |
| RL-08 | 分配权限 → 取消 | 抽屉树 · 取消 |
| RL-09 | 更多·停用 → 取消 | MessageBox → 取消 |
| RL-10 | admin 分配权限 | disabled |
| RL-11 | 导出 / 模板 | CSV；导入 SKIP |
| RL-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 共 12↔API；筛空/命中/重置；状态中文
- [x] 新建·编辑·权限抽屉·停用均取消；admin 权限钮禁用
- [x] 导出/模板；导入 SKIP；UX 900/1366/1920
- [x] BUTTONS / FINDINGS / Changelog；SYSTEM SYS-ROLE-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **12** · admin/operator/replenisher/finance/viewer/merchant… |
| 筛 | 无匹配 0 · `operator` 共 1 · 重置 12 |
| 写路径 | 新建取消 · 编辑 `operator`（key disabled）· 权限树 **233** 节点取消 · 停用「运营人员」取消 |
| 保护 | 超级管理员「分配权限」disabled |
| 导出 | `角色_*.csv`（12）· `角色导入模板_*.csv` |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
