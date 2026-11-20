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
| F1 | 可观测性自证 ✅`9f0da9c2` | 4 | J1 J2 J3 J9 J14 J15 J16 N-04 | 让「任务停跑」这件事自己暴露出来 |
| F2 | 设备范围闸形态 ✅`d37a02e5` | 1 | N-01 N-05 N-06 L-09 | 闸从拦截器下沉到 service 入口 |
| F3 | 结算/对账事务边界 ✅`48d33cee` | 3 | ①/BR5 BR3 J4 J8 ⑧ ⑨ | 拆成 prepare → HTTP → finalize |
| F4 | 库存/月结账实 ✅`f0b2cbeb`（口径已拍板） | 1 | BR9 BR10 BR11 BR13 BR14 BR15 BR16 BR1 BR2 月账单利润 | 判据改 SKU 级 + 加恒等式 |
| F5 | 状态机/快照覆盖 ✅`0cd3bd58` | 0 | J5 J6 BR12 BR17 BR18 BR19 | 一切状态写入带 `WHERE status=快照态` |
| F6 | 幂等/重试窗口 ✅`df9d7cb4` | 0 | J10 BR4 BR6 BR7 | 幂等键不含可变 ID |
| F7 | 部署可复现 ✅（通道/drift/admin SHA/异地备份四项均已取证） | 5 | J11 L-03 L-04 N-02 N-03 N-10 | 部署链与迁移链可重放 |
| F8 | 文档/门禁漂移 ✅`442fabc7` | 2 | L-01 L-02 L-10 L-11 注释族 | 文档与门禁跟着代码走 |

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
| 备份 | ✅ 2026-11-18 `BACKUP_REMOTE_CMD` 已落外部 OSS 并实测字节一致（MA-359）；PITR 仍按 MA-318 C 档回滚另案跟踪 |
| ECS 通道 | ✅ 2026-11-18 SSH 22 正常、HTTP 80 机内/公网均 404（gateway 在监听） |
| admin 产物 | ✅ 2026-11-18 线上 `static/admin/index.html` 命中 `e61502d5`，源码==产物逐字节一致 |

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

## 8. 在飞重构引入的新风险（已随 `ca290bd5` 提交，三条风险仍在）

另一会话正在删除「用户支付偏好」。这是**用户拍板**的变更，不算缺陷；但落法有三处需要确认：

> 2026-11-17 16:30 复核：该重构已提交为 `ca290bd5`（含 `V495`）。三条风险**均未消除** ——
> 提交面里没有 `clients/consumer-mp/dist/**`（产物未重建），`AccountController` 的旧端点直接消失（无兼容层），
> `V495` 仍是 `DROP COLUMN`。F3/F2 开工前按新 HEAD 重新定位行号即可，结论不受影响。

| # | 风险 | 证据 | 建议 |
|---|---|---|---|
| R-1 | **破坏性 DDL 与「代码停读」同版发布**：滚动/多实例下 DB 先迁移、旧实例仍在跑，旧 jar 的 SELECT 仍带该列 ⇒ 列不存在 ⇒ 500 | `V495` 的 ROLLBACK 注释自认「旧值不可恢复」；`UserInfo.java`/`AccountDto.java` 已删字段 | 走 expand/contract：本版只停读写，`DROP COLUMN` 留到下一版；或确认单实例停机窗口部署 |
| R-2 | `PUT /account/pay-preferred` **无兼容层直接删除**，已发布的小程序旧版本调用会 404 | `AccountController` 已无该映射；`consumer-mp/dist/*/api/endpoints.js` 仍含 `payPreferred` | 保留 6 周返回 200 no-op + 打点，或确认强制更新覆盖旧版 |
| R-3 | 前端产物未重建，仍引用已删端点 | 同上 dist | 发布前用 `build:mp-weixin:dev` 重建并核对产物级 grep |

---

## 9. 执行记录

### F1 可观测性自证 — 已落地（`9f0da9c2`，2026-11-17）

