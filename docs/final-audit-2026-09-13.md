# ai-cabinet 全方位代码审计 · 最终版（Final R1 + 源码复核）

> 审计范围：admin-vue、consumer-mp、merchant-mp、services（trade-service + device-service）、vision-service、edge（Android）
> 审计维度：UI / 按钮功能 / 业务逻辑 / 界面 / 体验 / 性能 / 架构
> 审计时间：2026-09-13
> 阶段前提：项目处于开发阶段，未接入真实硬件与真实支付通道；硬件/支付相关结论以「代码确定性行为 + 待联调」为准
> 本文件是合并 R3 UI 审计与各端抽样审计后的最终版；**已按仓库源码复核修正误报/过时项**，为开发期整改真源（真收费门禁另见 `production-launch-checklist.md`）

---

## 〇、总评（TL;DR）

| 维度 | 评级 | 一句话总结 |
|------|------|----------|
| 总体质量 | **A-** | 工程成熟度高于行业同类项目；防御性编码到位；开发期代码缺陷 P0 = 0 |
| UI / 体验 | **A-** | 第三轮 UI 审计已收口；admin 玻璃态已修；少量一致性问题 |
| 业务逻辑 | **A** | 开门/扣款/订单/纠纷全链路有完整状态机、幂等、预授权、分布式锁 |
| 性能 | **B+** | 列表虚拟化缺位、补货列表 N+1、仓库页 5600+ 行巨型组件 |
| 架构 | **A-** | 跨端抽象合理；后端依赖注入密集；Admin ↔ Merchant ↔ Consumer 协同边界清晰 |
| 安全 | **A** | RBAC fail-closed、CSRF token、内网 API Key、admin Cookie 优先；H5 小程序 token 仍偏本地存储 |
| 隐私合规 | **B+** | 定位/手机号脱敏到位；H5 缺隐私政策首屏弹窗 |

> **P0 口径**：下表「P0 = 0」指**当前开发期 / mock 硬件与支付**下，未发现会导致数据错乱或安全阻断的代码缺陷。真收费上线的业务硬门禁见 [`production-launch-checklist.md`](production-launch-checklist.md)，二者勿混读。

**汇总问题**：P0 = **0**，P1 = **23**，P2 = **54**，合计 **77 条**（详见各端章节；相对原稿已按源码剔除/降级误报）

**最大风险**（上线前必修）：
1. 两端小程序 `manifest.json` `mp-weixin.appid` 为空 → 真机/发布硬阻塞（admin-vue 无此文件）
2. 大列表缺虚拟化 / 补货 N+1 → 1000+ 行或弱网卡顿
3. 后端 SettlementService ~30 依赖 → "上帝类"难维护
4. 跨端数据无乐观锁 → admin/merchant/consumer 并发写覆盖风险
5. H5 端 token 存 localStorage / uni Storage → XSS 暴露面

---

## 一、admin-vue 端（Vue 3 + Element Plus）

> 本节为收口摘要（各端独立 audit 文件未单独落盘，内容已并入本最终版）。

### 1.1 问题清单

#### P0（阻断/数据错乱/安全）
**未发现。** 路由守卫、RBAC、CSRF、XSS 清洗、token 存储、定时器清理、请求竞态均有到位处理。

#### P1（功能缺陷 / 体验严重）

| 编号 | 严重度 | 模块 | 文件:行 | 简述 | 修复建议 | 影响面 |
|------|--------|------|---------|------|---------|--------|
| A-P1-001 | P1 | 路由守卫 / RBAC | `router/index.ts:490-504` + `config/menu.ts` | 权限闸门基于 `findNavByPath`：仅当 `nav?.perm` 存在才拦截；**未登记进 menu 的路由 `perm` 为 undefined → fail-open**；新增路由漏登 menu 即构成权限绕过 | 把 `perm` 写入 `route.meta`，守卫校验 `to.meta.perm`，未匹配且无 meta 默认 **deny** | 全局越权风险 |
| A-P1-002 | P1 | 性能 / 列表 | 全量列表视图（`WarehouseView`、`OrderListView`、`DeviceListView`） | 全程未使用 `el-table-v2`，1000+ 行卡顿 | 引入 `el-table-v2` 或约束后端 `pageSize` 上限 + lazy | 大数据列表交互 |
| A-P1-003 | P1 | 内存泄漏 | `views/reports/DeviceReportView.vue` | `onMounted` 注册 `resize`；虽有 `onUnmounted` 解绑，但 `AdminLayout` 使用 `keep-alive :max="12"`，切页通常**不触发** `onUnmounted`，缺 `onDeactivated` | `onDeactivated` 移除监听（`onActivated` 再绑）或改 `ResizeObserver` + `disconnect` | 报表页 + 同类 keep-alive 视图 |

#### P2（可优化）

| 编号 | 严重度 | 模块 | 文件:行 | 简述 |
|------|--------|------|---------|------|
| A-P2-001 | P2 | 会话 / 401 跳转 | `api/client.ts` vs `AdminLayout.vue` | `loggingOut` 标志依赖布局收尾；非 AdminLayout 入口调用后可能长时间抑制 401 |
| A-P2-002 | P2 | 架构 | `views/warehouse/WarehouseView.vue` | 单文件约 **5600+** 行，承载采购/退货/库存多业务 |
| A-P2-003 | P2 | 启动性能 | `App.vue` + `router/index.ts` | `App.onMounted` 与 `beforeEach` 都可能调 `auth.restore()`，首屏 `/rbac/me/*` 双拉风险 |
| A-P2-004 | P2 | RBAC 防御 | `stores/auth.ts:249-251` | `isNavMenuActive` 在 `!activeNavLoaded` 时返回 true（菜单高亮窗口）；路由守卫另有 fail-closed 路径 |
| A-P2-005 | P2 | API 抽象 | 全部视图 | ~~无集中 endpoint~~ → 试点 `AdminEndpoints`（工作台/趋势/SLA/设备参照等）+ `check:admin-endpoints`；其余业务路径仍待迁 |
| A-P2-006 | P2 | 安全配置 | `api/client.ts` | ~~非 Cookie JWT 落 localStorage~~ → Cookie 优先；dev 仅 `sessionStorage`；生产 cookieEnabled=false fail-closed；`check:admin-token-storage` |
| A-P2-007 | P2 | 残留日志 | 多文件 | 多处 `console.warn/error` 残留（生产仍输出） |
| A-P2-008 | P2 | 无障碍 / i18n | 多视图 | ~~缺 dialog/drawer 命名门禁~~ → 已用 `check:admin-dialog-a11y` + `ResizableDrawer` 强制 title；i18n 框架仍不强制（前台中文约定） |

### 1.2 架构评估

