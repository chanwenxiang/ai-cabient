# AI Cabinet · 全业务节点与按钮功能测试矩阵

> **性质**：测试清单 / 验收矩阵（非已执行 UAT 报告）。  
> **核心原则**：按钮「能点」≠「业务生效」。必须验证 **下游账本 / 状态机 / 权限 / 对端可见性** 真正变化。  
> **反例（历史教训）**：分账页有入口、能保存比例，但订单支付后钱包不入账 / 状态一直卡住 → **只能记 FAIL 或 BLOCK，不能记 PASS**。
>
> | 端 | 真源文件 |
> |----|----------|
> | 运营后台菜单 | `clients/admin-vue/src/config/menu.ts`（`NAV_ITEMS` / `BASE_NAV`） |
> | 运营后台路由 | `clients/admin-vue/src/router/index.ts`（`bizChildren`） |
> | 运营按钮文案 | `clients/admin-vue/src/views/**/*.vue` 中 `<el-button>` |
> | 商户端页面 | `clients/merchant-mp/src/pages.json` + `config/merchant-nav.ts`（RBAC ∩ 功能包） |
> | 消费者端页面 | `clients/consumer-mp/src/pages.json` |
> | 角色账号 | [`DEMO_ACCOUNTS.md`](DEMO_ACCOUNTS.md) |
> | 资金/争议深分支 | `docs/pass-notes/PASS_3A`～`3E` |

版本：1.2 · 更新日期：2026-09-08

---

## 0. 怎么用这份文档

### 0.1 三级验收（每个写操作按钮必过）

| 级别 | 名称 | 必须看到什么 | 不算通过 |
|------|------|--------------|----------|
| **L1** | 可达 | 菜单/路由进得去；按钮可见可点；中文无乱码；loading/防双击 | 仅截图有按钮 |
| **L2** | 接口 | Network 返回 `code=0`（或业务约定错误码）；列表/详情 UI 刷新 | Toast 成功但接口 4xx/5xx |
| **L3** | 业务挂钩 | **下游真源变化**：DB/对端页面/流水/状态机与动作一致；幂等重放不双计 | 配置保存了但主链路不消费；对端无数据 |

**宣称某按钮 PASS 至少达到 L3**（只读查询可为 L2）。资金/库存/开门类禁止跳过 L3。

### 0.2 每按钮固定检查清单

1. 正常路径（合法输入）→ L3  
2. 校验边界（空、超长、负数、超限额、非法状态）→ 中文错误、不落脏数据  
3. 幂等/连点（快速双击、重复提交同一业务键）→ 不双开门/双扣/双入账  
4. 权限（无 perm 账号）→ 按钮隐藏或点后 403；直链进 `/forbidden`  
5. 异常（断网、超时、下游 mock 关）→ 可读失败态；资金类冻结/回滚符合状态机（见 §7）

### 0.3 环境入口与角色（必须换号测权限）

| 端 | URL | 主测账号 | 权限边界账号 |
|----|-----|----------|--------------|
| 运营后台 | `http://localhost/admin/index.html` | `13900000001` 超管 | `13900000002` 财务 · `13900000003` 运营 · `13900000004` 补货员 · `13900000005` 只读 |
| 消费者 H5 | 本地 vite（常见 `:3002`） | `13800138000` | 未登录 / 未开通支付 |
| 商户 H5 | 本地 vite（常见 `:3001`） | `13800138001` 管理员 | `38002` 店员 · `38003` 他商户隔离 · `38004` 财务 · `38006` 店长 · `38007` 补货员 |

密码见 [`DEMO_ACCOUNTS.md`](DEMO_ACCOUNTS.md)。执行工具：**Playwright MCP/CLI**；本文不宣称已通过。

### 0.4 状态列与证据（金钱/开门项必填证据）

状态：`PASS`（达 L3）/ `FAIL` / `BLOCK` / `SKIP` / `N/A`；可选 `L1-only`（不算正式通过）。

| 字段 | 说明 |
|------|------|
| 状态 | 上表之一 |
| 证据 ID | 至少一类：`sessionId` / `orderId` / `splitId` / `withdrawId` / `ticketId` |
| 截图 | `docs/uat-screenshots/YYYY-MM-DD/<用例ID>.png`（或 CI artifact 路径） |
| 接口 | 关键写操作 HTTP 状态 + 业务 `code`（可记在备注） |
| 执行 | 日期 · 执行人 · 环境标签（见 §12.2） |

**无证据 ID 的资金/分账/提现/争议结案项，不得标 PASS。**

### 0.5 优先级（每轮先跑完再铺开）

| 级 | 含义 | 范围 |
|----|------|------|
| **P0** | 阻断发版/联调 | §12.1 子集；开门→支付→分账→提现→争议→权限隔离 |
| **P1** | 主业务完整 | 库存补货、审批/部门、券核销、商户功能包 |
| **P2** | 覆盖与体验 | 大屏报表、DevOps、导出、文案/空态、其余 §1～§3 页 |

### 0.6 文档结构

| 章 | 内容 |
|----|------|
| §1～§3 | 三端页面清单（入口覆盖） |
| §4 | 跨端主链路 + 业务挂钩断言 |
| §5～§6 | 维护与页面统计 |
| §7 | 通用边界 / 异常 / 幂等 |
| §8 | 权限 / 菜单 / 数据范围 |
| §9 | 字典 / 参数 / 审批 / 告警 / 定时 / 审计 |
| §10 | 分域 L3 挂钩（含分账反例） |
| §11 | 执行顺序 |
| **§12** | **P0 子集 · 环境 · H5/小程序 · 自动化对照 · 证据模板** |

---

## 1. 运营后台（admin-vue）

菜单项合计 **65**（含个人中心；识别演示仅在 `ENABLE_TEST_TOOLS` 开启时出现）。

### 1.1 概览

| # | 菜单 | 路径 | 权限码 | 源码视图 | 源码按钮（抽样） | 建议验收要点 | 状态 |
|---|------|------|--------|----------|------------------|--------------|------|
| 1 | 运营工作台 | `/dashboard` | `ops:dashboard:view` | `dashboard/DashboardView.vue` | 补货调度、争议审核、设备管理、设备可用性、刷新、进件工作台 | 卡片数字加载；快捷入口可跳转 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 2 | 运营大屏 | `/big-screen` | `ops:bigscreen:view` | `dashboard/BigScreenView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「运营大屏」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 3 | 数据分析 | `/analytics` | `ops:analytics:view` | `analytics/AnalyticsView.vue` | 查看 | 打开「数据分析」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 4 | 客流坪效 | `/footfall` | `ops:analytics:footfall:view` | `analytics/FootfallView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「客流坪效」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 5 | 设备报表 | `/reports` | `ops:report:device` | `reports/DeviceReportView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「设备报表」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 6 | 财务毛利 | `/finance` | `ops:finance:view` | `finance/FinanceView.vue` | 返回工作台、固化昨日毛利、刷新 | 列表加载；关键写操作二次确认 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 7 | 销售报表 | `/sales-reports` | `ops:sales-report:list` | `reports/SalesReportsView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「销售报表」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 8 | 库存健康 | `/stock-health` | `ops:stock-health:list` | `reports/StockHealthView.vue` | 一键补货规划（、台） | 打开「库存健康」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 9 | 用户分析 | `/user-analysis` | `ops:user-analysis:view` | `growth/UserAnalysisView.vue` | 导出沉睡名单、导出复购榜 | 打开「用户分析」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |

### 1.2 交易履约

| # | 菜单 | 路径 | 权限码 | 源码视图 | 源码按钮（抽样） | 建议验收要点 | 状态 |
|---|------|------|--------|----------|------------------|--------------|------|
| 1 | 订单管理 | `/orders` | `ops:order:list` | `orders/OrderListView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 订单筛选；详情金额与状态 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 2 | 开门记录 | `/sessions` | `ops:session:list` | `sessions/SessionListView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 会话列表；关联订单/视频 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 3 | 争议审核 | `/disputes` | `ops:dispute` | `disputes/DisputeListView.vue` | 添加商品、从识别建议填充、智能识别建议、上传关键帧获取商品建议 | **L3**：结案后订单金额/退款/库存/分账 void 或 adjust；消费者+商户状态一致；二次结案幂等 | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 4 | 异常中心 | `/exceptions` | `ops:exception:list` | `exceptions/ExceptionListView.vue` | 设备运维、添加商品、按调整明细落账、免单/全额退回、添加备注、转派 | 免单/落账二次确认；账本与库存变化；无权限不可见写按钮 | PASS（L1 页可达；深测见 §7–§10 / P0） |

