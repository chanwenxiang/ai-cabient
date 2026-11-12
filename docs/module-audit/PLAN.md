# 11 轮审计计划（三端分模块）

> **快照锚点**：HEAD `e5c2ff52`（2026-10-31 重拉，见下）；清单来源（全部是代码，非文档）：
> `clients/admin-vue/src/config/menu.ts`、`clients/admin-vue/src/router/index.ts`、`clients/consumer-mp/src/pages.json`、`clients/merchant-mp/src/pages.json`。
> 🔴 **Step 0 对账已脚本化**：`node docs/module-audit/step0-inventory.mjs` —— 输出各模块页面数（按 `group` 分桶，去重）、菜单↔路由差集、无权限码页、两端 mp 页数。**开轮必跑并把输出贴进轮报告**，禁止手算（手算不可比：menu.ts 里 `path:` 字面出现 80 次，去重后才 77+ 兼容重定向 3）。
> ⚠️ **本表数字只是起点，不是真值**：每轮开轮以脚本重拉结果为准并更新本表。
> 本次重拉的两处漂移（证明「不信旧文档」这条铁律有必要）：
> - **R4 仓储 7 → 8**：`/warehouse/reconciliation` 已登记（menu.ts:251，注释标 D28-B15 修复），线索 #1 已收口，不再是需要复核的死路由；
> - **R9 merchant-mp 34 → 35**：新增 `pages/prep-list`（备货单，与 `prep-generate` 分工见 PROJECT_KNOWLEDGE 近期条目）。
> ~~快照日工作区有 309 个未提交文件~~：2026-10-31 起主树已落在干净 HEAD 上（并发会话改为提交批次制），此提示作废。

## 轮次总览

| 轮 | 主题 | 后台页面（快照数） | 状态 | 轮报告 |
|----|------|--------------------|------|--------|
| R0 | 环境基线（一次性前置，建议新增） | — | ✅ 通过（2026-10-31，HEAD `cb904b71`，见轮报告） | rounds/R00-baseline.md |
| R1 | 概览 | 9（Step 0 重拉一致） | ✅ 完成（2026-10-31，锚 `c3e0e44d`；MA-001~003 已修已推，线索 #2 待领号） | rounds/R01-overview.md |
| R2 | 交易履约 | 4（Step 0 重拉一致） | ✅ 完成（2026-10-31，锚 `9637a884`；MA-006 P2 轮内修、MA-007 P1 轮末修，3 条待拍板） | rounds/R02-trade.md |
| R3 | 设备商品 | 10 + 设备详情动态页（分 2 批） | ✅ 完成（2026-10-31，锚 `7cda6af7`；MA-008~011 全修；批 2 商品面零新缺陷） | rounds/R03-devices.md |
| R4 | 仓储 | 8（对账单页已登记） + `/print` 隐藏页（分 2 批） | ✅ 完成（2026-10-31；MA-013 打印页居中轮内修；其余零缺陷） | rounds/R04-warehouse.md |
| R5 | 履约仓储 | 4（Step 0 重拉一致） | ✅ 完成（2026-10-31，锚 `bc760fab`；零缺陷，两条文案待拍板） | rounds/R05-fulfillment.md |
| R6 | 财务商户 | 16（分 6a-1 / 6a-2 / 6b 三批） | ✅ 完成（2026-10-31；MA-014/015 修完、MA-016 拍板 wontfix；新增资金对账脚本与 4 条费率钉测） | rounds/R06-finance.md |
| R7 | 增长风控 | 11（分 7a / 7b） | ✅ 完成（2026-10-31；MA-017 修完、MA-018 门禁上线+清理待拍板） | rounds/R07-growth.md |
| R8 | 系统 | 15 + 登录/错误页 + 权限矩阵专项 | ✅ 完成（2026-11-01：批 1 MA-019、批 2 MA-020/021/022；MA-020 属「实在需要我」只登记未动码） | rounds/R08-system.md |
| R9 | 双小程序为主 → 后台联动点验 | consumer-mp 23 页 / merchant-mp 35 页 | ✅ **两端走完**（consumer 22/23 页，2026-11-01 批 1+2+3，`announcements/detail` 无公告数据可看；merchant-mp 35/35，2026-11-02 六批 M1–M6）。战果：MA-023①/024~028 + MA-029（方案 A，含 ② 的 5 处减项）+ MA-030/033 轮内修；MA-031/032/034/035 领号 open 归「轮末文案批」；未挣三条（公告内容腿 / 仓拓扑退化 / 提现确认框披露）见轮报告待拍板 #11 | rounds/R09-miniprograms.md |
| R10 | 三端横切审计 | 同一数据三端口径/权限/一致性 | ✅ 完成（2026-11-02，锚 `3d0e999e`）：字段口径三维度单源、权限闭环四方向全 0、回归 103 例 + 2 IT 绿；`PermClosureSqlIT` 已落并登记守卫（含负向自测）、8 处 toLocale* 已判完（4 处补时区已修） | rounds/R10-crosscut.md |
| R11 | 业务流转测试（造数全链路） | 三端全链路剧本 | ✅ **完成**（2026-11-02）：段 1 会员单六层互证；段 2 全跑——B1 跨仓调拨 create/ship/receive 全 200 且两仓合计 45 不变（MA-037/038 修完活栈实证）、B2 退款 REFUNDED/243 对平 + 提现 MOCK/PAID 冻结-费6‰-打款三行对平、B3 公告造数+真端读面、B4 三端同读六个数一致 | rounds/R11-e2e-flows.md |

