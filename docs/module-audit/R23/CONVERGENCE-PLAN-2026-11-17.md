# 收敛计划：68 → 8 个 PR

> 基线：`5086c85d`（与另一会话的 `3fece4e7` 代码同一份，仅多两笔 docs 提交）
> 编写：2026-11-17 · 只读审计产出，未改动任何代码
> 口径：未闭环 **68 条**（P1 16 / P2 34 / P3 18）

> ⚠️ **基线漂移提示（2026-11-17 15:30 观测）**：工作树有另一会话未提交的「删除用户支付偏好」重构
> （28 文件 + 新迁移 `V495__drop_user_pay_preferred_channel.sql`）。本计划中 F2/F3 的**行号会失效**，
> 执行前需按新代码重新定位；但结论不受影响：
> - **BR3 仍成立**：`openServiceOrderForShopping` 依旧按 `isPasswordFreeReadyForChannel(user, WECHAT)`
>   无条件创单，与 `freezeForOpen` 的按入口渠道判免密仍是两套判据（已按改后代码复核）。
> - 迁移高水位由 V494 → **V495**，F7 的 drift 核对口径随之更新。
> - 该重构另引入两条新风险，见 §8。

---

## 0. 这份文档怎么用

- **给执行会话**：按 `F1 → F8` 顺序做，**一族一个 PR**。每族必须同时交付「代码修复 + 配套门禁」，只修代码不算完。
- **给排期**：P1 16 条全做；P2 只做「资损 / 越权 / 不可恢复」30 条；P3 18 条不排期。
- **给验收**：§1 的 5 条硬门槛全绿才算「可上线」，不是「清零」。

**为什么按族不按条**：68 条里绝大多数是同一根因的不同投影。逐条修 = 68 次改动、下一轮再长出同形态；按族修 = 8 次结构性改动 + 8 条门禁，把「再长出来」这件事掐断。

---

## 1. 完成定义（DoD）

| # | 门槛 | 判据 |
|---|---|---|
| 1 | P1 清零 | 16 条全部关闭；其中 3 条运维动作（ECS 通道 / 备份异地 / 告警实发）须留有证据 |
| 2 | P2 三类清零 | 资损、越权、不可恢复共 30 条关闭 |
| 3 | 每族门禁落地并**负向验证** | 注入已知坏形态 → 门禁红 → 还原 → 绿（项目既有纪律，见 MEMORY 铁律 #4） |
| 4 | 部署前置全绿 | 目标环境 `flyway-drift-check` PASS；V494 已在低峰窗执行；compose 链含 xxljob |
| 5 | 冻结审计 4 周 | 期间只跑「修复回归」，不开新镜头；新发现一律进 backlog 不阻断 |

**明确不做的**：4 条 P2 + 18 条 P3 → 写入 backlog，标注 owner 与「不修的理由」。**不修不是遗漏，是决定。**

---

## 2. 八族总览

| 族 | 名称 | P1 | 覆盖条目 | 一次修法的本质 |
|---|---|---|---|---|
| F1 | 可观测性自证 | 4 | J1 J2 J3 J9 J14 J15 J16 N-04 | 让「任务停跑」这件事自己暴露出来 |
| F2 | 设备范围闸形态 | 1 | N-01 N-05 N-06 L-09 | 闸从拦截器下沉到 service 入口 |
| F3 | 结算/对账事务边界 | 3 | ①/BR5 BR3 J4 J8 ⑧ ⑨ | 拆成 prepare → HTTP → finalize |
| F4 | 库存/月结账实 | 1 | BR9 BR10 BR11 BR13 BR14 BR15 BR16 BR1 BR2 月账单利润 | 判据改 SKU 级 + 加恒等式 |
| F5 | 状态机/快照覆盖 | 0 | J5 J6 BR12 BR17 BR18 BR19 | 一切状态写入带 `WHERE status=快照态` |
| F6 | 幂等/重试窗口 | 0 | J10 BR4 BR6 BR7 | 幂等键不含可变 ID |
| F7 | 部署可复现 | 5 | J11 L-03 L-04 N-02 N-03 N-10 | 部署链与迁移链可重放 |
| F8 | 文档/门禁漂移 | 2 | L-01 L-02 L-10 L-11 注释族 | 文档与门禁跟着代码走 |

---

## 3. 逐族方案

### F1 可观测性自证（P1×4）

