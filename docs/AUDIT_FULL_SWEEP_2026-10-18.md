# AI Cabinet 全方位审计报告（第四轮 · 2026-10-18）

> 基线：**`d5dee737`**（= `origin/dev` HEAD，本地 dev 落后 15 提交）。
> 审计在独立只读检出上执行：`git worktree add --detach <wt> d5dee737`（本仓 cadmin-uat 与 audit 两个 worktree 并存，主工作树未被读写）；结论分支 `audit/full-sweep-2026-10-18`。
> CI 实证：run **39107673166** @ d5dee737 = `completed / success`（本仓 5 作业）。
> **本轮所有 P0 都在 CI 绿的前提下存在** —— 见 §6「CI 为什么看不见」。
> **同日增补**：§10（FS-04 自我订正为 P3 + 新增资损 P0 **FS-20**，真库实证）、§11（与另一份 AI 报告 AU-136~173 逐条对账）、§12（FS-20 复现命令）。

## 0. 执行口径

- 主工作树 `D:/ai-generated code/ai-cabinet` 有并发会话未提交改动（8 改 + 4 新，含 `infra/docker-compose.production.yml`、两份 Dockerfile、未跟踪的 `docs/PRODUCTION_TOPOLOGY.md` 与 `infra/docker-compose.prod-managed.yml`）⇒ **不在审计范围**，只在 §8 作风险备注。
- 9 路并行只读审计 + 主会话逐条亲验源码。标记含义：
  - **[已亲验]** = 主会话直接读过所引代码行并确认逻辑链。
  - **[路报]** = 并行路给出的 file:line + 片段，未逐条复核 ⇒ 修复前须自行复核（历史上并行路有过夸大）。
- 禁则遵守：未跑 mvn / npm / docker / 网络探测；依赖 CVE、SAST 工具、运行时验证一律未做（§7）。
- **旧文档一律复验不采信**（用户要求）：结论见 §5。

## 1. 覆盖矩阵

| 轴 | 范围 | 状态 | 本轮新发现 |
|----|------|------|-----------|
| A | 后端资金/结算/退款/分账/提现（trade-service 877 主源） | ✅ | 4 P0/P1 资金缺陷 |
| B | `clients/admin-vue`（79 视图 + 路由/菜单/RBAC + 产物新鲜度） | ✅ | 1 P0（三页全员 403） |
| C | `clients/consumer-mp` + `merchant-mp`（仓库内无独立 H5 目录，H5 是这两包的构建目标） | ✅ | 1 P1 功能失效 |
| D | `services/jiangyi-gateway`（25 主源 3009 行 / 11 测试类 88 @Test） | ✅ 首审 | 2 P1 + 7 P2 |
| E | infra（12 份 compose 全读）+ nginx + 监控 + 备份 + Dockerfile + CI | ✅ | 1 P0（prod-server.yml 未入库） |
| F | edge（`edge/` 现仅剩 `device-simulator`，android-app 已随 47188717 退役）+ 旧链残留 + `packages/shared-*` | ✅ | 1 P1（清库脚本指向已 DROP 表） |
| G | 测试有效性 + 门禁完整性（52 个 `check-*.mjs`） | ✅ | 1 P0（IT 恒不执行） |
| H | 安全/隐私（106 控制器 / 409 变更型接口枚举） | ✅ | 1 P0 + 2 P1 |
| I | 旧审计结论回归复验（11 条） | ✅ | 1 条自报 CLOSED 与代码不符 |
| — | 未做：依赖 CVE、SAST、压测、微信合规书面实证、真机/运行时、`prod-server.yml` 内容 | ❌ | 见 §7 |

## 2. P0 —— 上线阻塞（4 条，全部 [已亲验]）

> 增补后本节口径：FS-04 依 §10.1 **降为 P3**（判据有误）；新增资损 P0 **FS-20**（§10.2，真库实证）；并入对方报告复核为真的 P0 **AU-136 / AU-172**（§11.1）。最终上线阻塞序见 §11.4。

### FS-01 按行部分退款把 `refunded_cents` 记两倍 ⇒ 佣金基数、分账、对账系统性错账

调用链（`services/trade-service/src/main/java/com/aicabinet/trade/service/`）：

1. `SettlementPartialRefundService.java:69-72` —— `partialRefund` 先调支付层，再进 prepare：
   ```java
   orderPaymentService.refundOrder(order, refundCents, reason);
   PartialRefundPrep prep = self.preparePartialRefund(order.getOrderId(), refundLines, defaultRestore);
   ```
2. `OrderPaymentService.java:537-551` —— 支付层在**同线程**内完成退款并回刷调用方对象：`prepareOrderRefund`（余额/mock 走 `completeLocalRefund`，`OrderPaymentService.java:807` `order.setRefundedCents(Math.max(0, …) + amountCents)`）；真实渠道走 `finalizeLiveOrderRefund`（`OrderPaymentService.java:649` 同一行累加）；随后 `:551 refreshCallerOrder(order)`。
3. `SettlementPartialRefundService.java:128` —— prepare 在**已被累加**的实体上取基线：`int priorRefunded = Math.max(0, order.getRefundedCents());`
4. `SettlementPartialRefundService.java:140-147` —— finalize 再叠一次本次金额并写入：
   ```java
   int nextRefunded = prep.priorRefunded() + refundCents;      // 已含本次 ⇒ 双倍
   if (order.getRefundedCents() < nextRefunded) { order.setRefundedCents(nextRefunded); }
   ```
   `:141-143` 的 `min(…, charged)` 钳制只封顶到「实收」，1000 分订单退 400 ⇒ 账面 `refunded_cents=800`。
5. 下游放大：`CommissionBaseCalc.java:46` 用 `grossCents(order) - refundedCents` 作佣金基数 ⇒ 基数被蚕食，平台少收；全额退判定、告警、对账口径同步失真。

**为什么 CI 全绿**：`services/trade-service/src/test/java/com/aicabinet/trade/service/SettlementPartialRefundTest.java:90/130/195` 三处
`doNothing().when(orderPaymentService).refundOrder(...)` 把支付层整段桩掉，`:145` 再断言 `assertEquals(700, order.getRefundedCents())`
—— 单测断的是「mock 世界里的正确值」，真实累加路径根本没进过测试。

**触发条件**：任何非运营号段消费者（`OrderPaymentService.java:530` 对 `user_id ≥ OPERATOR_USER_ID_START = 100_000_000`
（`CabinetConstants.java:42`）直接 return，演示账号逃过此劫）做按行部分退款 ⇒ 每笔必中。
**最小修法**：finalize 不信任 `priorRefunded`，改按「实收 − 剩余应退」重算，或 prepare 前先读一次 DB 基线并在
`refundOrder` 返回后跳过重复累加；补一条**不 mock `refundOrder`** 的资金级 IT。