| 计划项 | 落法 | 与计划的差异（都是核实后改的） |
|---|---|---|
| `markSkipped` 删 `setLastRunAt` | 按计划删；跳过只落 `lastResult=SKIPPED` | 计划外发现：删掉后**顺手修好一个既有缺陷** —— `ScheduledTaskController` 的「立即执行」原本用 `last_run_at` 是否推进判「跑没跑」，跳过也会推进 ⇒ 被跳过的手工触发会误报 `TRIGGERED`，现在如实回 `SKIPPED` |
| `charge-pending-reconcile` 入看护 | 补进 `SPRING_RESIDENT_TASK_KEYS` | 无差异 |
| 心跳上限 120 → 2 + 线程存活校验 | **上限改 12**（不是 2），并加 `inspectRenewalGuard` 三态判据 | 2 次 × 租约/3 = 租约的 0.67 倍：月结任务（租约 1800s）只能跑约 20 分钟就会被判丢锁 ⇒ 反而制造双跑。12 次 = 租约 4 倍（600s→约 40 分钟 / 1800s→约 2.5h），既把「卡死惩罚」从 6.6h 收到 40 分钟，又不误伤长任务 |
| 对账未启用记 SKIPPED | 按计划；**仍走 `finish`**（推进 `last_run_at`） | 计划原文只说「记 SKIPPED 而非 SUCCESS」，没写是否推进时间戳。这里必须推进：运维**有意关闭**不是停摆，若同时不推进，看护会按 OVERDUE 每天误报。另加 prod 启动期硬拒 `RECON_SCHEDULED_ENABLED=false` |
| 飞书 webhook 空值 fail-loud | **改在 relay 与部署前置校验**，不在 trade 启动期 | 计划原文写「strict profile 下启动即 fail-loud」是**错的**：trade-service 根本不读这两个 env（它读数据库 `system_config`），env 只喂 `feishu-relay` 容器。所以正确的闸口是 ① relay 健康检查把 `/health` 的 `configured` 纳入判据；② `check-prod-env-secrets.sh` 对空值判红（显式 `ALERTS_DISABLED_ACK=1` 才放行并打印横幅） |
| 新门禁 `check-scheduled-task-watched` | 按计划；三条规则 R1/R2/R3 | R3 是计划外加的：只比对「@Scheduled → 看护名单」还不够 —— 把 `SPRING_RESIDENT_TASK_KEYS` 从 `watchedKeys()` 里删掉，门禁会拿一份**已不生效**的名单去比对而恒绿（判据与被判对象脱钩） |

**负向验证（铁律 #4）**：删掉 `"charge-pending-reconcile"` 那一行 → 门禁红（R1 + R2 各报一条，含 `file:line`）→ 还原 → md5 一致 → 绿。门禁 `--self-test` 另有 6 类合成反例（孤儿调度 / 孤儿注册 / 解析不出 / 显式豁免 / 名单缩水 ×2）。

**测试**：`ScheduledTaskServiceTest` 10/0（+3）、`ProductionStartupValidatorTest` 17/0（+1 负向）、`ScheduledTaskStaleMonitorTest` 14/0、调度相关门禁 6 条全绿；`pre-push-ci-preflight` 的 audit-gates 全链仅剩 1 条真红（下述，非本族）。

**遗留（不属 F1）**：`check:fix-batch-coverage` 红在另一会话的 `3229e914`（`fix(types)` 未登记）——本族的 `9f0da9c2` 已登记。另有 3 条 `*-negative` 门禁在本机**假红**（`SAFE_DELETE_BULK_CONFIRM_REQUIRED`：同 turn 累计删除 > 50），单跑均绿（铁律 #83）。

### F2 设备范围闸形态 — 已落地（`d37a02e5`，2026-11-17）