**根因**：看护体系的判据是 `last_run_at`，而「跳过」也会刷新它；告警链路又没配置投递。结果是**停跑 → 看不出来 → 看出来也发不出去**，三层同时失效。

**改动点**

| 文件 | 改什么 |
|---|---|
| `ScheduledTaskService.java:283-291` | `markSkipped` **删掉** `row.setLastRunAt(now)`，只保留 `lastResult=SKIPPED`。看护按 `last_run_at` 判 OVERDUE 才会生效 |
| `ScheduledTaskStaleMonitor.java:60-66` | `SPRING_RESIDENT_TASK_KEYS` 增 `"charge-pending-reconcile"`（阈值已备在 `ScheduleZones.java:129`，属漏登记） |
| `ScheduledTaskService.java:329,353-397` | `MAX_HEARTBEAT_RENEWALS`（现 120 次 ≈ 6.6h）降到 `2`；心跳续租前校验「持锁线程仍存活」，避免线程被 kill 后标志残留 |
| `ReconciliationScheduler.java:52-55` | 未启用时记 `SKIPPED` 而非 `SUCCESS`；启动期断言 `RECON_SCHEDULED_ENABLED` 与 profile 一致 |
| `docker-compose.apps.yml:382-383` + `.env.production.example:149,152` | 飞书 webhook/secret 空值在 strict profile 下启动即 fail-loud（不能等投递 503） |

**配套门禁（拟建，尚未落地）**：新增 `check-scheduled-task-watched` 脚本
- 扫所有 `@Scheduled` / `@XxlJob` 方法与 `ScheduledTaskRegistry.register("key"` 的 key
- 判据：每个 key 必属于 `XxlJobManagedTasks.KEYS` ∪ `SPRING_RESIDENT_TASK_KEYS` ∪ 显式豁免文件（带理由）
- 负向：注释掉 `charge-pending-reconcile` 一行 → 必须红

**测试**：`markSkipped` 后断言 `last_run_at` 未变；连续 skip 超阈值后看护产出 OVERDUE。

**风险**：改完第一次跑会集中报 OVERDUE（历史 skip 堆积）。上线前先手工把已知正常的任务 `finish` 一次刷基线，否则会被当故障。

---

### F2 设备范围闸形态（P1×1 + P2×3）

**根因**：范围闸建在**拦截器**上，只能看见 `{deviceId}` 路径变量。deviceId 一旦在 body 或 query，闸就完全失效；`check-ops-device-scope.mjs` 的候选集也只从路径/查询参数建，所以门禁全绿。

**改动点（三处写面 + 一处凭据）**

| 入口 | 现状 | 改法 |
|---|---|---|
| `OpsService.java:77-98` `openDoorForRestockAsUser` | 只校验任务归属，`OperatorAuth.isOperator` 直接放行 | `persistRestockOpeningSession` 内加 `merchantScopeService.requireDeviceAccess(userId, deviceId)` |
| `OpsDeviceRentalController.java:100-121`（`bills/generate` / `mark-paid` / `void`） | 无范围闸，billId 自增可枚举 | Service 层按 bill→deviceId 反查后过闸；禁止只靠 Controller |
| `InventoryOpsService.java:76,224`（`writeOff` / `stocktakeAdjust`） | 都收了 `operatorId` 却没调闸 | 方法首行加 `requireDeviceAccess(operatorId, request.deviceId())` |
| `DeviceHttpCredentialService` | 明文 secret 回显 + 对已有凭据静默轮换 | `explicitRotate=true` 才允许轮换；首次签发不回明文（只回一次）或改一次性展示 |

**配套门禁**：改 `scripts/check-ops-device-scope.mjs`
- 候选集从「有 deviceId 形参的端点」扩为「`/api/v2/ops` 下所有写端点（POST/PUT/PATCH/DELETE）」
- 识别 deviceId 三形态：`@PathVariable`、`@RequestParam`、**`@RequestBody` DTO 字段**（扫 `record XxxRequest(... String deviceId ...)` 与 `private String deviceId`）
- 判据：方法体 4 跳内出现 scope API，否则必须进 `scripts/ops-device-scope-baseline.json` 并写理由
- 负向：新增一个 body-deviceId 且无闸的端点 → 必须红（当前必然绿，这是本次要修的门禁缺陷）

**测试**：role 2 受限运营 + 范围外 deviceId，四个入口逐个断言 403。

