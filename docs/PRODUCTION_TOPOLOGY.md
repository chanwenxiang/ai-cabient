# 生产拓扑与服务器规格（2 台 + 云托管）

> 状态：**目标形态（2026-10-16 用户拍板）**，不是当前落地状态。
> 当前实况=**单机**：`scripts/deploy-prod.sh` 只 scp/build/recreate 一台主机（`PROD_HOST` 默认值，
> deploy-prod.sh:25），叠加的 `infra/docker-compose.prod-server.yml` **不在 git**，
> 全仓此前**没有任何服务器规格文档**——本文补这个洞。
> 编排落地见 `infra/docker-compose.prod-managed.yml`（本形态专用叠加层）。
>
> 🔴 **实施时点（2026-10-16 用户口径：「等上线再说」）**：形态已拍板、编排已备好，但**本轮不迁移、
> 不开第 2 副本、prod-server.yml 暂不入库**；首次上线仍按**单机**跑。本文的 §4 顺序与 §3 风险清单
> 是「真要扩的时候照着做」的预案——尤其 §3 前两条（库存/钱包无 DB 兜底）是**开 `--scale` 的硬前置**，
> 单机期间不阻塞上线。

## 1. 目标形态一句话

A 台跑应用（trade ×2 + device ×1 + 网关 + 监控），B 台跑中间件（EMQX / MinIO / Kafka / XXL-JOB），
PostgreSQL 与 Redis 换**云托管主备/主从**走内网；对外只有 A 台 nginx :80 一个入口。

```
                 公网 ──► [A 台 nginx gateway :80]
                              │ /admin/ 静态、/api/* → trade-service（2 副本，loopback 上游）
   消费者/商户小程序 ──────────┤ MQTT 1883（B 台 EMQX，设备与 device-service 连它）
   运营浏览器 ─────────────────┘
        A 台（应用）                      B 台（中间件）              云托管
  trade-service ×2  1024m/1.5c    emqx          512m/0.5c      PostgreSQL 主备（内网）
  device-service     768m/1.0c    minio         768m/0.5c      Redis 主从（内网, AOF）
  jiangyi-gateway    768m/1.0c    redpanda                      （迁移自托管时留 B 台兜底）
  gateway(nginx)     —             xxl-job-mysql/admin
  prometheus+grafana 512m each    minio-init(一次性)
```

资源上限取自现有编排实测值：`infra/docker-compose.apps.yml:153,192`（trade 1024m、device 768m）、
`infra/docker-compose.yml`（redis 512m、emqx 512m、minio 768m、postgres 1g、prometheus 512m）。

## 2. 规格与算术依据

### A 台 · 应用机：8 vCPU / 16 GB（最低 6 核 12 GB，**不要 4C8G**）

| 项 | 账 |
|---|---|
| 容器限额之和 | trade 2×1024m + device 768m + jiangyi-gateway 768m + prometheus 512m + grafana 512m ≈ **4 GB** |
| JVM 实际用量 | trade 堆 = 容器内存 × 75%（见 `infra/docker/trade-service.Dockerfile:66`）≈ 768m/副本，**堆外**（Metaspace+线程栈+DirectBuffer+GC）还要 ~250–350m/副本 ⇒ 单副本真实 RSS ≈ 1.1–1.2 GB，已顶到 `mem_limit: 1024m` |
| 结论 | trade 的 1024m 在**多副本 + 开缓存**下偏紧：**建议把 apps.yml 的 trade `mem_limit` 提到 1536m**，或显式设 `JAVA_OPTS="-Xmx1g"`；否则 OOM 表现是容器被 kill（`ExitOnOutOfMemoryError` 只是让它死得干脆、由 `restart: unless-stopped` 拉起） |
| 宿主机余量 | 4 GB 容器 + docker daemon/日志/构建缓存 ⇒ 16 GB 才敢放 2 副本；**A 台不要参与镜像构建**（见 §5 第 4 步） |
| CPU | trade 1.5c × 2 + device 1.0 + gateway 1.0 = 5.0 配额，加 nginx/监控/系统 ⇒ 8 核 |

### B 台 · 中间件机：4 vCPU / 8 GB

