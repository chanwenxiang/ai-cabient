# AI Cabinet 三端全面审查报告（2026-09-27）

> **审查方式**：6 路并行只读深审（后端资金链路 / 后端平台与安全 / admin-vue / consumer-mp / merchant-mp / 跨端一致性与 dev-prod 边界），全部结论要求 `文件:行` 级证据；5 项载荷性结论（P0 候选与资金旁路）已由主审逐条复核源码坐实。未采信任何文档自报状态。
> **审查前提（用户口径）**：当前为**开发环境**——暂无小程序正式号、无真实支付、无真实硬件、无端侧识别（视觉走 mock）。本报告所有严重级别均在此前提下评定；「接真前必须」事项单列于 §6。
> **文档/记忆口径**：按审计纪律，`PROJECT_KNOWLEDGE.md` 与 WorkBuddy 记忆只当地图，不作证据。§8 列出本次实测发现的文档漂移。

---

## 一、总体结论

**整体健康度：中上～良好。产品代码无 P0；第二轮实测将 full 演示栈 Grafana 弱口令升级为环境级 P0（§10.3）。** 在「资金/安全/数据正确性」三个最高优先级维度上，未发现当前 dev 形态下可直接利用的产品缺陷。

核心正面结论（均经源码证实）：

1. **mock 边界设计成熟**：全部 mock 开关为「env 驱动默认 + strict-profile 启动校验 fail-loud + 代码层双闸」三重防护。`AICABINET_MOCK_ENABLED=true` 时 prod/staging profile 直接拒绝启动（`ProductionStartupValidator.java:119-121`）；DevMock 控制器 `@ConditionalOnProperty` 生产不装配；**未发现任何「prod 忘关 mock 会静默假成功」的点**。
2. **鉴权 fail-closed**：`/api/v2/**` 默认拒绝 + 显式白名单（AuthInterceptor）；ops/merchant 控制器抽查均挂 `@RequiresPermissions`；`/internal/**` 共享密钥拦截器未配置即 503；水平越权抽查（订单/会话/商户端）全部以 JWT userId/服务端推导 merchantId 为锚。
3. **金额全链路统一「分」**，未发现元/分混用点；结算幂等三重防护（会话维度订单唯一 + `session:settle` 分布式锁 + `findByIdForUpdate` 行锁）；余额账本双检幂等键 + 负余额/冻结额校验。
4. **前端纪律属实**：admin-vue CrudTable 54 页迁移、端点收口（70 条试点字面量门禁实跑通过）、无 v-html、令牌不落 localStorage、构建产物与 src 同提交一致；consumer/merchant 两端 C1–C12 / M1–M12 技术债清单抽查全部与源码相符（仅 M9 open 与文档一致）。
5. **shared-types 同步纪律良好**：抽查 3 个近两周改动 DTO 字段逐一一致；三端均消费 generated 类型，无大面积手写重复。

主要风险集中在 **3 条资金侧旁路（结算竞态多付 / 购物超时免单 / 提现超时盲置 FAILED）**、**设备侧共享 MQTT 凭据**（接真硬件后接近 P0）、以及 **preflight 的 shared-types 再生盲区**（本地绿 CI 红）。

---

## 二、发现总表

严重级别定义：**P0** = 当前即可造成资金损失/安全事件；**P1** = 接真前必须修，或当前即有实质功能缺陷；**P2** = 质量/一致性/债务。

### P1（11 项）

| # | 域 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| F1 | 资金 | `SettlementOrderFinalizeService.java:169-188` + `OrderPaymentService.java:374-401` + `UnpaidOrderService.java:259` | **余额扣款竞态多付**：`detectUnpaidBeforeCharge`（不持余额锁）预检通过后、扣款执行前余额被并发扣减，则 `captureForCharge` 已先扣余额+解冻预授权，随后 `balanceLedgerService.change` 抛「余额不足」被吞转 PENDING——已冲抵的预授权未计入 `netCompletedCents`，事后补扣按全额 charge，用户多付 capture 部分 | 补扣前按「净额 + 已冲抵预授权」校验，或 catch 内回补/重算 PENDING 金额 |
| F2 | 资金 | `SessionExpireService.java:384-427`（已复核） | **购物超时免单旁路**：消费者 SHOPPING 超 10 分钟未关门，直接 `releaseIfFrozen` + 转 CANCELLED，**不结算、不建争议工单**（仅 opsException）；同文件 L268-276 识别超时路径有 `disputeService.createTimeoutTicket`，此路径没有。取货后拖住门不开即免单 | 超时取消前若存在重力扣减/购物车证据，转 DISPUTED 并建超时争议单，预授权保留至争议结案 |
| F3 | 资金 | `MerchantWithdrawService.java:486-520`、`LineWithdrawService.java:361-390` | **提现 PAYING 超时盲置 FAILED**：`failStalePayingWithdraws` 不查渠道结果直接置 FAILED 并解冻。真实渠道「已出款但回执丢失」场景=解冻+已到账双重支出。当前真实转账未接入故未触发，**接真前必须修** | 对非 MOCK 渠道单禁用该 sweep 或先渠道查单确认无结果再置 FAILED |
| S1 | 安全 | `infra/docker/emqx/aicabinet-acl.conf:14` + `edge/android-app/app/build.gradle.kts:21,32` | **设备侧共享 MQTT 凭据**：所有柜机共用 `aicabinet-device` 一个口令（编译进 APK）；ACL 规则 `cabinet/${clientid}/#` 中 clientId 由客户端自选，攻击者提取一台柜机口令后以 `clientId=受害者deviceId` 接入即可收发受害者柜机全部消息（截获开门指令/伪造 door 与识别事件干扰结算）。文件内注释「拿到共享密码也伪造不了别的柜机」**不成立**。dev 无硬件暂不可利用，**接真硬件后接近 P0** | 每设备独立凭据（username=deviceId + 逐设备 secret）或 mTLS；bootstrap 生成器支持设备清单批量发卡；APK 不内嵌口令 |
| S2 | 安全 | `AuthInterceptor.java:36-43` | GET 请求允许 `?access_token=` 后备认证（全 `/api/v2/**` 生效），token 进网关/访问日志/浏览器历史/Referer | 收窄到明确需要的下载路径，或全部改 MinIO presigned URL |
| S3 | 安全 | `MerchantService.java:413-421`、`MerchantDevicePortalService.java:390` | 后端 CSV 导出未中和公式注入（`= + - @` 开头单元格在 Excel 可执行）。注意不对称：admin 前端 `utils/csv.ts` 有防护，后端没有 | 后端统一 csv() 对危险前缀加 `'` 或 tab |
| S4 | 安全 | `infra/gateway/nginx-full.conf:120-133` + `docker-compose.full.yml:321-330` | full（演示）栈 Grafana 路由无 `auth_basic`（`nginx.compose.conf:145` 有），默认 `admin/admin` 且 `ALLOW_EMBEDDING=true`、端口未绑回环。**第二轮实测升级为 P0（环境级）**：`curl -u admin:admin :13000/api/user` 返回 `isGrafanaAdmin:true` 实测可登；绑 `0.0.0.0:13000` 局域网可达；网关层无 Basic 补防 | full 栈补 basic auth 或端口绑 127.0.0.1；`GF_SECURITY_ADMIN_PASSWORD` 强制覆盖；生产禁用 full 栈 |
| A1 | admin | `views/recharges/RechargeListView.vue:204` | 本地 `isRefundable` 自建状态表，绕过 `money-ui-contracts.ts` 契约收口（D5 声称 View 必须复用） | 充值域状态判定收进 `money-ui-contracts` 并注释域差异 |
| C1 | consumer | `utils/consumer-api.ts:667-684`（已复核） | `createSession` 首次失败后**不分错误类型**延迟 600ms 重发同一 POST /sessions——余额不足、柜机忙、频控等确定性 4xx 也重试，有二次触发频控/风控风险（有 idempotencyKey 幂等兜底，故未到 P0） | 复用 request 层 `isRetriableMpTransportError` 语义，仅超时/网络错误重试 |
| C2 | consumer | `pages/index/index.vue:2189-2263` | 首页会话轮询（2s）**无总时长上限**：柜机长期停在非终态且用户停留首页则永续轮询（有防堆积、onHide 停表；对比 orders 页 300s / result 页 180s 均有 cap） | 加 maxDuration（如 10 分钟）到期提示「稍后在订单查看」并停表 |
| X1 | 跨端 | `scripts/pre-push-ci-preflight.mjs:170-183`（已复核） | **shared-types 再生盲区**：检测到 Controller/DTO/generated 改动时默认档仅打印警告；`check:openapi-types` 仅 `--full` 且存在 `.tmp/live-openapi.json` 才跑（且依赖可能陈旧的缓存）。CI 起真 jar 现抓 ⇒ **本地绿、CI openapi-regen 红**盲区实存 | 检测到 openApiSurface 时把 `check:openapi-types`（结构档）纳入默认档强制执行 |
| X2 | 跨端 | `infra/docker-compose.staging.yml:26-44` | staging overlay 未显式钉死 `PAYSCORE_MOCK_ENABLED`/`PROFIT_SHARING_MOCK_ENABLED`/`LINE\|MERCHANT_WITHDRAW_MOCK_ENABLED`，防假成功完全依赖 Java 代码双闸，环境层纵深缺一层 | 像 `AICABINET_MOCK` 一样在 overlay 显式写 `"false"` |