---

### F3 结算/对账事务边界（P1×3）

**根因**：MA-274「去事务化」只做完了补缴链一半。主结算链仍是 `@Transactional` + 行锁 + 渠道 HTTP；对账修复链同构。渠道已扣、本地回滚后，痕迹置 `FINALIZED` 而 `hasChargePending` 只认 `PENDING` ⇒ 不收敛、不告警、重试换 `orderId` 再扣一次。

**改动点**

| 文件 | 现状 | 目标 |
|---|---|---|
| `SettlementSettleOrchestrator.java:105-111` | `processRecognitionResultInTx` `@Transactional` + `findByIdForUpdate` | 拆 `prepare`（短事务落单+痕迹）/ `charge`（事务外）/ `finalize`（短事务收口） |
| `OrderPaymentService.java:414,429-446` | `chargeChannelOnce` 在外层事务内 | 复用 B1-B 的 `resolveChargePlan` / `chargeOrderWithPlan` 范式 |
| `OrderPaymentService.java:501-503` | `hasChargePending` 只认 `PENDING` | 增 `hasUnsettledCharge`：认 `PENDING` + `FINALIZED` 但无对应 COMPLETED 账本的组合 |
| `SettlementConfirmDisputeService.java:154-176` | 争议首扣腿同构（注释 `:78` 自认） | 同上 |
| `DataConsistencyService.java:1468-1474,1724-1747` | 退款 HTTP 在 `@Transactional` 内，catch 分账异常已被标 rollback-only | 退款段移出事务；catch 只做留痕不吞异常 |
| `SettlementService.java:201-214` | Redis 锁 60s 固定租约无心跳 | 对齐订单支付锁的心跳纪律（`OrderPaymentService.java:1705-1709` 有现成范式） |
| `ConsumerPreauthService.java:110-122` vs `PayScoreService.java:342` | 冻结按 `entryChannel`、结算按「有无微信支付分单」 | 统一为「本次会话是否创建了支付分单」单一判据 |

**配套门禁（拟建，尚未落地）**：新增 `check-tx-boundary-channel-call` 脚本
- 扫 `@Transactional` 方法体（含 1 跳 self 调用），出现 `*Client.` / `chargeChannelOnce` / `submitChannelCharge` / `refund(` → 红，除非在豁免表登记并写理由
- 负向：给任一 `@Transactional` 方法插一行 `weChatPayScoreClient.` 调用

**测试**：并发双投 + 「渠道成功、finalize 抛异常」注入，断言：不双扣、痕迹可被巡检捞到、重投能收敛到终态。

**风险**：这是本计划最大改动，必须先有 F1 的巡检能力再拆段，否则拆完出问题看不见。**F3 依赖 F1 合入。**

---

### F4 库存/月结账实（P1×1 + P2×9）

**根因**：批次判据是**设备级**而不是 SKU 级；月结公式与导出列各算一套，没有恒等式断言。

| 文件 | 改什么 |
|---|---|
| `InventoryService.java:117` | `deviceUsesLotLedger(deviceId)` → 按 SKU 判 `hasSellableLots(deviceId, skuId)`；批次不足但汇总有货时，先补建 `LEGACY-OPENING` 再扣，而不是 409 |
| `InventoryLotService.java:553-604` | `LEGACY-OPENING` 并入加唯一键/行锁，防并发双并 |
| `DeviceSkuLotMapper.xml:6-12` | 可售口径决定是否含 `BLOCKED`（建议：可售不含，但另出「在柜总量」列，避免账实差被藏起来） |
| `WarehouseMonthlyCloseSheetService.java:288-340` | 导出补「退货」列（否则 `期初+采购+调入−外调−补货−退货` 无法复算） |
| `WarehouseMonthlyCloseSheetService.java:478,494-501` | 差异方向变化时重置 `disposition` |
| `WarehouseMonthlyCloseService.java:161-166` | 实盘取「账期末最近一次」而非账期内任意一次 |
| `WarehouseMonthlyCloseService.java:187-193` | 明确差异与四损耗列是否相加，二者只能有一个进索赔额 |
| `DeviceRentalService.java:242-295` | javadoc 与实现二选一（满期是否免扣安装费）——**需产品拍板** |
| `DeviceRentalService.java:373-401` | 余额扣到 0 时也要落到终态，不能永远 HELD |

