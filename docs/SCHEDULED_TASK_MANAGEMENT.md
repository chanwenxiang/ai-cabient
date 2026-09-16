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
2. **分布式锁**：`job:<taskKey>`（Redis，**等同 ShedLock**），多实例同一任务只跑一个；
3. **执行记录**：最近时间/结果/耗时；
4. **XXL 让位**：`XXL_JOB_ENABLED=true` 且 taskKey ∈ `XxlJobManagedTasks` 时，内置 `@Scheduled` 让位；
   仅 XXL 线程或运营「立即执行」（`runAllowingBuiltin`）可进入。
5. **ArchUnit**：`TradeArchitectureTest.scheduledMustCallTryBegin` 禁止新增裸 `@Scheduled`。

多副本部署见 [HIGH_AVAILABILITY.md](HIGH_AVAILABILITY.md)。

## 四、与 XXL-JOB（仓库根目录启动）

日常全栈在 **ai-cabinet 根目录** 起，不要单独 `cd infra`：

```powershell
# 仓库根目录
.\docker-up.ps1
```

> ⚠️ `docker-up.ps1` 末尾是 `exit $LASTEXITCODE`，在**你自己的交互终端**里跑没问题；
> 但被脚本/CI 以非交互方式调用时会**终止整个宿主进程**，日志来不及 flush（表现为 0 字节日志）。
> 自动化场景请直接用 `cd infra && docker compose --env-file .env -f docker-compose.full.yml -f docker-compose.win-ports.yml up -d <服务名…>`。

会拉起 trade + **XXL-JOB 调度中心**（已写入 `docker-compose.full.yml`）。

| 项 | 值 |
|---|---|
| 控制台 | http://localhost:18090/ （**3.x 无 `/xxl-job-admin` 前缀**） |
| 账号 | admin / 123456 |
| 执行器 AppName | trade-service |
| 资金类任务 | `XxlJobManagedTasks`（对账/分账/未付取消等） |
| 高频巡检 | 仍 Spring（会话/设备离线等） |

本地只跑 IDEA、不起 Docker 全栈时：保持 `XXL_JOB_ENABLED=false`（`application.yml` 默认），资金任务继续走 Spring。

种子任务：`infra/xxl-job/seed_aicabinet_jobs.sql`（调度中心 MySQL 首次初始化自动导入）。

## 五、超期看护：托管任务停跑的兜底

XXL 让位（上一节第 4 条）只判「开关开 + key 在清单」，**不校验调度中心是否可达、执行器是否已注册**。
所以一次配置漂移（例如 `XXL_JOB_ADMIN_ADDRESSES` 多带 `/xxl-job-admin` 前缀）就会让两边同时失效：
任务**永久停跑**，`last_run_at` 只是停止推进，没有报错、没有告警，测试也覆盖不到
（实测停跑约 19 小时无人发现，详见 `three-end-full-audit-2026-09-15.md` §18/§19）。

`ScheduledTaskStaleMonitor` 补上这一层：

| 项 | 说明 |
|---|---|
| 判据 | **只看业务表 `scheduled_task.last_run_at`**，与 `ScheduleZones.MAX_SILENCE_BY_TASK` 的阈值比较 |
| 范围 | `XxlJobManagedTasks.KEYS`（11 个托管任务）；非托管任务由 Spring 常驻执行，不在看护范围 |
| 周期 | 每 5 分钟（`aicabinet.scheduled-task.stale-monitor-interval-ms`） |
| 超期判定 | `MISSING_ROW`（运营台无登记行）/ `NEVER_RUN`（有行但从未执行）/ `OVERDUE`（静默超过阈值） |
| 告警出口 | ① 运营「异常列表」写入 `SCHEDULED_TASK_STALE`（CRITICAL，恢复后自动关闭）；② 钉钉/企微/通用 Webhook；③ Prometheus 指标 `aicabinet_scheduled_task_silence_seconds{task}`、`aicabinet_scheduled_task_stale_count` |
| 自身 | 注册为系统任务「定时任务超期看护」，可在本页启停/立即执行；**刻意不列入托管清单**，否则它会跟着一起停跑 |
| 重复告警 | 同一批超期任务 6 小时内只外发一次（`stale-monitor-realart-minutes`），异常列表侧由去重键天然收敛 |

> **不要用调度台判断任务是否在跑**：`xxl_job_info.trigger_status=1` 与每分钟刷新的
> `trigger_last_time` 在故障期间看起来完全正常。排查链：
> `xxl_job_log`(trigger_code/handle_code) → `xxl_job_registry` / `xxl_job_group.address_list`
> → 执行器日志 `registry error` → admin 日志 `No mapping for POST …`。

> **登记行缺失是另一种隐形态**：`finish()` 只更新**已存在**的行，行不存在时执行记录被静默丢弃，
> 任务照跑但运营台看不见、不能启停、不能手动触发。`scripts/check-scheduled-task-seed.mjs`
> 已把「注册表 ↔ 种子行」钉成静态契约（曾漏 7 个任务）。

## 六、技术实现

- 表：`scheduled_task`
- 后端：`ScheduledTaskService` / `ScheduledTaskRegistry` / `ScheduledTaskXxlJobHandler` / `ScheduledTaskController` / `ScheduledTaskStaleMonitor`
- 前端：`ScheduledTaskView.vue`（系统 → 定时任务）
- 根启动：`docker-up.ps1` → `infra/docker-compose.full.yml`（含 xxl-job-admin）
- 防回归门禁：`scripts/check-xxl-job-wiring.mjs`（托管清单 ↔ handler ↔ 种子 ↔ 看护阈值）、
  `scripts/check-scheduled-task-seed.mjs`（注册表 ↔ 登记行）
