# 问题台账（MA-xxx 唯一编号源）

> 规则见 [README.md](README.md)。领号前先回读本表（编号全局递增，禁止撞号）。
> 入账前提：**已在当前 HEAD 实跑复现**（用户拍板：旧文档结论只当线索，复现通过才入账）。

## 索引表

| ID | 级 | 状态 | 端/页面 | 一句话 | 发现人/日期 | 关联 |
|----|----|------|---------|--------|-------------|------|
| MA-001 | P2 | fixed | admin `/user-analysis`（后端口径） | 30 天口径 KPI 随所选窗口漂移：days=7 时活跃/新增「30 天」显示 0（真值 2），days=90 时显示 24（真值 2） | Qoder R1 / 2026-10-31 | |
| MA-002 | P3 | fixed | admin `/user-analysis` | 沉睡名单静默截断 20 条：KPI=22 但表格/CSV 导出/召回弹窗「目标人数」均按 20 显示且无截断提示 | Qoder R1 / 2026-10-31 | |
| MA-003 | P3 | fixed | admin `/user-analysis`（召回弹窗） | 「目标人数（近 {{days}} 天沉睡名单）」文案与实际不符：沉睡窗口固定 30-90 天，与所选 days 无关 | Qoder R1 / 2026-10-31 | |
| MA-004 | P2 | fixed | admin `/sales-reports` + `/big-screen`（品类维度） | 品类维度直接渲染字典 value 裸码（显示「1」而非品类中文名），两处同源（`CompetitiveGapService.aggregateByCategory`） | Qoder R1 / 2026-10-31 | 355bb308 |
| MA-005 | P3 | fixed | admin `/big-screen`（头部） | 窄视口（<1600px）下大屏头部标题与右侧信息重叠溢出 | Qoder R1 / 2026-10-31 | 5a62f77e + 产物 0eb90b49 |
| MA-006 | P2 | fixed | 三端共享 `displayBizNo`（admin `/orders` 首发） | 列表显示的单号是剥出来的假短号（`E2E-TAIL-0001`→「20001」），按显示值搜索 0 结果，且同行「复制单号」给的是真值 | Qoder R2 / 2026-10-31 | 见明细 |
| MA-015 | P2 | fixed | admin `/consistency`（巡检规则） | 巡检把「下单时无批次」的历史订单判成 MISSING_SALE（规则只看设备现在有无批次账本，不看订单行是否带批次）⇒ 本轮 5 条 FAIL 里 2 条是误报 | Qoder R6 / 2026-10-31 | 见明细 |
| MA-019 | P2 | fixed（档 A） | admin `/menus` 菜单管理 | 提示语称侧栏叶子与「菜单 C」一一对应，实测 19 页共用他页权限码（`ops:warehouse:list` 一行管 8 页），停用一行会连带隐藏全部共用页且树上毫无提示；另有 `/recognition-demo` 幽灵 C 行（无路由无页面、3 角色持有） | Qoder R8 / 2026-11-01 | rounds/R08-system.md |
| MA-020 | P1 | **待拍板**（未动码） | admin `/login` | 「记住账号密码」默认勾选，登录口令以 base64（可逆）常驻 `localStorage.admin_password`，只有取消勾选才清；资金后台凭据在浏览器侧一步可还原 | Qoder R8 / 2026-11-01 | 推荐档 B：应用不再存口令，交浏览器密码管理器 |
| MA-021 | P2 | fixed（V421） | admin `/menus` + 角色权限树 | 4 个「前后端零引用」页面权限码仍 ACTIVE（H51 只改了前端 perm），菜单管理显示成启用页、角色树勾了不生效＝幽灵菜单 | Qoder R8 / 2026-11-01 | 沿用 V386 RETIRED 口径；`ops:operlog`/`ops:pricing:view`/`ops:nav:*` 判据不同刻意保留 |
| MA-022 | P3 | fixed | admin `/devops` + `/observability` | dev（vite）端内嵌看板恒 404：在线探针由后端发起说「在线」，iframe 走同源 `/devops/grafana` 而 vite 只代理 `/api` ⇒ 「卡片在线、嵌入区空白」自相矛盾 | Qoder R8 / 2026-11-01 | 生产形态（网关挂 /admin）已实证 200，非产品缺陷 |
| MA-023 | P2 | 展示侧 fixed（`a96b57c8`）/ 记账侧随 MA-029 闭合 | consumer-mp `pages/index` 商品卡 + `cabinet_order` 优惠拆解 | 会员 98 折在两层都不现身：商品卡下发目录价 350、活动价按 343 再减 100 ⇒「原价 ¥3.50 − 1.00 ≠ 实付 ¥2.43」；且 `member_discount_cents` 全仓无写入者恒 0，而巡检对账式正在消费该列 | Qoder R9 / 2026-11-01 | 原价列 V424 已落；巡检折后式已拆雷（`813bb20e`）；入账与检测式于 2026-11-02 随 MA-029 落齐 |
| MA-024 | P2 | fixed（V424 毛额列 + 明细摊开） | consumer-mp `pages/order-detail` vs `pages/result` | 同一单结果页披露「活动优惠 减¥1.00」、订单详情页没有活动行；且 `cabinet_order` 无 `original_amount_cents` 列 ⇒ 读模型反推的「商品合计」是活动后金额，现在补行只会更对不上 | Qoder R9 / 2026-11-01 | 新增 `gross_amount_cents`；页面按 合计−会员−活动−券=实付 摊开，会员档账面为 0 时反推 |
| MA-029 | P1 | fixed（方案 A：①为权威） | 结算链 `SettlementOrderSupport` + 巡检 + 部分退预估 | 会员折扣有两套并存机制：①单价里已折（行金额含折扣）②把 `member_discount_cents` 当订单级减项再扣一次。历史因②无写入者恒 0 而潜伏；一旦入账即重复扣减＝少收钱 | Qoder R9 / 2026-11-01 | 2026-11-02 落地：删 ②共 **5 处**减项（改单重算/比例退/退侧预估/巡检检测式/脏券清理不再抹会员）+ ①入账 + V425 改 ②形种子；见 rounds/R09 与 AUTO-RUN §1 |
| MA-030 | P2 | fixed（R9 批 1 轮内修） | merchant-mp 首页 + 待办页（同一屏两个「待办数」） | 首页快捷入口角标 = `mergedTodos.length`（全量 60），底部 tab 角标与页内四卡 = 归类合计（58）——根因是 `RESTOCK_TASK_TIMEOUT`（补货任务超时）没归任何卡；同屏两数对不上，用户把四卡相加也复算不出角标 | Qoder R9 / 2026-11-02 | 修法沿用 2026-10-27 `OPEN_TIMEOUT` 那条：归库存/补货卡 + 新增单源 `todoBadgeCount()`，两处徽标改走它；不变式「徽标==列表行数（全类型清单）」3 例钉测（先红 2 例再绿 29/29）+ 活体复验 60/60 |
| MA-032 | P3 | **open（轮末文案批，含口径判）** | merchant-mp `pages/warehouse-stocktake` 盘点单列表 | 「待录入」盘点单显示 `明盘 · 账面 502 · 实盘 502 · 差异 0（0 行）`，读起来像「已核平无差异」；DB 实证该单 9 行全 `PENDING`、`counted_qty` 是**创建时按账面预填**的（明盘设计＝只改偏差行）⇒ 数字不算错，但状态语义没交代，运营/商户会误读 | Qoder R9 / 2026-11-02 | 建议：`status=DRAFT` 时渲染「实盘 未录入（默认同账面）」；模板 `warehouse-stocktake.vue:43-44` 已有 `countedQty ?? '—'` 兜底，改的是「预填态」而不是空值态。判据 = 待录入单不再出现「差异 0（0 行）」 |
| MA-033 | P3 | fixed（R9 批 2 轮内修） | merchant-mp `pages/self-replenish` | 空明细时页面同时显示两句打脸话：上面「已按缺货与销量建议预填数量，可直接调整」，下面「该柜机暂无在架商品（商品已下架或柜内无台账）」；默认选中柜机是 门店二号柜（`/suggestions?deviceId=CAB-OTHER` 实测返回 `[]`，因其 7 个货道 `assignedSkuId` 全为 null） | Qoder R9 / 2026-11-02 | 修法：提示行加 `v-if="draftLines.length"`；活体复验（重烤产物 + `cli auto :9420`）⇒ 空态只剩一句实话 + 按钮显示「发起补货（0 种）」。图证 `b2-m13*`（修前）/`b2-m21*`（修后） |
| MA-031 | P3 | **open（轮末文案批）** | merchant-mp 待办行类型标签 | 4 个待办类型不在 `merchant_alert_type` 基线里 ⇒ 行首类型显示「未知」（实测命中 补货任务超时 / 开门超时 两行）；缺 `OPEN_TIMEOUT`、`RESTOCK_TASK_TIMEOUT`、`SLOT_DISCREPANCY`、`INVENTORY_MISMATCH`；另 `DOOR_OPEN_TOO_LONG` 与 `OPEN_TIMEOUT` 若都译成「开门超时」会撞 `DictLabelUniquenessIT` 的同名门禁 | Qoder R9 / 2026-11-02 | 修法：基线 `packages/shared-dict/src/index.ts:783-798` 补 4 值（`DOOR_OPEN_TOO_LONG` 改「长时间未关门」与 `exception_type` 对齐）+ 重烤 shared-dict dist + `SysDictBootstrap` 默认值 + 迁移补 DB 侧字典（admin 读 DB）；判据 = 待办页 0 行「未知」 |
| MA-034 | P3 | **open（轮末文案批）** | merchant-mp `pages/business` 经营分析 KPI 卡 | 同屏两套分母不标注：「营收 ¥7.00 / 件均 ¥3.50」用**行原价营收**，而「客单 ¥2.50」用**实付合计 ÷ 成交单数** ⇒ 用户按屏上数算不平（7.00 ÷ 2 单 ≠ 2.50） | Qoder R9 / 2026-11-02 | 代码已证实两个分子：`MerchantAnalyticsService:113` `sumRevenueByDeviceIdsSince`（原价）与 `:124-126` `orderRevenue/orderCount`（注释「客单 = 剩余实付合计 / 成交单数（零元单不进分母）」）。数值本身都对，缺的是标注；最小修法＝标签改「客单(实付)」或复用本页既有 `help-q` 说明位。判据 = 卡内任意两数可互相推导 |
| MA-025 | P2 | fixed（读面判据） | consumer-mp `pages/balance` 余额明细 | 微信支付分的 `CHARGE`（通道 WECHAT、余额前后 NULL）被列进「余额明细」并显示「余额 ¥0.00」，而同页可用余额 140 未动 ⇒ 读成「余额被扣到 0」 | Qoder R9 / 2026-11-01 | 根因＝可见性查询不按通道过滤 + `value()` 把 null 兜底成 0 |
| MA-026 | P2 | fixed（读面判据） | consumer-mp `pages/coupons` + `pages/marketing` | 过期券（`expire_at` 已过 5 天）在读面仍算「未使用/1 张可用」且可设优先使用，而 `CouponService` 用券抛 409、自动选券直接排除 ⇒ 「提示可用、结算不选」自相矛盾 | Qoder R9 / 2026-11-01 | 置过期只靠日批（最近成功 10-24 02:00），读面无 `expire_at` 兜底 |
| MA-027 | P2 | fixed（V423 + 三处默认值） | consumer-mp `pages/help` vs `pages/policy` | 客服电话两个号：帮助中心读配置 `consumer.service_phone`=400-888-0018，用户协议/隐私政策正文硬编码 4000182992（3 处）⇒ 法定联系方式披露与实际客服号不一致 | Qoder R9 / 2026-11-01 | 🔴 用户 2026-11-01 确认 **4000182992 是实号**（微信支付已认证）⇒ 改配置侧，法律文本不动 |
| MA-028 | P3 | fixed（V422 + 写入口校验） | consumer-mp `pages/points/redeem` | 3 张兑换券券名与 `min_spend_cents` 不符（满100减20 实际满50、满50减10 实际满20、满20减5 实际无门槛），页面把券名与「满 X 可用」并排显示 | Qoder R9 / 2026-11-01 | 建议以门槛为准改名 + 加「券名满N ⇔ 门槛」校验门禁 |
| MA-018 | P3 | fixed（V418 彻底清除） | admin 各页字典下拉 | 8 组字典同一 type 下两个值同名（如「已回复」「对账差异」「手工入库」），下拉出现重复项；根因是 `SysDictBootstrap` 的兼容别名 | 用户 / 2026-10-31 | 门禁 `DictLabelUniquenessIT`（白名单已清空）+ V418 + shared-dict baseline 同步 |
| MA-017 | P3 | fixed | consumer`/auth` 短信验证码（phone-verify 代码审查） | 校验是「查出未用 → save(used_at)」，并发两个请求可同时通过同一码（双花） | Qoder R7 / 2026-10-31 | 见明细 |
| MA-016 | P3 | wontfix（用户拍板） | admin `/consistency`（争议规则） | 「争议已结案但无关联订单」一律记不一致（3 条），未区分「无购买争议」是否本就允许无单 | Qoder R6 / 2026-10-31 | 见明细 |
| MA-014 | P3 | fixed | admin `/fund-bills`（账务明细） | 账期留空时后端只看**近 7 天**且页面不交代 ⇒ 库里 6 条 10 月流水全不显示，空态「暂无流水」误导为没数据 | Qoder R6 / 2026-10-31 | 见明细 |
| MA-013 | P3 | fixed | admin `/print`（打印预览） | 纸张固定 720px + 左右 40px 内边距 ⇒ 窄视口（702px）溢出、`margin:auto` 失效看着偏左 | 用户 / 2026-10-31 | 见明细 |
| MA-012 | P2 | fixed | admin `/ad-campaigns`（关键词） | 关键词为前端本页过滤（`data.items.filter`），`total` 仍取服务端 ⇒ 行数与「共 N 条」不符、跨页命中丢失（MA-007/011 同族，由新门禁扫出） | Qoder R3 门禁 / 2026-10-31 | `check:list-filter-server-side` |
| MA-011 | P2 | fixed | admin `/device-ops`（关键词） | 关键词是**前端本页过滤**（`filterByKeyword` + 页内条数顶替 total），与 MA-007 同类：命中第 2 页的事件搜不到、总数谎报 | Qoder R3 / 2026-10-31 | 同 MA-007 族 |
| MA-010 | P2 | fixed（档 A → 已加 from/to） | admin `/device-ops` 设备运维 | 列表后端写死「近 14 天」窗口（100 条事件只显 54），前端既无提示也无日期筛选 ⇒ 14 天前的事件在页面上永久不可见，而「共 54 条」看着像全量 | Qoder R3 / 2026-10-31 | 见明细 |
| MA-009 | P3 | fixed | admin `/devices/:id`（关联单据 tab） | 两张表各只取最近 8 条，段级既无总数也无「查看全部」入口（总数已取回却只用于空态判断） | Qoder R3 / 2026-10-31 | 见明细 |
| MA-008 | P2 | fixed | admin `/reports` vs `/dashboard`（同名 KPI） | 「离线设备」两处不同数（报表 5 / 工作台 3）且报表侧不标口径：报表=全量含未投放/已退役，工作台=仅 DEPLOYED（刻意设计） | Qoder R3 / 2026-10-31 | 线索 #2 转正 |
| MA-007 | P1 | fixed | admin `/sessions`（类型筛选） | 「类型」筛选是**客户端只对当前页生效**：已完成+消费 显 13 条，真值 17 条，且 `total` 被换成页内过滤后条数 ⇒ 结果不全、分页总数不可信、导出同源 | Qoder R2 / 2026-10-31 | 见明细；lessons #205 |