## R0 环境基线（一次性前置）

产出：栈起来（docker compose 各服务健康）、三端可登录（超管/商户/补货员三账号实测）、admin 产物与源码一致（重建一次）、开发者工具两个小程序可编译预览、种子数据在位、核准/订正 README「环境速查」每一条。**没有 R0，后续每轮都要重付环境成本，且多 AI 无法互相复现问题。**

## R1–R8 各模块页面清单（menu.ts 快照）

**R1 概览（9）**：`/dashboard` 运营工作台 · `/big-screen` 运营大屏（全屏路由）· `/analytics` 数据分析 · `/footfall` 客流坪效 · `/reports` 设备报表 · `/finance` 财务毛利 · `/sales-reports` 销售报表 · `/stock-health` 库存健康 · `/user-analysis` 用户分析
联动线索：销售/用户分析类数据与 consumer-mp 订单、营销活动核销口径对照。

**R2 交易履约（4）**：`/orders` 订单管理 · `/sessions` 开门记录 · `/disputes` 争议审核 · `/exceptions` 异常中心
联动：consumer-mp `orders / order-detail / dispute`；merchant-mp `alerts`。

**R3 设备商品（10 + 详情页）**：`/device-ops` 设备运维 · `/devices` 设备管理 · `/devices/:id` 设备详情 · `/device-map` 投放地图 · `/device-kpi` 设备可用性 · `/repair-tickets` 维修工单 · `/skus` 商品管理 · `/sku-review` 选品诊断 · `/sku-vision` 识别入驻 · `/learning-library` 商品学习库 · `/vision-mappings` 识别映射
联动：merchant-mp `devices / device-detail / pricing`；学习库/识别映射涉及将邑链路。

**R4 仓储（8 + 1）**：`/warehouse/master` 仓库与库位 · `/warehouse/procurement` 采购与供应商 · `/warehouse/claims` 待索赔台账 · `/warehouse/expiry-alerts` 近效期预警 · `/warehouse/monthly-close-sheets` 月结单台账 · `/warehouse/stock` 库存与盘点 · `/warehouse/fulfillment` 领货与调拨 · `/warehouse/reconciliation` 供应商对账单（**线索 #1 已收口**：V326 上线漏登记侧栏，D28-B15 补登记并挂 `ops:warehouse:list`，页内「生成对账单」由后端码 `ops:procurement:list` 收口）；隐藏：`/print` 打印单据；`/warehouse` 旧 tab 入口为兼容重定向。
联动：merchant-mp `purchase / self-replenish / warehouse-stocktake / device-stocktake / tally-records / prep-generate`。
🔴 业务判据提醒：分仓是补货员自己的仓（本部仓只对采购）；补货=自提自送、无「分仓→柜」在途段（依据 2026-10-29 用户拍板口径）。

