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
5. **ArchUnit**：`TradeArchitectureTest.scheduledMustCallTryBegin` 禁止新增裸 `@Scheduled`；
   唯一豁免 `CacheConfig#purgeExpiredCache`（本机资源回收，见 §四.3）。

多副本部署见 [HIGH_AVAILABILITY.md](HIGH_AVAILABILITY.md)。

## 四、与 XXL-JOB

### 1. 托管范围：全部业务定时任务（30 个）

> 2026-09-16 起由「资金类优先」改为**全量托管**。原因是生产为多实例部署：Spring `@Scheduled`
> 在集群里靠 Redis 锁只能保证「只有一台真跑」，代价是**每台每周期都空转抢一次锁**（10 个秒级任务
> × N 实例）。交给调度中心单选派发后这层浪费消失，且失败重试/路由/触发历史都在调度台可见。

| 项 | 值 |
|---|---|
| 控制台 | http://localhost:18090/ （**3.x 无 `/xxl-job-admin` 前缀**） |
| 账号 | admin / 123456 |
| 执行器 AppName | trade-service |
| 托管清单 | `XxlJobManagedTasks.KEYS` —— **30 个业务任务**，覆盖 §五 表格中的全部业务项 |
| 路由策略 | `FAILOVER`（多实例下单选一台；该台不可用时自动转下一台） |
| 阻塞策略 | `SERIAL_EXECUTION` |
| 执行器开关 | `XXL_JOB_ENABLED`（`application.yml` 默认 `false`） |

本地只跑 IDEA、不起 Docker 全栈时：保持 `XXL_JOB_ENABLED=false`，所有任务继续走 Spring 常驻调度。

### 2. 刻意排除的 2 个（不是遗漏，托管后会失效）

| 任务 | 为什么不能托管 |
|---|---|
| `scheduled-task-stale-monitor`（超期看护） | 它是「检测 XXL 是否失效」的装置。一旦也交给 XXL，调度中心故障时它会与被看护任务**同时**停跑，唯一能报警的东西没了。`scripts/check-xxl-job-wiring.mjs` 硬性拦截 |
| `cache-purge`（本机缓存清理） | 清理的是本进程 `ConcurrentHashMap`，必须**每个实例各自执行**；且本机资源回收不应依赖外部调度中心是否可用 |

### 3. `cache-purge` 的特殊性（曾是真缺陷）

`CacheConfig.purgeExpiredCache` 早先借 `tryBegin("cache-purge")` 的全局锁，在多实例下**只有一台实例
被清**，其余实例的内存条目**永不回收** —— 锁在这里不是「防重复」，而是**造成漏清理**。

现已改为**每实例自清、不借锁**，并因此从 `check-scheduled-task-seed.mjs` 的 `LOCK_ONLY_TASKS`
豁免名单**撤出**（名单当前为空）：若将来有人给它加回 `tryBegin`，规则四会立刻拦下。
它不进 `scheduled_task` 台账、运营台不可见、不参与超期看护；执行情况由 Micrometer
`tasks_scheduled_execution_seconds_count` 覆盖。

### 4. 启动与种子

日常全栈在 **ai-cabinet 根目录** 起，不要单独 `cd infra`：

```powershell
# 仓库根目录
.\docker-up.ps1
```

> ⚠️ `docker-up.ps1` 末尾是 `exit $LASTEXITCODE`，在**你自己的交互终端**里跑没问题；
> 但被脚本/CI 以非交互方式调用时会**终止整个宿主进程**，日志来不及 flush（表现为 0 字节日志）。
> 自动化场景请直接用 `cd infra && docker compose --env-file .env -f docker-compose.full.yml -f docker-compose.win-ports.yml up -d <服务名…>`。

种子任务：`infra/xxl-job/seed_aicabinet_jobs.sql`（调度中心 MySQL 首次初始化自动导入）。

> **新增排期后必须重跑 seed（幂等）**，否则调度中心里没有这条 job，任务会「让位了但没人接」：
> ```bash
> docker exec -i <xxl-mysql> mysql -uroot -pxxljob xxl_job < infra/xxl-job/seed_aicabinet_jobs.sql
> ```
> seed 的 `ON DUPLICATE KEY UPDATE` 已覆盖 `schedule_conf` / `executor_handler` / `executor_param` /
> 路由与阻塞策略 / 重试次数，重跑即对齐。
>
> ⚠️ **数据卷一旦存在，initdb 就不会再执行** —— seed 只在 `xxl-job-mysql` 数据卷**首次初始化**时
> 自动导入（挂到 `/docker-entrypoint-initdb.d/`）。所以上面的手工重跑**不是可选项**，
> 而且**没有任何门禁覆盖「代码 ↔ 运行库」这条路径**（`scripts/` 下的两个 `check-*` 都只读源码与迁移）。