| 维度 | 评估 |
|------|------|
| 模块划分 | 良好。stores / api / views / components / composables / utils / config / directives / plugins 职责清晰 |
| 状态管理 | 良好。Pinia 多 store，`auth` fail-closed，不从 localStorage 初始化权限 |
| API 抽象 | 中等。底层 `ApiClient` 质量高（GET 自动重试、401 refreshPromise 去重、统一 envelope），但业务层缺 endpoint + 类型 |
| RBAC | 良好但有耦合（A-P1-001）。`v-hasPermi` 实现扎实：`display:none` + `disabled` + `pointer-events:none` + `aria-hidden` + `tabindex:-1` |
| 路由 | 良好。`beforeEach` 三段（登录态/权限/错误页豁免）齐备；`resolveHomePath` 防死循环；`afterEach` 聚焦 `#main-content` |
| 内存/竞态 | 良好。`createLoadSeq` 覆盖约 45 个视图；`DeviceReportView` 在 keep-alive 下需补 `onDeactivated` |

### 1.3 admin-vue 小结

- **问题总数**：P0: 0，P1: 3，P2: 8（合计 11）
- **核心建议**：A-P1-001 perm 收归 meta 并 fail-closed；A-P1-002/A-P1-003 虚拟化 + keep-alive 监听清理；A-P2-005 建立共享 endpoint + 类型层

---

## 二、consumer-mp 端（uni-app + Vue 3 + TS，MP + H5）

### 2.1 问题清单

#### P0（阻断/崩溃级）
**未发现。** 全量页面均有 loading/empty/error 三态，关键异步均有 guard（`opening`/`closingDoor`/`finishingSession`/`pollInFlight`），未发现空指针、未捕获 Promise、越界渲染。

#### P1（发布阻塞 / 安全 / 核心链路边界）

| 编号 | 严重度 | 模块 | 文件:行 | 简述 | 修复建议 | 影响面 |
|------|--------|------|---------|------|---------|--------|
| C-P1-1 | 高 | 发布配置 | `clients/consumer-mp/src/manifest.json:15` | `mp-weixin.appid` 为空字符串 `""`（根级 `appid` 为 uni 占位，不等于微信 AppID） | 填入真实微信 AppID（或测试号） | 真机/发布 |
| C-P1-2 | 高 | 安全 | `packages/shared-uni` + `consumer-api.ts` | H5 / 小程序 token 存 `uni.setStorageSync('consumer_token')`（H5 底层常映射 localStorage），可被 XSS 读取冒用 | H5 优先 httpOnly Cookie（与 admin 对齐）；MP 维持 Storage 但缩短 TTL + 刷新单飞 | H5 全链路 |
| C-P1-3 | 高 | 资金安全 | `recharge.vue` + `PaymentService` / `BalanceRefundService` | 前端对充值/退余额有 `>¥5000` 校验；**充值预下单后端未见同款金额上限**；退余额后端校验余额/渠道可退但**无与前端一致的单次 ¥5000 硬顶**（订单自助退款另有 `REFUND_SELF_MAX_CENTS`，默认 5000**分**=¥50，勿混淆） | 后端为充值/余额退款增加可配置上限并与前端文案对齐 | 充值/退余额 |
| C-P1-4 | 高 | 核心链路 | `index.vue` | live 模式缺「关门后未出账单 / 联系运营」兜底；状态推进依赖硬件 webhook | 增加手动查询/联系运营入口 | 真实开门场景 |
| C-P1-5 | 高 | 链路边界 | `index.vue` `demoCloseSession` | 联调闭环依赖 mock/demo 关门路径；无真实视觉/硬件时无法 live 闭环 | 文档化「模拟支付 vs 占位」边界；live 闭环须视觉 + 硬件信令 | 全链路结账 |

#### P2（中优先级）

| 编号 | 严重度 | 模块 | 文件:行 | 简述 |
|------|--------|------|---------|------|
| C-P2-1 | 中 | 性能/资源 | `index.vue:856-860` `onHide` | 切 tab 仅 `stopDevicePoll` + `showTabBar`，**未停止 `pollTimer`/`recognitionTimer`**；后台每 2s 仍 `getSession` |
| C-P2-2 | 中 | UI/UX | coupons / dispute / index / messages | ~~成功态误用 showError~~ → 复制/刷新成功改 `showSuccess`（剩余真实错误仍用 showError） |
| C-P2-3 | 中 | 性能 | `src/pages.json` | 24 个页面全部主包、无 `subPackages` |
| C-P2-4 | 低 | 架构一致性 | `recharge.vue` | ~~裸 `get('/api/v2/payment/recharges')`~~ → `consumerApi.listRecharges` |
| C-P2-5 | 中 | 体验 | `nearby.vue:98-99,160-163` | 定位失败静默回退到**硬编码上海坐标** `(31.2304,121.4737)`；未授权展示异地柜机 |
| C-P2-6 | 低 | 安全配置 | `manifest.json:18` `urlCheck:false` | 生产构建应开启 `urlCheck` |
| C-P2-7 | 低 | 健壮性 | `index.vue onShow` | ~~无重入锁~~ → `showSeq` 丢弃过期 onShow |
| C-P2-8 | 中 | 业务逻辑 | `messages.vue` vs `coupons.vue` | ~~误以为应 claimCampaign~~ → COUPON 消息 bizId=已持有券；点击设优先券 + 跳转 UNUSED 券包置顶高亮（营销领券走 CAMPAIGN/`claimCampaign`） |
| C-P2-9 | 低 | 性能 | `messages.vue` | ~~每次 onShow 拉 50 单算 pending~~ → `GET /orders/pending-count` |
| C-P2-10 | 低 | 竞态 | `index.vue finishSession` | ~~DISPUTED 600ms 定时器与 onShow 竞态~~ → 导航在 `finishingSession` 内完成；onShow 遇 finishing 直接 return |

### 2.2 核心业务链路

| 链路 | 评估 |
|------|------|
| 扫码开门 → 取货 → 扣款 | **模拟环境端到端可跑通**；设备态/预授权/幂等/轮询/异常分支实现扎实；真实硬件/视觉识别闭环尚未接入，属开发阶段预期 |
| 订单生命周期 | `orders` → `order-detail`（loading/empty/error 完备、`bootstrapPromise` 合并 onLoad+onShow、`hashchange` 监听增删对称）；申诉/退款/部分退款校验完整 |
| 优惠券 / 会员 / 消息 | 优惠券**领取入口缺失**（C-P2-8）；会员 profile+count+coupons 并行拉取完备；消息 `goByBiz` 路由分发严谨 |
| 纠纷（申诉/退款/证据） | `dispute-form`、`dispute-copy`、`dispute-evidence`（`fetchEvidenceLocalPath` **下载失败不把 token 拼进 URL，防泄露**）实现质量高 |

### 2.3 跨端兼容

