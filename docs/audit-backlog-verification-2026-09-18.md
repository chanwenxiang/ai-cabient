# 审计修复计划全量核实报告

- **日期**：2026-09-18
- **核实对象**：`C:\Users\cwx\.cursor\plans\full_audit_fix_backlog_c999e778.plan.md`（约 130 项）
- **方法**：8 个子代理按领域分批逐项对照当前工作区代码（含未提交修改）；H05/H40 由主会话补充核实。每项均要求给出 文件:行号 + 代码摘录。
- **结论口径**：【存在】问题真实且描述基本准确；【部分属实】本质存在但细节/机制/影响有偏差；【已修复或不存在】当前代码已修复或指控不成立。
- **总计**：130 项 = **存在 92 / 部分属实 29 / 已修复或不存在 9**。

---

## 一、已修复或不存在（9 项，建议从计划剔除或降级重验）

| ID | 计划描述 | 核实结论 |
|---|---|---|
| H01 | available 忽略 online | **已修复**。`DeviceValidationService.java:66-68` `available = online && active.isEmpty() && !replenishment && !locked`，offline 单列 busyReason；NearbyDeviceService 兜底分支也判 ONLINE（:81-85）。（注：两类的实际位置在 trade-service，不在 device-service） |
| H16 | XXL handler 失败仍 handleSuccess | **已修复**。`ScheduledTaskXxlJobHandler.java:211-214` 任何异常走 `XxlJobHelper.handleFail`，handleSuccess 仅在正常返回后调用 |
| H32 | deviceExists 异常当「不存在」 | **不成立**。`TradeServiceClient.java:113` 异常→false 是注释声明的 fail-closed 设计（trade 不可达不向未知设备发令）；唯一调用方 `DeviceCommandService.java:51-52` 据此抛 404 拒发指令 |
| H36 | 补货关在途但上架 0 | **不成立**。取消 SHIPPED 未签收行会回仓+作废在途（`WarehouseService.java:737-748`）；完成路径有在途签收与补签（`ReplenishmentService.java:1067`、`:804-817`） |
| H49 | 生产 EMQX 无主题 ACL | **已修复**。`docker-compose.production.yml:48-55` 配置文件授权器 `EMQX_AUTHORIZATION__NO_MATCH: deny` + `aicabinet-acl.conf` 按 clientId 隔离，另有 bootstrap 认证强制凭据 |
| H61 | 上传后先删本地 | **不成立（与描述相反）**。`OfflineUploadQueue.kt:87-90` 删除发生在 MinIO 上传且 trade attachVideo 确认成功之后；任一步失败文件保留重试，本地即兜底 |
| M04 | 充值超时取消用过期 status 仍 closeOrder | **已修复**。`PaymentService.java:460-471` 先 sync 再取消，`markRechargeCancelledIfPending`（:822-831）行锁+仅 PENDING 才置 CANCELLED，已支付单不会被取消 |
| L01 | stale-monitor-realart-minutes 拼写 | **已修复**。yml 与 Java 已统一为 `realert`（`application.yml:250`、`ScheduledTaskStaleMonitor.java:130`） |
| L02 | RechargeOrderDto 状态与 OpenAPI 漂移 | **不成立**。`RechargeOrderDto.java:10` 与 `openapi.ts:9857` 双方均为 plain string、无枚举声明，无「两套枚举不一致」可言 |

## 二、部分属实（29 项，修复前需先纠正计划描述）

### 资金/会话
- **C04** 部分退：本地行改/回库先独立提交、渠道 HTTP 在事务**外**（`OrderPaymentService.java:249-252` 注释「无外层长事务包裹渠道」）——计划写「渠道 HTTP 与写账同事务」恰好说反；因短事务先提交，渠道失败本地无法回滚，结果成立、机制相反。
- **C05** 余额退：①先渠道后退余额属实（`BalanceRefundService.java:224-229`）；②outRefundNo 随机尾缀属实（`:245-246` requestId+UUID 8位）；③「失败盲目解冻」**不成立**——catch 内 releaseFreeze 后 219 行必抛，整个 review 事务回滚，冻结实际未释放（状态也不会落 FAILED）。
- **C18** 补偿：PENDING **分布式事务**确实只 ++retry 永不重试（`CompensationTaskScheduler.java:250-258`）；但 PENDING **compensation_task**（实际唯一在产的 PROFIT_SHARING_RETURN）由 processTask 真实执行——指控只对事务表成立。
- **H63** 见下（存在）。**C12** notify 路径成立；mock 确认路径（confirmRechargeMock）有守卫且锁失败抛 409 是正确的——问题仅限 notify 回调路径。