**R5 履约仓储（4）**：`/replenishment` 补货调度 · `/ota` 固件版本 · `/sla` 服务时限监控 · `/replenishment-staff` 补货员效率
联动：merchant-mp `replenishment / self-replenish`。

**R6 财务商户（16，建议 6a/6b 两批）**：`/fund-bills` 资金账单 · `/platform-fund` 平台资金池 · `/device-rental` 设备租赁 · `/merchants` 商户与分账 · `/merchant-onboarding` 进件工作台 · `/line-managers` 线长钱包 · `/line-routes` 线路与提成 · `/machine-payee-bills` 机器账单（场地方）· `/merchant-fee-ledgers` 商户费用台账 · `/merchant-withdraw` 商户提现 · `/reconciliation` 对账 · `/consistency` 数据一致性 · `/recharges` 充值管理 · `/balance-refunds` 余额退款 · `/invoices` 开票申请 · `/users` 用户余额
联动：merchant-mp `settlements / bills / wallet / line-wallet / splits / business / tax`；consumer-mp `recharge / balance`。资金敏感：只读优先、写操作只碰 `AUDIT-` 造数、支付走 dev mock。

**R7 增长风控（11）**：`/phone-verify` 手机验证 · `/risk` 风控 · `/promotions` 营销活动 · `/coupons` 优惠券 · `/ad-assets` 素材库 · `/ad-campaigns` 投放计划 · `/points-redeem` 积分兑换管理 · `/member-levels` 会员等级规则 · `/marketing-roi` 活动效果分析 · `/notifications` 消息记录 · `/feedback` 用户反馈
联动：consumer-mp `marketing / member / points / coupons / feedback / messages`；双端 `announcements`。

**R8 系统（15 + 专项）**：`/operators` 运营账号 · `/roles` 角色管理 · `/departments` 部门管理 · `/approvals` 审批流配置 · `/menus` 菜单管理 · `/dicts` 字典管理 · `/system-configs` 参数配置 · `/alert-rules` 告警规则 · `/scheduled-tasks` 定时任务 · `/org-sites` 组织与点位 · `/announcements` 通知公告 · `/audit` 审计日志 · `/devops` DevOps 中心 · `/observability` 日志中心 · `/profile` 个人中心；外加 `/login`、`/forbidden`、`/not-found`。
专项：**角色×权限码×页面矩阵**（超管全量 + 每模块抽 1 个受限角色实测可见性/403）在此轮收口；此前各轮先用超管测全功能、受限角色仅快测该模块。

## R9 双小程序为主（快照：consumer 23 页 / merchant 35 页，2026-10-31 重拉）

consumer-mp：主页 `index/orders/mine/login/verify/realname/result` + 分包 `order-detail, dispute, recharge, balance, marketing, member, points(2), coupons, messages, announcements(2), help, policy, report, feedback`。
merchant-mp：主页 `login/home/devices/alerts/mine` + 分包 `device-detail, pricing, replenishment, ops-config, self-replenish, warehouse-stocktake, device-stocktake, tally-records, password-change, purchase, business, tax, cabinet-reports, notify, settlements, bills, disputes, orders, order-detail, video, messages, announcements(2), team, splits, wallet, line-wallet, policy, prep-generate, prep-list（备货单，10-31 新增 ⇒ 35 页）`。
方法：以小程序页面为审计主体（按钮/交互/样式/文案），每个写面动作到后台点验数据落点正确；R1–R8 已覆盖的后台读面只抽查不重测。