EMQX 512m + MinIO 768m + redpanda + xxl-job-mysql + xxl-job-admin + 系统 ≈ 3–4 GB 用量；
MinIO 存柜机视频（视觉链的写放大主要落这里），**数据盘按独立卷挂 + 预留 ≥ 500 GB 起**，
Kafka 消息只是传递不长期占用。CPU 4 核够（B 台没有 JVM 大堆，redpanda/EMQX 都是轻载）。

### 云托管 PostgreSQL：2 核 4 GB 起，**`max_connections` 是硬算术**

连接池口径：`services/trade-service/src/main/resources/application.yml:24` ⇒
`maximum-pool-size: ${DB_POOL_SIZE:20}`，每副本一个 Hikari 池。

```
needed = 副本数 × DB_POOL_SIZE + Flyway/监控/人工 psql 余量
       = 2 × 20 + 10 = 50   ⇒ 托管实例 max_connections ≥ 60（默认 100 的实例可直接用）
```

- 扩副本或扩 `DB_POOL_SIZE` 前**必须先核对托管实例的 `max_connections`**，否则新副本启动期
  连接池打不满 → 请求排队 → 表现为「第二个副本更健康但更慢」。
- **只有 trade-service 持有连接池**：`infra/docker-compose.apps.yml` 里仅 `:63`（trade）设了
  `SPRING_DATASOURCE_URL`，device-service 的 `application*.yml` 完全没有 datasource（其实跑验证：
  它靠 Redis + 内部 API 调 trade）。⇒ 算术里**不要**给 device 加池，但 device 多副本仍要加 Redis 连接数。
- 门禁 `scripts/check-scheduling-vs-db-pool.mjs` 已保证**单实例内**定时任务并发 ≤ 池上限（实测
  trade scheduling=8 / hikari=20），但它**不算副本数**——多副本时是上面那条算术的责任。
- 要主备 + 自动备份 + PITR；只有「每日 pg_dump」不算备份达标（见 §4 第 5 条）。

### 云托管 Redis：1 GB，主从 + AOF everysec

Redis 在本项目不只是缓存，**它扛着三类强依赖**（选规格前必须知道）：

1. 分布式锁：`ScheduledTaskService.tryBegin` 的 `job:<taskKey>` 租约锁 + `DistributedLockService`
   （Redisson）——多副本定时任务单飞全靠它；
2. 幂等/去重：开门事件 `DoorEventDeduplicator`、命令状态 `DeviceCommandTracker`；
3. L2 缓存：`support/CacheService.java`（Redis 优先，不可用才回退本地）。

⇒ 必须支持 `MULTI`/`eval`（Redisson 依赖 Lua）、带密码、**只开内网访问**、开 AOF
（丢了锁只是重跑，丢了幂等键会重复处理硬件事件）。版本 ≥ 6 即可，不需要集群版
（单分片主从足够，本项目无跨 slot 需求；**Redisson 当前是 `useSingleServer` 单点连接**）。

## 3. 这套形态仍然解决不了的（诚实清单）

| 项 | 现状 | 影响 |
|---|---|---|
| Redis 单点 | `RedissonConfig.java:28,39` `useSingleServer`；托管主从需**客户端故障转移**才受益 | 托管侧切换瞬间连接报错→锁拿不到→**定时任务整轮跳过**（fail-safe 但不 fail-loud） |
| 锁租约不续期 | 无 watchdog 续期，长临界区（含外部 HTTP，如 `ProfitSharingRetryScheduler.java:45` 在 `@Transactional` 里调将邑）超时后被别副本抢锁 | 双跑风险，靠业务幂等兜 |
| 库存/钱包无 DB 兜底 | `InventoryService.java:318-337` 读改写、无 `@Version`；钱包流水无业务键唯一索引 | **多副本上线前置项**，已立项 `docs/TRI_CLIENT_BACKLOG_2026-10-15.md`（P-1/P-2） |
| L1 缓存不广播 | `CacheService` evict 只在 Redis 层跨实例，进程内 L1 到期才更新 | 看板数字短暂不一致（非资金） |
| EMQX / jiangyi-gateway 单实例 | 没有像 trade 那样的 scale 叠加层 | 单点；EMQX 挂 ⇒ 柜机事件全断（比 trade 挂更致命） |
| Kafka 分区 | redpanda 单 broker，分区数不 ≥ 实例数时视觉链消费无并行 | 扩副本要同步扩分区 |
| 备份调度 | `scripts/backup-database.ps1` 只**打印** cron 建议，不安装任务 | 「有备份脚本」≠「备份在跑」，需 cron/systemd timer 实证 |
| 编排真源不完整 | `docker-compose.prod-server.yml` 不在 git | 换机/灾难恢复只能靠人脑记忆，本文 §5 第 1 步先解决 |