**配套门禁**：IT 不变量测试（不是静态门禁）
- `期初 + 采购 + 调入 − 外调 − 补货 − 退货 = 期末`，对 30 天随机账期断言
- `Σ批次可售 = 汇总可售`（同一 SKU 同一柜）
- 失败即红，接进 `mvn test`

---

### F5 状态机/快照覆盖（P2×6）

**根因**：扫快照 → 整实体 `save/updateById`，没有 `WHERE status=快照态`。并发下会复活终态、丢更新。

- `CompensationTaskScheduler.java:105-107,194,211`、`RefundRetryScheduler.java:131-134`：改条件更新（项目已有正确范式：`ReplenishmentTimeoutScheduler.java:114`）
- `MerchantSettlementBillService.java:101-148`：已 CONFIRMED 的账单禁止回溯改写；跨月退款应生成调整单而非重算
- `SessionService.java:416-442`：强杀会话要联动订单（冲正已 PAID / 关闭 PENDING）
- `SessionExpireService.java:212-245,452-517`：离线强制关闭路径 + `closeTime` 取实际时刻

**配套门禁（拟建，尚未落地）**：`check-status-cas`，只扫**资金态表**（白名单）里的 `updateById`，要求同方法内出现状态谓词或 `*IfStatus` 方法。

---

### F6 幂等/重试窗口（P2×4）

- `OrderPaymentService.java:195,272`：幂等键 `CHARGE:<orderId>:<amount>` 含可变 orderId → 改 `CHARGE:<sessionId>:<amount>`
- `RefundRetryScheduler.java:64-72,109-128`：「同轮重发」与「超时置失败」分段，置失败前先查单
- `seed_aicabinet_jobs.sql:72,87`：`executor_fail_retry_count` 降为 0，或给对应任务补幂等证明
- `SessionService.java:368,379-384`：取消支付分单用 `sessionId`（`out_order_no` 口径），不是 `orderId`

**配套门禁**：扫幂等键字符串拼接中出现 `orderId` → 红。

---

### F7 部署可复现（P1×5）

| 项 | 动作 |
|---|---|
| `deploy-prod.sh:135,166` | compose 链补 `-f infra/docker-compose.xxljob.yml`（现在缺，换机重建=38 任务停摆） |
| 迁移 drift | 部署日先 `flyway-drift-check`；库 474 → 仓 487 的 13 个迁移逐个确认；V494 低峰执行，回滚前先清同号行 |
| 备份 | `BACKUP_REMOTE_CMD` 落外部 OSS；PITR 需拍板（托管 PG 或 WAL 归档） |
| ECS 通道 | 22/80 超时先恢复；恢复前不安排上线 |
| admin 产物 | 发布用「源码 commit == 产物 commit」的 SHA；中间 commit 不切镜像 |

**配套门禁**：`scripts/check-deploy-compose-chain.mjs`
- 断言 `deploy-prod.sh` 的 `-f` 集合 = 期望集合（含 xxljob）
- 断言 `flyway_schema_history` 最高版本 == 仓内最高迁移（部署前跑，只读）

---

### F8 文档/门禁漂移（P1×2）

- `TROUBLESHOOTING_GUIDE.md:489,498`：`ai_cabinet` → `aicabinet`；`pg_restore --time=` 不是合法参数，改 WAL + `recovery_target_time`
- `check-shared-dist-fresh.mjs:66`：`tracked.length === 0` 不能直接 continue；未跟踪 dist 的包改为「构建后比对 sha」而非跳过
- `ValidateCheck.java:57-61`：FAIL 必须 `System.exit(1)`
- `deploy-staging.ps1:22-32`：健康检查超时后 `exit 1`，且 check-env 移到 `up -d` 之前
- 注释族：`CabinetOrderLineMapper.xml:244-261`（还写「没有退款流水表」）、`SiteContractService.java:25`（称有到期任务但没有）——按「注释与实现矛盾」批量修

**配套门禁（拟建，尚未落地）**：`check-doc-commands`（把文档里的 `pg_restore`/`psql` 关键命令抽出来做静态校验：库名/用户必须来自同一常量）。

---

## 4. 波次与依赖

```
Wave 1（可并行，无依赖）  F1 可观测性   F2 范围闸   F8 文档/门禁
Wave 2（依赖 F1）        F3 结算拆段
Wave 3（可并行）          F4 库存/月结   F5 状态机   F6 幂等
Wave 4（发布前）          F7 部署可复现
```