## R10 三端横切

不做「三端重测一遍」，只收**三类可判定**的东西（其余靠 R1–R9 各自轮内已测）：

1. **同一端点三端字段口径**：挑 R1–R9 里被两端以上消费的接口，逐字段对「金额分/元、状态名、时间时区」。判据＝同一请求体在三端的展示值可互相推导（不是「看着像」）。
2. **权限闭环对账（可脚本化）**：`rbac/me/nav` ACTIVE 菜单表 ↔ `menu.ts` 的 `perm` ↔ 后端 `@RequiresPermissions` 码，三方并集/差集一次跑完。既有 `check:menu-permissions`／`check:frontend-perm-codes` 只查「前端码在后端存在」，**没查反向的「后端有码但导航表无行 ⇒ isNavMenuActive 恒 false 恒 403」**——D28-B15 就是这么栽的，本条把这类一次性挖干净。
3. **回归 [regression-index.md](regression-index.md)**：状态为「无」的行必须补成能失败的用例；各轮 `open/confirmed` 逐条复验是否已被后续会话修掉（🔴 台账标 open 的项可能已被后端过滤成 no-op，先读取数函数体 + 行数判可达，再下结论）。

## R11 业务流转（造数全链路）

用 `seeds/` 的 `AUDIT-` 前缀造数脚本跑端到端剧本：采购入库 → 分仓 → 备货/补货任务 → 出库领货 → 上柜/领而未上 → 消费下单支付（dev mock）→ 争议/退款 → 对账/月结 → 提现。每段剧本先写进轮报告再执行，金额逐段对账（台账↔批次↔流水三层一起对）。

## 每轮工作法（固定 8 步）

1. **开轮**：规则 2 三件事（看 git 变化 / 回读本目录 / 重拉该模块页面清单）→ 建 `rounds/Rxx-*.md`，记录 HEAD 与环境。重拉清单＝跑 `node docs/module-audit/step0-inventory.mjs`，输出原样贴进轮报告 Step 0 小节；表内数字与脚本输出不一致时**以脚本为准并更新本表**。
   🔴 **>8 页的模块强制分批**（R3 10／R6 16／R7 11／R8 15，R1 实测 9 页就吃满一整轮会话）：每批 ≤6 页，批末必须「轮报告落盘 + 已修缺陷提交」，让接手的 AI 能从明确边界接手，而不是从半页状态猜。
2. **页面×选项卡清单**：从视图源码列出该页全部选项卡/子面板（不只是 menu 项），逐项挂 checklist。
3. **逐页审计**：按 [templates/page-checklist.md](templates/page-checklist.md) 九个维度过；每页测完立即写进轮报告。
4. **问题入账**：当场在 ISSUES.md 领 MA 编号、写全字段；证据（截图/请求）落 `evidence/`。
5. **联动点验**：该页涉及小程序的，到对应 mp 页面验证数据/状态一致（开发者工具即可，真机项进「需用户配合」清单）。
6. **轮末收口**：汇总发现、P0–P3 计数、需拍板/需真机清单、修复批次建议；更新 PLAN 状态列。
7. **修复**（用户 2026-10-31 订正，替代原「轮内只记录不修」）：
   - **P2/P3 轮内即修**——测到就修、修完当场回归（钉测 + 活栈实测），不必攒到轮末；
   - **P0/P1 轮末统一批次修**——走正常开发流程（Skill `ai-cabinet-dev-test` + 门禁 + `pre-push-ci-preflight`）；🔴 **例外**：若该 P0 会让后续页面/轮次的数据被继续污染或不可复现（资损累计、写坏共享库），立即上报用户并**先停轮**，不带病往下测；
   - 每条 MA 必须记**双锚点**：`发现锚 <sha>`（复现时的 HEAD）+ `修复锚 <sha>`（合入批次的提交），PLAN 状态列记批次号。轮内边测边修会让「同一轮前后两次观测」不可比，双锚就是补救。
   - 🔴 **P0/P1 修完必须沉淀回归用例**，并在 [regression-index.md](regression-index.md) 登记一行（MA-xxx ↔ 用例文件 ↔ 覆盖维度）。admin 用 Playwright（配方见 `docs/module-audit` 之外的 `.tmp` 登录脚本 + Skill `ai-cabinet-dev-test`），mp 用 automator。11 轮下来这张网就是 R10「回归各轮问题」的执行入口，没有它 R10 只能靠人眼重测。
