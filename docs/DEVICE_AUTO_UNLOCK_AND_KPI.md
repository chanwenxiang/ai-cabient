# 设备稳定在线自动解锁 + 设备可用性 KPI

## 一、背景

设备离线超过配置分钟数（`device.offline.auto_sales_lock_minutes`，默认 10）后系统自动锁机停售。
行业惯例是“恢复后人工确认再起售”，因此**自动解锁默认关闭**；需要更优体验时可开启
“稳定在线自动解锁”：设备恢复在线并稳定运行 N 分钟，且无未结算会话、无未完结维修工单时自动解除销售锁。

## 二、系统参数（运营后台「参数配置」页可见）

| 参数 Key | 默认值 | 说明 |
|---|---|---|
| `device.offline.auto_unlock_enabled` | `false` | 稳定在线自动解锁总开关（默认关闭） |
| `device.offline.auto_unlock_stable_minutes` | `15` | 自动解锁前需保持稳定在线分钟数，`0` 关闭 |

配置入口：运营后台 → 系统 → 参数配置。两个参数会自动出现在列表里，**保存后即时生效**
（任务每次执行实时读取，无需重启服务）。参数页查看 / 编辑 / 删除分别受
`ops:config:list`、`ops:config:edit`、`ops:config:delete` 权限控制。

## 三、安全校验（自动解锁前置条件）

1. 设备存在**未解决**的 `DEVICE_FAULT`（离线超时自动锁机产生的 OFFLINE_TIMEOUT 故障）——
   运营手动锁机**不会**被自动解锁；
2. 该设备**无未完结维修工单**（OPEN / IN_PROGRESS）；
3. 该设备**无未结算购物会话**（CREATED~SETTLING 任一状态）。

满足条件后：下发 MQTT UNLOCK → 解除销售锁并清禁售 → 自动关闭 `DEVICE_FAULT`、`DEVICE_OFFLINE`
异常 → 写入审计 `DEVICE_AUTO_UNLOCK_STABLE_ONLINE`。

## 四、XXL-JOB 接入

### 默认开箱即用（不需要自己启动任何东西）

不开启 XXL-JOB 时，两个功能由内置 Spring 定时任务兜底执行：

| 功能 | 内置兜底调度 | 频率 |
|---|---|---|
| 稳定在线自动解锁 | `DeviceAvailabilityJobScheduler` | 每 5 分钟扫描一次 |
| 设备可用性 KPI 日快照 | `DeviceAvailabilityJobScheduler` | 每日 01:10 统计前一天 |

开启 XXL-JOB（`aicabinet.xxljob.enabled=true`）后，内置兜底**自动让位**，由调度中心统一调度，避免双跑。

### 可选：接入 XXL-JOB 调度中心

执行器代码已接入（`xxl-job-core 3.4.2`，Spring Boot 3 / JDK 17 兼容），默认**关闭**：

```yaml
aicabinet:
  xxljob:
    enabled: ${XXL_JOB_ENABLED:false}
xxl:
  job:
    admin:
      addresses: ${XXL_JOB_ADMIN_ADDRESSES:}
    access-token: ${XXL_JOB_ACCESS_TOKEN:}
    executor:
      appname: ${XXL_JOB_EXECUTOR_APPNAME:trade-service}
      port: ${XXL_JOB_EXECUTOR_PORT:9999}
```

开启方式：`XXL_JOB_ENABLED=true`，并保证调度中心可达。

### 调度中心部署（docker-compose 覆盖层）

```powershell
cd infra
copy .env.example .env
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.xxljob.yml `
  --profile apps --profile xxljob up -d --build
```

调度中心地址：`http://localhost:18080/xxl-job-admin`（默认账号 admin / 123456）。
`infra/xxl-job/tables_xxl_job.sql` 为 3.4.2 官方初始化脚本，MySQL 首次启动自动导入。
调度中心为独立系统，账号体系与运营后台分离，建议仅运维负责人持有登录权限。

### 需在调度中心注册的任务

| JobHandler | 建议调度 | 说明 |
|---|---|---|
| `deviceStableOnlineAutoUnlockJob` | 每 5~10 分钟 | 稳定在线自动解锁（受系统参数控制） |
| `deviceAvailabilityKpiDailyJob` | 每日 `0 10 1 * * ?` | 生成前一日设备可用性 KPI 快照 |

执行器 AppName 为 `trade-service`（可用 `XXL_JOB_EXECUTOR_APPNAME` 覆盖）。

## 五、设备可用性 KPI

### 数据来源与口径

- 离线事件数：当日新增 `DEVICE_OFFLINE` 异常数；
- 自动锁机数：当日新增 `DEVICE_FAULT` 异常数；
- 自动解锁数：当日审计 `DEVICE_AUTO_UNLOCK_STABLE_ONLINE` 次数；
- 人工解锁数：当日审计 `DEVICE_UNLOCK`（操作人非系统）次数，含运维按钮与维修工单完结解锁；
- 平均锁定时长 / 平均恢复时长：当日已解决异常的创建→解决时长均值（小时），未解决记为空；
- 人工介入率：人工解锁数 /（人工+自动）解锁总数。

### 表与 API

表：`device_availability_kpi_daily`（`kpi_date` 主键，每日覆盖写入）。

查询 API（需 `ops:device-kpi:view` 权限；后台页面「设备商品 → 设备可用性」默认展示当天）：

```text
GET /api/v2/ops/admin/device-availability-kpi                  # 默认当天实时口径
GET /api/v2/ops/admin/device-availability-kpi?date=2026-08-06  # 指定日期（有快照返回快照，无则实时计算）
```

默认展示当天数据，只有选择日期时才查询该日期，避免一次拉全量。

## 六、相关：定时任务管理模块

两个任务的启停、手动执行与执行记录可在运营后台「系统 → 定时任务」页操作
（权限 `ops:task:list/edit/run`）。详见 [SCHEDULED_TASK_MANAGEMENT.md](SCHEDULED_TASK_MANAGEMENT.md)。