### 鉴权
- **C16** 超管保护：doDisableOperator 仅防自禁、无角色校验、禁用无审计（`OpsRbacService.java:427-435`）；但重置密码有审计（:461）、自助改密需旧密码（:479-480）——「无任何保护」过重。
- **C17** PayScore 签约伪实现属实（`PayScoreService.java:96-99` 只写 PSC-*），但有 enabled/mockEnabled 门禁，扣费路径另要求 liveChargeEnabled+网关配置（:245-265）。
- **H67** 幂等键：ADMIN_ADJUST 键只含运营输入 key、不含 userId/金额（`OpsMemberFinanceAdminService.java:133-134`），同键跨用户重放会静默跳过第二次入账并写入带他人 balanceAfter 的错误审计；但去重命中即返回，**不会重复入账**；内部键（CHARGE:orderId 等）含全局唯一单号。

### Edge/小程序
- **C22** 离线上传仅 attachVideo 属实（`OfflineUploadQueue.kt:115`），但 CLOSED 已在入队前经持久化 OutboundMqttQueue 补投（`CabinetController.kt:175`），且 trade 侧 expireStaleConsumerShoppingSessions 兜底，不会永久卡 SHOPPING。
- **H60** 过期开门确实不回失败 ACK（`MqttDeviceClient.kt:201-204`），但 DeviceCommandTracker 15s 超时标 TIMEOUT（`DeviceCommandTracker.java:33`）+ 会话过期清扫，云端不会一直等。
- **H62** (a) 离线放弃仅 Log.e 无上报，成立；(b) 去重命中（`CabinetController.kt:98-100`）不发的只是 /evt 业务 ACK，MQTT PUBACK 由 Paho 正常返回，「导致 MQTT 重投」因果不成立。
- **M26** `useReplenishmentFulfillment.ts:181-183` 无 sessionId 时 doorOpened 确实置 true（仅本地标记），无上报行为，服务端门禁兜底。

### 支付渠道
- **H10** (a) 重放返回固定 MOCK_SIGN（`PaymentService.java:214-217`）但全库无验签逻辑消费它；真实缺陷是 live 已配置时也误返回 mock 参数；(b) 配置同时返回 mockEnabled/paymentModeHint 与 wechatPayLive（`SystemConfigService.java:234`），信息未隐瞒，仅预下单 live 优先。
- **H11** (a) 同步查单金额 0 仍按原额入账（:900/:978）成立；(b) notify 与取消均在 FOR UPDATE 行锁内做状态检查（:967-977、:822-831），CANCELLED 竞态已封住。
- **H13** notify_id 占位确在金额校验前（`AlipayNotifyService.java:40-45`）；但验签先行、支付宝重试用同一 notify_id、金额正确的后续通知是新 id，另有 syncPendingOrder 查单兜底——「正确通知被挡」夸大。
- **H48** 空 access-token 不鉴权属实（`XxlJobConfig.java:25-52`）；full/apps compose 默认空；但**生产 compose 用 `:?` 强制非空**（production.yml:17）——仅非生产环境默认空。