### P2（按域归组）

**后端资金**
- `MerchantWithdrawService.java:477,487`、`LineWithdrawService.java:351,361`：常量名 `PAYING_TIMEOUT_MINUTES=60` 却按 `ChronoUnit.HOURS` 使用（实际 60 小时），名实不符。
- `MerchantWalletService.java:65-71,438-464`（`SessionDoorService.java:82-85`、`CouponService.java:519-525` 同型）：`@Transactional` 方法体内先抢 Redis 锁、finally 解锁早于事务提交；靠行锁兜底正确性无损，但与 `BalanceLedgerService` 声明的「锁在事务外」模式不一致，等锁期间占 DB 连接。
- `SettlementSettleOrchestrator.java:57-77`：vision HTTP 在 `session:settle` 锁（租约 60s）内执行，vision 慢于租约过期后第二请求可并发进入；靠订单唯一+行锁防双扣，但会话状态可能交替迁移。建议 vision 调用移出锁体。
- `SettlementRecognitionService.java:314-330`、`SettlementService.java:102-123`：mock/兜底识别闸门依赖 `modelVersion` 字符串 contains("mock"/"fallback")，vision 端改版本命名即误放行/误拦截。建议改结构化字段（`source: MOCK/LIVE`）。
- `RevenueSplitService.java:97-99`：分账以折后应付为基数（券成本全由商户承担）、平台分润截断余数归商户——口径未见业务确认文档。
- `OrderPaymentService.java:702-721`：mock 下微信/支付宝订单退款直接加余额（有 mockEnabled 守卫，prod 抛 503）；切换真实支付前对账脚本需排除此类 mock 流水。

**后端平台**
- `ProductionStartupValidator` vs `application.yml:344`：未校验 `xxl.job.access-token` 弱默认 `dev-xxl-token`、Redis 无密码——裸 `SPRING_PROFILES_ACTIVE=prod`（不经 compose overlay）启动不会被拦。
- `application-staging.yml`：staging 未关 springdoc（prod 已关）；网关不路由 swagger，仅内网直连可达。
- `JwtService.java:107-112`：旧 token 无 `act` claim 时按 `userId >= 100_000_000` 号段推断账户类型——兼容逻辑依赖号段不变式，登录态全量轮换后应移除。
- `vision-service/app/main.py:327-350`：`suggest-class`/`dispute-suggest` 每请求外呼付费 DeepSeek API，仅共享 Key 保护，无独立限流/配额。
- `edge/android-app/.../EdgeRuntimeConfig.kt:161`：Keystore 加密失败 fallback 明文存 SharedPreferences，应阻断而非降级。
- `MerchantPortalController.java:39-42`：`/merchant/me` 仅登录无 permission 注解（返回本人信息，可接受；如商户体系细分角色再补）。

**admin-vue**
- `packages/shared-uni/src/format.ts:89`：`fmtMoney` **0 个视图使用**；5 个视图本地 `money()` + 约 150 处内联 `/100).toFixed(2)`。语义一致无资金错误，但空值口径（`0.00` vs `暂无`）与千分位不统一。建议渐进替换、新代码禁止裸算。
- `useReplenishmentTaskLines.ts:159`、`useWarehouseLabels.ts:117`、`SkuListView.vue:662`、`useSessionVideo.ts:43`：`window.open(url,'_blank')` 未带 `noopener,noreferrer`（另有 2 处已带，口径不一）。建议统一封装。
- `stores/dict-runtime.ts:32`：裸路径 `'/api/v2/dicts/runtime'` 逃脱端点门禁——`check-admin-endpoints.mjs` 只扫 views/composables，不扫 stores/。迁入 AdminEndpoints + 门禁加扫 stores/。
- `static/admin/runtime-config.json`（未跟踪产物）：本机构建混入真实高德 key + securityCode。git 未跟踪无泄露，但部署产物一致性可能被破坏；生产构建禁止注入或确认域名白名单。
- UI token：views 209 处 + main.css 78 处 6 位 hex；裸 z-index 34 处（多为局部小层级，无千级冲突）。约定执行约七成。
- `router/index.ts:496-498`：`meta.perm` 冗余逃生口，当前所有业务路由均未设、全靠菜单兜底。删除或标注用途。

**consumer-mp**
- `pages/index/index.vue:726`：客服电话兜底硬编码 `400-888-0018` 疑似占位号，公开配置拉不到时应显示「暂无」。
- 全局样式约 300+ 处裸 hex 业务色；`check-consumer-endpoints.mjs:9-14` 扫描根未含 components/utils。
- `dispute/detail.vue:477`、`order-detail.vue:902`、`help.vue:193`：`makePhoneCall` 无 fail 回调（C9 deferred 既定范围）。
- JWT 明文落 storage（`consumer-api.ts:38-44`）：MP 端通行做法，已有 expires/401 单飞刷新，风险可接受；正式号上线后可评估仅内存持有。
- `index.vue:1046-1064`：每次 onShow 触发 serverBoot+refresh，tab 频繁切换多余请求，可加 30s 内存缓存。

**merchant-mp**
- `utils/dict-runtime.ts:7`：裸路径绕过 `MerchantEndpoints`，且 M3 门禁只扫 pages/composables，utils 漏扫（与 consumer 同型问题）。
- `components/WalletPage.vue:305-352`：提现按钮无 `hasPerm` 前置裁剪，无权用户点击后才收 403；金额仅校验 >0 且 ≤ 可用，无后端最小金额（¥1）与单日限额预检。
- `pages/pricing/pricing.vue:156-161`：`formatTime` 本地时区与其余页面东八区 `formatDateTimeShort` 双轨。
- 约 30 处软色横幅裸 hex 未进 token。
- `clients/merchant-mp/dist/`、`tsc-out.txt`、仓库根 `_tmp-docker-up-before-fix.ps1`：构建产物/临时文件入库噪音。
- `MerchantPortalController.java:655-680`：`amountCents` 缺失时 `Long.parseLong(null)` 抛 NFE→500 而非 400。

**跨端/配置**
- `docker-compose.apps.yml:103` vs `full.yml:178`：`PROFIT_SHARING_MOCK_ENABLED` 默认值两套 compose 不一致（false/true）。
- `ProductionStartupValidator.java:161,241-256`：分账 mock 校验仅 prod profile 执行，staging 误开会记假分账且无告警。
- `LineWithdrawPayoutService.java:47-49`：mock 关闭且微信已配置仍命中骨架失败分支——**关 mock 后提现实质不可出款**（fail-loud 有提示），部署生产脚本应加显式确认步骤。
- admin `MerchantWithdrawView.vue:502-530` 与 merchant-mp `order-detail.vue:147-176`：视图层手写 interface 与 shared-types 重复，字段漂移无门禁。
- `scripts/cleanup-test-data.ps1:226-234` 等 5 文件 20 处写死 `CAB-` 实体号（多为清理/演示脚本，可接受，建议按前缀通配）。
- `ci.yml:106,120`：migration 两个 self-test 只在 CI 跑，preflight 不自测。

---

## 三、分域详述

### 3.1 后端资金链路（trade-service）

设计成熟度高：结算幂等三重防护、余额账本双检、预授权按会话 hold 冲抵并钳制不抽干他会话（`ConsumerPreauthService.java:250-253`）、渠道 HTTP 一律事务外短事务编排、退款幂等键剔除可变 reason。**无 P0**。三条 P1（F1/F2/F3）中：F1 是低概率高危害的竞态窗口；F2 是结构性的免单旁路（已复核源码坐实）；F3 在接真实转账前不触发、但接真前不修就是资金事故。另注意 `PAYING_TIMEOUT_MINUTES` 名实不符（P2）与「提现关 mock 后实质不可出款」这一接真事实。

### 3.2 后端平台与安全

「默认拒绝 + 显式白名单」的 AuthInterceptor、`@RequiresPermissions` 全覆盖抽查、fail-closed 内部密钥拦截器、`ProductionStartupValidator` 17 项强校验（mock 开关/弱密钥/默认口令/localhost CORS/SMS 万能码/内部 CIDR/微信进件等）。正面确认：SMS 万能码双闸门；DevMock 控制器生产不装配；水平越权抽查通过；Mapper XML 全量无 `${}`；文件下载防穿越；`.env*` 均未入 git；Flyway V1–V287 无重复无断号、近期脚本带 `MIGRATION_KIND`/幂等守卫；生产 compose 强制 `RECOGNIZER_BACKEND` 非 mock、EMQX 禁匿名 + no_match=deny。四个 P1：设备共享凭据（S1，最重要）、GET token 后备、CSV 公式注入、full 栈 Grafana。

### 3.3 admin-vue（运营后台）