## 待复核线索（未入账，实跑复现后转 MA 编号）

### 线索 #1：供应商对账单页面疑似不可达（R4 复核）→ ✅ 已实证并收口（D28-B15，2026-10-29）

- **来源**（当前代码，非旧文档）：
  - `clients/admin-vue/src/router/index.ts` 有路由 `warehouse-reconciliation`（`/warehouse/reconciliation`，标题「供应商对账单」，meta 无 `perm`）；
  - `clients/admin-vue/src/config/menu.ts` 的 `BASE_NAV` **没有** `warehouse/reconciliation` 条目；
  - `router/index.ts` 的 `beforeEach`：`!nav && !metaPerm` → 一律重定向 `forbidden`（fail-closed），而 `findNavByPath('/warehouse/reconciliation')` 只查 `NAV_ITEMS`，查不到。
- **实跑证实（2026-10-29，Playwright + 超管 13900000001）**：直链 `/admin/warehouse/reconciliation` → `forbidden?from=...`，页面 0 个元素；且全站 grep 无任何入口链到该路由——整页死路，**P1 成立**。
- **额外实测发现**：即使补登记 NAV_ITEMS，挂后端码 `ops:procurement:list` 仍 403——`rbac/me/nav` 的 ACTIVE 菜单表（95 行）没有任何行用该码，`isNavMenuActive` 恒 false；须挂已在 ACTIVE 表里的码（兄弟子页口径 `ops:warehouse:list`）。
- **修复**：见 [admin-vue-debt-tracker.md 十五期 D28-B15](../engineering/admin-vue-debt-tracker.md)（F5 批次）：NAV_ITEMS 补登记 + 菜单码 `ops:warehouse:list`，页内「生成对账单」由 `canList`（=后端注解码 `ops:procurement:list`）收口；Playwright 复测页面可达。R4 无需再开 MA 条目。

## 明细区块（每条一节，追加在文件末尾）

<!-- 模板：
### MA-xxx 标题
- 级别/状态：P1 / open
- 端与页面：admin `/orders`（订单管理 → 退款弹窗）
- 发现人/日期/HEAD：xxx / 2026-10-xx / `<sha>`
- 环境：本地 docker（trade:18080，admin 产物 <sha>）/ 开发者工具 mp-weixin
- 复现步骤：1) … 2) … 3) …
- 期望 / 实际：… / …
- 证据：evidence/MA-xxx-1.png（截图/请求响应/控制台）
- 源码定位：`path/to/file.ts:123`
- 联动影响：consumer-mp `order-detail` 显示 …
- 关联：AU-xxx / D-xx（旧编号，仅线索）
- 修复记录：（fixed 时填：改动、验证方式、门禁结果）
-->