### FS-02 同单同额幂等键碰撞 ⇒ 渠道钱没退、账已退、分账已冲正

`OrderPaymentService.java:597-601`：幂等键只含「订单 + 金额」（注释 M02 明确 reason 不入键）
```java
String idemKey = "REFUND:" + locked.getOrderId() + ":" + refundCents;
if (isCompleted(idemKey)) { return new OrderRefundPrep(..., true, false); }   // skipped=true
```
`OrderPaymentService.java:538-541`：`skipped` ⇒ `refreshCallerOrder(order); return;`（**void 方法，不向调用方返回任何标志**）。
`SettlementPartialRefundService.java:69-74` 对渠道是否真执行毫无感知，继续 prepare → finalize → `:157 adjustSplitAfterPartialRefund`。

后果：同一订单先退 A 商品 400 分、后退 B 商品 400 分 ⇒ 第二笔**微信/余额一分不退**，但订单行被改、`refunded_cents` 被记（叠加 FS-01）、
分账被冲正、状态推进到 `PARTIAL_REFUNDED`/`REFUNDED`，接口回 200。用户实际损失 + 商户多冲。
**最小修法**：`refundOrder` 返回 `skipped`/`channelExecuted` 标志，`partialRefund` 在非「零收款/演示」路径命中 `skipped` 时抛 409；
幂等键加入退款行集指纹（排序后 `sku:qty` 摘要）。

### FS-03 运营后台三个资金/仓储页面全体用户（含超管）不可达

- 路由已注册但**无 `meta.perm`**：`clients/admin-vue/src/router/index.ts:67-78`（`platform-fund`、`device-rental`）、`:247-253`（`warehouse/monthly-close-sheets`）。
- 菜单登记缺失：`clients/admin-vue/src/config/menu.ts` 与 `sidebar.ts` 对这三个 path **0 命中**（对照 `:70 /sales-reports`、`:257 /fund-bills`、`:313 /reconciliation` 均在册）。
- 守卫是 fail-closed：`clients/admin-vue/src/router/index.ts:584-586`
  ```ts
  if (!nav && !metaPerm) { return { name: 'forbidden', … } }
  ```
- 后端能力与权限种子早已就绪：`OpsPlatformFundController.java:31-77`（`ops:fund:*`）、`OpsDeviceRentalController.java:35-108`（`ops:rental:manage`）、`OpsWarehouseController.java:330-404`（close-sheets）；`V341__platform_fund_pool.sql:59-67`、`V342__device_rental.sql:80-88` 已建权限并授予角色 1。

影响：CB-033 平台资金池、CB-034 设备租赁、V327 月结单台账三块功能上线即「隐身」，直接敲 URL 也 403；菜单管理页救不了自己
（`NAV_ITEMS` 是编译期常量，`findNavByPath` 不读 DB）。这与 2026-10-15 那轮 P0 是**同一个模式第 N 次复发**（见 FS-P1-ADMIN-1）。
**最小修法**：`menu.ts` BASE_NAV 补 3 条（perm 取后端读权限 `ops:fund:list` / `ops:fund:list` / `ops:warehouse:list`）+ `sidebar.ts` 图标。

### FS-04 演示超级管理员 `13900000001 / 123456` 的清除只靠一个**仓内没有任何地方设置**的环境变量　⚠️ **本节判据有误，已在 §10.1 订正为 P3，请以 §10.1 为准**

- 弱口令无条件写入：`services/trade-service/src/main/resources/db/migration/V20__user_password.sql:5-9`
  ```sql
  -- 种子账号默认密码 123456（仅开发/预发；生产请修改）
  UPDATE user_info SET password_hash = '$2b$10$wtX2…' WHERE user_id IN (10001, 100000001) …
  ```
  同一 hash 另出现在 V35/V37/V44/V46/V116/V117/V140/V162（**共 10 处，全部无 `${seed_env}` 守卫**；全仓仅 12 个迁移引用 seed_env）。
- 超管角色：`V8__ops_rbac.sql:123` 把 `100000001` 绑到 role 1（admin）。
- 唯一清除点条件在 seed_env：`V344__production_demo_seed_guard.sql:36-39`（`IF '${seed_env}' <> 'none' THEN RETURN`，`:44` 清 100000001-100000011）。
- 默认值是放行：`application.yml:45` `seed_env: ${SEED_ENV:local}`。
- **仓内没有任何地方设 SEED_ENV**：`grep -rn SEED_ENV infra/ scripts/deploy-prod.sh docs/DEPLOYMENT_CHECKLIST.md` = **0 命中**（[已亲验]）。
- 启动校验器不看它：`ProductionStartupValidator.java:79-83` 非 strict profile 直接 `return`；strict 分支全文无 seed 检查（grep `seed` = 0 命中，[已亲验]）。

影响：`SPRING_PROFILES_ACTIVE=prod` 在 `docker-compose.production.yml:12` 有钉（[已亲验]），所以正常路径靠 V344 兜住；但**只要一次部署漏叠
production overlay、或有人在 prod profile 外手工起服务做联调**，公网即留下超管弱口令（TOTP 是账号级可选，该演示号未开）。这不是「已配置好」的问题，
是**防线只存在于口头约定**。
**最小修法**（三条都做）：① 新增迁移（不可改已应用迁移）无条件把演示号段 `password_hash` 置 NULL 且状态不可登录；
② `ProductionStartupValidator` 在 strict profile 增检 `seed_env != none` ⇒ fail-fast；
③ `infra/docker-compose.production.yml` 显式写 `SEED_ENV: none`，并补一条 compose 门禁。

## 3. P1 —— 真实缺陷，建议随上线批修

