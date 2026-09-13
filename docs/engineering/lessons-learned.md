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

## 追加模板

```md
| N | 领域 | 一句话现象 | 一句话根因（有证据） | 禁止/必须… | 脚本或路径 |
```