| 维度 | 现状 | 评价 |
|------|------|------|
| 扫码 | `wx.scanCode`（MP）/ 失败回退手输（H5） | 合理 |
| 支付 | 微信仅 MP（`requestPayment`）、支付宝仅 H5 | 渠道隔离正确 |
| 导航 | `uni.openLocation`（MP）/ `window.open(amap)`（H5） | OK |
| 订阅消息 | `requestSubscribeMessage` 仅微信；H5 静默跳过 | OK |
| 登录态 | `wx.login`（MP）/ 公众号 OAuth / 支付宝回跳（H5） | 分端处理；`refreshTokenSilently` 单飞 |
| 隐私/地理授权 | `nearby` 用 `getLocation`，失败静默回退 | **C-P2-5 回退到硬编码坐标需改** |

### 2.4 consumer-mp 小结

- **问题总数**：P0: 0，P1: 5，P2: 10（合计 15）
- **核心链路小结**：扫码开门→取货→扣款在模拟环境端到端可跑通；真实硬件/视觉识别闭环尚未接入
- **发布卡点**：C-P1-1 `mp-weixin.appid` 为空是消费者端真机/发布硬阻塞（商户端同类见 M-P2-3）
- **核心建议**：补 H5 token 存储 + 充值/退余额后端上限 + live 关门兜底 + `showError` 误用修正 + 24 页分包

---

## 三、merchant-mp 端（uni-app + Vue 3 + TS，MP + H5）

### 3.1 问题清单

#### P0（阻断 / 严重安全）
**未发现。** 权限与资金写操作均由服务端 `me` 校验兜底。

#### P1（性能 / 跨端 / 一致性）

| 编号 | 严重度 | 模块 | 文件:行 | 简述 | 修复建议 | 影响面 |
|------|--------|------|---------|------|---------|--------|
| M-P1-1 | 高 | `replenishment.vue` | `refreshEvidenceCounts` / `refreshLineSummaries` | 列表进入时对每个任务（最多 40）并发调 `listReplenishmentEvidence` + `replenishmentTaskLines`，一次 `onShow` **额外最多 80 个请求** | 改为服务端聚合接口（任务列表直接带 evidenceCount/lineSummary）；或仅对可见任务懒加载 | 冷启动/弱网卡顿 |
| M-P1-2 | 高 | `wallet.vue` ↔ `line-wallet.vue` | - | 两页几乎完全重复（约 95% 同源代码）；缺陷修复需改两处 | 抽 `WalletPage` 组件，以 `role: 'merchant' \| 'line'` + 对应 api 入参复用 | 维护成本 |
| M-P1-3 | 高 | `replenishment.vue` | - | 单文件约 **3000+ 行 / ~95KB**「上帝组件」（列表 + sheet + 步骤流 + 弹窗 + 扫码 + 定位 + 开门缓存） | 拆分 `ReplenishDetailSheet` / `ConfirmDialog` / `StepFlow` / `DoorState` 子组件 | 维护风险 |
| M-P1-4 | 高 | 多页弹窗 | `replenishment`、`team`、`pricing` | 各页自建 bottom-sheet 与确认框，交互/样式不统一；可访问性参差 | 抽统一 `AppDialog` / `AppSheet` 组件 | 体验一致性 |
| M-P1-5 | 高 | 跨端数据一致性 | admin-vue ↔ merchant / consumer | 同一柜机可能 admin 改价、merchant 补货确认、消费者购物会话并发写；API 层未见乐观锁/版本号 | 服务端对 device/slot/price/pricing 写操作引入 `version`/`updatedAt`；前端带 `etag` 重试 | 高并发数据覆盖 |

#### P2（中优先级）

| 编号 | 严重度 | 模块 | 简述 |
|------|--------|------|------|
| M-P2-1 | 中 | `video.vue` `copyUrl` | 复制成功用 `showError('视频链接已复制')`，把成功/中性信息用错误 toast 表达 |
| M-P2-2 | 中 | `messages.vue` → `splits.vue` | ~~深链 orderId 未读~~ → `onLoad` 读 orderId 置顶高亮；失败 Tab 未命中回退全部 |
| M-P2-3 | 中 | `manifest.json` | `mp-weixin.appid` 空、`urlCheck:false`（与 consumer 同类发布配置问题） |
| M-P2-4 | 中 | 隐私合规 | 定位采集仅依赖微信授权弹窗；H5 无隐私政策/首次同意弹窗 |
| M-P2-5 | 中 | `useMerchantMe` 模块单例 | ~~登出不清内存态~~ → `clearMerchantMe` + `registerMerchantSessionClearHook`，App 启动注册 |
| M-P2-6 | 中 | `splits.vue` | ~~一次拉 100 无分页~~ → `PAGE_SIZE=20` + 加载更多 / onReachBottom；深链仍扫描定位 |
| M-P2-7 | 中 | `replenishment.vue` 模板 | ~~`hero-orb` 死装饰~~ → 已删除模板节点与 `display:none` 样式 |
| M-P2-8 | 中 | `merchant-api.ts` | ~~`@deprecated` 类型别名残留~~ → 调用方改 `shared-types`；仅保留争议视图投影类型 |
| M-P2-9 | 中 | `business.vue` `load` | 列表加载额外拉 `merchantApi.pricing()` 仅为取 SKU 缩略图映射 |
| M-P2-10 | 中 | 多页 | 页内自建 `.retry` 按钮样式与 `error-state` 组件重叠 |
| M-P2-11 | 中 | `merchant-api.ts` | `merchantApi` 聚合对象内大量重复 try/catch + `.catch(()=>[])` 兜底 |
| M-P2-12 | 中 | `confirmEvidenceIfNeeded` | ~~拍完仍 return false~~ → await 上传后有凭证则继续完成 |
| M-P2-13 | 低 | `home.vue` | 工作台页聚合 KPI/扫码/公告/快捷入口/营收趋势，体量偏大 |
| M-P2-14 | 中 | `mine.vue` `onBindWx` | 源码经 `wxLoginCode()`（`packages/shared-uni/src/notify.ts`）用 `uni.login`，H5 **不会**抛 `wx is undefined`，而是 reject「仅微信小程序可绑定提醒」；入口仍展示，体验不佳 | 建议 H5 隐藏绑定入口 |

### 3.2 核心业务链路

| 链路 | 评估 |
|------|------|
| 补货履约 | `扫码到柜(onScan)` → `assertReplenishmentDeviceAccess` → `openTask` → **签到** → **开门**（二次确认） → **核对清单**（容量校验/自动调低） → **现场凭证**（最多 5 张） → **确认完成**；定位生产不可跳过 ✅ |
| 柜机管理 | 列表/搜索/在线离线/常驻★ → 详情（设置编辑、货道 parLevel、温度历史、动销）；`canEditSlots = canEditPlanogramForMerchant`；无阻断 |
| 订单 / 营收 / 对账 | 多条件筛选 + 导出 + 视频（`canShowVideo`） + 关联争议；金额一律服务端 `refundCents` 为准 |
| 纠纷处理 | `disputes`（OPEN/RESOLVED/CLOSED + 分页）→ 底部抽屉详情 → 认领/回复/结案（WAIVE/KEEP/CONFIRM） + 视频 captions |
| 打卡（签到）隐私 | `obtainCheckInLocation` 仅服务端 `requireReplenishmentCheckInLocation=true` 时采集；跳过开关生产强制关闭；坐标随签到上报、文案明示用途 ✅ |
| 待办（alerts） | `mergeTodoItems` 合并三源、去重（EXPIRY 重复、DEVICE_OFFLINE+FAULT 合并）；KPI 网格 + 货道差异 + `resolveInventoryException` 需填说明 |