鉴权链 fail-closed（未登记路径一律拒、生产拒落 JWT、RBAC 未拉取不放行菜单）；401 全局拦截三层（单飞刷新→重试→清会话跳登录）；下载统一带凭证、401 清会话、文件名防穿越；`hasPermi` 指令有真机取证注释与单测；CSV 前端导出有公式注入防护。文档声称的 D1–D25/CrudTable 54 页迁移**抽查证实**。登录实现为**手机号+密码+图形验证码+可选 2FA（TOTP/恢复码）**——见 §8 文档漂移。1 项 P1（充值退款状态表绕过契约）+ 6 项 P2，无资金显示错误。

### 3.4 consumer-mp（消费者小程序）

开门幂等（idempotencyKey + 孤儿会话接管 + 幽灵会话宽限）、轮询防堆积、mock 入口 dev-only 门控是亮点。C1–C12 抽查全部属实（C9 deferred/C10b 延后与文档一致）。storage 23 个键全链读写一致、无孤立键。2 项 P1：createSession 重试不分类（C1）、首页轮询无总上限（C2）。appid 为空+构建期 `inject-miniapp-env.mjs` 注入机制完整正确，生产包微信充值入口在 live 未开时不渲染（fail-closed 已验证）。

### 3.5 merchant-mp（商户小程序）

请求层 401 单飞刷新+重试防抖、`softFallback` 带 label+toast、金钱口径统一 `fmtMoney`/`yuanToCents` 防浮点误差、提现幂等（requestNo + 后端锁 + `findByRequestNo` 去重）、补货链路服务端门闩齐全、pages.json 5 主包+17 分包与实际文件完全一致。M1–M12 抽查属实，M9（共享 UI 组件双轨）open 与文档一致。无 P0/P1（其报告中的「mock 默认 true」一条经跨端复核为**已闭环设计**：prod 硬编码 false + validator fail-loud，见 §4 总表）。P2 中值得先修：WalletPage 提现前置权限裁剪与最小金额校验。

### 3.6 跨端一致性与 dev/prod 边界

mock 开关总表见 §4；dev-only 面清单见 §5。shared-types 同步抽查 3 个近期 DTO 逐一一致；三端类型均出自 `@aicabinet/shared-types`，endpoints 三端各自收口 + 各自门禁；5 个 shared 包全部在用无僵尸包。门禁现状：聚合链 **39 个门禁**、全仓 47 个 `check-*.mjs`，wiring 校验实跑通过；CI ci.yml 跑同一聚合链，链内无「本地绿 CI 红」断链；CI-only 增项含 openapi 真再生（起 jar 现抓）、migration-safety、build-admin 产物一致性、OSV 审计、e2e-h5。唯二结构性盲区即 X1（preflight 再生仅警告）与 X2（staging env 纵深）。

---

## 四、mock 开关总表（实测）

| 开关 | dev 默认 | staging 默认 | prod 默认 | prod 关闭后行为 |
|---|---|---|---|---|
| `AICABINET_MOCK_ENABLED`（总闸） | true | **false 硬编码** | **false 硬编码** | 带 true 启动即抛（Validator:119-121）；DevMock 控制器不装配、demo-close 403、身份证核验要求真实 base-url |
| `PAYSCORE_MOCK_ENABLED` | true | 未钉死 ⚠（apps 层继承 true） | apps 层 true ⚠ | 双闸（security mock=false）⇒ mock 不可达；真实通道未配 ⇒ **抛异常**，无余额静默兜底 |
| `RECON_MOCK_ENABLED` | true | true（soak 设计） | 依赖 `.env.production`（模板 false） | 忘设 false ⇒ **启动即抛**（fail-loud，可接受）；真实账单下载，凭证缺失报错、禁止空账单假对账 |
| `PROFIT_SHARING_MOCK_ENABLED` | apps false / full true（不一致 ⚠） | 未钉死 ⚠ | false（Validator 仅 prod profile 校验 ⚠） | mock 提交不调微信 API、任意 wxTransactionId 可过——staging 误开无告警 |
| `LINE/MERCHANT_WITHDRAW_MOCK_ENABLED` | true（跟随总闸） | 经总闸解析为 false | false，否则启动抛 | 打款必然失败并记账「微信转账到零钱接口尚未接入」——**关 mock 后提现实质不可出款**（fail-loud 有 modeInfo 提示） |
| VISION（`MOCK_ENABLED`/`RECOGNIZER_BACKEND`） | true / mock | false + INSTALL_ML + APP_ENV=staging | **MOCK false 硬编码** + backend 必填非 mock（`:?`）+ docs 关 + CIDR 必填 | 识别器不可用 ⇒ 启动即抛；debug/force-need-review 403；secure env 置 mock ⇒ RuntimeError |
| SMS（`SMS_MOCK_CODE`） | `123456`（仅 dev profile） | 真实 webhook 容器；万能码因 mock=false 失效 | 无；validator 拒绝 123456/000000 | 必须 sms.webhook-url（Validator:86-88）；万能码仅 mock-enabled 时生效（代码双闸） |

**结论**：未发现「prod 忘关 mock 会静默假成功」的点；「prod 关 mock 会坏」的点只有提现打款（需接真实转账 API）。⚠ 标记处建议按 X2/对应 P2 收口。

---

## 五、dev-only 面与生产不可达证据

| 面 | 生产不可达证据 |
|---|---|
| `/api/v2/dev/**` 3 个 DevMock 控制器（充值/支付/支付宝） | `@ConditionalOnProperty(mock-enabled=true)`；strict profile 带 true 启动即抛 |
| DemoDataInternalController | 同上 + 网关 `/internal/ → 403`（nginx.conf:103、nginx-full:118） |
| `POST /api/v2/sessions/{id}/demo-close` | 运行时 `mockEnabled()` 检查 → 403（SessionController.java:72-80） |
| vision `/debug/force-need-review`、/docs、/redoc | secure env（APP_ENV=staging/prod 或 VISION_DISABLE_DOCS）→ 403/路由移除 |
| device-simulator 容器 | 仅 full.yml 定义；staging/production compose 无此服务 |
| 前端 dev 工具（模拟充值/mock 建档） | `shared-uni/runtime-flags.ts:10-28`：`isDevBuild=false` 强制 `resolveMockEnabled()=false` |
| 充值 mock 的服务端二次拦截 | `PaymentService.confirmRechargeMock` 校验 mock 开关 + 订单归属（PaymentService.java:321-333） |

---

## 六、接真前 Checklist

### 6.1 接小程序正式号（consumer + merchant）
1. manifest.json + project.config.json 填正式 appid（现有 `MP_WEIXIN_APPID_CONSUMER` 注入机制保持，勿写回源码）。
2. `.env.production.local` 替换 `.invalid` 占位为真实 HTTPS 域名，跑 `validate-miniapp-env.mjs` 把关；公众平台配置 request/downloadFile 合法域名。
3. `VITE_WX_SUBSCRIBE_TMPL_IDS` 订阅消息模板注入（现未配置 → 订阅静默跳过）。
4. 微信后台「用户隐私保护指引」声明（scanCode 相机用途）；隐私弹层已接。
5. 先修 C1（重试分类）、C2（轮询上限）；consumer 每次冷启动 bootstrap 频控与微信侧频控兼容确认。
6. 提审门禁：`check-consumer-endpoints`、`sync-shared-uni-components --check`、双端 type-check、vitest 全绿；mp 真机回归 C-1/C-2/C-3 场景。

### 6.2 接真实支付
1. **先修 F1**（预授权冲抵竞态多付）——上线前必改。
2. 配置微信支付 V3（商户号/证书/平台证书自动拉取）+ appid/secret；`ProductionStartupValidator` 会强制逐项，按报错补齐即可。
3. 真测：`PaymentService.handleWeChatNotify` 验签入账（金额校验已备）、`WeChatRefundReconciler` 对 PROCESSING 退款查单推进、PayScore `recordChargePending`/`finalizeChargePending` 与 gateway 查单补偿。
4. 对账切换：`RECON_MOCK_ENABLED=false` + `WeChatPlatformBillProvider` 与 mock 镜像口径差异确认；对账脚本排除 mock 退款加余额类流水（`OrderPaymentService.java:702-721`）。
5. 分账：`PROFIT_SHARING_MOCK_ENABLED=false`、`transferApiReady` 替换、券成本归属与尾差口径业务确认（P2）、`ProfitSharingRetryScheduler` 实测。

### 6.3 接真实硬件（含提现出款）
1. **先修 S1**（设备逐台独立凭据/mTLS + bootstrap 批量发卡 + APK 不内嵌口令 + 吊销共享账号）——接真硬件前最高优先。
2. **先修 F3**（提现 PAYING 超时先渠道查单再置 FAILED）；接微信商家转账 API 替换 `MerchantWithdrawPayoutService` 骨架；配置 review-threshold（¥500）/daily-limit（¥5000）/fee 口径并同步钱包文案。
3. 修 S2（GET access_token 收窄/presign）、S3（后端 CSV 公式注入）。
4. EMQX 生产化：禁匿名已确认；MQTT TLS truststore、`INTERNAL_API_ALLOWED_CIDRS` 等按实际 VPC 填写（勿用 0.0.0.0/0）。
5. 周边口令全部走 `.env.production` 强口令：Grafana（full 栈 admin/admin 必换或禁 full 栈）、MinIO、XXL-JOB MySQL；validator 补 xxl token/Redis 密码检查（P2）。
6. 确认 `seed_env=none` 生效、`flyway repair` 对齐历史 checksum；生产脚本加「提现未接入」显式确认步骤（在接入前）。