### MA-001 用户分析「30 天」口径 KPI 随所选窗口漂移（active30/new30 与 active7 同源）
- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R1；修复锚 `43a4efe0` + 产物 `c3e0e44d`）
- 端与页面：admin `/user-analysis`（根因在后端 UserBehaviorAnalyticsService）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / `0eb90b49`
- 环境：本地 docker（trade:18080），运营超管账号
- 复现步骤：
  1) GET `/api/v2/ops/admin/growth/user-analysis?days=7`（库内两真实用户 last_order=2026-10-16/10-22，均在 30 天窗口内、7 天窗口外）→ `activeUsers7d=0, activeUsers30d=0, newUsers30d=0`，真值 active30=2、new30=2；
  2) `?days=90` 并回填 22 个 last_order=2026-08-20 的订单后探针 → `activeUsers30d=24`（真值 2，把 90 天窗口内活跃全计入「30 天」）；
  3) UI 近 7 天档 KPI 卡「活跃用户 0 / 30 天 0」。
- 期望 / 实际：`activeUsers30d`/`newUsers30d` 恒为近 30 天口径（标签写死「30 天」）/ 实际随 `days` 参数同窗：days<30 漏人（0），days>30 虚增（24）。
- 根因：`computeWindowMetrics` 只遍历 `windowOrders`（days 窗口），`active30++`/`active7++` 在同一循环对同一批用户计数 ⇒ active7≡active30≡「days 窗口活跃数」；`new30` 虽用 `since30` 比对首单，但迭代集仍是 days 窗口订单，days<30 时漏人。
- 证据：evidence/R01-useranalysis-active30-window-7d.png（近 7 天档 KPI=0/0）
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/UserBehaviorAnalyticsService.java:106-140`
- 联动影响：运营按「30 天活跃」判断大盘规模时，选 7 天档会读成 0（误判无活跃），选 90 天档读成 24（虚增 12 倍）。
- 修复记录：2026-10-31 fixed（commit 见 R1 批次）。`computeWindowMetrics` 改三窗口独立计数（7d/30d 固定 + days 窗口），订单查询取 `min(sinceWin, since30)` 并集；钉测 `summary_days7_active30AndNew30_stayFixed30dWindow`、`summary_days90_active30_notInflated_andDormantList_notTruncatedAt20`（UserBehaviorAnalyticsServiceTest，4/4 绿）；活栈容器重建后探针 days=7/30/90：active30 恒 2、new30 恒 2、orders 随窗口（0/14/14）。口径依据 CB-081。

### MA-002 沉睡名单静默截断 20 条，导出与召回弹窗按截断值显示
- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R1；修复锚 `43a4efe0` + 产物 `c3e0e44d`）
- 端与页面：admin `/user-analysis`（沉睡用户表格 + 导出沉睡名单 + 召回弹窗）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / `0eb90b49`
- 环境：本地 docker（trade:18080）；回填 22 个 30-90 天沉睡用户（user_id 1990000001-22，已清理复原）
- 复现步骤：
  1) 回填 22 个 last_order=2026-08-20 的 PAID 订单+用户（`cabinet_order` 触发器 cutoff 2026-09-10 之前免 inventory 断言）；
  2) UI 沉睡 KPI=22，表格仅 20 行、无「仅显示前 20」提示；
  3) 点「导出沉睡名单」→ CSV 仅 20 行（前端 `s.dormantUsers` 已被截断）；
  4) 召回弹窗「目标人数 20 人」（真实 22）。
- 期望 / 实际：导出/展示口径与 KPI 一致（22 人），或明示截断 / 实际静默截 20。
- 根因：`UserBehaviorAnalyticsService.summary` 中 `if (dormant.size() > 20) dormant = dormant.subList(0, 20)`（dormantTotal 传全量，列表截 20，前端无从得知全量）。
- 证据：evidence/R01-useranalysis-dormant-truncated-30d.png、evidence/R01-useranalysis-recall-dialog-7d.png
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/UserBehaviorAnalyticsService.java:55-58`；前端导出 `clients/admin-vue/src/views/growth/UserAnalysisView.vue:257-269`
- 联动影响：召回不受影响（后端 `UserRecallService` 自行取全量、上限 1000），但运营按弹窗「20 人」核对发放数会对不上；导出名单不完整。
- 修复记录：2026-10-31 fixed。截断 20→`MAX_DORMANT_RETURNED=1000`（与 `UserRecallService.MAX_RECALL_USERS` 对齐）；回填 22 个沉睡用户复测：KPI=22、表格 22 行、导出同源。UI 回归截图 evidence/R01-useranalysis-fixed-dialog-22-full.png。测试数据已清理（0/0）。

