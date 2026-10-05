# AI Cabinet 全仓代码审计报告（2026-10-05）

> **审计背景**：工程于 2026-10-04 从 `C:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet` 搬家至 `D:\ai-generated code\ai-cabinet`（搬家事故已恢复，基线 GitHub `dev`）。本次为搬家后首次全仓审计，基线 commit `3650a295`（dev，工作区干净）。
>
> **审计方式**：6 路并行只读审计（资金链路 / 库存仓储分仓 / 设备边缘视觉 / admin-RBAC / 双小程序 / 基础设施 CI），**随后全部 55 条发现（P1×9 + P2×21 + P3×25）经主审逐条独立复核源码（第二轮，见 §7 核实附录），无一撤回**。共覆盖 1484 个 Java 文件、86 个 Controller、303 个 Flyway 迁移、75 个 admin 视图、双小程序全源、12 份 compose。
>
> **结论速览**：**无 P0**。历史重大修复（F1 主体/F2/F3/P3-4/S1 主体/MinIO 匿名/Grafana 强口令/端口回环）全部在位无回归。新发现 **P1 × 9、P2 × 21、P3 × 25**，绝大多数为低成本修复（多为一至几十行）。

---

## 0. 搬家相关事项（本次特有 · 已于审计同日处置）

| 事项 | 状态 | 说明 |
|------|------|------|
| `.workbuddy/` 记忆目录 | ✅ **骨架已重建** | `memory/MEMORY.md`（铁律索引，标注待回填项）+ `memory/2026-10-05.md`（当日日志）已建。原 `PROJECT-REFERENCE.md` 外置手册与按日日志**永久丢失**，回填清单见 MEMORY.md |
| `BOOT-INF/lib/`（项目根） | ✅ **已删除** | 删除前确认仅含 `common-core-0.1.0-SNAPSHOT.jar`、未被 git 跟踪、gitignore 已排除 |
| 真实第三方凭据 | ⚠️ **本地层面已核净，云端处置 + 轮换须用户执行** | 实测旧位置 `OneDrive\Desktop\demo\ai-cabinet\` **已无 `infra/` 目录**，全树（排除 node_modules/target）无 `.env*`/`*.pem`/`*.jks`/`*.p12`/bootstrap CSV 残留。剩余风险：搬家前若曾被 OneDrive 同步，云端副本在 **OneDrive 在线回收站**（个人版保留 30 天），需用户网页端清空；轮换清单见 §7.2 |
| git 状态 | ✅ 干净 | dev 与 origin/dev 同步，最新提交与源码/静态产物已对齐 |

---

## 1. P1 — 高危（建议本周内修复）

### P1-1 【资金】`BalanceInsufficientException` 被 `runWithOrderPaymentLock` 包装成 500，F1-B 信号链断裂 + 事务毒化
- **位置**：`services/trade-service/src/main/java/com/aicabinet/trade/service/OrderPaymentService.java:951-954`（已复核）；抛出点 `:390`；捕获失败点 `SettlementOrderFinalizeService.java:177-190,211-217`
- **机理**：F1-B 设计为「余额不足 → 抛 `BalanceInsufficientException` → `chargeOrder` 的 `noRollbackFor` 保住冲抵 → 差额转 PENDING」。但 `runWithOrderPaymentLock` 的 `catch (Exception e)` 把它包装成 `ResponseStatusException(500)`，noRollbackFor 匹配不到原始类型 → 参与中的结算事务被标记 rollback-only；`SettlementOrderFinalizeService` 的 `catch (BalanceInsufficientException)` 也匹配不到（已变形）。
- **触发**：结算预检通过后、行锁重查前，用户余额被并发扣减（窄窗口）。
- **后果**：整个结算事务回滚 → 会话置 FAILED → **货已离柜但无订单**；且 FAILED 态无预授权释放清扫路径（叠加 P2-4），用户冻结资金悬挂需人工解冻。
- **修复**：在 `runWithOrderPaymentLock`（及 `UnpaidOrderService.java:450-453`、`DisputeService.java:1452-1455` 同款包装器）的 rethrow 列表加入 `BalanceInsufficientException`（对齐 `SessionService.java:633`、`SettlementService.java:230` 已有写法）。**一行级改动。**

### P1-2 【资金】`cabinet_order.session_id` 无 DB 唯一约束，防重复落单仅剩代码防线
- **位置**：`services/trade-service/src/main/resources/db/migration/V2__user_order_sku.sql:22,40`（仅普通索引 `idx_order_session`）
- **机理**：防重复结算靠「settle 分布式锁（60s 租约）+ `findByIdForUpdate` + 复查」三重代码防线。主链路当前推演成立，但锁提前过期 / Redis 抖动 / vision 超时叠加时无 DB 兜底，一旦穿透即同会话双订单双扣款。
- **修复**：先清历史重复，再 `CREATE UNIQUE INDEX uk_cabinet_order_session ON cabinet_order(session_id)`，作为纵深防御。

### P1-3 【库存】整仓盘点过账用「建单时快照账面」算差量，并发出入库时静默错账
- **位置**：`services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseStocktakeService.java:99-109`（建单固化 `line.setBookQty(inv.getQuantity())`，已复核）、`:244-248` → `WarehouseService.java:242-257`（`delta = counted − bookQty` 扣的是当前余量）
- **后果**：建单→过账窗口内有采购收货/出库时差量被双重应用（账面 10、卖出 3、实盘 7 → 再扣 3 → 真实余量 4）。因调整自带 `STOCKTAKE` 流水，`WAREHOUSE_LEDGER` 巡检恒绿，**错账完全静默**且污染月结损耗列。
- **修复**：过账时以当前 `warehouse_inventory` 余额重算 delta；或过账前校验「当前账面 == line.bookQty」不等则 409 强制重建盘点单。

### P1-4 【库存】`cleanupStaleOutbounds` 同事务吞内层 `@Transactional` 异常 → rollback-only，整批清理必然 500
- **位置**：`services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseService.java:833-855`（已复核调用链）
- **机理**：`self.cancelUnreceivedOutbound(...)` 经代理以 REQUIRED 合入外层事务；内层拿不到分布式锁或撞 `WAREHOUSE_OUTBOUND_CANCEL_BLOCKED` 抛 RSE 时，Spring 把共享事务标 rollback-only。外层 `catch { skipped++ }` 吞不掉这个标记。
- **后果**：`POST /warehouse/outbounds/cleanup-stale` 只要跳过 1 条（并发持锁时极易发生），全部已「成功」清理一并回滚且返回 500。
- **修复**：单条取消改 `REQUIRES_NEW`，或先收集候选、事务外逐条处理。

### P1-5 【设备/结算】`/internal/v1/vision/edge-results` 无设备绑定 + fleet 共享 `INTERNAL_API_KEY` → 可伪造任意会话识别结果驱动结算
- **位置**：`VisionInternalController.java:51-55`；`common/dto/VisionRecognitionResultDto.java:24-35`（**DTO 无 deviceId 字段**，已复核）；`VisionResultIngestService.java:68-97`
- **机理**：同族内部端点均有设备绑定（`SessionService.persistAttachedVideo`、`SessionDoorService.java:91-93` 都校验 `session.getDeviceId() == request.deviceId()`），唯独 edge-results——**唯一的结算驱动入口**——不校验「上报者 = 会话所属柜机」。而设备侧内部凭据是全 fleet 单一共享 key（`edge/android-app/app/build.gradle.kts:32` 内嵌、`EdgeRuntimeConfig.kt:55-56` 现场注入同值），任一台柜机被逆向即全员泄露。持 key 者可为任意 RECOGNIZING 会话伪造识别结果（多算=多扣顾客钱）、还能调 open-door/ops-command 开门任意柜机。
- **修复**：① edge-results 增加 deviceId 并强校验与会话一致（对齐 attachVideo）；② 设备侧内部凭据改逐设备派生（可复用 device_mqtt_credential），或对上报加 HMAC；③ APK release 变体不再内嵌默认 key。**接真硬件前必须关闭。**

### P1-6 【设备】MQTT 凭据轮换「restart ≠ 生效」陷阱仅靠文档约束，吊销可静默失效
- **位置**：`docs/S1_DEVICE_CREDENTIAL_DESIGN.md:89-93`（已实测记录：`docker restart` 后 bootstrap 导入失败、旧密码继续有效，必须 `--force-recreate`）
- **缺口**：`scripts/deploy-production.ps1`、`verify-production-readiness.ps1`、`check-env.ps1:120-121` 均无 bootstrap CSV 与 EMQX 内置库一致性校验。「运营台吊销成功」但被吊销柜机仍可凭旧 secret 收发。
- **修复**：① bootstrap 生成融入 deploy 并强制 `--force-recreate emqx`；② 部署后比对内置库用户哈希 vs CSV，不一致即 fail；③ 中期走 EMQX REST 运行时同步。

### P1-7 【admin/后端】服务端 CSV 导出公式注入中和未收口：9 份 `csv()` 副本仅 2 处走权威 `CsvCells`
- **位置**（已复核副本数量=9、仅 `MerchantService`/`MerchantDevicePortalService` 引用 CsvCells）：
  - 最弱：`FundBillService.java:430`（只查逗号，引号/换行/公式前缀全不防）
  - 流量最大：`OpsSessionOrderQueryService.java:389`（订单/开门记录导出）、`OpsCsvExportService.java:228`（含**商户提现**导出）、`OpsGapFeaturesController.java:338`、`CompetitiveGapService.java:513`（无公式中和）
  - 缺 TAB/CR：`MerchantPortalService.java:522`、`MerchantFinanceService.java:520`
- **后果**：`skuName`/`merchantName`/`rejectReason` 等用户可控字段以 `=`、`+`、`-`、`@` 开头时，运营用 Excel 打开即触发公式执行（DDE/钓鱼）。前端导出已中和，仅服务端带缺口。
- **修复**：7 份副本统一改调 `CsvCells.escape`（补 TAB/CR），加门禁禁止新增 `private static String csv(`。一次性 PR 可完成。

### P1-8 【小程序/后端】分仓采购「单价」契约信任前端传入正值
- **位置**：`ProcurementService.java:712-733` `fillSatelliteLineCosts`（已复核：`if (cost <= 0 && ...)` 才回填目录价）；请求侧 `clients/merchant-mp/src/utils/merchant-api.ts:874-884`
- **机理**：`unitCostCents` 取自页面本地状态，后端对正值照单全收，并进入 `receivedValueCents` 喂给 `supplierPayableService.recordReceive(...)`（供应商应付/库存估值）。该路径专为无 `ops:procurement:edit` 权限的补货员开放。
- **后果**：改包客户端可提交任意正单价，扭曲应付账款与成本核算。
- **修复**：分仓路径服务端**无条件**用 `catalogUnitCost(sku)` 覆盖；DTO 上对 satellite 契约去掉该字段。

### P1-9 【基础设施】真实第三方凭据明文存本地 env，OneDrive 时代可能已上云
- **位置**（已复核键存在，值已截断）：`infra/.env.sandbox:30`（DeepSeek key）、`:54-55`（完整支付宝沙箱 RSA 私钥 ~1700 字符）、`infra/.env:65`（高德 Web Key）
- **现状**：均被 gitignore、未进 git（`git log --all -S` 无命中）。但项目 10-04 前位于 OneDrive，这些文件极可能曾同步至云端。
- **处置**：① 立即在 DeepSeek 平台轮换该 key（真实余额账户即资损面）；② 检查 OneDrive 回收站/版本历史并清除；③ 高德 key 配域名白名单；④ 长期改脚本注入，不再文件明文。

---

## 2. P2 — 中危

### 2.1 资金链路（4 条）

| # | 发现 | 位置 | 影响 |
|---|------|------|------|
| P2-1 | `recordOperation` 落库的 CHARGE/REFUND/ADJUST_CHARGE 流水缺 `user_id` | `OrderPaymentService.java:847-863` | `countRefundsSince` 按 user_id 统计不到渠道退款 → **每日自助退款 ≤3 次限额被渠道退款绕过**；用户侧流水不可见 |
| P2-2 | 争议退款渠道 HTTP 被 `@Transactional` 外层事务包裹，与自身设计矛盾 | `DisputeService.java:242-254` → `executeFullRefund` | 渠道退款成功后外层回滚 → 本地“未退”钱已退；幂等号稳定使双退风险低，主要账实不一致 + 长事务占连接 |
| P2-3 | 争议确认补差：渠道差额已扣但订单行/状态落库失败时无自动补偿告警 | `SettlementConfirmDisputeService.java:73-77` | 有 `INITIATED` 痕迹但 finalize 失败仅抛 500，无 ops 告警单 |
| P2-4 | 会话 FAILED 态无预授权释放清扫（与 P1-1 叠加放大） | `SessionExpireService.java` 四清扫器均不覆盖 FAILED | FAILED + FROZEN 成为资金悬挂盲区，仅人工 `forceCancelForOperations` 可解 |

### 2.2 库存仓储（4 条）

| # | 发现 | 位置 | 影响 |
|---|------|------|------|
| P2-5 | 运营侧收货可转投任意仓库（含无主仓），绕过「日常采购必须入分仓」治理 | `ProcurementService.java:495-503`（不校验 `requireManagedWarehouse`） | 货入无主仓后 `resolveOutboundWarehouseId` 拒绝出库 → 库存搁浅 |
| P2-6 | `sumAllocatedQty` 把已作废行仍算作占用 → 幻影占用 | `WarehouseOutboundLineMapper.xml:6-16`（未滤 `handover_status`） | 后续合法出库被误判库存不足（方向保守不超卖，但可用量失真） |
| P2-7 | 发运扣库行级锁无全局排序，存在 PG 死锁窗口 | `WarehouseService.java:668-675` | 两出库单行序相反且共用批次时互相等待，PG 中止一方（500） |
| P2-8 | 手工入库仍可落无主默认仓，与出库治理自相矛盾 | `WarehouseService.java:159-161,1271-1283` | 与「禁止再落无主默认仓」注释矛盾；演示流在仓无负责人时 400 |

### 2.3 设备/边缘/视觉（5 条）

| # | 发现 | 位置 | 影响 |
|---|------|------|------|
| P2-9 | 设备 MQTT secret 明文落库（`device_mqtt_credential.mqtt_secret`） | `V290__device_mqtt_credential.sql:12`、`DeviceMqttCredentialService.java:26-30` | 拖库=一次性拿到全 fleet 设备 secret，S1 隔离收益被单点归零。应改 PBKDF2 哈希 |
| P2-10 | 生产 MQTT TLS「有校验、无供给」：truststore 全链无生成/下发/校验 | `application.yml:27-28`、`docker-compose.production.yml:48` | 真机接入时要么启动失败要么现场关 TLS 明文接入 |
| P2-11 | 生产 compose EMQX 端口策略缺失（默认回环，真机必手改且无门禁） | `docker-compose.production.yml:57-78` 无 ports 段 | 手改过程易顺手开 `0.0.0.0:1883` 明文 |
| P2-12 | 心跳/告警回报 body.deviceId 可覆盖 topic 来源（door 事件却拒绝 mismatch） | `MqttEventListener.java:199-214` | 持本柜凭据可伪造他柜心跳/刷告警（不触资金） |
| P2-13 | staging `RECOGNIZER_BACKEND` 默认 `yolo` 已进弃用名单，按默认启动即崩 | `docker-compose.staging.yml:51-54` + `factory.py:14-26` | fail-loud 非静默，但 staging 预发起不来；`INSTALL_ML` 装无用依赖扩大攻击面 |

### 2.4 admin 前端（2 条）

| # | 发现 | 位置 | 影响 |
|---|------|------|------|
| P2-14 | AdminEndpoints 门禁双重盲区 + 实际漏网 | `scripts/check-admin-endpoints.mjs:9-14`（不扫 components/layouts）、`:45-51`（允许清单反查，清单外放行）；漏网 `SkuVisionEnrollView.vue:1245`、`stores/brand.ts:43` | D12「禁止散落裸路径」约定可被新端点静默绕过（漏网处后端有 `@RequiresPermissions`，属收口破约非越权） |
| P2-15 | money-ui-contracts 缺线长提现契约，视图手写重复 | `LineManagerView.vue:803-813,1204-1237` vs `money-ui-contracts.ts:63-96` | 状态机调整时改漏线长域，文案双源漂移 |

### 2.5 双小程序（4 条）

| # | 发现 | 位置 | 影响 |
|---|------|------|------|
| P2-16 | 微信充值 mock/live 判定与注释相反，歧义时 fail-open 到 mock | `clients/consumer-mp/src/utils/recharge.ts:266-269` | 生产漏发 `debugInfo.mode` 时误调 dev 接口（后端 `@ConditionalOnProperty` 兜底为 404，非免费充值，但代码与声明不变量相悖） |
| P2-17 | 支付宝充值同样默认 mock（更激进：仅显式 `mode=live` 才走真渠道） | `recharge.ts:294` | 同上；且与微信侧判定逻辑不一致 |
| P2-18 | 生产包保留 mock 微信网页授权码路径，仅靠公开配置开关区分（无 `isDevBuild` 前置） | `clients/consumer-mp/src/pages/login/login.vue:390-398` | 生产配置误置 `wechatH5OauthEnabled=true` 时线上包拿固定假 code 登录，安全押在后端拒绝上 |
| P2-19 | 分仓收货：行级校验门闩用 `some()` 而提交发全量行，批次/效期默认自动填充 | `pages/purchase/purchase.vue:222-231,284-304` | 未核对行可静默入库（越权无虞：后端强制 `order.warehouseId == mine.warehouseId`） |

### 2.6 基础设施（5 条）

| # | 发现 | 位置 | 影响 |
|---|------|------|------|
| P2-20 | staging 对账 mock 三层默认开（模板即教用户开 mock） | `.env.staging.example:22`、`docker-compose.staging.yml:30`、`application-staging.yml:15` | 「接近 prod 的环境」跑 mock 对账，与 production 钉死风格不一致 |
| P2-21 | staging Postgres 密码自相矛盾：base compose 硬编码 `aicabinet` 不读 env | `docker-compose.yml:14` vs `.env.staging:6` | 按模板设强密码则应用连不上库；保持弱密码则被 `ProductionStartupValidator` 拒启，staging 部署链靠手工对齐 |
| P2-22 | base compose MinIO 硬编码 `minioadmin/minioadmin` 不读 env，staging 继承弱口令 | `docker-compose.yml:84-85` | `.env` 改密码对 base+apps 组合无效（静默漂移） |
| P2-23 | nginx 三份配置漂移：device 限速 zone 仅 `nginx.conf` 有 | `infra/gateway/nginx.conf:3` vs `nginx-full.conf`/`nginx.compose.conf` | compose 部署的栈中**设备接口无网关限速**（已知 P1 遗留，仍漂移） |
| P2-24 | staging sms-webhook-mock 绑 `0.0.0.0`（唯一非回环豁免） | `docker-compose.staging.yml:11-12` | 局域网任意主机可打 mock 短信回调干扰联调；有书面豁免但建议改回环 |

---

## 3. P3 — 低危 / 建议（摘要）

**资金**：netCompletedCents 内存聚合建议下沉 SQL SUM；`deterministicRefundNo` hashCode 双拼理论碰撞。
**库存**：`device_sku_lot` 缺 (device,sku,batch,slot) 唯一索引；`restoreToBatch` 兜底 lot 自动造「+365 天可售」批次应改 BLOCKED；月结全量加载流水长期 O(全史)；V302/V303 迁移评审头不齐；采购价兜底用零售价 `max(priceCents,1)` 虚增应付；`InventoryOpsService` 死代码。
**设备**：APK 内嵌 dev 共享凭据（release 应置空）；边缘 secret 加密失败静默回退明文（应拒绝联网）；命令去重仅内存（重启后 QoS1 重投窗口）；dev ACL 未对齐 `${username}` 语义；`.env.production.example` 残留共享账号变量；door 转发对 4xx 一律重试 3 次。
**admin**：token-storage 门禁按键名匹配（改名即失明）；`admin_phone` 明文 localStorage；table-gate-baseline 18 项豁免无过期机制。
**小程序**：消费者视频页接受任意 `?url=` 深链（钓鱼承载面）；consumer 端点门禁不扫 utils/components（潜在盲区）；order-detail 死引用 `videoUrl`；税号页加载失败静默清空表单且不验 18 位格式；`secureRandomToken` 兜底弱随机。
**基础设施**：Grafana dev 默认 admin/admin（仅 dev）；`grafana.htpasswd` 用 apr1(MD5) 且被跟踪（建议换 bcrypt）；CI 无顶层 `permissions: contents: read`；E2E 弱口令 123456 泛滥（已验证生产免疫）；XXL dev 弱默认（生产钉死）；MinIO `latest` 未 pin；仓库卫生（993KB 会话日志、过期 alipay-test.html、4.6MB mp4、大图 PNG 被跟踪）；旧 Dockerfile root 运行（建议删）；无 logback PII 脱敏兜底；staging `CHECKOUT_BALANCE_ONLY` 默认 true（有注释，有意为之）。

---

## 4. 历史问题回归验证（全部在位）

| 历史编号 | 结论 | 关键证据 |
|---|---|---|
| F1 余额扣款竞态 | **主体在位**，但 P1-1 所述异常包装缺口需补 | `noRollbackFor`（OrderPaymentService:121,133）、净额口径（:376）、PREAUTH_CAPTURE 挂单计入净额、markPaid 净额三分支、F1-C 净额>0 禁自动关单 |
| F2 超时免单旁路 | ✅ 已修复 | `SessionExpireService.java:386-451`：超时改 DISPUTED + createTimeoutTicket |
| F3 提现盲置 FAILED | ✅ 已修复且单点化 | `WithdrawPayoutPolicy.java:19-21` 仅 MOCK 可自动失败，两侧委托 |
| P3-4 争议自动免单 | ✅ 默认 OFF + 门控完整 | `DisputeAutoWaiveProperties` 默认 false、四 yml 均未开、单轮上限/防薅/告警齐全 |
| SLA 扫描竞态（M01） | ✅ 已修复 | `updateSlaMarkers` 仅列级更新，无人再整实体 save |
| S1 设备独立凭据 | **主体在位**，P1-6/P2-9/P2-10 为运维与供给链缺口 | 生产 bootstrap 无共享账号、ACL `${username}` + NO_MATCH deny、伪装 UAT 三用例 |
| MinIO 匿名桶 / Grafana admin/admin / 9999·13000 绑 0.0.0.0 | ✅ 全部闭环 | `mc anonymous set none` ×3 套 compose；production `:?` 强制；端口门禁化 |
| 分仓权限三角 / 混仓无主仓拒绝 / 月结件数金额分离 | ✅ 设计意图真实落地 | 本人仓 + 订单仓匹配双校验；跨仓 400；`WarehouseMonthlyCloseMath` 纯件数公式 |

---

## 5. 已验证无问题的重点方面（抽验方式见各域）

1. **金额单位**：全资金类无 `double/float` 参与计算，统一分（int/long）；分账取整方向安全（merchantShare=gross−platform 吸收尾差）。
2. **状态机旁路**：grep 全库 `setState(` 仅 `SessionService.transition()` 与补货初态赋值两处合规点。
3. **越权/IDOR**：消费者订单/会话/争议/退款全部本人校验且非本人 404 防枚举；商户端 IDOR 全依赖服务端归属校验，前端无跨商户参数伪造点；admin 8 个敏感写操作（退款/免单/调账/提现审核/补货开门）均有 `@RequiresPermissions` + fail-closed `PermissionAspect` 配套。
4. **幂等体系**：扣款/冲抵/退款/补差/充值/提现幂等键先查后插 + 行锁内复查；渠道回调先验签 + 时间戳 + nonce + mchid；mock 回调控制器 `@ConditionalOnProperty` + 本人订单双校验。
5. **分布式锁**：覆盖开门/会话/结算/支付/预授权/余额/双钱包/争议/分账，锁序单向无倒置。
6. **vision 上传安全**：流式 20MB 上限、文件名清洗 + 路径逃逸校验、`file://` 限定缓存目录、扩展名白名单——无路径穿越。
7. **vision mock 生产钉死三层防呆**：compose 强制 + 运行时 secure-env 双查 + debug 端点 403。
8. **视频链路**：presign 归属校验（会话 + deviceId/userId + objectKey 前缀）、消费端点全在登录拦截内、预签名拒绝 `file://`/`http(s)`。
9. **XSS**：admin-vue 全源 0 处 v-html/innerHTML；前端 CSV 导出统一 `escapeCsvCell`（缺口仅在服务端，见 P1-7）。
10. **PII 脱敏**：管理端用户/运营/审计三处 `PhoneMask.mask`。
11. **小程序敏感信息**：两端零 console.log；token 仅存业务态；manifest 无硬编码 appid（发布期注入）；`.env*` 仅 example 入库。
12. **shared-types 同步**：generated 与 dist 同刻构建；CI 以 `OPENAPI_CHECK_REGEN=1` 零 diff 强制。
13. **admin 静态产物**：与源码同步（最近提交源码文案在产物 chunk 中 grep 命中，`git status` 干净）。
14. **git 卫生**：无 .pem/.jks/.p12/.key/.env 实体被跟踪；生产 bootstrap CSV 已忽略；无真实密钥进 git 历史。

---

## 6. 总体结论与修复路线建议

**总体评价**：项目安全工程化水平显著高于典型同规模项目——生产 fail-loud 覆盖率、CI 双层密钥扫描、40+ 审计门禁、幂等/锁/巡检体系、历史修复的回归钉住都达到少见的高标准。**未发现 P0**，搬家后代码基线完整。

**风险画像**：新发现的 9 个 P1 呈三类聚集——
1. **异常语义边界**（P1-1 资金异常包装、P1-4 事务内吞异常）：修法都是几行；
2. **时序/纵深**（P1-2 session 唯一索引、P1-3 盘点快照差量）：补 DB 约束与差量基准；
3. **信任根与输入信任**（P1-5 edge-results 无绑定 + 共享 key、P1-8 前端单价直通、P1-7 CSV 中和碎片化、P1-6 轮换护栏、P1-9 本地凭据）：接真硬件与对外运营前必须收口。

**建议修复顺序**（按「后果 × 成本」）：
1. 本周：P1-1（一行）→ P1-4（REQUIRES_NEW）→ P1-7（CSV 一次性 PR）→ P1-8（服务端覆盖单价）→ P1-9（轮换凭据 + 清 OneDrive 残留）→ P1-3（盘点差量基准）；
2. 接真硬件前：P1-5（edge-results 绑定 + 逐设备凭据）→ P1-6（轮换护栏自动化）→ P2-9/P2-10/P2-11（secret 哈希化 + TLS 供给 + 端口策略）；
3. 随迭代：P2 其余 + P3 按域顺手清；
4. 运维项：~~重建 `.workbuddy/memory/`、删除根目录 `BOOT-INF/`~~ **已完成（2026-10-05）**；剩余用户动作：OneDrive 在线回收站清查 + DeepSeek/高德/支付宝沙箱凭据轮换（§7.2）。

---

## 7. 核实附录（2026-10-05 第二轮，主审逐条独立复核）

> 第一轮为 6 路并行子代理审计；本轮由主审对**全部 55 条发现逐条重新过源码取证**（grep/sed 直接读码），结论：**55/55 成立，无撤回、无降级**；另有 1 条补充观察（§7.3）。

### 7.1 逐条核实状态

| 编号 | 结论 | 关键复核证据（本轮亲验） |
|------|------|------|
| P1-1 | ✅ 成立 | `OrderPaymentService.java:953` `catch (Exception e) → RSE(500)` 确认包装；`BalanceInsufficientException` 仅继承 RuntimeException |
| P1-2 | ✅ 成立 | `V2:22,40` 仅普通索引；全迁移目录 `UNIQUE+cabinet_order` 仅 `uk_cabinet_order_pay_trade_no`（V239） |
| P1-3 | ✅ 成立 | `WarehouseStocktakeService.java:109` 建单时 `line.setBookQty(inv.getQuantity())` 快照固化 |
| P1-4 | ✅ 成立 | `cleanupStaleOutbounds` 带 `@Transactional`（:825），循环内 `self.cancelUnreceivedOutbound`（:838，内层 `@Transactional` REQUIRED 合入）+ `catch (RSE) skipped++`（:848-850）→ rollback-only 成立 |
| P1-5 | ✅ 成立 | `VisionRecognitionResultDto` 字段清单亲验：sessionId/taskId/traceId/items/overallConfidence/needReview/modelVersion/detectedClasses/provider/occurredAt——**确无 deviceId** |
| P1-6 | ✅ 成立 | `deploy-production.ps1`/`verify-production-readiness.ps1` grep emqx/bootstrap/force-recreate 零命中；`check-env.ps1` 仅校验 MQTT_BROKER ssl |
| P1-7 | ✅ 成立 | `grep "private static String csv(" = 9` 份；引用 `CsvCells` 的仅 `MerchantService`/`MerchantDevicePortalService` |
| P1-8 | ✅ 成立 | `fillSatelliteLineCosts`：`if (cost <= 0 && …) 才回填目录价`，正值直通 |
| P1-9 | ✅ 成立 | `infra/.env.sandbox:30,54-55`、`infra/.env:65` 键存在亲验；`git ls-files infra/` 仅 `.example` |
| P2-1 | ✅ 成立 | `recordOperation` 逐字段亲验无 `setUserId`；`countRefundsSince` 按 userId 过滤；`RefundPolicyService:96-99` 日限额用它 |
| P2-2 | ✅ 成立 | `refundByOperator/refundByConsumer` 均 `@Transactional`；链路 `executeFullRefund → waiveAndRefund（SettlementWaiveRefundService:58/65）→ OrderPaymentService.refundOrder:496 executeLiveChannelRefund` 亲验连通 |
| P2-3 | ✅ 成立 | `confirmDisputedItems`：`applyPaymentDelta`（渠道+短事务提交）后 `finalizeConfirmDispute` 失败仅抛错；全文件 grep alert/notify **零命中** |
| P2-4 | ✅ 成立 | `SessionExpireService` 四清扫器状态清单亲验（CREATED/OPENING、SHOPPING/WAITING_UPLOAD ×2、RECOGNIZING/WAITING_UPLOAD/SETTLING）**无 FAILED**；`SessionSettleService:123-128` 异常即 transition(FAILED) 不释放 |
| P2-5 | ✅ 成立 | `receivePurchaseOrder:416` 用 `resolveReceiveWarehouse`；`:487-491` 回写 `order.setWarehouseId`；该方法及上下文 `requireManagedWarehouse` 零命中 |
| P2-6 | ✅ 成立 | `sumAllocatedQty` SQL 仅滤 `o.status IN ('DRAFT','PICKED')`；`markDeviceLinesCancelled` 只改 handover_status+picked、不清 quantity |
| P2-7 | ✅ 成立 | `doShipOutbound` 按 line 顺序逐行 `deductWarehouseStock`，无全局排序 |
| P2-8 | ✅ 成立 | `inbound → resolveWarehouseId → resolveDefaultWarehouseId`（任意 ACTIVE 仓）；对照出库侧 `requireManagedOutboundWarehouse` 要求 `managerUserId != null` |
| P2-9 | ✅ 成立 | `V290:12` `mqtt_secret varchar(128) NOT NULL`；类注释自认「表里同时存明文与哈希——明文是生成 EMQX bootstrap CSV 所必需」 |
| P2-10 | ✅ 成立 | `application.yml:27-28` 默认空；工厂无 truststore 时回退 JVM 默认；生产强制 `ssl://emqx:8883`；`grep MQTT_TRUST_STORE infra/ scripts/ = 0` |
| P2-11 | ✅ 成立 | 生产 emqx 段亲验：仅 env+volumes，无 ports 段 |
| P2-12 | ✅ 成立 | `handleAlert`/`handleHeartbeat`：`node.has("deviceId")` 即覆盖 topic 来源；`handleDoorEvent` 却拒绝 mismatch |
| P2-13 | ✅ 成立 | staging `RECOGNIZER_BACKEND: ${RECOGNIZER_BACKEND:-yolo}`；`factory.py:14` `yolo ∈ _DEPRECATED_BACKENDS` |
| P2-14 | ✅ 成立 | `check-admin-endpoints.mjs` 扫描根=views/composables/stores（无 components/layouts）；机制=仅对允许清单做 includes 反查；漏网点 `SkuVisionEnrollView.vue:1245`、`brand.ts:43` 亲验且不在 endpoints.ts |
| P2-15 | ✅ 成立 | `money-ui-contracts.ts` grep LineWithdraw 零命中；`LineManagerView.vue:803-813` 手写 `canReviewWithdraw` 亲验 |
| P2-16 | ✅ 成立 | `recharge.ts:267-269`：注释称「字段丢失按 live 兜底」，代码 `rawMode==='live' \|\| prepay.wxPay ? 'live' : 'mock'` 双缺失时落 mock，与声明相反 |
| P2-17 | ✅ 成立 | `recharge.ts:294` 仅显式 `mode==='live'` 才走真渠道 |
| P2-18 | ✅ 成立 | `login.vue:395-398`：`cfg?.wechatH5OauthEnabled === 'true'` 即调 `consumerWxH5Login('dev-mock-web-code')`，无 isDevBuild 前置 |
| P2-19 | ✅ 成立（含补充观察 §7.3） | `hydrateReceiveLines` 预填批次/效期/数量；`canConfirmSatelliteReceive` 用 `.some()`；`receive():289-295` 提交全量行 |
| P2-20 | ✅ 成立 | 三层默认 true 亲验（example:22 / staging compose:30 / application-staging.yml:15） |
| P2-21 | ✅ 成立 | `docker-compose.yml:14` 硬编码 `aicabinet`；`apps.yml:65` 读 env；`.env.staging:6` 设了不同强密码 |
| P2-22 | ✅ 成立 | `docker-compose.yml:84-85` 硬编码 minioadmin；staging 层无覆盖 |
| P2-23 | ✅ 成立 | `device_ratelimit` 仅 `nginx.conf:3` 命中，另两份零命中 |
| P2-24 | ✅ 成立 | `"8099:8099"`（0.0.0.0）；豁免登记于 `check-compose-ports.mjs:20` |
| P3 资金×2 | ✅ 成立 | `netCompletedCents` selectList 后内存循环亲验；`deterministicRefundNo` 用 `hashCode()` 双拼亲验 |
| P3 库存×6 | ✅ 成立 | V24 仅普通索引；`createFallbackLot` 设 `+365 天 / ON_SALE`；月结 `findByWarehouseId` 无时间窗；V302/V303 头仅 `MIGRATION_KIND` 而 V301/V304 全套；`catalogUnitCost` 回退 `Math.max(priceCents,1)`；`InventoryOpsService` 409 闸门后 lot 分支永假 |
| P3 设备×6 | ✅ 成立 | build.gradle.kts 内嵌 dev 凭据+`INTERNAL_API_KEY`（:20-32）；`putSecret` 加密失败 warn 后明文落盘（:159-164）；`recentCommandIds` 内存有界集合（:90-102，正确路径为 `service/CabinetController.kt`）；dev ACL `cabinet/${clientid}/#` + dev csv 明文口令；`.env.production.example:57-58` 共享账号变量仍在；`TradeServiceClient` `catch (RuntimeException)` 3 次全重试 |
| P3 admin×3 | ✅ 成立 | token-storage 门禁正则仅匹配 `admin_token`/`TOKEN_KEY`；`admin_phone` localStorage 写点 3 处亲验；baseline `rawElTableFiles` 18 条 |
| P3 小程序×5 | ✅ 成立 | video.vue `opts?.url` 无 orderId 时直用；consumer 门禁扫描根仅 pages/composables（merchant 侧含 utils/components 对照）；order-detail `videoUrl` 仅写无读；tax.vue catch `taxForm.value = emptyTaxProfileForm()` 且 business-tax.ts 仅非空校验；secure-id.ts 兜底 `Date.now()+seq` 线性填充 |
| P3 基础设施×10 | ✅ 成立 | dev Grafana `:-admin`×2；htpasswd `$apr1$`；ci.yml/sonar.yml 无顶层 `permissions:`；123456 分布 37 文件（V117 演示账号+e2e 脚本+env example，生产 `seed_env:none` 免疫）；xxljob `:-xxljob`/`:-dev-xxl-token`；MinIO latest ×3；旧 Dockerfile USER=0；logback 文件 0 个；staging `CHECKOUT_BALANCE_ONLY:-true`；仓库卫生（993KB jsonl 已跟踪且已扫描无密钥、alipay-test.html ×2、sample-shopping.mp4 4.6MB 均被跟踪） |
| P3 BOOT-INF | ✅ 已处置 | 审计同日删除（删前确认未跟踪单 jar） |

### 7.2 凭据轮换清单（须用户在平台侧执行）

| 优先级 | 凭据 | 位置 | 动作 |
|--------|------|------|------|
| ① 立即 | DeepSeek API key（真实计费账户） | `infra/.env.sandbox:30` | 平台吊销旧 key → 签发新 key → 更新本地 env |
| ② 本周 | 高德 Web key | `infra/.env:65` | 控制台配域名白名单；若曾被同步，重置 key |
| ③ 低风险 | 支付宝沙箱 RSA 私钥（app_id 9021000140670062，沙箱网关） | `infra/.env.sandbox:54-55` | 沙箱应用可顺手重新生成密钥对 |
| ④ 云端 | OneDrive 在线回收站 | onedrive.com → 回收站 | 搜索 `ai-cabinet/infra/.env` 并**从回收站清除**（本地旧位置已实测无残留）；必要时查版本历史 |
| ⑤ 长期 | env 文件明文 | `infra/.env*` | 改脚本注入/密管，不在同步目录存明文 |

### 7.3 补充观察（核实过程中新发现，未列入原编号）

- **P2-19 附加**：`clients/merchant-mp/src/pages/purchase/purchase.vue:294` 提交时 `receivedQty: Number(line.orderedQty) || 0`——实收数恒等于要货数（整单全收语义），页面无实收数量编辑入口（`receivedQty` 仅出现于类型定义 :161、默认填充 :229、提交 :294 三处，无 v-model）。若产品预期「可改实收数」则为功能缺失；若整单全收为预期，则 P2-19 的 `.some()` 门闩问题依然成立，且「分仓收货页」应去掉数量可改的错觉。建议产品拍板后决定是否补实收数编辑。



---

## 8. 修复记录（2026-10-05 审计同日实施）

> 修复范围 = §6 路线的「本周」批次 + 「接真硬件前」批次的代码部分 + 低风险一行级 P2。验证：trade-service **全量 1421/1421（Skipped=0）**、device-service 受影响 24 用例、merchant-mp 153 + consumer-mp 135 单测、双端 vue-tsc、audit-gates 全链（41 门禁）、prettier/eslint——全部绿。

### 已修复（代码）

| 编号 | 修复内容 | 关键落点 |
|------|----------|----------|
| P1-1 | 四个锁包装器（OrderPaymentService/UnpaidOrderService/DisputeService×2）rethrow 列表补 `BalanceInsufficientException`，F1-B 信号链恢复 | `runWithOrderPaymentLock`/`runWithDisputeTicketLock` |
| P1-3 | 盘点过账差量基准改为「过账时刻当前账面」（行锁内读 current，直接对齐 counted） | `WarehouseService.adjustStocktake`；并发测试改写（调负库存构造上不可能，回归点改为锁释放） |
| P1-4 | `cleanupStaleOutbounds` 去外层 `@Transactional`，单条取消独立成事务，skip 不再毒化整批 | 方法 javadoc 说明成因 |
| P1-7 | 9 份 `csv()` 全部委托 `CsvCells.escape`；新增防回归门禁 `check:csv-escape`（进 audit-gates 聚合链，wiring 校验通过） | 7 处副本替换 + `scripts/check-csv-escape.mjs` |
| P1-8 | 分仓采购单价**无条件**服务端目录价覆盖；SKU 无目录价 400 | `fillSatelliteLineCosts`；测试夹具补目录价 |
| P1-5① | `VisionRecognitionResultDto` 新增**必填 `deviceId`**，ingest 强校验与会话柜机一致（先于状态判定防泄露）；e2e 脚本补 F6/F7 负对照；端点 javadoc 契约更新 | DTO/`VisionResultIngestService`/`verify-vision-result-ingest.py` |
| P2-1 | `recordOperation` 补 `setUserId`——渠道退款计入每日自助退款次数闸 | `OrderPaymentService` |
| P2-5 | 运营收货转投仓同样要求已指定负责人（无主仓 400） | `resolveReceiveWarehouse` |
| P2-6 | `sumAllocatedQty` 增加过滤 `handover_status <> 'CANCELLED'` | `WarehouseOutboundLineMapper.xml` |
| P2-7 | 发运扣库前按 (skuId,batchNo) 全局排序，消除 PG 死锁窗口 | `doShipOutbound` |
| P2-12 | 心跳/告警 body.deviceId 与 topic 不符即丢弃（对齐 door 口径） | `MqttEventListener.rejectDeviceIdMismatch` |
| P2-16/17 | 双充值通道 mock 判定 fail-closed：mock 必须显式声明，缺失/矛盾一律报错 | `consumer-mp/src/utils/recharge.ts` |
| P2-18 | 登录页 mock 授权码分支加 `isDevBuild` 前置 | `login.vue` |
| P2-19 | 收货门闩 `some()`→`every()` + 空行守卫 + 新单测 | `purchase-display.ts` |
| P2-20 | staging 对账 mock 三层默认改 false | compose/应用 yml/env 模板 |
| P2-21/22 | base compose Postgres/MinIO 凭据改读 env（dev 缺省不变） | `docker-compose.yml` |
| P2-24 | staging sms-mock 端口回环绑定 + 移除端口门禁豁免 | staging compose + `check-compose-ports.mjs` |
| P2-13 | staging 识别后端默认改 quectel（fail-loud 诚实姿态）、`VISION_INSTALL_ML` 默认 false | staging compose + application-staging.yml + `.env.staging` |

### 测试夹具同步

- `VisionResultIngestServiceTest`：DTO 构造 9 处补 deviceId + 新增 mismatch/缺 deviceId 两条负对照；**设备号用中性 `DEVICE_ID` 常量，不写死已废弃柜号**。
- `ProcurementServiceTest`/`ProcurementReceiveWarehouseTest`：补受管仓库与目录价 stub（新校验的预期 400 语义不变）。
- `WarehouseStockConcurrencyTest`：盘点异常路径改由流水写失败触发（P1-3 后「把库存调负」构造上不可能）。

### 未修（保持待办）

- **P1-9 凭据轮换**：须用户在平台侧执行（§7.2 清单）。
- **P1-2**（session_id 唯一索引）：需先清历史重复再建唯一索引，属 DB 变更，评审后单独出迁移（V305）。
- **P1-6** / P2-9（secret 哈希化，需解决 EMQX bootstrap plain 依赖明文的矛盾）/ P2-10 / P2-11：接真硬件前批次，需部署环境配合验证。
- **P2-2/3/4**（退款外层事务/补差告警/FAILED 预授权清扫）：资金失败语义重构，单独批次带测试做。
- P2-8/P2-14/P2-15/P2-23 及其余 P3：随迭代批次。镜像 pin（P3）需可验证的 tag 后再钉（本机无法核验 Docker Hub，不编造版本号）。



---

## 9. 修复记录 · 第二批（2026-10-05 续，应用户「剩余批次现在做」指令）

> 覆盖：P1-2、P1-6、P2-2/3/4、P2-8/11/14/15/23、快赢 P3×5，及 P1-9 处置。P2-9/P2-10 经评估保持接真前批次（见 §9.3）。验证：trade **全量 1421/0/0**（surefire 汇总）+ device 48/0/0 + admin 84 + merchant 153 + consumer 135 单测、三端 vue-tsc、audit-gates 全链（41 门禁）、prettier——全部绿。

### 9.1 已修复

| 编号 | 修复内容 | 关键落点 |
|------|----------|----------|
| **P1-2** | `V305__cabinet_order_session_unique.sql`：先 DO 块**显式检出历史重复**（发现重复即 RAISE EXCEPTION 并逐单列出，禁止盲建——资金记录如何处置属业务决策），无重复才建 `uk_cabinet_order_session` 唯一索引；评审头齐全过迁移门禁 | 防重复落单获得 DB 级最后防线 |
| **P2-4** | 第五个清扫器 `releaseStaleFailedSessionHolds`（每 5 分钟）：FAILED 且 preauthStatus=FROZEN 滞留 >1h 自动释放 + HIGH 异常留痕；`V306` 补 scheduled_task 种子行（运营台可见/可启停）；滞后 1h 避免与人工处置抢跑 | `SessionExpireService`；seed 门禁绿 |
| **P2-2** | `refundByOperator/refundByConsumer` 去外层 `@Transactional`（下游 waiveAndRefund/partialRefund 自带三段式编排；外层事务会让「渠道已退、本地回滚」账实漂移），javadoc 说明成因 | `DisputeService` |
| **P2-3** | 争议补差：`applyPaymentDelta`（渠道差额已落账）后的 `finalizeConfirmDispute` 失败时发 `OpsAlertDispatcher` HIGH 告警（ orderId/delta/金额上下文），告警失败不影响异常传播 | `SettlementConfirmDisputeService` |
| **P1-6** | `check-env.ps1` prod 段新增 bootstrap 护栏：CSV 缺失即 error（指向 gen 脚本）；`MQTT_USERNAME` 不在 CSV 即 error，并明示「restart 不重导 bootstrap，须 `--force-recreate emqx`」 | 部署链护栏自动化 |
| **P2-11** | 生产 compose emqx 段显式 ports 策略：1883/8883 绑定地址 env 可控（默认回环）、Dashboard 18083 钉死回环；`.env.production.example` 删除误导性的共享设备账号两行（P3-5）并补轮换红线注释 | `docker-compose.production.yml` |
| **P2-8** | 手工入库「空白→默认仓兜底」路径补 `requireManagedOutboundWarehouse`（显式指定仓不拦）；演示流走 createPurchaseOrder 已有校验不受影响 | `WarehouseService.inbound` |
| **P2-14** | `check-admin-endpoints` 硬化为**拒绝式**：扫描根扩 components/layouts（共 5 根），任何 `/api/` 字面量即报错（豁免 api/ 层与 *.test.ts 断言）；两处既有漏网已收口（recognition-preview→`AdminEndpoints.recognitionPreview`、ops-branding→`AdminEndpoints.opsBranding`） | 门禁 + endpoints.ts |
| **P2-15** | 线长提现三函数+审核体收进 `money-ui-contracts`（`canReviewLineWithdraw` 等四函数，复用商户版语义单点化）；`LineManagerView` 手写体全部改调契约 | money-ui-contracts.ts |
| **P2-23（修正）** | 复核发现原报告高估：`device_ratelimit` zone 在 nginx.conf **定义了但无任何 location 引用（死配置）**，且设备流量不经网关（device-service :8081 直连、无对外 HTTP 业务面）。修正=删除死 zone 消除三份配置漂移源，注释钉住「设备 HTTP 面未来入网关须三份同步」 | `nginx.conf` |
| P3 批 | ① ci.yml/sonar.yml 顶层 `permissions: contents: read`；② `Dockerfile.java-service` 加非 root USER（仍被 full 栈使用），`Dockerfile.java-runtime` 零引用已删；③ consumer 端点门禁扫描根补 utils/components——**随即抓到 feature-flags.ts 注释里两处裸路径**，改注释引用 endpoints 常量后过；④ order-detail 死引用 `videoUrl` 删除；⑤ 登录页 `showError` 多余 import 删除（eslint 归零） | 多处 |

### 9.2 P1-9 处置（应用户确认「不再需要 DeepSeek 做识别」）

- **DeepSeek**：代码复核确认云识别已退役（`factory.py` 不再返回 deepseek 后端、`_DEPRECATED_BACKENDS` 含 yolo_deepseek；仅 health 端点读配置展示）。**平台侧吊销即可**（无功能依赖）；本地 `infra/.env.sandbox` 的真实 key 已清空并留注释。
- **高德**：仓内实际是**两类 key**——`AMAP_WEB_KEY`（服务端 `AmapGeocodeService` 调 restapi.amap.com 地理编码）与 `AMAP_JS_KEY`（admin 前端地图 JS）。**Web 服务 key 无法配域名白名单**（那是 JS key 的机制），服务器 IP 白名单可选；**建议：JS key 在高德控制台配域名白名单（必做），Web key 若未配 IP 白名单则确认额度告警即可**。此前报告「高德 key 配域名白名单」的表述据此修正。

### 9.3 维持待办（含评估结论）

- **P2-9（MQTT secret 哈希化）**：EMQX 5.8 bootstrap 支持 `bootstrap_type=hash`（CSV 存 password_hash+salt，算法 sha256/pbkdf2 等），技术上可行；但需同步改库表语义（明文列→哈希列）、生成器、compose 三层，且 pbkdf2 参数须与 authenticator **逐参对齐**，本机无 EMQX 实例实测认证通过——盲改会让全 fleet 设备连不上。**保持接真硬件前批次，需部署环境配合验证**（文档参考：[EMQX 内置数据库认证](https://docs.emqx.com/en/emqx/latest/guides/access-control/authn/mnesia.html)）。
- **P2-10（TLS 供给链）**：同属接真前批次（私有 CA→EMQX 证书→device-service/柜机 truststore→部署脚本下发），需真实证书与设备联调。
- P2-12 已修（第一批）；其余 P3（grafana.htpasswd bcrypt、MinIO tag pin、htpasswd/仓库卫生等）随迭代。

### 9.4 第二批验证汇总

| 面 | 结果 |
|----|------|
| trade-service 全量 | **1421 tests, 0 failures, 0 errors**（surefire 汇总，Skipped=0） |
| device-service 全量 | 48 tests, 0 failures, 0 errors |
| admin-vue | 84 单测 + vue-tsc 绿 |
| merchant-mp | 153 单测 + vue-tsc 绿 |
| consumer-mp | 135 单测 + vue-tsc 绿 |
| 门禁 | audit-gates 全链 41 项 ✓（含新增拒绝式 admin-endpoints、csv-escape） |
| 迁移/种子 | check-migration-safety（V305/V306）✓、check-scheduled-task-seed ✓、check-xxl-job-wiring ✓ |
| 格式 | prettier 全部改净；eslint 改动文件 0 警告 |



---

## 10. 修复记录 · 第三批（2026-10-05 续：P2-19 附加拍板 + 收尾 P3）

### 10.1 P2-19 附加：分仓收货实收数量已可编辑（竞品对照后拍板）

- **竞品结论**：easygo 旧系统入库**一次性全额、实际到货数不入账**（`SYSTEM_COMPARISON_EASYGO_VS_AICABINET` §2/§4 判定「旧会账实漂移，新正确」）；本系统运营侧收货本就支持部分收货（`PARTIAL_RECEIVED` 状态机 + 质检闸）。
- **推荐与实现**：分仓收货页补**实收数量 stepper**（每行 ±，范围 1..要货数，默认=要货数；供应商少发/破损按实收入账，剩余量可后续再收），提交改用实收值（原恒等于要货数）。后端零改动（`processReceiveLine` 既有能力）。
- **落点**：`merchant-mp/src/pages/purchase/purchase.vue`（模板 + `adjustReceivedQty` + 提交）、`purchase.page.css`（行头布局）；merchant-mp 154 单测 + tsc 绿。

### 10.2 收尾 P3（5 项）

| 项 | 处置 |
|----|------|
| grafana.htpasswd apr1(MD5) | **已换 bcrypt**（`$2b$10$`，新口令已按 `.env.example` 约定登记本地 `infra/.env` 的 `GRAFANA_BASIC_AUTH_PASSWORD`；Git 内旧 apr1 哈希随本次提交失效） |
| 税号格式校验 | **已加**：`business-tax.ts` 18 位统一社会信用代码字符集校验（GB 32100-2015，不含 I/O/S/V/Z），新增 4 组单测 |
| 消费者视频页 `?url=` 深链 | **已收紧**：删除任意 url 兜底，只接受 orderId 后端鉴权拉流（对齐商户端；仓内跳转本就只传 orderId） |
| 仓库卫生 | `alipay-test.html`/`alipay-test-form.html` 已删（一次性沙箱表单、过期签名、零引用）；`sample-shopping.mp4` 被 `check:demo-video-format` 门禁锚定**保留**；`docs/recovery/`（搬家恢复资料）与 `docs/evidence/`（UAT 留证）为刻意保留，不动 |
| MinIO tag pin | 仍待办：需可联网核验 Docker Hub tag 时再钉（不编造版本号） |

### 10.3 第三批验证

merchant-mp 154 单测 + consumer-mp 135 单测 + 双端 vue-tsc 绿；csv-escape / compose-ports 门禁复跑绿。


*审计执行：6 路并行只读子代理（资金 / 库存 / 设备 / admin / 小程序 / 基础设施）；第二轮主审逐条独立复核全部 55 条发现（§7），无一撤回。发现已在 §1–§3 全量留痕（文件:行号），可直接转工单。*