8. **轮末门禁化收口**：把本轮发现的问题过一遍「能不能变成静态/运行约束」，能则登记为门禁候选（见下节）；不能的降级为模板必测项。目的：同类缺陷在第 N 轮只出现一次，而不是每轮都靠人眼抓。

## 已拍板业务口径（每轮 Step 0 必读；🔴 审计中不得「顺手对齐」）

> 这些是用户拍板 + 竞品对照（CB-xxx）后的**定案**。历史上多次因为看着「不一致」就顺手统一，把刻意保留的差异改坏。判「缺陷是不是缺陷」之前先查本表；本表以代码符号为锚，锚失效（改名/删除）才允许动。

| 口径 | 唯一源/锚点 | 拍板依据 |
|------|-------------|----------|
| 商品「在架」 | `support/SkuOnShelf.isOnShelf`（19 处副本已收口） | 2026-10-30 拍板；热销榜看全量含下架，洞察/缺货/低库存/补货建议只看 ACTIVE |
| 库存告警/缺货 | `support/SkuStockLevel.of(qty, lowThreshold)` | CB-076（单源，admin 补货行 `shortageSlots` 已改「待补商品数」） |
| 开门柜 vs 货道柜 | `DeviceSlotService.isDoorCabinet`＝**将邑 BOUND**，不是 `device_type` | 2026-10-28 拍板（字典当前只有 AI_CABINET_V1，无法区分柜型）；补货行恒 `slotId=null` |
| 分仓→柜无「在途」段 | 领货与调拨页标题口径（menu.ts `/warehouse/fulfillment` 注释） | CB-074：补货员自提自送 |
| 提现手续费 | 用 **bps 整数**存储（默认 60＝6‰），可按商户覆盖 | 2026-10-16 拍板；`FundBillService` 注释说明为何不用 double |
| 平台分账比例 | 默认 **0** | V382 `merchant_platform_rate_default_zero`；恢复抽成需先拿微信实证 |
| 下架 SKU 的柜×商品台账行 | **不随动清**，admin 端「可见可清」 | CB-080：下架是运营动作、出柜是实物动作，不级联 |
| 用户分析活跃/新增 | **固定窗口**（7d/30d 各自独立，不随 `days` 漂移） | CB-081 / MA-001（友盟+、AppsFlyer 同口径） |
| 同字面的其他 ACTIVE 判据 | 用户/商户/仓/供应商/优惠券的 `status='ACTIVE'` **不许**与商品在架口径对齐 | 2026-10-31 拍板，语义不同 |

## 轮末门禁化候选台账（工作法第 8 步的落点）

> 规矩：**只给「已在当前 HEAD 复现过的缺陷类」上门禁**（沿用 `docs/engineering` 既有纪律），且上线前必须先测误报率——门禁红一次就没人信。既有 97 个 `check-*.mjs` 已覆盖「前端权限码 → 后端存在性」双向（`check:menu-permissions`／`check:frontend-perm-codes`），下面列的是**尚未覆盖**的类。