### 6.4 接端侧识别 / 真实视觉
1. `RECOGNIZER_BACKEND` 切非 mock（production 已强制必填）；vision 真实模型上线前做**争议率/置信度阈值验收**——mock 会把低置信度强制路由 need_review，真实模型未必。
2. 识别 mock 闸门从 `modelVersion` 子串匹配改结构化字段（P2，vision 与 trade 联合评审命名契约）。
3. 跑通「高置信扣款 / 低置信 DISPUTED」矩阵回归；vision suggest 接口加限流与预算上限（P2）。
4. 遵守既有铁律 G7：关 `VISION_MOCK_ENABLED` 须端侧识别就绪，禁止本机 UAT 假关。

### 6.5 不分接真阶段、建议本轮就修
X1（preflight 强制 `check:openapi-types`）、X2（staging overlay 钉死 mock env）、S4（full 栈 Grafana）、A1 + 两端 dict-runtime 裸路径与门禁扫描根补全（stores/utils）、`PAYING_TIMEOUT_MINUTES` 名实不符、merchant-mp dist/tsc-out 入库清理。

---

## 七、建议修复顺序（P1）

1. **F2 购物超时免单旁路**——纯后端小改（对齐同文件识别超时路径），当前 dev 即可造测。
2. **X1 preflight 盲区**——防的是最频繁的开发事故（本地绿 CI 红）。
3. **S1 设备共享凭据**——改动面最大（EMQX/生成器/APK 三处），离接真硬件尚有时间，尽早开设计。
4. **F1 结算竞态多付**——需要仔细设计预授权冲抵与补扣的净额口径，配并发单测。
5. **F3 + X2**——同属「接真前提」，可打包成一个「提现接真就绪」切片。
6. **S2/S3/S4/A1/C1/C2**——各自独立小切片。

---

## 八、文档与记忆漂移修正（本次实测 vs 旧口径）

| 旧口径 | 实测结论 |
|---|---|
| `PROJECT_KNOWLEDGE` §5「13900000001 / 123456 + 验证码」 | admin 登录实为**手机号+密码+图形验证码+可选 2FA（TOTP/恢复码）**（`stores/auth.ts`、D9 已验证 2FA challenge 只进 sessionStorage）；「验证码」非短信码 |
| §2「Flyway 287 个脚本、trade Controllers ~84」 | 实测 286 个 `V*.sql`、85 个 `*Controller.java`（小幅漂移，以实测为准） |
| WorkBuddy 记忆「#14 提现：非 mock 失败 / mock 开＝假成功」 | 已闭环升级：prod 硬编码 false + Validator fail-loud + mock 恒成功仅 dev；新事实是**关 mock 后提现实质不可出款**（骨架恒失败），接真需接转账 API |
| §10 G7「灰度真金 vision mock 仍开」 | 更准确：vision mock 为 env 驱动，staging/prod 已强制非 mock 且 secure env 启动拒 mock；「仍开」仅指本机 dev/full 栈 |
| 技术债文档 C1–C12/M1–M12 done | 抽查**全部属实**（C9 deferred、C10b 延后、M9 open 与文档一致）——本轮未发现虚报 |

---

## 九、未验证清单（诚实边界）

> **本节已被 §10 第二轮验证清零取代**：所有可在本机完成的项已全部验证完毕，最终保留的「客观不可验 / 本轮未执行」清单见 §10.5。

---

## 10. 第二轮：验证清零（2026-09-27 同日续）

> **方式**：6 路并行验证（后端资金 / 后端安全 / infra / admin-vue / 双端 mp / 门禁实跑），本轮允许实跑只读校验（类型检查、单测、门禁链、compose 渲染、运行栈 curl 实测、DB 只读查询）。逐项覆盖第一轮 §九 的全部「未验证」。

### 10.1 实跑结果总表（全部真实命令+退出码）

| 项 | 命令 | 结果 |
|---|---|---|
| admin-vue type-check | `npx vue-tsc --noEmit`（admin-vue） | ✅ exit 0 |
| admin-vue 单测 | `npx vitest run` | ✅ 13 文件 / **84 通过 / 0 失败**（872ms） |
| consumer-mp type-check | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | ✅ exit 0 |
| merchant-mp type-check | 同上（merchant-mp） | ✅ exit 0 |
| consumer-mp 单测 | `npx vitest run` | ✅ 17 文件 / **121 通过 / 0 失败**（4.13s） |
| merchant-mp 单测 | `npx vitest run` | ✅ 21 文件 / **130 通过 / 0 失败**（4.55s） |
| 推送预检全链 | `node scripts/pre-push-ci-preflight.mjs`（NODE_OPTIONS 绝对路径 nopipe） | ✅ exit 0，47s：format ✓ / 双端 type-check ✓ / lint ✓（0 error，17 个既有 unused-vars warning）/ **audit-gates 39/39 ✓** / migration 双门禁 ✓ |
| audit-gates 独立复跑 | `node scripts/run-audit-gates.mjs` | ✅ exit 0，10s，39 门禁 0 失败 |
| **实时 OpenAPI diff** | `curl :18080/v3/api-docs`（200，550 paths，480KB）→ 同工具链生成到 .tmp 与提交的 generated 逐字节比对 | ✅ **完全一致**：`openapi.ts` 30,706 行 diff 为空；7 个别名组文件全部 SAME；结构检查 OK |
| 行尾检查 | `node scripts/check-line-endings.mjs` | ✅ exit 0（5,370 文件） |
| 网关匿名探测 | curl 运行栈 /admin、/consumer/、/merchant/、POST /api/v2/sessions、/internal/v1、/actuator/env 等 | ✅ 与代码声明一致（公开面收敛，401/403 各就位） |
| /admin 静态策略实测 | curl -I index.html / assets / 未知 SPA 路由 | ✅ index `no-store`、assets `max-age=31536000 immutable`、history fallback 回 index.html |
| production compose 渲染 | `docker compose ... config`（base+apps+production） | ✅ **fail-loud 实证**：`VISION_ALLOWED_CIDRS`/`INTERNAL_API_ALLOWED_CIDRS` 缺失即拒绝渲染（`:?` 生效） |
| Grafana 弱口令实测 | `curl -u admin:admin :13000/api/user` | 🔴 **实测可登**，`isGrafanaAdmin:true`（S4 升级 P0 环境级） |
| **MinIO 匿名访问实测** | `curl 127.0.0.1:19000/cabinet-videos?list-type=2` + 匿名 GET 对象 | 🔴 **桶级匿名可读可列举**（ListObjects 200、对象 GET 200），桶内含 `archive/…/session-*/…-top.mp4` 购物会话顶摄视频与补货证据照片——见新 P1·V4 |
| htpasswd 口令离线验证 | apr1 盐 `zcAICab1` 本地验 7 个常见候选 | ✅ 全部不匹配——apps 栈 Grafana basic auth 口令非占位弱值 |
| XXL 真实调度精度 | `xxl_job_log` 按 DATETIME 正确口径查询 | ✅ **完全健康**：compensationProcessJob 每 30s 真实触发（每分钟稳定 2 条，8 小时 1037 条）、执行器注册唯一在线、`handle_code=200` 927 条；110 条 `trigger_code=500` 触发失败待查（量小，tryBegin 兜底）。⚠️ 初查的「2612 年测试污染」为口径误报，已撤回（见 lessons #232） |
| CORS 运行值 | `docker inspect ai-cabinet-trade-service-1` env | ✅ dev 运行值 `CORS_ORIGIN=http://localhost`；生产值仍属仓库外 |
| **mvn verify（CI 同款）** | `mvn verify -DskipITs -pl services/trade-service -am`（REDIS_HOST=localhost，后台 43min） | ✅ **exit 0，BUILD SUCCESS**；surefire 实数 **1,344 个 `<testcase>`**（302 个测试类） |
| **admin 侧 UAT 实浏览器**（串行，Playwright 真浏览器打运行栈） | `node tests/admin-uat.mjs` 等 6 脚本 | ✅ admin-uat **10/10**；three-end-business **8 过 0 挂 2 skip**（视频样例缺、演示数据缺）；batch-imp **6/6**；alert-channel **6/6**；role-regression **19 过 2 数据态软挂**（B-09 无待审批行/B-10 无 PO-UAT-B06 驳回——环境数据态，非产品缺陷）；dispute-ui 消费端腿走 H5 ⇒ 按「mp 验收权威」口径本地不跑（CI 棘轮 0 绿） |
| **补偿链造数实证** | psql 插入合成 `compensation_task`（假 tx_id）→ 等 30s 调度 | ✅ 调度器准时拾取→PROCESSING→按类型路由→`split not found` 终态 FAILED+executed_at——claim/派发/路由/终态写入运行时全链实证；重试退避/耗尽告警路径由代码读覆盖（`deferProfitSharingReturnTask` 60/240/540/960s×5→COMPENSATION_TX_STUCK）；合成行已清理 |
| **安全/并发/对账冒烟** | `scripts/security-concurrency-recon-test.ps1` | ✅ **pass=30 fail=0 warn=1**：6 路并发同幂等键开门→5 路 409「设备使用中」（锁生效、零重复开门）；消费者打运营 API 403；2FA challenge token 打业务 401；错误 TOTP 拒绝；warn=设备Busy致并发组无成功样本（数据态，非缺陷）。附带：一致性巡检真实工作，现库 7 条 S3 软写遗留脏数据（WALLET_BALANCE/REFUND_AMOUNT 等，属数据态） |
| 争议造数 | `scripts/create-open-dispute.ps1` | ✅ 重试即成（首败=模拟器瞬时 state=FAILED，非产品问题）；`.tmp/open-dispute.json` 已就绪 |