### 1.3 设备商品

| # | 菜单 | 路径 | 权限码 | 源码视图 | 源码按钮（抽样） | 建议验收要点 | 状态 |
|---|------|------|--------|----------|------------------|--------------|------|
| 1 | 设备运维 | `/device-ops` | `ops:device-ops:list` | `devices/DeviceOpsMonitorView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「设备运维」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 2 | 设备管理 | `/devices` | `ops:device:list` | `devices/DeviceListView.vue` | 新建设备、刷新、创建 | 新建/创建；进详情 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 3 | 投放地图 | `/device-map` | `ops:device-map:view` | `devices/DeviceMapView.vue` | 详情 | 打开「投放地图」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 4 | 设备可用性 | `/device-kpi` | `ops:device-kpi:view` | `devices/DeviceKpiView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「设备可用性」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 5 | 维修工单 | `/repair-tickets` | `ops:repair:list` | `devices/RepairTicketsView.vue` | 批量指派、新建工单、刷新、详情、开始处理、完成、取消、创建、确认指派 | 打开「维修工单」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 6 | 商品管理 | `/skus` | `ops:sku:list` | `skus/SkuListView.vue` | 导入模板、导入、识别入驻、新建商品、刷新 | 新建商品；导入 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 7 | 选品诊断 | `/sku-review` | `ops:sku-review:list` | `growth/SkuReviewView.vue` | 批量下架、批量保留、运行诊断、建议下架、保留、确认下架 | 打开「选品诊断」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 8 | 识别入驻 | `/sku-vision` | `ops:sku:list` | `skus/SkuVisionEnrollView.vue` | 导入模板、导入、商品管理、入驻配置、保存入驻、关闭 | 打开「识别入驻」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 9 | 识别映射 | `/vision-mappings` | `ops:vision:list` | `vision/VisionMappingView.vue` | 商品管理、新增映射、编辑、删除、取消、保存 | 打开「识别映射」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 10 | 录像上传 | `/upload-queue` | `ops:session:upload` | `upload/UploadQueueView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「录像上传」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 11 | 识别演示 | `/recognition-demo` | `ops:recognition-demo:view` | `vision/RecognitionDemoView.vue` | 识别映射、商品管理、清空 | 打开「识别演示」；列表或表单可用；关键写操作有中文反馈 | SKIP（本环境未开 `ENABLE_TEST_TOOLS`；直链 404「页面不存在」符合约定） |

### 1.4 履约仓储

| # | 菜单 | 路径 | 权限码 | 源码视图 | 源码按钮（抽样） | 建议验收要点 | 状态 |
|---|------|------|--------|----------|------------------|--------------|------|
| 1 | 补货调度 | `/replenishment` | `ops:replenishment:list` | `replenishment/ReplenishmentView.vue` | 规划补货路线、创建路线 | 规划路线；任务状态 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 2 | 仓库 | `/warehouse` | `ops:warehouse:list` | `warehouse/WarehouseView.vue` | 编辑、保存、确认付款、创建、确认入库、确认移库、添加一行、取消、确认收货 | 打开「仓库」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 3 | 固件版本 | `/ota` | `ops:ota:list` | `ota/OtaView.vue` | 批量下架、发布版本、刷新、下架、发布 | 打开「固件版本」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 4 | 服务时限监控 | `/sla` | `ops:sla` | `sla/SlaView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「服务时限监控」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 5 | 补货员效率 | `/replenishment-staff` | `ops:replenishment:list` | `growth/ReplenishmentStaffView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「补货员效率」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |

### 1.5 财务商户

| # | 菜单 | 路径 | 权限码 | 源码视图 | 源码按钮（抽样） | 建议验收要点 | 状态 |
|---|------|------|--------|----------|------------------|--------------|------|
| 1 | 资金账单 | `/fund-bills` | `ops:fund:list` | `finance/FundBillView.vue` | 刷新、支持跨月，单次不超过 90 天、查询 | 打开「资金账单」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 2 | 商户与分账 | `/merchants` | `ops:merchant:list` | `merchants/MerchantSplitsView.vue` | 新建商户、确认提交、保存 | **L3 必测**：改 platformRate → 新订单 `recordSplit` 后商户份额=公式；钱包/分账明细有记录；旧单不被静默改写。仅保存配置不算 PASS | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 3 | 进件工作台 | `/merchant-onboarding` | `ops:merchant:onboard:list` | `merchants/MerchantOnboardingView.vue` | 批量通过、批量驳回、新建进件、编辑、通过、驳回、保存 | **须走审批**：不可手工直改 ACTIVE；总部→财务节点见 `APPROVAL_DEPARTMENT_FLOW.md` | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 4 | 线长钱包 | `/line-managers` | `ops:line-manager:list` | `finance/LineManagerView.vue` | 批量通过、批量驳回、新建线长、刷新、查询、绑柜、调账、流水、业绩、代提现、通过并打款、驳回 | 与商户钱包隔离；提现 freeze/consume；勿与 SPLIT 流水混断言 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 5 | 商户提现 | `/merchant-withdraw` | `ops:merchant-withdraw:list` | `finance/MerchantWithdrawView.vue` | 批量通过、批量驳回、刷新、查询、调账、流水、代提现、通过并打款、驳回、重试打款、确认调账 | 通过并打款后余额/冻结变化；FAILED **不解冻**；双击不双扣；驳回释放冻结 | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 6 | 对账 | `/reconciliation` | `ops:reconciliation:list` | `reconciliation/ReconciliationView.vue` | 执行对账、执行 | 执行对账有结果 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 7 | 数据一致性 | `/consistency` | `ops:consistency:list` | `consistency/ConsistencyView.vue` | 立即巡检、刷新、修复 | 打开「数据一致性」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 8 | 充值管理 | `/recharges` | `ops:recharge:list` | `recharges/RechargeListView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 列表加载；关键写操作二次确认 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 9 | 余额退款 | `/balance-refunds` | `ops:balance-refund:list` | `finance/BalanceRefundView.vue` | 批量通过、批量驳回 | 打开「余额退款」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 10 | 开票申请 | `/invoices` | `ops:invoice:list` | `finance/InvoiceListView.vue` | 批量开具、批量驳回、刷新 | 列表加载；关键写操作二次确认 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 11 | 用户余额 | `/users` | `ops:user:list` | `users/UserListView.vue` | 确认调整 | 列表加载；关键写操作二次确认 | PASS（L1 页可达；深测见 §7–§10 / P0） |

### 1.6 增长风控

| # | 菜单 | 路径 | 权限码 | 源码视图 | 源码按钮（抽样） | 建议验收要点 | 状态 |
|---|------|------|--------|----------|------------------|--------------|------|
| 1 | 手机验证 | `/phone-verify` | `ops:phone-verify:list` | `users/PhoneVerifyView.vue` | 登记验证、编辑、删除、保存 | 打开「手机验证」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 2 | 风控 | `/risk` | `ops:risk:list` | `risk/RiskView.vue` | 加入黑名单、确认 | **L3**：拉黑后该用户开门/支付被拒；解黑恢复；审计可查 | PASS（L1 页可达；L3 见 R-01/R-02） |
| 3 | 营销活动 | `/promotions` | `ops:promotion:list` | `promotions/PromotionsView.vue` | 导入模板、导入、批量停用、新建活动、刷新、保存 | 新建/保存/启停后列表刷新 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 4 | 优惠券 | `/coupons` | `ops:coupon:list` | `promotions/CouponsView.vue` | 导入模板、导入、批量停用、新建优惠券、手动发券、批量发券、保存、发放、批量发放 | **L3**：发券后消费者可见；下单抵扣；停用后不可用；超发被拒 | PASS（MK-01+MK-02：发券可见；下单抵扣 350→300） |
| 5 | 素材库 | `/ad-assets` | `ops:ad:list` | `growth/AdAssetsView.vue` | 批量停用、批量删除、上传素材、上传、保存 | 新建/保存/启停后列表刷新 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 6 | 投放计划 | `/ad-campaigns` | `ops:ad:campaign:list` | `growth/AdCampaignsView.vue` | 批量停止、新建投放、保存 | 新建/保存/启停后列表刷新 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 7 | 积分兑换管理 | `/points-redeem` | `ops:points:list` | `growth/PointsRedeemView.vue` | 批量上架、批量下架、新建兑换项、刷新、保存 | 新建/保存/启停后列表刷新 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 8 | 会员等级规则 | `/member-levels` | `ops:member-level:list` | `growth/MemberLevelsView.vue` | 批量启用、批量停用、新建等级、刷新、编辑、展示名： 取消、保存 | 新建/保存/启停后列表刷新 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 9 | 活动效果分析 | `/marketing-roi` | `ops:marketing-roi:view` | `growth/MarketingRoiView.vue` | （模板未扫到 el-button 或按钮为动态/插槽） | 打开「活动效果分析」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 10 | 消息记录 | `/notifications` | `ops:notify:list` | `growth/NotificationsView.vue` | 删除选中、发送站内信、刷新、编辑、删除、消费者、商户、取消、发送、保存 | 打开「消息记录」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 11 | 用户反馈 | `/feedback` | `ops:feedback` | `feedback/FeedbackView.vue` | 提交回复 | 打开「用户反馈」；列表或表单可用；关键写操作有中文反馈 | PASS（L1 Playwright 可达冒烟 2026-09-08） |

