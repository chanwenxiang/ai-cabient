# 高可用（trade 多实例）

本仓**不另引 ShedLock**：写型定时任务已统一经 `ScheduledTaskService.tryBegin` + Redis 锁
（`job:<taskKey>`）做多实例选举，语义等同 ShedLock；资金类可再叠加 XXL-JOB。

## 1. 已具备的集群安全能力

| 能力 | 机制 | 说明 |
|---|---|---|
| 定时任务单飞 | `tryBegin` / Redis `job:*` | 多副本同一 taskKey 只跑一个；见 `docs/SCHEDULED_TASK_MANAGEMENT.md` |
| 资金任务中心化 | XXL-JOB + `XxlJobManagedTasks` | `XXL_JOB_ENABLED=true` 时内置 `@Scheduled` 让位 |
| Flyway 迁移锁 | PostgreSQL advisory lock | Flyway 默认串行迁移，多实例启动不会并行跑脚本 |
| 防回潮 | ArchUnit `scheduledMustCallTryBegin` | 新增 `@Scheduled` 必须调用 `tryBegin` |

## 2. 启 2 副本（Compose）

本地 / 预发验证多实例时叠加 HA 层，**不要**给 trade 映射宿主机 `8080`（否则 scale 端口冲突）：

```powershell
# 仓库根目录
docker compose -p ai-cabinet `
  -f infra/docker-compose.yml `
  -f infra/docker-compose.apps.yml `
  -f infra/docker-compose.ha.yml `
  --profile apps up -d --scale trade-service=2
```

约束：

1. **Redis 必须可用**（否则 `tryBegin` 拿不到锁，任务全跳过）。
2. **建议开 XXL-JOB**（`XXL_JOB_ENABLED=true`），资金/对账走调度中心。
3. 对外入口走 **gateway/nginx**，不要直连单容器 `8080`。
4. 滚动发布：先起新副本 → health 绿 → 再停旧副本；Flyway 由先起来的实例执行，其余等 advisory lock。

## 3. 上线后两周内建议

- [ ] staging 以 `--scale trade-service=2` 浸泡 ≥24h，核对定时任务「最近执行」无双跑
- [ ] 确认 gateway 对 `trade-service` 做上游负载均衡 / 健康检查摘除
- [ ] PostgreSQL 主从或托管高可用 + 备份恢复演练（见 `docs/PRODUCTION.md`）
- [ ] K8s / Swarm：`replicas: 2` + pod 反亲和（不同节点）

## 4. 为何不引入 ShedLock 依赖

`ScheduledTaskService` 已覆盖：启停开关、租约锁、执行记录、运营「立即执行」、XXL 让位。
再加 ShedLock 会双轨锁、双套配置，收益低。ArchUnit 保证新任务不会漏锁即可。
