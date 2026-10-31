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

### 线索 #2：设备报表 vs 工作台「离线设备」同词两口径（R1，未复现未领号）

- **来源**（R01 会话，2026-10-31）：设备报表 `/reports` 显「离线设备 5」（全部设备），工作台 `/admin` 显「离线设备 3」（疑 deployed/投放柜口径）。同词不同数，运营无法判断哪个是真。
- **未复现原因**：dev 库设备行会随环境变动，两处取数函数体未逐行比对，按入账前提（实跑复现才入账）暂不领号。
- **下一步**：读两处取数函数体 + `device_info` 行数/lifecycle 分布，判定是缺陷还是口径差；若为口径差，按「汇总行=全集、子集列表」惯例加口径标注而非对齐（参考记忆规则：negation/口径类先查权威模型）。