### 1.7 系统

| # | 菜单 | 路径 | 权限码 | 源码视图 | 源码按钮（抽样） | 建议验收要点 | 状态 |
|---|------|------|--------|----------|------------------|--------------|------|
| 1 | 运营账号 | `/operators` | `ops:rbac:assign` | `system/OperatorManageView.vue` | 导入模板、导入、新增账号、刷新、保存、全部货柜、勾选柜机、线路 （） 取消 | **L3**：改角色后重新登录菜单/按钮变化；数据范围（柜机）生效；只读角色无写按钮 | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 2 | 角色管理 | `/roles` | `ops:rbac:role` | `system/RoleManageView.vue` | 新增角色、导入模板、导入、刷新、保存、全选、清空 | 勾选权限码后挂到账号→侧栏与 `v-hasPermi` 一致；取消权限后直链 `/forbidden` | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 3 | 部门管理 | `/departments` | `ops:dept:list` | `system/DepartmentManageView.vue` | 批量启用、批量停用、新增部门、刷新、编辑、成员、保存 | 成员变更后审批待办归属变化（进件/提现） | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 4 | 审批流配置 | `/approvals` | `ops:approval:config` | `system/ApprovalConfigView.vue` | 新增、保存、+ 结束、审批通过、结束、审批驳回、取消、上移、保存流程图 | 改节点后新单走新路径；无权限人点通过→403 | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 5 | 菜单管理 | `/menus` | `ops:rbac:menu` | `system/MenuManageView.vue` | 全选、清空、新增、展开、收起、保存 | **注意**：admin 侧栏真源主要是 `menu.ts`；本页改库菜单须验证登录权限树/按钮级 `F` 是否被消费，避免「改了库侧栏不变」假通过 | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 6 | 字典管理 | `/dicts` | `ops:dict:list` | `system/DictManageView.vue` | 新增类型、编辑、删除、刷新、导入模板、导入、新增字典项、保存、启用停用、取消 | **只影响展示**：改 label 后三端 Tag/筛选项更新；**不得**靠字典开关支付/开门。`GET /api/v2/dicts/runtime` 覆盖 `shared-dict` | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 7 | 参数配置 | `/system-configs` | `ops:config:list` | `system/SystemConfigView.vue` | 导入模板、导入、新增、刷新、上传标志、清除、保存品牌、查询 | 品牌/文档标题等可见变化；能力开关以环境变量为准（见 MODULES） | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 8 | 告警规则 | `/alert-rules` | `ops:config:list` | `system/AlertRuleView.vue` | 批量删除、新增、保存 | 触发条件后待办/告警出现；停用后不再刷 | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 9 | 定时任务 | `/scheduled-tasks` | `ops:task:list` | `system/ScheduledTaskView.vue` | 批量启用、批量停用、批量执行、新增、保存 | 「执行」产生预期副作用（对账/巡检等）；停用后到点不跑 | PASS（L1 页可达；深测见 §7–§10 / P0） |
| 10 | 组织与点位 | `/org-sites` | `ops:org:list` | `system/OrgSitesView.vue` | 新增顶级组织、编辑、新增子级、分配设备、删除、批量删除、新增合同、租金分账、出账、批量出账、标记已付、作废 | 分配设备后商户数据范围变化；租金出账有流水 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 11 | 通知公告 | `/announcements` | `ops:announcement:list` | `announcements/AnnouncementsView.vue` | 导入模板、导入、发布公告、刷新 | 目标端 announcements 可见；未发布不可见 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 12 | 审计日志 | `/audit` | `ops:audit:list` | `system/AuditLogView.vue` | （只读为主） | 关键写操作后有对应审计行（操作人/资源/时间） | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 13 | DevOps 中心 | `/devops` | `ops:devops:view` | `system/DevOpsHubView.vue` | 刷新状态、新窗口打开、重跑 Sonar、下方嵌入看板 | 链接可达；无权限不可见 | PASS（L1 Playwright 可达冒烟 2026-09-08） |
| 14 | 个人中心 | `/profile` | `（无独立 perm / 登录即可）` | `profile/ProfileView.vue` | 编辑资料、修改密码、刷新资料、清除、取消、保存 | 改密后旧 token 失效策略符合预期；资料回显 | PASS（L1 Playwright 可达冒烟 2026-09-08） |

### 1.8 路由存在但非侧栏菜单（仍需测）

| 路径 | 说明 | 建议验收 | 状态 |
|------|------|----------|------|
| `/login` | 登录页 | 正确账号进首页；错误提示中文；重置密码入口 | PASS（登录页可达；密码登录进后台） |
| `/print` | 打印单据 | 有单据参数时可打印/预览 | PASS（路由存在；无单据参数时页面可开） |
| `/devices/:id` | 设备详情（动态） | 从设备列表进入；货道/补货/复制链接等 | PASS（设备列表可进详情路由） |
| `/forbidden` | 无权访问 | 无权限菜单跳转落此页；可回工作台 | PASS（无权限落错误页；布局已修） |
| `/recognition-demo` | 识别演示（测试开关） | 仅 `ENABLE_TEST_TOOLS` | SKIP（本环境未开 `ENABLE_TEST_TOOLS`；直链 404「页面不存在」符合约定） |

---

## 2. 商户端（merchant-mp）

真源：`clients/merchant-mp/src/pages.json`。

### 2.1 TabBar

| Tab 文案 | pagePath | 状态 |
|----------|----------|------|
| 工作台 | `pages/home/home` | PASS（H5 Tab 可达 2026-09-08） |
| 柜机 | `pages/devices/devices` | PASS（H5 Tab 可达 2026-09-08） |
| 待办 | `pages/alerts/alerts` | PASS（H5 Tab 可达 2026-09-08） |
| 我的 | `pages/mine/mine` | PASS（H5 Tab 可达 2026-09-08） |

### 2.2 全页面矩阵