## 4. 迁移到 2 台的顺序（每步可回退）

1. **先补齐编排真源**：把服务器上 `infra/docker-compose.prod-server.yml` `cat` 回仓（或逐行 diff 后
   提交）。它不在 git 时，任何「换台机器」都是重写历史。
   （2026-10-16 用户口径：**等上线再说**，本轮不催；真要换机/扩容时这是第 1 步，别跳。）
2. **托管先接、后拆**：`.env.production` 里加 `DB_URL / DB_USER / DB_PASSWORD / REDIS_HOST / REDIS_PORT /
   REDIS_PASSWORD`（键名见 `prod-managed.yml`，`:?` 必填=忘配直接起不来），先用 `pg_dump|restore`、
   `redis-shake`/RDB 把数据搬到托管，**本机 postgres/redis 容器继续跑不动**，验证通过再叠加
   `prod-managed.yml`（它把本机两个服务改挂 `legacy-local-stateful` profile，**不删定义**，
   去掉叠加层即回退自托管）。
3. **A 台起第 2 副本**：
   ```bash
   docker compose -p ai-cabinet \
     -f infra/docker-compose.yml -f infra/docker-compose.apps.yml \
     -f infra/docker-compose.production.yml -f infra/docker-compose.prod-managed.yml \
     --profile apps up -d --scale trade-service=2
   ```
   叠加层已 `ports: !reset []` 摘掉宿主机 8080（否则 scale 端口冲突，同 `docker-compose.ha.yml` 做法），
   对外全走 gateway :80。**前置**：§3 的库存/钱包 DB 兜底已落，否则不要开第 2 副本。
4. **构建从 A 台搬走**：`deploy-prod.sh:104` 是「服务器现场 `compose build trade-service`」（Maven
   全量打包 + admin 静态），在应用机上现场编译会把 CPU 吃满几分钟，同机 trade 副本的响应延迟
   直接被拖崩。上 2 台后改成 CI 出镜像（GH Actions build → 私有 registry → 两台 `pull`），
   A 台只跑不建。另外该脚本的健康检查只盯容器 `ai-cabinet-trade-service-1`（deploy-prod.sh:108），
   多副本时**第 2 副本起不来它照样报 ✅**——需要改成遍历所有副本或查 gateway 上游。
5. **B 台落地**：EMQX/MinIO/redpanda/xxl-job 迁 B 台，柜机侧 MQTT 地址是**设备端配置**——
   换 IP 要先确认能否批量下发（否则老固件继续连旧地址，表现为「一半柜子不报事件」）。
6. **备份与恢复**：托管 PG 开自动备份 + PITR；`backup-database.ps1` 的 cron 建议**真的装进 crontab**，
   并在 staging 做一次「从备份拉起整套栈」演练（`docs/uat/PROD_POST_LAUNCH_VERIFICATION_PROMPT.md` §5）。
7. **验证**： soak ≥ 24h，核对 `scheduled_task.last_run_at` 推进且无双跑、gateway 上游健康摘除、
   `docker stats` 无副本触顶 `mem_limit`。

## 5. 明确不做（此形态下）

- 不上 K8s/Swarm：2 台机用 Compose + `--scale` 已覆盖，K8s 的运维成本现在没有回报。
- 不做跨可用区多活：先接受「A 台挂 = 应用层不可用，但托管 PG/Redis 数据不丢」这一档 SLA。
- 不给 trade 配会话粘滞：JWT 无状态，入口侧的多副本发现已经现成——
  `infra/gateway/nginx.compose.conf:19` `resolver 127.0.0.11 valid=10s` + `:23-24` 变量式
  `proxy_pass http://$trade_upstream` ⇒ nginx 每次请求向 Docker 内嵌 DNS 解析 `trade-service`，
  在多个 A 记录间轮转，**扩副本不需要 reload nginx**。代价：副本被杀后最长 10s 内仍可能把请求
  打到已消失的 IP（表现为偶发 502，靠客户端重试兜），这不是 nginx 层能自动摘除的健康检查。