### MA-003 召回弹窗「近 {{days}} 天沉睡名单」文案与实际固定 30-90 天窗口不符
- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R1；修复锚 `43a4efe0` + 产物 `c3e0e44d`）
- 端与页面：admin `/user-analysis`（一键召回弹窗）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / `0eb90b49`
- 环境：同 MA-002
- 复现步骤：选「近 7 天」→ 沉睡表格仍有 22 人（固定窗口）→ 点「一键召回」→ 弹窗显示「20 人（近 7 天沉睡名单，最多 1000 人）」。
- 期望 / 实际：文案「30-90 天沉睡名单」（沉睡窗口固定，`UserBehaviorAnalyticsService` 用固定 dormantCutoff/dormantFloor，与 days 无关；后端召回同源）/ 实际按所选 days 动态拼「近 7 天」，误导运营以为召回目标随窗口变化。
- 证据：evidence/R01-useranalysis-recall-dialog-7d.png
- 源码定位：`clients/admin-vue/src/views/growth/UserAnalysisView.vue:191`
- 联动影响：纯文案；行为本身一致（前后端都用同一固定窗口名单）。
- 修复记录：2026-10-31 fixed。`UserAnalysisView.vue` 弹窗改「{{ s?.dormantUsers30d ?? 0 }} 人（30-90 天沉睡名单，最多 1000 人）」——人数取全量 KPI 字段、窗口文案写死真实口径；复测弹窗显示「22 人（30-90 天沉睡名单）」。
### MA-004 品类维度显示裸字典码「1」（销售报表 + 大屏同源）

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R1；修复锚 `355bb308`）
- 端与页面：admin `/sales-reports`（销售报表 → 品类维度）与 `/big-screen`（品类销量榜）——同一后端方法，两页同时受影响
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / 发现于 R1 轮内（开轮基线 `4a96b0af`；精确观测 SHA 未记，故不假装精确定位）
- 复现步骤：1) `/sales-reports` 选品类维度出报表 → 类目列显示 `1` 而非中文品类名；2) `/big-screen` 品类榜同样显示裸码
- 期望 / 实际：与消费者目录一致的中文品类标签 / 直接透出 `sku_catalog.category` 里存的 `category_code` 字典值
- 根因：`CompetitiveGapService.aggregateByCategory` 归并键取 `sku_catalog.category`（存的是 `category_code` 字典值），此前**未过 `SysDictService` 翻译**就进展示字段。
- 证据：无独立截图（缺陷与结论记在 [rounds/R01-overview.md](rounds/R01-overview.md) 的 `/sales-reports`、`/big-screen` 两节）
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/CompetitiveGapService.java`（`aggregateByCategory`，修复后构造函数多注入 `SysDictService`）
- 联动影响：任何复用该品类维聚合结果的读面（报表导出 CSV 同列）都会带裸码。
- 修复记录：2026-10-31 fixed（`355bb308`）。与消费者目录同口径经 `SysDictService` 翻中文；**未收录的历史自由文本原样透出、空值仍归「未分类」**（刻意不改成 fail-closed 报错，避免历史数据把整张报表打死）。钉测 `CompetitiveGapSalesReportTest.salesReport_categoryDim_translatesDictCodeToLabel`。回归索引见 [regression-index.md](regression-index.md)。

### MA-005 运营大屏头部在窄视口下标题与右侧信息重叠溢出

- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R1；修复锚 `5a62f77e` + 产物 `0eb90b49`）
- 端与页面：admin `/big-screen`（全屏路由，头部区）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / 发现于 R1 轮内（开轮基线 `4a96b0af`）
- 复现步骤：1) 浏览器窗口收窄（非 1920 投屏档）；2) 头部标题与右侧时间/指标区互相压字、溢出容器
- 期望 / 实际：窄视口下不重叠（可换行/收缩）/ 绝对定位头部两坨文字打架
- 证据：`evidence/R01-big-screen.png`（修复前重叠）、`evidence/R01-big-screen-narrow-fixed-558.png`（修复后同视口档复验）
- 源码定位：`clients/admin-vue/src/views/dashboard/BigScreenView.vue`（+19 行窄视口样式）
- 联动影响：无（纯展示层）。
- 修复记录：2026-10-31 fixed。样式层收口，未动布局结构；产物随 `0eb90b49` 提交。🔴 **未解债**：无自动用例（1280/1920 双视口截图断言待 R10 补），且**严格 1920px 投屏档本轮未测**（见 R01 报告大屏节）。

### MA-006 `displayBizNo` 兜底把可读编号剥成假短号，显示的单号搜不到

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R2；发现锚 `8e12c11f` → 修复锚 `56100ddc` + 产物 `4715e6d9`）
- 端与页面：首发 admin `/orders`（订单号列 + 详情抽屉「订单号」）；根因在三端共享函数 `packages/shared-uni/src/format.ts` 的 `displayBizNo` ⇒ **merchant-mp / consumer-mp 所有用它展示单号的位置同受影响**
- 发现人/日期/HEAD：Qoder R2 / 2026-10-31 / `8e12c11f`
- 复现步骤：
  1) `/orders` 关闭「隐藏零元单」→ 第三行订单号列显示 **`20001`**；同页 API 真值是 `E2E-TAIL-0001`（`GET /api/v2/ops/admin/orders?page=0&size=10&excludeZero=1` 实测三行：`1792659779535535992400`、`1792659650288345016481`、`E2E-TAIL-0001`）；
  2) 关键词框输入 `20001` → 查询 → 请求 `q=20001&excludeZero=1` → **共 0 条 / 空态「暂无订单」**；
  3) 输入真值 `E2E-TAIL-0001` → 共 1 条（证明不是搜索功能坏，是显示值不可搜）；
  4) 同一行「更多操作 → 复制单号」复制的是 `row.orderId`（真值，`OrderListView.vue:1134` `copyOrderId(row.orderId)`）⇒ **同一行显示与复制不一致**。
- 期望 / 实际：列表显示的单号可直接用于搜索与对账 / 显示的是剥出来的假短号。
- 根因：`displayBizNo` 末段兜底 `const digitsOnly = raw.replace(/\D/g, ''); return digitsOnly.length >= 4 ? digitsOnly : raw;`（format.ts:308-309）。对「剥完已知前缀后仍含字母」的可读编号，它取纯数字：`E2E-TAIL-0001` → `20001`（丢掉首字母 `E` 与 `TAIL` 段）；按同一条规则，本轮造数前缀 `AUDIT-R1-0001` 会被显示成 `10001`（与 SKU 编号 `10001` 撞形）。
- 证据：`evidence/R02-orders-list.png`（第三行显示 `20001`）、`evidence/R02-orders-displayid-search-miss.png`（关键词 `20001` → 暂无订单）；上述两组 API 响应
- 源码定位：`packages/shared-uni/src/format.ts:268-310`（`displayBizNo`）；调用面含 `clients/admin-vue/src/views/orders/OrderListView.vue:139+`、`components/device/DeviceRelatedRecordsTab.vue`、`components/warehouse/WarehouseMovementsTab.vue` 等
- 联动影响：任何展示 `displayBizNo(...)` 的列表/详情/弹窗（三端）都可能给出不可回查的短号；客服/运营按屏幕上的号去搜必然 0 结果。
- 关联：**项目已对该类缺陷定过性**——同文件 `shortBizNo` 的 `@deprecated` 注释（2026-09-18 起）理由就是「列表取末尾 N 位、详情页给全号 ⇒ 同一单号两处对不上，客服按列表号搜不到」。既有钉测（`clients/merchant-mp/src/utils/biz-no.test.ts`）只锁了「PD 可读单号原样」「纯数字原样」，**没有覆盖本兜底分支** ⇒ 属漏网回归面。
- 修复记录：2026-10-31 fixed（`56100ddc`）。`displayBizNo` 末段兜底由「取全部数字」改为**原样返回**——走到该分支的都是「剥完已知前缀仍含字母」的混合编号，数字段不等价于编号本身；已知前缀剥完是纯数字的（`PO-12`→`12`）约定**不动**。
  - 回归：`clients/merchant-mp/src/utils/biz-no.test.ts` 新增 3 例（`E2E-TAIL-0001`/`AUDIT-R1-0001` 原样 + `PO-12`→`12` 保持），6/6 绿；
  - 活栈复验（产物 `4715e6d9`，硬刷 ignoreCache）：`/orders` 第 9 行订单号由「20001」变 **`E2E-TAIL-0001`**，关键词输入该显示值 → 共 1 条（修复前 0 条）。证据 `evidence/R02-orders-displayid-fixed-searchable.png`。
  - 影响面：`displayBizNo` 三端 34 个文件在用；生产真实单号是纯数字（早分支原样返回），本改动只改「混合字母编号」这一类。

### MA-007 开门记录「类型」筛选只在当前页生效，结果漏数据且分页总数谎报

- 级别/状态：P1 / **fixed**（2026-10-31，Qoder R2 轮末批次；发现锚 `fc5a6bde` → 修复锚 `124e03ff` + 产物 `9637a884`）
- 端与页面：admin `/sessions` 开门记录 → 筛选行「类型」下拉（消费/补货/运维/盘点）
- 发现人/日期/HEAD：Qoder R2 / 2026-10-31 / 发现锚 `fc5a6bde`
- 复现步骤：
  1) 打开 `/sessions`「全部」→ `共 72 条`（= SQL `select count(*) from shopping_session` = 21 CANCELLED + 24 COMPLETED + 22 DISPUTED + 5 FAILED ✅）；
  2) 点「已完成」tab → `共 24 条`（= SQL 真值 ✅，服务端参数 `state=COMPLETED`）；
  3) 类型选「消费」→ UI 变 **`共 13 条`**，而网络面板显示这次查询的请求仍是 `/api/v2/ops/admin/sessions?page=0&size=20&state=COMPLETED`（**没有类型参数**）；
  4) 真值对照：`GET /sessions?page=0&size=200&state=COMPLETED` → 24 条里 `sessionKind=CONSUMER` 有 **17** 条 ⇒ 少 4 条（落在第 2 页，被页内过滤吃掉）。
- 期望 / 实际：类型筛选应服务端生效、返回该筛选下全量并可翻页 / 只过滤当前页 20 行，且把 `total` 替换成过滤后的页内条数。
- 根因：`clients/admin-vue/src/views/sessions/SessionListView.vue:465-470` —— `fetchPage` 内 `rows = rows.filter(r => (r.sessionKind || 'CONSUMER') === kindFilter.value)`，随后 `const total = rows.length < before ? rows.length : (data.total ?? 0)`。该处注释写着「本页有剔除 → total 跟过滤后（禁「共 N + 空表」#205）」⇒ **是修 lessons #205 时用「页内计数」替代，代价换成了跨页漏数据**。
- 后端侧：`AdminDashboardController.java:271-284` 的 `/ops/admin/sessions` 参数集里**没有 kind/sessionKind** ⇒ 正解需要后端加参数 + mapper 过滤 + 前端把 `kindFilter` 进 query，并 regen openapi/shared-types（预检 `openapi-regen` 门禁会拦，见记忆坑：改 Controller 加参数必须 regen）。
- 联动影响：`useListCsv` 的 `pickSelected(crud.displayItems)` 同源 ⇒ **导出也只含当前页过滤结果**；`stuckOnly`/滞留置顶等其它页内过滤若与类型叠加，同样受本页内过滤语义影响。
- 证据：`.tmp` 探针（API 24 条中 CONSUMER=17）+ UI 三次观测（72 / 24 / 13）与请求 URL 记录；截图见 `evidence/R02-sessions-*.png`
- 关联：lessons #205（「共 N + 空表」旧缺陷，本次是其反向代价）；与 MA-006 同属「界面显示值 ≠ 可回查真值」类。
- 门禁化候选：PLAN 台账新增一条 —— **列表页客户端 `rows.filter(` 出现在 `fetchPage` 内即红**（除显式 allowlist），强制「筛选必须服务端化」。这条 R2 抓到两次（MA-006 是显示层，本条是取数层），值得钉。
- 修复记录：2026-10-31 fixed（`124e03ff` + 产物 `9637a884`）。
  - **同源做法**：新增 `support/SessionKind`（类型名 + 幂等键前缀的唯一字面量源），`DeviceValidationService` 的三个谓词改读同一批常量，mapper 条件也从这里取 ⇒ 没有把规则抄成第二份 SQL 字符串（铁律 16）。
  - 筛选进 `SessionFilterCriteria.sessionKind`，条件与 Java 判定顺序等价（OPS > RESTOCK > STOCKTAKE > CONSUMER）；🔴 取反一律写 `key IS NULL OR key NOT LIKE ...`——`NOT LIKE` 对 NULL 求值为 NULL，直接 `notLikeRight` 会把**没有幂等键的消费者会话整批判掉**（IT 里专门钉了这条）。
  - `/sessions` 与 `/sessions/export` 均加 `kind` 参数，未知值 **400**（不静默忽略筛选）；导出审计留痕带上 kind，事后能还原拖的是哪一类。`openapi.ts` 同批 regen（+2 行）。
  - 前端：`kind` 进 query，删掉 `fetchPage` 内的 `rows.filter(...)` 与「页内条数顶替 total」逻辑。
  - 验证腿（四腿齐）：① `SessionKindFilterSqlIT` 真库 2/2 绿（SQL 过滤结果逐个等于 `sessionKind()`、四类之和=全表、NULL 键留在 CONSUMER）；② 既有 `DeviceValidationServiceSessionKindTest` 3/3 绿；③ API 真值：已完成∩消费 **17**（RESTOCK 4 + STOCKTAKE 3 + OPS 0，四类之和=24=已完成全量）；④ 活栈 UI 重建产物后：**共 17 条 / 17 行**，请求 `?state=COMPLETED&kind=CONSUMER`，console 0 error。

### MA-015 一致性巡检对「无批次历史订单」误报 MISSING_SALE

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R6；发现锚 `d5c22e8c` → 修复锚 `（本批）` + 容器重建后巡检复跑）
- 端与页面：admin `/consistency` 数据一致性 → 巡检规则 `checkCrossLinkMissingSale`（CROSS_LINK/A）
- 发现人/日期/HEAD：Qoder R6 / 2026-10-31 / 发现锚 `d5c22e8c`
- 复现：页面 `未通过 5`，其中 2 条为 `SALE|1792659779535535992400` / `SALE|1792659650288345016481`，文案「已付有货且柜机有批次账本，但缺少 SALE+ORDER 库存流水」。
- 真值：两单 2026-10-22 支付，`cabinet_order_line.batch_no` **为空**（当时无批次，走无批次扣减，`inventory_deducted=true`）；该柜批次账本 `device_sku_lot` 最早 2026-10-26 建立 ⇒ **下单时刻不可能有 SALE 批次流水**；全库 `inventory_movement` 中 `SALE` = 0 条（只有 ADJ/SELL_OFF/RESTOCK）。
- 根因：规则只判断「设备现在存在批次账本」，未要求「订单行本身带批次」（`DataConsistencyService.checkCrossLinkMissingSale:1207-1224`）。
- 期望 / 实际：只对「从批次中扣减的销售」要求 SALE 流水 / 历史无批次订单被永久判红，检测器噪音会让人不信这张表。
- 证据：上述 SQL 真值 + 规则源码
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/DataConsistencyService.java:1207-1224`
- 关联：与 [[project-real-cabinet-settlement-test]] 的真柜结算时序一致（先有订单、后建批次）。
- 修复记录：2026-10-31 fixed。`checkCrossLinkMissingSale` 的「缺 SALE 流水」分支增加前提
  `EXISTS (SELECT 1 FROM cabinet_order_line bl WHERE bl.order_id = o.order_id AND COALESCE(bl.batch_no,'') <> '')`
  ——只有「从批次扣减」的销售才可能产生批次流水；并加注释说明历史无批次订单的时序。
  - 规则 SQL 前后对比（真库）：旧规则命中 **2** 条（正是那两笔 10-22 订单）→ 新规则 **0** 条，其余订单不受影响（库里只有这 2 笔 PAID）。
  - 活栈验证：容器重建后调用 `POST /consistency/run` → `failCount = 3`（仅剩三条争议 CROSS_LINK），SALE 误报消失；记录表状态 FAIL 5 → **3**。
  - 说明：**未**动 dispute 规则（MA-016 待拍板）。

### MA-018 字典同名标签 8 组（下拉重复项）

- 级别/状态：P3 / **open（门禁已加，清理方向待拍板）**
- 端与页面：admin 各页字典下拉（首见 `/feedback` 状态筛选「已回复」出现两次）
- 发现人/日期/HEAD：用户 2026-10-31 指出 → Qoder R7 全量排查 / 锚 `c433222e`
- 复现：`/feedback` 状态筛选出现「已回复」两次；全量 SQL 扫出 **8 组**同 type 同 label 的 ACTIVE 项。
- 根因：`SysDictBootstrap` **刻意**为历史数据保留了兼容别名（两套词表并存），例如
  `split_status` 同时种 `WECHAT_SUBMITTED`/`SUBMITTED`（都叫「已提交」）、
  `session_kind` 把 `SHOPPING`（其实是 `session_state` 的值）也种成「消费」。
  这不是散落手工数据；只在**下拉选项**层表现为重复。
- 门禁（已上线）：`DictLabelUniquenessIT`（Testcontainers + Flyway 全量迁移后断言 ACTIVE 字典无同名标签，
  棘轮白名单 8 组双向校验：新增重名红 / 清单多余红），已登记 `check-docker-tests-guarded`（job=integration）。
  首次实跑证据：`实际：{enable_status|停用=…, feedback_status|已回复=HANDLED,REPLIED, …}`。
- 清理方向（待拍板）：
  A. 清理式：每组定规范值，旧值停用/删除 + 迁历史数据（历史标签可能掉回裸码）；
  B. **选项层收敛（推荐）**：字典保留全部值用于历史渲染，只在「下拉选项」返回规范值（配 canonical 清单）——不动数据、不丢历史标签。
- 规范值证据：feedback→HANDLED（UserFeedbackService:118 写）、ops_alert→RECON_MISMATCH（ReconciliationService:216/283）、
  restock_line→PULL_OFF（ReplenishmentService:82/1705，读取端仍兼容 PULL/REMOVE）、split_status→WECHAT_*（RevenueSplitService:27）、
  warehouse_movement→MANUAL_INBOUND（WarehouseService:261）。
- 修复记录：（待选题后填）

### MA-017 短信验证码校验非原子，并发可双花

- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R7；发现锚 `04d946c7` → 修复锚（本批））
- 端与接口：consumer `POST /api/v2/auth/login`（及后台重置密码同链路）→ `SmsCodeService.verifyCode`
- 复现（代码判据 + 钉测）：旧实现 `findTopBy...` 读出未用码 → `entry.setUsedAt(now); save(entry)`，两步之间无行锁/无条件更新 ⇒ 两个并发请求都能读到未用、都判定成功。
- 影响：同一手机号同一码可在并发窗口内被消费两次（例如两个登录请求同时换取会话）；不改余额/资金，故定 P3。
- 修复：新增 `SmsVerificationCodeMapper.markUsedIfUnused(id)`（`UPDATE ... SET used_at=now WHERE id=? AND used_at IS NULL`，返回是否抢到）；`verifyCode` 成功分支改为条件抢占，抢不到 ⇒ 判失败但**不计失败次数**（避免误烧新码）；mock 码分支的 `markLatestUsed` 同改。
- 钉测：新增 `SmsCodeServiceTest` 4 条（正确码走原子消费 / 并发已消费不计失败 / 过期码标记已用 / 第 5 次错码烧码），4/4 绿。
- 说明：dev 的 `SMS_MOCK_CODE` 会让配置的万能码短路校验（仅 mock 开启时生效，生产由 `ProductionStartupValidator` 禁止），故双花的活栈复现需关 mock-code；本轮以钉测为判据。

### MA-016 争议「已结案但无关联订单」是否一律算不一致（待拍板）

- 级别/状态：P3 / **wontfix**（用户 2026-10-31 拍板：演示假数据、代码没错，3 条争议数据人工处理，规则不改）
- 端与页面：admin `/consistency` → `checkCrossLinkDispute`
- 现象：3 条 `DSP|…`「争议已结案但缺少关联订单」，对应会话 `COMPLETED` 且 `order_id` 为空（顾客开门未购 / 识别争议未成单）。
- 规则：`RESOLVED/CLOSED` 且会话无订单 ⇒ 记 CROSS_LINK（`DataConsistencyService:1273-1284`）。
- 拍板结果（2026-10-31）：**规则保持原样**；这 3 条是演示数据，按「人工处理」对待（记录仍以 FAIL 呈现，属于检测器如实反映数据，不是缺陷）。
- 旁证：同批的另一条对齐差异（`payment_reconciliation` 2026-10-05 BALANCE，diff 500 / 未匹配 1，`detail.demo=true`）也由用户定为「人工平账留痕」——已执行：status→MATCHED、diff/unmatched 归零，原始值写入 `detail.manualClose`（`originalDiffCents=500`、`originalUnmatched=1`、`by=R6-audit`）；页面复验「批次总数 7 / 差异 0 / 匹配 7」。

### MA-014 「账务明细」默认只查近 7 天且不交代，空态误导

- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R6；发现锚 `ecf45bb2` → 修复锚 `87e6beb1` + 产物 `56d58377`）
- 端与页面：admin `/fund-bills` → 账务明细 tab
- 发现人/日期/HEAD：Qoder R6 / 2026-10-31 / 发现锚 `ecf45bb2`
- 复现：账期选择器留空（默认）打开账务明细 → `共 0 条` + 「暂无流水」；API `GET /ops/admin/fund/ledger?page=0&size=20` → `total=0`，而加 `fromDate=2026-10-01&toDate=2026-10-31` → **total=6**（3 笔订单 × 实付/通道费/商户入账三类分录）。
- 根因：`FundBillService.listLedger` 默认窗口 `now-7d → now`（:332-333），页面 `range` 默认 null 且只提示「支持跨月，单次不超过 90 天」，**没写默认窗口**；账期留空时没有任何线索指向「7 天」。
- 期望 / 实际：留空应交代窗口（或在空态里给出「试试选账期」）/ 静默只看 7 天，看起来像没数据。
- 证据：上述两次 API 对照（0 vs 6）
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/FundBillService.java:332-333`；`clients/admin-vue/src/views/finance/FundBillView.vue:42,460-462`
- 关联：MA-010（14 天窗口）同族；不改变默认行为，只补交代。
- 修复记录：2026-10-31 fixed（`87e6beb1` + 产物 `56d58377`）。账期提示补「留空＝『账务明细』只看近 7 天」；空态分三态（有搜索词 / 未选账期 / 已选账期）分别给文案。**后端默认窗口一行未动**（7 天是既定行为，放宽需另行拍板）。
  - 活栈复验：提示文本与空态文本均按预期渲染；再加 10 月账期可看到 6 条分录（与 `wallet_ledger` 的 SPLIT 150/350、通道费合计 ¥0.03 对上）。

### MA-013 打印预览纸张在窄视口溢出、不居中

- 级别/状态：P3 / **fixed**（2026-10-31；发现锚 `5fd3e04a`（用户在 R4 实测中指出）→ 修复锚 `1f18d352` + 产物 `a3aa3097`）
- 端与页面：admin `/print`（采购收货单 / 拣货单预览）
- 复现：内置浏览器 702px 打开 `/print?type=picking&outboundId=15` → 纸张右侧被截、页面横向滚动；`margin: 24px auto` 因内容宽（720+80）> 视口而失效，视觉上贴左。
- 根因：`.print-sheet { width:720px; padding:32px 40px }` 未计入内边距、无上限收缩。
- 修复：`box-sizing:border-box; width:min(720px, calc(100% - 32px))` + `@media (max-width:760px)` 收紧内边距；`@media print` 原有 100%/margin:0 规则不动（打印纸面不受影响）。
- 验证（无头 Playwright 两个宽度）：702 → 宽 670、左右各 16、无横向滚动；1600 → 宽 720、左右各 440；均 `centered=true`。证据 `evidence/R04-print-centered-702.png` / `-1600.png`。
- 关联：R4 批 2「/print 打印单据」维度 5。

### MA-012 投放计划「关键词」为前端本页过滤，行数与总数不符

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R3 门禁批；发现锚 `423bdfe4` → 修复锚 `bb68e077` + 产物 `cb031e79`）
- 端与页面：admin `/ad-campaigns` 投放计划 → 关键词
- 发现人/日期/HEAD：Qoder R3（新门禁 `check:list-filter-must-be-server-side` 首扫）/ 2026-10-31 / 发现锚 `423bdfe4`
- 复现步骤：`AdCampaignsView.vue:312-325` fetchPage 里 `const items = kw ? (data.items||[]).filter(matchKeyword) : …`，`total` 仍 `Number(data.total)`；若某关键词只命中第 2 页的行，则列表为空但底部仍显示「共 N 条」；若命中当前页 3 行，则显示 3 行 + 「共 20 条」。
- 期望 / 实际：关键词服务端过滤、`total` 与命中集合一致 / 只过滤当前页且总数不联动。
- 根因：同 MA-007/MA-011（注释「关键词沿用前端过滤（只作用于当前页，与原 displayRows 行为一致），total 仍取服务端值」）。
- 证据：代码判据 + 门禁首扫输出
- 源码定位：`clients/admin-vue/src/views/growth/AdCampaignsView.vue:322-324`
- 关联：MA-007 / MA-011；门禁 `scripts/check-list-filter-server-side.mjs`（本轮落地）
- 修复记录：2026-10-31 fixed（`bb68e077` + 产物 `cb031e79`）。`/ops/admin/ad/campaigns` 加 `q`（匹配 活动名称/状态值）与 `status`（精确）参数，条件进 SQL；前端关键词进 query、中文状态标签先翻成值再作为 `status` 传（保住「按标签搜」），删除本地 `matchKeyword` 与页内过滤。
  - 验证：API `?q=RUNNING` → `total=1 items=1`（与 SQL `ad_campaign` RUNNING=1 一致）、`?q=点击` → 0；容器重建 + openapi regen（+4 行）；admin 产物重建；console 0 error。
  - 同批落地门禁 `check:list-filter-server-side`（自测 9 条断言 + 棘轮白名单 3 条 + 聚合链接线），**本条是该门禁首扫抓到的**——即「先有门禁再有修复」，后续同类写法在 CI 直接红。

### MA-011 设备运维「关键词」为前端本页过滤，跨页搜不到且总数谎报

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R3；发现锚 `54b807dd` → 修复锚 `f9bdf3f6` + 产物 `2bf02669`）
- 端与页面：admin `/device-ops` 设备运维 → 关键词
- 发现人/日期/HEAD：Qoder R3 / 2026-10-31 / 发现锚 `54b807dd`
- 复现步骤：列表 `size=50`、总 54 条 ⇒ 第 2 页只有 4 条；输入一个只命中第 2 页事件的 ID/详情关键词 → 后端仍只返回第 1 页 50 条，前端在这 50 条里 `.filter()` ⇒ **0 条**，且 total 被替换成 0。
- 期望 / 实际：关键词应服务端过滤、可命中全量窗口内事件 / 只在当前页里搜。
- 根因：`DeviceOpsMonitorView.vue:241` `filterByKeyword(rows)` + 取数处「有剔除时 total 跟可见行一致」（注释引 lessons #205/#208）；后端 `/device-ops/events` 当时没有关键词参数。
- 证据：代码判据 + MA-007 同族实测（同一台机、同样的页内过滤写法）
- 源码定位：`clients/admin-vue/src/views/devices/DeviceOpsMonitorView.vue:241`（修复后该方法删除）
- 关联：MA-007（`/sessions` 类型筛选同族）；R2 提出的门禁候选 `check:list-filter-must-be-server-side` 正是为这类设的。
- 修复记录：2026-10-31 fixed（`f9bdf3f6` + 产物 `2bf02669`）。`/device-ops/events` 加 `q`（匹配 事件ID/设备编号/标题/详情，mapper 里 `LIKE` + `CAST(event_id AS TEXT)`），前端关键词进 query 并**删除 `filterByKeyword` 与「页内条数顶替 total」**。
  - 活栈/API 验证：取放宽窗口下**第 2 页首条事件 ID（69）**做关键词 → `total=1`（修复前这种只在第 2 页的事件必然 0 条）；
  - 同批实测中文关键词 `设备离线`：API 14 天窗口内 27 条 = SQL `title LIKE '%设备离线%' AND created_at > now()-14d` 27 条（终端直接 curl 中文会因 GBK 编码得 0，必须用 UTF-8 编码发请求——这条也记进交接注记）。

### MA-010 设备运维列表后端写死 14 天窗口，前端不交代也不可改

- 级别/状态：P2 / **fixed（含 from/to 参数）**（2026-10-31，Qoder R3；发现锚 `e326601f` → 修复锚 `7dce0f04` 文案 + `f9bdf3f6` 参数 + 产物 `2bf02669`）
- 端与页面：admin `/device-ops` 设备运维（事件列表）
- 发现人/日期/HEAD：Qoder R3 / 2026-10-31 / 发现锚 `e326601f`
- 复现步骤：
  1) 打开 `/device-ops` → 分页器 `共 54 条`；
  2) SQL 真值：`select count(*) from device_ops_event` = **100**（类型分布 OFFLINE 45 / NO_SALES 28 / FAULT 21 / AISLE_AUDIT 6，且 `device_id` 全部能在 `device_info` 找到 ⇒ 不是孤儿行）；
  3) 筛选项只有 类型 / 级别 / 设备 / 关键词，**没有任何时间范围控件**；页面 hint 只说「与交易异常分流…事件 ID 默认升序」，不提 14 天。
- 期望 / 实际：要么在页面上写明窗口、要么给出可改的时间范围 / 静默截断，运营以为「共 54 条」就是全部运维事件。
- 根因：`CompetitiveGapService.listDeviceOpsEvents`（:376-380）把查询窗口硬编码成 `Instant.now().minusSeconds(86400L * 14)` ~ `now()`，controller `/device-ops/events` 也没有 from/to 参数（`OpsGapFeaturesController.java:183-195`）。
- 证据：`.tmp/r3-device-ops.json`（`paginationTotal=共 54 条` + 请求 `?page=0&size=50&sortDir=asc`）+ 上述 SQL；截图 `evidence/R03-device-ops-pass.png`
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/CompetitiveGapService.java:376-380`
- 联动影响：`/device-kpi` 的「离线事件」是**另一条口径**（今日上海时区新建的 `DEVICE_OFFLINE` 异常数，实测 2 = SQL 2 ✅ 正确），两者别混；商户端理货记录-盘点复用同一查询（CB-050 注释）⇒ 也受同一 14 天窗口影响。
- 关联：与 MA-008 同族（口径不交代）；与 MA-009（截断不交代总数）同族。
- 修复记录：2026-10-31 fixed 档 A（`7dce0f04` + 产物 `8a7c9418`）。页面 hint 追加「仅统计近 14 天事件（后端固定窗口，更早事件本页不返回，故『共 N 条』不是全量）」。活栈复验：文案已渲染、分页仍 `共 54 条`、console 0 error（`evidence/R03-device-ops-pass.png`）。
  - **用户拍板（2026-10-31）：加 from/to 参数**，已落地（`f9bdf3f6`）：`/device-ops/events` 新增可选 `from`/`to`，**两者都空时仍回落既有 14 天默认窗口** ⇒ 商户端理货-盘点（CB-050 同一查询）行为不变。前端加「时间」daterange，右端取当天 23:59:59.999。
  - 活栈验证：选 2026-10-01~10-31 → 请求带 `from=2026-09-30T16:00:00.000Z&to=2026-10-31T15:59:59.999Z`，列表 `共 80 条`；不选 → `共 44 条`；同刻 SQL `all=80 / within14d=44` **完全一致**。
  - 🔴 集成踩坑：Element Plus daterange 默认 v-model 回 **Date 对象**，而代码按 `number` 判 `Number.isFinite` ⇒ 一开始永远不带 from/to；加 `value-format="x"` 才通（已修 `0c5d76b6` 系列）。
  - 同轮澄清：`/device-kpi` 的「离线事件」是**另一条口径**（今日上海时区新建的 `DEVICE_OFFLINE` 异常数），实测页面 2 = SQL `created_at >= 2026-10-31 00:00+08` 的 2 ✅ 正确，与本条无关，别混。

### MA-009 设备详情「关联单据」截断 8 条且无总数、无段级查看全部入口

- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R3；发现锚 `2b4046a3` → 修复锚 `3f877459` + 产物 `34068cd0`）
- 端与页面：admin `/devices/:id` → 关联单据 tab（`DeviceRelatedRecordsTab.vue`）
- 发现人/日期/HEAD：Qoder R3 / 2026-10-31 / 发现锚 `2b4046a3`
- 复现步骤：
  1) 打开将邑测试机详情 `EG943322621391`（SQL 真值：`shopping_session` 62 条、`cabinet_order` 8 条）→ 关联单据 tab；
  2) 「最近开门记录」「最近订单」两张表各渲染 8 行（共 16 行），**页面上没有任何地方说明开门记录其实有 62 条**；
  3) 想查全量只能自己想去 `/sessions` 再按设备筛——段级没有跳转入口，只有行级（点某条会话号才跳 `/sessions?deviceId=…&sessionId=…`）。
