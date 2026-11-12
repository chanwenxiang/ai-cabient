# 四端审计 R19 报告 · 全量横切（2026-11-12）

> **性质**：**全量**（非增量）——不设 diff 锚点，按 6 条横切轴覆盖全仓：①接口授权矩阵 ②持久层与迁移 ③金额与校验 ④定时任务/异步 ⑤三端前端 ⑥基建与监控。
> **规模**：111 Controller / 291 Service / 177 Mapper / 450 Flyway 迁移 / 83+36+23 前端页面 / 46 个 `@Scheduled`。
> **方法**：6 路只读子代理全量扫 + 主审对**全部 P0/P1/P2 逐条重读源码或实跑只读命令复核**。
> **标记**：✅=主审亲验（读码或实跑）｜◐=子代理提出、主审未逐条复验。
> **总账**：**28 条 = P0×1 + P1×4 + P2×9 + P3×14**；另列 9 条待定。

---

## 一、P0（阻断部署）

### R19-1 ✅ `infra/docker-compose.apps.yml` YAML 已损坏，生产部署链必然失败

- 文件第 12-18 行：`services:` 下出现了 4 空格缩进的裸 `logging:` 块（提交 `2d8d525b` 补日志轮转时插错位置）。
- 实测：`docker compose -f docker-compose.yml -f docker-compose.apps.yml config --quiet`
  → `while parsing a block mapping at line 5: line 18, column 3: did not find expected key`。
- 影响：`scripts/deploy-prod.sh:124` 正是 `-f docker-compose.apps.yml`，staging/ha/prod-managed、xxljob 同一套链 ⇒ **当前任何一次生产部署都会在 compose 解析阶段失败**。
- 现有 `check-compose-*.mjs` 全是正则扫文本、不做 YAML 解析，所以整批门禁假绿。
- **修法**：删掉该段（logging 只对 service 生效，写在 `services:` 下必须挂在具体服务上）；CI/预检加 `docker compose -f infra/docker-compose*.yml config -q`。
- **验证**：`docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.production.yml -f docker-compose.prod-server.yml config -q` 返回 0。

---

## 二、P1

### R19-2 ✅ 充值赠送不随退款冲回（开关型铸币）

- 赠送：`PaymentService.java:1297-1305` `applyRechargeBonus` → `balanceLedgerService.change(uid, +bonusCents, "RECHARGE_BONUS", …, "recharge-bonus:"+orderId)`。
- 退款：`PaymentService.java:1044-1057` `debitForRechargeRefund` 只扣 `-order.getAmountCents()`，**没有**任何 `RECHARGE_BONUS` 冲正。
- 默认 `RECHARGE_BONUS_PERCENT=0`（关闭），一旦运营开启（如 20%）：充 100 → 到账 120；全额退款 → 只扣 100 ⇒ 净得 20 分，可重复循环。
- **修法**：退款时按键 `recharge-bonus-refund:{orderId}` 反向扣同额（禁止余额不足则拒退）；或退款前校验 `amount+bonus` 可扣。
- **验证**：单测「充值+赠送+全额退款」后余额应回到退款前；SQL `ΣRECHARGE + ΣRECHARGE_BONUS − ΣRECHARGE_REFUND − ΣBONUS_REFUND = 0`。

### R19-3 ✅ 运营越权：机具账单可跨商户读写，分成比例即出账金额

- `OpsMachinePayeeBillController.java:36-79`：`GET` 列表只校验 `ops:device:list`、`PUT /config/{deviceId}` 只校验 `ops:device:edit`；
  `MachinePayeeBillService.saveConfig(String deviceId, cfg)`（`:188`）**不收 operatorId**，全仓无 `requireDeviceAccess`。
- 后果：只绑商户 A 的运营可读全平台账单（含金额）、可把他柜的 `shareBps/electricAmountCents` 改成 0——这两列正是 `generateMonthly` 的出账依据。
- **修法**：service 签名带 operatorId，写前 `merchantScopeService.requireDeviceAccess`，列表按允许设备集过滤。
- **验证**：商户 A 运营 token 对商户 B 的 deviceId 调 `PUT /api/v2/ops/admin/machine-payee-bills/config/{id}` 应 403。