- **F1 必须最先**：它是后面所有修的「验收仪器」。没有它，F3 拆完出问题看不见。
- **F2 优先级仅次于 F1**：越权是唯一「不需要并发、不需要脏数据、单次请求就能造成」的 P1。
- **F3 不要和 F4 同批**：两族都动资金/库存主链，混在一起出问题无法二分定位（铁律 #3：一次只改一个变量）。

---

## 5. 与并发会话的协作纪律

当前工作树有其他会话在跑，本计划执行时：

1. `git add` **显式列路径**，禁止 `git add -A`（多会话会把别人未完成的改动带进去）
2. **不整文件覆盖回写**；改前 `git log -S <关键串>` 确认改动是否还在（铁律 #61）
3. 每族开工前 `git log --oneline -1` 对齐基线，避免在别人的半成品上叠改
4. 每族 PR 前跑 `node scripts/pre-push-ci-preflight.mjs`
5. 混线文件按 hunk `git apply --cached` 拆分，不整文件提交

---

## 6. 明确不修（22 条）

| 类 | 示例 | 处置 |
|---|---|---|
| P2 但不阻断（4） | 零元单计单数、fixedRate 漂移、调度池容量、盯盘指标口径 | backlog + owner |
| P3 卫生（18） | 注释矛盾、`fmtMoney` 空串、2FA 后备码位数、押金事件枚举、PK 口径 94 vs 96、V492 缺 COMPETITOR_REF | 登记，不排期 |

**理由写清楚**：不是「不重要」，是「修它不改变上线决策，且会挤占 P1 的验证时间」。

---

## 7. 验收与反证

做完不是看数字，是看**能不能自己抓到下一次**：

1. **反证 1**：在范围闸上注入一个 body-deviceId 无闸端点 → `check-ops-device-scope` 必须红
2. **反证 2**：注释掉看护清单里任一条 → `check-scheduled-task-watched` 必须红
3. **反证 3**：给任一 `@Transactional` 方法插渠道调用 → tx-boundary 门禁必须红
4. **反证 4**：伪造一次「渠道成功、本地回滚」→ 巡检必须报出该笔（F3 的核心验收）
5. **数字**：P1 = 0；P2（资损/越权/不可恢复）= 0；新增门禁 ≥ 8；`flyway-drift-check` PASS

---

## 8. 在飞重构引入的新风险（2026-11-17 15:30 观测，未提交）

另一会话正在删除「用户支付偏好」。这是**用户拍板**的变更，不算缺陷；但落法有三处需要确认：

| # | 风险 | 证据 | 建议 |
|---|---|---|---|
| R-1 | **破坏性 DDL 与「代码停读」同版发布**：滚动/多实例下 DB 先迁移、旧实例仍在跑，旧 jar 的 SELECT 仍带该列 ⇒ 列不存在 ⇒ 500 | `V495` 的 ROLLBACK 注释自认「旧值不可恢复」；`UserInfo.java`/`AccountDto.java` 已删字段 | 走 expand/contract：本版只停读写，`DROP COLUMN` 留到下一版；或确认单实例停机窗口部署 |
| R-2 | `PUT /account/pay-preferred` **无兼容层直接删除**，已发布的小程序旧版本调用会 404 | `AccountController` 已无该映射；`consumer-mp/dist/*/api/endpoints.js` 仍含 `payPreferred` | 保留 6 周返回 200 no-op + 打点，或确认强制更新覆盖旧版 |
| R-3 | 前端产物未重建，仍引用已删端点 | 同上 dist | 发布前用 `build:mp-weixin:dev` 重建并核对产物级 grep |

---

## 附：条目 → 族 索引

| 族 | 条目 |
|---|---|
| F1 | J1 J2 J3 J9 J14 J15 J16 N-04 |
| F2 | N-01 N-05 N-06 L-09 |
| F3 | ①/BR5 BR3 J4 J8 ⑧ ⑨ |
| F4 | BR1 BR2 BR9 BR10 BR11 BR13 BR14 BR15 BR16 月账单利润 |
| F5 | J5 J6 BR12 BR17 BR18 BR19 |
| F6 | J10 BR4 BR6 BR7 |
| F7 | J11(⑪) L-03(⑫) L-04 N-02 N-03 N-10 |
| F8 | L-01 L-02 L-10 L-11 注释族 |
