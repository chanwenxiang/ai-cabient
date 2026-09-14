# 工程踩坑总册（现象 → 根因 → 必须怎么做）

> 由规则 `record-lessons-learned` 维护。能归入领域 `.mdc` 的优先写领域表；此处收跨模块或尚未单独立规的条目。  
> 后台布局专表见：`.cursor/rules/admin-layout-anti-jitter.mdc`。

| # | 领域 | 现象 | 根因 | 必须怎么做 | 门禁/文件 |
|---|------|------|------|------------|-----------|
| 1 | admin 布局 | 操作列横滑跑走 | 为防抖关掉右侧 sticky | 右侧 `fixed-column--right` 必须 sticky；左侧可 static | `pnpm check:admin-anti-jitter` |
| 2 | admin 抽屉 | 拉窄松手自动变宽 | 先清 inline width，EP 闪回旧 `:size` | 松手先钉 `finalW` + `nextTick`；禁 pointerup 清空 width | 同上 |
| 3 | admin 抽屉 | 点纵向滚动条内容缩一下再弹；按钮闪折行 | Win overlay→经典条吃宽 + `overflow-y:auto` + `flex-wrap` | body 用 `overflow-y:scroll` + gutter；操作行 `nowrap` | 同上 |
| 4 | admin 表格 | 长文案悬停盖邻列 | `show-overflow-tooltip` 浮层挂表内，横滚/sticky 错位 | **全后台禁** `show-overflow-tooltip`；`installTableCellNativeTitle` + 可选手写 `:title` / `.cell-ellipsis`；门禁扫全部 `.vue` | 同上 |
| 5 | admin 审单 | 数量框挡住「删除」 | 数量列 132px < input-number ~150px | `.manual-line` 数量列 ≥150px 且 input `max-width:100%` | 同上 |
| 6 | 业务空态 | 识别存疑「订单/关联订单」显示暂无被当成丢数据 | 争议未落账前会话无 `order_id`，属阶段空 | 文案用「待落账」+ title 说明；结案后才有订单号 | Exception/Dispute 列表视图 |
| 7 | ops RBAC | 从超管角色去掉按钮权限后，UI/API 仍能操作 | `PermissionService`：持有 `ops:admin` 时运营域权限一律放行 | 测按钮权限必须用不含 `ops:admin` 的角色；超管场景只测「有权限」路径 | `PermissionService.hasPermission` |
| 8 | admin 表单 | 运营账号「重置密码」新密码框蓝底；placeholder 像已填正文 | Chrome autofill 改背景；`-webkit-text-fill-color` 误伤 placeholder | 弹窗内单独 autofill 覆盖 + `autocomplete="new-password"`；placeholder 用 `--el-text-color-placeholder`，autofill 只作用于已填值 | `OperatorManageView.vue` |
| 9 | admin 鉴权 | 退出登录连弹两次「请先登录」 | 清 session 后在途 inbox 轮询/请求 401，全局拦截器仍 `ElMessage.error` | 退出设 `loggingOut` 抑制 toast；轮询判 `isLoggedIn()`；退出时 `closeAll()` | `api/client.ts`、`logout-message-guard.ts`、`OpsApprovalInbox.vue` |
| 10 | admin 登录 | 登录页不应出现自助「忘记密码」 | 产品：仅系统→运营账号、持 `ops:rbac:assign:reset-password` 者可重置 | 禁止登录页忘记密码/SMS 重置入口；重置只走运营账号 API | `LoginView.vue` |
| 11 | admin UI | 一致性「基准/对照」露出 `SALE_OK`/`MISSING_SALE` 等英文码 | 后端存诊断码，前端未映射 | 展示走 `formatConsistencyValue` + `consistency_diag_code`；悬停可看原始码 | `ConsistencyView.vue`、`shared-dict` |
| 12 | CI / GitHub | 连续多次 `CI` failure：`generated OpenAPI types are stale` | 新增/改 API（如 reset-password）后未 `pnpm gen:api-types` 并提交 `packages/shared-types` | **改 Controller/DTO 后必须**：起 trade → `pnpm gen:api-types` → 提交 `src/generated/`+`dist`；推前本地 `OPENAPI_CHECK_REGEN=1 OPENAPI_FILE=.tmp/live-openapi.json pnpm check:openapi-types` | `scripts/gen-openapi-types.mjs`、`check:openapi-types` |
| 13 | CI / ESLint | `pnpm lint` 因 UAT 脚本未用变量 / JMeter report 第三方码失败 | `docs/uat-screenshots` 与压测 HTML 报告被扫进 `eslint .`；脚本里残留未使用绑定 | UAT 留证目录与 `.tmp` **必须**在 `eslint.config.mjs` ignores；`scripts/*.mjs` 未用变量删掉或 `_` 前缀；推前 `pnpm lint` | `eslint.config.mjs` |
| 14 | admin RBAC | 漏登侧栏菜单的路由任意登录可进 | 守卫只拦 `nav?.perm`，无 menu 项时 fail-open | 业务路由必须能 `findNavByPath` 或显式 `meta.perm`；未知路径 **deny**；`canAccessPath` 同口径 | `router/index.ts`、`useNavAccess.ts` |
| 15 | consumer 附近 | 拒定位仍看到上海柜机 | `getLocation` fail 静默回退硬编码坐标并继续拉列表 | 定位失败必须 error 空态，禁止默认城市假附近；半径切换在无定位时不请求 | `nearby.vue` |
| 16 | consumer live | 真机关门后用户只能干等「请关门」 | live 刻意不暴露 demo-close，但缺刷新/客服兜底 | SHOPPING+!mock 必须提供「刷新状态」「未出账单？」；禁止把 demo-close 接到生产 | `index.vue` |
| 17 | 资金限额 | 前端 ¥5000 可被改包绕过 | 充值/余额退款后端无同款上限 | `recharge.max_cents` / `balance.refund.max_cents` 服务端强制；单测覆盖超额拒绝 | `PaymentService`、`BalanceRefundService` |
| 18 | merchant 补货 | 列表进场卡顿/弱网炸请求 | 每任务并发拉 evidence+lines，最多 80 请求 | 列表 DTO 聚合 `evidenceCount`/`lineSummary`；禁列表页 N+1 | `MerchantInventoryPortalService`、`replenishment.vue` |
| 19 | Kafka 视觉 | 识别失败消息静默丢 | auto-commit + catch 吞异常 | vision `enable_auto_commit=false` 成功/入 DLT 后 commit；trade 失败发 result.DLT 再抛 | `kafka_worker.py`、`VisionRecognitionListener` |
| 20 | toast | 成功操作弹出「错误」无图标 toast | 误用 `showError`（icon:none） | 成功/中性文案必须 `showSuccess` | consumer/merchant 多页 |
| 21 | session 超时 | 开门/补货/识别过期只能改代码常量 | 魔法值散落 SessionService | 收归 `aicabinet.session-expire` + `SessionExpireProperties` | `application.yml`、`SessionExpireProperties` |
| 22 | vision 超时 | 云端识别挂起无结果 | 无 wall-clock timeout / 失败只进 DLT | HTTP+Kafka 超时回退 `need_review=true` 仍发 result | `main.py`、`kafka_worker.py` |
| 23 | 改价覆盖 | 并发改价后写互相覆盖 | `device_sku_price` 无 version | 库存同款乐观锁 + 客户端传 `expectedVersion` | `DeviceSkuPriceMapper`、`pricing.vue` |
| 24 | consumer H5 | XSS 可读 JWT | H5 把 token 存 Storage | `cookieEnabled` 时不落 JWT，`withCredentials` + CSRF 头 | `consumer-api.ts`、`shared-uni/request.ts` |
| 25 | 支付 mock | mock 路径挂在正式 `/payment` 前缀易被误当契约 | DevMock 与正式 Controller 同前缀 | mock 仅 `/api/v2/dev/payment/**` + `mock-enabled`；正式 `PaymentController` 契约冻结注释 | `DevMock*Controller`、`PaymentController` |
| 26 | H5 隐私 | 首屏无同意即可继续用 | 缺隐私同意门闩 | H5 入口页弹 `privacy-consent-modal`，同意键 `aicabinet_privacy_consent_v1` | `shared-uni/privacy-consent` |
| 27 | consumer 分包 | 主包过大启动慢 | 24 页全进主包无 `subPackages` | tab/登录/结果留主包；其余按目录 `subPackages` + `preloadRule` | `consumer-mp/src/pages.json` |
| 28 | Settlement 拆分 | 改退款/异步视觉易牵动整类回归 | 纯计算与异步提交混在上帝类 | 按行退款数学进 `SettlementPartialRefundMath`；部分退编排进 `SettlementPartialRefundService`；异步视觉进 `SettlementVisionAsyncService`；免单进 `SettlementWaiveRefundService`；争议确认进 `SettlementConfirmDisputeService`；落单扣款进 `SettlementOrderFinalizeService`；识别决策进 `SettlementRecognitionService`；settle 入口进 `SettlementSettleOrchestrator`；行/DTO 进 `SettlementOrderSupport`；主类只委托 + 锁 | `SettlementService` |
| 29 | merchant 分包 | 主包过大冷启动慢 | 业务页全进主包 | tab+登录留主包；其余 `subPackages` + `preloadRule` | `merchant-mp/src/pages.json` |
| 30 | merchant 补货 | 开门缓存/确认框/列表逻辑与页耦合难测 | 状态机散落在 3k 行页内 | 开门缓存进 `useReplenishmentDoorState`；确认框进 `useAppConfirmDialog`；列表进 `useReplenishmentList`；签到/开门/核对/完成进 `useReplenishmentFulfillment`；深链/详情/凭证进 `useReplenishmentDetail`；扫柜/扫商品进 `useReplenishmentScan`；SKU/货道/任务文案进 `useReplenishmentDisplay`；Hero/导航/加载/步骤进 `useReplenishmentShell`；UI 块继续拆子组件 | `composables/`、`Replenish*.vue` |
| 31 | Session 拆分 | 改 expire/开门/补货/门事件/结算易牵动整类回归 | 调度与短事务混在上帝类 | expire→`SessionExpireService`；开门短事务→`SessionOpenService`；补货快照短事务→`SessionRestockService`；门事件/关门路径→`SessionDoorService`；关门后 settle/异步识别/演示零元/开发上传→`SessionSettleService`；状态变更仍走 `SessionService.transition` | `SessionExpireService`、`SessionOpenService`、`SessionRestockService`、`SessionDoorService`、`SessionSettleService` |
| 32 | admin 仓库页 | 改一域弹窗易误伤其它域 | 单文件 5k+ 行多业务混杂 | 采购/盘点/货位/出库/调拨写流分别进 composable+Dialogs；仓库/供应商/付款/其它入库进 `useWarehouseEntityDialogs`；tab 加载进 `useWarehouseTabLoader`；筛选/在途时效进 `useWarehouseListFilters`；CSV 导入导出进 `useWarehouseCsv`；展示文案进 `useWarehouseLabels`；路由深链/分页/keep-alive 进 `useWarehouseRouteLifecycle` | `WarehouseView.vue` |
| 33 | admin 列表性能 | 单页拉 100+ 行卡顿回潮；虚拟表缺位 | `page-sizes` 含 100 且无门禁；多数页仍用普通 el-table | 列表 `:page-sizes` 不得超过 50；用 `ADMIN_LIST_PAGE_SIZES` + `clampAdminPageSize`；大数据试点用 `AdminVirtualTable`；合入前 `pnpm check:admin-page-size` | `admin-list-pager.ts`、`AdminVirtualTable.vue`、`check-admin-page-size.mjs` |
| 34 | Flyway | trade 启动报 duplicate version 270 | 两份 `V270__*.sql` 同号合入 | 新迁移必须用下一空号（已有 V270 则用 V274+）；合入前 `ls db/migration/V*.sql` 查重 | `V274__device_sku_price_version.sql` |
| 35 | vision | 容器 uvicorn SyntaxError 起不来 | `if (` 缺右括号 | Python 改条件后本地 `python -m py_compile app/main.py` 再打镜像 | `vision-service/app/main.py` |
| 36 | admin 鉴权 | 首屏 `/rbac/me/*` 打两遍 | App + router restore 且 Layout `onMounted` 再 `refreshPermissions` | 首屏只走 `beforeEach → restore`（inflight 去重）；Layout 仅 window `focus` 刷新 | `App.vue`、`auth.ts`、`AdminLayout.vue` |
| 37 | admin 退出 | 非 Layout 路径 logout 后长期吞 401 Toast | `logoutSession` 置 `loggingOut=true` 却无 `endLogout` | 退出生命周期收进 `logoutSession`（begin + 定时 end） | `api/client.ts` |
| 38 | admin 列表 | 设备运维改 el-table-v2 后表头/多选/拖列宽与其它页不一致 | v2 无原生拖列宽且样式体系不同 | 常规运营列表优先标准 `el-table`（`border`+`type=selection`）；性能靠 pageSize≤50；虚拟表仅极端大数据页 | `DeviceOpsMonitorView.vue` |
| 39 | trade 异常 | 500 日志难对齐网关请求 | 通用 handler 未写 MDC traceId | `log.error(..., RequestCorrelation.summary(), ex)`；响应头 `X-Trace-Id` + 文案短追踪号 | `GlobalExceptionHandler`、`RequestCorrelation` |
| 40 | admin 生产 | 线上控制台仍有 warn 噪音 | 软失败路径裸 `console.warn` | 软路径用 `adminDevWarn`（仅 DEV）；生产安全告警可保留 | `admin-dev-log.ts` |
| 41 | admin RBAC | 停用菜单首屏短暂可见 | `isNavMenuActive` 在 `!activeNavLoaded` 时 true | ACTIVE 未加载必须 fail-closed（`isNavMenuActiveFor`） | `rbac-cache-policy.ts` |
| 42 | trade 日志 | 门事件日志难按用户/柜机检索 | 多数只打 sessionId | 关键路径用 `SessionLogContext.of(session)` | `SessionDoorService` |
| 43 | trade 纠纷 | 工单状态守卫散落字符串易漂移 | OPEN/RESOLVED/CLOSED 比较分散 | 统一 `DisputeTicketTransitions` + 单测 | `DisputeService` |
| 44 | trade 会话 | 超时/运维直接 `setState` 绕过状态机 | `canTransitionTo` 边不全 + 旁路写 | 扩合法边；已有实体只走 `SessionService.transition` | `SessionState`、`Session*Service` |
| 45 | Kafka 消费 | 高峰易 rebalance / 一次拉太多 | 未限 `max.poll.records` | trade/vision 显式 `max-poll-records`（默认 50/20）+ poll/session 超时 | `application.yml`、`kafka_worker.py` |
| 46 | admin a11y | 抽屉/对话框读屏无名 | 部分组件只转发 attrs、无强制 title | `ResizableDrawer` 必填 title；`check:admin-dialog-a11y` 门禁 | `ResizableDrawer.vue`、`check-admin-dialog-a11y.mjs` |
| 47 | mapper 分页 | 分账列表假分页拖垮堆 | `searchByMerchantsAll` 全量再 subList | 必须 `selectPage`；禁止内存切片冒充分页 | `OrderRevenueSplitMapper` |
| 48 | 线长日佣 | 按日全量订单再 filter 柜机 | `findByCreatedAtBetween` + Java filter | 必须按 `deviceId`+时间窗查 | `CabinetOrderMapper`、`LineCommissionJob` |
| 49 | 用户行为分析 | 堆 OOM / 慢 | `orderRepository.findAll()` 物化全表 | 必须 `GROUP BY user_id` 聚合；禁止分析路径 `findAll` | `UserBehaviorAnalyticsService`、`CabinetOrderMapper` |
| 50 | 温湿度/用户列表 | 长窗口或历史无界拖垮接口 | `findByDeviceIdSince` / `findByUserId*` 无 LIMIT | 历史必须 LIMIT（温湿度硬顶）；用户侧列表默认 ≤100 | `DeviceTemperatureReadingMapper`、`DisputeTicketMapper` 等 |
| 51 | 业务缓存 | TTL/前缀散落难治理 | 裸 `"dashboard:*"` + `30_000L` | 必须 `CacheNames` 常量；新缓存禁止魔法串；门禁 `pnpm check:cache-names` | `CacheNames.java`、`check-cache-names.mjs` |
| 52 | 定时任务 | 日界错一天 / cron 无 zone | `@Scheduled(cron)` 缺 `zone` 或用系统默认时区 | 统一 `ScheduleZones` / `aicabinet.schedule.zone`；门禁 `pnpm check:scheduled-zone` | `ScheduleZones.java`、`check-scheduled-zone.mjs` |
| 53 | API 版本 | 无法灰度 / 客户端不知版本 | 仅路径硬编码 `/api/v2` | 契约常量 `ApiVersions`；响应 `X-Api-Version`；未支持主版本 410；破坏性开 v3 | `ApiVersions.java`、`ApiVersionInterceptor` |
| 54 | admin 端点 | 同路径多处拷贝易漂移 | views 裸 `/api/v2/ops/admin/...` | 高频路径进 `AdminEndpoints`；试点字面量门禁 `check:admin-endpoints` | `api/endpoints.ts`、`check-admin-endpoints.mjs` |
| 55 | admin JWT | XSS 可读长期 JWT | 非 Cookie 把 `admin_token` 写 localStorage | Cookie 优先；dev 仅 sessionStorage；生产无 Cookie 则 fail-closed；禁 `localStorage.setItem(admin_token)` | `auth-storage.ts`、`check-admin-token-storage.mjs` |
| 56 | consumer 消息券 | 点优惠券消息以为要领券却应设优先 | 审计误把 COUPON bizId 当活动 id；`claimCampaign` 要 activityId | COUPON=`user_coupon.id`→设 preferred；营销领券走 CAMPAIGN/`claimCampaign`；成功提示用 `showSuccess` | `messages.vue`、`coupons.vue` |
| 57 | consumer/merchant API | 页面裸路径与消息深链丢参 | 充值绕过 consumerApi；splits 只读 status | 列表 API 进 `*Api` 门面；深链 query 必须读全（orderId 置顶） | `recharge.vue`、`splits.vue` |

## 追加模板

```md
| N | 领域 | 一句话现象 | 一句话根因（有证据） | 禁止/必须… | 脚本或路径 |
```