| ID | 轴 | 结论 | 证据 | 验证 |
|----|----|------|------|------|
| FS-05 | A | 全额退款 void 链的钱包冲正仍带「可用余额」闸 ⇒ 负余额商户退款后 finalize 永久回滚 | `RevenueSplitService.java:451 reverseWalletCredit → MerchantWalletService.java:73 → WalletEngine.java:241` 调 `doDebit`，`:192-204` 余额不足抛 412；对照同文件 `:207-211` T4 修 `clawbackWalletDelta` 用 `debitIfAbsentAllowNegative` 并写明「旧闸会让退款冲正永远失败」——**同缺陷只修了一条路径** | [已亲验] |
| FS-06 | H | 将邑 `classes.txt` 拉取构成 SSRF：URL 来自第三方接口返回值，无协议/主机白名单、无私网拦截、默认跟随重定向 | `JiangyiModelSyncService.java:235-238 publicHttp.get().uri(URI.create(model.modelTextUrl()))`，`:48 RestClient.create()` | [已亲验] |
| FS-07 | H | 商户提现「申请人已实名」可自建账号绕过：团队子账号创建时硬写 `verified=true`，创建者自设密码并绑定自己全部商户 | `MerchantTeamAdminService.java:253 user.setVerified(true)`；门槛只判 `WithdrawEligibilityService.java:98 if (!u.isVerified())` | [已亲验] |
| FS-08 | C | 商户端订单页四项筛选（状态/日期区间/关键字/排除零元单）被后端静默丢弃，恒返回全部订单且无报错 | 前端确实下发：`clients/merchant-mp/src/pages/orders/orders.vue:291-293`、`utils/merchant-api.ts:970-980`；控制器收下不用：`MerchantPortalController.java:294-308`（9 个形参 → `listOrders(userId, page, size, deviceId, merchantId)`）；服务硬编码 null：`MerchantPortalService.java:381-382` | [已亲验] |
| FS-09 | E | 生产编排文件 `infra/docker-compose.prod-server.yml` **从未入库**：`git ls-files` 为空（rc=1），而部署链第 4 步就引用它 ⇒ 生产真实拓扑不可审查、不可复现、不在备份面；脚本 `:99-105` 也无 `test -f` 预检 | `scripts/deploy-prod.sh:103`；`docs/DEPLOYMENT_CHECKLIST.md:140-145` 自述「只存在于服务器」 | [已亲验] |
| FS-10 | G/E | 资金级 DB 守卫 IT **恒不执行而 CI 仍绿**：4 个 trade `*SqlIT`（含 `WalletRefIdempotencySqlIT`、`WarehouseDeviceLedgerBalanceSqlIT`）+ jiangyi `DeviceAuthIT`（13 例激活鉴权负向用例，对应 17966237 事故面）都不在任何 job 的 `-pl` 范围内；守卫脚本对未构建模块直接放行，`ci.yml:432` 注释自认「如 trade-service…跳过，不误红」；而 `check-docker-tests-guarded.mjs:131-167` 却把它们登记为 `job:'integration'`（规则 3 只校验锚点文本存在，不校验 `-pl` 覆盖） | `.github/workflows/ci.yml:132 -DskipITs`、`:394 mvn -B verify -pl services/device-service -am`、`:436-437` | [已亲验] |
| FS-11 | A | 微信分账「回退/查询/轮询」三链仍在 `@Transactional` 内发 HTTP（B2 三段式只改造了 `submitSplit`）；轮询更是单事务内循环外呼 | `WeChatProfitSharingService.java:219 @Transactional returnMerchantShare` → `:254 post("/v3/profitsharing/return-orders")`；`:292/:409` 同形；外层 `MerchantService.java:281→290` 再叠 | [已亲验]（FS-09/10/11 与旧结论一致，见 §5） |
| FS-12 | D | jiangyi-gateway WS 无服务端保活与空闲超时 ⇒ 半开 TCP 下 `sessions` 永不清理、`isOnline` 恒 true（ghost-online），开门指令打进虚空；且**Prometheus 从不采集该模块、无一条告警规则** | `services/jiangyi-gateway/…/config/WebSocketConfig.java:29-34`（无 `ServletServerContainerFactoryBean`）、`ws/DeviceWebSocketHandler.java:148-151`；`infra/monitoring/prometheus.compose.yml:14-30` 仅 trade/device/minio，`grep jiangyi infra/prometheus/alert_rules.yml` = 0 | [路报] |
| FS-13 | D | 顶替竞态误报离线：新会话顶替后，旧会话 `afterConnectionClosed` 里 `sessions.remove(id, session)` 失败仍无条件 `reportOffline` ⇒ 在线设备在 trade 侧被置离线 | `DeviceWebSocketHandler.java:86-97` | [路报] |
| FS-14 | D | 识别上报转发失败被吞、仍回设备 200（设备不再重报），且 `finally` 无条件 `complete(CLOSE)` 撤销 300s 兜底 ⇒ 漏结算/会话悬挂 | `api/DeviceReportController.java:152-161` | [路报] |
| FS-15 | F | `scripts/cleanup-test-data.ps1` 仍指向 **V389 已 DROP 的 6 张旧钱包表** ⇒ `ON_ERROR_STOP=1` + `:324 throw` ⇒ 默认清库模式必失败，`verify-local.ps1` / `mp-seed-gate.ps1` 与 4 份 UAT 文档链路被堵；且改表名后仍缺新表 `wallet_account/wallet_ledger` 的清理面 | `cleanup-test-data.ps1:176-179/258/288-289`；`V389__drop_six_legacy_wallet_tables.sql`；消费者 `docs/BROWSER_MIN_UAT.md:71`、`docs/MASTER_TEST_PLAN.md:67` | [已亲验] |
| FS-16 | I | 分账/GMV 口径与佣金基数**不同源**：`CommissionBaseCalc.java:11-13` javadoc 自己判定 `totalAmountCents` 会被部分退摊薄，而 `RevenueSplitService.java:103/264/307/502` 四处一律取 `order.getTotalAmountCents()` | 同左 | [已亲验] |
| FS-17 | E | 告警下游在生产 profile 里是断的：`alertmanager` + `feishu-alert-relay` 只存在于 `docker-compose.full.yml` 的 alerting profile，base/apps/production 均无 ⇒ 规则触发也没人收；prometheus/grafana/redpanda/emqx **四类都无数据卷**（重启丢告警历史/消费位点/EMQX bootstrap 凭据） | `infra/docker-compose.full.yml:421-445`；`infra/monitoring/prometheus.compose.yml:9-11`；`infra/docker-compose.yml:195-198` 仅 pg/minio/redis 三卷 | [路报] |
| FS-18 | E | 备份体系与生产平台错配：`backup-database.ps1:115-122` `pg_dump` 失败只 `Write-Warning` 不查退出码、`'wal'` 分支只打印提示、Windows 脚本对 Linux 服务器；仓内无 MinIO/Redis 备份作业 ⇒ 可能长期「零备份却显示成功」 | `scripts/backup-database.ps1:115-138` | [路报] |
| FS-19 | G | 人工签收入口可被空轮次冒充绿：`verify-production-readiness.ps1:187-188` 带 `-Dsurefire.failIfNoSpecifiedTests=false`（指定类消失即静默 SUCCESS）；`clients/admin-vue/tests/audit-admin-flows-uat.mjs:2706` 只在 `fail>0` 时退 1 ⇒ 全 SKIP 轮次仍算绿，`:2620` E-01 是无条件 `true` 的恒真断言 | 同左（驱动器「不 SQL 造绿」的纪律本身是好的） | [路报] |

## 4. P2 / INFO（按轴，证据均 [路报] 除非另注）

