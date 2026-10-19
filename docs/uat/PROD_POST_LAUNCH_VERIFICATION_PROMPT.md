# 生产上线后验证包 · AI 执行提示词（只读 + 影子链路，2026-10-16）

> **地位**：与 `docs/uat/FULL_TEST_SESSION_PROMPT.md`（上线**前**功能终测）**配套但不重叠**。
> 那份证明"业务逻辑对"；这份只回答一件事：**上线后系统活着、没伤到真实商户与真实消费者的钱**。
> **三条硬规矩**：
> 1. **不动真实账**：不提现、不退款到真实消费者、不改真实在售柜的商品映射/价格/启停、不删真实台账。
> 2. **写只走影子**：唯一允许的写路径 = 影子账号 + 影子测试柜（专用 deviceId/classId）的开门→识别→结算。
> 3. **破坏性一律在 staging 同构副本**（恢复演练/回滚演练/故障注入/多副本 soak），**禁止在生产执行**。

---

## 提示词正文（复制从这里开始）

```
你是 AI Cabinet 仓库的 Agent，执行「生产上线后验证」。环境事实（本仓读码/读盘已核实，仍须现场复验）：
- 生产是**单 host 单机**：`scripts/deploy-prod.sh:25` 默认 `PROD_HOST=120.25.246.179`、
  `PROD_DIR=/data/apps/ai-cabinet`，只 build/up `--force-recreate trade-service`（:104-105）。
- 编排 = `docker-compose.yml` + `docker-compose.apps.yml` + `docker-compose.production.yml`
  + `.env.production --profile apps`；另有 `infra/docker-compose.prod-server.yml`（bind mount 等）**不在 git**，
  只在服务器 ⇒ 现场必须先 `cat` 一份回仓或登记差异，否则第 1 节做不完。
- 除 `gateway :80`（0.0.0.0）外，其余服务一律 bind `127.0.0.1`；compose 内**无** replicas/deploy/k8s 清单。
- 容器资源限制（apps.yml/docker-compose.yml）：trade 1024m/1.5c · device 768m/1c · jiangyi-gateway 768m/1c
  · postgres 1g/1c · redis 512m/0.5c · emqx 512m/0.5c · minio 768m/0.5c · redpanda 512m。
- 多副本能力**只在文档与 overlay**：`infra/docker-compose.ha.yml` + `docs/HIGH_AVAILABILITY.md`
  （`--scale trade-service=2`），`deploy-prod.sh` 从不带它 ⇒ 现状是单副本。
- nginx 用 `resolver 127.0.0.11 valid=10s` + 变量 `proxy_pass`（nginx.compose.conf:19,23-24）
  ⇒ scale 后能重新解析 DNS 轮询；但 `jiangyi-gateway` 的 WS 长连（`proxy_read_timeout 3600s`）
  会把设备钉在单实例上 ⇒ 设备接入面**不能**按 trade 的方式扩。

### 0. 开工前必须向用户拿到的输入（缺项即 BLOCK，禁止猜）
 I1 生产入口域名/后台地址、影子运营账号（只读权限优先）、影子消费者手机号段、影子商户号（或真实商户下的影子柜授权）
 I2 专用测试柜：deviceId（纯数字）+ 该机在将邑侧的专用 classId 集合 + 现场可控断电/断网的人
 I3 `infra/docker-compose.prod-server.yml` 与 `.env.production` 的**当前内容**（脱敏后可回仓；密钥不外发）
 I4 备份位置与最近一次成功备份时间；恢复演练允许用哪台机器；允许的窗口
 I5 告警渠道与值班名单（飞书/短信/邮件真实送达给谁）
 I6 生产是否已开 `--scale trade-service=N`（决定第 7 节是否执行）
 I7 真实小额退款/真实提现是否在允许范围（默认**不允许**）
 I8 发布与回滚责任人、当前线上镜像 tag 与 git SHA

### 1. 只读基线盘点（活着且配得对，不做任何写）
 1 `docker compose ps` 全清单 + 镜像 tag + `RestartCount`（>0 逐个查因，不许"重启过就好"）+ `docker stats` 抽样
 2 健康检查与关键端点：`/actuator/health`、`/v3/api-docs` 200、`/admin/index.html` 200 且 content-type/字节数正确
   （铁律 40：已入库≠能显示）；H5 冒充不适用——本节不涉小程序渲染
 3 🔴 **启动日志证明 fail-loud 生效**：`ProductionStartupValidator.rejectMockFlagsInStrictProfile` 是 throw 不是 log，
   所以只要服务起来了，就应能证明 `security.mock` / 线长提现 mock / 商户提现 mock /（prod 还含）recon mock **全为 false**；
   从容器 env 逐项读出真值并列证（不要相信 .env 模板，要读运行容器 `docker inspect`）
 4 数据面：postgres `max_connections` vs **N × Hikari 池**（`DB_POOL_SIZE` 默认 20）乘算是否逼近上限；
   redis `INFO`（AOF yes + `redisdata:/data` 卷真挂上）；minio 桶与 OSS `ai-cabinet-by` 生命周期规则
   （`jiangyi-video-expire-90d`）现场复核；EMQX：`EMQX_MQTT_BIND` 是否给不支持 TLS 的柜机开了 1883 明文（若是，登记为风险，
   与 backlog A2「MQTT 默认凭据收口」一起交用户排期），ACL 是否每柜独立凭据
 5 XXL 控制台**实跑列表** vs `ScheduleZones.XXL_CRON_BY_TASK` 37 条 + 注册表 39 key：多/少/禁用/cron 不一致逐条登记；
   月任务 `trigger_status` 是否真为启用
 6 🔴 **`scheduled_task` 表最近执行推进**（上线后第一件事）：按 `MAX_SILENCE_BY_TASK` 口径，
   每个 key 的 `last_run_at` 都在静默阈值内、`last_result=SUCCESS`；`settlement-bill`（每日 02:40，注册表外
   ⇒ 运营台「立即执行」应 404）是否到点真跑并写了记录 —— 它没有手动触发兜底，是生产最脆的一环
 7 时区：容器 `TZ` / `aicabinet.schedule.zone` 与 `Asia/Shanghai` 一致（跨零点分桶与账期全靠它）

### 2. 影子链路端到端（唯一允许的写路径，金额压到最小）
 1 影子消费者登录（真实小程序面，禁 H5 冒充）→ 只允许看到**影子测试柜**；真实在售柜清单不得出现在影子账号里（越权负例）
 2 真柜扫码开门 1 次 → 取 1 件低价 SKU → 关门 → 将邑端侧识别 → 结算扣款成功 → 订单三端逐字段一致 → 录像文件真在（GET 200）
 3 库存：柜机账面与货道真值核对（现场点数一次），期初+Σ流水=账面在影子柜上成立
 4 资金**只跑到记账为止**：分账 split 行生成、商户钱包入账、待分/已结同源；
   **不出账**（不发起真实提现、不调真渠道打款）；若 I7 允许小额真退，才做 1 笔影子单全额退并验回退口径
 5 幂等硬验（生产也做，用同一影子会话）：同一识别结果**重复上报两次** ⇒ 不双扣款/不双建单/不双落库
 6 全程只读 SQL 复核（`BEGIN READ ONLY` 或只读账号），任何"顺手 UPDATE 一下"都属违规

### 3. 真实到点浸泡（24–48h，不干预）
 - 关键 8 个任务逐项核对 `last_run_at/last_result/last_duration_ms`：`reconciliation`(01:30) ·
   `settlement-bill`(02:40) · `line-commission`(00:20) · `finance-margin`(00:05) ·
   `profit-sharing-retry`(15min) · `withdraw-paying-timeout`(10min) · `device-presence`(60s) ·
   `dispute-sla`/`dispute-auto-waive`(15min，开关实况登记)
 - `scheduled-task-stale-monitor` 在浸泡期**不得**报 OVERDUE；若报，先定性是真停跑还是阈值不合理
 - 通知类：`merchant-daily-income-notify` 真送达（商户端消息中心 + 渠道留痕），不是只写库

### 4. 告警可达性演练（用影子柜，不碰真实在售柜）
 1 影子测试柜断网/断电 ≤5 分钟 → `DEVICE_OFFLINE` 是否在预期 SLA 内**真的到了值班人手里**（不是只落库）
 2 复电 → `last_offline_at`/`online_since` 正确写入，后台在线率回升
 3 `CHARGE_PENDING`、`RECON_MISMATCH` 若不可控制造 ⇒ 标 `ENV-BLOCK` 并写清需要谁能造；禁 mock 冒充通过
 4 自动免单开关：实测生产实况（默认 OFF），ON 前必须有用户签字

### 5. 备份与恢复（**在 staging 同构副本**，禁在生产还原）
 1 `scripts/backup-database.ps1` 产物（custom pg_dump）恢复到新库 → Flyway 版本一致 → 关键表 count 对平 → 记录耗时
 2 🔴 核实生产上这个备份**到底有没有在跑**：仓内脚本 retention 30d + cron `0 3 * * *` 只是**打印建议**（:98-131,168-169），
   无调度证据 ⇒ 现场必须查宿主机 crontab/云快照，缺失即为 **No-Go 级发现**
 3 Redis 重启后 KV 真回（AOF）；对象存储不可达时的行为（`app.storage.local-fallback-enabled` 生产必须 false，
   应报错而非写单节点磁盘）
 4 Redis 停机演练（staging）：`tryBegin` 拿不到锁 ⇒ 任务全跳过、开门/结算抛 CONFLICT —— 这是**有意的单点**，
   须成文写清"Redis 挂了 = 停摆"，供用户决定是否上 Redis 主从/sentinel（现 `RedissonConfig.java:28` 是 useSingleServer）

### 6. 回滚演练（staging 走一遍，生产只核对剧本）
 1 `IMAGE_TAG` 回退一个版本 + `docker compose up -d`；核对 `DEPLOYMENT_CHECKLIST.md §5` 步骤可执行性
 2 `deploy-prod.sh` 在生产可用否：它引用的 `docker-compose.prod-server.yml` 不在 git ⇒ 换机/新人接手能不能跑通？跑不通就登记
 3 回滚后 Flyway **不可逆迁移**清单：本轮/近期有没有"回滚版本但迁移已应用且不兼容"的组合，列成表
 4 生产回滚需用户批准，本会话只演练与核对，**不执行**

### 7. 多副本 soak（仅当 I6 确认生产真开了 `--scale trade-service=N≥2`；否则整节 SKIP 并写明"生产单副本"）
 1 请求分布：连续压 `trade` 入口，确认 nginx 真分发到多容器（看各副本访问日志/实例标识）
 2 定时任务无双跑：同一 key 在 N 副本下 `last_run_at` 只推进一次、无并发交叠（ArchUnit `scheduledMustCallTryBegin` 只管新任务，运行时仍须实测）
 3 🔴 **库存/钱包 DB 级兜底探针**（源码已核实为风险，实测确认是否真能撞上）：
   - `InventoryService.applyDelta:318-337` 是 findById→Java 相加→save 的读改写，`next<0` 判断基于**陈旧读**；
     全仓 **grep 无 `@Version`**；lot（`InventoryLotService:145-200`）与 slot（`DeviceSlotService:455+`）同样无 `FOR UPDATE`/原子 SQL
     ⇒ 用影子柜并发两笔同 SKU 结算（或两实例），比对账面是否少扣/负库存/批次账实不符
   - 钱包 ledger 无业务键唯一索引（`merchant_wallet_ledger` V139:69-72、`line_wallet_ledger` V133:101、
     `staff_wallet_ledger` V359:27 **只有普通索引**；对照 `merchant_fee_ledger` V355:18 与
     `commission_clawback` V361:21 **已有**唯一索引 ⇒ 同仓做法不一致，别默认"资金表都有唯一键"）
     ⇒ 重复触发一次分账/入账，验是否双写
     （本项与上一条已在 `docs/TRI_CLIENT_BACKLOG_2026-10-15.md` §六 立项为 P-1/P-2，**尚未改码**；
     实测若撞红，按 §六 的负验证口径登记而不是自行改业务代码）
 4 缓存跨实例脏读：`CacheService.lookup` 是 Redis 优先、Redis miss 后**回落本实例 L1**（`support/CacheService.java:88-115`），
   `evict` 只清本实例 L1 + Redis，**无广播**（:149-164）⇒ A 实例改数据后 B 实例可能读到陈旧 L1 到 TTL
   （`docs/CLUSTER_READINESS.md` 已于 2026-10-16 把「evict 跨实例生效」订正为「只对 Redis 半边成立」；
   实测仍要自己撞一次，别拿文档结论当 PASS）
 5 设备在场态互踢：多副本下 `device-presence` 抢锁只一个跑，但 WS/心跳归属在 `jiangyi-gateway`（不 scale）——
   验没有把在线设备误判离线（`last_offline_at` 无异常写入）
 6 MQTT 共享订阅：device-service 若也 scale，`MqttTopics.ALL_EVENTS_SHARED`（`MqttEventListener.java:392`）每事件只投一个实例；
   clientId = `MQTT_CLIENT_ID + "-evt-" + 主机名`（:75-78，`MqttCommandPublisher.java:47` 另加随机后缀）
   ⇒ 实测**两副本 hostname 是否真不同**（相同即 clientId 冲突、后者被踢），不要凭"默认带后缀"就判安全

### 8. 交付
 - `docs/uat-screenshots/<日期>/prod-post-launch/`：`p1-runtime-config.md`（含 `docker inspect` env 真值证据）、
   `p2-shadow-flow.md`、`p3-soak-tasks.md`、`p4-alerting.md`、`p5-restore-drill.md`、`p6-rollback.md`、
   `p7-scale.md`、`PROD_FINDINGS.md`
 - `docs/uat/PROD_POST_LAUNCH_REPORT_<日期>.md`：结论先行（健康 / 带风险运行 / 建议回滚）+ 每条 PASS 标证据类型
   （只读实测 / 影子实测 / staging 演练 / ENV-BLOCK）+ **新增风险登记表**（备份没在跑、Redis 单点、库存无 DB 兜底、
   prod-server.yml 不在 git、MQTT 明文口/默认凭据、抽成 >30% 上限…逐条给"证据 + 影响 + 建议排期"）
 - 全程**不得**修改生产数据或业务代码；发现生产缺陷写进 `PROD_FINDINGS.md` 并告知用户，修复走 dev 分支发布流程

### 9. 禁止（违反即判生产事故风险）
 1 在真实在售柜/真实商户/真实消费者身上做任何写或破坏性操作（含改映射、停用 SKU、改价、删录像）
 2 在生产发起真实提现/真实打款/真实退款（除 I7 明确授权的小额影子退款）
 3 直改生产数据库数据来"让验证过"；绕过鉴权/验证码/限额
 4 在生产执行恢复/回滚/故障注入（只能在 staging）
 5 把 `.env.production`、密钥、真实商户号写进提交或聊天结论（只登记键名与布尔值）
 6 用 staging/单副本结果冒充生产多副本 PASS；用文档口径（如"22 个任务""evict 跨实例生效"）冒充实测
 7 未拿到 I1–I8 就"先测起来"
```