| 候选门禁 | 缺陷类（MA 锚） | 判据（静态可判？） | 状态 |
|----------|-----------------|--------------------|------|
| check:route-nav-registered（候选名，未落地）：admin router 里的页面路由必须在 `menu.ts` 登记（或进显式白名单：`/print`、`/login`、`/forbidden`、`/not-found`、兼容重定向） | 线索 #1 / D28-B15（对账单页路由存在未登记 ⇒ fail-closed 恒 403，整页死路） | ✅ 可判。`step0-inventory.mjs` 的「路由有、菜单未登记」段就是现成实现，改成断言即可 | **建议先做**（R4 之前） |
| check:fixed-window-kpi（候选名，未落地）：DTO 同现 `xxx7d`+`xxx30d` 时，对应服务必须同时出现 `minusDays(6)` 与 `minusDays(29)` 两个字面窗口，禁止只按入参 `days` 单窗口计数 | MA-001（`days=7` 时 30d 口径被吞成 0、`days=90` 时被虚增） | ⚠️ 半可判：先只对 `UserBehavior*` 这一族开，误报面小 | R6/R7 前评估 |
| check:truncation-constant（候选名，未落地）：服务里出现 `subList(0, N)` 必须用**带注释的命名常量**，且返回 DTO 必须有 `xxxTotal` 全量字段 | MA-002（沉睡名单静默截断 20 条、KPI 显示 22） | ✅ 可判（正则 + DTO 字段名） | R6 前评估 |
| check:admin-copy-window-consistency（候选名，未落地）：弹窗/说明文案里写「近 {{ x }} 天」「最多 N 人」时，N 必须取后端字段而非本地参数 | MA-003（弹窗显「20 人（近 7 天沉睡名单）」，实际口径是 30-90 天全量） | ❌ 静态易误报 ⇒ **降级为 page-checklist 必测项**（文案口径 × 数据窗口一致性） | 已入模板 |
| ~~`check:list-filter-server-side`~~ **已上线为 `check:list-filter-server-side`（2026-10-31）**：扫 admin 视图 `fetchPage` 方法体，对「本页行」`.filter(` ⇒ 红（棘轮白名单只减不增 + 锚点 ≥15 个 fetchPage + 自测 9 条） | MA-007 / MA-011 / MA-012（三次同类实证） | ✅ 可判 | ✅ **已落地并接入聚合链**（首扫 4 处：1 真缺陷 + 3 白名单），见 `scripts/check-list-filter-server-side.mjs` |
| check:dict-label-render（候选名，已落地为 `check:no-inline-dict-display`）：admin 视图不得把字典 value 裸绑到展示位（品类/状态/类型列必须过翻译） | R1 销售报表/大屏「裸码 1」 | ✅ **已落地为 `check:no-inline-dict-display`**（模板内 `=== 'ENUM' ? '中文'` 棘轮） | ✅ 2026-10-31 上线 |
| `check:no-inline-money-math`：视图内 `/100` 分→元手算（改用 `centsToYuanDigits`） | lessons #304/#306；R6 资金轮 | ✅ 已落地（28 文件/113 处存量棘轮，只减不增） | ✅ 2026-10-31 上线 |



## R12–R17 第二轮全量审计（2026-11-02 用户拍板，换镜头全量，未开工）

> 用户拍板记录（2026-11-02 晚）：①方案选「换镜头全量」而非照抄 R1–R11 重走——页面 100% 覆盖不变，但每页只测第一轮没测的面；②发现一律**领 MA 号入台账（标 open）**，修复由其它 AI 领走（偏离「P2/P3 轮内即修」默认节奏，用户明示）；③子代理并发升到 **4 个/批**（用户拍板；若报 concurrency limit 回落 2/批）。

- **前置任务 T0**：✅ 完成（2026-11-02 晚，由接手修复会话代领：六轨 25 条=N1~N25 → MA-039~064 一一对应且多已 fixed 带活栈实证，另自加 MA-040/065/066/067 四条；本会话对账核实无缺号。收件箱留档 `T0-INBOX-2026-11-02.md`，映射见其头部补记）
- **执行顺序**：R12 → R17 → R13 → R14 → R15 → R16（先钱后面）。每批 = 子代理并行 + 主会话汇总 + 领号落盘；全部进度落本目录，跨会话断点续跑。

