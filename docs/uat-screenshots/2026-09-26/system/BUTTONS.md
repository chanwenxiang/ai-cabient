# 系统附录 B · 按钮清点 · 2026-09-26（复测）

> 工具：Playwright MCP · 账号 `13900000001` · 视口 1366×768  
> 分册：[`SYSTEM_FULL_BROWSER_UAT.md`](../../../uat/SYSTEM_FULL_BROWSER_UAT.md)  
> 规则：写路径一律 **弹窗就绪后取消**（#200）。DevOps/日志无观测栈须诚实空态。  
> 截图：本目录 `sys-*.png`

---

## B.1 运营账号 `/operators`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| OP-01 | 列表 | ✓ | 共 11 条含演示超管；`sys-op-01.png` |
| OP-02 | 新增账号 → 取消 | ✓ | 「新增账号」；`sys-op-02-new.png` |
| OP-03 | 分配角色 → 取消 | ✓ | 「分配角色」；`sys-op-03-role.png` |
| OP-04 | 编辑 → 取消 | ✓ | 「编辑账号」；`sys-op-04-edit.png` |
| OP-05 | 更多 → 货柜范围 → 取消 | ✓ | 「货柜范围」；`sys-op-05-scope.png` |
| OP-06 | 更多菜单 | ✓ | 含商户范围 / 货柜范围 |
| OP-07 | **单页深测 SYS-OP-02** | ✓ | [`OPERATORS_FULL_BROWSER_UAT.md`](../../../uat/OPERATORS_FULL_BROWSER_UAT.md) · `operators/`（筛脱敏/重置密码停用取消/导出模板） |

---

## B.2 角色 `/roles`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| ROLE-01 | 列表 | ✓ | 共 12 条；`sys-role-01.png` |
| ROLE-02 | 新增角色 → 取消 | ✓ | `sys-role-02-new.png` |
| ROLE-03 | **单页深测 SYS-ROLE-02** | ✓ | [`ROLES_FULL_BROWSER_UAT.md`](../../../uat/ROLES_FULL_BROWSER_UAT.md) · `roles/`（筛/权限抽屉停用取消/导出） |

---

## B.3 部门 `/departments`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| DEPT-01 | 列表 | ✓ | 共 6 条；`sys-dept-01.png` |
| DEPT-02 | 新增部门 → 取消 | ✓ | `sys-dept-02-new.png` |
| DEPT-03 | **单页深测 SYS-DEPT-02** | ✓ | [`DEPARTMENTS_FULL_BROWSER_UAT.md`](../../../uat/DEPARTMENTS_FULL_BROWSER_UAT.md) · `departments/`（编辑成员批停取消） |

---

## B.4 审批流 `/approvals`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| APR-01 | 列表 | ✓ | 共 7 条；`sys-apr-01.png` |
| APR-02 | 新增 → 取消 | ✓ | 「新增审批流」 |
| APR-03 | 编辑 → 取消 | ✓ | 「编辑审批流」；`sys-apr-03-edit.png` |
| APR-04 | **单页深测 SYS-APR-02** | ✓ | [`approvals/BUTTONS.md`](../approvals/BUTTONS.md) · 展开/流程图/删除取消 |

---

## B.5 菜单 `/menus`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| MENU-01 | 树/表 | ✓ | 本页约 194 行；`sys-menu-01.png` |
| MENU-02 | 新增 → 取消 | ✓ | 「新增菜单」；`sys-menu-02-new.png` |
| MENU-03 | **单页深测 SYS-MENU-02** | ✓ | [`menus/BUTTONS.md`](../menus/BUTTONS.md) · 范围切/四写取消/导出 |

---

## B.6 字典 `/dicts`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| DICT-01 | 类型 + 项 | ✓ | 多类型；`sys-dict-01.png` |
| DICT-02 | 新增类型 → 取消 | ✓ | 「新增字典类型」 |
| DICT-03 | 新增字典项 → 取消 | ✓ | 先选类型；`sys-dict-03-item.png` |
| DICT-04 | **单页深测 SYS-DICT-02** | ✓ | [`dicts/BUTTONS.md`](../dicts/BUTTONS.md) · 六写取消/导出模板 |