### R19-4 ✅ 生产 nginx 未屏蔽 WebSocket 设备 token 进 access log

- `infra/gateway/nginx-full.conf` 里 `ws_noargs` 出现 **0 次**；`nginx.compose.conf` 有 3 处（AU-190 只修了 staging 用的那份）。
- 设备 WS 走 query 传 token（`jiangyi-gateway/config/WebSocketConfig` 注释自认），TTL 7 天 ⇒ 生产 access log 长期落盘长效设备凭证，拿到日志即可冒充设备。
- **修法**：把 compose.conf 的 `log_format ws_noargs` + `access_log … ws_noargs` 抄入 nginx-full 的 `/websocket/` location。
- **验证**：`curl -H 'Upgrade: websocket' '…/websocket/device/x?token=abc'` 后 `tail access.log | grep -c 'token='` 应为 0。

### R19-5 ✅ dev 栈对局域网暴露 80 且开启万能登录码

- 实测 `docker port ai-cabinet-gateway-1` → `0.0.0.0:80`（IPv4+IPv6 全开）。
- `docker-compose.full.yml:235` `AICABINET_MOCK_ENABLED:-true` + `application-dev.yml:5` mock-enabled + `application-dev.yml:9` `mock-code: 123456`。
- 后果：同网段任何人可对**任意手机号**用 `123456` 登录消费者/管理端，读取真实经营数据、走 mock 充值。
- **修法**：网关端口改 `127.0.0.1:80:80`；mock 渠道收紧为回环或加一次性口令。
- **验证**：`docker port` 只回环；异机 `curl` 失败。

---

## 三、P2

### R19-6 ✅ 资金调账缺上限（三处同类）

| 端点 | 文件:行 | 现状 | 对照 |
|---|---|---|---|
| 消费者余额调账 | `AdjustBalanceRequest.java:6` → `OpsMemberFinanceAdminService.java:125` | `int deltaCents` 无 `@Min/@Max` | 同语义 `WalletAdjustRequest` 已限 ±100 万元 |
| 线长资金池 | `LineManagerController.java:109` → `LineManagerService.java:335` | `long amountCents` 无界 | 同上 |
| 平台资金池 | `OpsPlatformFundController.java:76` → `PlatformFundService.java:152` | 裸 `Map` body | 同上 |

判据：`deltaCents=Integer.MAX_VALUE` / `amountCents=9e18` 应 400。修法：统一走带上下限的 DTO + 审批阈值。

### R19-7 ✅ 资金一致性巡检吞异常 → 假绿

- `DataConsistencyService.java:214-252`：`catch (Exception e) { log.error("数据一致性巡检中断", e); }` 之后**继续**返回当前 FAIL 数。
- 后果：任一条检查 SQL 崩掉，剩余 20+ 项检查全部跳过，而任务结果仍是 SUCCESS；正是资金对账最需要报警的地方。
- **修法**：中断计入失败（返回 -1 或抛出），`finish(FAILED)`；`ScheduledTaskService` 侧已有 FAILED 记录能力。

### R19-8 ✅ XXL 调度台对 8 个任务恒绿

- `ScheduledTaskXxlJobHandler.java:254-273`：只要 `action().run()` 不抛异常就 `handleSuccess`。
- 8 个任务体 catch 后只写 `finish(FAILED)` 并 `return`（如 `UnpaidOrderScheduler.java:59-64`、`ReconciliationScheduler:66-70`、`MachinePayeeBillJob:39-42`、`DepositMonthlyRefundJob:36-39` 等）⇒ XXL 侧永不失败、`executor_fail_retry` 不生效。
- 叠加 `infra/xxl-job/seed_aicabinet_jobs.sql`：`alarm_email` 全空 ⇒ 失败既无重试也无通知。
- **修法**：handler 读 `last_result` 决定 `handleFail`；补告警邮箱。

### R19-9 ✅ FAILED 冻结清扫：吞异常 + 外层事务 → 批次回滚但看板记 SUCCESS

- `SessionExpireService.java:148-196`：方法级 `@Transactional`，循环内 `releaseIfFrozen`（同为 `@Transactional`）抛错被 per-session `catch` 吞掉 ⇒ 共享事务已被标 rollback-only；
  结束后 `finally` 里 `finish(SUCCESS)` 以 REQUIRES_NEW **先提交**，外层提交再抛 `UnexpectedRollbackException` → 整批释放回滚、看板绿。