**A 后端**：取整口径混用产生 ±1 分漂移（`FundBillService.java:136/214` 用 `Math.round(double)` vs `WithdrawFeeCalculator.java:36`、`LineCommissionJob.java:188` 整型截断）；`settleManual` 首次扣款仍在事务内（`SettlementService.java:95-217`，代码注释已声明权衡）；`LineCommissionJob.java:196` 的 `orderCount` 含不可计状态；通知去重仅 Redis 24h TTL（Redis 丢数据可重复推送）。

**B admin**：**[已亲验]** 无「路由↔菜单一致性」回归测试或门禁（`NAV_ITEMS` 零测试引用）⇒ FS-03 会再复发；
`OpsReplenishmentController.java:269 /replenishment/my-tasks` 无 `@RequiresPermissions` 且全仓零消费者；
死包装 5 个（`endpoints.ts:41/200/205/307/570`）、`client.ts:108/173` 死导出；本地 `money()/yuan()` 自建格式化残 4 处
（`DisputeListView.vue:905`、`OrderListView.vue:971`、`MerchantSplitsView.vue:1020`、**`DeviceDetailView.vue:1290-1292`**，最后一条推翻了台账 A6④「15 处已收敛」的自报 CLOSED）；
产物新鲜度**[已亲验]** 无问题：`edbd5c24` 之后 `clients/admin-vue/src` 零 diff，HEAD bundle 与源码同源。

**C 小程序**：CI `mini-programs` job **只跑 `build:h5`，从不 `build:mp-weixin`**（`ci.yml:594-598`）⇒ 真实上架产物零编译覆盖，`patch-mp-weixin-appjson` / `inject-miniapp-env` / `validate-miniapp-env` 五个脚本回归全静默；无「Web-only API 禁入 mp src」门禁（`grep URLSearchParams scripts/*.mjs` = 0，FS-01 那类修复可无阻力回退）；商户 H5 登录页微信按钮无 `#ifdef MP-WEIXIN` 门控（`merchant-mp/src/pages/login/login.vue:101-111/249-282`，消费者端有对照分支）；`infra/README.md:39-40` 承诺的 `/consumer/`、`/merchant/` H5 分发依赖一个**不存在的脚本** `build-mp-h5-gateway.mjs`，三份 nginx 也无对应 location；充值上限提示裸除 100（`recharge.vue:340/429`）应走 `fmtMoney`。

**D jiangyi-gateway**：无优雅停机（`application.yml` 无 `server.shutdown: graceful`，容器级 `stop_grace_period` 对 Spring 无效）⇒ SIGTERM 时全设备 WS 无 close frame 硬断，**这就是隔离栈 `code=1006` 现象的同一机理，代码里不存在「door 事件后主动断链」路径**（此前记为 harness 限制，需据此修正）；WS 容器线程上同步调 trade（`DeviceWebSocketHandler.java:111 → TradeInternalClient.java:40-42` connect 10s/read 30s）；watchdog 分布式锁无属主校验（`SessionWatchdog.java:53-64` 可能删别实例的锁）；握手 JWT 走 query token，nginx 默认 combined 日志会整段落盘（TTL 7 天）；`DeviceTokenController.java:62-96` 免鉴权面无限流、突发仅告警不拒发；死配置 `session-open-timeout-seconds`（`application.yml:31` 全仓零引用）。
正面（[路报]，与既往结论一致且更细）：JWT 验签 fail-closed、拒 `alg:none`/算法混淆、密钥 env-only、吊销走 `token_version`；OSS STS 设备级子目录收窄；四栈纳管齐。

**E infra/CI**：边缘无 TLS/HSTS（所有 nginx 只 `listen 80`）；`deploy-prod.sh:104-105` 只滚 trade-service 且 `| tail -2` 截日志；Dockerfile 基础镜像仅 tag 无 digest、`-DskipTests`、`.dockerignore` 缺 `.env*`/`*.pem` 而 `Dockerfile.java-service:4 COPY . .`（服务器目录即上下文 ⇒ `infra/.env.production` 可能进构建层 **[已亲验风险面]**）；JVM 未设 `MaxRAMPercentage`（`trade-service.Dockerfile:58` 裸 `java -jar` + `mem_limit: 1024m`）；`ci.yml` 无 `concurrency` 块；e2e-h5 两步把 dispute/imp 失败降级 `exit 0`（`ci.yml:842-904`，注释自认该套件 CI 里一条不跑）；`docs/DEPLOYMENT_CHECKLIST.md:33` 等 4 处引用不存在的 `scripts/verify-step5.ps1`。
监控判据澄清 **[已亲验]**：`DevOpsHubService.java:91/186` 的**健康探测走 `DEVOPS_PROMETHEUS_HEALTH_URL`（容器网内 `:157`）**，不受网关缺路由影响 ⇒ 「D3 恒误绿」不成立；但后台展示链接 `:294 DEVOPS_PROMETHEUS_URL=${PUBLIC_BASE_URL}/devops/prometheus` 在生产挂载的 `nginx.compose.conf`（`docker-compose.apps.yml:241`，无该 location，仅 `nginx-full.conf:177` 有）下会 404 ⇒ 降为 P2 展示缺陷。
SCRAM 四处一致、redis AOF+卷、actuator 白名单、端口 loopback 门禁、`JWT_SECRET` fail-loud 均**核对通过**。

**F edge/残留**：`edge/android-app` 已整体退役（`47188717`），`ANDROID_TEST_FILE_BASELINE=14` 失去对象、CI 已升级为方法级 `<testcase>` ≥13 钉死（`ci.yml:303`）⇒ 旧「按文件计数可删方法逃逸」缺陷随退役消失 **[已亲验 grep=0]**。残留清单：`SkuVisionContextItemDto`/`DeviceVisionContextDto`/`UpsertSkuVisionEnrollmentRequest` 三件套孤立；`UpsertSkuRequest.java:32-34` 仍收 `yoloClassName`/`visionEnrollmentStatus` 死字段且已生成进 `openapi.ts:10782`；`SysDictBootstrap.java:182` 死字典 `sku_enrollment_status`（列已被 V340 DROP）；`packages/shared-dict/src/index.ts:961-968` 8 条永不命中的审计动作标签；4 处 javadoc 仍写「edge APK 装机页注入」；`docs/AUDIT_LEDGER_2026-10-13.md:34/36/187` 待办仍挂在已删文件上（会诱使下个会话去「修」不存在的东西）；`packages/shared-dict/dist/dict-options.spec.js` 是**无源码的孤儿编译产物**（已提交）。`packages/shared-*` dist 同步复核：src/dist 同 commit、导出全命中 ⇒ 旧「dist 落后一提交」**本基线不复现**。