### 3.3 与 admin-vue / consumer-mp 协同

| 端 | 关系 | 待明确 |
|----|------|--------|
| admin-vue | admin 配置 → merchant 执行；商家**不能**反向配置平台级参数（仅 `canEdit*` 等少数开关） | 原 M-P1-6 / 现 M-P1-5：admin 与 merchant 对同一柜机/货道/价格的并发写需乐观锁 |
| consumer-mp | 共享主键：`orderId`、`deviceId`、`skuId`；购物视频与争议为两端共同对象；商家补货开门走"补货会话"**不按购物扣款**（防误扣） | 服务端补货会话与消费购物会话在硬件层互斥 |

### 3.4 merchant-mp 小结

- **问题总数**：P0: 0，P1: 5，P2: 14（合计 19）
- **正向亮点**：权限三重保障（strip + 服务端 me + nav guard）；跨端降级完整；竞态防护普遍；提现幂等号 + 定位生产不可跳过
- **核心建议**：补货列表聚合接口 → 钱包合并 → replenishment 拆分 → 统一弹窗 → H5 隐藏微信绑定 → 乐观锁

---

## 四、services 后端（trade-service + device-service）

### 4.1 架构总览

| 维度 | 现状 |
|------|------|
| 服务划分 | trade-service（8080）+ device-service（8081）+ vision-service（8082） + edge |
| 数据存储 | PostgreSQL（Flyway 迁移，advisory lock 多实例串行）+ Redis |
| 消息队列 | Kafka（`trade-service` group） + MQTT（柜机指令） |
| 缓存 | Redis（`RedisTemplate`） |
| 鉴权 | JWT（`JWT_SECRET`，默认 `expiration-seconds:1800`）+ HttpOnly Cookie 优先（`AUTH_COOKIE_ENABLED:true`）+ internal API Key |
| ORM | MyBatis-Plus + `id-type: input` |
| 微服务通信 | RestTemplate / WebClient（trade ↔ device, trade ↔ vision） |

### 4.2 问题清单

#### P0（阻断/数据错乱/安全）
**未发现严重 P0。** SessionService/SettlementService 的状态机、分布式锁、幂等、预授权、MQTT 开门解耦均有到位处理。

#### P1（功能缺陷 / 体验严重）

| 编号 | 严重度 | 模块 | 文件:行 | 简述 | 修复建议 | 影响面 |
|------|--------|------|---------|------|---------|--------|
| S-P1-1 | 高 | `SettlementService`（`service/SettlementService.java`） | - | 「上帝类」：约 **30** 个 `private final` 依赖（含 `self` / ObjectProvider 等） | 拆分结算流程（订单生成 / 支付 / 库存 / 视频归档 / 通知）为独立的 `@Service` 类 | 维护成本、回归风险 |
| S-P1-2 | 高 | `SessionService`（`service/SessionService.java`） | - | 大量 `@Transactional` 方法集中在单一类；事务边界由 service 层决定，跨 service 调用易破坏 ACID | 引入 saga / outbox pattern；事务粒度下沉到 repository 层 | 分布式事务一致性 |
| S-P1-3 | 高 | `SettlementService` / `SessionService` | - | 大量超时/过期常量（如 `OPENING_EXPIRE_SECONDS`、`RESTOCK_SHOPPING_EXPIRE_MINUTES` 等）散落 | 收归 `SessionConstants` / `application.yml` 配置化（外部可调） | 调参成本、可测试性 |
| S-P1-4 | 高 | `payment` 模块 | - | 微信支付 / 支付宝存在 mock / DevMock controller 路径；真实接入前的接口契约需冻结 | 联调前冻结 `PaymentController` 等对外契约；mock 与真实实现并存但路由隔离 | 联调延期 |
| S-P1-5 | 高 | `AdminDeviceOpsService` / `SessionService` | - | 多处设备开门指令与状态机推进未做「乐观锁 + version 字段」；admin / merchant / consumer 三端并发写同柜机可能覆盖 | 引入 `@Version` + `updatedAt`；写操作带 `If-Match` / ETag | 数据覆盖 |

#### P2（可优化）

| 编号 | 严重度 | 模块 | 简述 |
|------|--------|------|------|
| S-P2-1 | 中 | `SessionState` / `SessionService` | `SessionState.canTransitionTo` **已在 domain 枚举**；service 层仍可能散落额外判断，需统一只走 `canTransitionTo` |
| S-P2-2 | 中 | `config/GlobalExceptionHandler.java` | 通用 `Exception.class` 处理器仅记 log.error；缺少 traceId、用户上下文、链路追踪 |
| S-P2-3 | 中 | `mapper/**.xml` | ~~假分页 / `findAll` / 无界列表~~ → 已修分账、线长、用户行为聚合、温湿度/争议等 LIMIT；对账日窗仍按日全量（故意） |
| S-P2-4 | 中 | 多 Service | ~~缓存 name/TTL 散落~~ → 已建 `CacheNames` + 仪表盘入口统一；业务侧禁止裸前缀/裸毫秒 |
| S-P2-5 | 中 | `VisionRecognitionListener.java` | Kafka 消费失败重试策略未显式（DLT topic？指数退避？） |
| S-P2-6 | 中 | `service/DisputeService.java` | 纠纷状态机（OPEN → RESOLVED → CLOSED）转移缺单元测试覆盖 |
| S-P2-7 | 中 | `ScheduledTaskXxlJobHandler` | ~~时区/cron 散落~~ → `ScheduleZones` + `aicabinet.schedule.zone`；缺 zone 的 `@Scheduled(cron)` 已补；XXL 种子 cron 表对齐 |
| S-P2-8 | 中 | `kafka` 配置 | `auto-offset-reset: earliest` + 无显式 max.poll.records；高吞吐下有 rebalance 风险 |
| S-P2-9 | 中 | 日志 | 部分关键 Service 仅 `log.info`，缺结构化字段（sessionId、userId、deviceId） |
| S-P2-10 | 中 | API 版本 | ~~无版本策略~~ → `ApiVersions` + `ApiVersionInterceptor`（响应 `X-Api-Version`；未支持主版本 410）；shared-api 导出 `API_VERSION` |
| S-P2-11 | 中 | `ProductionStartupValidator` | **已实现** prod/staging 拒绝默认 JWT/INTERNAL/VISION key、mock 支付等；残余风险是误用非严格 profile 或漏挂该 Bean 的环境 | 部署门禁必须强制 `prod`/`staging` profile |

### 4.3 核心业务链路：开门 → 识别 → 扣款 → 订单 → 结算