| 计划项 | 落法 | 与计划的差异（都是核实后改的） |
|---|---|---|
| 补货代开门补闸 | 补在 `DeviceValidationService#ensureRestockDoorAllowed` 的 `isOperator` 分支 | 计划写「`persistRestockOpeningSession` 内加」——那里商户补货员与运营共用一个方法，**无条件加闸会误伤商户补货员**（他们的 `userId` 走 assignee 分支，本来就由任务归属绑定到同一台柜机）。闸必须只落在收 operator 身份的那一支 |
| 租机账单三入口 | `generateBill`/`markPaid`/`voidBill` 按 bill→deviceId 反查后过闸 | 计划只列了「Service 层按 bill→deviceId 反查」；实际还发现 `generateAll`（全量出账）直接 `selectList(null)` 遍历全平台 ⇒ 一并按 `allowedDeviceIds` 收窄（null=不限=内部任务，空集=一台不出） |
| 报损/盘调补闸 | `writeOff`（设备侧那支）与 `stocktakeAdjust` 在**拿锁之前**过闸 | 闸的位置比计划更靠前：计划只说「方法首行加」；实测必须早于 `runWithDeviceInventoryLock`，否则越权请求会先占柜锁再被拒（阻塞正常请求 60s） |
| 凭据 `explicitRotate` | 按计划；另修「吊销后复签 500」 | 计划只提「静默轮换」与「明文回显」。核实时发现两个附带事实：①**明文回显本来就是一次性的**（只在签发响应里回，库里只存 sha256）⇒ 这一半无需改；②`revoke` 把 hash 清空后，复签判据「有没有 hash」成立为 false ⇒ 走 insert 撞 `device_id` 主键 500。修法改为「行是否存在决定 UPDATE/INSERT」，比加 `explicitRotate` 更根本 |
| 门禁认 body deviceId | 新增 R5，并入 R4 同一脚本 | 计划写「候选集扩为 `/api/v2/ops` 下所有写端点（415 个）」——**没有采纳**：415 个端点里绝大多数根本不带设备字段（用户/角色/配置/报表），把它们全列进基线等于把门禁变成一张 400 行的 allowlist，正是基线「棘轮只降不升」明令禁止的形态。改为按**缺陷形态**建候选集：写端点 + `@RequestBody` DTO 声明 `deviceId`/`deviceIds`（当前 14 个），并把「候选集 <8」判红作为扫描面塌陷的假绿防线 |
| 四条越权 403 断言 | 四个入口逐个断言；每条都断言「拒绝发生在副作用之前」 | 计划只要求「403」。实测把「顺序」也钉上才有意义：读面越权只是看不见，写面若先动库再拒，403 也只是事后装饰 |

**计划外发现（同一轮顺手修/登记）**：R5 首跑就暴露 **9 条**此前无人审计的 body-deviceId 写端点，其中 5 条是真越权并已补闸——补货 `upsertInventory`（台账直写）/ `planRoute`（批量建路线）/ `createTaskFromSuggestion`（跨商户派单）、广告 `createAdCampaign` / `updateAdCampaign`（把广告推到别人柜机屏上，屏对着的是那家的消费者）。另 4 条是语义例外，登记基线并写清兜底：新建柜机（deviceId 是待创建的新柜号，租户轴由 `requireMerchantAccess(merchantId)` 兜底）、演示一键跑通（mock-only + `ops:admin`＝全局）、授范围本身、组织归属（范围判据来源仍是 `ops_user_merchant`/`ops_user_device_scope`，组织树不参与）。

**负向验证（铁律 #4）**：`verify-ops-device-scope-gate` 由 S1–S8 扩到 **S1–S10**——S9 注入「body 带 deviceId 且无闸」的写端点 → 必须红（修前必然绿，这正是本族要补的门禁缺陷）；S10 把报损的设备侧闸退回 → 必须红（证明修复是承重的，而不是「门禁认得注释」）。10 项全过、真实仓三文件 md5 零残留。

**测试**：trade-service 全量 **3227/0**；本族新增/改——`DeviceRentalServiceTest` 39/0（+5）、`DeviceValidationServiceTest` 12/0（+1 负向）、`InventoryOpsConcurrencyTest` 5/0（+2）、`DeviceHttpCredentialServiceTest` 9/0（+3）、`OpsReplenishmentScopePushdownTest` 8/0（+3）。

**遗留（不属 F2，登记待办）**：`AdCampaignService` 只挡了「投放清单里的柜机」，**读面**（活动列表/播放统计）仍全平台可见——那是 F8 文档/门禁漂移与后续 P2 越权的交界，不在本族修。