**G 测试有效性**：`common-core` 428 主源 **0 测试**；前端基线门只数文件不数用例（`check-mp-unit-tests-wired.mjs:166/173-177`，清空 `it()` 仍绿）；Mockito 桩 abstract 绕 default 的结构性风险面 = 150 个 mapper 接口带 default、`thenCallRealMethod` 全树仅 7 处；时区口径分叉（`InventoryLotService.java:141/262` 用 `LocalDate.now()` 而 SQL 注释钉 Asia/Shanghai，CI runner 是 UTC）；DEFERRABLE 触发器与库级唯一索引**无任何 IT 触达**（`V271:65-68`、`V323:62/79`）。
正面：全树 0 处 `@Disabled/.only/skip:true`，52 个门禁与 package.json/CI 无漂移，`check-migration-safety` 与 `check-competitor-benchmark` 校验的是内容不是文件存在，防假绿守卫只认 `<testcase>` 元素数且零发现即红。

**H 安全（除 FS-04/06/07 外）**：strict profile 缺微信/实名凭据可启动（`ProductionStartupValidator.java:149-183` 降级 WARN）；预 HTTPS 允许 `Secure=false` Cookie（`:234-276`）；员工打款幂等兜底键含 `System.nanoTime()` ⇒ 非幂等可重复出款（`OpsStaffWalletController.java:54-56`）；actuator 与业务同端口（`application.yml:311-315`，外网由 nginx 兜，容器网内未鉴权）；导出类接口无审计留痕（`OpsCsvExportService.java:58-69` 等，对照同文件写操作有留痕）；`SessionInternalController.java:41-46` 缺设备绑定（对照 `VisionInternalController` 有 `DEVICE_MISMATCH`）；**`MerchantScopeService.java:76-94/202-211`「无商户绑定 = 全局可见」是 fail-open**，配合 `OpsRbacService.java:643/661` 可清空绑定 ⇒ 未绑定账号可见并可操作全平台商户；SMS 验证码明文入库（`SmsCodeService.java:163-169`）、openId/IMEI/deviceSn/联系人手机号日志与 DTO 未掩码；提现费率默认值跨文件漂移（`application.yml:222 = 60` vs `infra/docker-compose.apps.yml:127/129`、`.env.example:60/62 = 50` ⇒ 实际按 5‰ 出账，与 6‰ 定价口径不符 **[已亲验]**）。
正面（**[已亲验] 抽样**，且覆盖统计见原文）：76 个 MyBatis XML 零 `${}`、回调验签齐全、无 trust-all TLS、无 fastjson/ObjectInputStream、zip 三重限制、CORS 白名单、Cookie HttpOnly+SameSite=Strict、`ops:admin` 不跨 `merchant:*`；409 变更型接口中 326 个带鉴权注解，83 个不带的已逐条分类、约 35 个完成 Controller→Service 两级人工核验（含全部资金/提现/退款/券/设备类）。

## 5. 旧文档结论复验（用户要求：验证别全部采用）

| 旧结论 | 当前状态 | 证据 |
|--------|---------|------|
| 商户 mp `endpoints.ts:73 URLSearchParams` | **已修复**（仅残留两处类型位 `endpoints.ts:115`、`merchant-api.ts:866`，运行时安全） | `:19/:72` 改走 `withQuery`；`shared-uni/src/query.ts:3/16/25` 手写 query；全仓 `new URLSearchParams` 只命中 admin-vue（合法） |
| jiangyi-gateway 全仓零测试入口 | **已修复** | 11 测试类实存；`ci.yml:266-267` `mvn test -pl … jiangyi-gateway -am`；`:307-339` 零发现即红守卫（但 `DeviceAuthIT` 仍不跑 ⇒ FS-10） |
| `deploy-prod.sh:103` 引用缺失 compose | **仍成立且定性升级** | `prod-server.yml` 从未入库（`git log --all --` 空）⇒ 从「脚本 bug」升为「生产编排不可复现」FS-09 |
| redis AOF 无数据卷 | **已修复** | `infra/docker-compose.yml:32/35/198`（`:28` 注释即「2026-10-16 审计 A5」） |
| 钱包流水无 DB 唯一索引 | **已修复** | `V379:85/89/93` 三域 partial unique → `V388:89-92 uk_wallet_ledger_ref`；⚠️ partial 索引**豁免 `WITHDRAW`** ⇒ 提现生命周期多行仍仅应用层保证，建议补断言 |
| 分账事务内 HTTP | **部分成立**：主链 `submitSplit` 已三段式（`WeChatProfitSharingService.java:70-90`），回退/查询/轮询三链未改 | `:219→:254`（[已亲验]）⇒ FS-11 |
| 通知「先标记后投递」＝失败即丢 | **已修复** | `NotificationDispatchListener.java:47→:51-59 releaseDispatchMark`；`DoorEventDeduplicator.java:14-19` M11 同改（抢占与投递之间被 kill 仍有窗口） |
| 僵尸服务 ×3 | **已修复**（`vision-service` 整目录 `d83a9380` 删除、`edge/android-app` `47188717` 退役、env 模板 VISION_* 计数 0）；`alertmanager/relay` 反而在生产 profile 缺位 | FS-17 |
| `shared-*` dist 落后 src | **勘误作废**（本基线同 commit、导出全命中） | §4-F |
| 业务轮 T1–T7 自报 CLOSED | **7 条主链源码复核为真**；T2 只关了佣金侧，分账侧不同源 | FS-16 |
| 台账 A6④「15 处自建 `yuan()` 已收敛」 | **与代码不符** | `DeviceDetailView.vue:1290-1292` 仍是本地 `function yuan`，而 `display.ts:81` 注释正是为禁它写的；`check-money-yuan-prefix.mjs:38` 只识别 import 的 `yuanText*`，把本地定义写成了测试 fixture（`:162`）⇒ 门禁盲区 |
| DEV-03「20 条 AU 修复一条没进主干」 | **已过期** | `grep -rhoE "AU-[0-9]{3}" services scripts infra` 21 个编号全在，`FundKeyGuard` 测试与 `check-env-placeholders.mjs` 均在，AU-133 已落 `V369__jiangyi_device_identifier_nullable.sql`，迁移重号扫描为空 |

**取证入口勘误**：`origin/docs/audit-findings-2026-10-16` 这个 ref **不存在**（`git ls-tree` fatal）；实际 docs ref 是 `origin/audit-docs`，
且 2026-10-16 四份审计文档已在基线工作树 `docs/` 内。旧记忆中「工作树干净是起跑闸门」本轮无法满足（peer 有 12 处未提交）⇒ 已改用 detached 干净检出锚定 d5dee737，结论不受脏树影响。

## 6. CI 为什么全绿还留着这些洞（4 类结构性原因）