- 后果：FAILED 会话冻结资金继续悬挂（与「审计 P2-4 清扫器」的目标相反），每 5 分钟重复一次。
- **修法**：去掉方法级事务，逐会话 `REQUIRES_NEW`/`TransactionTemplate`；`finish` 结果按实际释放数。

### R19-10 ✅ 商户待办四卡漏计后端会写的异常类型

- 字典已种 `SETTLEMENT_FAILED / SETTLEMENT_STUCK / BALANCE_INSUFFICIENT / LATE_RECOGNITION / RECOGNITION_UNAVAILABLE`（`SysDictBootstrap.java:309/392/395-396`）。
- `merchant-mp/src/utils/todo-list.ts:248`：争议卡只认 `t === 'DISPUTE' || t.startsWith('RECOGNITION')` —— `LATE_RECOGNITION` 以 `LATE` 开头不匹配；其余类型不属于任何卡。
- 商户异常列表接口不按类型过滤 ⇒ 「全部」有行、四卡与 tab 徽标不计，MA-030 同形。
- **修法**：未知类型归「其它」并计入总数；`todo-list.test.ts` 的全类型断言改为从字典枚举取全集（避免新增类型不红）。

### R19-11 ✅ 采购收货 int 溢出可抬库存

- `ProcurementService.java:514`：`int stockDeltaPieces = deltaCases * packQty`；入参无 `@Valid`，`packQty` 仅 `> 0`。
- `orderedQty=100000 × packQty=50000` → int 回绕成正数，库存被凭空抬高。
- **修法**：`Math.multiplyExact(long)` + 件数上限；DTO 补 `@Max`。

### R19-12 ✅ 两张核心表缺索引（大表 Seq Scan）

- `cabinet_order_line` **无 `order_id` 索引**（详情页、件数汇总按 orderId 查）。
- `cabinet_order` 无 `(status, created_at)` 索引（待办/超时单扫描）。
- **修法**：`CREATE INDEX CONCURRENTLY`；判据 `EXPLAIN` 由 Seq Scan 转 Index Scan。

### R19-13 ◐ 116 个金额列缺 `>= 0` CHECK

- 全库仅 44 个 CHECK、9 个含 `>=0`（库存表有）；`cabinet_order.total_amount_cents`、`line_withdraw_request.amount_cents` 等无约束，代码侧回绕/负数只能事后发现。
- **修法**：分批 `ADD CONSTRAINT … CHECK (col >= 0) NOT VALID` 后 `VALIDATE`。

### R19-14 ◐ 分页与列表上限不一致

- `MerchantWalletController.java:50` → `MerchantWithdrawService.java:160` 无 clamp；`WalletLedgerMapper.java:30`（补货员视图）无 LIMIT。同类方法已钳 200。
- 后果：`limit=1e8` 拉全账本 / 单次响应超时。

---

## 四、P3（一行一条，均已定位）

| # | 位置 | 问题 |
|---|---|---|
| 1 | `V360__deposit_auto_refund.sql:19` | `UPDATE … SET charge_mode='OFFLINE'` 无 WHERE，增量库上会把已 ONLINE 的押金静默改为线下 |
| 2 | `balance_refund_request.idem_key` | 无唯一索引（其余 10 个幂等列均有），DB 层无最后防线 |
| 3 | `DisputeTicketMapper.java:177` | `inSql` 字符串拼接（仅手工转义），应改 `<foreach>#{d}` |
| 4 | `WarehouseInTransitMapper.java:95` | `<foreach>` 缺空集合守卫，`deviceIds=[]` 生成 `IN ()` |
| 5 | `admin_audit_log` | 缺 `(target_type,target_id,created_at)` 索引，事故/订单详情每次全表扫 |
| 6 | `admin-vue/config/menu.ts:611-618` | `/print` 只认 `ops:warehouse:list`，页面实际还读 sku/procurement ⇒ 权限码错配，403 被吞成空目录 |
| 7 | `consumer-mp/pages/index/index.vue:786…` | 开门倒计时硬编码 90s，后端已改 60s ⇒ 60-90s 间提示「可安全取消」 |
| 8 | `admin-vue/api/client.ts:65-75` | 下载通道无超时（无 AbortController），连接挂起时按钮永久转圈 |
| 9 | `PointsExpiryScheduler.java:135-171` | 部分结转不标记 EARN 行，先扣 5 后补 100 再扣 10 ⇒ 累计多扣 5 |
| 10 | `GrowthLogArchiveScheduler.java:37-121` | 单个 `@Transactional` 包住全量删除循环（首次跑删 6 个月日志），长事务锁表 |
| 11 | `monitoring/prometheus/alert_rules.yml:42-51` | `heap used/max` 按 pool 逐序列除，G1 部分 pool max=-1 ⇒ 告警表达式结果为空（永不触发） |
| 12 | `infra/docker-compose.full.yml:442-458` | dev 网关无 `restart`（R15-M1 只改了 base 文件） |
| 13 | 备份链 | 备份失败无告警、无 WAL/PITR（RPO=24h）、xxljob-mysql 未备份 |
| 14 | `full.yml:162` 等 | trade-service 等无 `mem_limit`/无资源限制（O8 压测曾打崩 Docker） |