（提示词正文到此结束）

---

## 与上线前终测的分工（给人看）

| 维度 | FULL_TEST_SESSION_PROMPT.md（上线前） | 本包（上线后） |
|---|---|---|
| 环境 | dev 全栈 + mock 渠道 + 将邑测试租户 | 生产真环境（只读）+ staging 同构（演练） |
| 数据 | ≥60 笔订单、36 条业务线铺量、造历史 | 1 笔影子单，最小金额，不铺量 |
| 写 | 允许（新主体、可清理命名） | 🔴 只允许影子柜 + 影子账号 |
| 破坏性 | 允许（本轮主体） | 一律挪到 staging |
| 主要问题 | 逻辑对不对、账平不平、表有没有被写 | 活着没、配得对不对、告警到人没、备份能不能恢复 |
| 判据 | Go/No-Go 闸门（该文件 §11） | 健康 / 带风险运行 / 建议回滚 |

## 本包现在测不到、必须另行安排的（诚实边界）

- **容量与并发基线**：仓里有压测资产（`docs/PERFORMANCE_TESTING.md` + `scripts/perf/*.jmx` +
  `run-o8-staged.sh` 分级升压），但其数据是**本机 Docker VM（3.82→7.9GB 配额）单副本**口径，
  不是生产规格基线。生产基线要么用同一脚本在生产低峰重跑，要么明确"暂不做"。
- **服务器数量与规格**：🔴 **仓内没有任何机器数量/CPU/内存/磁盘规格定稿**（`docs/PRODUCTION.md` 只讲环境变量与网络，
  `DEPLOYMENT_CHECKLIST.md` 无规格表）——需用户拍板（建议见对话结论，不是仓内事实）。
- **微信小程序合规**：类目/隐私声明/支付资质/域名白名单，属人工审核面。
- **依赖 CVE / 渗透测试 / 老 easygo 存量数据迁移**：暂缓项，未纳入本包。