- 期望 / 实际：截断要同时给出总数与查看全部入口 / 只写「最近」，运营会以为该柜就这 8 次开门。
- 根因：`useDeviceRelatedRecords.ts:25-45` 两张表都按 `page=0&size=8` 取数，但**同时把 `total` 存进了 `sessionTotal` / `orderTotal`**；父页 `DeviceDetailView.vue:1215-1216` 只拿这两个总数判断「是否全空」，模板里从未渲染 ⇒ 数据在手却没用。
- 证据：`.tmp/r3-detail.json`（将邑柜 关联单据 rows=16）+ 上述 SQL 真值；截图 `evidence/R03-device-detail-related.png`（修复后）
- 源码定位：`clients/admin-vue/src/components/device/DeviceRelatedRecordsTab.vue:30,131`（两个 `section-title`）
- 联动影响：`/devices` 列表页的「关联记录」入口同组件 ⇒ 一并受益。
- 关联：与 R2 MA-007 同族（截断/口径不交代），但本条是**展示层缺交代**，非取数错误。
- 修复记录：2026-10-31 fixed（`3f877459` + 产物 `34068cd0`）。段级标题同行加「共 N 条 · 查看全部」，**仅当 `total > 已渲染行数` 才出现**（不截断就不显示），点击复用组件既有的 `open-sessions` / `open-orders` emit，不新增跳转实现。
  - 活栈复验（真 Chrome 1600）：将邑柜「最近开门记录」显 **共 62 条 · 查看全部**，点击落 `/admin/sessions?deviceId=EG943322621391` 且列表 `共 62 条`（深链两页都支持）；
  - 负向判据同测：同柜「最近订单」8 条未截断 ⇒ **不显示链接**；演示柜 2 条会话 ⇒ 两段都无链接；
  - 证据 `evidence/R03-device-detail-related.png`；console 0 error。