环境坑记录（已入踩坑总册 #230）：Node v24 下 `NODE_OPTIONS --require` **相对路径必失效**（秒退 exit 1 `MODULE_NOT_FOUND`），必须绝对路径——与「EBUSY 全红假红」是两种不同故障。

### 10.2 原未验证项清零结论

**后端资金：**

| 原未验证项 | 结论 | 证据 |
|---|---|---|
| TCC 补偿闭环 | ⚠️ **部分**：TCC 三件套（coordinator/tx/step）为**无业务调用方的死代码**（DB `distributed_transaction`/`compensation_task` 0 行）；cancel 只改状态不冲正。唯一活跃补偿=分账回退链路（真实查渠道+重发，指数退避 60/240/540/960s×5 次后告警） | `CompensationTaskScheduler.java:70,127-163`、psql 0 行 |
| CHARGE_PENDING/INITIATED 痕迹 | ⚠️ **无 sweeper**：`payment_operation` 落了痕迹但全仓无扫描者，仅重试打款时被动查单。当前 DB 0 条无积压 | `OrderPaymentService.java:279-300` |
| WeChatRefundReconciler | ✅ 逻辑正确（标记替换幂等、ABNORMAL 告警）；🔴 但触发门 **`RECON_SCHEDULED_ENABLED` 默认 false，每日对账+退款推进实际不执行**（DB 证据：scheduled_task `reconciliation` last_message=「对账调度未启用」）→ 新 P1 | `ReconciliationScheduler.java:41-62`、psql |
| ReconciliationService 差异处理 | ✅ 落库+三分类（PLATFORM_ONLY/LEDGER_ONLY/金额差）+ reviewAction 完整；无自动冲正（设计即人工）；⚠️ MISMATCH 仅打 Prometheus 不发 OpsAlert（新 P2）；mock 账单构造上必平，真实微信空账单抛错防假 MISMATCH | `ReconciliationService.java:169-245` |
| XXL-JOB 资金任务 | ✅ 全部安全：资金类 handler（withdrawPayingTimeout/reconciliation/profitSharingRetry/compensation/unpaidCancel/rechargeCancel/session×4）`xxl_job_info` 全部 `trigger_status=1`、cron 6 段、`trigger_last_time` 均今日；psql `last_run_at` 今日 SUCCESS。无「注册但永不触发」资金任务 | mysql/psql 实测 |
| VisionRecognitionListener（Kafka 异步） | ✅ 默认完全不装配（`enabled:false` 无 matchIfMissing）；消费幂等（状态非 RECOGNIZING 忽略）；DLT 同步等 broker ack 成功才提交 offset；兜底 `session-recognizing-expire` 任务今日 SUCCESS | `KafkaEnableConfig`、`SessionSettleService.java:157-170` |
| 支付客户端验签 | ✅ 有真实验签：微信平台证书按 serial 解析（静态 PEM+12h 拉取）+ SHA256-RSA + 300s 时效 + Redis nonce 防重放(10min) + AEAD 解密 + mchid 比对；支付宝 RSA2；回调金额与订单强校验、PAID 短路+行锁+幂等键去重 | `WeChatPayNotifyService.parseAndVerify:45-77`、`PaymentService:1050-1061` |
| 提现审核流端到端 | ✅ 安全：min ¥1/可用余额/日限额校验 → ≥¥500 走 PENDING_REVIEW+审批流（`ops:merchant-withdraw:review`）否则自动打款 → 冻结/消费/释放同值、FAILED 重试先重新冻结；mock 分支有 mockEnabled 守卫 | `MerchantWalletController:63-72`、`MerchantWithdrawService:371-463,522-537` |
| /dev/ mock 路径约定 | ✅ 三个 DevMock 控制器均在 `/api/v2/dev/payment/**` + `@ConditionalOnProperty` + 服务层二次校验，与 lesson #220 一致 | `DevMockRechargeController:19` 等 |
| Redisson 租约 | ✅ 静态安全：watchdog 有意关闭（强制显式 leaseTime>0）、unlock 校验持有者；内部 REST read=30s < 会话锁 60s 租约，超时被 catch 落 FAILED/DISPUTED 不会带锁长跑。真压测客观未做 | `DistributedLockService.java:41-43,76,127-131`、`InternalRestClientFactory:16-18` |

**后端安全：**