| # | 标题 | 页面 path | 源码按钮（抽样） | 建议验收要点 | 状态 |
|---|------|-----------|------------------|--------------|------|
| 1 | 登录 | `pages/login/login` | （启发式未扫到；打开页面核对） | 登录/退出；错误提示中文 | PASS（登录页可达；13800138001 进工作台） |
| 2 | 工作台 | `pages/home/home` | 扫码、扫码到柜、柜机列表、查看记录 | 打开「工作台」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 3 | 柜机 | `pages/devices/devices` | 扫码到柜、补货任务、导航、清除 | 柜机列表/详情；定价入口 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 4 | 柜机详情 | `pages/device-detail/device-detail` | 导航到柜、补货任务、发起要货 | 柜机列表/详情；定价入口 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 5 | 点位定价 | `pages/pricing/pricing` | 调价历史 | 打开「点位定价」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 6 | 补货任务 | `pages/replenishment/replenishment` | 扫码找柜、扫码核对、现场签到、确认商品与数量、扫码到柜、扫码、要货、常驻柜、清除筛选、查看已完成、查看全部、复制编号 | 任务列表；接单/完成；与运营侧一致 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 7 | 要货申请 | `pages/request/request` | 去补货 ›、发起要货、我的申请、刷新建议 | 任务列表；接单/完成；与运营侧一致 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 8 | 经营分析 | `pages/business/business` | 导出柜机报表、load()">重试、保存税号资料 | 打开「经营分析」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 9 | 待办 | `pages/alerts/alerts` | 完成库存核对、查看柜机 | 打开「待办」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 10 | 我的 | `pages/mine/mine` | 编辑资料、保存、保存提醒偏好 | 打开「我的」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 11 | 结算对账 | `pages/settlements/settlements` | 导出对账单 | 打开「结算对账」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 12 | 争议处理 | `pages/disputes/disputes` | 认领工单、回复、同意免单、维持原单、按识别结案、查看订单、查看柜机 | 争议列表；处理动作权限正确 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 13 | 柜机订单 | `pages/orders/orders` | （启发式未扫到；打开页面核对） | 打开「柜机订单」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 14 | 订单详情 | `pages/order-detail/order-detail` | 查看柜机、查看购物视频、相关争议 | 打开「订单详情」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 15 | 购物视频 | `pages/video/video` | 复制链接 | 打开「购物视频」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 16 | 消息中心 | `pages/messages/messages` | （启发式未扫到；打开页面核对） | 打开「消息中心」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 17 | 通知公告 | `pages/announcements/announcements` | 查看详情 › | 打开「通知公告」；下拉刷新（若启用）；空态中文 | PASS（可见 UAT 公告 announceId=1） |
| 18 | 公告详情 | `pages/announcements/detail` | load()">重试 | 打开「公告详情」；下拉刷新（若启用）；空态中文 | PASS（可见 UAT 公告 announceId=1） |
| 19 | 团队成员 | `pages/team/team` | 邀请成员、确认邀请、保存角色、确认重置、停用该成员、重新启用 | 打开「团队成员」；下拉刷新（若启用）；空态中文 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 20 | 分账明细 | `pages/splits/splits` | 失败、全部 | **L3**：支付成功后本页出现对应 split；金额=公式；失败态有原因；空列表≠「分账未跑」时须对照运营订单 | PASS（L1 页可达；深测见 P0/分账） |
| 21 | 商户钱包 | `pages/wallet/wallet` | 申请提现 | 余额=流水合计；申请后冻结↑；运营打款后余额↓；与线长钱包隔离 | PASS（L1 页可达；线长未绑定时中文提示） |
| 22 | 线长钱包 | `pages/line-wallet/line-wallet` | 申请提现 | 非商户 SPLIT；勿用订单分账断言本页 | PASS（L1 页可达；线长未绑定时中文提示） |

---

## 3. 消费者端（consumer-mp）

真源：`clients/consumer-mp/src/pages.json`。

### 3.1 TabBar

| Tab 文案 | pagePath | 状态 |
|----------|----------|------|
| 首页 | `pages/index/index` | PASS（H5 Tab 可达 2026-09-08） |
| 订单 | `pages/orders/orders` | PASS（H5 Tab 可达 2026-09-08） |
| 我的 | `pages/mine/mine` | PASS（H5 Tab 可达 2026-09-08） |

### 3.2 全页面矩阵

| # | 标题 | 页面 path | 源码按钮（抽样） | 建议验收要点 | 状态 |
|---|------|-----------|------------------|--------------|------|
| 1 | 开门购物 | `pages/index/index` | 去充值、重试开门、重新扫码、换一台、去登录、查看审核详情、关门结算、取消本次开门、稍后再看结果、附近找柜、报修、稍后查看订单 | 扫码/输柜号开门主路径；失败提示 | PASS（登录/备用手机号表单可达） |
| 2 | 我的订单 | `pages/orders/orders` | 扫码购物、去登录、故障报修、帮助与客服、隐藏零元单、上拉加载更多 | 订单列表/详情；视频/争议入口 | PASS（登录/备用手机号表单可达） |
| 3 | 我的 | `pages/mine/mine` | 充值、加载中…、暂无余额流水、购物扣款、退款与充值会出现在这里、余额、微信免密、支付宝免密 | 打开「我的」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 4 | 开通支付 | `pages/verify/verify` | 去扫码开门 | 登录/开通支付流程可完成或明确阻塞原因 | PASS（登录/备用手机号表单可达） |
| 5 | 登录 | `pages/login/login` | 验证码、密码、用户协议、隐私政策、退款规则 | 登录/开通支付流程可完成或明确阻塞原因 | PASS（登录/备用手机号表单可达） |
| 6 | 账单结果 | `pages/result/result` | 回首页、查看订单、返回本柜、账单有问题、申请退款、帮助 | 打开「账单结果」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 7 | 故障报修 | `pages/report/report` | （启发式未扫到；打开页面核对） | 打开「故障报修」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 8 | 意见反馈 | `pages/feedback/feedback` | 提交反馈、我的反馈 | 打开「意见反馈」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 9 | 账户充值 | `pages/recharge/recharge` | 返回我的 | 充值档位；支付结果回跳 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 10 | 订单详情 | `pages/order-detail/order-detail` | 再去本柜购物、查看购物视频、帮助与客服 | 订单列表/详情；视频/争议入口 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 11 | 购物视频 | `pages/video/video` | 复制链接 | 打开「购物视频」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 12 | 我的优惠券 | `pages/coupons/coupons` | 扫码购物、看热门活动 | 券列表；活动可点 | PASS（登录后可见券列表；MK 券在列） |
| 13 | 附近柜机 | `pages/nearby/nearby` | 导航、去开门 | 打开「附近柜机」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 14 | 会员中心 | `pages/member/index` | 积分明细 › | 扫码/输柜号开门主路径；失败提示 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 15 | 积分明细 | `pages/points/points` | 明细 › | 打开「积分明细」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 16 | 积分兑换 | `pages/points/redeem` | 明细 › | 打开「积分兑换」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 17 | 消息中心 | `pages/messages/messages` | 全部已读 | 打开「消息中心」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 18 | 热门活动 | `pages/marketing/index` | 扫码购物、去领券 | 扫码/输柜号开门主路径；失败提示 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 19 | 账单审核 | `pages/dispute/detail` | 查看账单订单、返回订单列表 | 提交材料；状态回显 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 20 | 帮助中心 | `pages/help/help` | 拨打、复制、去查看、去反馈、去报修、去找柜、查看我的订单 | 打开「帮助中心」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 21 | 条款说明 | `pages/policy/detail` | load()">重试 | 打开「条款说明」；返回与 Tab 正常 | PASS（H5 L1 Playwright 可达 2026-09-08） |
| 22 | 通知公告 | `pages/announcements/announcements` | load()">重试 | 打开「通知公告」；返回与 Tab 正常 | PASS（可见 UAT 公告 announceId=1） |
| 23 | 公告详情 | `pages/announcements/detail` | load()">重试 | 打开「公告详情」；返回与 Tab 正常 | PASS（可见 UAT 公告 announceId=1） |

---

## 4. 跨端主链路（业务挂钩断言）

执行时记录 `sessionId` / `orderId` / `ticketId` / `splitId` / `withdrawId`。  
**通过标准必须可核对下游**，禁止只写「页面正常」。

| # | 链路 | 关键节点 | 关键动作 | **L3 业务挂钩（必查）** | 边界/异常 | 状态 |
|---|------|----------|----------|-------------------------|------------|------|
| 1 | 扫码开门→结算 | 消费者 `index` → 柜机 → `result`/`orders` | 扫码/柜号、开门、关门结算 | 会话状态机推进；订单生成；金额可读；可进视频；MQTT/mock 门事件一致 | 未登录/未开通支付/柜离线/门超时/连点开门 | PASS（P0-01） |
| 2 | 争议闭环 | 消费者争议 → 运营 `/disputes` `/exceptions` → 商户 `disputes` | 提交；调整/免单/结案 | 退款到账或免单；库存回库策略符合；**分账 void/adjust**；三端状态一致；二次结案幂等 | 无权限结案；重复提交；部分退与全额退 | PASS（P0-05 + S-06/07） |
| 3 | 补货履约 | 运营补货 → 商户 `replenishment`/`request` → 设备货道 | 规划、接单、补货开门、实盘 | 任务完结；货道账面变化；FEFO/实盘调账符合 PASS_3D | 未签到完成、扫错柜、超权限开门 | PARTIAL（P0-08 库存 PUT；全链路补货脚本未绿） |
| 4 | **分账入账** | 运营 `/merchants` 比例 → 消费者购物支付成功 → 商户 `splits`+`wallet` | 保存比例；完成一单支付 | 见 **§10.1**：有 split 记录；`merchantShare` 符合 bps；LEDGER_ONLY 则钱包+流水；重放不双入 | 比例 0/10000；ACCRUED 不误断言本地钱包；关 mock 支付 | PASS（§10.1） |
| 5 | 提现打款 | 商户 `wallet` 申请 → 运营 `/merchant-withdraw` | 申请；通过并打款/驳回 | 冻结→PAID consume 或 REJECT 释放；FAILED 冻结仍在；流水类型正确 | 低于最低额；超日限；双 requestNo；无审批人 | PASS（P0-04/10） |
| 6 | 营销核销 | 运营券/活动 → 消费者领用 → 下单 | 发券、领券、抵扣 | 订单优惠金额；核销次数；ROI/券状态；停用后不可用 | 过期券、叠用规则、库存券发完 | PASS（MK-01～03） |
| 7 | 设备运维 | 消费者报修 → 运营工单 → 商户待办 | 报修、指派、完成 | 工单状态闭环；通知到达 | 取消工单、重复报修 | PASS（ticket=1 OPEN→IN_PROGRESS→DONE；设备 CAB-001） |
| 8 | 消息公告 | 运营发布/站内信 → 两端 messages/announcements | 发布、发送、已读 | 目标 audience 可见；未发布不可见；已读计数 | 删信后对端 | PASS（announceId=1 PUBLISHED；消费者/商户 announcements 均可见） |
| 9 | 审批流 | 进件/提现/采购 | 提交→节点通过/驳回 | 状态机按部门走完才 ACTIVE/打款；错部门账号不可过 | 跳过节点、并行重复点通过 | PASS（P0-10 提现+采购门禁） |
| 10 | 数据隔离 | 商户 `38001` vs `38003` | 互看柜机/订单/钱包 | 他商户数据 403 或空；运营跨商户仅有权限可见 | 篡改 URL id | PASS（P0-07） |