---

## 五、待定（子代理提出、需数据或产品口径才能定级）

1. `OpsPayoutAccountController.java:54-94`：`ownerId` 由 body 传入、无 `requireMerchantAccess` —— 若 `ops:merchant:edit` 未按商户拆分，则可为他人商户设置默认收款账户（资金改道）。需查 `ops_role/ops_user_role` 实际授权粒度。
2. `DeviceTokenController.java:62-90`：仅凭 deviceSn 换 7 天设备 JWT（SN 可枚举），现有缓解=激活态+BOUND+突发告警。
3. `member.total_spent` / `member_level_rule.min_spent` 为 DECIMAL 元，而 DTO 其余字段为分 ⇒ 运营按分填门槛会放大 100 倍（等级/积分失效）。需产品确认是否刻意。
4. `GatewayWebConfig.java:44-46`：`/jiangyi/api/domainConfig/**` 免鉴权无限流，可探测部署域名。
5. `SchedulingPoolCapacitySelfCheck.java:198-233`：判据未计 `finish()` 的第二个连接与 XXL 线程，启动瞬间峰值可达 16-32（Hikari 20）。
6. `DeviceCommandTracker.java:331-350`：device-service 多实例各自扫描同一 zset ⇒ 重复超时通知；`runAsync` 用 commonPool 无界。
7. `SessionWatchdog.java:53-65`：30s 租约无 fencing，超时后 finally 仍可能删别家锁。
8. `InventoryLotService.java:155/567/583`、`DeviceSlotService.java:582`：`LocalDate.now()` 依赖 JVM 时区（只靠 Dockerfile TZ 兜底），非 +08 环境临期判定差一天。
9. `infra/docker-compose.prod-server.yml:22-24`：`minio/mc:server-pin` 疑为非真实 tag（未联外网核实）⇒ 生产 minio-init 可能拉不到镜像。

---

## 六、子代理「已核对无问题」（供下轮划界，主审抽验）

- **接口授权**：trade `/api/v2/**` 与 gateway `/jiangyi/api/**` 身份一律取 `ATTR_USER_ID/ATTR_DEVICE_ID`，全仓无 query 取身份；消费者面 10 组控制器服务层均校验归属；商户面统一走 `MerchantScopeService`；`/api/v2/ops/**` 写端点权限注解齐全（`/rbac/me*` 为自助面，非缺陷）；`/internal/**` 三服务恒定时间比对 key；dev/mock 端点三重闸。
- **持久层**：`${}` 注入面 0 处；4 个动态 SQL 均 `<script>` 包裹；`@Update` 全带 WHERE；提现/券/短信/库存 version CAS 均校验 rows；分页 100/500 clamp 覆盖；动态表名走白名单。
- **金额**：订单行/合计 long 累加带列域校验；分账 `platform=floor(gross*bps/10000)`、`merchant=gross-platform` 恒等；退款拆腿与余额退款切片 Σ=申请额；提现手续费封顶；金额链路无 double/float。
- **定时任务**：`LineCommissionJob`（事务在锁内按设备-日）为最佳范式；`ScheduledTaskService` 租约 owner 校验；`ScheduledTaskStaleMonitor`；38 个 XXL job 已登记；生产者侧无 `@Async` 自调用；唯一 Kafka consumer 有幂等键+DLT。
- **前端**：三端 `v-html/innerHTML/rich-text` 0 处；`console.log` 0 处；admin JWT 仅 sessionStorage；`/100` 全部整数拆分；优惠四件套三端一致；路由↔菜单 diff 无死链。
- **基建**：`.env`/`grafana.htpasswd` 未入库；prod 关 mock/springdoc/actuator；JWT/内部 key/DB 口令弱默认被启动校验硬拒；pom CVE 覆盖齐全；六卷持久化与日志轮转到位。