| 阶段 | 关键类 | 关键保障 |
|------|--------|----------|
| 1. 扫码开门 | `SessionController#createSession` → `SessionService#createSession` | 幂等键 `idempotencyKey`（先 `saveAndFlush` 唯一约束防并发开门）+ 分布式锁 `runWithDeviceOpenLock` + 风控 `apiRateLimitService.assertOpenDoorAllowed` |
| 2. MQTT 开门 | `DeviceServiceClient#requestOpenDoor` → `device-service` → edge `MqttDeviceClient` | 开门指令下发失败 → `markOpenDoorFailed` + 释放预授权 `consumerPreauthService.releaseIfFrozen` |
| 3. 取货识别（live） | `kafka_worker` 消费 `cabinet/{deviceId}/evt` → `VisionRecognitionListener` → `SettlementService#processRecognitionResult` | Kafka 异步 + 识别置信度不足自动转入 `DISPUTED` |
| 4. 取货识别（mock/dev） | `SessionService#completeDevUploadRecognition` | `runWithSessionLifeLock` 防并发结算 |
| 5. 预授权冻结 | `ConsumerPreauthService#freezeForOpen` | 开门前冻结 `preauth-cents: 2000`，按 `passwordFree` 决定免密/实名 |
| 6. 结算扣款 | `SettlementService#settleOrder` → `OrderPaymentService` | 余额扣款 + 优惠券核销 + 积分 + 退款分账 `revenueSplitService` |
| 7. 状态机 | CREATED → OPENING → SHOPPING → RECOGNIZING → SETTLING → COMPLETED/DISPUTED/FAILED | `EnumSet<SessionState> ACTIVE_STATES` + `canTransitionTo` 强制合法转移 |
| 8. 异步事件 | `DomainEventPublisher` + Kafka + 视频归档 `VideoArchiveService` | 视频异步上传、通知 `NotificationDispatchProducer` |

**链路评估**：**模拟环境端到端可跑通；真实硬件/视觉识别闭环需在联调阶段接入。** 状态机、幂等、分布式锁、风控限流、预授权、MQTT 解耦均有到位设计。

### 4.4 后端架构评估

| 维度 | 评估 |
|------|------|
| 模块划分 | 中等。`api / service / mapper / domain / config / messaging / metrics / event` 分层清晰；但 service 层「上帝类」（SettlementService ~30 依赖）需拆分 |
| 状态管理 | 不适用（无状态服务） |
| API 抽象 | 中等。`ApiResponse<T>` envelope + `ApiMessages.translate()` 错误码国际化 + `GlobalExceptionHandler` 全局异常处理（11 类异常映射到 400/404/405/409/412/500） |
| RBAC | 良好。`@PreAuthorize` + JWT + `LoginFailureService` 登录失败锁定（`login-max-failures:5`，`login-lock-minutes:10`） |
| 路由 | 良好。`SessionInternalController` / `VisionInternalController` 等内部接口独立路由，前缀隔离 |
| 内存/竞态 | 良好。`DistributedLockService`（Redisson）+ `idempotencyKey` 唯一约束 + `runWith*Lock` 模板 |
| 可观测性 | 中等。结构化日志（SLF4J）+ `CabinetMetrics`（Micrometer）；缺 traceId 全链路 |

### 4.5 与三端 API 对齐

| 端 | 关键接口 | 一致性 |
|----|----------|--------|
| admin-vue | `/api/v2/rbac/me/*`、`/api/v2/dicts/runtime`、`/api/v2/ops/*` | ✅ |
| consumer-mp | `/api/v2/consumer/*`（account、session、order、dispute、coupon、member、notification、marketing） | ✅ 集中收敛 |
| merchant-mp | `/api/v2/merchant/*` | ✅ |
| 三端公共 | `/api/v2/auth/*`、`/api/v2/payment/*`（mock）、`/api/v2/system/*` | ⚠️ 支付仍为 mock controller，真实接入需冻结契约 |

### 4.6 后端小结

- **问题总数**：P0: 0，P1: 5，P2: 11（合计 16）
- **核心建议**：S-P1-1/2 拆分上帝类与事务下沉；S-P1-3 魔法值配置化；S-P1-4 冻结支付契约；S-P1-5 乐观锁；部署强制严格 profile（S-P2-11）

---

## 五、vision-service（FastAPI）

### 5.1 架构总览

| 维度 | 现状 |
|------|------|
| 框架 | FastAPI 0.9.0 |
| 识别后端 | `mock`（默认）/ `deepseek` / `quectel`（可插拔工厂 `factory.py`） |
| 通信 | Kafka 消费柜机事件 + HTTP API 接收上传 |
| 鉴权 | `X-Internal-Api-Key` 中间件（`hmac.compare_digest` 常时比较） |
| 存储 | `app/storage.py`（MinIO / S3 兼容） |
| 生产安全 | `MOCK_ENABLED=true` + `_IS_PROD` → 启动失败；`VISION_API_KEY` 默认值 → 启动失败 |
| 上传上限 | `VISION_UPLOAD_MAX_BYTES=20MB`（可配置） |

### 5.2 问题清单

#### P0（阻断/严重安全）
**未发现。** 内部 API Key 鉴权、生产模式强制校验已到位。

#### P1（功能缺陷 / 体验严重）

| 编号 | 严重度 | 模块 | 文件:行 | 简述 | 修复建议 | 影响面 |
|------|--------|------|---------|------|---------|--------|
| V-P1-1 | 高 | `app/main.py` L42-52 | - | `MOCK_ENABLED=false` 且 `RECOGNIZER_BACKEND=mock` 时仅打 `log.warning`，不强制退出；生产误配置会导致 cloud 返回 `need_review` | 启动时强制：`MOCK_ENABLED=false` 且 `available=false` 时 `raise RuntimeError`（与 API_KEY 默认值一致） | 生产识别失效 |
| V-P1-2 | 高 | `app/recognition/factory.py` | - | 识别后端无健康检查；`quectel_recognizer` 云端超时无 fallback → 全柜机识别失败 | 加熔断 + 退避；超时回退 mock 标记 `need_review=true` | 全链路识别 |
| V-P1-3 | 高 | `app/kafka_worker.py` | - | Kafka 消费失败无显式重试 / DLT topic；下游依赖识别结果的事件会丢失 | 配置 `max.poll.records`、`enable.auto.commit=false`、显式 ack + 失败路由到 `vision-recognition.DLT` | 事件丢失 |

#### P2（可优化）

| 编号 | 严重度 | 模块 | 简述 |
|------|--------|------|------|
| V-P2-1 | 中 | `app/recognition/deepseek_recognizer.py` | 第三方 SDK 调用无超时配置；冷启动阻塞主线程 |
| V-P2-2 | 中 | `app/storage.py` | 上传对象无 lifecycle 策略；过期图片/视频无限增长 |
| V-P2-3 | 中 | `app/recognition/types.py` | 识别结果缺 `traceId` / `sessionId` 透传字段 |
| V-P2-4 | 中 | `app/main.py` | FastAPI `docs_url=None` 仅 prod 关闭；staging 应保留 `redoc_url` 便于排查 |
| V-P2-5 | 中 | `tests/test_mock_recognizer.py` | 单元测试覆盖 < 30%（未覆盖 fusion、frame_extract、deepseek_recognizer） |

