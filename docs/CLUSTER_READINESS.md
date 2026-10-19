# 集群就绪清单

目标：多实例部署下不出现重复执行、重复处理、单节点状态、文件/资金一致性问题。

## 一、已完成

| 项目 | 实现 |
|---|---|
| 写型定时任务分布式锁 | 注册表 **39 个 taskKey**（38 常驻 + `ops-fee-bill-monthly` 条件注册，`ScheduledTaskRegistry:86-190`）全部经 `ScheduledTaskService` 统一守卫（启停开关 + Redis 租约锁 + 执行记录），见 [SCHEDULED_TASK_MANAGEMENT.md](SCHEDULED_TASK_MANAGEMENT.md)；XXL 侧 37 个 cron，差额与 `settlement-bill` 半登记见 §二「任务登记口径」 |
| XXL-JOB 可选接管 | `aicabinet.xxljob.enabled=true` 时由调度中心调度，内置兜底自动让位 |
| MQTT 共享订阅 | device-service 改为 `$share/aicabinet/cabinet/+/evt`，同一事件只投递给一个实例 |
| 设备事件去重 | `DoorEventDeduplicator` 优先 Redis（多实例共享），Redis 不可用时回退本地 |
| 会话状态并发防护 | 开门事件/视频挂接改为 `SELECT ... FOR UPDATE` 行锁读取会话，重复事件被状态机天然挡掉 |
| 文件本地回退开关 | `app.storage.local-fallback-enabled` 默认 true（兼容现状），生产 profile 强制 `false`，MinIO 不可用时直接报错而非写单节点磁盘 |
| 余额行锁（修正） | `UserAccountMapper._findByIdForUpdateRaw` 原来只有方法名带 ForUpdate、SQL 并无 FOR UPDATE，已补上 |
| 命令状态跨实例 | `DeviceCommandTracker` 状态写入 Redis（TTL 1 小时），任意实例可查，Redis 不可用时回退本地 |
| 看板缓存跨实例**基本**一致 | `CacheService` Redis 优先（类型自描述序列化），Redis 层删除跨实例可见；但 `evict(prefix)` 只清**本进程** L1 map + 删 Redis 键，**没有失效广播**——其他实例在 L1 未过期前仍会命中旧值（`CacheService:149-164`）。详见 §二「L1 无失效广播」 |

## 二、多实例前必须先认的风险（2026-10-16 按源码实跑复核）

> 这一节改名前叫「低风险项」，只列了两条。实跑核对后补进四条**会在多副本下真出事**的项，
> 别再按「低风险」理解。目标形态与迁移顺序见 [PRODUCTION_TOPOLOGY.md](PRODUCTION_TOPOLOGY.md)。