---

## 七、建议修复顺序（本轮只出报告，交由其他 AI 实施）

1. **R19-1 单独一笔立即修**（部署链断了，其它修复无法上线验证）。
2. **R19-2/R19-3 + R19-6**（资金面：铸币开关、越权改分成、调账无上限）——同一批，配「越权 403」「超限 400」钉测。
3. **R19-7/R19-8/R19-9**（假绿三连）——统一「任务结果以业务结果为真」，加一条门禁：任务体 catch 后不重抛必须写 FAILED 且有告警。
4. **R19-4/R19-5**（暴露面）——nginx-full 抄 log_format；dev 端口收回环。
5. **R19-10 ~ R19-14 + P3 表**按迭代并入。

---

## 八、处置记录（2026-11-12 落地批次）

> 编号 R19-15 起为本轮新增（原「待定 1~9」在复核后逐条定级）。判据一律「先说清为什么，再改」，改动均带钉测或门禁。

| 编号 | 原条目 | 判定 | 处置 |
|---|---|---|---|
| **R19-15** | 待定 1：`OpsPayoutAccountController` 的 `ownerId` 由 body 传、只查功能码 | **P1 真缺陷** | `ops:merchant:edit` 是功能码，不含商户维度 ⇒ 管 A 商户的运营可拿 `ownerId=MCH-B` 改 B 的默认收款账户（资金改道）。补 `MerchantScopeService.requireMerchantAccess`；线长域**有意不同**（现网无「运营↔线长」数据范围表，注释写明）。钉测 `OpsPayoutAccountScopeTest` 5 例（越权 403 且服务层零调用）|
| **R19-16** | 待定 9：`minio/mc:server-pin` 疑非真实 tag | **真缺陷** | 「跟服务端 pin 一致」这句备注被当成了 tag，生产 `minio-init` 必然 pull 失败 ⇒ 桶与生命周期策略从未初始化。改为与 apps/full 同一 digest；新增门禁 `check:compose-image-tags`（自带 10 例负向自测，每跑先自证） |
| **R19-17** | 待定 8：`LocalDate.now()` 依赖 JVM 时区 | 非缺陷（环境已固化） | 容器 2026-10-14 起 `ENV TZ` + `/etc/localtime`。仅给 ENTRYPOINT 补 `-Duser.timezone=Asia/Shanghai`（防换 base image / tzdata 被裁）；60 处调用点**不动**（环境固化优先于大规模改造） |
| **R19-18** | 待定 6：`DeviceCommandTracker` 多实例重复扫描 + commonPool | **真缺陷** | ①`expireCommandsRedis` 改**抢占式**：用 `ZREM` 返回值当令牌，只有真正摘掉成员的实例才处置；②超时通知从 `CompletableFuture.runAsync`（commonPool、无界）换专用有界池（1/2/256 + CallerRuns 背压）。钉测 `DeviceCommandTrackerClaimTest` 2 例 |
| **R19-19** | 待定 7：`SessionWatchdog` 30s 租约无 fencing | **真缺陷** | 锁值从常量 `"1"` 改 UUID token，解锁用 Lua 比对（`get==ARGV[1]`）。原实现里扫超 30s 的实例 finally 会**删掉别人的锁**，第三实例随即并发进同一批 due |
| **R19-20** | 待定 5：`SchedulingPoolCapacitySelfCheck` 判据偏小 | **真缺陷（漏报）** | 判据从「并发 ≤ 池」改为「峰值 = 并发 × 2 ≤ 池」——一个任务不止一条连接：任务体持外层事务，收尾 `ScheduledTaskService.finish(...)` 是 `REQUIRES_NEW`。静态门禁 `check-scheduling-vs-db-pool` 同步改（两侧同源）。默认 8×2=16 ≤ 20 仍绿；`8 并发 / 10 池` 由假绿转红 |
| **R19-21** | P2：`CouponService` 无 LIMIT 分页 | 半误报 | 分页端点 `listDefinitionsPage` 本就 clamp 100；真正的残留是两个**零调用方**的 `findAll()` 全表方法，已删（留注释说明为何不要复活） |
| **R19-22** | P2：`seed_aicabinet_jobs.sql` 的 `executor_timeout=0` | **真缺陷** | XXL 里 0 = 永不超时，配合 `SERIAL_EXECUTION` ⇒ 悬挂任务永久占住该 job（调度中心无任何报错）。38 行按「周期 ×4~5，120s~3600s」重填；门禁 `check-xxl-job-wiring` 新增规则 3.12（timeout>0 且 ≤ 6×周期），负向注入指名 `id=101 unpaidCancelJob` |
| **R19-23** | P3：`admin-vue` 路由 `restore()` 失败被当「无权限」 | 真缺陷（体验/排障） | 守卫此前忽略 `restore()` 返回值：429/5xx/网络抖动时 `rbacHydrated` 仍 false ⇒ `hasPerm` 全 false ⇒ 甩「无权访问」。新增 `rbac-unavailable` 页（说明「不是权限问题」+ 可重复重试），守卫区分「仍未登录」与「已登录但加载失败」 |