| 原未验证项 | 结论 | 证据 |
|---|---|---|
| 85 Controller RBAC 全量 | ✅ **无 P0/P1 无保护写端点**。三层架构：默认拒绝拦截器 + `@RequiresPermissions` 切面（52/85 有注解）+ internal Key 拦截器。33 个无注解 Controller 全部判定：公开白名单（auth/notify/公告/media/public/marketing 两读/SPA 壳）、internal Key、仅登录（service 层 userId 归属校验）、Dev 门控 | `WebConfig.java:38-58`、`PermissionAspect.java:25-33` |
| 图形验证码强度 | ✅ 4 字符×32 字符集、TTL 300s、Redis `getAndDelete()` **一次性消费**（1/1,048,576）；接入 admin 登录/短信/重置。⚠️ consumer 与 merchant 密码登录无验证码（新 P2） | `CaptchaService.java:30-66`、`AuthController:59/88/114/119` |
| LoginThrottleService | ✅ 仅账号维度 5 次锁 10 分钟（无 IP 维度，网关 5r/s 按 TCP 源互补，无 XFF 绕过）；⚠️ 恶意锁死他人手机号的 DoS 向量（新 P2）；未注册号也计数防枚举 | `LoginThrottleService.java`、`AuthService:319-325` |
| SessionCookieService | ✅ 双 realm 均 `Path=/`+HttpOnly+SameSite=Strict+host-only；服务端按路径选 realm；CSRF=X-Requested-With 头（写方法强制，Bearer/GET token 豁免）。⚠️ 两点收紧项（新 P2）：ADMIN Cookie 可回落授权全部消费者 API；`AUTH_COOKIE_SECURE` 全链未配置 | `SessionCookieService.java:50-64`、`AuthInterceptor:49-51` |
| /api/v2/media 公开子树 | ✅ fileId→DB 行→refType 白名单过滤→storagePath 流式输出；无目录遍历（normalize+startsWith）、无列举端点、跨类型 404。⚠️ 自增 fileId 可顺序枚举（仅商品图/品牌 Logo 低敏内容，新 P2） | `MediaController`+`FileAttachmentService:648-795` |
| vision storage.py SSRF | ✅ 无 SSRF：`parse_object_uri` 仅接受 minio/oss/s3，`file://` 强制 resolve 落在 VIDEO_CACHE_DIR（含 symlink 解析）；MinIO 端点固定 env；Key+CIDR 中间件覆盖所有 /api/**。⚠️ 运行实例 `VISION_API_KEY=vision-key-change-me` 弱默认未换（P3+） | `storage.py:61-80`、`main.py:105-114` |
| 生产 Redis 定义链 | ⚠️ 生产 Redis=基座定义（redis:7-alpine，127.0.0.1，**无 requirepass**），production overlay 未补；validator 不查。唯一屏障=回环绑定+docker 网络（与 V3 交叉证实，新 P2） | `docker-compose.yml:23-36`、docker inspect 实测 |

**infra：**

| 原未验证项 | 结论 |
|---|---|
| nginx 三份 diff | 🔴 **漂移成立（新 P1）**：`/devops/grafana/` apps 栈有 basic auth、full 栈（运行中）无；`/admin` apps 代理后端 vs full 磁盘托管；`/consumer|merchant` 仅 full 栈有。三份→三套栈映射已实证（docker inspect Mounts） |
| staging 渲染 | ⚠️ X2 **部分属实**：overlay 仅钉死 `AICABINET_MOCK/SECURITY_MOCK=false`；PAYSCORE/PROFIT_SHARING/vision-MOCK 依赖 env-file（官方 staging 模板多数已 false）；**`RECOGNIZER_BACKEND` 用官方模板渲染回落 `"mock"` 未钉死**；误用 dev `.env` 起 staging 则四项 mock 全 true |
| production 渲染 | ✅ fail-loud 实证（`:?` 报错原文留存） |
| LINE/MERCHANT_WITHDRAW_MOCK 渲染值 | 客观不可验：非 compose 层变量，仅存在于 Spring yml 嵌套默认（`docker compose config` 不渲染），需 Java 侧断言 |

**admin-vue：**

| 原未验证项 | 结论 |
|---|---|
| 权限码三向比对（F=178 / B=184 / S=259） | ✅ **B−S=空集**（后端注解的每个权限码都有 Flyway 种子）；F−B=34 个全是客户端 CSV 导出/导入门控（不调后端，非死按钮），真正「可授但后端不认」仅 2 个（新 P2：`ops:rbac:assign:device`、recognition-demo view 与动作码解耦）；B−F=38 个中 33 个 `merchant:*` 属商户端正常、6 个为隐藏能力 |
| BigScreenView/PrintView 全量 | ✅ 无 P1：金额换算/定时器与 ECharts dispose/AMap destroy 全部清理、marker HTML 全 escapeHtml、路由 fail-closed。P2×4（见 10.3） |
| tests/ E2E 内容 | ✅ 6 个 Playwright UAT 脚本（87 断言）、棘轮 `UAT_MAX_FAIL=0`（CI 显式设定）、跑在 ci.yml e2e-h5 job；⚠️ `admin-alert-channel-uat.mjs` 未接 CI（新 P2）；E2E 实浏览器运行本轮未执行（需串行 UAT，见 10.5） |
| 巨型文件深读 | ✅ 无写路径金额/数量换算 P1；分页口径一致（page 0 起两套对齐）；`yuanToCents` 防浮点正确。P2×8（见 10.3） |

**双端 mp：**

| 原未验证项 | 结论 |
|---|---|
| 后端 /auth/refresh 契约 | ✅ 「旧 access 换新」一次性轮换：验签+boot claim+jti 吊销黑名单（TTL=剩余有效期+30s）→ 签发全新 JWT；consumer/merchant 共端点；单飞刷新两端共用 `shared-uni/request.ts:110-157` |
| server-boot 机制 | ✅ 返回进程启动毫秒；前端不一致→强制清会话重登，拉取失败→保留会话（区分网关抖动）；JWT boot claim 双层兜底。merchant 不消费此接口（401→重登即可） |
| debugInfo.mode | ✅ 后端按 `isConfigured()` 硬编码 live/mock，未配置且 mock 关→503。⚠️ 前端 mode 缺失时静默按 mock 处理（新 P2） |
| 订阅消息静默路径 | ✅ 模板空时入口整体不渲染、`onSubscribe` 直接 return，**完全不调用** `requestSubscribeMessage`；后端下发侧同样空值即不发 |
| use-auto-refresh 多页共存 | ✅ 实例闭包私有、start 先 stop 幂等、tick 有 inFlight 闸+maxDuration(3min)+dispose 永久失效；onShow/onHide/onUnload 三钩子接线两端一致 |
| 导出 openDocument 回退 | ✅ 仅 merchant 有导出（consumer 无此功能）：openDocument fail→H5 建 `<a download>`、mp 提示「从文件管理打开」，永不 reject 不卡 loading；settlements 有 RBAC 前置 |
| shared-dict 降级 | ✅ 三级解析（runtime→编译种子→「未知」），不露裸码；runtime 拉取失败回落种子不清空选项 |
| dist app.json patch | ⚠️ `patch-mp-weixin-appjson` fail-open（找不到仅 warn 退出 0）且 dev watch 链未接（新 P2）；`inject-miniapp-env` release **fail-closed**（缺 appid/urlCheck≠true→exit 1）✓ |

### 10.3 第二轮新发现（增补）

**P1（新 3 项）：**

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| V1 | `application.yml:168` + `ReconciliationScheduler.java:52-55` | `RECON_SCHEDULED_ENABLED` 默认 false，**每日对账与退款 PROCESSING 推进实际不执行**（DB 实证：last_message=「对账调度未启用」）。接真实微信后退款 ABNORMAL 将无人自动发现 | 生产 compose 强制 `RECON_SCHEDULED_ENABLED=true`；或退款推进拆出独立于对账门的任务 |
| V2 | `nginx.compose.conf:144` vs `nginx-full.conf:120` | 网关漂移：同一 `/devops/grafana/` apps 栈有 basic auth、full 栈无；同一 `/admin/` 代理 vs 磁盘托管——同 URL 跨栈行为不一致 | full 栈补 basic auth 对齐 apps 栈 |
| V3 | `docker-compose.full.yml:200,324` | trade XXL executor `:9999` 与 Grafana `:13000` 绑 `0.0.0.0`（docker ps 实测），超出演示需要；9999 叠加弱 `dev-xxl-token` 有注入面 | 端口绑 `127.0.0.1`；XXL token 生产已 `:?` 强制，full 栈也换强值 |
| V4 | **MinIO `cabinet-videos` 桶**（运行栈实测） | **桶级匿名可读可列举**：ListObjectsV2 与对象 GET 均无需凭据即 200；桶内含购物会话顶摄视频（`archive/…/session-*/…-top.mp4`）、补货证据照片、sim/perf 对象——后端 MediaController 的 refType 白名单被完全绕过。compose 初始化只 `mb`+`ilm` 无 anonymous 设置 ⇒ 政策系控制台手工设置的无意残留。当前缓解仅剩宿主端口绑 127.0.0.1 + docker 网络；dev 语境 P1，**生产若复制同政策即 P0** | `mc anonymous set none local/cabinet-videos`；把桶政策固化进初始化脚本（显式 private）；对外取流一律走后端流式或 presigned URL |

**P0（升级 1 项）**：S4 Grafana——弱口令 admin/admin **实测可登**（`isGrafanaAdmin:true`）+ `0.0.0.0:13000` + 网关无 Basic 补防 + `ALLOW_EMBEDDING=true`（环境级，full 演示栈语境）。

**P2（新 22 项）：**

| 域 | 位置 | 问题 |
|---|---|---|
| 资金 | `OrderPaymentService.java:254,295` | CHARGE_PENDING/INITIATED 痕迹无 sweeper/告警；TCC 三件套为无调用方死代码，徒增维护面 |
| 资金 | `ReconciliationService.java:189-191` | MISMATCH 仅打 Prometheus 指标，不发 OpsAlertDispatcher，运营无主动通知 |
| 资金 | xxl_job_log | ~~2612 年测试污染~~ **撤回（口径误报，lessons #232）**；实际待查项：8 小时 110 条 `trigger_code=500` 触发失败（cron 秒级重叠/misfire 补偿嫌疑，量小且有 tryBegin 兜底） |
| 安全 | `AuthController.java:120-125` | merchant-password-login 无图形验证码（admin 有）；consumer password-login 同样无 |
| 安全 | `AuthService.java:320,339` | 密码登录 5 次硬锁手机号 10 分钟，可被恶意锁死任意已知手机号（DoS 向量） |
| 安全 | `SessionCookieService.java:58-64` | ADMIN Cookie 可回落授权全部消费者 API（共享字典设计过宽） |
| 安全 | `AuthProperties.java:10`、application.yml:105 | `AUTH_COOKIE_SECURE` 默认 false 且全部 overlay/env 未配置 |
| 安全 | `MediaController.java:36-57` | 公开图片用自增 fileId 可顺序枚举（注释 "opaque id" 名不副实） |
| 安全 | `docker-compose.yml:23-36` | Redis 无密码，production overlay 未补，validator 不检查 |
| 安全 | vision 运行 env | `VISION_API_KEY=vision-key-change-me` 弱默认在用 |
| infra | `nginx.conf:3` | 限流 zone `device_ratelimit` 定义但从未引用（死配置） |
| infra | `docker-compose.apps.yml:188` | staging 未钉死 `RECOGNIZER_BACKEND`，官方模板渲染回落 mock |
| admin | `OperatorManageView.vue:680` | `ops:rbac:assign:device` 前端已用且已种子化，但后端接口只认 `ops:rbac:assign`——仅授 device 码的角色保存必 403 |
| admin | `menu.ts:494` + `OpsRecognitionController` | recognition-demo 菜单码与动作码种子不捆绑：仅授 view 的角色进得去、上传必 403 |
| admin | `BigScreenView.vue:900,834` | 30s 定时 load 无重入保护（慢响应时请求交叠后写覆盖）；投放柜 size=100/排行 size=50 硬上限静默截断 |
| admin | `PrintView.vue:251-265` | 标签打印拉 `size=500` 单页后客户端过滤：>500 SKU 静默缺失，softFallback 吞错 |
| admin | `ReplenishmentView.vue:1494-1505,1674` | 临期 tab 服务端分页后客户端过滤致 total 虚高、其他页命中不可见；`currentAssigneeId()` 兜底硬编码 userId=1 |
| admin | `useWarehouseTransfers.ts:48-80`、`useWarehouseBins.ts:165,213` | 数量缺 >0/整数校验、from==to 不拦、`Number(qty)||0` 可提交 0 |
| admin | `useReplenishmentTaskLines.ts:141-151,256-289`、`useWarehouseEntityDialogs.ts:168` | blob URL 竞态泄漏；履约 N+1 预取（24 任务×GET lines）；`Object.assign` 换行串显 |
| admin | `tests/admin-alert-channel-uat.mjs` | UAT 脚本未接 CI（与 ci.yml:104 注释承认过的同类病） |
| mp | `scripts/patch-mp-weixin-appjson.mjs:28-30` | fail-open 且 dev watch 链未调用，dev 产物可能缺 lazyCodeLoading |
| mp | `consumer-mp/src/utils/recharge.ts:227-229` | `debugInfo.mode` 缺失时静默按 mock 处理，live 下字段丢失会误调 confirmMockRecharge |
| 包产物 | `packages/shared-rbac/dist`（落后 27 天）、`shared-dict/dist`（落后 6 天） | rbac dist 缺 B-13 修复（**dist 中 `ops:admin` 无条件放行跨域**，src 已修；两端靠 vite 别名到 src 兜底，node 直消费 dist 的脚本会拿到旧逻辑）；dict dist 缺 7 组词条。`check:shared` 只重建不比对故恒绿 |

### 10.4 修订后的优先级建议

1. **S4（升级 P0）**：full 栈 Grafana 弱口令实测可登——本机一条命令即可修（强口令 + 端口绑回环 + basic auth）。
2. **F2 免单旁路 → X1 preflight 盲区 → V1 对账调度默认关**：三个都是小改高价值。
3. **S1 设备共享凭据 → F1 竞态 → F3+X2**：接真前提切片不变。
4. 其余 P1（V2/V3 网关漂移与端口绑定）与 22 项新 P2 按域打包。
5. 新增低成本收口：`pnpm build:packages` 连同 dist 一起提交（清掉 rbac/dict dist 陈旧，lessons #231）。

### 10.5 最终清单：复核后重分类（对子代理「客观不可验」逐条复查后的裁定）

> 第三轮复核（主审亲测）发现：子代理标的「客观不可验」有 **4 项其实本机就能验**，且其中一项（MinIO）验出了新 P1。逐条裁定如下。

**原「不可验」→ 实际已验证（本轮补验）：**

| 原判定 | 复核裁定 | 实测结果 |
|---|---|---|
| 「MinIO 匿名直连策略未探测」 | ❌ 标错了——MinIO 就在本机 19000 | 🔴 桶级匿名可读可列举（含会话视频），新 P1·V4 |
| 「XXL 真实调度精度不可验」 | ❌ 标错了——xxl_job_log 按 DATETIME 正确口径即可量测 | ✅ 30s cron 真实触发、执行器健康、成功 927/8h；顺带撤回「2612 污染」误报（lessons #232） |
| 「htpasswd 口令强度不可逆向」 | ❌ 标错了——apr1 盐内嵌哈希串，可离线验证候选 | ✅ 7 个常见候选全部不匹配，非占位弱口令 |
| 「CORS 生产值不可确认」 | ◐ 部分标错——**运行容器**的 env 可 inspect | ✅ dev 运行值 `CORS_ORIGIN=http://localhost`；`.env.production` 实际值仍属仓库外 |
| 「真机端到端（开门全链）不可验」 | ◐ 表述过宽——**模拟器可代硬件** | ✅ 开门→购物→结算主链已由 S0–S3 UAT（device-simulator）覆盖并有 PASS 记录；真机专属差异（摄像头、真机支付）才不可验 |