| 轮 | 镜头 | 覆盖面与判据 | 批次 | 状态 |
|----|------|--------------|------|------|
| R12 | 后端写面全量（静态） | 95 个 Controller（实测数）全部 POST/PUT/DELETE 四查：权限注解 / 入参校验 400 / 幂等与重复提交 / 审计留痕；产出写面矩阵；五批分组表见 rounds/R12-write-surface.md | 5 | ✅ **全轮完成**（2026-11-03 凌晨，五批 95 Controller / 395 写端点，发现 MA-068~125 共 58 条：P2×7 / P3×51，无 P0/P1；分组表与各批战果见 rounds/R12-write-surface.md） |
| R17 | 资金剧本运行时 | R11 剧本扩展：会员×活动×券 8 组合 × 全额/部分退 × 分账/钱包/券返还三面；提现边界（0/负/超余额/并发同 requestNo）；余额+微信组合支付 | 2–3 | ✅ **全轮完成**（批 1：会员链复验+提现边界+并发幂等 MA-126；批 2（用户授权矩阵 E2E）：8 组合矩阵全绿+全额退/部分退三面+补扣链腿 → 新发现 MA-147 全额退不还券，测试 `SettlementComboMatrixE2ETest` 已入仓随 CI；批 3 剩余=余额+微信组合支付（需双渠道 mock 环境，留待下次）；🔴 **2026-11-05 批 3 收口订正**：所谓「需双渠道 mock 环境」前提不成立——现成的 `OrderPaymentChargeInvariantTest` 内存流水 harness 即可造「钱包已冲抵 + 渠道补缴」前置态，一条用例当场抓出真缺陷 **MA-160（P1，渠道腿按应付全额重复扣）**，已修 `d7b37278`；退款拆分缺口另立 **MA-161（open 待拍板）** |
| R13 | admin 76 页写面实测 | Playwright 真实登录态逐页 + 写面形态验证 + 安全写操作端到端 | 13 | ✅ **完成**（2026-11-03：79 路由读面 100% 干净（4 ⚠️ 甄别为误报）+ 调拨弹窗 MA-041/058 修复复验 ✓ + 字典/公告/部门写操作端到端（零残留）；无新领号；见 rounds/R13-admin-write.md） |
| R14 | consumer-mp 23 页写面实测 | automator 真机环境逐页遍历 + 渲染/登录态检查 | 4 | ✅ **完成**（2026-11-03：23/23 页遍历，22 OK + login 页守卫按设计；修复本地 API 地址配置（.199→localhost）；资金写链已由 R17 矩阵 E2E 覆盖；元素交互协议兼容问题留痕；见 rounds/R14-consumer-write.md） |
| R15 | merchant-mp 35 页写面实测 | automator 登录态逐页遍历（用户协作登录） | 6 | ✅ **完成**（2026-11-03：34/34 页 OK、数据真实加载；六批样式提交整批复验；元素交互协议兼容问题留痕；见 rounds/R15-merchant-write.md） |
| R16 | 跨端同数全量 | 枚举所有 ≥2 端消费的实体（订单/SKU/库存/钱包/任务/告警/券…），每实体造真数、三端逐字段比对（系统枚举非抽样） | 2 | ✅ **全轮完成**（静态前置：MA-127~146；运行时订单实体：R17 批 2 矩阵每组合 consumer+merchant 读面五字段逐一等于落库，E2E 断言固化；其余实体运行时比对由 R11 B4 三端同读+regression-index 覆盖） |

- **边界声明**：本轮全部结论不覆盖「运行时真机」「整体测试缺口盘点」「性能/无障碍/i18n」；静态发现须活栈复现后才算 confirmed。
- **T0 收件箱**：六轨增量审计 25 条发现已落盘 `T0-INBOX-2026-11-02.md`（从 MA-039 起领号，逐条先对 HEAD 复核——接手 AI 修复在途）。所有无人值守驱动器（定时/闲时）以该文件+本表状态列为唯一状态源。