### 复核后判「不改」的条目（与「是缺陷」同样要证据）

- **待定 3（`member.total_spent` 元 / DTO 分混用）**：会员域 `DECIMAL(12,2)` 元与订单域「分」各自内部一致（`BigDecimal.valueOf(cents,2)` 写入、规则表比对同单位），管理端表单已按「元」标注并有注释。改口径的收益为零、风险是历史数据全错。
- **待定 4（`domainConfig` 免鉴权）**：2026-10-20 实证——设备 token 过期后固件以「无 Authorization」探测配置，401 会掐死自愈链（丢配置只能人工到场）；端点只回公网基址。建议后续只补限流与响应字段白名单，**不动鉴权**。
- **待定 2（设备 token 仅凭 SN 换 7 天 JWT）**：现有三重缓解（激活制 + BOUND + 同 SN 突发告警）成立；缩短 TTL 需真机确认「401 后走 domainConfig 自举重领」再动，未验证前不改。
- **P3 `V360` 的无 WHERE `UPDATE ... SET charge_mode='OFFLINE'`**：迁移时刻该列刚由 `ADD COLUMN ... DEFAULT 'ONLINE'` 建出，全部既有行按定义都是「存量线下收缴」，无 WHERE 是**当时正确的全表语义**（注释已写明）；且 V360 已应用，重写会破 checksum —— 不改。

---

## 九、处置记录（P2/P3 续批，2026-11-12 下午）

