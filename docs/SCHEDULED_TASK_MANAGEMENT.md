# 定时任务管理模块

## 一、功能

在运营后台「系统 → 定时任务」页面统一管理全部写型定时任务：

- **查看**：任务名称、标识、分组、调度说明、启停状态、最近执行时间/结果/耗时；
- **启停**：切换开关即生效（任务每次执行前实时读取，无需重启）；
- **立即执行**：手动触发一次，走与自动调度完全相同的执行入口（含分布式锁与执行记录）。

## 二、权限与菜单

权限点（与侧边栏/按钮一一对应）：

| 权限 | 用途 | 对应菜单/按钮 |
|---|---|---|
| `ops:task:list` | 查看定时任务 | 侧边栏「系统 → 定时任务」 |
| `ops:task:edit` | 启停任务 | 页内启停开关 |
| `ops:task:run` | 立即执行 | 页内「立即执行」按钮 |

数据库权限挂载与侧边栏一致：`ops:task:list` 挂在「系统」导航（`ops:nav:sys`）下，
路径 `/scheduled-tasks` 与前端路由一致；启停/执行按钮挂在查看权限下（与 V132/V142 按钮惯例一致）。
角色授权跟随参数配置：拥有 `ops:config:list` 的角色可查看，拥有 `ops:config:edit` 的角色可启停与执行，admin 全量。

## 三、执行守卫（集群安全）

所有写型定时任务统一经过 `ScheduledTaskService.tryBegin/finish`：

1. **启停开关**：`scheduled_task.enabled=false` 时任务直接跳过；
2. **分布式锁**：Redisson 锁 `job:<taskKey>`，多实例部署时同一任务只在一个实例执行；
3. **执行记录**：每次执行写入最近执行时间、结果（SUCCESS/FAILED）、耗时，独立事务提交（外层异常回滚不影响记录）。

「立即执行」同样受启停开关与锁保护（执行中会返回 SKIPPED）。

## 四、与 XXL-JOB 的关系

- 默认（`XXL_JOB_ENABLED=false`）：内置 Spring 调度 + 分布式锁，集群可直接使用；
- 开启 XXL-JOB（`XXL_JOB_ENABLED=true`）：`device-auto-unlock`、`kpi-snapshot` 两个任务由
  XXL-JOB 接管（页面「立即执行」会提示去调度中心），其余任务仍由内置调度执行；
- 需要将更多任务交给 XXL-JOB 时，在 `DeviceXxlJobHandler` 注册对应 handler，并在
  `ScheduledTaskRegistry` 中把该任务标记为 xxlManaged 即可。

## 五、技术实现

- 表：`scheduled_task`（任务注册表 + 最近执行记录），Flyway V152 初始化并预置 22 个任务；
- 后端：`ScheduledTaskService`（守卫/启停/记录）、`ScheduledTaskRegistry`（任务元数据 + 手动触发入口）、
  `ScheduledTaskController`（`/api/v2/ops/admin/scheduled-tasks`）；
- 前端：`ScheduledTaskView.vue`（系统 → 定时任务）。
