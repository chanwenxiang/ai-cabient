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

执行日志：`docs/uat-screenshots/2026-09-26/system/FINDINGS.md`  
按钮清点：`docs/uat-screenshots/2026-09-26/system/BUTTONS.md`  
DevOps/日志：`docs/uat-screenshots/2026-09-26/system/DEVOPS_BUTTONS.md`  
收口总览：`docs/uat-screenshots/2026-09-26/UAT_CLOSEOUT.md`

## Done

- [x] 关键 ID 均有 PASS/SKIP + 证据  
- [x] 写路径确认后取消  
- [x] **2026-09-26 复测**：附录 B + `sys-*.png`；Grafana 未启诚实空  