**真客观不可验（维持，理由充分）：**
1. `uni.requestPayment` live 收银台、订阅消息真机弹窗、支付宝沙箱回跳（无正式 appid/真实商户号/审核通过的模板 ID）。
2. 真实微信/支付宝签名互操作与真实账单下载（无商户私钥/平台证书/真实渠道流量）。
3. Redisson 60s 租约**真实负载**压测（轻量并发冒烟可做但需写库造会话，归入未执行）。
4. `.env.production` 实际值、生产部署形态、生产 CORS/htpasswd 的生产环境真实值（仓库外运行时事实）。

**原「不可验」→ 改判「未执行（本机可做）」：**
1. 分账回退补偿全链：mock 渠道下可造失败回退任务验证补偿重试链（`compensation_task` 现为 0 行），需写库造数，本轮未做。
2. 轻量并发/锁冒烟：repo 有 `security-concurrency-recon-test` 类脚本，可在 dev 栈跑（写测试数据，之后 `cleanup-test-data.ps1` 清理），本轮未做。

**第四轮执行后的最终状态（原「未执行」已基本清零）：**
1. ~~mvn verify 全量 Java 测试~~ → **已做**：1,344 用例全绿。
2. ~~e2e-h5 实浏览器~~ → **admin 侧 6 脚本已本地实跑**（结果见 10.1）；consumer/merchant 的 H5 UAT 按用户口径**不作为 mp 端验收**（mp-weixin 权威，H5 仅 CI 冒烟；mp 端实机证据=同日 DevTools 烟测 19+22 路由与 S3 页矩阵）。**审查对象说明**：双端小程序审查扫的是 `clients/*/src` **同一份 uni-app 源码**（非 H5 构建产物）；两端全部条件编译仅 39 处，本轮报出的 P1/P2 均落在双端共用逻辑层（C1/C2 代码区域实测无 `#ifdef`），结论对 mp-weixin 直接有效；平台差异面（登录/支付/storage/H5 cookie 会话）在报告内已按端各自标注。
3. ~~补偿链验证~~ → **已做**：合成任务运行时实证（见 10.1）。
4. ~~并发锁冒烟~~ → **已做**：30 过 0 挂（见 10.1）。
5. `check:shared` 的 `build:packages` 原样执行与 `check-openapi-types` regen 原样执行 → 维持用 .tmp 等价比对结论（OpenAPI 一致、dist 陈旧属实）；`build:packages` 属**修复动作**（刷新 dist）而非验证，留待修复轮一并做。

**前两轮子代理结论的亲验对账（主审逐条复核后的裁定）：**
- 第一轮 11 项 P1 全部亲验属实：F1（无锁预检+先 capture 后扣款结构，`SettlementOrderFinalizeService.java:136-154` 亲读）、F2/F3（源码亲读）、S1（ACL 亲读+APK `build.gradle.kts:21` 内嵌 `aicabinet-device`/`dev-mqtt-device-pass` 亲读）、S2（`AuthInterceptor.java:36-43` 亲读）、S3（`MerchantService.csv()` 仅处理逗号/引号/换行，无公式中和，亲读）、S4（curl 实测）、A1（`RechargeListView.vue:204` 亲读）、C1/C2（consumer 源码亲读）、X1（preflight 源码亲读）、X2（compose config 亲渲染）。
- 第二轮抽查修正 2 处：① V1「xxl_job_log 2612 年测试污染」**撤回**（DATETIME 口径误报，XXL 实际健康，lessons #232）；② V4「`ops:rbac:assign:device` 后端不认」**降级为权限码双轨不一致**（`CompetitiveGapService.java:122` 用 `requireAnyPermission` OR 语义认该码，但 `OpsRbacController` 多处仅认 `ops:rbac:assign`——真实问题是码不统一，非全盘死码）。其余 V1/V2/V4/V5/V6 关键断言（TCC 无调用方、RECON 门默认关、验证码 getAndDelete、Cookie ADMIN 回落、recognition-demo view/动作码解耦、recognition 控制器 OR 权限）均亲验属实。

---

## 十一、修复轮（2026-09-27 同日，已落地并验证）

> 按 §10.4 顺序执行。除标注「随下次镜像生效」外均已实测。变更已 `git add`（未提交，待用户决定）；提交后全链预检即 48/48 全绿。

### 11.1 已修清单（14 项）