---

## B.7 参数配置 `/system-configs`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| CFG-01 | 列表 + 品牌区 | ✓ | 共 76；上传标志/保存品牌可见；`sys-cfg-01.png` |
| CFG-02 | 新增 → 取消 | ✓ | 「新增参数」；`sys-cfg-02-new.png` |
| CFG-03 | 查询/导出/导入/刷新 | ✓ 可见 | |
| CFG-04 | **单页深测 SYS-CFG-02** | ✓ | [`system-configs/BUTTONS.md`](../system-configs/BUTTONS.md) · 三写取消/历史/导出 |

---

## B.8 告警规则 `/alert-rules`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| ALERT-01 | 列表 | ✓ | 共 25 条；`sys-alert-01.png` |
| ALERT-02 | 编辑 → 取消 | ✓ | 「编辑告警规则」；`sys-alert-02-edit.png` |
| ALERT-03 | 新增 | ✓ 诚实 | toast「白名单键均已存在…」无弹窗；`sys-alert-03-add.png` |
| ALERT-04 | **单页深测 SYS-ALERT-02** | ✓ | [`alert-rules/BUTTONS.md`](../alert-rules/BUTTONS.md) · 编辑删取消/测试SKIP |

---

## B.9 定时任务 `/scheduled-tasks`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| TASK-01 | 列表 | ✓ | 共 32 条；`sys-task-01.png` |
| TASK-02 | 立即执行 → 取消 | ✓ | 「确认立即执行「补偿任务处理」？」；`sys-task-02-run.png` |
| TASK-03 | **单页深测 SYS-TASK-02** | ✓ | [`scheduled-tasks/BUTTONS.md`](../scheduled-tasks/BUTTONS.md) · 新建编辑批取消/开关未拨 |

---

## B.10 组织与点位 `/org-sites`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| ORG-01 | 组织树 | ✓ | 有树；`sys-org-01.png` |
| ORG-02 | 新增顶级组织 → 取消 | ✓ | 「新增组织」；`sys-org-02-new.png` |
| ORG-03 | 编辑 → 取消 | ✓ | 「编辑组织」；`sys-org-03-edit.png` |
| ORG-04 | **单页深测 SYS-ORG-02** | ✓ | [`org-sites/BUTTONS.md`](../org-sites/BUTTONS.md) · 三Tab/写取消 |

---

## B.11 通知公告 `/announcements`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| ANN-01 | 列表 | ✓ | 共 1 条已发布；`sys-ann-01.png` |
| ANN-02 | 发布公告 → 取消 | ✓ | 「发布公告」；`sys-ann-02-new.png` |
| ANN-03 | **单页深测 SYS-ANN-02** | ✓ | [`announcements/BUTTONS.md`](../announcements/BUTTONS.md) · 发布编辑归档取消/导出 |

---

## B.12 审计日志 `/audit`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| AUD-01 | 列表 | ✓ | 共 225 条；`sys-aud-01.png` |
| AUD-02 | 查询/重置/导出/刷新 | ✓ | |
| AUD-03 | **单页深测 SYS-AUD-02** | ✓ | [`audit/BUTTONS.md`](../audit/BUTTONS.md) · 共244/仅看我的/导出 |

---

## B.13 DevOps `/devops` · 日志中心 `/observability`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| DEVOPS-01 | 页可开 | ✓ | PromQL 快捷按钮可见；`sys-devops-01.png` |
| OBS-01 | 页可开 + 诚实空态 | ✓ | 「Grafana 未启动，无法嵌入看板」；`sys-obs-01.png` |
| OBS-02 | iframe 实嵌 | SKIP | 本轮无 Grafana/Loki 栈 |

细则另见 [`DEVOPS_BUTTONS.md`](./DEVOPS_BUTTONS.md)。

---

## Phase 7 Done（复测）

- [x] 侧栏十二页 + DevOps/日志附录 B 勾完  
- [x] 写路径均取消未提交  
- [x] 日志见 [`FINDINGS.md`](./FINDINGS.md)