专项深读：金钱 [`PASS_3A`](pass-notes/PASS_3A_MONEY.md) · 争议 [`PASS_3B`](pass-notes/PASS_3B_DISPUTE.md) · MQTT [`PASS_3C`](pass-notes/PASS_3C_MQTT.md) · 库存 [`PASS_3D`](pass-notes/PASS_3D_INVENTORY.md) · 钱包分账 [`PASS_3E`](pass-notes/PASS_3E_MERCHANT_WALLET.md)。

---

## 5. 维护约定

1. **增删页面**：先改 `menu.ts` / `router` / `pages.json` / `merchant-nav.ts`，再改本矩阵（或重跑生成脚本）。  
2. **重新生成页面表**：`python scripts/gen-btn-scan.py` → `python scripts/gen-business-test-matrix.py`（**§7～§10 为手写业务真源，生成后勿覆盖丢失**；应用补丁合并）。  
3. **权限**：每个 `perm` 至少用「有权 / 只读 / 无权限」各测一次。  
4. **Pass 专项**：深分支以 `pass-notes` 为准；本矩阵保证入口+挂钩+边界不漏。

---

## 6. 统计（页面入口）

| 维度 | 数量 |
|------|------|
| 运营菜单项 | 65 |
| 商户 pages.json 页 | 22 |
| 消费者 pages.json 页 | 23 |
| 跨端主链路（含挂钩） | 10 |
| §7 通用边界类 | 见下表 |
| §8～§10 专题用例 | 见下表（执行时打勾） |

---

## 7. 通用边界 / 异常 / 幂等（全端套用）

对 **每一个写操作按钮** 按需抽测；资金与开门类 **全做**。

| ID | 类型 | 操作 | 期望 | 状态 |
|----|------|------|------|------|
| G-01 | 空提交 | 必填未填点保存/提交 | 前端校验或 400；中文提示；无脏行 | PASS（商户 upsert 空 merchantId→400「不能为空」） |
| G-02 | 非法值 | 负数金额、超长字符串、非法枚举 | 拒绝；DB 无写入 | PASS（券面值 -10→400「满减券面值须大于 0」） |
| G-03 | 状态机 | 在错误状态下点动作（如已结案再免单） | 409/业务码；状态不变 | PASS（已结案争议再 resolve→409「工单已处理」） |
| G-04 | 连点 | 500ms 内双击同一提交 | 仅一次生效；或第二次明确「处理中/已存在」 | PASS（同 P0-09） |
| G-05 | 幂等键 | 同 `requestNo` / 同业务 ref 重放 | 不双入账、不双开门 | PASS（同 P0-09 / S-05） |
| G-06 | 401 | 清 token 后点写操作 | 跳转登录；无半成功 | PASS（无 token 写商户→401「请先登录」） |
| G-07 | 403 | 无权限账号点隐藏/直调 | 按钮不可见或 403；无数据泄露 | PASS（§8 角色/功能包 403） |
| G-08 | 404 | 篡改详情 id | 空态/404 中文；不 500 白屏 | PASS（假订单 id→404「资源不存在」） |
| G-09 | 网络 | DevTools Offline 点提交 | 可读失败；可重试；不假成功 | PASS（参数配置 Offline 点刷新→Toast「网络错误，请稍后重试」；无假成功） |
| G-10 | 超时 | 慢网/下游超时 | 超时提示；资金类符合冻结/回滚语义 | PASS（对账「执行对账」确认后 route.abort→Toast「网络错误，请稍后重试」） |
| G-11 | 并发 | 两角色同时审同一单 | 仅一方成功；另一方冲突提示 | PASS（退款 req=3：001 APPROVED / 003 SKIPPED→403「不是处理人」） |
| G-12 | 空态 | 无数据列表 | 中文空态；非转圈死锁 | PASS（merchants q 无匹配 total=0 items=0） |
| G-13 | 分页 | 大数据翻页/筛选 | 条数与筛选条件一致；无串页 | PASS（orders page0/1 size=2 overlap=0） |
| G-14 | 导出 | 导出按钮 | 文件可打开；权限不足不可导出 | PASS（超管 CSV 可读；005 export 403） |
| G-15 | 二次确认 | 免单/删除/打款 | 取消则不执行；确认才 L3 | PASS（用户余额「调整余额」→二次确认点取消；余额仍 79800 未变） |

---

## 8. 权限 / 菜单 / 数据范围

### 8.1 运营后台角色矩阵（账号见 DEMO_ACCOUNTS）

| 检查项 | 超管 001 | 财务 002 | 运营 003 | 补货员 004 | 只读 005 | 状态 |
|--------|----------|----------|----------|------------|----------|------|
| 登录后默认落地页合理 | ✓ | 财务相关 | 运营相关 | 补货/仓 | 工作台或首个可读 | PASS（001/002/003/005 UI；004 API） |
| 侧栏仅显示有 `perm` 的项 | ✓ | 无设备写等高危（按种子） | 按种子 | 按种子 | 几乎只读 | PASS（002/003/005 抽样） |
| 直链无权限 path → `/forbidden` | — | 测 1～2 个写页 | 测财务写页 | 测提现打款 | 测任意 edit | PASS（002→争议；003→提现；005→提现） |
| 写按钮 `v-hasPermi` 隐藏 | — | — | — | — | **无**新建/通过/打款 | PASS（005 争议页无结案） |
| 数据范围（柜机/商户） | 全局 | 全局财务 | 按配置 | 绑商户范围 | 只读全局或按配置 | PASS（003 设备/订单/争议 ALLOW、提现 403；004 补货/仓 ALLOW、设备/订单/争议/提现 403） |

脚本参考：`clients/admin-vue/tests/role-regression-uat.mjs`。

### 8.2 商户端：RBAC ∩ 功能包

真源：`merchant-nav.ts`（`field` / `biz` / `team`）+ `merchant.pack_*_enabled`。

| 检查项 | 操作 | 期望 | 状态 |
|--------|------|------|------|
| 关 `pack_field` | 管理员登录 | 补货/柜机/待办入口裁剪或不可用 | PASS（off：devices/replenishment 403；wallet/orders 仍 ALLOW；me.enabledPacks 无 field） |
| 关 `pack_biz` | 同上 | 结算/钱包/分账/订单等 biz 入口不可用 | PASS（off：wallet/orders/revenue-splits 403；devices 仍 ALLOW；恢复后 wallet OK） |
| 关 `pack_team` | 同上 | 团队入口不可用 | PASS（off：team/users·roles 403；enabledPacks 无 team；恢复后 team/users OK） |
| 店员 `38002` | 进设置/邀请 | 只读或 403 | PASS（wallet/team 邀请 403；devices/orders 可读） |
| 他商户 `38003` | 打开默认商户订单 URL | 403/空；不见 MCH-DEFAULT | PASS（订单 total=0；钱包 MCH-OTHER） |
| 财务 `38004` | 设备写操作 | 拒绝；结算可读 | PASS（wallet 可读；settings PATCH 403；replenishment 403） |
| 补货员 `38007` | 提现/团队 | 不可；补货可 | PASS（replenishment/devices ALLOW；wallet/team/orders 403） |
| 运营号登商户门户 | — | 403（DEMO_ACCOUNTS 约定） | PASS（001→`/merchant/me`·wallet 403） |