1. **支付层被整段 mock**：FS-01/FS-02 的错账只出现在「真实累加 + 真实幂等键」的组合里，单测把 `refundOrder` 桩成 `doNothing`。
2. **IT 不在 job 的 `-pl` 范围内**：守卫「跳过未构建模块」= 资金级 DB 防线（`WalletRefIdempotencySqlIT`、`DeviceAuthIT`）恒不跑而 job 仍绿（FS-10，`ci.yml:432` 注释自认）。
3. **只数文件不数断言/用例**：`check-mp-unit-tests-wired.mjs:173-177`（数文件）、`admin-artifacts`（只比字节，不比路由↔菜单语义）⇒ FS-03 这类「登记缺失」没有任何机器判据。
4. **默认值放行 + 口头配置**：FS-04 的防线键在 `SPRING_PROFILES_ACTIVE` 的**字面量**上（profile 漏钉 ⇒ 种子清理与 fail-loud 两道防线同时失效，见 §10.1），FS-09 的编排是服务器上那份没入库的文件。

## 7. 未覆盖 / 无法判定（诚实清单）

- 依赖 CVE 扫描、SAST taint 分析、Sonar quality gate 实际阈值（远端配置）；JaCoCo 真实行覆盖率未跑（root pom 仅 report 无 `check`）。
- 运行时与网络探测一律未做（按只读约束）：JWT 时钟偏移、Redis 是否鉴权、CORS 实际响应头、nginx TLS/HSTS、EMQX ACL、Xxl-Job 控制台口令、备份真实执行记录。
- `infra/docker-compose.prod-server.yml` 内容不可见 ⇒ 生产真实编排的全部判断只能证「它不在仓」。
- 第三方侧：将邑/微信/支付宝证书轮换与限频、OSS 桶实际生命周期（90 天规则是文档记载，非仓库证明）；微信分账比例上限书面实证（§12 #7）仍无人发起。
- 逐行覆盖率口径：admin 79 视图实开约 14 个；44 个 ops 控制器逐方法核字段者 6 个；两端 mp 的 45 个页面多数仅经 grep；172 处 default-method 桩点为启发式匹配，需人工抽验。
- 2026-10-19 真将邑柜复跑（F1 结算链 + F2/F3 真柜面）未在本轮范围。

## 8. 建议修复批次（按「一行改 → 资金健壮 → DevOps」）

**批次 1 快赢（当天可完成，不动架构）**
1. FS-03：`menu.ts`/`sidebar.ts` 补 3 条登记 + 新增「路由↔菜单一致性」单测与门禁。
2. FS-15：`cleanup-test-data.ps1` 改指 `wallet_account/wallet_ledger`（顺带补齐缺的新表清理面）。
3. FS-04（订正后为 P3，见 §10.1）：新迁移收口 V20 演示口令 + `-f` 栈 profile 门禁 / validator 增检「prod 库内仍存在演示号段 ⇒ 启动失败」。
4. FS-08：控制器把 `status/from/to/keyword/excludeZero` 透传（`isExcludeZeroParam` 已存在）。
5. 提现费率默认值 50/60 对齐为单一来源（拍板 6‰）。

**批次 2 资金健壮性（须配资金级 IT）**
6. FS-01 + FS-02 同批修（同一函数体），并补一条**不 mock `refundOrder`** 的 `*SqlIT`，同时把该 IT 纳入 CI 范围（FS-10）。
7. FS-05：`reverseWalletCredit` 改 `debitIfAbsentAllowNegative`（与 T4 同构，幂等 refId 不变）。
8. FS-16：分账/GMV 四处 `getTotalAmountCents()` 统一走 `CommissionBaseCalc`，补跨链钉住用例。
9. FS-07：`doCreateTeamUser` 写 `verified=false`，或提现门槛改判商户主体实名联系人。

**批次 3 DevOps / 可观测**
10. FS-09 + FS-10 + FS-17 + FS-18：prod-server.yml 入库（或制品化 + sha256 校验）、integration job 扩 `-pl`、alerting 组件进生产 profile、prometheus/grafana/redpanda/emqx 补数据卷、备份脚本改 Linux cron + 检查退出码。
11. FS-12~FS-14（jiangyi WS 保活/顶替竞态/上报吞错）、FS-06 SSRF 白名单、FS-11 三链三段式改造。

**批次 4 上线前人工必答**
12. 真将邑柜 F1 复跑（2026-10-19）、`build:mp-weixin` 纳入 CI 一次实证、微信分账比例上限书面确认（§12 #7）。

## 9. 与既有台账的关系

本文是 2026-10-16 closeout 之后的**第四轮全角度体检**，与 `docs/AUDIT_LEDGER_2026-10-13.md`（AU 系列）、
`docs/TRI_CLIENT_BACKLOG_2026-10-15.md`（T/A/B 系列）并行编号 **FS 系列**；§5 已把可关闭的旧结论标出、把两条自报 CLOSED 证伪（A6④、DEV-03）。
建议把 **FS-01~FS-20**（FS-04 以 §10.1 订正后的 P3 口径入库）与 §11.1 并入的 AU 条目一并收进主台账 `docs/AUDIT_LEDGER_2026-10-13.md`，并同步 `docs/PROJECT_KNOWLEDGE.md §10/§12`。

---

## 10. 增补 A（同日晚）：FS-04 自我订正 + 新增 FS-20（真库实证资损 P0）

### 10.1 ⚠️ FS-04 原判据有误，降级为 P3（机制订正 + 一条门禁建议）

原报告称「演示超管 `13900000001 / 123456` 的清除只靠一个仓内没有任何地方设置的环境变量 `SEED_ENV`」。**这是漏读了一处硬编码**：

- `services/trade-service/src/main/resources/application-prod.yml:31-32` 把 Flyway placeholder `seed_env` **写死为 `none`**（不依赖环境变量）；
- `V344__production_demo_seed_guard.sql:42-44` 的 `_demo_user` 覆盖 `generate_series(100000001, 100000011)` 与 `10001/10002/13800138000` ⇒ profile=prod 时演示号段连同 `user_account`/`ops_user_role` 一并删除；
- `infra/docker-compose.production.yml:12` 钉 `SPRING_PROFILES_ACTIVE: prod`；另一会话未提交的 `infra/docker-compose.prod-managed.yml` 是**叠加层**（其文件头用法把 production.yml 一同列在 `-f` 栈里），不绕过该 profile。

**订正后仍成立的风险（P3，但要一条门禁钉住）**：整套生产防线键在 **profile 字面量** 上——`V20__user_password.sql:5-9` 无条件给 `10001/100000001` 写 `123456` 的 BCrypt 哈希；`ProductionStartupValidator.java:301` 的强校验是 `"prod".equalsIgnoreCase(profile)`；`docker-compose.apps.yml:62,168` 默认 `${SPRING_PROFILES_ACTIVE:-dev}`。⇒ 任何**漏钉 profile** 的叠加层会同时失去「种子清理」与「fail-loud」两道防线，且失败方式是静默用 dev 配置跑生产。
建议（批次 3）：① 新迁移把 V20 的演示口令写入改为 seed 守卫（或对这两个 user_id 置空 hash）；② 门禁校验「`-f` 栈里出现 apps.yml 就必须显式 `SPRING_PROFILES_ACTIVE=prod`」，或在 validator 加一条「prod 库内仍存在演示号段 ⇒ 启动失败」。