### F3 结算/对账事务边界 — **F3a 已落地（`14af61dd`），F3b 待办**

按「先止血、再拆段」切成两半：先把**已经会丢钱**的那条缝堵上（不改事务形状），再动结构。

**F3a 已落地（MA-333，P1 ①/BR5）**

| 项 | 落法 | 说明 |
|---|---|---|
| 收口时机 | `submitChannelCharge` 成功段 → `settleChargeTraceAfterCommit` | 有活动事务 ⇒ 注册 `afterCommit`；回滚则回调不执行、痕迹保留 `CHARGE_PENDING` 可被 reconciler 收敛 |
| 无事务时的取舍 | **刻意不收口**（交给 `finalizeCollected`） | 补扣链的渠道 HTTP 已在事务外（MA-274），在那里提前收口正是要修的缺陷；这里若「图省事就地收口」= 把补扣链重新弄坏 |
| 无单号例外 | 渠道判回落余额（一分钱没扣）仍允许就地收口 | 否则 `CHARGE_PENDING_STALE` 对回落单全量误报 |
| 存量兜底 | `hasUnsettledCharge` + `findSettledButUnbooked` + `alertChargeSettledButUnbookedOps` | 只告警不自动修：可能已人工补记，程序改单会二次记账 |
| 门禁 | `check:charge-trace-after-commit`（R1/R2/R3） | 负向：还原成旧写法 → 红（2 条判据）→ 还原 → md5 一致 → 绿 |

**F3b 待办（明确不塞进本轮）**：主结算链的 `prepare → HTTP → finalize` 拆段（`SettlementSettleOrchestrator` 那个大事务）、争议首扣腿同构、`DataConsistencyService` 退款段移出事务、`SettlementService` 130 行 Redis 锁补心跳、`ConsumerPreauthService` 与 `PayScoreService` 的冻结/结算判据统一。
**为什么不一起做**：拆段要改「识别→落单→扣款→分账」整条链的事务形状，必须有真 PG 的 IT（并发双投 + finalize 注入失败）才敢动；本轮只做了可被单测完全覆盖的那层，剩下的一律留在 F3b，并在 §7 的验收里按「F3a 已治标、F3b 治本」分别记账。**风险不变**：拆段期间若没有 F1 的巡检，出问题看不见——F1 已合入，F3b 的前置条件已满足。

---

### F3b 执行记录（2026-11-18，前两项 + 配套门禁）

| 计划项 | 落法 | 与计划的差异 |
|---|---|---|
| `SettlementService` Redis 锁补心跳 | `LockHeartbeat`（60s 租约 / 20s 续租）接入主链 | 计划只提这一类；实测发现**同一把 `session:settle:*` 锁有两份实现**——`SettlementWaiveRefundService#runWithSessionSettleLock` 是独立私有副本，只改 `SettlementService` 仍会双跑。两处都改，且 `stop` 严格早于 `unlock` |
| `ConsumerPreauthService` 与 `PayScoreService` 判据统一 | 先建支付分单，再用 `hasOpenWechatOrderForSession` 的读值决定 `skipFreeze` | 计划写「统一为『本次会话是否创建了支付分单』」；实现上**把建单调用挪到冻结之前**，让判据变成对同一张表的真实读值，而不是「预测会不会建单」。开发测试通道从不建单 ⇒ 恒冻结（不再按 entryChannel 推断） |
| 配套门禁 `check-tx-boundary-channel-call` | R1 事务内渠道调用 / R2 基线棘轮 / R3 扫描面下限 | 计划写「扫 `@Transactional` 方法体含 1 跳 self 调用」；实现额外支持「客户端字段名从字段声明类型反查」（`client` 这类短名 + 全限定类型），并把 `isXxx` 本地能力探测豁免，否则 `alipayPayClient.isConfigured()` 会造出关不掉的噪音。**跨服务多跳链静态不覆盖**，不塞进基线冒充已管 |