| 编号 | 条目 | 判定 | 处置 |
|---|---|---|---|
| **R19-25** | P3：编排 healthcheck 缺口 | **真缺口（范围比原判窄）** | 先用「被 `depends_on: service_healthy` 依赖、自己没有 healthcheck」做判据扫全仓 ⇒ **零命中**：apps/full/staging/xxljob/devops 的被依赖方（postgres / trade-service / device-service / xxl-job-mysql / sonarqube-db / sms-webhook-mock）都有 healthcheck。真缺口是 `full.yml` 的 trade-service / device-service 漏配（apps.yml 同镜像同端口却有）⇒ full 栈 `docker compose ps` 恒 `running` 而非 `healthy`。已按 apps.yml 逐字补齐，`compose config` 实测两个 `actuator/health` 生效 |
| **R19-26** | P3：网关缺 `server_tokens` / gzip | 加固（低成本） | 三份配置统一加 `server_tokens off`（默认 on 会把 `nginx/x.y.z` 写进 Server 头与 4xx 页）+ gzip（`gzip_types` 只列文本类型，不压 xlsx/图片；`text/html` 默认总压）。三份都用 `nginx -t` 实测通过（full 走运行中容器、另两份走临时 alpine 容器）|
| **R19-27** | P2：`nginx.conf` 的 `/websocket/` 仍在落 token | **真缺陷（R19-4 同族残留）** | WS 握手 token 在 query string、TTL 7 天；`nginx.conf` 有 `location /websocket/`（151 行）却**没有** `access_log ... ws_noargs` —— R19-4 修了 compose 版、上一轮修了 full 版，这份一直漏着。补齐后三份 `log_format` 逐字一致（md5 相同）。新增门禁 `check-nginx-gateway-parity`：7 条判据（server_tokens / gzip / 三条安全头必须在 **server 级** / body_size 一致 / ws_noargs **定义体**逐字一致 / 有 `/websocket/` 必须有覆盖 / ratelimit zone 集合一致），自带 9 例自测。负向注入删掉 `access_log` ⇒ 指名 `nginx.conf` 变红，还原 md5 一致 |
| **R19-28** | P3：备份失败静默 | **真缺陷（最贵的假绿）** | `backup-postgres.sh` 失败只写 stderr，cron 没配 `MAILTO` 就是完全静默；连续失败 7 天后 retention 把旧备份清空 = **零备份而所有人以为有**。改动：① 任何非零退出走 `alert()`（配 `BACKUP_ALERT_CMD` 时回调 `<cmd> <LEVEL> <MSG>`，钩子自身失败不改变备份结论）；② 每次成功写 `.last-success-epoch`，开场若距上次成功超 `STALE_HOURS`（默认 30h）先发 WARN，让「昨晚就没成」在今晚失败前被看见。实测四路径：失败⇒CRITICAL、48h 前⇒WARN+CRITICAL、1h 前⇒仅 CRITICAL（不误报）、成功⇒exit 0 且写 state；真实 dump 1831KB / 3742 TOC / 179 张表，`pg_restore -l` 可读 |
| **R19-29** | 两份 bash 备份脚本语义不同 | 说明（非缺陷） | `backup-postgres.sh`（生产 cron 实装：平铺目录 + 内建 MinIO + 64KB 下限）与 `backup-database.sh`（AU-189：分时间戳子目录 + `BACKUP_REMOTE_CMD` + 1KB 下限）并存且**互不影响**。已在后者头部写明「改备份行为前先确认改的是哪一份，只改本文件不影响生产」，并给出收敛前提（先核对服务器 crontab）|
| **R19-30** | P2：`DeviceCommandTracker` 全量拉取 | **真缺陷（背压）** | `rangeByScore` 不带 limit ⇒ 一次性把全部到期成员拉成 Set，再每条一次 ZREM + HGETALL，全压在同一条 5s 调度线程。积压 1 万条时单轮几十秒，看门狗自己先超时；积压来源很现实（device-service 停机一天后重启）。改为分批：单批 200、每轮最多 5 批（1000 条/轮、约 2s 上限 < 5s 间歇），未取满即收手。被抢占的成员已不在 zset ⇒ 下一批不会重复，无死循环风险。钉测新增 `fullBatch_triggersAnotherScanRound`（满批 200 ⇒ 恰好 2 次 rangeByScore、200 次 ZREM 且不读 hash）|

### 本批判「不改」的条目

- **`uat-isolated.yml` 六个服务都无 healthcheck**：它是 `-f docker-compose.full.yml -f docker-compose.uat-isolated.yml` 的**端口覆盖层**（文件头明写 `!override` 只改宿主端口），服务定义全部继承 full.yml ⇒ R19-25 补齐后它自动获得 healthcheck，无需重复声明。
- **设备 token TTL 7 天**：仍需真机实证「401 后走 domainConfig 自举重领」再动，未验证前不改（同 §八）。

### 顺手修掉的一条（不在原清单里）

**R19-31 `docker-compose.apps.yml` 的幽灵服务**：`services:` 下有一个**缩进 4 空格**的 `logging:` 块（本意是给 prometheus 的日志轮转，被挤到了 AU-188 注释之后）。compose 会把 `services` 下的每个键当服务名 ⇒ 出现一个名为 `logging`、既无 image 也无 build 的服务。已把它挪回 prometheus 服务块内（归属易误判正是它被挤出去的原因，注释已写明）。