### 10.2 🔴 FS-20（P0，资损，真库实证）余额退款「续退补扣」单轮双扣余额

- 复现物证（Testcontainers + 空库 Flyway 迁到 V389，`profile=test`）：
  `[FS-20] 补扣后余额=9200 流水行=[{operation_type=BALANCE_REFUND, amount_cents=400, balance_before_cents=9600, balance_after_cents=9200}] 申请状态=PROCESSING`
  ⇒ 应扣 400（10000→9600），实扣 800（10000→**9200**），流水只留一条 400。
- 根因（源码级）：`BalanceRefundService.debitRemainingBalance:477-488` 先 `account.setBalanceCents(before - cents)` + `accountMapper.save(...)`（`BaseTradeMapper.save:60-73` ⇒ 立即 `updateById`），**再**调 `balanceLedgerService.change(userId, -cents, ...)`；而 `BalanceLedgerService.doChange:60-79` 是**会自行改余额**的原语（`findByIdForUpdate` 读到的正是刚被手动扣过的行，`next = before + delta` 再扣一次）。
  对照：首轮扣款 `debitAccountForApprovedRefund:621-637` 配套的是 `recordFreezeOnly`（`doRecordFreezeOnly:110-133` **只写流水、不动余额**）。⇒ 姊妹路径用审计型原语、续退路径误用变更型原语，这是它一直没被发现的原因。
- 对账发现不了：用户侧资金巡检判据是 `DataConsistencyService:487-501`「余额 vs 最近一条 BALANCE 流水的 `balance_after`」，双扣发生在 `doChange` 内部 ⇒ 流水尾值恰等于最终余额 ⇒ **巡检恒绿**。「Σ流水 = 余额」的全史公式只做在商户/线长钱包（`:673+/:712+`），用户余额没有。
- 触发面：`PARTIAL` 续退且两轮之间新出现可退充值单（争议期用户再充值，或上一轮额度不足只规划了部分）。
- 测试面：`grep -rn "resumePartialRefund|BALANCE_REFUND_RESUME" services/trade-service/src/test` **零命中** ⇒ 该链完全无测试。
- 修法：删掉手动扣减，补扣只走 `change(-cents)` 单一原语；幂等键加轮次维度（`RESUME:{requestNo}:{attempt}`）或改用本次失败切片的 `outRefundNo`。复现钉已落 `services/trade-service/src/test/java/com/aicabinet/trade/integration/BalanceRefundResumeDebitSqlIT.java`（带 `@Disabled`，修复后去掉注解即转回归测试）。复跑命令见 §12。

## 11. 增补 B：与另一份 AI 审计报告（AU-136~AU-173）的逐条对账

> 对方基线 = 本地 dev `28c9f99a`；本报告基线 = `origin/dev d5dee737`（+15 提交）。下表所有条目我都在 d5dee737 **逐行读过源码**；FS-01/02/03/20 亦在 `28c9f99a` 复核存在 ⇒ 分歧属覆盖差，不是基线差。

### 11.1 对方独有、我复核为真 ⇒ 并入本轮清单（沿用其 AU 号）

| AU | 我的复核结论（d5dee737 锚点） |
|----|------|
| AU-136 **P0** | 成立。`AuthController.java:151-156` 原样传 `request.phoneNumber()` → `AuthService.wxH5Login:250-252` 直接进 `loginOrCreateByOpenId(openId, phoneNumber)`；对照 C 端 `resolveWxPhoneNumber:231-247` 有 C13 门（无 phoneCode 的明文手机号只在 mock 下放行，否则 400）——H5 整条绕开：已知手机号 ⇒ 绑 openid ⇒ 发正式 token。 |
| AU-172 **P0** | 成立。`SlotTemplate.java:13-14` 的 `@TableId(type=IdType.AUTO) Long templateId` 推得列名 `template_id`，而 `V368__slot_template.sql:18` 主键叫 `id` ⇒ 模板 CRUD 500。纯 Mockito 单测拦不住。 |
| AU-173 **P1** | 成立。`ScheduledTaskXxlJobHandler.runKey:250` 只 `descriptor.action().run()` 自身不加锁；`ScheduledTaskRegistry:133-137` 这两个任务的 action 直挂 `runDailyCheck`，而 `PlatformFundService:256` / `DeviceRentalService:536` 内部无 `tryBegin`（全仓 37 处 `tryBegin`，这两条链 0 处）⇒ `scheduled_task.enabled=false` 与 `job:*` 锁双双被旁路，上线手册 `d8563c6b`「资金定时任务当天保持关」的依赖不成立。`DeviceRentalService.runDailyCheck` 确为 `@Transactional`。 |
| AU-138/139 **P1/P2** | 成立。`clients/merchant-mp/src/pages/device-stocktake/device-stocktake.vue:44` 用 `raw.match(/(\d{6,})/)` 剥掉 EG，而 `DeviceIdService.java:22,77` 新编号**本体**就是 `EG+12位` 入库；权威解析器 `packages/shared-uni/src/qrcode.ts:7` 保留 EG。同文件 `:161` 又硬拼「柜机 EG + deviceId」⇒ 新旧两种形态都显示错。 |
| AU-142 **P2** | 成立。`AuthService.changeOwnPassword:200-218` 只校验 6-64 位；同文件 `:179` 与 `MerchantTeamAdminService:208/231`、`OpsRbacService:408/530/548` 都调 `PasswordPolicy.validate` ⇒ 自助改密是唯一漏口。 |
| AU-144 **P2** | 结论成立、**锚点文件名有误**：两个基线都不存在 `utils/auth-storage.ts`。实际在 `clients/admin-vue/src/stores/auth.ts:206,254`（`/me` 无条件写 `admin_phone`，击穿 `views/LoginView.vue:432-434` 的 opt-out），且 `logout():259-274` 只清内存态、全仓仅 `LoginView.vue:434` 会删除该键 ⇒ 登出确实不清。 |
| AU-145 **P2** | 成立。`clients/consumer-mp/src/pages/login/login.vue:584-597 bindWeixinIfPossible` → `consumerWxLogin(wxCode, phoneNum)`，`utils/consumer-auth-session.ts:81-89 wxLoginBody` 把明文手机号放进 body 且 `phoneCode` 缺省 ⇒ 生产被 `resolveWxPhoneNumber:240-244` 拒 400，异常被空 `catch` 吞掉 ⇒ 微信绑定永远静默缺失。 |
| AU-146 **P2** | 成立。`git ls-files` 证实 `infra/gateway/grafana.htpasswd` 入库，内为**真实** bcrypt 哈希且 **cost=05**；`infra/docker-compose.apps.yml:243` 注释仍称「替换占位口令」⇒ 注释失真。用户名是个人账号名，属仓库内真实凭据面。 |
| AU-149 合规 | 成立。全仓 Java 源码检索「注销」零命中，无删除/注销端点；隐私政策承诺走人工客服。 |
| AU-125 | 成立并补一条：`AnnouncementController.java:36-39` 的 `/published` 无 `@RequiresPermissions`，`WebConfig.java:57-77` 只保证「登录」不保证「运营」；更关键它调 `AnnouncementService.listPublished:57-59` = **全量 PUBLISHED、不过滤 targetScope、返回裸实体**，而受众专用 `listPublishedForAudience:65-72` 才做 scope 过滤 ⇒ 任意登录态（含消费者 token）可读运营/内部定向公告全文。 |
| AU-063 | = 本报告 FS-14，同一处（`DeviceReportController.java:152-163`）。 |