#### 落地必须「重跑 seed + 重建镜像」成对执行

托管后 `tryBegin` 对清单内任务**无条件让位**，所以只做一半会得到比不落地更糟的结果：

> 新代码认 30 个 key → 内置 `@Scheduled` 让位；调度中心若只有旧的 11 条 → **19 个任务无人派发**
> ⇒ **静默停摆，调度台和运营台都看不到异常。**

顺序：**先重跑 seed，再重建/重启应用**（反了会报 handler not found，至少是响的）。
落地后按下表逐项验证：

| # | 验证 | 命令/判据 |
|---|---|---|
| 1 | 调度中心业务 job 数 = 30 | `SELECT COUNT(*) FROM xxl_job_info WHERE id BETWEEN 101 AND 131;` |
| 2 | 已有 job 未被改动 | `101–111` 的 `id`/`executor_handler`/`schedule_conf` 与改动前**逐条相同**（防重复插入 → 双触发） |
| 3 | 全部启用 | `SELECT trigger_status, COUNT(*) FROM xxl_job_info GROUP BY trigger_status;` → 业务侧 30 条均 `=1`（`0` 只应是镜像自带 demo） |
| 4 | 执行器已注册 | `SELECT * FROM xxl_job_registry;` 有 `EXECUTOR/trade-service` 且 `update_time` 是近期心跳 |
| 5 | 调度器能解析每条 cron | admin 日志**没有** `refreshNextValidTime error for job: jobId=…` |
| 6 | 端到端真的跑 | 手动触发 1~2 条 → `xxl_job_log.handle_code=200`，且业务表 `scheduled_task.last_run_at` 推进 |

> **为什么单列第 5 条**：cron 语法错误**不会**让任务报错，只会让 XXL 的 `CronExpression`
> 解析失败（`storeExpressionVals` 抛错）→ 该 job 算不出下次触发时间 → **永远不触发**，
> 并且会被调度器**自动停用**（`trigger_status` 被置 0）。这与「没接线」的现象完全一样。
> 门禁 `check-xxl-job-wiring` 规则 3.10 现在会静态拦截 6 段以外的形态与「日/周同时限定」。

### 5. 新增一个托管任务的完整清单（5 处，缺一处门禁即红）

| # | 位置 | 改什么 |
|---|---|---|
| 1 | `XxlJobManagedTasks.KEYS` | 加 taskKey |
| 2 | `ScheduledTaskXxlJobHandler` | 加具名 `@XxlJob("xxxJob")` → `runKey("<taskKey>")` |
| 3 | `ScheduleZones.XXL_CRON_BY_TASK` | 加 Quartz **6 段** cron（`秒 分 时 日 月 周`；**「日」与「周」只能有一个写 `?`**，见下） |
| 4 | `ScheduleZones.MAX_SILENCE_BY_TASK` | 加超期阈值（周期 + 宽限；**月任务按 32 天**） |
| 5 | `infra/xxl-job/seed_aicabinet_jobs.sql` | 加排期行（`executor_handler` 指向 #2 的具名 handler） |

若该任务此前未在运营台登记，还要在 `db/migration` 补 `scheduled_task` 行、并在
`ScheduledTaskRegistry` 注册（否则「立即执行」404）。以上全部由门禁静态校验。

### 6. 条件装配的任务：关闭时要「两边一起关」

`ops-fee-bill-monthly` 是唯一带 `@ConditionalOnProperty` 的托管任务
（`aicabinet.fee-bill.auto-generate-enabled`，默认 `true`）。关掉它时：

| 层 | 行为 | 要做的事 |
|---|---|---|
| 执行器 | bean 不装配 → 不注册进 `ScheduledTaskRegistry`，并登记进 `conditionallyAbsent` | —— |
| 看护 | **豁免超期告警**（识别为「有意不跑」而非停摆） | —— |
| 调度中心 | job 119 **仍在、仍按 `0 30 1 1 * ?` 每月派发** → 执行器回 `handleFail("任务未注册")` | **同时在调度台停用 job 119**（或接受每月一条失败日志） |

> 为什么看护能豁免而调度中心必须人工停：cron 与 job 行是**静态种子**，运行时的 Spring 条件装配
> 改变不了它们；而看护读的是运行时的注册表，能区分「有意关闭」与「漏注册」——
> 豁免名单之外的 key 若查不到 descriptor，会按 `NOT_REGISTERED`（执行器未注册）**照报**，不会被一起静音。

## 五、超期看护：托管任务停跑的兜底