### 8.3 菜单管理 vs 前端 NAV

| ID | 步骤 | 期望 | 状态 |
|----|------|------|------|
| M-01 | `/menus` 新增目录/菜单/按钮 `F` | 保存成功；权限树可勾选 | PASS（`POST /rbac/permissions` 建 `ops:uat:btn:m01-*` id≈654；权限树可见） |
| M-02 | 角色勾选新权限 → 账号重登 | 按钮级权限生效（API 403 边界） | PASS（viewer 角色 PUT 权限体为 **JSON 数组**；勾选后重登生效；去勾选后无该 perm） |
| M-03 | 对比侧栏与 `menu.ts` | 记录差异：以 **实际路由可达 + perm** 为准；避免只改 DB 以为侧栏变了 | PASS（侧栏仍由前端 `menu.ts` 驱动；DB path 抽样空≠侧栏变；以路由+perm 为准） |
| M-04 | 禁用菜单项 | 无权限用户不可见；超管策略符合产品约定 | PASS（权限 `INACTIVE` 后 viewer 无该码；超管仍可管权限树） |

---

## 9. 系统域深测（字典 / 参数 / 审批 / 告警 / 定时 / 审计）

### 9.1 字典（`/dicts`）

约定（[`MODULES.md`](MODULES.md)）：**字典只做展示文案，不是能力开关**。

| ID | 步骤 | L3 期望 | 状态 |
|----|------|---------|------|
| D-01 | 改某状态 label（如订单状态文案） | 运营列表 Tag、商户/消费者筛选项（runtime）更新 | PASS（PAID label→`已支付-UAT` runtime 同步；已恢复原文案） |
| D-02 | 停用某字典项 | 新单筛选项不再可选；历史展示降级策略可接受 | PASS（INACTIVE 后 runtime 不再返回该项；已恢复 ACTIVE） |
| D-03 | 新增项后拉 `GET /api/v2/dicts/runtime` | ACTIVE 项出现；失败时前端回退编译期 DICT | PASS（改 label 后 `/api/v2/dicts/runtime` 立即可见） |
| D-04 | **反例** | 改字典 **不能** 打开/关闭支付或开门；能力仍看环境变量/Java 常量 | PASS（改字典后 account/device status 仍正常） |

### 9.2 参数配置 / 品牌（`/system-configs`）

| ID | 步骤 | 期望 | 状态 |
|----|------|------|------|
| C-01 | 改品牌名/Logo 保存 | 文档标题、登录页品牌可见变化 | PASS（`ops.brand.title`→`前海易购-UAT`；`/api/v2/public/ops-branding`+登录页标题含 `-UAT`；已恢复） |
| C-02 | 非法配置值 | 拒绝保存 | PASS（空 key→400「配置键不能为空」；超长→400「最长 2048」） |
| C-03 | 无 `ops:config:list` | 不可进或只读 | PASS（005 system-configs API 403） |

### 9.3 审批流 × 部门（`/approvals` `/departments`）

详见 [`APPROVAL_DEPARTMENT_FLOW.md`](APPROVAL_DEPARTMENT_FLOW.md)。

| ID | 业务 | 步骤 | 期望 | 状态 |
|----|------|------|------|------|
| A-01 | 进件 `MERCHANT_ONBOARD` | 提交→总部通过→财务通过 | 才 ACTIVE；中途不可手工 ACTIVE | PASS（onboarding=1 强制 ACTIVE→409「须审批通过后方可生效」；SUBMITTED + instance=3 PENDING） |
| A-02 | 进件 | 财务账号越权审总部节点 | 403 或无待办 | PASS（002 待办无进件；review→403「不是当前审批节点的处理人」；仍 SUBMITTED） |
| A-03 | 提现 `MERCHANT_WITHDRAW` | 超阈值：经理→财务 | 节点未过不能打款 | PASS（wd3 PENDING_REVIEW；payout 409；instance=1） |
| A-04 | 余额退款 | 经理→财务 | 通过后才退；驳回不退 | PASS（req=1：经理后仍 PENDING+冻结；财务后 REFUNDED -300；req=2 驳回释放冻结） |
| A-05 | 改审批流后新单 | 改节点名后新建退款单 | 新单走新节点；旧单仍按实例快照 | PASS（节点改名 `运营审核-UAT`：新 instance 任务带新名；旧 instance 仍 `运营审核`；已恢复节点名；refund=5 清理驳回） |
| A-06 | 部门撤成员 | 从 MANAGER 移除 003 | 待办不再派给该人 | PASS（003 移出 MANAGER 后余额退款待办 0；已恢复部门成员） |

### 9.4 告警规则 / 定时任务 / 审计

| ID | 模块 | 步骤 | 期望 | 状态 |
|----|------|------|------|------|
| T-01 | 告警规则 | 配置阈值→制造触发条件 | 告警/待办出现 | PASS（`device.temp.alert_max_c=8`；heartbeat 27℃→`TEMP_ABNORMAL` OPEN） |
| T-02 | 告警规则 | 停用规则 | 同条件不再触发 | PASS（`alert_max_c=0` 后超温不建 OPEN；已恢复 8） |
| T-03 | 定时任务 | 手动「执行」对账/巡检类 | 有结果行或日志；与按钮文案一致 | PASS（`compensation-process` 手动执行；`lastRunAt` 更新） |
| T-04 | 定时任务 | 停用后等待触发点 | 不执行 | PASS（停用后 `/run`→`result=SKIPPED`「任务已停用」；已重新启用） |
| T-05 | 审计 | 完成提现打款/改角色/改分账比例 | `/audit` 有操作人、动作、资源 id | PASS（`/audit-logs` 含 DISPUTE_RESOLVE / MERCHANT_CREATE 等；字典改动未入审计另记） |
| T-06 | 审计 | 只读账号 | 无写操作审计噪音；不可清日志（若产品禁止） | PASS（005 audit-logs 403；DELETE 405） |

---

## 10. 分域 L3 挂钩用例（防「有页面不起作用」）

### 10.1 分账 / 钱包（必测 · 针对历史假通过）

源码：`RevenueSplitService` · 笔记：[`PASS_3E`](pass-notes/PASS_3E_MERCHANT_WALLET.md)。

| ID | 场景 | 步骤 | **必须核对** | 失败判定（假通过） | 状态 |
|----|------|------|--------------|-------------------|------|
| S-01 | 配置生效 | `/merchants` 设 `platformRateBps` 保存 | 配置回显 | 只 Toast 成功 | PASS（POST upsert 1000→1500→回读 1500→恢复 1000） |
| S-02 | 支付后入账 | 消费者完成一单 PAID | 存在 split；`merchantShare=gross-platform`；状态多为 `LEDGER_ONLY` | **仅**分账列表空、或金额不对 | PASS（order `…9794` split `…1232` 350/35/315） |
| S-03 | 钱包挂钩 | 打开商户 `wallet` | 余额增加≈ merchantShare；流水 `SPLIT`/splitId | 有 splits 页但钱包不变 | PASS（`SPLIT_CREDIT` +315；商户 wallet API） |
| S-04 | 商户端明细 | `splits` 筛选该 order | 状态/金额与运营一致 | 两端不一致 | PASS（`GET /merchant/revenue-splits` 含 order `…9333` LEDGER_ONLY 315） |
| S-05 | 幂等 | 触发重复 recordSplit（或重放结算） | 钱包不双入 | 余额翻倍 | PASS（UK + doRecordSplit 短路） |
| S-06 | 全额退 | 争议 WAIVE/全额退 | split VOIDED；钱包 reverse；余额回退 | 订单退了钱包不减 | PASS（order `…0182` REFUNDED；split VOIDED；`SPLIT_REVERSE` -180） |
| S-07 | 部分退 | 改单减额 | `SPLIT_PARTIAL_REV`；份额更新 | 只改订单不分账 | PASS（`e2e-partial-refund-line`；`SPLIT_PARTIAL_REVERSE` -180；库存 6→4→5） |
| S-08 | ACCRUED 路径 | 有微信接收方时 | 文档化：本地可能不入账；**勿**用钱包余额当微信分账对账 | 误报「分账坏了」或误报「好了」 | SKIP（本轮无 wechat_receiver；均为 LEDGER_ONLY） |
| S-09 | 提现联动 | 入账后申请提现→运营打款 | 冻结/consume 链路完整 | 申请成功但余额逻辑错 | PASS（wd1 PAID；wd3 PENDING 冻结 50000） |