### MA-008 「离线设备」在设备报表与工作台是两个口径，报表侧不交代口径

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R3；发现锚 `35804dd5` → 修复锚 `7ad59bbc` + 产物 `c4ccfde9`）
- 端与页面：admin `/reports` 设备报表 KPI「离线设备」 vs `/dashboard` 运营工作台待办「离线设备」
- 发现人/日期/HEAD：Qoder R3 / 2026-10-31 / 发现锚 `35804dd5`（由 R1 遗留的**线索 #2** 转正）
- 复现步骤：
  1) 工作台读 `GET /api/v2/ops/admin/workbench-bundle` → `offlineDevices` = **3**；
  2) 设备报表 KPI 读 `GET .../reports/devices?online=OFFLINE&page=0&size=1` → `total` = **5**（`DeviceReportView.vue:554-561`）；
  3) SQL 真值：`select count(*) from device_info where online_status<>'ONLINE'` = **5**；再加 `and lifecycle_status='DEPLOYED'` = **3**；lifecycle 分布 = DEPLOYED 3 / INBOUND 1 / RETIRED 1（当前 5 台设备**全部离线**）。
- 期望 / 实际：两处数字都「对」，但同名 KPI 差 2 且报表侧只有 hint「点击筛选离线」，不交代是**含未投放/已退役的全量** / 运营按工作台数找报表数对不上，会当成数据缺陷。
- 根因（两侧都是刻意的，不许「顺手对齐」）：`OpsWorkbenchQueryService.countOfflineDevices`（:631-637）显式 `filter(isDeployedDevice)`，注释写明「仅投放柜离线（避免 INBOUND 孤儿柜抬高『离线设备』）」；设备报表的列表本来就是全设备台账，其 KPI 沿用列表口径。
- 证据：`.tmp` 探针（API 两侧 total 3 vs 5）+ 上述 SQL；截图 `evidence/R03-reports-offline-kpi.png`
- 源码定位：`clients/admin-vue/src/views/reports/DeviceReportView.vue:436-440`（hint 未标口径）、`services/.../OpsWorkbenchQueryService.java:631-643`
- 联动影响：商户端工作台 `MerchantWorkbenchQueryService` 也各有一套离线计数口径（R9 点验，不在本条范围）。
- 关联：R1 线索 #2（本条即其转正）；先例＝R1「缺货建议 tab 恒空」按档 A 纯文案落地（`24e2a900`）。
- 修复记录：2026-10-31 fixed（`7ad59bbc` + 产物 `c4ccfde9`），**档 A 纯文案**（沿用 R1「缺货建议 tab」先例）。`deviceReportOfflineHint` 由「点击筛选离线」改为「全量口径（含未投放/已退役柜；工作台只算投放柜）· 点击筛选离线」。
  - 两侧计数逻辑**一行未动**：工作台只算 DEPLOYED 是 `OpsWorkbenchQueryService.countOfflineDevices:631-643` 注释写明的刻意设计（避免 INBOUND 孤儿柜抬高），报表列表本就是全设备台账 ⇒ 不许顺手对齐。
  - 活栈复验（真 Chrome 1600）：报表 KPI 文本渲染出全量口径说明且值=5，同一会话工作台=3；证据 `evidence/R03-reports-offline-kpi.png`；console 0 error。
  - 顺带印证 R2 删夹具后的新基线在报表页同步生效：累计营收 ¥5.00、订单 13。