XXL 让位（§三 第 4 条）只判「开关开 + key 在清单」，**不校验调度中心是否可达、执行器是否已注册**。
所以一次配置漂移（例如 `XXL_JOB_ADMIN_ADDRESSES` 多带 `/xxl-job-admin` 前缀）就会让两边同时失效：
任务**永久停跑**，`last_run_at` 只是停止推进，没有报错、没有告警，测试也覆盖不到
（实测停跑约 19 小时无人发现，详见 `three-end-full-audit-2026-09-15.md` §18/§19）。

`ScheduledTaskStaleMonitor` 补上这一层：

| 项 | 说明 |
|---|---|
| 判据 | **只看业务表 `scheduled_task.last_run_at`**，与 `ScheduleZones.MAX_SILENCE_BY_TASK` 的阈值比较 |
| 范围 | `XxlJobManagedTasks.KEYS` —— **全量托管后即全部 30 个业务任务** |
| 周期 | 每 5 分钟（`aicabinet.scheduled-task.stale-monitor-interval-ms`） |
| 超期判定 | `MISSING_ROW`（运营台无登记行）/ `NEVER_RUN`（有行但从未执行）/ `OVERDUE`（静默超过阈值） |
| 告警出口 | ① 运营「异常列表」写入 `SCHEDULED_TASK_STALE`（CRITICAL，恢复后自动关闭）；② 钉钉/企微/通用 Webhook；③ Prometheus 指标 `aicabinet_scheduled_task_silence_seconds{task}`、`aicabinet_scheduled_task_stale_count` |
| 自身 | 注册为系统任务「定时任务超期看护」，可在本页启停/立即执行；**刻意不列入托管清单**，否则它会跟着一起停跑 |
| 重复告警 | 同一批超期任务 6 小时内只外发一次（`stale-monitor-realert-minutes`），异常列表侧由去重键天然收敛 |
| 停机豁免 | 判据起点取 `max(last_run_at, 进程启动时刻)` —— 进程没活着的那段时间任务不可能执行，见下节 |
| 条件装配豁免 | 任务 bean 未装配（如 `aicabinet.fee-bill.auto-generate-enabled=false`）时不进 `ScheduledTaskRegistry`，看护**跳过**该 key 而不报超期 —— 那是「有意不跑」，不是停摆 |

> **不要用调度台判断任务是否在跑**：`xxl_job_info.trigger_status=1` 与每分钟刷新的
> `trigger_last_time` 在故障期间看起来完全正常。排查链：
> `xxl_job_log`(trigger_code/handle_code) → `xxl_job_registry` / `xxl_job_group.address_list`
> → 执行器日志 `registry error` → admin 日志 `No mapping for POST …`。

> **登记行缺失是另一种隐形态**：`finish()` 只更新**已存在**的行，行不存在时执行记录被静默丢弃，
> 任务照跑但运营台看不见、不能启停、不能手动触发。`scripts/check-scheduled-task-seed.mjs`
> 已把「注册表 ↔ 种子行」钉成静态契约（曾漏 7 个任务）。

### 停机不算超期

看护最初只算 `now - last_run_at`，**不看进程是否活着**。这在天天关机的开发机上必然误报：
开机后高频任务（`session-opening-expire`/`ops-exception-scanner`/`compensation-process` 阈值 5 分钟，
`unpaid-cancel`/`profit-sharing-retry` 45 分钟）会立刻被判超期。

修正：判据起点取 `max(last_run_at, 进程启动时刻)`。豁免的是**停机期**，不是**任务** ——
进程恢复后仍超过阈值的静默照报（例如已运行 30 分钟、阈值 20 分钟，仍没有任何执行 → 真停跑）。
`NEVER_RUN` 同样给一个阈值宽限期，否则每次重启都会把所有任务报一遍。

### 能力边界（必须知道）

看护与被看护的任务**同进程**，因此它看不到「本进程整体消失」：

| 场景 | 看护表现 | 正解 |
|---|---|---|
| 进程活着、任务被让位后不触发（上一轮真实故障） | ✅ 能报 | 本看护 |
| 进程死掉 / 宿主关机 | ⚠️ 等进程回来才报（迟到，仍有价值） | Prometheus `ServiceDown`（`up == 0`，已有规则） |
| 宿主休眠 / 待机 | ⚠️ 会被判超期（挂起期时钟推进但进程未跑） | 同上；开发机可临时关 `stale-monitor-enabled` |

即：**它治「任务没跑」，不治「进程没了」**。后者必须由进程外的探针兜底。

### 只看「跑没跑」，不看「跑成没跑成」——已由告警规则补上

看护判据是 `last_run_at`，**不看 `last_result`**，所以「一直在跑但每次都失败」的任务它看不见：
实测 `growth-log-archive` 因 `Instant.minus(n, ChronoUnit.MONTHS)` 抛
`UnsupportedTemporalTypeException`，**上线以来 100% 失败**，而 `scheduled_task.last_result='FAILED'`
没有任何人消费它。

