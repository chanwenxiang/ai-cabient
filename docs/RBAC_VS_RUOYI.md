# 若依权限设计 vs 本系统（AI Cabinet）

对照若依（RuoYi）经典 RBAC，说明本仓**已对齐**、**刻意差异**与**部门演进边界**。

## 1. 若依标准模型

| 概念 | 若依实现要点 |
|------|----------------|
| 菜单 | `sys_menu`：M 目录 / C 菜单 / F 按钮，`perms` 权限字符 |
| 角色 | `sys_role` + `sys_role_menu` |
| 用户 | `sys_user_role`；用户归属 **一个** `dept_id` |
| 部门 | `sys_dept` **树**（parent_id / ancestors） |
| 数据权限 | 角色 `data_scope`：全部 / 自定义 / 本部门 / 本部门及以下 / 仅本人；`@DataScope` 按业务表 `dept_id` 过滤 |

前提：业务数据带运营组织 `dept_id`。

## 2. 本系统映射

| 维度 | 若依 | 本系统 | 结论 |
|------|------|--------|------|
| 菜单 M/C/F | 有 | `ops_permission.perm_type` | 已对齐 |
| 权限字符 + 通配 | `system:user:list` | `ops:*` / `merchant:*`；`PermissionService` 分段通配 | 已对齐（若依风格） |
| 角色绑权限 | `sys_role_menu` | `ops_role_permission` | 已对齐 |
| 用户绑角色 | `sys_user_role` | `ops_user_role` | 已对齐 |
| 部门树 | `sys_dept` | `ops_department.parent_id`（V233+） | 对齐中 |
| 用户部门 | 单 `dept_id` | 多部门 + **主部门** `is_primary` | 扩展对齐 |
| 交易数据范围 | 部门 `data_scope` | **商户 / 设备 / 线路**（`MerchantScopeService`） | **刻意不同** |
| 审批指派 | 无内置 | 节点 `DEPT/PERM/ROLE/USER` | 本仓增强 |
| 项目内 `DataScopeDto` | — | 仅演示/mock 横幅 | **不是**若依 data_scope |

## 3. 为何交易数据不跟若依「按部门过滤」

开门柜主数据是商户、柜机、订单，隔离维度是租户与设备，不是「财务部 vs 采购部」。

- **功能权限**（能不能点）：角色 + M/C/F → 学若依  
- **交易数据范围**（能看哪些商户/柜）：商户绑定 + 设备/线路 → 保持现有  
- **运营部门**：组织归属 + 审批待办分组（及将来仅对带 `dept_id` 的运营单据做范围）

```text
角色/菜单/权限  →  页面与按钮
部门（主+兼任） →  组织 + 审批 DEPT 指派
商户/设备绑定  →  订单/设备列表隔离
```

## 4. 后台入口

| 能力 | 路径 | 说明 |
|------|------|------|
| 角色 / 菜单 / 运营账号 | `/roles` `/menus` `/operators` | 若依式功能权限 |
| 部门 | `/departments` | 树形组织 + 成员；可设主部门 |
| 审批流 | `/approvals` | 节点可按部门指派 |
| 商户/货柜范围 | 运营账号弹窗 | 交易数据隔离（非部门） |

更多审批关系见 [APPROVAL_DEPARTMENT_FLOW.md](APPROVAL_DEPARTMENT_FLOW.md)。