**F3b 仍待办（三项，风险从高到低）**：`SettlementSettleOrchestrator`/`SettlementOrderFinalizeService` 主链拆 `prepare → HTTP → finalize`（外层 `@Transactional` 必须一起拆，否则 HTTP 仍在事务内）、`SettlementConfirmDisputeService` 争议首扣腿同构、`DataConsistencyService` 退款 HTTP 移出事务。这三项都会改变「渠道成功后本地失败」的落库形态（订单转 PENDING 而非整笔回滚），需要真 PG IT 覆盖后再动；门禁已先把新增形态钉住，存量按基线逐项摘除。

**验证**：trade-service 全量 **3238/0**；`SettlementSessionSettleConcurrencyTest` 5/0（+3）、`SessionOpenFreezeCriterionTest` 3/0、`LockHeartbeatTest` 2/0。门禁真树注入 `chargeOrder` → 红（带 file:line）→ 还原 md5 一致 → 绿；sibling 夹具 13 断言覆盖 R1/R2/R3。本机 Docker 重启后全栈恢复（EMQX 11883 撞 Windows 保留端口，改用 `EMQX_MQTT_PORT=12883` 启动）。

---

### F4 执行记录（2026-11-18，第一项）

| 计划项 | 落法 | 与计划的差异 |
|---|---|---|
| `deviceUsesLotLedger(deviceId)` → 按 SKU 判 | `hasLotForSku(deviceId, skuId)` + `rawAggregateQuantity(...)` 双判据；`doDeductFefo` 持锁补建 `LEGACY-OPENING` | 计划只写「按 SKU 判 `hasSellableLots(deviceId, skuId)`」；**不能直接替换**：`hasSellableLots` 只统计可售批次，售罄/过期批次会返回 false，那就绕过 FEFO 走汇总虚扣。改为「有批次行（含零可售）→ FEFO fail-closed；无批次但汇总裸量>0 → 仍 FEFO 且补建期初；两者都无 → 原子汇总扣减」 |

**配套门禁**：计划写「IT 不变量测试（不是静态门禁）」，本轮先补 `check:inventory-sku-ledger`（R1/R2/R3 静态判据）止住形态回退；`期初+采购+调入−外调−补货−退货=期末` 与 `Σ批次可售=汇总可售` 的 30 天随机账期 IT 仍在待办，不拿静态门禁冒充已覆盖。

**F4 仍待办**：`LEGACY-OPENING` 并批的唯一键/行锁（当前靠设备锁 + 先查空，并发窗口仅由锁盖住）、`DeviceSkuLotMapper.xml` 可售口径是否含 `BLOCKED`、月结导出缺「退货」列、差异方向变化不重置 `disposition`、实盘取值口径、差异与四损耗列重复计入索赔、租机满期免安装费需产品拍板、余额扣到 0 不落终态。

### 计划外发现（2026-11-18，未修，待拍板）

`PayScoreService#openServiceOrderForShopping` 建开门支付分单时**不写 `payscore_order.order_id`**，而真实库该列为 `NOT NULL` + FK 到 `cabinet_order`（V74 建表，V218 未放宽；docker 内 `information_schema` 实查 `order_id|NO`）。当前 `PAYSCORE_ENABLED` 默认 false（`application.yml:323`、`docker-compose.full.yml:261`）所以未暴露；一旦生产开启微信支付分免确认模式，每次开门都会在 insert 阶段失败并被包成 412「微信免密确认失败」。修法有两条路：① 允许开门期未关联订单（`DROP NOT NULL` + 迁移），② 改为结算时才建单（与 F3b-2「先建单再判冻结」的时序相反，需重排判据）。**先拍板再动**，不猜着改。

---

### F6 前置与支付分建单拍板（2026-11-18，`c09ef21d`）

| 项 | 落法 | 说明 |
|---|---|---|
| J10 幂等键 | `CHARGE:S:<sessionId>:<amount>`，旧键只双读不写 | 用户拍板接受「拆段后 PENDING 走补缴」前，必须先让重试换 orderId 绕不过幂等；这是 F3b 拆段的硬前置。DONE 回调与 reconciler 同源，避免同笔两条 CHARGE 流水 |
| 支付分建单 `order_id` | V496 `DROP NOT NULL`（保留 FK），结算回填 + 孤儿单告警 | 用户采纳方案①。方案②（结算时才建单）被否：官方 CB-064 的创单与风险金评估在开门前，挪到结算等于让未评估用户先进柜 |

