# 运营后台 ·「系统」全量浏览器 UAT（Phase 7）

> **地位**：第七册（侧栏「系统」）。工具铁律同 Overview §1。  
> **版本**：1.1 · 2026-09-26（十四入口复测含 DevOps/日志）  
> **截图**：`docs/uat-screenshots/2026-09-26/system/`  
> **说明**：`/devops`、`/observability` 在无 Grafana/Prometheus/Sonar 时须诚实空态；按钮级见 `system/DEVOPS_BUTTONS.md`。

| 序 | 路径 | 标题 |
|----|------|------|
| 1 | `/operators` | 运营账号 |
| 2 | `/roles` | 角色管理 |
| 3 | `/departments` | 部门管理 |
| 4 | `/approvals` | 审批流配置 |
| 5 | `/menus` | 菜单管理 |
| 6 | `/dicts` | 字典管理 |
| 7 | `/system-configs` | 参数配置 |
| 8 | `/alert-rules` | 告警规则 |
| 9 | `/scheduled-tasks` | 定时任务 |
| 10 | `/org-sites` | 组织与点位 |
| 11 | `/announcements` | 通知公告 |
| 12 | `/audit` | 审计日志 |
| 13 | `/devops` | DevOps 中心 |
| 14 | `/observability` | 日志中心 |

| 字段 | 值 |
|------|-----|
| 结案 | **Phase 7 Done（复测）**；[`system/BUTTONS.md`](../uat-screenshots/2026-09-26/system/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/system/FINDINGS.md) |

ID：`SYS-*`

| ID | 期望 |
|----|------|
| SYS-OP-01 | 运营账号列表可读；写路径可取消 |
| SYS-OP-02 | 运营账号单页深测：见 [`OPERATORS_FULL_BROWSER_UAT.md`](./OPERATORS_FULL_BROWSER_UAT.md)（共11/筛脱敏/六写取消/导出模板） |
| SYS-ROLE-01 | 角色列表可读；新增可取消 |
| SYS-ROLE-02 | 角色管理单页深测：见 [`ROLES_FULL_BROWSER_UAT.md`](./ROLES_FULL_BROWSER_UAT.md)（共12/筛/编辑权限抽屉停用取消/导出） |
| SYS-DEPT-01 | 部门列表可读 |
| SYS-DEPT-02 | 部门管理单页深测：见 [`DEPARTMENTS_FULL_BROWSER_UAT.md`](./DEPARTMENTS_FULL_BROWSER_UAT.md)（共6/新建编辑成员批停取消） |
| SYS-APR-01 | 审批流列表可读；写路径可取消 |
| SYS-APR-02 | 审批流配置单页深测：见 [`APPROVALS_FULL_BROWSER_UAT.md`](./APPROVALS_FULL_BROWSER_UAT.md)（共7/展开/新建编辑流程图删除取消） |
| SYS-MENU-01 | 菜单树可读；新增可取消 |
| SYS-MENU-02 | 菜单管理单页深测：见 [`MENUS_FULL_BROWSER_UAT.md`](./MENUS_FULL_BROWSER_UAT.md)（运营194/商户39/四写取消/导出） |
| SYS-DICT-01 | 字典类型+项可读；双新增可取消 |
| SYS-DICT-02 | 字典管理单页深测：见 [`DICTS_FULL_BROWSER_UAT.md`](./DICTS_FULL_BROWSER_UAT.md)（类型89/六写取消/导出模板/导入SKIP） |
| SYS-CFG-01 | 参数列表可读；写路径可取消 |
| SYS-CFG-02 | 参数配置单页深测：见 [`SYSTEM_CONFIGS_FULL_BROWSER_UAT.md`](./SYSTEM_CONFIGS_FULL_BROWSER_UAT.md)（共76/品牌未硬写/三写取消/历史/导出） |
| SYS-ALERT-01 | 告警规则列表可读；编辑可取消；新增诚实 |
| SYS-ALERT-02 | 告警规则单页深测：见 [`ALERT_RULES_FULL_BROWSER_UAT.md`](./ALERT_RULES_FULL_BROWSER_UAT.md)（共25/新增toast/编辑删取消/测试SKIP） |
| SYS-TASK-01 | 定时任务列表可读；立即执行可取消 |
| SYS-TASK-02 | 定时任务单页深测：见 [`SCHEDULED_TASKS_FULL_BROWSER_UAT.md`](./SCHEDULED_TASKS_FULL_BROWSER_UAT.md)（共32/新建编辑执行批取消/开关未拨） |
| SYS-ORG-01 | 组织树可读；新增/编辑可取消 |
| SYS-ORG-02 | 组织与点位单页深测：见 [`ORG_SITES_FULL_BROWSER_UAT.md`](./ORG_SITES_FULL_BROWSER_UAT.md)（三Tab/组织写取消/合同空/账单出账取消） |
| SYS-ANN-01 | 公告列表可读；发布可取消 |
| SYS-ANN-02 | 通知公告单页深测：见 [`ANNOUNCEMENTS_FULL_BROWSER_UAT.md`](./ANNOUNCEMENTS_FULL_BROWSER_UAT.md)（共1/发布编辑归档取消/导出） |
| SYS-AUD-01 | 审计日志列表可读 |
| SYS-AUD-02 | 审计日志单页深测：见 [`AUDIT_FULL_BROWSER_UAT.md`](./AUDIT_FULL_BROWSER_UAT.md)（共244/筛/仅看我的/导出） |
| SYS-DEVOPS-01 | DevOps 入口可读；无观测栈诚实空（见 `system/DEVOPS_BUTTONS.md`） |
| SYS-DEVOPS-02 | DevOps 单页深测：见 [`DEVOPS_FULL_BROWSER_UAT.md`](./DEVOPS_FULL_BROWSER_UAT.md)（四卡/PromQL stub/Grafana 空/Sonar 禁用） |
| SYS-OBS-01 | 日志中心可读；五/六主题可切 + Grafana 空（见 `system/DEVOPS_BUTTONS.md`） |
| SYS-OBS-02 | 日志中心单页深测：见 [`OBSERVABILITY_FULL_BROWSER_UAT.md`](./OBSERVABILITY_FULL_BROWSER_UAT.md)（六签/诚实空；hint F1 已修） |
| SYS-DEVOPS-03 | 观测栈在线：Grafana/Prometheus「在线」+ iframe 实嵌运营概览（需 Grafana 会话；见 `devops/` `dv-online-*`） |
| SYS-OBS-03 | 观测栈在线：六 iframe 实嵌日志流/概览（告警消失；见 `observability/` `ob-online-*`） |

执行日志：`docs/uat-screenshots/2026-09-26/system/FINDINGS.md`  
按钮清点：`docs/uat-screenshots/2026-09-26/system/BUTTONS.md`  
DevOps/日志：`docs/uat-screenshots/2026-09-26/system/DEVOPS_BUTTONS.md`  
收口总览：`docs/uat-screenshots/2026-09-26/UAT_CLOSEOUT.md`

## Done

- [x] 关键 ID 均有 PASS/SKIP + 证据  
- [x] 写路径确认后取消  
- [x] **2026-09-26 复测**：附录 B + `sys-*.png`；Grafana 未启诚实空  
- [x] **2026-09-27 单页深测**：十四入口均有 `*_FULL_BROWSER_UAT.md`（含 DevOps/日志）；写路径软取消；OBS hint F1 已修  
- [x] **2026-09-27 观测栈在线**：SYS-DEVOPS-03 / SYS-OBS-03；DevOps hint F2（需登录一次）已修  