补法不在看护里，而是直接用 Micrometer 已打点的指标（覆盖面比托管清单更广，含未登记的任务）：

```promql
sum by (code_namespace, code_function, error) (
  increase(tasks_scheduled_execution_seconds_count{outcome="ERROR"}[1h])
) > 0        # 告警 ScheduledTaskExecutionFailed
```

### 覆盖范围：运营台 31 行 = 30 托管 + 1 看护

运营台当前 **31 行登记**（已逐个核对触发源，**无残留行**）：

| 类别 | 数量 | 触发方式 | 超期看护 |
|---|---|---|---|
| XXL 托管 | 30 | XXL-JOB 派发（`XxlJobManagedTasks.KEYS`） | ✅ 逐任务阈值 |
| Spring 常驻 | 1 | 内置 `@Scheduled`（`scheduled-task-stale-monitor`） | ❌ 刻意排除（见 §四.2） |

另有 **1 个不在台账内的执行点**：`cache-purge`（本机缓存清理，每实例自清、不借锁、不进运营台）。

> 全量托管的**直接收益**：以前「Spring 常驻」那 20 个任务同样会因 cron 写错、时区漂移、bean 未装配
> 而停跑，且**没有任何机制会发现**（实测 `sla-snapshot`（每日 00:05）静默 15 天无人察觉）。
> 现在它们全部进入逐任务阈值的看护范围。

> **月任务阈值必须按 32 天**：`ops-fee-bill-monthly` 的 cron 是每月 1 日 01:30，若按日任务的 26 小时
> 设阈值，会**每月被误报一次超期**。这类低频任务的检测天然滞后（真停跑要等 32 天才报），是判据的固有属性。

> 核对键时**不能只 grep 字面量**：多数任务写成 `private static final String TASK_KEY = "xxx"` 再 `tryBegin(TASK_KEY, …)`，
> 字面量检索会把 `ops-fee-bill-monthly` 这类任务误判成「无 runner」。门禁已按常量解析。

### 开发机 与 服务器 的判据差异

| 现象 | 开发机（本机） | 服务器 | 对看护的要求 |
|---|---|---|---|
| 夜间关机 | 常见 | 不会 | 每日 cron 类任务在本机长期不跑属**环境特性**，别当缺陷修 |
| 发版/重启 | 有 | 有（主要场景） | 停机豁免必须存在，否则每次发版后首轮巡检就误报 |
| 宿主休眠/待机 | 有 | 无 | 挂起期时钟推进但进程未跑 → 会被判超期（已知边界） |
| 进程整体消失 | 有 | 有（更需重视） | 同进程的看护看不到，靠 Prometheus `ServiceDown`（`up == 0`） |

**停机豁免的代价**：进程重启后，每个任务的判定都从「进程启动时刻」重新起算，
因此发版后最长要等**该任务自己的阈值**才会重新报警（高频任务 5~45 分钟，每日任务 8~26 小时，月任务 32 天）。
这不是漏检——重启瞬间本来就无法区分「马上要跑」和「已经坏了」，阈值就是这段分辨期。

### 托管方式切换的一个副作用（好的方向）

部分任务的 Spring 侧用的是 `fixedRate`（从**进程启动时刻**起算，没有固定墙钟点），而运营台
`schedule_desc` 写的是墙钟描述（如「每日 03:00」）。托管后由调度中心的**墙钟 cron** 驱动，
两边就此对齐 —— 顺带消除了「展示频率 ≠ 实际调度」的偏差。

## 六、技术实现

- 表：`scheduled_task`
- 后端：`ScheduledTaskService` / `ScheduledTaskRegistry` / `ScheduledTaskXxlJobHandler` / `ScheduledTaskController` / `ScheduledTaskStaleMonitor` / `XxlJobManagedTasks` / `ScheduleZones`
- 前端：`ScheduledTaskView.vue`（系统 → 定时任务）
- 根启动：`docker-up.ps1` → `infra/docker-compose.full.yml`（含 xxl-job-admin）
- 防回归门禁：
  - `scripts/check-xxl-job-wiring.mjs` —— 托管清单 ↔ 具名 handler ↔ 种子行 ↔
    **cron 两处逐条一致** ↔ 看护阈值 ↔ **托管任务已在注册表注册** ↔ 看护指标必须有告警规则消费
  - `scripts/check-scheduled-task-seed.mjs` —— 注册表 ↔ 登记行 ↔ tryBegin 调用点（无豁免名单）
- 告警规则：`infra/prometheus/alert_rules.yml` → `ScheduledTaskStale`（停跑）、
  `ScheduledTaskExecutionFailed`（执行失败）