### 设备/库存/调度
- **H26** 日限额统计 `.in(PENDING_REVIEW,APPROVED,PAYING,PAID)`（`MerchantWithdrawRequestMapper.java:31`）排除终态是正确的；真实问题是 **FAILED 冻结不释放**（`MerchantWithdrawService.java:426`，仅人工 cancelFailed :312-333 解冻）且不计入限额——期间可再申请致总冻结超日限额；指控的两个具体形态（占用限额/重复冻结）都不成立。
- **H27** 未绑定→全局属实（`MerchantScopeService.java:80`）但是类注释 :36 文档化的设计；MerchantPortalGuard 拒绝全局账号进商户门户（:33-38）；空集路径 fail-closed（:249）——策略取舍而非意外越权。
- **H30** 场地租金：(a) markPaid/void 确无行锁（`:119,139`）但 markPaid 只是人工标记、无资金动作；(b) **VOID 占 UK 成立**（V255 uk 不含 status，`countNonVoid` 判重却允许重出）；(c) **INACTIVE 参与 10000bps 校验但出账只 filter ACTIVE，余量静默并入第一条 ACTIVE 规则**（`SiteRentSplitService.java:73-77`、`SiteRentBillService.java:169-172,:220`）成立。
- **H31** 客户端请求体并无 UUID（`DeviceServiceClient.java:26-33`）；真实机制是 device-service 对每次 HTTP 生成新 commandId（`MqttCommandPublisher.java:69`）且无 sessionId 级去重——「响应丢失后重试重复开门」窗口存在，机制与描述不符。
- **H38** 无 PAYING 自动恢复任务属实；但打款是同步调用、markPaying 允许从 PAYING 重入（`LineWithdrawService.java:297`），重新 executePayout 可人工恢复，非「永久卡死」。
- **H54** tracker 超时只标记不回调属实（`DeviceCommandTracker.java:163-170`）；但 trade 的 expireStaleOpeningSessions（30s 扫描/90s 超时）兜底取消+解冻——恢复延迟而非永久卡死。
- **H56** (a) 离线扫描用 updatedAt（`DevicePresenceService.java:162`）而 OTA reportVersion 每次轮询无条件 save 刷活（`OtaService.java:130-135`）——升级中误判在线属实；(b) 自动锁售后每次开门都复核 salesLockedEnabled（`DeviceValidationService.java:100-102`），「售不复核」不成立（恢复后不解锁是有意设计）。

### 基建/admin/vision
- **H04** (a) EMQX 匿名**已修**：full.yml:36-45 内置库认证+no_match=deny；(b) DB/MQ/MinIO 已绑 127.0.0.1，但 apps 叠加层 8080/8081/8082 未绑回环；(c) dev 可猜口令系文件头标注的有意设计，生产叠加 `:?` 强制强口令；(d) 非生产 XXL token 默认空（full.yml:187）成立。
- **H44** 活动存在性已校验（`AdCampaignService.java:224`）；但 RUNNING 状态、设备归属、assetId 未校验——登录用户可污染任意设备/活动的统计。
- **H45** settle 确无环境/开关守卫；但需 ops:sku:demo 权限，CLOSE_ONLY 仅限本人会话且 deviceId 匹配、mock 识别结果被拒（`RecognitionTestService.java:146-173`）——「随意结算任意会话」不成立。
- **H69** (a) `_IS_PROD` 不认 staging（vision main.py:24-27），staging 走 dev 逻辑（/docs 开放、prod 守卫不生效、MOCK/默认 Key 可启动）属实；(b) **prod 下 MOCK/默认 Key 会拒绝启动**（:43-46）——洞在 staging 不在 prod。

### Medium/Low
- **H05** XXL 种子注册了 opsFeeBillMonthlyJob（`seed_aicabinet_jobs.sql:71-73`，id=119），但 `OpsFeeBillJob` 的 `@ConditionalOnProperty(matchIfMissing=true)` + `FEE_BILL_AUTO_GENERATE_ENABLED` 默认 true，**当前无任何环境关闭它**，两侧一致运行；仅当显式设 false 才出现「XXL 每月 handleFail(任务未注册)」，且 `ScheduledTaskRegistry.java:119-133` 已显式记账 conditionallyAbsent + 看护豁免（注释承认此取舍）——文档化的设计取舍，非现时缺陷。
- **M07** shared-dict 确缺 SALES_LOCKED/PROFIT_SHARING_*（index.ts:557-568），但后端 SysDictBootstrap seed 已含这两类（:202-211）；另有 4 个后端实际会发出的告警类型（PROFIT_SHARING_RETURN_SUBMIT_FAILED、XXL_JOB_WIRING_BROKEN、SCHEDULED_TASK_STALE、VISION_ANOMALY）**连后端 seed 也没有**。
- **M11** 去重键先写后转发属实（`MqttEventListener.java:226`），但 TTL 60s 且转发异常会 clear——有限窗口丢失，非「永久去重」。
- **L07** 字典编辑后已主动刷新（`DictManageView.vue:481,535`）；剩余仅「拉取失败保留会话级旧缓存、无 TTL」是 dict-runtime.ts:22-24 注释声明的设计。

## 三、确认存在（92 项，描述与代码相符）