### 11.2 对方结论方向对、机制需订正

- **AU-137（其 P1）**：后果判断（多轮复审吞用户资金、幂等键不含轮次）与我一致，但根因不是「跨轮撞键」。
  `balance_refund_allocation` **没有状态列**（`V185__balance_refund_request.sql:29-37`、实体仅 6 字段），失败切片行既不删也不标（`markRefundFailedAndReverse:535-557` 只写流水与状态），而事务① `debitAndMarkProcessing:500-502` 在**调渠道之前**就把全部切片落库 ⇒ 续退里 `done` 恒含失败片，`:453` 的 `succeededCents = Σ全部已计划` 把「已计划」当「已成功」。
  于是两条分支：(a) 首轮规划满额 ⇒ `remainCents=0` ⇒ `allocations.isEmpty()` ⇒ **`resumePartialRefund:400-402` 直接 `markRefundSuccess`，把 PARTIAL 翻成 REFUNDED 且失败切片永不重试**（状态谎报渠道已退，运营点「续退」等于确认书）；(b) 两轮之间新出现可退充值单 ⇒ 才进 `debitRemainingBalance`，此时真正的资损是**单轮双扣**（FS-20，已真库实证），跨轮 `RESUME`/`REVERSAL` 撞键只是叠加放大。
  ⇒ 并案处置：FS-20 补扣单一原语化 + 键加轮次；同时给 allocation 补 `status` 列，让续退只重划 FAILED 切片。
- **AU-148（其 P2「gateway 是全栈唯一无 restart 的常驻服务」）**：`gateway` 无 restart 成立（`docker-compose.yml`/`apps.yml`/`full.yml` 三处定义都没带），但「唯一」不成立。逐 service 实测：`docker-compose.yml` 还漏 `redpanda`；`full.yml` 漏 `postgres/redis/emqx/minio/redpanda/gateway/prometheus/grafana`；**`docker-compose.production.yml` 整份 9 个服务 0 个 restart**。真实结论比对方更重：**生产编排层没有任何重启自愈策略**，不止 gateway。
- **AU-141** = 本报告 FS-11（分账 return/refresh 族 `@Transactional` 包 HTTP），同一条，无分歧。

### 11.3 冲突项与未复核项（诚实清单）

- 对方总评称「**无资损级 P0**」。这与 FS-01（`refunded_cents` 记两倍）、FS-02（同额二次退款静默 skip：渠道没退、账已退、分账已冲正）、FS-20（真库实证双扣）**直接冲突**；三条在其基线 `28c9f99a` 复核存在（`SettlementPartialRefundService:128/140`、`OrderPaymentService:599`）。
- 对方附录 D 称 admin「路由守卫 fail-closed、端点纪律全绿（390 条前端引用全命中后端 618 路由）」。FS-03 恰是 fail-closed 的**反噬**：`platform-fund` / `device-rental` / `warehouse/monthly-close-sheets` 三条路由有 `meta` 无 `perm` 且不在编译期 `NAV_ITEMS`，于是 `router/index.ts:584` 的「无 nav 且无 metaPerm ⇒ forbidden」把**包括超管在内的所有人**挡在门外。API 端点命中率与路由可达性是两回事，可同时为真。
- 对方的实跑数字（`mvn test 2030/0`、`WalletRefIdempotencySqlIT 5/5`、AU-125 运行时 200 探测、dev 库停 V378）**我未复核**，按其自报登记。我方本轮新增实跑：FS-20 IT（Testcontainers 真库，上下文启动 75s，断言 expected 9600 but was 9200）。
- 对方「约 150 条历史声明逐条核验：失实 17 条、虚报已修 0 条」「抽验 16 条台账已回归全实在位」——方向与我 §5 一致（我方 §5 亦证实「文档落后于代码」是主要债务，并证伪 A6④/DEV-03 两条自报 CLOSED），但其全量核验我未复算。
- 对方的台账回写（AU-136~173 已写入 `docs/AUDIT_LEDGER_2026-10-13.md`）**仍是主工作树未提交改动**（主树 HEAD 仍 `28c9f99a`）；本报告只在审计分支，未替它提交。
- 对方引用「A 案钱包统一 V387–V390」：`origin/dev d5dee737` 实际最高 **V389**，无 V390（`ls db/migration` 无 `V39*`）；其 §5.4 对 `TRI_CLIENT_BACKLOG`「V371→实际 V386」的订正在其基线成立，但相对 `origin/dev` 已再次过时。

### 11.4 合并后的上线阻塞序（本轮最终口径）

1. **资金 P0**：FS-20（单轮双扣，已有复现钉）→ FS-01（两倍记账）→ FS-02（幂等键静默 skip）→ AU-137(a)（PARTIAL→REFUNDED 谎报，与 FS-20 同批修）。
2. **认证/结构 P0**：AU-136（H5 明文手机号接管）、AU-172（陈列模板主键错位）。
3. **可达性 P0/P1**：FS-03（三页全员 403）、AU-138/139（新版编号设备盘点必失败）、AU-173（定时任务启停开关失效，动摇上线手册勾选）。
4. 其余 P2/P3 按 §8 批次同奏；FS-04 依 §10.1 订正后并入批次 3 门禁项；AU-148 依 §11.2 订正为「生产编排整体缺重启自愈策略」。

## 12. FS-20 复现命令

```bash
mvn -o -B -pl services/trade-service -am verify \
  -Dtest=ZzNoUnitTestMatch -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=BalanceRefundResumeDebitSqlIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

去掉测试类上的 `@Disabled` 即可复现 `expected: <9600> but was: <9200>`。