### 5.3 vision-service 小结

- **问题总数**：P0: 0，P1: 3，P2: 5（合计 8）
- **正向亮点**：生产模式强制校验（`MOCK_ENABLED` / `VISION_API_KEY`）、HMAC 常时比较、可插拔后端工厂
- **核心建议**：V-P1-1 启动强制识别后端可用；V-P1-2 熔断 + fallback；V-P1-3 Kafka DLT + 显式 ack

---

## 六、edge（Android Kotlin）

### 6.1 架构总览

| 维度 | 现状 |
|------|------|
| 平台 | Android（`com.aicabinet.edge`），MinIO 视频上传 |
| 通信 | MQTT（Eclipse Paho）+ Kafka Producer（直连 broker？） + HTTP（MinIO 上传、trade-service 上报） |
| HAL | `ILockDriver` 接口 + `MockLockDriver`（开发） + `ChzhLockDriver`（真实硬件）/ `ChzhSerialPort`（串口） |
| 状态 | `DeviceStatusHub`（单例，发布 MQTT 连接状态） |
| 视频 | `SessionVideoRecorder` + `MinioUploader` + `OfflineUploadQueue`（离线重试） |
| MQTT 队列 | `OutboundMqttQueue`（SharedPreferences 持久化，QoS 1，重试 200 次，队列上限 500） |
| OTA | `OtaChecker` |

### 6.2 问题清单

#### P0（阻断/严重安全）
**未发现。** MQTT 持久化队列 + 自动重连 + 心跳均有到位处理。

#### P1（功能缺陷 / 体验严重）

| 编号 | 严重度 | 模块 | 文件:行 | 简述 | 修复建议 | 影响面 |
|------|--------|------|---------|------|---------|--------|
| E-P1-1 | 高 | `mqtt/MqttDeviceClient.kt` | - | `MqttConnectOptions` 未设置 TLS / `userName` / `password` / 证书；生产 broker 通常要求认证 | 配置 TLS + 设备证书；显式 userName/password | 设备认证 |
| E-P1-2 | 高 | `upload/OfflineUploadQueue.kt` vs `OutboundMqttQueue.kt` | - | 离线视频上传队列与 MQTT 出站队列双队列设计，失败重试上限 / 存储策略不统一（SharedPreferences） | 抽象统一 `OutboundQueue<T>` + 统一存储策略（SQLite 等） | 可靠性 |

#### P2（可优化）

| 编号 | 严重度 | 模块 | 简述 |
|------|--------|------|------|
| E-P2-1 | 中 | `service/CabinetService.kt` | 长时间运行 Service 未使用 Foreground Service；Android 8+ 后台被杀风险 |
| E-P2-2 | 中 | `hal/DoorCloseWatcher.kt` | 关门检测仅依赖 GPIO 中断，无兜底轮询；硬件故障无信号 |
| E-P2-3 | 中 | `video/SessionVideoRecorder.kt` | 视频分段硬编码 30s；无动态调整 |
| E-P2-4 | 中 | `config/EdgeRuntimeConfig.kt` | 设备 ID 从 SharedPreferences 取，首次启动未设备注册 |
| E-P2-5 | 中 | `mqtt/OutboundMqttQueue.kt` | MAX_ITEMS=500 / MAX_ATTEMPTS=200 硬编码；满后丢最早 → 关键开门事件可能丢失 |
| E-P2-6 | 中 | `upload/MinioUploader.kt` | MinIO endpoint 从配置读，无断路器；MinIO 长时间不可用会耗尽队列 |

### 6.3 edge 小结

- **问题总数**：P0: 0，P1: 2，P2: 6（合计 8）
- **正向亮点**：MQTT 自动重连 + 持久化队列；`publish`/`publishNow` 失败已入队 `OutboundMqttQueue`（原稿「publish 失败无入队」为误报，已剔除）；HAL 可插拔；离线视频上传队列
- **核心建议**：E-P1-1 设备证书 + TLS；E-P1-2 统一队列抽象

---

## 七、跨端协同与系统性议题

### 7.1 三端 ↔ 后端 ↔ 视觉 ↔ 边缘端 端到端链路

```
┌─────────────┐ 扫码  ┌─────────────┐ HTTP  ┌────────────────┐
│ consumer-mp │ ────► │ trade-service│ ────► │ device-service │
└─────────────┘        │ (SessionCtrl)│       └────────────────┘
                       │              │ MQTT         │
                       │              │ ──────────►  │ ┌──────────┐
                       │              │              │ │   edge   │
                       │              │              │ │ (Android)│
                       │              │ ◄────────────│ └──────────┘
                       │              │  DoorEvent   │     │
                       │              │              │     │ 摄像头
                       │              │              │     ▼
                       │              │ Kafka    ┌────────────────┐
                       │              │ ───────► │ vision-service │
                       │              │          └────────────────┘
                       │              │               │
                       │              │ ◄─────────────┘ RecognitionResult
                       │              │ Kafka
                       │              │
                       ▼              ▼
                  ┌────────────────────────┐
                  │   PostgreSQL + Redis    │
                  └────────────────────────┘
```

**链路完整性**：模拟环境端到端可跑通（trade ↔ device mock ↔ vision mock）；真实硬件/视觉/支付需联调接入。

### 7.2 跨端一致性问题（系统性）

| 议题 | 描述 | 修复建议 |
|------|------|----------|
| 并发写覆盖 | admin 改价、merchant 补货确认、consumer 购物会话并发写同柜机/价格/库存 | 服务端引入 `@Version` 乐观锁；前端写操作带 ETag |
| 字段一致性 | `SessionDto.state`、`DeviceStatus.preauthCents` 等枚举值三端需锁版本 | 生成共享 TypeScript/Java enum 文件（`@aicabinet/shared-types`） |
| 错误码一致性 | 后端 `ApiMessages` ↔ 三端错误处理映射 | 在 `shared-types` 定义 `ErrorCode` 常量 |
| 时区一致性 | 多端时间展示未统一（UTC ↔ Asia/Shanghai） | API 统一返回 ISO-8601 + timezone；前端统一 `Intl.DateTimeFormat` |
| 幂等键 | consumer-mp/merchant-mp 部分写操作未传 `idempotencyKey` | 关键写操作强制 `Idempotency-Key` header |

### 7.3 上线前必须完成项（Checklist）

> 此处为**代码审计视角**的硬阻塞（与 [`production-launch-checklist.md`](production-launch-checklist.md) 的真机/真支付/真视觉门禁互补）。