### 资金/会话（存在 11）
C01（V252 无守卫改最新 PAID 订单=441，全 profile 共用 migration 目录）、C02（V202:55-59 全量 FAIL→FIXED）、C03（免单 set flag 不落库，重试重复回库）、C06（争议先动库存后收差额，扣款失败库存已提交）、C07（DISPUTED 用券只调总额无退款）、C08（识别超时升级无 session:life 锁/行锁，同文件其它路径都有）、C09（识别超时不 releaseIfFrozen，且 DISPUTED/FAILED 不被 forceCancel 兜底）、C10（markOpenDoorFailed 无锁无 CAS；releaseIfFrozen 失败仅 warn）、C12（notify 入账抢锁失败静默 return，控制器仍无条件 204/success，渠道不再重试）、C20（审核锁外读行+无 @Version，并发双渠道退）、H63（余额退逐片渠道退，中途失败已成功切片 DB 痕迹被回滚、重试再退）。

### 鉴权/RBAC/协议（存在 9）
C13（登录信任客户端 phoneNumber 绑 openid 到已有账号）、C14（换绑手机无短信验证）、C15（ops:admin 可入自定义角色；持之放行全部权限）、H22（短信验证码登录完全绕过 2FA challenge）、H23（refreshSession 不查 INACTIVE，禁用账号可续期）、H24（有争议权限的运营可按 fileId 枚举读任意附件）、H65（可创建 ops:* 通配权限且引擎显式支持）、H66（Bearer 登出另一 realm cookie 仅清除不吊销；merchant-mp 登出不调 /auth/logout）、H68（支付宝解约类通知被忽略且 alipayAgreementId 永不清；绑定 notify 不验 alipay_user_id）。

### Edge/小程序（存在 11）
C11（转发失败外层吞异常，Paho 正常 PUBACK，QoS1 丢失）、C19 三连（TIMEOUT 不停录；trade 不等上传即结算；unlock 成功即报 OPEN 无门磁确认）、C21（critical 判定按 topic 子串，实际 topic `cabinet/{id}/evt` 全不匹配，队列满先丢门事件）、C23（onHide 停轮询，restoreActiveSession :1935 早致 startPoll 永不执行）、C24（canReplenish 快照早于 ensureMe，冷启动误判无权限）、C25（前端判 UNPAID 后端是 PENDING，按钮永不命中）、H57（serial null 只打日志，runCatching 仍 success 报 OPEN）、H59（先录像后开锁，失败不停录）、H70（scope.launch 无互斥，录像 sessionId 被覆盖）、M25（取消开门仅清本地，不中止 createSession，门照开）、L06（全部密钥明文 SharedPreferences+内置默认口令，无 EncryptedSharedPreferences）。

### 支付渠道/发票/券（存在 14）
H03（退款 total 只汇总 CHARGE，netCharged 含 ADJUST_CHARGE，差额单 refund>total）、H12（nonce 占位在验签前，垃圾请求占 Redis）、H41（渠道 HTTP 在结算事务内系注释承认的刻意设计；native 失败盲切 gateway 同额重扣）、H42（退款不查渠道状态、无查单无回调）、H46（视频 presign 只非空校验不验归属；/internal 密钥缓解）、H47（springdoc 开放不在拦截范围，prod 未关，无 spring-security）、H50（AgreementChargeClient 只有 charge 无查单）、H64（payscore_order 表 Java 零引用；取消会话无 PayScore 释放）、M02（退款幂等键含 reason hash，可双退）、M15（无 Idempotency-Key）、M22（PARTIAL_REFUNDED 按全额开票；全额退无红冲拦截）、M23（INACTIVE 定义仍可手动核销；PERCENT_OFF 预算占用恒 0）、M27（ADJUST 先 HTTP 后落库，重试再扣，gateway 无幂等）。

### 设备/库存/调度（存在 16）
H02（待补缴跳转参数无人消费且 tabBar 页 navigateTo 失败，显示全部订单）、H17（Kafka 吞错不重抛，offset 提交消息丢）、H18（批次核销缺货静默少扣，仅 total=0 才报错）、H19（佣金抢锁失败跳过且只算昨天，无多日补偿）、H20（白名单是「必含」非「排他」，gray 缺省 100 → 白名单+默认灰度=全量推送）、H21（videoUri 任意 http(s)/file 注入，file:// 可读本地文件外发）、H25（CANCELLED 出库单可被 markPicked 复活并发货扣库）、H28（有未决故障反而是自动解锁前提；人工锁机+故障工单会被自动解锁）、H29（维修 DONE 不查同设备其它开单）、H33（收货仓不回写 order.warehouseId，退货去错误仓库扣减）、H34（passRule=ALL 被存储但评估完全不读，一人通过即 SKIP 他人推进）、H35（容量/账面只算 ON_SALE/NEAR_EXPIRY，BLOCKED 批次仍占货道，headroom 虚高）、H37（SLA webhook 失败仍落 slaAlertedAt 不再重试）、H53（多商户提现固定取自然序第一个）、H58（争议凭证 URL 对商户必然 403）。