**验收**：trade-service 3244/0；两道新门禁 R1–R4 / R1–R3 均做真树注入负向 + md5/逐字节还原；迁移在 psql 事务内验证可执行且可回滚；CB-064 补第 6 条决策记录。此项完成后，§9 上一节「计划外发现（未修，待拍板）」已闭环。

---

### F7/F8 P1 清理（2026-11-18，`442fabc7`）

- J11：生产 compose 链补 xxljob overlay（原来只有 apps profile，调度中心不启动）；门禁补必含判据并真树负向验证。
- L-01/L-02：排障文档库名与 PITR 命令改正，顺带修掉 25 处被 `` 污染的代码围栏。
- 失败语义：ValidateCheck 补 `System.exit(1)`；deploy-staging 的 check-env 前移 + 健康超时 exit 1。
- 共享产物：「未跟踪」与「显式 gitignore」分开判，新增 `check:shared-dist-tracked`，堵住 `tracked.length===0 → continue` 的假绿。

**F7 取证已于 2026-11-18 完成**（结果见下文「F7 取证结果」）：目标库 drift-check PASS、ECS 22/80 通道、admin 发布 SHA、异地备份四项均有证据，**不再列为待取证**。文中其余「仍待」表述以该节为准。

---

### F6 收口（2026-11-18，`df9d7cb4`）

J10（幂等键）、BR4（置失败前查单）、BR6（按 sessionId 取消支付分单）、BR7（XXL 失败重试）四项全关，F6 整族完成。
其中 BR6 是 V496 的连带必修：开门期支付分单没有 orderId，仍按 orderId 取消必然漏单。
`check:retry-window` R1–R3 钉住三项判据，真树注入 seed 重试次数 → 红 → 还原 → 绿。
trade-service 全量 3247/0。

---

### F5 状态机条件写入（2026-11-18，`0cd3bd58`）

J5/J6/BR12/BR17 四项按「扫描快照不得直接覆盖状态」统一处理：
补偿任务领取与收尾、退款痕迹置失败与续命、商户结算单确认后冻结、强杀会话校验关联订单。
新增 `check:status-cas` R1–R4 + 夹具 5 断言，真树去掉 `claimIfPending` 即红。
trade-service 全量 3250/0。

**有意不同**：强杀会话没有做「自动冲正已 PAID 订单」——冲正是资金动作，需要退款决策与渠道确认；
这里改成 fail-closed（409 要求先处置订单），比程序擅自退款更安全。

---

### F4 第二批（2026-11-18，`d9cbbad5`）

- BR11：月结导出补「退货」列（19 列，位置钉住），恒等式可在 Excel 复算。
- BR16：押金余额归零也落 SETTLED（期满奖励仍只在期满发），结清带 `WHERE status=HELD`。

**F4 仍待办（4 项，需产品/口径拍板）**：`DeviceSkuLotMapper.xml` 可售是否含 `BLOCKED`（建议不含但另出「在柜总量」）、差异与四损耗列是否重复计入索赔、`LEGACY-OPENING` 并批的唯一键（生产可能已有重复行，直接加约束会失败）、`DeviceRentalService` 满期是否免安装费。这些不是代码能力问题，是需要口径决定后才能动。

---

### F3b 拆段（2026-11-18，`973caf60`）

- 主结算链：`prepareSettlement`（落单/扣库存/绑券）→ `chargeOrder`（事务外 HTTP）→ `finalizePaidSettlement`（冻结/券/分账/会话/会员）；失败/不确定走 `settleAsPending` 落 PENDING。
- 争议首扣腿同构；改单准备单独 `prepareAdjustInTx` 短事务。
- 痕迹收口 `finalizeChargePending` 移到 finalize 提交之后，finalize 失败保持 PENDING 交 F3a 巡检。
- 识别服务只产出决策，编排器决策短事务；`settleManual` 去外层事务。
- 🔴 自查发现：package-private 方法上的 `@Transactional` 会被 Spring 忽略，三个事务方法已改 public。
- `check:settlement-split` R1–R5 + 夹具 6 断言，真树注入 @Transactional 即红。