- [ ] **两端小程序 `manifest.json` 填入真实 `mp-weixin.appid`**（C-P1-1 / M-P2-3；admin-vue 无此文件）
- [ ] **两端小程序生产构建开启 `urlCheck`**（C-P2-6 / M-P2-3）
- [x] **部署强制 `prod`/`staging` profile**：`docker-compose.production.yml` / `staging.yml` 固定 `SPRING_PROFILES_ACTIVE`；`ProductionStartupValidator` 拒绝默认密钥；`scripts/check-env.ps1` 门禁（S-P2-11）
- [x] **支付接口契约冻结**：mock / DevMock 与真实实现路由隔离；`PaymentController` 对外契约不再随意变更（S-P1-4）— mock 迁至 `/api/v2/dev/payment/**`
- [x] **H5 token 存储改 httpOnly Cookie**（C-P1-2；MP 可维持 Storage）— consumer H5 `cookieEnabled` 不落 JWT + `withCredentials`
- [x] **充值/退余额后端可配置金额上限**（与前端 ¥5000 对齐；C-P1-3）— recharge.max_cents / balance.refund.max_cents
- [x] **live 模式「关门结算」兜底入口**（C-P1-4）— 刷新状态 + 未出账单/客服
- [x] **文档化「模拟支付 vs 占位」边界**（C-P1-5）— live 兜底文案已区分 demo-close
- [x] **vision-service 强制识别后端可用检查**（V-P1-1）— mock 关闭且 recognizer 不可用时启动失败
- [x] **Kafka DLT topic + 显式 ack**（V-P1-3 / S-P2-5）— vision request DLT + trade result DLT
- [x] **edge MQTT TLS/账号配置化**（E-P1-1）— BuildConfig/Prefs 可选 TLS+user/pass；设备证书仍待现场签发
- [x] **Session 超时配置化**（S-P1-3）— `aicabinet.session-expire`
- [x] **vision 识别超时 need_review fallback**（V-P1-2）— HTTP/Kafka `RECOGNIZE_TIMEOUT_MS`

> 已同步落地：admin 路由 fail-closed、DeviceReportView keep-alive 解绑、consumer onHide 停轮询、nearby 定位失败空态、merchant H5 隐藏微信绑定、生产构建校验 appid/urlCheck。

### 7.4 上线后必须跟进项

- [x] A-P1-001 路由守卫 fail-closed（未知路径 deny；明细仍靠 findNavByPath）
- [x] A-P1-002 / A-P1-003：pageSize 上限收紧至 50 + `check:admin-page-size` 门禁；订单/设备列表接入 `ADMIN_LIST_PAGE_SIZES`；设备运维仍用标准 `el-table`（与其它列表同款多选/表头/拖列宽；`AdminVirtualTable` 组件保留备选）；A-P1-003 keep-alive 已修
- [x] A-P2-001：`logoutSession` 内统一 `beginLogout` + 2.5s 后 `endLogout`；不依赖 AdminLayout 收尾
- [x] A-P2-003：去掉 App/`AdminLayout` 首屏重复 RBAC；仅 `router.beforeEach → restore`（含 inflight 去重）；窗口 focus 仍可 refresh
- [x] A-P2-007：软路径 `console.warn/error` 改 `adminDevWarn/Error`（仅 DEV）；生产仍保留 cookie 误配置告警
- [x] A-P2-004：`isNavMenuActive` 在 ACTIVE 菜单未加载时 fail-closed（`isNavMenuActiveFor`）；避免停用菜单首屏闪现
- [x] S-P2-2：`GlobalExceptionHandler` 通用异常日志带 `traceId/spanId/sessionId`；500 响应 `X-Trace-Id` + 短追踪号文案
- [x] S-P2-9（试点）：`SessionLogContext` 统一 sessionId/deviceId/userId；`SessionDoorService` 门事件日志已接入
- [x] S-P2-9（扩展）：`SessionSettleService` / `SessionExpireService` 关键日志接入 `SessionLogContext`
- [x] S-P2-6：`DisputeTicketTransitions` 收口 OPEN→RESOLVED→CLOSED（含重开）；单测覆盖；`DisputeService` 守卫改走状态机
- [x] S-P2-1：扩展 `SessionState.canTransitionTo`（超时/运维/重试/申诉边）；已有实体写路径统一 `SessionService.transition`（新建赋初态除外）
- [x] S-P2-8：trade `max-poll-records` + poll/session 超时显式化；vision worker 同步 `max_poll_records` / interval / session（环境变量可调）
- [x] A-P2-008（门禁）：`ResizableDrawer` 强制 `title`→`aria-label`；`check:admin-dialog-a11y` 校验 dialog/drawer 命名；全局搜索 dialog 补 `aria-label`（i18n 框架仍按项目约定前台中文，不强制）
- [x] S-P2-3（首批）：`OrderRevenueSplitMapper.searchByMerchants` 改为 MyBatis-Plus `selectPage`（去掉全表+subList）；线长日佣改为 `findByDeviceIdAndCreatedAtBetween`（其余大表窗口扫描仍待跟进）
- [x] S-P2-3（续）：用户行为分析改 `aggregatePaidOrdersByUser`（GROUP BY），禁止 `cabinet_order.findAll()`；对账/温湿度窗口扫描仍待跟进
- [x] S-P2-3（再续）：温湿度历史 `LIMIT`（按小时×30，硬顶 5000）；争议/反馈/发票/库存流水列表加上限；对账日窗全量 ID 仍按日口径保留
- [x] S-P2-4：`CacheNames` 统一前缀与 TTL 档位；`AdminDashboardController` 读写均走常量；`CacheService` 默认 TTL 对齐 `TTL_DEFAULT_MS`
- [x] S-P2-7：`ScheduleZones`（Asia/Shanghai）+ `aicabinet.schedule.zone`；对账/券/SLA/KPI/佣金/毛利 cron 显式 zone；XXL 推荐 cron 与 seed 对齐；运营台 scheduleDesc 带时区
- [x] S-P2-10：`ApiVersions`（当前 v2）+ 拦截器响应头 / 未支持版本 410；`shared-api` 导出 `API_VERSION`/`API_PREFIX` 并带请求头；破坏性变更开 v3 灰度（未开路由）
- [x] 防回归门禁：`check:scheduled-zone`（cron 必带 zone）、`check:cache-names`（禁止裸 cache prefix）；汇总 `pnpm check:audit-gates`（含 dialog-a11y）
- [x] A-P2-005（试点）：`AdminEndpoints` 收敛工作台/趋势/SLA/财务统计/设备参照；`check:admin-endpoints` 禁 views/composables 再散落试点字面量；已并入 `check:audit-gates`
- [x] A-P2-006：`auth-storage` — Cookie 不落 JWT；非 Cookie 仅 `sessionStorage`；生产 `cookieEnabled=false` 拒绝持久化；遗留 localStorage JWT 自动迁移删除；`check:admin-token-storage` 并入 `check:audit-gates`
- [x] C-P2-8：消息 COUPON 按「已持有券优先使用」收口（禁误调 claimCampaign）；深链 UNUSED + 置顶高亮；C-P2-2 成功 toast 改 showSuccess
- [x] C-P2-4：充值记录 `consumerApi.listRecharges`；M-P2-2：分账页深链 `orderId` 置顶高亮（失败 Tab 未命中回退全部）
- [x] M-P2-5：`clearMerchantMe` + session clear hook（登出/401 清内存 me）；M-P2-12：补货缺凭证拍照 await 后继续完成
- [x] C-P2-9：`/api/v2/orders/pending-count` + 消息中心改用之；C-P2-7 index/messages onShow/load 序号门闩；C-P2-10 DISPUTED 导航纳入 finishingSession
- [x] M-P2-6：分账明细分页（20/页）+ 加载更多/触底；失败 Tab 双状态同页合并；深链 orderId 多页扫描
- [x] M-P2-7：补货页删除 `hero-orb` 死装饰节点与样式
- [x] M-P2-8：清除 merchant-api `@deprecated` 别名，页面改引 `shared-types`（争议视图类型保留）
- [x] M-P1-1 补货列表聚合接口（消 N+1）— evidenceCount/lineSummary
- [x] M-P1-2 钱包页抽公共组件（`WalletPage` + role）
- [x] M-P1-3 replenishment.vue 拆分（子组件 + Door/List/Fulfillment/Detail/Scan/Display/Shell composables）
- [x] S-P1-1 SettlementService 拆分（VisionAsync / PartialRefundMath / PartialRefund / WaiveRefund / ConfirmDispute / OrderFinalize / Recognition / SettleOrchestrator / OrderSupport；主类为薄 Facade + 锁）
- [x] S-P1-2 Session 拆分（`SessionExpireService` / `SessionOpenService` / `SessionRestockService` / `SessionDoorService` / `SessionSettleService`；主类为 Facade + 锁/DTO/购物车）
- [x] A-P2-002 WarehouseView 拆分（采购/盘点/货位/出库/调拨写流 + 实体弹窗 + `useWarehouseTabLoader` + `useWarehouseListFilters` / `useWarehouseCsv` + `useWarehouseLabels` / `useWarehouseRouteLifecycle`；页约 3.2k 行）
- [x] S-P1-5 / M-P1-5 定价乐观锁（`device_sku_price.version`；库存此前已有）
- [x] H5 隐私政策首屏弹窗（C-P2-5 / M-P2-4）— `privacy-consent-modal` + 两端入口页
- [x] 24 页分包（C-P2-3）— consumer `pages.json` 主包 6 + `subPackages` + `preloadRule`
- [x] merchant H5 Cookie 优先（对齐 consumer；`aicabinet_admin_session`）
- [x] merchant 分包 — 主包 tab+登录；其余 `subPackages` + `preloadRule`

