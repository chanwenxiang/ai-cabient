# 定时任务管理模块

## 一、功能

在运营后台「系统 → 定时任务」页面统一管理全部写型定时任务：

- **查看**：任务名称、标识、分组、调度说明、启停状态、最近执行时间/结果/耗时；
- **启停**：切换开关即生效（任务每次执行前实时读取，无需重启）；
- **立即执行**：手动触发一次，走与自动调度完全相同的执行入口（含分布式锁与执行记录）；
  XXL 托管任务也可在本页立即执行（本进程强制跑一遍），不必只去调度中心；
- **备注**：每行可编辑备注，写清楚这个任务干什么用的，方便运维理解与交接。

## 二、权限与菜单

| 权限 | 用途 | 对应菜单/按钮 |
|---|---|---|
| `ops:task:list` | 查看定时任务 | 侧边栏「系统 → 定时任务」 |
| `ops:task:edit` | 启停任务 | 页内启停开关 |
| `ops:task:run` | 立即执行 | 页内「立即执行」按钮 |

## 三、执行守卫（集群安全）

所有写型定时任务统一经过 `ScheduledTaskService.tryBegin/finish`：

1. **启停开关**：`scheduled_task.enabled=false` 时跳过；
2. **分布式锁**：`job:<taskKey>`，多实例同一任务只跑一个；
3. **执行记录**：最近时间/结果/耗时；
4. **XXL 让位**：`XXL_JOB_ENABLED=true` 且 taskKey ∈ `XxlJobManagedTasks` 时，内置 `@Scheduled` 让位；
   仅 XXL 线程或运营「立即执行」（`runAllowingBuiltin`）可进入。

## 四、与 XXL-JOB（仓库根目录启动）

日常全栈在 **ai-cabinet 根目录** 起，不要单独 `cd infra`：

```powershell
# 仓库根目录
.\docker-up.ps1
```

会拉起 trade + **XXL-JOB 调度中心**（已写入 `docker-compose.full.yml`）。

| 项 | 值 |
|---|---|
| 控制台 | http://localhost:18090/xxl-job-admin |
| 账号 | admin / 123456 |
| 执行器 AppName | trade-service |
| 资金类任务 | `XxlJobManagedTasks`（对账/分账/未付取消等） |
| 高频巡检 | 仍 Spring（会话/设备离线等） |

本地只跑 IDEA、不起 Docker 全栈时：保持 `XXL_JOB_ENABLED=false`（`application.yml` 默认），资金任务继续走 Spring。

种子任务：`infra/xxl-job/seed_aicabinet_jobs.sql`（调度中心 MySQL 首次初始化自动导入）。

## 五、技术实现

- 表：`scheduled_task`
- 后端：`ScheduledTaskService` / `ScheduledTaskRegistry` / `ScheduledTaskXxlJobHandler` / `ScheduledTaskController`
- 前端：`ScheduledTaskView.vue`（系统 → 定时任务）
- 根启动：`docker-up.ps1` → `infra/docker-compose.full.yml`（含 xxl-job-admin）