### 线索 #2：设备报表 vs 工作台「离线设备」同词两口径（R1，未复现未领号）→ ✅ 已复现并转正为 MA-008（2026-10-31）

- **来源**（R01 会话，2026-10-31）：设备报表 `/reports` 显「离线设备 5」（全部设备），工作台 `/admin` 显「离线设备 3」（疑 deployed/投放柜口径）。同词不同数，运营无法判断哪个是真。
- **未复现原因**：dev 库设备行会随环境变动，两处取数函数体未逐行比对，按入账前提（实跑复现才入账）暂不领号。
- **下一步**：读两处取数函数体 + `device_info` 行数/lifecycle 分布，判定是缺陷还是口径差；若为口径差，按「汇总行=全集、子集列表」惯例加口径标注而非对齐（参考记忆规则：negation/口径类先查权威模型）。

### MA-020 后台把可逆还原的登录口令常驻 localStorage，且「记住」默认开启（P1，待拍板）

- 级别/状态：P1 / **待拍板**（2026-11-01，Qoder R8 批 2；值守期间按「实在需要我」处理，未动码）
- 端与页面：admin `/login`（+ 登出清态 `api/auth-storage.ts`）
- 发现人/日期/HEAD：Qoder R8 / 2026-11-01 / 发现锚 `911ebf4b`
- 复现步骤：
  1) 未登录打开 `/login`：勾选项唯一、默认「√ 记住账号密码」，口令框初始长度 0（A-1「不预填演示口令」仍成立）；
  2) 用 `13900000001` 登录一次；
  3) 读 `localStorage`：`admin_password` 存在，`atob(admin_password) === 本次登录口令`（探针判据 `true`），`admin_remember_password="1"`，`admin_phone` 同在；
  4) 登出（保持勾选）再读：值仍在（[auth-storage.ts:87-89](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/api/auth-storage.ts#L87-L89) 只在取消勾选即 `'0'` 时清）。
- 期望 / 实际：期望凭据不由页面脚本持有；实际是「任何 admin 源上的 XSS / 任何有 storage 权限的扩展 / 任何能开 devtools 的人」一步还原明文口令并可重放登录，而这个后台管提现、分账、改价。
- 根因/沿革：[LoginView.vue:291-309](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/views/LoginView.vue#L291-L309) 注释自认「base64 编码（便利性混淆，非安全边界）」。2026-10-22 的拍板主题是「两勾选合并为一个」，并未评估凭据在浏览器侧可逆留存这一层 ⇒ 本条不是推翻那次拍板，而是补上它没覆盖的风险面。
- 证据：`.tmp/r8-login.mjs` 探针输出（键名 + 判据，未打印口令）；截图 `evidence/R08_login-fresh.png`
- 三档与推荐：A 保持现状（须把注释/hint 改成「明文可逆存本机」，别再写「非安全边界」）；**B 推荐**——应用不再写 `admin_password`、进页即清历史值，勾选改名「记住账号」，口令交给浏览器密码管理器（体验等价、凭据进 OS 级保管、脚本读不到；改动面 LoginView 两处 + auth-storage 一处，无迁移无后端）；C 折中 sessionStorage（关标签即失，XSS 窗口仍在，代码比 B 多收益比 B 小）。
- 关联：rounds/R08-system.md 待拍板 #3；同页正向结论（勾选项、忘记密码弹窗、验证码）均无缺陷。

### MA-021 4 个前后端零引用的页面权限码以 ACTIVE 留在权限表＝幽灵菜单（P2，已修）

- 级别/状态：P2 / **fixed**（2026-11-01，Qoder R8 批 2；发现锚 `911ebf4b` → 修复锚 `55bac20f`）
- 端与页面：admin `/menus` 菜单管理、`/roles` 角色权限树（数据源同一张 `ops_permission`）
- 复现步骤：
  1) `select perm_code,status,perm_type from ops_permission where perm_code in ('ops:ad:campaign:list','ops:bigscreen:view','ops:sku-vision:list','ops:vision:list')` ⇒ 四行 `C / ACTIVE`；
  2) 全仓（排除 node_modules/dist/target/db-migration/docs）检索这四个码 ⇒ **0** 命中；`menu.ts` 里只剩解释性注释（:35、:188、:441），侧栏 56 个 `NAV_ITEMS.perm` 全集不含它们；
  3) 菜单管理页（`rbacPermissionsIncludeInactive`）把它们显示成启用中的页面；角色编辑树（`RoleManageView.vue:402` 取 `rbacPermissions` 即 ACTIVE 全集）可把它们勾给角色——当前分别有 2/4/3/2 个角色持有。