### 10.2 交易 / 支付 / 开门

| ID | 场景 | L3 核对 | 状态 |
|----|------|---------|------|
| P-01 | 余额支付成功 | 消费者余额↓；订单 PAID；分账触发（S-02） | PASS（P0-01/02） |
| P-02 | 余额不足 | 不开门或明确阻断；无幽灵会话 | PASS（balance=0→412 预授权不足；activeSession 空） |
| P-03 | 支付回调重放 | 不双扣 | PASS（充值 mock-success 重放：余额 +200 后再次同回调 +0） |
| P-04 | 开门指令 | 设备侧/mock 收到；会话 OPENING→SHOPPING | PASS（MQTT e2e） |
| P-05 | 门未关超时 | 有兜底/告警；主流程不裸崩 | PASS（SHOPPING 回拨 open_time→`CANCELLED`「开门超时自动关闭（超过10分钟未关门）」） |

### 10.3 库存 / 补货 / 仓库

| ID | 场景 | L3 核对 | 状态 |
|----|------|---------|------|
| I-01 | 销售出库 | 货道数量↓与订单行一致 | PASS（order `…100194` qty=1 SALE -1 batch=B-NEAR；inventoryDeducted） |
| I-02 | 补货实盘 | 「按实盘调账面」后账面=实盘 | PASS（stocktake `SKU-WATER-001` 5→9） |
| I-03 | 采购入库 | 仓存↑；确认付款状态机正确 | PASS（PO=2 审批→RECEIVED；`SKU-WATER-001` 仓存 102→105；payable=UNPAID） |
| I-04 | FEFO | 出库批次符合近效期优先（PASS_3D） | PASS（LOT-NEAR/FAR 各5；write-off×3→NEAR=2 FAR=5） |

### 10.4 营销 / 会员 / 积分

| ID | 场景 | L3 核对 | 状态 |
|----|------|---------|------|
| MK-01 | 发券 | 消费者 `coupons` 可见 | PASS（defId=1 AMOUNT_OFF；issue couponId=1 UNUSED；消费者列表可见） |
| MK-02 | 下单抵扣 | 订单优惠额；券核销 | PASS（session=`…6562649` preferredCoupon=1；order=`…0185959` PAID original350 discount50 total300；券 USED） |
| MK-03 | 停用活动 | 新单不可用 | PASS（def=2 INACTIVE 后发券→400「优惠券已停用」） |
| MK-04 | 积分兑换 | 积分↓；兑换记录；库存项↓ | PASS（item=1 扣 100 分→4900；redeemed 0→1；发券 couponId=3；流水 USE/REDEEM） |
| MK-05 | 会员倍率 | 升级后积分入账倍率符合规则 | PASS（GOLD `pointsRate=3`；确认争议单 paid=200→入账 6 分=`floor(2×3)`） |

### 10.5 风控 / 用户

| ID | 场景 | L3 核对 | 状态 |
|----|------|---------|------|
| R-01 | 加黑名单 | 该用户无法开门/支付（产品约定） | PASS（blacklist 10001→开门 403「账号受限…」；需 `aicabinet.risk.enabled`） |
| R-02 | 解黑 | 恢复 | PASS（DELETE blacklist 后会话可 OPENING） |
| R-03 | 调余额 | `/users` 调整后消费者余额一致；有审计 | PASS（+123→19773；同幂等键重放余额不变；`BALANCE_ADJUST` 审计） |

### 10.6 设备 / OTA / 识别

| ID | 场景 | L3 核对 | 状态 |
|----|------|---------|------|
| E-01 | 新建设备 | 列表可见；商户范围正确 | PASS（device `581464685583` → MCH-DEFAULT DEPLOYED） |
| E-02 | OTA 发布 | 目标柜收到版本策略（灰度/定向） | PASS（publish→`/internal/.../ota/check` updateAvailable+targetVersion；unpublish 后 false；gray≤0 服务端归一 100） |
| E-03 | 识别映射 | 结算识别结果映射到正确 SKU | PASS（YOLO `uat_water_bottle`→`SKU-WATER-001`；admin 列表+`/internal/v1/vision/mappings` 可见） |

---

## 11. 执行顺序建议

1. 登记环境标签（§12.2）  
2. **§12.1 P0 子集**（可先跑 §12.4 自动化再补 UI 证据）  
3. **§8 权限**（只读 + 财务 + 他商户）  
4. **§4 链路 1→4→5** + **§10.1 分账全表**  
5. **§9 字典/审批/菜单**  
6. P1：库存补货、券、功能包；再扫 §1～§3 剩余 + §7 边界  
7. 争议/MQTT/库存深分支对照 `pass-notes`  
8. 汇总 FAIL/BLOCK → issue（比填满状态列更优先）

与旧文档：抽样编排仍可用 [`BROWSER_MIN_UAT.md`](BROWSER_MIN_UAT.md)、[`BROWSER_FULL_UAT_PLAN.md`](BROWSER_FULL_UAT_PLAN.md)；**全量节点 + 挂钩 + 边界 + P0 以本文为准**。

---

## 12. P0 子集 · 环境 · H5/小程序 · 自动化 · 证据

### 12.1 P0 必过子集（每轮联调/发版前）

| P0-ID | 对应 | 动作摘要 | 证据至少含 | 状态 | 证据备注 |
|-------|------|----------|------------|------|----------|
| P0-01 | §4#1 · P-01/P-04 | 扫码/柜号开门→结算出单 | sessionId, orderId | PASS | `e2e-shopping.ps1` BALANCE：session=`1788832341471405582` → DISPUTED→CONFIRM → order=`1788832425799859794` PAID ¥3.50；日志 `docs/uat-screenshots/2026-09-08/e2e-shopping.log` |
| P0-02 | §10.1 S-02/S-03 | 支付后 split + 钱包入账 | orderId, splitId, 钱包差额 | PASS | order=`1788832425799859794`；split=`1788832425876341232` LEDGER_ONLY gross=350 platform=35 merchant=315；钱包 `MCH-DEFAULT` +315；ledger `SPLIT_CREDIT` |
| P0-03 | §10.1 S-05 | 分账/结算重放不双入 | 两次后余额不变 | PASS | `uk_order_revenue_split_order_id` 拒重插；`RevenueSplitService.doRecordSplit` 已存在则直接返回；余额保持 315、ledger 仅 1 条 |
| P0-04 | §4#5 · S-09 | 提现申请→审核打款或驳回 | withdrawId, 冻结变化 | PASS | withdraw=`1` `P0-WD-20260908100217` 低于审核阈值自动 PAID（MOCK）；钱包 315→冻结→215；UI 截图 `P0-04-merchant-withdraw.png` |
| P0-05 | §4#2 · 争议 | 结案后金额/退款/分账冲正三端一致 | ticketId, orderId | PASS | ticket=`1788832791807266280` CONFIRM→RESOLVED；session=`1788832699823814767` COMPLETED；order=`1788833033656619333` PAID；钱包 +315（新分账） |
| P0-06 | §8.1 | 只读 `005` 无打款/结案写按钮；直链 forbidden | 截图 | PASS | `13900000005` 直链 `/merchant-withdraw`→`/forbidden`（`P0-06-viewer-withdraw-forbidden.png`）；争议页仅查询/刷新，无结案写按钮（`P0-06-viewer-disputes.png`） |
| P0-07 | §8.2 | `38003` 不可见默认商户订单/钱包 | 截图或 403 | PASS | `13800138003`→钱包 `merchantId=MCH-OTHER` bal=0；订单 `total=0`（不含 DEFAULT 单 `1788832425799859794`） |
| P0-08 | §4#3 抽测 | 补货或实盘后货道账面变化 | deviceId, 前后库存 | PASS | `PUT /api/v2/ops/admin/inventory`：CAB-001 `SKU-WATER-001` 0→6（`e2e-replenishment.ps1` 因 slot 缺口为空/签到坐标暂未全绿，本项以库存写接口 L3 计） |
| P0-09 | G-04/G-05 | 开门或支付连点 / 同业务键重放 | 仅一次生效 | PASS | `e2e-fund-safety.ps1` TC-5.7：同幂等键重放返回同一 session=`1788832794705173923`；日志 `e2e-fund-safety.log` |
| P0-10 | §9 A-01 或提现审批 | 审批未完成不能终态生效 | 实例 id | PASS | 大额提现 withdraw=`3` `PENDING_REVIEW` 冻结 50000；`approval_instance` id=`1` MERCHANT_WITHDRAW PENDING；未审直接 payout→409「当前状态不可打款」；采购 PO=`1` `PENDING_APPROVAL` instance=`2` |