### 基建/admin/vision（存在 15）
H06（毛利固化无二次确认）、H07（keep-alive 后深链高亮不刷新，无 onActivated）、H08（风控加黑默认 userId=1）、H08b（onDisputeCreated 在 transition(DISPUTED) 前计数，阈值触发延后一单）、H09（OTA 发布无二次确认，unpublish 反而有）、H14（阿里云短信只判 HTTP≥400 不解析业务 Code）、H15（V244/V253/V254/V262 无环境守卫，prod 也会执行）、H43（media/** 免登录，ops-avatars 按自增 ID 可枚举）、H51（ops:ad:campaign:list 后端零引用；ops:bigscreen:view 无接口要求——菜单与 API 双向错位）、H52（vision 仅 API Key 无 CIDR）、H55（ACK 只看 payload commandId，不校验 topic/deviceId）、M08（CIDR 仅 getRemoteAddr，无 X-Forwarded-For）、M12（Grafana 网关路径无鉴权，dev 栈匿名 Viewer）、M16（apps compose 8080/8081/8082 直映射宿主，生产叠加未 override）、M20（生产 device-service 段缺 INTERNAL_API_ALLOWED_CIDRS，空=不限制）。

### Medium/Low（存在 16）
M01（set(null)+updateById 清不掉列：DisputeService 重开争议、OpsTwoFactorService 禁 TOTP、ApprovalWorkflowService 等，无 FieldStrategy 覆盖）、M03（毛利固化 SQL 无 status 条件，未支付/退款单计入）、M05（result.vue 读 payTime 后端是 paidAt，恒空）、M06（shared-dict 缺 3 个场地租/流量费枚举，后端已 seed）、M09（通知分发无消费者幂等；按全局开关双发短信）、M10（sonar.yml `maven.test.failure.ignore=true`，测失败不红）、M13（分账重试无订单锁，与用户侧并发）、M14（识别超时批处理单会话异常吞掉仍 SUCCESS，无失败计数）、M17（ci.yml dispute UAT continue-on-error）、M18（仅凭 videoUri 字符串标「有录像」）、M19（tryBegin 失败 action 正常 return→handleSuccess）、M21（`!= null` 恒真，无券订单显示「券减¥0.00」）、M24（MerchantNotify 直调订阅不检查 wechat-enabled，默认 false 仍发送）、L03（mock 短信验证码 INFO 明文日志）、L04（/o/{deviceId} 公开可探测存在性、无限流）、L05（DomainEventPublisher 唯一实现仅打日志）。

### 主会话补充（存在 1）
**H40 Vision DLT 失败仍 commit —— 存在（两侧同构）**
- Java 侧：`VisionRecognitionListener.java:68-72` 失败入 DLT 后正常返回提交 offset；`publishToDlt`（:93-95）把 DLT 发送失败也吞掉，且 `kafkaTemplate.send` 是异步 future，「已入 DLT」无保证——DLT 失败=消息彻底丢失仍提交。
- Python 侧：`kafka_worker.py:184-191` 外层失败先发 REQUEST_DLT，**无论 DLT 成败一律 `consumer.commit()`**（:189），DLT 发布失败仅打日志。
- 注：kafka_worker 对识别超时/识别异常转 need_review payload 是有意设计，不算此问题。

## 四、共性观察

1. **「prepare 短事务先提交、渠道/支付步骤后置且失败无补偿」**是 C03/C04/C06/M27 的共同结构。
2. **「吞异常 + 正常返回 → MQ offset/PUBACK 提交」**是 C11/H17/H40/M09 的共同模式（DLT 发送本身也不被保证）。
3. **幂等键设计缺陷**集中在 M02/H31/M15/M27/H67（含可变字段、缺渠道幂等参数、重试换键）。
4. 若干「High」实为文档化设计取舍（H27、H32、H36、H54 的兜底路径），修复前应先在计划里降级或改写。