| 项目 | 事实（源码位置） | 多副本影响 | 建议 |
|---|---|---|---|
| **库存扣减无 DB 兜底** | `InventoryService.java:318-337` 与 `InventoryLotService.java:145-200` 是「读→算→写」，实体无 `@Version`，DB 层无 `CHECK (quantity >= 0)` | 两副本并发同槽位 ⇒ **超卖**，且写进去就是脏账，事后看不出是谁超的 | 单副本前不动也行；**起第 2 副本的前置项**（已立项 `docs/TRI_CLIENT_BACKLOG_2026-10-15.md` P-1：原子 `UPDATE … WHERE qty >= ?` + CHECK 约束） |
| **钱包流水无业务键唯一索引** | `line_wallet_ledger`（V133:101）、`merchant_wallet_ledger`（V139:69-72）、`staff_wallet_ledger`（V359:27）三张流水表都**只有普通查询索引**，去重全靠应用层判断；对照：`merchant_fee_ledger`（V355:18）和 `commission_clawback`（V361:21）**已有**业务键唯一索引⇒同一套代码里做法不一致 | 双跑/重试 ⇒ 重复入账，**只能靠对账发现** | 立项 P-2；照 V355/V361 的写法补唯一索引，**上线索引前先跑存量重复检测**（有历史脏数据会让迁移直接失败） |
| **锁租约不续期** | `ScheduledTaskService` 拿 Redis 租约锁（`job:<taskKey>`），无 watchdog 续期；`ProfitSharingRetryScheduler.java:45` 把**外部 HTTP（将邑）**包在 `@Transactional` 里 | 单轮跑得比租约长 ⇒ 锁过期被别的副本抢走 ⇒ **同一任务双跑**；长事务还占着连接池 | 立项 P-3：临界区外移 HTTP，或租约续期/超时上限对齐 |
| **L1 无失效广播** | `CacheService.java:149-164` evict 只清本进程 + 删 Redis 键 | 其他实例 L1 到期前继续供旧值（看板/运营数字短暂不一致，非资金） | 立项 P-4：Redis pub/sub 失效广播，或把 L1 TTL 压到可接受窗口 |
| Redis 是单点强依赖 | `RedissonConfig.java:28,39` `useSingleServer`；锁（`job:<taskKey>`）、幂等（`DoorEventDeduplicator`）、命令状态（`DeviceCommandTracker`）、L2 缓存（`CacheService`）都挂它 | Redis 抖动 ⇒ 定时任务**整轮静默跳过**（fail-safe 不 fail-loud）；托管主从切换也要客户端支持故障转移，`useSingleServer` 不会自动跟 | 副本数 ≥ 2 时把「任务超期看护」`scheduled-task-stale-monitor` 的告警接到人；换托管时确认端点支持 Lua/`MULTI` |
| Kafka 消费组并行度 | 视觉识别监听按分区消费 | topic 分区数 < 实例数 ⇒ 多出来的副本空转 | 扩实例时同步扩分区 |
| MQTT 设备端命令订阅 | 设备按自身 clientId 订阅，EMQX 按 topic 路由 | 无 | 无需处理 |
| 任务登记口径 | 注册表 39 个 taskKey（38 常驻 + `ops-fee-bill-monthly` 条件注册），`ScheduleZones.XXL_CRON_BY_TASK` 与 `infra/xxl-job/seed_aicabinet_jobs.sql` 各 **37** 行；差的 2 个 = `refund-retry`、`scheduled-task-stale-monitor`（只由 Spring 兜底） | `settlement-bill` 是**半登记**：V334 给它 seed 了 `scheduled_task` 行（运营台可见可启停、执行记录照写），但它**不在 `ScheduledTaskRegistry`**，也没有 XXL cron ⇒ 运营台「立即执行」返回 **404「任务不存在: settlement-bill」**，XXL 手工触发返回 **handleFail「任务未注册: settlement-bill」** | 上多副本前把两侧数字对齐（`pnpm check:xxl-job-wiring` / `check:scheduled-task-seed` 已可校验）；测试口径见 `docs/uat/FULL_TEST_SESSION_PROMPT.md` §6 |

## 三、集群部署要点

- 所有实例共享 Postgres / Redis / Kafka / MinIO / EMQX；
- trade-service 多实例：网关负载均衡，无粘性会话要求（JWT 无状态）；入口侧
  `infra/gateway/nginx.compose.conf:19,23-24` 用 `resolver … valid=10s` + 变量 `proxy_pass`，
  扩副本**不需要 reload nginx**（代价：副本消失后最长 10s 内偶发 502）；
- device-service 多实例：已用共享订阅。`MQTT_CLIENT_ID` 后缀自 A7/P3 起是**稳定的容器 `HOSTNAME`**
  （非容器环境才退回随机，`MqttEventListener.java:74-79`）⇒ 同机起两副本务必给不同 `hostname`
  （`infra/docker-compose.prod-managed.yml` 用 `device-${DEVICE_INSTANCE}` 显式钉住），否则会互踢会话；
- 定时任务：默认分布式锁已保证单实例执行；启用 XXL-JOB 时按 seed 现值——路由 **FAILOVER** +
  阻塞 **SERIAL_EXECUTION**（`infra/xxl-job/seed_aicabinet_jobs.sql:13`），不是「第一个」；
- 生产配置：`app.storage.local-fallback-enabled=false` 已内置到 prod profile；
- JVM：`mem_limit` 与堆的关系见 `infra/docker/trade-service.Dockerfile:59-66`（默认
  `MaxRAMPercentage=75`，可用 `JAVA_OPTS` 整体覆盖）——**设了 `stop_grace_period` 不代表能优雅退出**，
  entrypoint 用 `exec java` 保 PID 1，SIGTERM 才真的到 JVM。

## 四、验证

全量回归：trade-service 229 用例 + device-service 4 用例，0 失败 0 错误；
前端 `vue-tsc` 类型检查通过。