- 期望 / 实际：期望「勾了=能打开」；实际勾了既不放开页面也不放开接口。
- 根因：H51 那轮修「导航码 ≠ 后端校验码」时只改了前端一侧，被换下的行没处理（V154/V170/V144/V14-V109 种子原样 ACTIVE）。
- 修复记录：`V421__retire_phantom_page_perms.sql` 置 **RETIRED 不删行**（V386 `ops:session:upload` 先例：授权行与审计历史保留，`OpsPermissionMapper` 两条读查询带 `p.status='ACTIVE'` ⇒ 即刻失效）。
  - 实跑证据：dev 库事务内 dry-run（`BEGIN` → 迁移体 → `ROLLBACK`）得 `UPDATE 4`，四行 ACTIVE→RETIRED，同前缀 `ops:vision:export` 未误伤；迁移幂等（`AND status <> 'RETIRED'`），空库顺序无新依赖（纯 DML，不动历史迁移）。
  - **判据不同、刻意保留**（勿顺手对齐，已写进迁移头）：`ops:operlog`(290)/`ops:pricing:view`(718) 各带一个在用 F 子节点（`ops:operlog:export`、`ops:pricing:edit` 前后端均有实引用）；`ops:nav:*` 7 个 M 目录行按设计不进代码（侧栏分组由「有无可见子页」推导，`config/sidebar.ts:176-184`）；`ops:recognition-demo:view` 由两条门禁按 V138 显式豁免，去留属 R8 待拍板 #1。
  - 权限矩阵正向同轮全绿：56 个导航码逐个都有 ACTIVE C/M 行（0 缺失、0 INACTIVE）。
- 后续建议（本轮未做，避免脏树期加 CI 红面）：把「反向」判据做成静态门禁——新迁移里 INSERT 的 C/M 码必须出现在导航 perm / 后端字面量 / 被引用的 F 子节点的父行 / 后续 RETIRED 语句之一。当前 `check-menu-permissions`、`check-frontend-perm-codes` 只查正向。

### MA-022 dev 端内嵌看板恒 404，与同页「在线」判定自相矛盾（P3，已修）

- 级别/状态：P3 / **fixed**（2026-11-01，Qoder R8 批 2；发现锚 `911ebf4b` → 修复锚 `55bac20f`）
- 端与页面：admin `/devops` DevOps 中心、`/observability` 日志中心
- 复现步骤：vite dev（`:3100`）登录后开这两页 ⇒ console 分别 1 条、6 条 404，全落在 `/devops/grafana/d/ai-cabinet-*`；同页四张卡却显示「Grafana 看板 · 在线」。
- 根因：在线探针是**后端**发起（`DevOpsHubService` 打 `aicabinet.devops.grafana-url`，容器网络内可达），而 iframe 用**浏览器同源路径** `/devops/grafana/…`；vite `server.proxy` 原先只有 `/api` ⇒ dev 下该前缀由 vite 直接 404。
- 生产形态不是缺陷（已实证）：带 htpasswd 凭据 `curl http://127.0.0.1/devops/grafana/d/ai-cabinet-overview/…` → **200**（网关 Basic + Grafana 自身登录，`GF_AUTH_ANONYMOUS_ENABLED=false`、网关剥 `Authorization`，见 `infra/gateway/nginx-full.conf:167-192`）。
- 修复记录：[vite.config.ts:60-68](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/vite.config.ts#L60-L68) 增 `/devops` 代理，默认 `http://127.0.0.1:13000`（compose 已发布该端口，root_url 含子路径故无需 rewrite），可用 `VITE_DEV_DEVOPS_TARGET` 覆盖；不动生产链、不写凭据。
  - 实跑证据：另起临时 dev（`:3199`，不动在跑的 `:3100`）后 `GET /devops/grafana/api/health` → **200**（改前同路径 404）。
  - 该文件不参与打包（只影响 dev server），故本轮不需重建 admin 产物。
- 关联观察（未领号）：`/observability` 六个 iframe 一次全挂（`v-for` + `v-show`，刻意换切页即时性），代价是打开即并发 6 个看板查询——留 R10 横切面评估「首次访问才挂载」。

### MA-023 会员折扣在「商品卡」与「订单账」两层都不现身（P2，展示侧已修 / 记账侧待拍板）

- 级别/状态：P2 / **展示侧 fixed、记账侧待拍板**（2026-11-01，Qoder R9 批 1；发现锚 `55bac20f` → 修复锚 `a96b57c8`）
- 端与页面：consumer-mp `pages/index/index` 商品卡（原价/活动价）+ 三端订单优惠拆解（`cabinet_order.member_discount_cents`）
- 真值链（同一笔真单 `1793498414728440537903` 逐源钉）：
  `sku_catalog.price_cents` **350** →（会员 98 折，`MemberService.applyMemberPriceDiscount:377-395`）→ **343** →（活动 4 `SKU_REDUCE reduce_cents=100`）→ **243**；
  订单行 `unit_price_cents=243 / activity_discount_cents=100`，订单头 `total=243 / paid=243 / member_discount=0 / coupon_discount=0`；
  排除其它定价来源：`device_sku_price` 无该 SKU 行、`near_expiry_price_cents` 为空。
- 子缺陷 ①（展示侧，已修）：商品卡的 `priceCents` 下发的是**目录价**，而 `activityPriceCents` 按**会员后价**再减 ⇒ 卡面「原价 ¥3.50 / ¥2.43 罐 / 活动价」三者减不平（差 0.07 无处解释）；非活动商品同样「显示 3.50、结算 3.43」。
  - 修复：`DeviceCatalogService:97-110` 的 `priceCents` 改为本用户无活动时的应付单价（会员价；`NearbyDeviceService` 走 `listProducts(deviceId)` → `userId=null` → 原价透传，不受影响）。
  - 单测：`DeviceCatalogServiceTest.listProducts_priceCentsIsWhatThisUserPaysWithoutActivity` 钉 `priceCents=343`、`activityPriceCents=243`、差额=100；整类 **8/0/0**（surefire `tests="8" failures="0" errors="0"` 实取）。
  - 🔴 运行时复验未做（dev 容器仍是旧 jar）——不谎报已上线；重建后重跑 `.tmp/r9-consumer-walk.cjs` 看卡面三数即可。
- 子缺陷 ②（记账侧，待拍板）：`cabinet_order.member_discount_cents` **全仓无写入者**（`grep setMemberDiscountCents` 在 `main/java` 0 命中），恒 0；而 `DataConsistencyService:1557-1580` 的对账式 `lineSum − coupon − member = payableFromLines` 正在读它 ⇒ 会员折扣在账面上完全隐身，商户分账与按比例退款读的是账面金额，拿不到这一层。
  - 为什么不当轮修：这是**资金字段语义 + 巡检规则联动**（一旦补值，PAID_GROSS_MISMATCH / ORDER_AMOUNT 两条规则可能从「平」变「不平」），按本程序既定边界与记忆规则（资金须数字实证）应单独一批做：结算写入该列 → 巡检口径复核 → 三端明细加「会员优惠」行 → 非零钉测 + `money-recheck.sql` 四层互证 + 留痕。
- 证据：卡面三数取自 automator 文本快照（`pages/index/index` 渲染节点：`活动价 | 原价 ¥3.50 | ¥2.43/罐`）+ 上述 SQL/API 逐源取值，见 rounds/R09-miniprograms.md 批 1 表。
  🔴 取证缺口：`evidence/R09/pages_index_index.png` 存下来的是**落地页**（同路由被第二次访问覆盖，购物态未留图）——不拿它对价格下结论，重建后的复验轮补图。
- 关联：CB-082 v1 的活动价展示链（本轮把「原价可减出实付」补为硬判据）；R8 MA-020 同属「三端明细可解释性」族。


