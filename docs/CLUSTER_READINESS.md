# 集群就绪清单

目标：多实例部署下不出现重复执行、重复处理、单节点状态、文件/资金一致性问题。

## 一、已完成

| 项目 | 实现 |
|---|---|
| 写型定时任务分布式锁 | 全部 22 个任务接入 `ScheduledTaskService` 统一守卫（启停开关 + Redisson 锁 + 执行记录），见 [SCHEDULED_TASK_MANAGEMENT.md](SCHEDULED_TASK_MANAGEMENT.md) |
| XXL-JOB 可选接管 | `aicabinet.xxljob.enabled=true` 时由调度中心调度，内置兜底自动让位 |
| MQTT 共享订阅 | device-service 改为 `$share/aicabinet/cabinet/+/evt`，同一事件只投递给一个实例 |
| 设备事件去重 | `DoorEventDeduplicator` 优先 Redis（多实例共享），Redis 不可用时回退本地 |
| 会话状态并发防护 | 开门事件/视频挂接改为 `SELECT ... FOR UPDATE` 行锁读取会话，重复事件被状态机天然挡掉 |
| 文件本地回退开关 | `app.storage.local-fallback-enabled` 默认 true（兼容现状），生产 profile 强制 `false`，MinIO 不可用时直接报错而非写单节点磁盘 |
| 余额行锁（修正） | `UserAccountMapper._findByIdForUpdateRaw` 原来只有方法名带 ForUpdate、SQL 并无 FOR UPDATE，已补上 |
| 命令状态跨实例 | `DeviceCommandTracker` 状态写入 Redis（TTL 1 小时），任意实例可查，Redis 不可用时回退本地 |
| 看板缓存跨实例一致 | `CacheService` Redis 优先（类型自描述序列化），evict 跨实例生效，Redis 不可用时回退本地 |

## 二、仍存在的低风险项

| 项目 | 影响 | 建议 |
|---|---|---|
| Kafka 消费组并行度 | 视觉识别监听按分区消费，topic 分区数应 ≥ 实例数才有并行度 | 扩实例时同步扩分区 |
| MQTT 设备端命令订阅 | 设备按自身 clientId 订阅，多实例不影响（EMQX 按 topic 路由） | 无需处理 |

## 三、集群部署要点

- 所有实例共享 Postgres / Redis / Kafka / MinIO / EMQX；
- trade-service 多实例：网关负载均衡，无粘性会话要求（JWT 无状态）；
- device-service 多实例：已用共享订阅，勿把 `MQTT_CLIENT_ID` 配成相同值（默认已带随机后缀）；
- 定时任务：默认分布式锁已保证单实例执行；如启用 XXL-JOB，路由策略选「第一个」；
- 生产配置：`app.storage.local-fallback-enabled=false` 已内置到 prod profile。

## 四、验证

全量回归：trade-service 229 用例 + device-service 4 用例，0 失败 0 错误；
前端 `vue-tsc` 类型检查通过。