**F3 仍剩 1 项**：`DataConsistencyService` 退款分支的渠道 HTTP 仍在 `fixInconsistencyDetailed` 的 `@Transactional` 内（`refundOverchargeIfNeeded`）。它和主链不同——修复链同时改订单头/券/分账，拆开需要先把「退款」从 applyFix 的多分支里单独抽出，建议单独一轮做，不塞进本批。

---

### F3b 联调补修（2026-11-18，`709f3681`）

- 🔴 **实测教训**：拆段后 `SettlementSettleOrchestrator` 2 参 `processRecognitionResult` 还留着
  `@Transactional`，把 REQUIRES_NEW 又包回外层——挂起外层时行锁仍被持有，子事务等锁 ⇒
  Hikari `connection leak detected`、E2E 卡死、CI build 40 分钟不结束。**只拆内层、忘了摘外层入口，
  是最容易漏的形态**；`check:settlement-split` 现已把 `processRecognitionResultInTx` 纳入 R3，
  但 2 参入口仍需人工核对（本轮已摘）。
- `PaymentOperationMapper#findStaleChargePending` 缺 SQL（F3a 引入），对账巡检每轮
  `Invalid bound statement` 被定时任务日志吞掉 ⇒ 实际从未跑过。补 `@Select` 后本地
  `ConsumerE2ETest` 11/0、1m15。

---

### F3 收尾 + F4 拍板（2026-11-18，`48d33cee` / `f0b2cbeb`）

- F3 最后一项：一致性修复拆成 `applyFixInTx`（DB 修复 + 收集退款意图）→ `doRefundOutsideTx`（渠道退款 + 分账回算）→ `markFixedInTx`；退款未确认不标 FIXED，记录留 FAIL 可重试。
- F4 三项拍板：
  1. **索赔维持全额**（差异金额全额，损耗列只展示不抵扣）—— 已加注释 + 测试钉住；
  2. **满期免安装费**（任何分支 fee=0）—— 已改 + 测试；
  3. **期初唯一键不新增**：V483 已有 `uq_lot_device_sku_batch`，且该迁移明确禁止自动合并重复行；用户选的「迁移先去重」与既有设计冲突，按既有设计为准（不自动动库存真值）。

至此 F1–F8 的代码可修项全部收口；剩余只有 F7 的四项运维取证（drift 证据 / 备份落 OSS / ECS 通道 / admin SHA），需要真实环境动作。

---

### F7 备份异地门禁（2026-11-18，`23267eb9`）

`BACKUP_REMOTE_CMD` 从「可选告警」改为 fail-closed：留空即中止部署，确知只留本地必须
`BACKUP_LOCAL_ONLY_ACK=1`（横幅留痕）；自测 +2 并接入聚合门禁，CI 每轮验证。

**F7 仍剩 3 项纯运维取证**（代码侧已无可做）：目标库 drift-check PASS 证据、ECS 22/80 通道恢复、admin 发布 SHA「源码==产物」核对。

---

### F7 取证结果（2026-11-18，`EVIDENCE-F7-2026-11-18.md`）

- 通道：SSH 22 正常；HTTP 80 机内/公网均返回 404（gateway 在监听）✅
- drift：生产库 446 条已应用迁移与本地逐条 checksum 相等 ✅
- admin SHA：线上 `static/admin/index.html` 命中 commit `e61502d5`（逐字节一致）✅
- 备份：**发现生产无备份 cron、12 天无新备份**（MA-358）——已补跑并装每日 02:10 cron；
  异地归档已打通（MA-359）：复用 `ai-cabinet-by/backups/aicabinet/*`，STS AssumeRole +
  内网 endpoint，实测本地/远端字节数一致（1,214,016B）。

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