> urlCheck / 真实 appid：本地 `manifest` 可保持 `urlCheck:false`；**生产构建**由 `scripts/validate-miniapp-env.mjs` 强制 `urlCheck=true` + 非空 appid（真实支付/发号另议）。

---

## 八、最终版 vs 历史审计对比

| 维度 | R3 UI 审计（2026-09-12） | 本次最终版 |
|------|--------------------------|------------|
| 范围 | 仅 UI（admin-vue + 两端小程序的界面、按钮可见性、间距、对齐） | **UI + 按钮功能 + 业务逻辑 + 界面 + 体验 + 性能 + 架构**（七维度） |
| 深度 | 7 项建议 + 5 项新发现，R3-A01 admin 玻璃态漏改 + 同日修复 | **77 条 P0/P1/P2**（源码复核后），覆盖三端 + 后端 + 视觉 + 边缘 |
| 端覆盖 | admin-vue + consumer-mp + merchant-mp | **三端 + services + vision-service + edge**（6 模块） |
| 输出形式 | markdown 报告 + 修复 PR | **本最终版文档 + 上线前 checklist**（各端独立 audit 未单独落盘） |

---

## 九、文档归档与导航

### 9.1 本次审计产物

| 文件 | 内容 |
|------|------|
| `docs/final-audit-2026-09-13.md` | **本最终版（唯一合并真源）** |

> 原稿曾规划 `admin-vue-audit.md` / `consumer-mp-audit.md` / `merchant-mp-audit.md` / `services-audit.md` 等独立文件；当前仓库**未落盘**，结论均已并入本文件。勿再引用不存在的路径。

### 9.2 历史审计产物（保留）

| 文件 | 内容 |
|------|------|
| `docs/ui-audit-2026-09-12.md` | R1 UI 审计 |
| `docs/ui-audit-merged-2026-09-12.md` | R2 合并报告 |
| `docs/ui-audit-r3-2026-09-12.md` | R3 复核（5 项新发现已修） |
| `docs/ui-audit-r3-verify-2026-09-12.md` | R3 修复落地核验 |
| `docs/production-launch-checklist.md` | 真收费上线业务门禁（与本审计 P0 口径互补） |

### 9.3 阅读路径

1. **新成员**：`docs/final-audit-2026-09-13.md`（本文件） → `docs/MODULES.md` → `docs/ARCHITECTURE.md`
2. **修复 P1**：本文件 §七.3 checklist → 各端章节
3. **架构理解**：`docs/ARCHITECTURE.md` → `docs/MODULES.md` → 本文件 §四.4 后端架构评估
4. **三端对比**：本文件 §一/§二/§三 → `docs/FRONTEND_PRODUCT_DECISIONS.md`
5. **真上线门禁**：`docs/production-launch-checklist.md`

---

## 十、修订记录与时效

- **审计时间**：2026-09-13（Final R1）
- **源码复核修订**：2026-09-13（修正计数、误报、过时项；见下）
- **主要修订**：
  - 汇总计数对齐各章：P1=23 / P2=54 / 合计 77
  - 「三端 manifest appid」改为两端小程序；admin 无 `manifest.json`
  - S-P1-4 原「trade 缺生产凭证校验」→ 已有 `ProductionStartupValidator`，降为 S-P2-11
  - E-P1-2「publish 失败未入队」→ 源码已入队，剔除误报
  - M-P1-5「H5 wx is undefined」→ 实际经 `wxLoginCode` reject，降为 M-P2-14
  - A-P1-003 补充：已有 `onUnmounted`，keep-alive 下仍缺 `onDeactivated`
  - C-P1-3 区分充值上限 / 余额退款 / 订单自助退款（分 vs 元）
  - `.gitignore` 取消忽略 `docs/final-audit-*.md`，便于版本控制
- **下次审计触发**：上线后第一个迭代 / 重大重构后 / 新端接入时
- **失效条件**：支付接入真实通道、视觉识别接入真实模型、edge 接入真实硬件时，本报告相关 P1/P2 需重新评估

> 本文件为 ai-cabinet 项目**当前阶段**（开发期 / 未接真实硬件与支付）的最终代码审计；任何模块的「接入真实硬件/真实支付」动作均需重新审计并更新本文。