| 级别 | 项 | 修法 | 验证 |
|---|---|---|---|
| **P0** | S4 Grafana 弱口令+暴露面 | `.env` 换强口令；compose 端口绑 `127.0.0.1`（grafana 13000、trade XXL executor 9999）；nginx-full `/devops/grafana/` 补 basic auth（挂 `grafana.htpasswd`，与 apps 栈同款）；`grafana.htpasswd` 重生成为强口令（口令记于 `infra/.env` 的 `GRAFANA_BASIC_AUTH_PASSWORD`，文件 gitignored） | recreate 后实测：`admin:admin`→401、新口令→200、无 Basic→401、带 Basic→200、`/admin` 无损 |
| **P1** | V4 MinIO 桶匿名 | 两套 compose init 加 `mc anonymous set none`；**运行桶已即时收紧**（一次性 mc 容器） | 匿名读对象/列举均 403 |
| **P1** | V1 对账调度默认关 | `application.yml` `RECON_SCHEDULED_ENABLED` 默认 `true`；production overlay 钉 `"true"` | 生效随下次镜像重建；DB 门信息将消失 |
| **P1** | X2 staging mock 纵深 | staging overlay 钉死 `PAYSCORE/PROFIT_SHARING/LINE\|MERCHANT_WITHDRAW_MOCK=false`；vision 钉 `RECOGNIZER_BACKEND:-yolo` | compose 渲染复核 |
| **P1** | F2 购物超时免单旁路 | 状态机加运营边 `SHOPPING→DISPUTED`；`expireOneStaleConsumerShoppingSession` 对齐 C09：释放预授权（失败不阻断）→DISPUTED→`createTimeoutTicket`，DISPUTED 迁移失败回退 CANCELLED；DOOR_OPEN_TOO_LONG 文案同步 | `SessionStateTest`+`SessionServiceRecoveryTest` 等三类 **14 用例绿**（含 DISPUTED 断言、ticket verify、回退路径） |
| **P1** | F3 提现盲置 FAILED | 两服务 `failSingleStalePaying` 仅 `payChannel=MOCK` 自动置 FAILED；真实渠道不自动失败，落 `*_WITHDRAW_PAYOUT_STALE_MANUAL` 审计+warn 转人工；`PAYING_TIMEOUT_MINUTES` 改按 `MINUTES`（60 分钟，修正 60 小时名实不符） | 两 sweep 测试类 **11 用例绿**（含新增 real-channel skip 用例） |
| **P1** | X1 preflight 盲区 | `pre-push-ci-preflight.mjs`：openApiSurface 时默认档**强制**跑 `check:openapi-types` 结构档（live regen 仍归 CI+`--full`） | wiring 门禁 ✓、结构档直跑 ✓、prettier/eslint ✓ |
| P2 | S3 后端 CSV 公式注入 | 新建 `support/CsvCells`（中和 `=+-@`+TAB/CR 前缀、RFC 包裹），`MerchantService`/`MerchantDevicePortalService` 统一走它 | `CsvCellsTest` 3 用例绿 |
| P2 | V1 MISMATCH 无告警 | `ReconciliationService` 注入 `OpsAlertDispatcher`，MISMATCH 时发 `RECON_MISMATCH` 运营告警（投递失败自吞+warn） | `ReconciliationServiceTest`/`ConcurrencyTest` 绿 |
| P2 | S2 GET query token | **全仓证实无消费方**（前端 C-7 已改 Bearer 下载）→ 直接删除 `AuthInterceptor` 的 `?access_token=` 后备 | auth 域 4 测试类 16 用例绿 |
| P2 | A1 充值退款状态绕契约 | `money-ui-contracts` 增充值域 `canRefundRechargeStatus`；RechargeListView 复用 | admin tsc+84 用例绿 |
| P2 | admin/consumer/merchant 端点门禁盲区 | admin `stores/`、merchant `utils/+components/` 纳入扫描根；两端 `dict-runtime` 裸路径收口到 Endpoints；merchant-config 注释措辞修正 | 三端 gate 绿（admin 70 literals/merchant 15/consumer 16） |
| P2 | C1 开门重试不分类 | shared-uni 导出 `isRetriableMpTransportError`；`createSession` 仅对超时/网络错误重试 | 双端 type-check+121 用例绿 |
| P2 | C2 首页轮询无上限 | `landing-session` 增 `SESSION_POLL_MAX_DURATION_MS`（10 分钟）+`pollDurationExceeded`；到期停表提示去订单页，同会话不重燃 | consumer tsc+121 用例绿 |
| P2 | 杂项 | BigScreen `load` 重入保护；WalletPage 提现前置 `hasPerm`+最低 ¥1 预检（契约扩展 `BELOW_MIN_AMOUNT`）；`shared-rbac/dist`+`shared-dict/dist` 重建提交；merchant `tsc-out.txt` 清理 | admin tsc+BigScreen 编译、merchant tsc+130 用例绿 |

### 11.2 修复后统一验证

- 三端 type-check：admin/consumer/merchant `vue-tsc --noEmit` 全 exit 0。
- 三端单测：84/121/130 全过。
- 后端定点：F2+F3+CSV+告警+auth 域共 10 个测试类全绿（`<testcase>` 计数）。
- 全链预检：**47 ✓ / 1 ✗**——唯一 ✗ 为 admin 产物「待提交」门禁的设计态（staged 产物在提交前必报）；提交后即全绿。

### 11.3 未修（留给后续批次，理由）

| 项 | 理由 |
|---|---|
| F1 结算预授权冲抵竞态 | 需「净额=已完成+已冲抵预授权」口径设计与并发单测，独立切片 |
| S1 设备共享 MQTT 凭据 | 涉 EMQX ACL/生成器/APK 三处设计，接真硬件前完成即可 |
| 登录验证码扩 merchant/consumer、Cookie ADMIN 回落收窄、`AUTH_COOKIE_SECURE`、Redis 密码、CHARGE_PENDING sweeper、TCC 死代码下线、media fileId 随机化、`ops:rbac:assign:device` 双轨统一、BigScreen/PrintView 截断、ReplenishmentView 过滤 total、兜底 userId=1、warehouse 数量校验、blob 泄漏、履约 N+1、Object.assign 串显、alert-channel UAT 接 CI、patch-mp-weixin dev 链、recharge mode 缺失按 live 处理 | P2 尾巴，按域打包小批量 |
| V1 对账调度 | 代码已改，dev 栈生效需下次镜像重建（`docker-up.ps1 -Build`） |

---

## 附：审查执行记录

---

## 十二、第一层+第二层执行（2026-09-28）

### 12.1 第一层：运行栈对齐最新代码 ✅
trade+device 双镜像重建、redis/trade/device 协调替换。运行时取证：redis 密码生效（无凭据 NOAUTH/带凭据 PONG）、ops 登录链路通、一致性 FAIL=0、Flyway V288 已应用。

### 12.2 第二层：批次一（提交 `630e6b75`）✅
1. **L2-1 CHARGE_PENDING 超时告警**：`alertStaleChargePendingOps()` 搭载 unpaidCancel 15 分钟任务；>60 分钟未收口发 `CHARGE_PENDING_STALE` HIGH 告警（不自动改单）。
2. **L2-3 TCC/分布式事务死代码下线**：删 `TccTransactionCoordinator`/`DistributedTransaction`/Mapper（含 .xml——**MyBatis XML 扫描漏删曾致启动失败，已修**）、`retryFailedTransactions` 全链、XXL handler/种子/注册表/KEYS/ScheduleZones 五处同步；`V288` DROP 两张死表+清 scheduled_task 行；**live xxl_job_info 已删 compensationRetryJob**。调度器仅保留分账回退补偿（测试 5 用例含未知类型防御）。

### 12.3 第二层：批次二（提交 `e5913e64`）✅
3. **L2-2 登录验证码（失败升级式）**：连续失败 ≥2 次后 `password-login`/`merchant-password-login` 强制图形验证码；双端登录页按需显示验证码 UI、失败后自动刷新。**首登用户与全部 e2e 脚本零破坏**（首试成功不触发）。
4. **L2-4 Redis 密码**：base+full compose `--requirepass`（dev 默认 `devredis`）；trade/device application.yml + 全 overlay env 对齐；staging/production `:?` 强制强口令。
5. **L2-6 BigScreen/PrintView 截断**：`fetchAllPages`/`fetchAllSkus` 分页拉全。
6. **L2-10 merchant `build:mp-weixin:dev` 脚本化**（含 appjson patch，补齐与 consumer 的链路差）。

### 12.4 门禁演进（提交 `411e5796`）
`check-scheduled-task-seed` 新增规则 2.5：识别后续迁移中的 `DELETE FROM scheduled_task … task_key='…'`（V288 模式），任务下线时登记行删除不再误判「残留登记行」；注册表仍注册已删 key 则报错。

### 12.5 插曲与教训
- 删 Mapper **接口**时漏删同名 **.xml**（MyBatis 扫描 resources/mapper/*.xml 解析失败 → trade 启动崩）——本地实测+CI e2e-h5 双双当场暴露，fix-forward 修复（`93a1d336`）。教训：删 Java Mapper 必同查 `resources/mapper/*.xml`。
- L2-4 部署时发现 full 栈 redis 定义是**自包含覆盖**（不经 base compose）——改 base 不够，须同步 full.yml（两处已一致）。
- 推送前漏跑完整预检一次（批次二），补跑后以 gate 修复 fix-forward 收口；后续严格维持「push 前预检」铁律。

### 12.6 L2 未做（维持挂账）
媒体 fileId 随机化（MinIO 私有化后降级）、临期 tab 服务端过滤、履约 N+1 聚合、alert-channel UAT 接 CI（需先验证 CI 无外网时 AC-05 行为）、mp dev watch 链 lazyCodeLoading。

- 方式：6 路 Explore 并行只读审查（约 2.7M–4.4M tokens/路），主审对 5 项载荷结论复核源码坐实（validator mock 拒绝、超时取消无争议、MQTT ACL、createSession 重试、preflight 警告档）。
- 工作树：审查时点 git clean（`8feb079a`）。
- 本报告结论有效期：至下次大规模改动；引用行号以审查时点为准。