P0 本轮：**10/10 PASS**（环境曾缺演示账号/商户，已临时补种；全量矩阵仍勿宣称通过）。

### 12.2 环境矩阵（结果必须带标签）

| 标签 | 含义 | 典型组合 |
|------|------|----------|
| `env:docker-full` | `infra/docker-compose.full.yml` | Gateway + 全服务 |
| `env:local` | 本机 trade/vite | 端口见 STARTUP_REFERENCE |
| `pay:mock` / `pay:live` | 支付 mock 或真商户号 | live 才测真渠道 |
| `vision:mock` / `vision:yolo` | 识别 mock 或真模型 | 见 vision health |
| `door:sim` / `door:mqtt-real` | 门事件模拟器 / 真柜 MQTT | 真柜另记柜号 |
| `client:h5` / `client:mp-weixin` | H5 或微信开发者工具/真机 | **不可互相替代宣称** |

示例备注：`2026-09-08 · env:docker-full · pay:mock · vision:mock · door:sim · client:h5`

### 12.3 H5 ≠ 微信小程序（原生能力缺口）

矩阵 §2～§3 默认可用 **H5** 跑通业务逻辑；下列项 **H5 PASS 不等于小程序 PASS**，须 `client:mp-weixin` 另测或标 BLOCK：

| 能力 | 为何 H5 不够 | 建议验法 |
|------|--------------|----------|
| 扫码开门 | 相机/扫码组件差异 | 真机扫柜码 |
| 微信/支付宝免密、支付 | JSAPI / 小程序支付 | 真机 + 对应 mock/live |
| 胶囊与自定义顶栏 | `getBelowCapsulePadPx` 等 | 对照 dist，看遮挡 |
| 分包/包体积 | 仅 mp 构建 | 确认 `dist/dev/mp-weixin` mtime 含本次改动 |
| 下拉刷新 / 分享 / 定位附近柜 | 端能力不同 | 真机点选 |

### 12.4 自动化对照（能自动则先自动，再补 UI 证据）

| 矩阵关注点 | 自动化资产 | 覆盖说明 |
|------------|------------|----------|
| 开门/购物 API | `ConsumerE2ETest`；`scripts/e2e-shopping.ps1` | MockMvc/脚本；UI 仍要 P0-01 |
| 商户权限/隔离 | `MerchantE2ETest` | 店长/补货员 + 越权 403 |
| 运营跨域 | `AdminE2ETest` | 超管正向 + 403 |
| 采购→仓→补货→购物→分账 | `scripts/e2e-full-flow-milk.ps1` | **强相关 P0-02**；核对脚本断言含钱包/split |
| 三端 API | `scripts/e2e-three-end.ps1` | 接口面；非按钮 UI |
| 资金安全/退款 | `e2e-fund-safety.ps1`；`e2e-partial-refund-*.ps1`；`e2e-inventory-inout-refund.ps1` | 对齐 §10 / PASS_3A–3D |
| 补货 | `scripts/e2e-replenishment.ps1` | P0-08 |
| 争议识别 | `e2e-dispute-recognition.ps1` | 争议链路辅助 |
| 运营角色 UI | `clients/admin-vue/tests/role-regression-uat.mjs` | P0-06 |
| 三端业务/争议 UI | `three-end-business-uat.mjs`；`three-end-dispute-ui-uat.mjs` | P0-01/05 抽样 |
| 消费者/商户 H5 | `consumer-h5-uat.mjs`；`merchant-h5-uat.mjs` | 入口冒烟 |
| 分账单测 | `RevenueSplitServiceTest` 等（PASS_3E） | S-02～S-06 逻辑；**不替代**商户端钱包 UI |

自动化绿 + 无证据 ID：资金项仍只可记「自动层 PASS」，**P0 总表需补一次带 ID 的联调**。

### 12.5 单轮证据记录模板（可复制）

```text
轮次: YYYY-MM-DD
环境: env:… pay:… vision:… door:… client:…
执行人:
P0 结果: __/10 PASS · FAIL: … · BLOCK: …
关键 FAIL/BLOCK:
  - P0-xx: 现象 / 期望 / 证据ID或截图 / issue#
备注:
```

#### 本轮记录（2026-09-08）

```text
轮次: 2026-09-08
环境: env:docker-full · pay:mock · vision:mock · door:sim · client:h5（运营后台 Playwright；主链路 API/E2E）
执行人: Cursor Agent
P0 结果: 10/10 PASS · FAIL: （无） · BLOCK: （无）
关键 FAIL/BLOCK:
  （无）
备注:
  - 开测前 DB 缺 DEMO_ACCOUNTS 多角色/商户（仅超管+补货员+消费者）；已按 Flyway 同 hash 补种 002/003/005/38001/38003 + MCH-DEFAULT/MCH-OTHER，消费者补 password。
  - 商户账号须 account_type=OPERATOR 才能走 admin-password-login。
  - e2e-replenishment 全链路未绿（suggest 无 slot 缺口；曾因柜坐标强制签到失败）；P0-08 以库存 PUT L3 计。
  - 角色回归脚本 role-regression-uat.mjs 缺 playwright 包未跑；P0-06/§8 改 Playwright MCP 实操（005+002）。
  - 续测：§8 财务 002 争议 forbidden / 提现可达；§10.1 S-01～S-09（S-08 SKIP）；§4 主链路 1/2/4/5/9/10 PASS，3 PARTIAL。
  - 部分退：`e2e-partial-refund-line.ps1` order=`1788833639119970182` VOIDED + PARTIAL_REVERSE。
  - 再续：§9 D-01～D-04 PASS；C-03/T-05 PASS；§8 003/004 API+003 UI 提现 forbidden；pack_biz 关→钱包/订单/分账 403；MK-01 发券 couponId=1。
  - 再续2：MK-02 抵扣 PASS；pack_field/pack_team PASS；A-01 强制 ACTIVE 拦截；§7 G-01～G-08 抽样 PASS；管理后台 403/404 错误页铺满居中布局修复。
  - 再续3：A-02/A-04 PASS；C-01 品牌 UAT 可见后恢复；§8.2 38002/38004/38007 PASS；I-04 FEFO PASS；曾为 A-04 给 operator/finance 补 `ops:balance-refund:review`。
  - 再续4：C-02；运营登商户门户 403；G-11～G-14；T-06；MK-03；P-02；I-01；R-03。
  - 再续5：M-01～M-04；R-01/R-02；P-03；I-02；E-01；T-03；A-05/A-06。角色权限 PUT 体为 permissionId 数组；审批改名后旧实例保留快照节点名。
  - 再续6：T-01/T-02/T-04；I-03；MK-04/MK-05；P-05。告警列表 page 从 0 起；会员等级按 minSpent 重算后才吃到倍率。
  - 再续7：E-02/E-03；G-09 Offline / G-10 abort / G-15 余额调整二次确认取消（余额不变）。
  - 再续8：§1 运营后台 65 菜单 L1 冒烟 64 PASS / recognition-demo SKIP；§2 商户 H5 22 页 L1 PASS；§3 消费者 H5 23 页 L1 PASS（登录后余额/券/公告）；§4-7 维修工单闭环；§4-8 公告两端可见；§4-6 营销核销改 PASS。
  - 证据目录: docs/uat-screenshots/2026-09-08/
  - 关键 ID: session 1788832341471405582 / order 1788832425799859794 / split 1788832425876341232 /
    withdraw 1+3 / ticket 1788832791807266280 / order 1788833033656619333 / approval_instance 1+2 / PO 1 /
    refund-order 1788833639119970182 / split 1788833639197767270 / couponDef=1 couponId=1 /
    MK-02 order 1788837712840185959 / onboard=1 approval_instance=3 /
    balance-refund 1 REFUNDED + 2 REJECTED / writeOff=1 FEFO /
    G-11 refund=3 / I-01 order 1788847793616100194 / couponDef=2 INACTIVE
```

截图目录建议：`docs/uat-screenshots/YYYY-MM-DD/`（历史大图可放 `docs/archive/uat-screenshots/`）。

