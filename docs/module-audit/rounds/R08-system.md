# R8 系统（15 页 + 登录/错误页 + 权限矩阵专项）

- **锚**：发现锚 `d79c52fe`（HEAD 以 `git rev-parse --short HEAD` 为准）；修复锚见本批末尾提交号。
- **环境**：本机 dev 栈（trade `:18080`、nginx `:80`、admin vite `:3100`）；UI 一律 Playwright 驱动本机真 Chrome（headless 批量探针 + 关键页截图）。
- **进度**：批 1（6 页）✅ 完成 —— `/operators` `/roles` `/departments` `/approvals` `/menus` `/dicts`；批 2（9 页 + 登录/错误页 + 权限矩阵）未开始。

## 批 1 逐页结论

| 页面 | 数据/口径核对（真值来源） | 交互与写面 | 结论 |
|------|---------------------------|------------|------|
| `/operators` 运营账号 | 11 行 = SQL；`phone` 为**服务端过滤**（`139`→5、全号→1、乱码→0，`total` 同步）；「数据范围」列与 `ops_user_merchant` 逐行一致（100000011=1、100000010=2、100000009=2） | 新增/重置密码/角色/商户范围/设备范围均为独立弹窗（本批只开框验证确认门，未提交写操作） | 无缺陷 |
| `/roles` 角色管理 | 「权限数」10 行逐条 = `ops_role_permission` 计数（198/75/23/44/29/41/22/19/23/11） | 关键词+状态筛选进 SQL；导入/导出/模板齐 | 无缺陷 |
| `/departments` 部门管理 | 4 行 = `ops_department`；「成员数」1/2/1/1 = `ops_user_department` 计数 | 无筛选条（4 行树表，可接受）；批量启用/停用有确认 | 无缺陷 |
| `/approvals` 审批流配置 | 7 条流程定义；节点图预览列有值 | 新增/编辑走弹窗 | 无缺陷（节点指派的越权面留批 2 权限矩阵） |
| `/menus` 菜单管理 | 树 201 行 = `ops_permission`；C 行 ACTIVE 62 条 | 关键词/类型筛选、含停用开关、新增/编辑/停用有确认 | **MA-019（P2）已修（档 A）** |
| `/dicts` 字典管理 | 左列 96 类型 = `sys_dict_type`；「项数」逐条 = `sys_dict_data` ACTIVE 计数（抽样 14 条全等）；右表 50/页且表头显式给「N 项」 | 类型/字典项分栏、导入导出、编辑确认 | 无缺陷 |

## MA-019（P2，档 A 已修）：菜单树提示语谎报「一一对应」，停用一行会连带隐藏最多 8 个侧栏页

- **原话（旧提示）**：「运营侧栏叶子菜单与本树 ACTIVE『菜单 C』一一对应（一级目录 = 侧栏分组）。停用菜单会从侧栏隐藏。」
- **实测**：侧栏 77 页里只有 62 条 C 行，且**一个权限码被多页共用**（`NAV_ITEMS.perm` 计数）：
  `ops:warehouse:list` → **8 页**、`ops:device:list` → 4 页、`ops:replenishment:list` / `ops:fund:list` → 3 页、
  `ops:config:list`（参数配置）→ 2 页（连带 `/alert-rules`）、`ops:devops:view` → 2 页（连带 `/observability`）等，共 10 个共用码 ⇒ **19 个侧栏页没有自己的 C 行**。
  后端 `OpsRbacService.activeNavPermissions`（只取 ACTIVE 的 C/M 码）+ `OpsPermissionMapper.selectPermCodesByUserId`（`p.status='ACTIVE'`）实锤：停用一行 = 共用它的全部页面一起从侧栏消失。
- **反向**：C 树里 3 条路径不是侧栏叶子 —— `/warehouse`、`/oper-logs` 是兼容重定向（`/warehouse` 旧正则漏识别，已修 `step0-inventory.mjs`），`/recognition-demo`（`ops:recognition-demo:view`，3 个角色持有）**前端无路由无页面**＝幽灵行。
- **档 A（已做）**：提示语改成实话（一个权限码可被多页共用 + 停用连带影响），并新增「侧栏页」列：C 行显示 `1 页` 或 `N 页共用`（warning tag），非 C 行 `—`。实测渲染 10 条「页共用」，无 console 错误。
- **档 B（待拍板，未动）**：给 19 个共用页补独立 C 行（迁移 + 逐角色授权）。推荐**不做**——授权面从 62 涨到 81、角色编辑弹窗变长，而收益只是「能单独隐藏某一页」，运营侧目前没有这个诉求；如要做，必须先定「谁该单独可停」。

## 待拍板 / 待办（未值守期间只记录不动）

1. `/recognition-demo` 幽灵 C 行：删除 or 置 INACTIVE？我倾向 **INACTIVE + 备注留档**（3 个角色仍持有，删行会连带 `ops_role_permission` 级联，取证面变差）。
2. 权限矩阵专项（批 2）：`/profile` 是唯一无 perm 码的侧栏页（登录即可见），需确认「个人中心」是否应受 `ops:operator:self` 类码约束。

## 门禁/脚本顺带修复

- `docs/module-audit/step0-inventory.mjs`：兼容重定向识别支持 `{ path, name, redirect: (to) => … }` 形态（`/warehouse` 此前被误判为「路由有、菜单未登记」的疑似死页）。
