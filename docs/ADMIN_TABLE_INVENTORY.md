# 运营后台全页面 × 全数据表格盘点

> 扫描：`clients/admin-vue` 全部业务路由 + `views/**` 与 `components/**` 里的每一个表格宿主（`<el-table>` 与包了列表状态的 `<CrudTable :table="x">`），按 import 图把组件里的表格归到宿主页面。
> 复现：`python scripts/gen-admin-table-inventory.py`

## 0. 总览

| 指标 | 数量 |
|------|------|
| 业务路由页 | 82 |
| 重定向入口（无组件） | 1 |
| views 文件 | 84 |
| components 文件 | 48 |
| 含表格的页面（含组件下钻） | 68 |
| 表格实例总数（跨文件去重） | 149 |
| 数据列总数（不含 selection/index） | 1163 |

> 计数口径：表格实例与列数按**文件去重**统计；同一组件被多页复用时，在「按路由展开」里会重复出现。

### 视图存在但未挂 router 懒加载

- （无）

### 表格有代码但路由上到不了

> 有表格的文件，从任何路由页顺着 import 都进不去。要么没被引用，要么被外壳页的 `:only` tab 子集挡掉。

- （无）

## 1. 路由总表（一页一行）

| 分组 | 路径 | 标题 | 组件 | 表数 | 列合计 |
|------|------|------|------|------|--------|
| 概览 | `/dashboard` | 运营工作台 | `views/dashboard/DashboardView.vue` | 1 | 5 |
| 概览 | `/analytics` | 数据分析 | `views/analytics/AnalyticsView.vue` | 0 | 0 |
| 概览 | `/footfall` | 客流坪效 | `views/analytics/FootfallView.vue` | 2 | 11 |
| 概览 | `/reports` | 设备报表 | `views/reports/DeviceReportView.vue` | 1 | 18 |
| 概览 | `/finance` | 财务毛利 | `views/finance/FinanceView.vue` | 1 | 9 |
| 财务商户 | `/fund-bills` | 资金账单 | `views/finance/FundBillView.vue` | 2 | 18 |
| 财务商户 | `/platform-fund` | 平台资金池 | `views/finance/PlatformFundView.vue` | 2 | 13 |
| 财务商户 | `/device-rental` | 设备租赁 | `views/finance/DeviceRentalView.vue` | 3 | 22 |
| 概览 | `/sales-reports` | 销售报表 | `views/reports/SalesReportsView.vue` | 1 | 14 |
| 概览 | `/stock-health` | 库存健康 | `views/reports/StockHealthView.vue` | 1 | 13 |
| 设备商品 | `/devices` | 设备管理 | `views/devices/DeviceListView.vue` | 1 | 21 |
| 设备商品 | `/device-map` | 投放地图 | `views/devices/DeviceMapView.vue` | 0 | 0 |
| 设备商品 | `/device-kpi` | 设备可用性 | `views/devices/DeviceKpiView.vue` | 0 | 0 |
| 设备商品 | `/repair-tickets` | 维修工单 | `views/devices/RepairTicketsView.vue` | 1 | 11 |
| 设备商品 | `/device-ops` | 设备运维 | `views/devices/DeviceOpsMonitorView.vue` | 1 | 9 |
| 设备商品 | `/devices/:id` | 设备详情 | `views/devices/DeviceDetailView.vue` | 7 | 42 |
| 交易履约 | `/sessions` | 开门记录 | `views/sessions/SessionListView.vue` | 2 | 20 |
| 交易履约 | `/orders` | 订单管理 | `views/orders/OrderListView.vue` | 4 | 36 |
| 设备商品 | `/skus` | 商品管理 | `views/skus/SkuListView.vue` | 1 | 13 |
| 设备商品 | `/sku-vision` | 识别入驻 | `views/skus/SkuVisionEnrollView.vue` | 0 | 0 |
| 交易履约 | `/disputes` | 争议审核 | `views/disputes/DisputeListView.vue` | 3 | 28 |
| 交易履约 | `/exceptions` | 异常中心 | `views/exceptions/ExceptionListView.vue` | 1 | 12 |
| 履约仓储 | `/replenishment` | 补货调度 | `views/replenishment/ReplenishmentView.vue` | 6 | 51 |
| 财务商户 | `/merchants` | 商户与分账 | `views/merchants/MerchantSplitsView.vue` | 3 | 30 |
| 财务商户 | `/line-managers` | 线长钱包 | `views/finance/LineManagerView.vue` | 6 | 58 |
| 财务商户 | `/line-routes` | 线路与提成 | `views/finance/LineRoutesView.vue` | 6 | 27 |
| 财务商户 | `/machine-payee-bills` | 机器账单（场地方） | `views/finance/MachinePayeeBillsView.vue` | 1 | 10 |
| 财务商户 | `/merchant-fee-ledgers` | 商户费用台账 | `views/finance/MerchantFeeLedgerView.vue` | 1 | 8 |
| 财务商户 | `/merchant-withdraw` | 商户提现 | `views/finance/MerchantWithdrawView.vue` | 3 | 28 |
| 财务商户 | `/reconciliation` | 对账 | `views/reconciliation/ReconciliationView.vue` | 2 | 13 |
| 财务商户 | `/consistency` | 数据一致性 | `views/consistency/ConsistencyView.vue` | 1 | 9 |
| 仓储 | `/warehouse/master` | 仓库与库位 | `views/warehouse/WarehouseMasterPage.vue` | 7 | 43 |
| 仓储 | `/warehouse/procurement` | 采购与供应商 | `views/warehouse/WarehouseProcurementPage.vue` | 12 | 81 |
| 仓储 | `/warehouse/claims` | 待索赔台账 | `views/warehouse/WriteOffClaimLedgerView.vue` | 1 | 10 |
| 仓储 | `/warehouse/monthly-close-sheets` | 月结单台账 | `views/warehouse/WarehouseMonthlyCloseSheetsView.vue` | 2 | 25 |
| 仓储 | `/warehouse/reconciliation` | 供应商对账单 | `views/warehouse/SupplierReconciliationView.vue` | 0 | 0 |
| 仓储 | `/warehouse/expiry-alerts` | 仓库近效期预警 | `views/warehouse/WarehouseExpiryAlertsView.vue` | 1 | 7 |
| 仓储 | `/warehouse/stock` | 库存与盘点 | `views/warehouse/WarehouseStockPage.vue` | 15 | 113 |
| 仓储 | `/warehouse/fulfillment` | 领货与调拨 | `views/warehouse/WarehouseFulfillmentPage.vue` | 8 | 55 |
| 财务商户 | `/recharges` | 充值管理 | `views/recharges/RechargeListView.vue` | 1 | 9 |
| 财务商户 | `/balance-refunds` | 余额退款 | `views/finance/BalanceRefundView.vue` | 1 | 8 |
| 财务商户 | `/invoices` | 开票申请 | `views/finance/InvoiceListView.vue` | 1 | 11 |
| 财务商户 | `/merchant-onboarding` | 进件工作台 | `views/merchants/MerchantOnboardingView.vue` | 1 | 10 |
| 财务商户 | `/users` | 用户余额 | `views/users/UserListView.vue` | 1 | 11 |
| 增长风控 | `/phone-verify` | 手机验证 | `views/users/PhoneVerifyView.vue` | 1 | 6 |
| 设备商品 | `/vision-mappings` | 识别映射 | `views/vision/VisionMappingView.vue` | 3 | 19 |
| 设备商品 | `/learning-library` | 商品学习库 | `views/vision/LearningLibraryView.vue` | 9 | 44 |
| 履约仓储 | `/ota` | 固件版本 | `views/ota/OtaView.vue` | 2 | 16 |
| 履约仓储 | `/sla` | 服务时限监控 | `views/sla/SlaView.vue` | 0 | 0 |
| 增长风控 | `/risk` | 风控 | `views/risk/RiskView.vue` | 2 | 14 |
| 系统 | `/operators` | 运营账号 | `views/system/OperatorManageView.vue` | 1 | 7 |
| 系统 | `/roles` | 角色管理 | `views/system/RoleManageView.vue` | 1 | 6 |
| 系统 | `/departments` | 部门管理 | `views/system/DepartmentManageView.vue` | 1 | 7 |
| 系统 | `/approvals` | 审批流配置 | `views/system/ApprovalConfigView.vue` | 1 | 5 |
| 系统 | `/menus` | 菜单管理 | `views/system/MenuManageView.vue` | 1 | 6 |
| 系统 | `/dicts` | 字典管理 | `views/system/DictManageView.vue` | 2 | 8 |
| 系统 | `/system-configs` | 参数配置 | `views/system/SystemConfigView.vue` | 2 | 10 |
| 系统 | `/alert-rules` | 告警规则 | `views/system/AlertRuleView.vue` | 1 | 6 |
| 系统 | `/scheduled-tasks` | 定时任务 | `views/system/ScheduledTaskView.vue` | 1 | 8 |
| 系统 | `/org-sites` | 组织与点位 | `views/system/OrgSitesView.vue` | 3 | 28 |
| 系统 | `/announcements` | 通知公告 | `views/announcements/AnnouncementsView.vue` | 1 | 6 |
| 系统 | `/audit` | 审计日志 | `views/system/AuditLogView.vue` | 2 | 11 |
| 系统 | `/devops` | DevOps 中心 | `views/system/DevOpsHubView.vue` | 0 | 0 |
| 系统 | `/observability` | 日志中心 | `views/system/ObservabilityView.vue` | 0 | 0 |
| 增长风控 | `/promotions` | 营销活动 | `views/promotions/PromotionsView.vue` | 1 | 10 |
| 增长风控 | `/coupons` | 优惠券 | `views/promotions/CouponsView.vue` | 1 | 11 |
| 增长风控 | `/ad-assets` | 素材库 | `views/growth/AdAssetsView.vue` | 1 | 7 |
| 增长风控 | `/ad-campaigns` | 投放计划 | `views/growth/AdCampaignsView.vue` | 1 | 10 |
| 增长风控 | `/points-redeem` | 积分兑换管理 | `views/growth/PointsRedeemView.vue` | 1 | 10 |
| 增长风控 | `/member-levels` | 会员等级规则 | `views/growth/MemberLevelsView.vue` | 1 | 8 |
| 增长风控 | `/marketing-roi` | 活动效果分析 | `views/growth/MarketingRoiView.vue` | 1 | 11 |
| 履约仓储 | `/replenishment-staff` | 补货员效率 | `views/growth/ReplenishmentStaffView.vue` | 2 | 17 |
| 设备商品 | `/sku-review` | 选品诊断 | `views/growth/SkuReviewView.vue` | 1 | 8 |
| 概览 | `/user-analysis` | 用户分析 | `views/growth/UserAnalysisView.vue` | 2 | 10 |
| 增长风控 | `/notifications` | 消息记录 | `views/growth/NotificationsView.vue` | 1 | 7 |
| 增长风控 | `/feedback` | 用户反馈 | `views/feedback/FeedbackView.vue` | 1 | 8 |
| 系统 | `/profile` | 个人中心 | `views/profile/ProfileView.vue` | 0 | 0 |
| 系统 | `/forbidden` | 无权访问 | `views/error/ForbiddenView.vue` | 0 | 0 |
| 未分组 | `/login` | login | `views/LoginView.vue` | 0 | 0 |
| 履约仓储 | `/print` | 打印单据 | `views/print/PrintView.vue` | 0 | 0 |
| 概览 | `/big-screen` | 运营大屏 | `views/dashboard/BigScreenView.vue` | 0 | 0 |
| 系统 | `/:pathMatch(.*)*` | 页面不存在 | `views/error/NotFoundView.vue` | 0 | 0 |

重定向入口（兼容旧地址，无组件、无表格）：`/warehouse`（warehouse-legacy）

## 2. 按路由展开：每一张表的每一列

### 概览

#### `/dashboard` · 运营工作台

组件：`views/dashboard/DashboardView.vue`

**表 1** — `CrudTable :table="queueCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 优先级 |
| 2 | `—` | 类型 |
| 3 | `title` | 标题 |
| 4 | `—` | 关联 |
| 5 | `detail` | 详情 |

#### `/analytics` · 数据分析

组件：`views/analytics/AnalyticsView.vue`

无表格。详情描述项：`累计营收`、`累计订单`、`待审争议`、`24h 争议率`、`今日毛利率`

#### `/footfall` · 客流坪效

组件：`views/analytics/FootfallView.vue`

**表 1** — `:data="data?.devices || []"`

| # | prop | label |
|---|------|-------|
| 1 | `deviceName` | 柜机 |
| 2 | `opens` | 开门 |
| 3 | `orders` | 订单 |
| 4 | `—` | 转化率 |
| 5 | `—` | 营收 |

**表 2** — `:data="data?.topSkus || []"`

| # | prop | label |
|---|------|-------|
| 1 | `skuName` | 商品 |
| 2 | `skuId` | SKU |
| 3 | `qtySold` | 销量 |
| 4 | `—` | 营收 |
| 5 | `—` | 件均价 |
| 6 | `—` | 营收占比 |

#### `/reports` · 设备报表

组件：`views/reports/DeviceReportView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `deviceId` | 设备编号 |
| 2 | `—` | 设备 |
| 3 | `—` | 状态 |
| 4 | `—` | 近7日在线率 |
| 5 | `—` | 商户 |
| 6 | `—` | 线路 |
| 7 | `—` | 地址 |
| 8 | `—` | 停售 |
| 9 | `—` | 温度 |
| 10 | `—` | 固件 |
| 11 | `orderTotal` | 累计订单 |
| 12 | `—` | 累计营收 |
| 13 | `—` | 累计客单 |
| 14 | `orderToday` | 今日订单 |
| 15 | `—` | 今日营收 |
| 16 | `—` | 今日客单 |
| 17 | `sessionTotal` | 累计会话 |
| 18 | `sessionActive` | 进行中 |

#### `/finance` · 财务毛利

组件：`views/finance/FinanceView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | 商品编号 |
| 2 | `—` | 商品 |
| 3 | `qtySold` | 销量 |
| 4 | `—` | 营收 |
| 5 | `—` | 成本 |
| 6 | `—` | 毛利 |
| 7 | `—` | 毛利率 |
| 8 | `—` | 件均价 |
| 9 | `—` | 件均成本 |

同页 el-descriptions：`累计营收`、`累计成本`、`累计毛利`、`今日报废金额`、`今日报废件数`

#### `/sales-reports` · 销售报表

组件：`views/reports/SalesReportsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `dimKey` | 编码 |
| 2 | `dimLabel` | 名称 |
| 3 | `orderCount` | 订单数 |
| 4 | `qty` | 销量 |
| 5 | `revenueCents` | 营收 |
| 6 | `refundedCents` | 退款 |
| 7 | `—` | 退款单 |
| 8 | `—` | 退款率 |
| 9 | `—` | 净营收 |
| 10 | `—` | 成本 |
| 11 | `marginCents` | 毛利 |
| 12 | `—` | 毛利率 |
| 13 | `—` | 客单价 |
| 14 | `—` | 件均价 |

#### `/stock-health` · 库存健康

组件：`views/reports/StockHealthView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 维度 |
| 2 | `—` | 设备 |
| 3 | `—` | 设备ID |
| 4 | `—` | 商户 |
| 5 | `—` | 路线 |
| 6 | `—` | SKU |
| 7 | `—` | SKU ID |
| 8 | `—` | 库存 |
| 9 | `—` | 容量 |
| 10 | `—` | 阈值 |
| 11 | `—` | 缺货率 |
| 12 | `—` | 断货天 |
| 13 | `—` | 到期日 |

#### `/user-analysis` · 用户分析

组件：`views/growth/UserAnalysisView.vue`

**表 1** — `:data="s?.topRepeatBuyers || []"`

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 用户ID |
| 2 | `—` | 姓名/手机 |
| 3 | `orderCount` | 订单数 |
| 4 | `—` | 累计消费 |
| 5 | `—` | 客单价 |

**表 2** — `:data="s?.dormantUsers || []"`

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 用户ID |
| 2 | `—` | 姓名/手机 |
| 3 | `orderCount` | 累计订单 |
| 4 | `—` | 上次消费 |
| 5 | `—` | 累计消费 |

#### `/big-screen` · 运营大屏

组件：`views/dashboard/BigScreenView.vue`

无表格（图表 / 表单 / 卡片页）。

### 财务商户

#### `/fund-bills` · 资金账单

组件：`views/finance/FundBillView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `bizDate` | 账期 |
| 2 | `merchantId` | 商户编号 |
| 3 | `—` | 商户 |
| 4 | `—` | 订单实付 |
| 5 | `—` | 平台抽成 |
| 6 | `—` | 通道费 |
| 7 | `—` | 已入账 |
| 8 | `—` | 待入账 |
| 9 | `orderCount` | 笔数 |
| 10 | `—` | 固化 |

**表 2** — `:data="displayLedger"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `entryId` | 分录号 |
| 2 | `—` | 财务类型 |
| 3 | `—` | 收支 |
| 4 | `—` | 金额 |
| 5 | `orderId` | 订单 |
| 6 | `deviceId` | 货柜 |
| 7 | `merchantName` | 商户 |
| 8 | `—` | 时间 |

#### `/platform-fund` · 平台资金池

组件：`views/finance/PlatformFundView.vue`

**表 1** — `CrudTable :table="accountCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `accountId` | ID |
| 2 | `channel` | 渠道 |
| 3 | `accountName` | 账户名称 |
| 4 | `—` | 备付余额 |
| 5 | `—` | 状态 |
| 6 | `—` | 更新时间 |

**表 2** — `CrudTable :table="flowsCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `flowId` | ID |
| 2 | `—` | 类型 |
| 3 | `—` | 金额 |
| 4 | `—` | 变动后余额 |
| 5 | `refType` | 关联 |
| 6 | `remark` | 备注 |
| 7 | `—` | 时间 |

#### `/device-rental` · 设备租赁

组件：`views/finance/DeviceRentalView.vue`

**表 1** — `CrudTable :table="billsCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `billId` | ID |
| 2 | `deviceId` | 设备 |
| 3 | `deviceName` | 设备名称 |
| 4 | `merchantId` | 商户 |
| 5 | `billMonth` | 账期月 |
| 6 | `—` | 月租 |
| 7 | `—` | 状态 |
| 8 | `—` | 锁柜 |

**表 2** — `CrudTable :table="depositsCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `depositId` | ID |
| 2 | `deviceId` | 设备 |
| 3 | `merchantId` | 商户 |
| 4 | `—` | 押金本金 |
| 5 | `—` | 可用余额 |
| 6 | `—` | 撤机安装费 |
| 7 | `—` | 收取方式 |
| 8 | `—` | 状态 |
| 9 | `—` | 登记时间 |

**表 3** — `CrudTable :table="eventsCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `eventId` | ID |
| 2 | `—` | 类型 |
| 3 | `—` | 金额 |
| 4 | `reason` | 依据 |
| 5 | `—` | 时间 |

#### `/merchants` · 商户与分账

组件：`views/merchants/MerchantSplitsView.vue`

**表 1** — `CrudTable :table="crudMerchants"`

| # | prop | label |
|---|------|-------|
| 1 | `merchantId` | 商户编号 |
| 2 | `—` | 商户 |
| 3 | `—` | 抽成 |
| 4 | `—` | 现场作业 |
| 5 | `—` | 经营工具 |
| 6 | `—` | 团队设置 |
| 7 | `—` | 商户改货道 |
| 8 | `—` | 商户改价 |
| 9 | `deviceCount` | 设备数 |
| 10 | `—` | 商户状态 |
| 11 | `—` | 联系人 |
| 12 | `—` | 电话 |
| 13 | `—` | 备注 |

**表 2** — `:data="roleTemplates"`

| # | prop | label |
|---|------|-------|
| 1 | `templateName` | 岗位 |
| 2 | `templateCode` | 编码 |
| 3 | `description` | 说明 |
| 4 | `permissionHint` | 权限提示 |
| 5 | `—` | 权限数 |

**表 3** — `CrudTable :table="crudSplits"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 分账编号 |
| 2 | `—` | 订单 |
| 3 | `—` | 商户 |
| 4 | `—` | 商户收入 |
| 5 | `—` | 平台抽成 |
| 6 | `—` | 订单总额 |
| 7 | `—` | 设备 |
| 8 | `—` | 结算批次 |
| 9 | `—` | 状态 |
| 10 | `—` | 失败原因 |
| 11 | `—` | 创建时间 |
| 12 | `—` | 结算时间 |

#### `/line-managers` · 线长钱包

组件：`views/finance/LineManagerView.vue`

**表 1** — `:data="managers"`

| # | prop | label |
|---|------|-------|
| 1 | `managerId` | 经理编号 |
| 2 | `—` | 姓名 |
| 3 | `phone` | 手机 |
| 4 | `orgName` | 组织 |
| 5 | `userId` | 绑定用户 |
| 6 | `wxOpenid` | 微信 OpenID |
| 7 | `—` | 余额(元) |
| 8 | `—` | 冻结(元) |
| 9 | `—` | 绑柜 |
| 10 | `—` | 佣金比例 |
| 11 | `commissionFixedCents` | 固定分/单 |
| 12 | `status` | 状态 |
| 13 | `—` | 创建时间 |
| 14 | `—` | 操作 |

**表 2** — `:data="withdraws"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `requestId` | 单号 |
| 2 | `requestNo` | 业务单号 |
| 3 | `managerName` | 线长 |
| 4 | `phone` | 手机 |
| 5 | `—` | 收款方 |
| 6 | `—` | 金额(元) |
| 7 | `—` | 手续费 |
| 8 | `status` | 状态 |
| 9 | `—` | 通道 |
| 10 | `payoutRef` | 回执 |
| 11 | `—` | 渠道对账 |
| 12 | `payoutMessage` | 打款说明 |
| 13 | `reviewRemark` | 审核备注 |
| 14 | `—` | 申请时间 |
| 15 | `—` | 打款时间 |
| 16 | `—` | 操作 |

**表 3** — `:data="promoTasks"`

| # | prop | label |
|---|------|-------|
| 1 | `taskId` | 编号 |
| 2 | `managerId` | 线长ID |
| 3 | `title` | 任务 |
| 4 | `routeCode` | 线路 |
| 5 | `—` | 进度 |
| 6 | `—` | 奖金(元) |
| 7 | `—` | 状态 |
| 8 | `—` | 截止 |

**表 4** — `:data="payoutAccounts"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `channel` | 通道 |
| 3 | `accountName` | 户名 |
| 4 | `accountNoMask` | 账号 |
| 5 | `—` | 默认 |
| 6 | `status` | 状态 |
| 7 | `—` | 操作 |

**表 5** — `:data="ledgers"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `—` | 变动(元) |
| 3 | `—` | 余额后 |
| 4 | `—` | 冻结后 |
| 5 | `—` | 关联 |
| 6 | `remark` | 备注 |
| 7 | `—` | 时间 |

**表 6** — `:data="kpi?.dailies || []"`

| # | prop | label |
|---|------|-------|
| 1 | `bizDate` | 日期 |
| 2 | `—` | 成交总额 |
| 3 | `—` | 佣金 |
| 4 | `orderCount` | 单量 |
| 5 | `—` | 客单价 |
| 6 | `—` | 佣金率 |

#### `/line-routes` · 线路与提成

组件：`views/finance/LineRoutesView.vue`

**表 1** — `CrudTable :table="routesCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `id` | ID |
| 2 | `routeName` | 线路名称 |
| 3 | `managerId` | 线长（负责人 userId） |
| 4 | `remark` | 备注 |
| 5 | `—` | 状态 |

**表 2** — `CrudTable :table="summaryManagersCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `managerId` | 线长 userId |
| 2 | `orderCount` | 订单数 |
| 3 | `—` | 销售额（元） |
| 4 | `—` | 提成（元） |
| 5 | `—` | 其中退款冲回（元） |

**表 3** — `CrudTable :table="summaryStaffsCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `staffUserId` | 补货员 userId |
| 2 | `restockQty` | 补货件数 |
| 3 | `—` | 佣金（元） |
| 4 | `—` | 其中退款冲回（元） |

**表 4** — `CrudTable :table="walletsCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `staffUserId` | 补货员 userId |
| 2 | `—` | 余额（元） |

**表 5** — `CrudTable :table="membersCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `id` | ID |
| 2 | `userId` | userId |
| 3 | `—` | 角色 |
| 4 | `—` | 提成基数 |
| 5 | `commissionRateBps` | GMV 比例(bps) |
| 6 | `—` | 每件佣金(元) |

**表 6** — `CrudTable :table="ledgerCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 时间 |
| 2 | `entryType` | 类型 |
| 3 | `—` | 金额（元） |
| 4 | `—` | 余额（元） |
| 5 | `remark` | 备注 |

#### `/machine-payee-bills` · 机器账单（场地方）

组件：`views/finance/MachinePayeeBillsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `billId` | ID |
| 2 | `billMonth` | 账期 |
| 3 | `deviceId` | 柜机 |
| 4 | `—` | 费用类型 |
| 5 | `—` | 金额（元） |
| 6 | `—` | 分成基数 GMV（元） |
| 7 | `payeeName` | 收款人（场地方） |
| 8 | `payeePhone` | 电话 |
| 9 | `—` | 状态 |
| 10 | `remark` | 备注 |

#### `/merchant-fee-ledgers` · 商户费用台账

组件：`views/finance/MerchantFeeLedgerView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `ledgerId` | ID |
| 2 | `merchantId` | 商户 |
| 3 | `feeDate` | 费用日期 |
| 4 | `—` | 费用类型 |
| 5 | `—` | 金额（元） |
| 6 | `—` | 状态 |
| 7 | `—` | 结清时间 |
| 8 | `remark` | 备注 |

#### `/merchant-withdraw` · 商户提现

组件：`views/finance/MerchantWithdrawView.vue`

**表 1** — `CrudTable :table="walletsCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `merchantId` | 商户编号 |
| 2 | `merchantName` | 名称 |
| 3 | `—` | 联系电话 |
| 4 | `—` | 余额(元) |
| 5 | `—` | 冻结(元) |
| 6 | `—` | 可用(元) |
| 7 | `status` | 状态 |

**表 2** — `CrudTable :table="wdCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `requestId` | 单号 |
| 2 | `requestNo` | 业务单号 |
| 3 | `merchantId` | 商户编号 |
| 4 | `merchantName` | 商户 |
| 5 | `—` | 收款方 |
| 6 | `—` | 金额(元) |
| 7 | `—` | 手续费 |
| 8 | `status` | 状态 |
| 9 | `—` | 通道 |
| 10 | `payoutRef` | 回执 |
| 11 | `—` | 渠道对账 |
| 12 | `payoutMessage` | 打款说明 |
| 13 | `reviewRemark` | 审核备注 |
| 14 | `—` | 申请时间 |

**表 3** — `:data="ledgers"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `—` | 变动(元) |
| 3 | `—` | 余额后 |
| 4 | `—` | 冻结后 |
| 5 | `—` | 关联单号 |
| 6 | `remark` | 备注 |
| 7 | `—` | 时间 |

同页 el-descriptions：`全局费率`、`本商户覆盖`、`生效费率`

#### `/reconciliation` · 对账

组件：`views/reconciliation/ReconciliationView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 对账ID |
| 2 | `—` | 账期 |
| 3 | `—` | 渠道 |
| 4 | `—` | 状态 |
| 5 | `—` | 差额 |
| 6 | `—` | 未匹配笔数 |
| 7 | `—` | 创建时间 |

**表 2** — `:data="detail.lines || []"`

| # | prop | label |
|---|------|-------|
| 1 | `platformTradeNo` | 平台流水 |
| 2 | `merchantOrderNo` | 商户单号 |
| 3 | `—` | 金额 |
| 4 | `—` | 交易类型 |
| 5 | `—` | 交易时间 |
| 6 | `—` | 匹配 |

同页 el-descriptions：`对账ID`、`日期`、`渠道`、`状态`、`渠道合计`、`本账合计`、`差额`、`未匹配笔数`、`创建时间`、`完成时间`

#### `/consistency` · 数据一致性

组件：`views/consistency/ConsistencyView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `—` | 键 |
| 3 | `tableName` | 表 |
| 4 | `—` | 级别 |
| 5 | `—` | 基准 |
| 6 | `—` | 对照 |
| 7 | `—` | 说明 |
| 8 | `—` | 状态 |
| 9 | `—` | 检出时间 |

#### `/recharges` · 充值管理

组件：`views/recharges/RechargeListView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `orderId` | 充值单 |
| 2 | `—` | 用户 |
| 3 | `—` | 金额 |
| 4 | `—` | 渠道 |
| 5 | `—` | 外部单号 |
| 6 | `—` | 状态 |
| 7 | `—` | 创建时间 |
| 8 | `—` | 支付时间 |
| 9 | `—` | 退款时间 |

#### `/balance-refunds` · 余额退款

组件：`views/finance/BalanceRefundView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `requestNo` | 申请号 |
| 2 | `—` | 用户 |
| 3 | `—` | 金额 |
| 4 | `—` | 状态 |
| 5 | `—` | 申请原因 |
| 6 | `—` | 审核备注 |
| 7 | `—` | 失败原因 |
| 8 | `—` | 申请时间 |

#### `/invoices` · 开票申请

组件：`views/finance/InvoiceListView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 申请号 |
| 2 | `—` | 订单 |
| 3 | `—` | 用户 |
| 4 | `—` | 抬头 |
| 5 | `—` | 税号 |
| 6 | `—` | 邮箱 |
| 7 | `—` | 金额 |
| 8 | `—` | 状态 |
| 9 | `—` | 驳回原因 |
| 10 | `—` | 申请时间 |
| 11 | `—` | 开票时间 |

#### `/merchant-onboarding` · 进件工作台

组件：`views/merchants/MerchantOnboardingView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `merchantId` | 商户 |
| 2 | `channel` | 渠道 |
| 3 | `status` | 状态 |
| 4 | `—` | 外部商户号 |
| 5 | `—` | 外部单号 |
| 6 | `—` | 支付模式 |
| 7 | `—` | 备注 |
| 8 | `—` | 最近同步 |
| 9 | `—` | 创建时间 |
| 10 | `—` | 更新时间 |

#### `/users` · 用户余额

组件：`views/users/UserListView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 用户编号 |
| 2 | `—` | 姓名 |
| 3 | `—` | 昵称 |
| 4 | `—` | 手机号 |
| 5 | `—` | 角色 |
| 6 | `—` | 余额 |
| 7 | `—` | 实名 |
| 8 | `—` | 会员等级 |
| 9 | `—` | 积分 |
| 10 | `—` | 黑名单 |
| 11 | `—` | 注册时间 |

### 设备商品

#### `/devices` · 设备管理

组件：`views/devices/DeviceListView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `deviceId` | 设备编号 |
| 2 | `—` | 设备 |
| 3 | `—` | 类型 |
| 4 | `—` | 投放场景 |
| 5 | `—` | 状态 |
| 6 | `—` | 锁机 |
| 7 | `—` | 能否购买 |
| 8 | `—` | 生命周期 |
| 9 | `—` | 柜内温度 |
| 10 | `—` | 锁机原因 |
| 11 | `—` | 固件 |
| 12 | `—` | 地址 |
| 13 | `—` | IMEI |
| 14 | `—` | 资产方 |
| 15 | `—` | 路线 |
| 16 | `—` | 商户 |
| 17 | `—` | 所属仓库 |
| 18 | `—` | 退款方式 |
| 19 | `—` | 最近开门 |
| 20 | `—` | 开门状态 |
| 21 | `—` | 更新时间 |

#### `/device-map` · 投放地图

组件：`views/devices/DeviceMapView.vue`

无表格（图表 / 表单 / 卡片页）。

#### `/device-kpi` · 设备可用性

组件：`views/devices/DeviceKpiView.vue`

无表格（图表 / 表单 / 卡片页）。

#### `/repair-tickets` · 维修工单

组件：`views/devices/RepairTicketsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `ticketId` | 工单号 |
| 2 | `deviceId` | 设备 |
| 3 | `merchantName` | 商户 |
| 4 | `title` | 标题 |
| 5 | `faultType` | 故障类型 |
| 6 | `priority` | 优先级 |
| 7 | `status` | 状态 |
| 8 | `—` | 负责人 |
| 9 | `—` | 备注 |
| 10 | `—` | 创建时间 |
| 11 | `—` | 关闭时间 |

同页 el-descriptions：`工单号`、`设备`、`商户`、`标题`、`故障类型`、`状态`、`优先级`、`负责人`、`创建人`、`备注`、`创建时间`、`更新时间`、`关闭时间`

#### `/device-ops` · 设备运维

组件：`views/devices/DeviceOpsMonitorView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `eventId` | 事件ID |
| 2 | `eventType` | 类型 |
| 3 | `—` | 级别 |
| 4 | `—` | 设备名称 |
| 5 | `deviceId` | 设备编号 |
| 6 | `title` | 标题 |
| 7 | `detail` | 详情 |
| 8 | `—` | 账龄 |
| 9 | `—` | 时间 |

#### `/devices/:id` · 设备详情

组件：`views/devices/DeviceDetailView.vue`

**表 1** — `:data="decode.rows || []"` · v-if=`decode` · 来源 `components/device/DeviceJiangyiCard.vue`

| # | prop | label |
|---|------|-------|
| 1 | `classId` | 识别编号 |
| 2 | `—` | 识别名（classes 原文） |
| 3 | `—` | 厂商商品 |
| 4 | `—` | 厂商条码 |
| 5 | `—` | 本平台同条码商品 |
| 6 | `—` | 当前挂接 |

**表 2** — `:data="gatherProgress?.checks || []"` · 来源 `components/device/DeviceJiangyiCard.vue`

| # | prop | label |
|---|------|-------|
| 1 | `name` | 商品 |
| 2 | `status` | 审核状态 |
| 3 | `rejectCause` | 驳回原因 |

**表 3** — `:data="rows"` · 来源 `components/device/DevicePricingCard.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `—` | SKU |
| 3 | `—` | 基准价 |
| 4 | `—` | 覆盖价（元） |
| 5 | `—` | 启用 |
| 6 | `—` | 当前成交价 |
| 7 | `—` | 可改区间 |
| 8 | `sellableQty` | 库存 |
| 9 | `—` | 操作 |

**表 4** — `:data="sessions"` · 来源 `components/device/DeviceRelatedRecordsTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 开门编号 |
| 2 | `—` | 状态 |
| 3 | `—` | 类型 |
| 4 | `—` | 入口 |
| 5 | `—` | 订单 |
| 6 | `—` | 失败原因 |
| 7 | `—` | 时间 |
| 8 | `—` | 操作 |

**表 5** — `:data="orders"` · 来源 `components/device/DeviceRelatedRecordsTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 订单 |
| 2 | `—` | 状态 |
| 3 | `—` | 渠道 |
| 4 | `—` | 金额 |
| 5 | `—` | 优惠 |
| 6 | `—` | 时间 |
| 7 | `—` | 操作 |

**表 6** — `:data="repairTickets"` · v-if=`repairTickets.length` · 来源 `components/device/DeviceRemoteOpsCard.vue`

| # | prop | label |
|---|------|-------|
| 1 | `ticketId` | 单号 |
| 2 | `title` | 标题 |
| 3 | `status` | 状态 |
| 4 | `—` | 优先级 |
| 5 | `createdAt` | 创建 |
| 6 | `—` | 更新 |

**表 7** — `:data="envRows"` · 来源 `components/device/DeviceTempEnvTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 指标 |
| 2 | `—` | 数值 |
| 3 | `—` | 上报时间 |

#### `/skus` · 商品管理

组件：`views/skus/SkuListView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | SKU |
| 2 | `—` | 主图 |
| 3 | `barcode` | 条码 |
| 4 | `—` | 名称 |
| 5 | `brand` | 品牌 |
| 6 | `spec` | 规格 |
| 7 | `unit` | 单位 |
| 8 | `operationScope` | 归属 |
| 9 | `—` | 售价 |
| 10 | `—` | 成本 |
| 11 | `category` | 类目 |
| 12 | `—` | 状态 |
| 13 | `—` | 添加时间 |

#### `/sku-vision` · 识别入驻

组件：`views/skus/SkuVisionEnrollView.vue`

无表格（图表 / 表单 / 卡片页）。

#### `/vision-mappings` · 识别映射

组件：`views/vision/VisionMappingView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `classId` | 识别编号 |
| 2 | `textName` | 识别名称 |
| 3 | `—` | 平台商品 |
| 4 | `skuId` | 商品编号 |
| 5 | `modelName` | 模型 |
| 6 | `status` | 状态 |
| 7 | `source` | 来源 |

**表 2** — `:data="deployments"`

| # | prop | label |
|---|------|-------|
| 1 | `modelName` | 模型 |
| 2 | `industrialControlModel` | 机型 |
| 3 | `status` | 状态 |
| 4 | `—` | 下发时间 |
| 5 | `—` | 确认时间 |
| 6 | `failReason` | 失败原因 |

**表 3** — `:data="reviewPlan.rows"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 处置 |
| 2 | `—` | 识别编号 |
| 3 | `—` | 识别名称 |
| 4 | `—` | 平台商品 |
| 5 | `—` | 状态 |
| 6 | `reason` | 说明 |

#### `/learning-library` · 商品学习库

组件：`views/vision/LearningLibraryView.vue`

**表 1** — `:data="pagedProducts"`

| # | prop | label |
|---|------|-------|
| 1 | `name` | 厂商商品名 |
| 2 | `textName` | 识别名称（classes） |
| 3 | `barCode` | 条码 |
| 4 | `—` | 我方商品 |
| 5 | `—` | 已上架柜机（ACTIVE 识别映射） |
| 6 | `—` | 操作 |

**表 2** — `:data="pagedCabinets"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 柜机 |
| 2 | `classesVersion` | 模型 classes 版本 |
| 3 | `—` | 映射复核 |
| 4 | `activeMappingCount` | 在架类别 |
| 5 | `—` | 采集模式 |
| 6 | `—` | 最近通信 |
| 7 | `—` | 操作 |

**表 3** — `:data="pagedModels"`

| # | prop | label |
|---|------|-------|
| 1 | `modelName` | 模型名 |
| 2 | `customerModelName` | 厂商初命名 |
| 3 | `quantity` | 类别数 |
| 4 | `industrialControlModel` | 工控机型 |
| 5 | `modelTextUrl` | classes 地址 |

**表 4** — `:data="pagedTrainings"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 发起时间 |
| 2 | `—` | 柜机 |
| 3 | `—` | 商品 |
| 4 | `modelName` | 模型 |
| 5 | `—` | 状态 |
| 6 | `—` | 完成时间 |

**表 5** — `:data="shelfResult.devices"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 柜机 |
| 2 | `—` | 结果 |
| 3 | `—` | 识别编号 |
| 4 | `—` | 说明 |

**表 6** — `:data="studyingRows"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `—` | 状态 |
| 3 | `—` | 驳回原因 |
| 4 | `—` | 图片 |

**表 7** — `:data="gatherCheckRows"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 id |
| 2 | `—` | 商品 |
| 3 | `—` | 状态 |
| 4 | `—` | 驳回原因 |
| 5 | `—` | 图片 |

**表 8** — `:data="consolePending"` · v-if=`consolePending.length`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `brandName` | 品牌 |
| 3 | `specs` | 规格 |
| 4 | `—` | 图片 |

**表 9** — `:data="consoleRun.upload"` · v-if=`consoleRun.upload.length`

| # | prop | label |
|---|------|-------|
| 1 | `productInfo` | 条目 |
| 2 | `—` | 上传 |
| 3 | `—` | 厂商判词 |

#### `/sku-review` · 选品诊断

组件：`views/growth/SkuReviewView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | SKU |
| 2 | `skuName` | 商品 |
| 3 | `category` | 分类 |
| 4 | `—` | 动销表现 |
| 5 | `salesQty` | 销量 |
| 6 | `—` | 营收(元) |
| 7 | `stockDays` | 库存天数 |
| 8 | `—` | 评审状态 |

### 交易履约

#### `/sessions` · 开门记录

组件：`views/sessions/SessionListView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `sessionId` | 会话编号 |
| 2 | `—` | 类型 |
| 3 | `—` | 入口渠道 |
| 4 | `—` | 预授权 |
| 5 | `—` | 购物/识别 |
| 6 | `—` | 用户 |
| 7 | `—` | 设备 |
| 8 | `—` | 订单 |
| 9 | `—` | 状态 |
| 10 | `—` | 等待原因 |
| 11 | `—` | 滞留 / 时限 |
| 12 | `—` | 失败原因 |
| 13 | `—` | 更新时间 |
| 14 | `—` | 时长 |

**表 2** — `:data="rows"` · 来源 `components/order/OrderJiangyiVideoDialog.vue`

| # | prop | label |
|---|------|-------|
| 1 | `serialNum` | 分片 |
| 2 | `videoQuantity` | 总片数 |
| 3 | `—` | 视频 |
| 4 | `reportedAt` | 上报时间 |
| 5 | `deviceId` | 柜机 |
| 6 | `—` | 操作 |

同页 el-descriptions：`会话`、`设备`、`订单`、`状态`、`入口渠道`、`失败原因`、`开门`、`关门`

#### `/orders` · 订单管理

组件：`views/orders/OrderListView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `orderId` | 订单号 |
| 2 | `—` | 流水号 |
| 3 | `—` | 商品 |
| 4 | `—` | 金额 |
| 5 | `—` | 优惠 |
| 6 | `—` | 会话 |
| 7 | `—` | 用户 |
| 8 | `—` | 设备 |
| 9 | `—` | 商户 |
| 10 | `—` | 订单状态 |
| 11 | `—` | 支付状态 |
| 12 | `—` | 退款状态 |
| 13 | `—` | 支付渠道 |
| 14 | `—` | 分账 |
| 15 | `—` | 扣库存 |
| 16 | `—` | 退款策略 |
| 17 | `—` | 退款时间 |
| 18 | `—` | 账龄 |
| 19 | `—` | 创建时间 |

**表 2** — `:data="detail.lines || detail.items || []"`

| # | prop | label |
|---|------|-------|
| 1 | `skuName` | 商品 |
| 2 | `skuId` | SKU |
| 3 | `batchNo` | 批次 |
| 4 | `quantity` | 数量 |
| 5 | `—` | 单价 |
| 6 | `—` | 小计 |

**表 3** — `:data="partialRows"`

| # | prop | label |
|---|------|-------|
| 1 | `skuName` | 商品 |
| 2 | `skuId` | SKU |
| 3 | `maxQty` | 可退 |
| 4 | `—` | 退款数量 |
| 5 | `—` | 回库 |

**表 4** — `:data="rows"` · 来源 `components/order/OrderJiangyiVideoDialog.vue`

| # | prop | label |
|---|------|-------|
| 1 | `serialNum` | 分片 |
| 2 | `videoQuantity` | 总片数 |
| 3 | `—` | 视频 |
| 4 | `reportedAt` | 上报时间 |
| 5 | `deviceId` | 柜机 |
| 6 | `—` | 操作 |

同页 el-descriptions：`订单号`、`流水号`、`会话`、`设备`、`商户`、`状态`、`金额`、`已退金额`、`原价`、`券优惠`、`会员优惠`、`差额说明`、`支付渠道`、`支付时间`、`分账状态`、`退款策略`、`扣库存`、`创建时间`、`退款时间`

#### `/disputes` · 争议审核

组件：`views/disputes/DisputeListView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `ticketId` | 工单号 |
| 2 | `—` | 工单 |
| 3 | `—` | 置信度 |
| 4 | `—` | 设备 |
| 5 | `—` | 会话 |
| 6 | `—` | 关联订单 |
| 7 | `—` | 状态 |
| 8 | `—` | 处理人 |
| 9 | `—` | 分类 |
| 10 | `—` | 优先级 |
| 11 | `—` | 已扣金额 |
| 12 | `—` | 建议金额 |
| 13 | `—` | 已退金额 |
| 14 | `—` | SLA |
| 15 | `—` | 证据 |
| 16 | `—` | 创建时间 |
| 17 | `—` | 结案时间 |

**表 2** — `:data="selected.suggestedItems"`

| # | prop | label |
|---|------|-------|
| 1 | `skuName` | 商品 |
| 2 | `skuId` | SKU |
| 3 | `quantity` | 数量 |
| 4 | `—` | 单价 |
| 5 | `—` | 小计 |

**表 3** — `:data="rows"` · 来源 `components/order/OrderJiangyiVideoDialog.vue`

| # | prop | label |
|---|------|-------|
| 1 | `serialNum` | 分片 |
| 2 | `videoQuantity` | 总片数 |
| 3 | `—` | 视频 |
| 4 | `reportedAt` | 上报时间 |
| 5 | `deviceId` | 柜机 |
| 6 | `—` | 操作 |

同页 el-descriptions：`工单`、`会话`、`设备`、`原因`、`检出类`、`已扣金额`、`建议金额`、`差额说明`、`已退金额`、`SLA`、`状态`、`处理人`、`处理时间`、`关联订单`

#### `/exceptions` · 异常中心

组件：`views/exceptions/ExceptionListView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `exceptionId` | 异常编号 |
| 2 | `—` | 异常 |
| 3 | `—` | 级别 |
| 4 | `—` | 类型 |
| 5 | `—` | 设备 |
| 6 | `—` | 会话 |
| 7 | `—` | 订单 |
| 8 | `—` | 用户 |
| 9 | `—` | 状态 |
| 10 | `—` | 处理时限 |
| 11 | `—` | 负责人 |
| 12 | `—` | 创建时间 |

同页 el-descriptions：`异常编号`、`异常类型`、`严重级别`、`处理状态`、`异常内容`、`详细信息`、`关联设备`、`关联会话`、`关联订单`、`时限截止`

### 履约仓储

#### `/replenishment` · 补货调度

组件：`views/replenishment/ReplenishmentView.vue`

**表 1** — `CrudTable :table="crudRoutes"`

特殊列：expand

| # | prop | label |
|---|------|-------|
| 1 | `—` | 路线 |
| 2 | `routeId` | 路线ID |
| 3 | `—` | 设备数 |
| 4 | `plannedDate` | 计划日期 |
| 5 | `—` | 状态 |
| 6 | `—` | 操作 |

**表 2** — `:data="sortedRouteTasks(row.tasks)"` · 展开行内子表

| # | prop | label |
|---|------|-------|
| 1 | `—` | 任务 |
| 2 | `—` | 设备 |
| 3 | `—` | 设备ID |
| 4 | `—` | 任务状态 |
| 5 | `—` | 人员 |
| 6 | `—` | 签到 |
| 7 | `—` | 用时 |
| 8 | `—` | 完成 |
| 9 | `—` | 出库单 |
| 10 | `—` | 操作 |

**表 3** — `:data="pagedFulfillment"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `taskId` | 任务 |
| 2 | `—` | 设备 |
| 3 | `—` | 设备ID |
| 4 | `—` | 路线 |
| 5 | `—` | 状态 |
| 6 | `—` | 人员 |
| 7 | `—` | 签到 / GPS |
| 8 | `—` | 用时 |
| 9 | `—` | 完成时间 |
| 10 | `—` | 出库单 |
| 11 | `—` | 备注 |

**表 4** — `CrudTable :table="crudShortages"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 设备 |
| 2 | `—` | 设备ID |
| 3 | `slotCode` | 货道 |
| 4 | `assignedSkuName` | 商品 |
| 5 | `bookQty` | 账面 |
| 6 | `minLevel` | 最低 |
| 7 | `parLevel` | 目标 |
| 8 | `—` | 状态 |

**表 5** — `CrudTable :table="crudExpiry"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 设备 |
| 2 | `—` | 设备ID |
| 3 | `skuId` | 商品 SKU |
| 4 | `batchNo` | 批次 |
| 5 | `lotId` | 批次 ID |
| 6 | `quantity` | 数量 |
| 7 | `—` | 原因 |
| 8 | `—` | 状态 |
| 9 | `—` | 创建时间 |
| 10 | `—` | 操作 |

**表 6** — `:data="rows"` · 来源 `components/replenishment/RestockVideoDialog.vue`

| # | prop | label |
|---|------|-------|
| 1 | `orderNo` | 补货会话 |
| 2 | `serialNum` | 分片 |
| 3 | `videoQuantity` | 总片数 |
| 4 | `—` | 视频 |
| 5 | `reportedAt` | 上报时间 |
| 6 | `—` | 操作 |

#### `/ota` · 固件版本

组件：`views/ota/OtaView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `appVersion` | 版本 |
| 2 | `channel` | 渠道 |
| 3 | `status` | 状态 |
| 4 | `—` | 强制 |
| 5 | `—` | 灰度% |
| 6 | `—` | 定向设备 |
| 7 | `minVersion` | 最低版本 |
| 8 | `—` | 发布时间 |
| 9 | `releaseNotes` | 说明 |

**表 2** — `:data="progressItems"`

| # | prop | label |
|---|------|-------|
| 1 | `deviceId` | 设备 |
| 2 | `—` | 当前版本 |
| 3 | `—` | 目标版本 |
| 4 | `—` | 状态 |
| 5 | `—` | 进度 |
| 6 | `—` | 失败原因 |
| 7 | `—` | 最近上报 |

#### `/sla` · 服务时限监控

组件：`views/sla/SlaView.vue`

无表格。详情描述项：`24h 开门成功率`、`当前在线率`、`24h 开门均时长`、`争议时限达标率`、`开放争议`、`逾期争议`

#### `/replenishment-staff` · 补货员效率

组件：`views/growth/ReplenishmentStaffView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 工号 |
| 2 | `—` | 姓名 |
| 3 | `—` | 手机 |
| 4 | `totalTasks` | 任务数 |
| 5 | `completedTasks` | displayLabel('order_status', 'COMPLETED') |
| 6 | `—` | 完成率 |
| 7 | `—` | 平均耗时(分) |
| 8 | `openTasks` | 待办 |
| 9 | `avgDailyTasks` | 日均任务 |

**表 2** — `CrudTable :table="tallyCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 时间 |
| 2 | `deviceId` | 设备 |
| 3 | `—` | 操作员 |
| 4 | `—` | 动作 |
| 5 | `—` | 商品 |
| 6 | `quantity` | 数量 |
| 7 | `—` | 已应用 |
| 8 | `—` | 任务 |

#### `/print` · 打印单据

组件：`views/print/PrintView.vue`

无表格（图表 / 表单 / 卡片页）。

### 仓储

#### `/warehouse/master` · 仓库与库位

组件：`views/warehouse/WarehouseMasterPage.vue`

**表 1** — `:data="receiveForm.lines"` · 来源 `components/warehouse/WarehousePurchaseDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次（收货必填） |
| 3 | `expiryDate` | 到期日（收货必填） |
| 4 | `orderedQty` | 采购数 |
| 5 | `—` | 待收 |
| 6 | `—` | 累计收货 |

**表 2** — `:data="returnForm.lines"` · 来源 `components/warehouse/WarehousePurchaseDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `expiryDate` | 到期日 |
| 4 | `receivedQty` | 已收 |
| 5 | `returnedQty` | 已退 |
| 6 | `—` | 可退 |
| 7 | `—` | 本次退货 |

**表 3** — `:data="stocktakeDetail.lines || []"` · 来源 `components/warehouse/WarehouseStocktakeDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `productionDate` | 生产日期 |
| 4 | `expiryDate` | 到期日 |
| 5 | `bookQty` | 账面 |
| 6 | `—` | 实盘 |
| 7 | `—` | 差异 |
| 8 | `—` | 状态 |

**表 4** — `:data="bins"` · 来源 `components/warehouse/WarehouseBinsTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 货位编码 |
| 2 | `binName` | 货位名称 |
| 3 | `—` | 仓库 |
| 4 | `—` | 状态 |
| 5 | `—` | 操作 |

**表 5** — `:data="binStock"` · 来源 `components/warehouse/WarehouseBinsTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 货位 |
| 2 | `—` | 商品 |
| 3 | `batchNo` | 批次 |
| 4 | `productionDate` | 生产日期 |
| 5 | `expiryDate` | 到期日 |
| 6 | `quantity` | 数量 |

**表 6** — `:data="derived"` · 来源 `components/warehouse/WarehouseTransferReceiveDialog.vue`

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | SKU |
| 2 | `batchNo` | 批次 |
| 3 | `quantity` | 发运 |
| 4 | `—` | 实收 |
| 5 | `—` | 损耗 |
| 6 | `—` | 损耗原因 |

**表 7** — `CrudTable :table="table"` · 来源 `components/warehouse/WarehouseOverviewTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `warehouseId` | 仓库编号 |
| 2 | `—` | 仓库 |
| 3 | `address` | 地址 |
| 4 | `—` | 负责人 |
| 5 | `—` | 状态 |

#### `/warehouse/procurement` · 采购与供应商

组件：`views/warehouse/WarehouseProcurementPage.vue`

**表 1** — `:data="receiveForm.lines"` · 来源 `components/warehouse/WarehousePurchaseDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次（收货必填） |
| 3 | `expiryDate` | 到期日（收货必填） |
| 4 | `orderedQty` | 采购数 |
| 5 | `—` | 待收 |
| 6 | `—` | 累计收货 |

**表 2** — `:data="returnForm.lines"` · 来源 `components/warehouse/WarehousePurchaseDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `expiryDate` | 到期日 |
| 4 | `receivedQty` | 已收 |
| 5 | `returnedQty` | 已退 |
| 6 | `—` | 可退 |
| 7 | `—` | 本次退货 |

**表 3** — `:data="stocktakeDetail.lines || []"` · 来源 `components/warehouse/WarehouseStocktakeDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `productionDate` | 生产日期 |
| 4 | `expiryDate` | 到期日 |
| 5 | `bookQty` | 账面 |
| 6 | `—` | 实盘 |
| 7 | `—` | 差异 |
| 8 | `—` | 状态 |

**表 4** — `:data="derived"` · 来源 `components/warehouse/WarehouseTransferReceiveDialog.vue`

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | SKU |
| 2 | `batchNo` | 批次 |
| 3 | `quantity` | 发运 |
| 4 | `—` | 实收 |
| 5 | `—` | 损耗 |
| 6 | `—` | 损耗原因 |

**表 5** — `:data="rows"` · 来源 `components/warehouse/WarehousePayablesTab.vue`

特殊列：expand

| # | prop | label |
|---|------|-------|
| 1 | `—` | 供应商 |
| 2 | `—` | 关联采购单 |
| 3 | `—` | 应付金额 |
| 4 | `—` | 已付 |
| 5 | `—` | 未付余额 |
| 6 | `—` | 到期日 |
| 7 | `—` | 状态 |
| 8 | `—` | 逾期 |
| 9 | `—` | 操作 |

**表 6** — `:data="row.payments"` · v-if=`row.payments?.length` · 展开行内子表 · 来源 `components/warehouse/WarehousePayablesTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 付款时间 |
| 2 | `—` | 付款金额 |
| 3 | `notes` | 备注 |

**表 7** — `:data="rows"` · 来源 `components/warehouse/WarehousePurchaseOrdersTab.vue`

特殊列：selection、expand

| # | prop | label |
|---|------|-------|
| 1 | `purchaseOrderId` | 采购单 |
| 2 | `refNo` | 外部单号 |
| 3 | `—` | 供应商 |
| 4 | `—` | 入库仓库 |
| 5 | `—` | 状态 |
| 6 | `—` | 审批节点 |
| 7 | `—` | 操作 |

**表 8** — `:data="row.lines || []"` · 展开行内子表 · 来源 `components/warehouse/WarehousePurchaseOrdersTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `orderedQty` | 采购数 |
| 4 | `receivedQty` | 已收数 |
| 5 | `returnedQty` | 已退数 |
| 6 | `—` | 成本 |
| 7 | `expiryDate` | 到期日期 |

**表 9** — `:data="rows"` · 来源 `components/warehouse/WarehousePurchaseReturnsTab.vue`

特殊列：selection、expand

| # | prop | label |
|---|------|-------|
| 1 | `returnId` | 退货单 |
| 2 | `purchaseOrderId` | 采购单 |
| 3 | `—` | 供应商 |
| 4 | `—` | 仓库 |
| 5 | `—` | 状态 |
| 6 | `—` | 创建时间 |

**表 10** — `:data="row.lines || []"` · 展开行内子表 · 来源 `components/warehouse/WarehousePurchaseReturnsTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `quantity` | 退货数 |

**表 11** — `:data="rows"` · 来源 `components/warehouse/WarehouseSuggestionsTab.vue`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `soldQty7d` | 近7日销量 |
| 3 | `soldQty14d` | 近14日销量 |
| 4 | `—` | 日均销量 |
| 5 | `—` | 预测日均 |
| 6 | `—` | 日均趋势 |
| 7 | `onHandQty` | 仓库库存 |
| 8 | `pendingPoQty` | 待收采购 |
| 9 | `coverageDays` | 覆盖天数 |
| 10 | `—` | 建议采购量 |
| 11 | `—` | 安全库存 |
| 12 | `—` | 建议理由 |

**表 12** — `:data="rows"` · 来源 `components/warehouse/WarehouseSuppliersTab.vue`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `supplierId` | 供应商编号 |
| 2 | `—` | 供应商 |
| 3 | `contactName` | 联系人 |
| 4 | `contactPhone` | 联系电话 |
| 5 | `paymentTermsDays` | 账期(天) |
| 6 | `—` | 状态 |
| 7 | `—` | 操作 |

#### `/warehouse/claims` · 待索赔台账

组件：`views/warehouse/WriteOffClaimLedgerView.vue`

**表 1** — `CrudTable :table="crud"` · testid=`claim-ledger-table` · v-if=`canList`

| # | prop | label |
|---|------|-------|
| 1 | `writeOffId` | ID |
| 2 | `—` | 商品 / 位置 |
| 3 | `batchNo` | 批次 |
| 4 | `quantity` | 数量 |
| 5 | `—` | 原因分类 |
| 6 | `—` | 责任方 |
| 7 | `—` | 理赔单号 |
| 8 | `—` | 索赔额 |
| 9 | `—` | 账面损失 |
| 10 | `—` | 报损时间 |

同页 el-descriptions：`partyLabel(row.responsible_party)`、`待索赔`

#### `/warehouse/monthly-close-sheets` · 月结单台账

组件：`views/warehouse/WarehouseMonthlyCloseSheetsView.vue`

**表 1** — `CrudTable :table="crud"` · testid=`mcs-sheet-table`

| # | prop | label |
|---|------|-------|
| 1 | `closeId` | 单号 |
| 2 | `warehouseName` | 仓库 |
| 3 | `yearMonth` | 月份 |
| 4 | `status` | 状态 |
| 5 | `—` | 盘亏（件 / 元） |
| 6 | `—` | 盘盈（件 / 元） |
| 7 | `approvedByName` | 审批人 |

**表 2** — `:data="detail?.lines || []"` · testid=`mcs-line-table`

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | SKU |
| 2 | `skuName` | 名称 |
| 3 | `openingQty` | 上期 |
| 4 | `purchaseInQty` | 采购入 |
| 5 | `transferInQty` | 调入 |
| 6 | `transferOutQty` | 调出 |
| 7 | `restockQty` | 上柜 |
| 8 | `returnQty` | 退货 |
| 9 | `rockLossQty` | 摇机 |
| 10 | `expiredLossQty` | 过期 |
| 11 | `guestWaterQty` | 客情 |
| 12 | `feeDeductQty` | 费用 |
| 13 | `expectedQty` | 应有 |
| 14 | `—` | 实盘 |
| 15 | `—` | 差异 |
| 16 | `—` | 差异金额 |
| 17 | `—` | 处置 |
| 18 | `—` | 操作 |

同页 el-descriptions：`仓库`、`月份`、`状态`、`期初来源`、`审批人`

#### `/warehouse/reconciliation` · 供应商对账单

组件：`views/warehouse/SupplierReconciliationView.vue`

无表格。详情描述项：`期初余额`、`本期新增应付（收货累加）`、`本期退货冲减`、`本期付款`、`期末余额（尚应付供应商）`、`交叉验证`

#### `/warehouse/expiry-alerts` · 仓库近效期预警

组件：`views/warehouse/WarehouseExpiryAlertsView.vue`

**表 1** — `CrudTable :table="crud"` · testid=`warehouse-expiry-alerts` · v-if=`canList`

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `expiryDate` | 到期日 |
| 4 | `daysRemaining` | 剩余 |
| 5 | `urgency` | 紧急度 |
| 6 | `quantity` | 数量 |
| 7 | `warehouseId` | 仓库 |

#### `/warehouse/stock` · 库存与盘点

组件：`views/warehouse/WarehouseStockPage.vue`

**表 1** — `:data="receiveForm.lines"` · 来源 `components/warehouse/WarehousePurchaseDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次（收货必填） |
| 3 | `expiryDate` | 到期日（收货必填） |
| 4 | `orderedQty` | 采购数 |
| 5 | `—` | 待收 |
| 6 | `—` | 累计收货 |

**表 2** — `:data="returnForm.lines"` · 来源 `components/warehouse/WarehousePurchaseDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `expiryDate` | 到期日 |
| 4 | `receivedQty` | 已收 |
| 5 | `returnedQty` | 已退 |
| 6 | `—` | 可退 |
| 7 | `—` | 本次退货 |

**表 3** — `:data="stocktakeDetail.lines || []"` · 来源 `components/warehouse/WarehouseStocktakeDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `productionDate` | 生产日期 |
| 4 | `expiryDate` | 到期日 |
| 5 | `bookQty` | 账面 |
| 6 | `—` | 实盘 |
| 7 | `—` | 差异 |
| 8 | `—` | 状态 |

**表 4** — `:data="rows"` · 来源 `components/warehouse/WarehouseInventoryTab.vue`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `—` | 仓库 |
| 2 | `—` | 商品 |
| 3 | `batchNo` | 批次 |
| 4 | `productionDate` | 生产日期 |
| 5 | `expiryDate` | 到期日期 |
| 6 | `quantity` | 库存 |
| 7 | `—` | 效期 |

**表 5** — `:data="rows"` · 来源 `components/warehouse/WarehouseMovementsTab.vue`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `movementId` | 流水 |
| 2 | `—` | 类型 |
| 3 | `—` | 商品 |
| 4 | `batchNo` | 批次 |
| 5 | `deltaQty` | 变动 |
| 6 | `—` | 关联业务 |
| 7 | `—` | 关联单号 |
| 8 | `—` | 时间 |

**表 6** — `:data="rows"` · 来源 `components/warehouse/WarehouseMonthlyCloseTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品名称 |
| 2 | `—` | 条码 |
| 3 | `openingQty` | 上期 |
| 4 | `purchaseInQty` | 采购入库 |
| 5 | `transferInQty` | 调入 |
| 6 | `returnQty` | 退货 |
| 7 | `transferOutQty` | 调出 |
| 8 | `—` | 上柜 |
| 9 | `rockLossQty` | 摇机损耗 |
| 10 | `expiredLossQty` | 过期损耗 |
| 11 | `guestWaterQty` | 客情用水 |
| 12 | `feeDeductQty` | 费用抵扣 |
| 13 | `lossQty` | 其他损耗 |
| 14 | `expectedQty` | 应有 |
| 15 | `—` | 实盘 |
| 16 | `—` | 差异 |
| 17 | `—` | 单价 |

**表 7** — `:data="detailRows"` · 来源 `components/warehouse/WarehouseMonthlyCloseTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 柜机 |
| 2 | `qty` | 件数 |
| 3 | `—` | 出库单 |
| 4 | `—` | 发运时间 |

**表 8** — `:data="previewBatch.unmappedProducts"` · 来源 `components/warehouse/ExternalChannelImportTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `externalKey` | 商品ID |
| 2 | `externalName` | 商品名称 |
| 3 | `timesSeen` | 出现次数 |

**表 9** — `:data="(previewBatch.rows ?? []).slice(0, 200)"` · 来源 `components/warehouse/ExternalChannelImportTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `srcLine` | 源行 |
| 2 | `person` | 线长 |
| 3 | `extKey` | 商品ID |
| 4 | `extName` | 商品名称 |
| 5 | `qty` | 数量 |
| 6 | `happenedAt` | 业务日期 |

**表 10** — `:data="products"` · 来源 `components/warehouse/ExternalChannelImportTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `channelCode` | 渠道 |
| 2 | `externalKey` | 商品ID |
| 3 | `externalName` | 商品名称 |
| 4 | `brand` | 品牌 |
| 5 | `spec` | 规格 |
| 6 | `timesSeen` | 出现次数 |
| 7 | `—` | 映射 |
| 8 | `—` | 操作 |

**表 11** — `:data="batches"` · 来源 `components/warehouse/ExternalChannelImportTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `batchId` | 批次 |
| 2 | `—` | 渠道 |
| 3 | `periodLabel` | 账期 |
| 4 | `fileName` | 文件 |
| 5 | `—` | 状态 |
| 6 | `—` | 行数 |
| 7 | `uploadedByName` | 上传人 |
| 8 | `—` | 上传时间 |
| 9 | `—` | 操作 |

**表 12** — `:data="flows"` · 来源 `components/warehouse/ExternalChannelImportTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `periodLabel` | 账期 |
| 2 | `warehouseId` | 仓库 |
| 3 | `personName` | 线长 |
| 4 | `skuId` | SKU |
| 5 | `—` | 操作类型 |
| 6 | `qty` | 数量 |
| 7 | `rowCount` | 原始行 |
| 8 | `remark` | 备注 |

**表 13** — `:data="(batchDetail.rows ?? []).slice(0, 300)"` · 来源 `components/warehouse/ExternalChannelImportTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `srcLine` | 源行 |
| 2 | `person` | 线长 |
| 3 | `extKey` | 商品ID |
| 4 | `extName` | 商品名称 |
| 5 | `qty` | 数量 |
| 6 | `happenedAt` | 业务日期 |

**表 14** — `:data="derived"` · 来源 `components/warehouse/WarehouseTransferReceiveDialog.vue`

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | SKU |
| 2 | `batchNo` | 批次 |
| 3 | `quantity` | 发运 |
| 4 | `—` | 实收 |
| 5 | `—` | 损耗 |
| 6 | `—` | 损耗原因 |

**表 15** — `:data="rows"` · 来源 `components/warehouse/WarehouseStocktakesTab.vue`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `—` | 盘点单号 |
| 2 | `—` | 仓库 |
| 3 | `—` | 模式 |
| 4 | `—` | 状态 |
| 5 | `bookQty` | 账面件数 |
| 6 | `countedQty` | 实盘件数 |
| 7 | `—` | 差异件数 |
| 8 | `diffLineCount` | 差异行数 |
| 9 | `—` | 创建时间 |
| 10 | `—` | 操作 |

#### `/warehouse/fulfillment` · 领货与调拨

组件：`views/warehouse/WarehouseFulfillmentPage.vue`

**表 1** — `:data="receiveForm.lines"` · 来源 `components/warehouse/WarehousePurchaseDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次（收货必填） |
| 3 | `expiryDate` | 到期日（收货必填） |
| 4 | `orderedQty` | 采购数 |
| 5 | `—` | 待收 |
| 6 | `—` | 累计收货 |

**表 2** — `:data="returnForm.lines"` · 来源 `components/warehouse/WarehousePurchaseDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `expiryDate` | 到期日 |
| 4 | `receivedQty` | 已收 |
| 5 | `returnedQty` | 已退 |
| 6 | `—` | 可退 |
| 7 | `—` | 本次退货 |

**表 3** — `:data="stocktakeDetail.lines || []"` · 来源 `components/warehouse/WarehouseStocktakeDialogs.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `productionDate` | 生产日期 |
| 4 | `expiryDate` | 到期日 |
| 5 | `bookQty` | 账面 |
| 6 | `—` | 实盘 |
| 7 | `—` | 差异 |
| 8 | `—` | 状态 |

**表 4** — `:data="derived"` · 来源 `components/warehouse/WarehouseTransferReceiveDialog.vue`

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | SKU |
| 2 | `batchNo` | 批次 |
| 3 | `quantity` | 发运 |
| 4 | `—` | 实收 |
| 5 | `—` | 损耗 |
| 6 | `—` | 损耗原因 |

**表 5** — `:data="rows"` · testid=`outbound-table` · 来源 `components/warehouse/WarehouseOutboundsTab.vue`

特殊列：selection、expand

| # | prop | label |
|---|------|-------|
| 1 | `—` | 出库单 |
| 2 | `routeId` | 路线 |
| 3 | `—` | 出库仓库 |
| 4 | `—` | 状态 |
| 5 | `—` | 创建时间 |
| 6 | `—` | 操作 |

**表 6** — `:data="row.lines || []"` · 展开行内子表 · 来源 `components/warehouse/WarehouseOutboundsTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 目标设备 |
| 2 | `—` | 商品 |
| 3 | `batchNo` | 批次 |
| 4 | `quantity` | 数量 |
| 5 | `—` | 交接状态 |

**表 7** — `:data="rows"` · 来源 `components/warehouse/WarehouseTransfersTab.vue`

| # | prop | label |
|---|------|-------|
| 1 | `transferNo` | 调拨单号 |
| 2 | `—` | 调出仓 |
| 3 | `—` | 调入仓 |
| 4 | `—` | 状态 |
| 5 | `—` | 明细 |
| 6 | `—` | 发运 |
| 7 | `—` | 收货 |
| 8 | `—` | 备注 |
| 9 | `—` | 操作 |

**表 8** — `:data="rows"` · 来源 `components/warehouse/WarehouseTransitTab.vue`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `outboundId` | 出库单 |
| 2 | `—` | 目标设备 |
| 3 | `—` | 商品 |
| 4 | `batchNo` | 批次 |
| 5 | `quantity` | 数量 |
| 6 | `—` | 状态 |
| 7 | `—` | 在途 / 时限 |
| 8 | `—` | 发运时间 |

### 增长风控

#### `/phone-verify` · 手机验证

组件：`views/users/PhoneVerifyView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `logId` | 记录ID |
| 2 | `phone` | 手机号 |
| 3 | `userId` | 用户ID |
| 4 | `channel` | 渠道 |
| 5 | `merchantId` | 商户 |
| 6 | `—` | 验证时间 |

#### `/risk` · 风控

组件：`views/risk/RiskView.vue`

**表 1** — `CrudTable :table="eventsCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 事件 |
| 2 | `—` | 用户 |
| 3 | `—` | 设备 |
| 4 | `—` | 详情 |
| 5 | `—` | 级别 |
| 6 | `—` | 处置 |
| 7 | `—` | 处置备注 |
| 8 | `—` | 处置时间 |
| 9 | `—` | 时间 |

**表 2** — `CrudTable :table="blacklistCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 用户 |
| 2 | `—` | 原因 |
| 3 | `—` | 来源 |
| 4 | `—` | 到期 |
| 5 | `—` | 加入时间 |

#### `/promotions` · 营销活动

组件：`views/promotions/PromotionsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `activityId` | 活动编号 |
| 2 | `—` | 活动 |
| 3 | `—` | 类型 |
| 4 | `—` | 时间 |
| 5 | `—` | 预算 |
| 6 | `—` | 已使用 |
| 7 | `—` | 剩余预算 |
| 8 | `—` | 每人限次 |
| 9 | `—` | 适用柜 |
| 10 | `—` | 状态 |

#### `/coupons` · 优惠券

组件：`views/promotions/CouponsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `couponDefId` | 券定义编号 |
| 2 | `—` | 优惠券 |
| 3 | `—` | 类型 |
| 4 | `—` | 面值 |
| 5 | `—` | 最低消费 |
| 6 | `—` | 有效期 |
| 7 | `—` | 绑定活动 |
| 8 | `—` | 发行/总量 |
| 9 | `—` | 剩余 |
| 10 | `—` | 说明 |
| 11 | `—` | 状态 |

#### `/ad-assets` · 素材库

组件：`views/growth/AdAssetsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `assetId` | ID |
| 2 | `title` | 标题 |
| 3 | `—` | 类型 |
| 4 | `—` | 预览 |
| 5 | `durationSeconds` | 时长(秒) |
| 6 | `—` | 状态 |
| 7 | `—` | 上传时间 |

#### `/ad-campaigns` · 投放计划

组件：`views/growth/AdCampaignsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `campaignId` | ID |
| 2 | `name` | 名称 |
| 3 | `—` | 状态 |
| 4 | `—` | 范围 |
| 5 | `—` | 素材 |
| 6 | `—` | 曝光 |
| 7 | `—` | 完播 |
| 8 | `—` | 完播率 |
| 9 | `—` | 柜机数 |
| 10 | `—` | 时间窗 |

#### `/points-redeem` · 积分兑换管理

组件：`views/growth/PointsRedeemView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `itemId` | ID |
| 2 | `—` | 兑换项 |
| 3 | `pointsCost` | 所需积分 |
| 4 | `couponName` | 兑换优惠券 |
| 5 | `—` | 券定义 |
| 6 | `—` | 库存 / 已兑 |
| 7 | `—` | 可兑 |
| 8 | `sortOrder` | 排序 |
| 9 | `—` | 状态 |
| 10 | `—` | 创建时间 |

#### `/member-levels` · 会员等级规则

组件：`views/growth/MemberLevelsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 等级编码 |
| 2 | `levelName` | 等级名称 |
| 3 | `—` | 累计消费区间(元) |
| 4 | `—` | 下一档积分门槛 |
| 5 | `—` | 积分倍率 |
| 6 | `—` | 会员折扣 |
| 7 | `sortOrder` | 排序 |
| 8 | `—` | 状态 |

#### `/marketing-roi` · 活动效果分析

组件：`views/growth/MarketingRoiView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `activityName` | 活动 |
| 2 | `—` | 类型 |
| 3 | `—` | 状态 |
| 4 | `—` | 预算(元) |
| 5 | `—` | 预算已用(元) |
| 6 | `claimedCount` | 发券数 |
| 7 | `usedCount` | 核销数 |
| 8 | `—` | 核销率 |
| 9 | `—` | 订单优惠(元) |
| 10 | `orderCount` | 带动订单 |
| 11 | `—` | 带动营收(元) |

#### `/notifications` · 消息记录

组件：`views/growth/NotificationsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `id` | ID |
| 2 | `—` | 时间 |
| 3 | `—` | 受众 |
| 4 | `title` | 标题 |
| 5 | `—` | 内容 |
| 6 | `—` | 业务 |
| 7 | `—` | 关联单号 |

#### `/feedback` · 用户反馈

组件：`views/feedback/FeedbackView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `feedbackId` | 反馈编号 |
| 2 | `—` | 类型 |
| 3 | `—` | 内容 |
| 4 | `—` | 用户 |
| 5 | `—` | 设备 |
| 6 | `—` | 评分 |
| 7 | `—` | 状态 |
| 8 | `—` | 时间 |

### 系统

#### `/operators` · 运营账号

组件：`views/system/OperatorManageView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 用户编号 |
| 2 | `—` | 账号 |
| 3 | `—` | 手机号 |
| 4 | `—` | 状态 |
| 5 | `—` | 角色 |
| 6 | `—` | 部门 |
| 7 | `—` | 数据范围 |

#### `/roles` · 角色管理

组件：`views/system/RoleManageView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `roleId` | 角色编号 |
| 2 | `—` | 角色 |
| 3 | `—` | 权限字符 |
| 4 | `—` | 状态 |
| 5 | `—` | 权限数 |
| 6 | `remark` | 备注 |

#### `/departments` · 部门管理

组件：`views/system/DepartmentManageView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `deptKey` | 编码 |
| 2 | `deptName` | 名称 |
| 3 | `—` | 上级 |
| 4 | `memberCount` | 成员数 |
| 5 | `sortOrder` | 排序 |
| 6 | `—` | 状态 |
| 7 | `remark` | 备注 |

#### `/approvals` · 审批流配置

组件：`views/system/ApprovalConfigView.vue`

**表 1** — `CrudTable :table="crud"`

特殊列：expand

| # | prop | label |
|---|------|-------|
| 1 | `—` | 业务 |
| 2 | `defName` | 名称 |
| 3 | `—` | displayLabel('enable_status', 'ACTIVE') |
| 4 | `—` | 流程预览 |
| 5 | `remark` | 备注 |

#### `/menus` · 菜单管理

组件：`views/system/MenuManageView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 名称 |
| 2 | `—` | 类型 |
| 3 | `—` | 权限标识 |
| 4 | `—` | 路由 |
| 5 | `sortOrder` | 排序 |
| 6 | `—` | 状态 |

#### `/dicts` · 字典管理

组件：`views/system/DictManageView.vue`

**表 1** — `:data="filteredTypes"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `itemCount` | 项数 |
| 3 | `—` | 操作 |

**表 2** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `dictDataId` | 数据编号 |
| 2 | `—` | 字典项 |
| 3 | `—` | 值 |
| 4 | `sortOrder` | 排序 |
| 5 | `—` | 状态 |

#### `/system-configs` · 参数配置

组件：`views/system/SystemConfigView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 配置键 |
| 2 | `—` | 功能分组 |
| 3 | `—` | 说明 |
| 4 | `—` | 配置值 |
| 5 | `—` | 更新时间 |

**表 2** — `:data="historyRows"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 变更时间 |
| 2 | `—` | 操作人 |
| 3 | `—` | 变更前 |
| 4 | `—` | 变更后 |
| 5 | `—` | 操作 |

#### `/alert-rules` · 告警规则

组件：`views/system/AlertRuleView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 分组 |
| 2 | `—` | 规则说明 |
| 3 | `—` | 配置键 |
| 4 | `—` | 单位/提示 |
| 5 | `—` | 当前值 |
| 6 | `—` | 更新时间 |

#### `/scheduled-tasks` · 定时任务

组件：`views/system/ScheduledTaskView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 任务名称 |
| 2 | `—` | 任务标识 |
| 3 | `—` | 分组 |
| 4 | `—` | 调度说明 |
| 5 | `—` | 状态 |
| 6 | `—` | 最近执行 |
| 7 | `—` | 最近结果说明 |
| 8 | `—` | 备注 |

#### `/org-sites` · 组织与点位

组件：`views/system/OrgSitesView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `deviceName` | 柜机 |
| 2 | `deviceId` | 设备ID |
| 3 | `—` | 场地 |
| 4 | `—` | 地址 |
| 5 | `landlordName` | 场地主 |
| 6 | `—` | 联系电话 |
| 7 | `—` | 月费 |
| 8 | `—` | 起租 |
| 9 | `—` | 到期 |
| 10 | `—` | 状态 |
| 11 | `—` | 备注 |

**表 2** — `:data="rentBills"` · v-if=`feeBillKind === 'SITE_RENT'`

| # | prop | label |
|---|------|-------|
| 1 | `billMonth` | 账期 |
| 2 | `—` | 场地 |
| 3 | `deviceId` | 设备ID |
| 4 | `—` | 收款方 |
| 5 | `—` | 对方ID |
| 6 | `—` | 份额 |
| 7 | `—` | 月费基数 |
| 8 | `—` | 应付 |
| 9 | `—` | 状态 |
| 10 | `—` | 操作 |

**表 3** — `:data="dataFeeBills"`

| # | prop | label |
|---|------|-------|
| 1 | `billMonth` | 账期 |
| 2 | `deviceName` | 柜机 |
| 3 | `deviceId` | 设备ID |
| 4 | `—` | 商户 |
| 5 | `—` | 应付 |
| 6 | `—` | 状态 |
| 7 | `—` | 操作 |

#### `/announcements` · 通知公告

组件：`views/announcements/AnnouncementsView.vue`

**表 1** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `announceId` | 公告编号 |
| 2 | `—` | 公告 |
| 3 | `—` | 优先级 |
| 4 | `—` | 目标 |
| 5 | `—` | 状态 |
| 6 | `—` | 发布时间 |

同页 el-descriptions：`标题`、`优先级`、`目标`、`状态`、`发布时间`、`内容`

#### `/audit` · 审计日志

组件：`views/system/AuditLogView.vue`

**表 1** — `CrudTable :table="summaryCrud"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 动作 |
| 2 | `—` | 次数 |
| 3 | `—` | 最近一次 |

**表 2** — `CrudTable :table="crud"`

| # | prop | label |
|---|------|-------|
| 1 | `logId` | 日志编号 |
| 2 | `—` | 时间 |
| 3 | `—` | 操作人ID |
| 4 | `—` | 操作人 |
| 5 | `—` | 动作 |
| 6 | `—` | 对象类型 |
| 7 | `—` | 对象ID |
| 8 | `—` | 详情 |

#### `/devops` · DevOps 中心

组件：`views/system/DevOpsHubView.vue`

无表格（图表 / 表单 / 卡片页）。

#### `/observability` · 日志中心

组件：`views/system/ObservabilityView.vue`

无表格（图表 / 表单 / 卡片页）。

#### `/profile` · 个人中心

组件：`views/profile/ProfileView.vue`

无表格。详情描述项：`姓名`、`手机号`、`邮箱`、`角色`、`数据范围`、`主题`、`字号`

#### `/forbidden` · 无权访问

组件：`views/error/ForbiddenView.vue`

无表格（图表 / 表单 / 卡片页）。

#### `/:pathMatch(.*)*` · 页面不存在

组件：`views/error/NotFoundView.vue`

无表格（图表 / 表单 / 卡片页）。

### 未分组

#### `/login` · login

组件：`views/LoginView.vue`

无表格（图表 / 表单 / 卡片页）。

## 3. 单页多表（≥2）

- `/footfall`：#1 `data?.devices || []`(5列)；#2 `data?.topSkus || []`(6列)
- `/fund-bills`：#1 `crud`(10列)；#2 `displayLedger`(8列)
- `/platform-fund`：#1 `accountCrud`(6列)；#2 `flowsCrud`(7列)
- `/device-rental`：#1 `billsCrud`(8列)；#2 `depositsCrud`(9列)；#3 `eventsCrud`(5列)
- `/devices/:id`：#1 `decode.rows || [] @ DeviceJiangyiCard.vue`(6列)；#2 `gatherProgress?.checks || [] @ DeviceJiangyiCard.vue`(3列)；#3 `rows @ DevicePricingCard.vue`(9列)；#4 `sessions @ DeviceRelatedRecordsTab.vue`(8列)；#5 `orders @ DeviceRelatedRecordsTab.vue`(7列)；#6 `repairTickets @ DeviceRemoteOpsCard.vue`(6列)；#7 `envRows @ DeviceTempEnvTab.vue`(3列)
- `/sessions`：#1 `crud`(14列)；#2 `rows @ OrderJiangyiVideoDialog.vue`(6列)
- `/orders`：#1 `crud`(19列)；#2 `detail.lines || detail.items || []`(6列)；#3 `partialRows`(5列)；#4 `rows @ OrderJiangyiVideoDialog.vue`(6列)
- `/disputes`：#1 `crud`(17列)；#2 `selected.suggestedItems`(5列)；#3 `rows @ OrderJiangyiVideoDialog.vue`(6列)
- `/replenishment`：#1 `crudRoutes`(6列)；#2 `sortedRouteTasks(row.tasks)`(10列)；#3 `pagedFulfillment`(11列)；#4 `crudShortages`(8列)；#5 `crudExpiry`(10列)；#6 `rows @ RestockVideoDialog.vue`(6列)
- `/merchants`：#1 `crudMerchants`(13列)；#2 `roleTemplates`(5列)；#3 `crudSplits`(12列)
- `/line-managers`：#1 `managers`(14列)；#2 `withdraws`(16列)；#3 `promoTasks`(8列)；#4 `payoutAccounts`(7列)；#5 `ledgers`(7列)；#6 `kpi?.dailies || []`(6列)
- `/line-routes`：#1 `routesCrud`(5列)；#2 `summaryManagersCrud`(5列)；#3 `summaryStaffsCrud`(4列)；#4 `walletsCrud`(2列)；#5 `membersCrud`(6列)；#6 `ledgerCrud`(5列)
- `/merchant-withdraw`：#1 `walletsCrud`(7列)；#2 `wdCrud`(14列)；#3 `ledgers`(7列)
- `/reconciliation`：#1 `crud`(7列)；#2 `detail.lines || []`(6列)
- `/warehouse/master`：#1 `receiveForm.lines @ WarehousePurchaseDialogs.vue`(6列)；#2 `returnForm.lines @ WarehousePurchaseDialogs.vue`(7列)；#3 `stocktakeDetail.lines || [] @ WarehouseStocktakeDialogs.vue`(8列)；#4 `bins @ WarehouseBinsTab.vue`(5列)；#5 `binStock @ WarehouseBinsTab.vue`(6列)；#6 `derived @ WarehouseTransferReceiveDialog.vue`(6列)；#7 `table @ WarehouseOverviewTab.vue`(5列)
- `/warehouse/procurement`：#1 `receiveForm.lines @ WarehousePurchaseDialogs.vue`(6列)；#2 `returnForm.lines @ WarehousePurchaseDialogs.vue`(7列)；#3 `stocktakeDetail.lines || [] @ WarehouseStocktakeDialogs.vue`(8列)；#4 `derived @ WarehouseTransferReceiveDialog.vue`(6列)；#5 `rows @ WarehousePayablesTab.vue`(9列)；#6 `row.payments @ WarehousePayablesTab.vue`(3列)；#7 `rows @ WarehousePurchaseOrdersTab.vue`(7列)；#8 `row.lines || [] @ WarehousePurchaseOrdersTab.vue`(7列)；#9 `rows @ WarehousePurchaseReturnsTab.vue`(6列)；#10 `row.lines || [] @ WarehousePurchaseReturnsTab.vue`(3列)；#11 `rows @ WarehouseSuggestionsTab.vue`(12列)；#12 `rows @ WarehouseSuppliersTab.vue`(7列)
- `/warehouse/monthly-close-sheets`：#1 `crud`(7列)；#2 `detail?.lines || []`(18列)
- `/warehouse/stock`：#1 `receiveForm.lines @ WarehousePurchaseDialogs.vue`(6列)；#2 `returnForm.lines @ WarehousePurchaseDialogs.vue`(7列)；#3 `stocktakeDetail.lines || [] @ WarehouseStocktakeDialogs.vue`(8列)；#4 `rows @ WarehouseInventoryTab.vue`(7列)；#5 `rows @ WarehouseMovementsTab.vue`(8列)；#6 `rows @ WarehouseMonthlyCloseTab.vue`(17列)；#7 `detailRows @ WarehouseMonthlyCloseTab.vue`(4列)；#8 `previewBatch.unmappedProducts @ ExternalChannelImportTab.vue`(3列)；#9 `(previewBatch.rows ?? []).slice(0, 200) @ ExternalChannelImportTab.vue`(6列)；#10 `products @ ExternalChannelImportTab.vue`(8列)；#11 `batches @ ExternalChannelImportTab.vue`(9列)；#12 `flows @ ExternalChannelImportTab.vue`(8列)；#13 `(batchDetail.rows ?? []).slice(0, 300) @ ExternalChannelImportTab.vue`(6列)；#14 `derived @ WarehouseTransferReceiveDialog.vue`(6列)；#15 `rows @ WarehouseStocktakesTab.vue`(10列)
- `/warehouse/fulfillment`：#1 `receiveForm.lines @ WarehousePurchaseDialogs.vue`(6列)；#2 `returnForm.lines @ WarehousePurchaseDialogs.vue`(7列)；#3 `stocktakeDetail.lines || [] @ WarehouseStocktakeDialogs.vue`(8列)；#4 `derived @ WarehouseTransferReceiveDialog.vue`(6列)；#5 `rows @ WarehouseOutboundsTab.vue`(6列)；#6 `row.lines || [] @ WarehouseOutboundsTab.vue`(5列)；#7 `rows @ WarehouseTransfersTab.vue`(9列)；#8 `rows @ WarehouseTransitTab.vue`(8列)
- `/vision-mappings`：#1 `crud`(7列)；#2 `deployments`(6列)；#3 `reviewPlan.rows`(6列)
- `/learning-library`：#1 `pagedProducts`(6列)；#2 `pagedCabinets`(7列)；#3 `pagedModels`(5列)；#4 `pagedTrainings`(6列)；#5 `shelfResult.devices`(4列)；#6 `studyingRows`(4列)；#7 `gatherCheckRows`(5列)；#8 `consolePending`(4列)；#9 `consoleRun.upload`(3列)
- `/ota`：#1 `crud`(9列)；#2 `progressItems`(7列)
- `/risk`：#1 `eventsCrud`(9列)；#2 `blacklistCrud`(5列)
- `/dicts`：#1 `filteredTypes`(3列)；#2 `crud`(5列)
- `/system-configs`：#1 `crud`(5列)；#2 `historyRows`(5列)
- `/org-sites`：#1 `crud`(11列)；#2 `rentBills`(10列)；#3 `dataFeeBills`(7列)
- `/audit`：#1 `summaryCrud`(3列)；#2 `crud`(8列)
- `/replenishment-staff`：#1 `crud`(9列)；#2 `tallyCrud`(8列)
- `/user-analysis`：#1 `s?.topRepeatBuyers || []`(5列)；#2 `s?.dormantUsers || []`(5列)

## 4. 列偏少（数据列 ≤ 4）

| 路径 | 表 | 来源 | data | 列 |
|------|----|------|------|----|
| `/devices/:id` | #2 | `components/device/DeviceJiangyiCard.vue` | `gatherProgress?.checks || []` | 商品、审核状态、驳回原因 |
| `/devices/:id` | #7 | `components/device/DeviceTempEnvTab.vue` | `envRows` | 指标、数值、上报时间 |
| `/line-routes` | #3 | `views/finance/LineRoutesView.vue` | `summaryStaffsCrud` | 补货员 userId、补货件数、佣金（元）、其中退款冲回（元） |
| `/line-routes` | #4 | `views/finance/LineRoutesView.vue` | `walletsCrud` | 补货员 userId、余额（元） |
| `/warehouse/procurement` | #6 | `components/warehouse/WarehousePayablesTab.vue` | `row.payments` | 付款时间、付款金额、备注 |
| `/warehouse/procurement` | #10 | `components/warehouse/WarehousePurchaseReturnsTab.vue` | `row.lines || []` | 商品、批次、退货数 |
| `/warehouse/stock` | #7 | `components/warehouse/WarehouseMonthlyCloseTab.vue` | `detailRows` | 柜机、件数、出库单、发运时间 |
| `/warehouse/stock` | #8 | `components/warehouse/ExternalChannelImportTab.vue` | `previewBatch.unmappedProducts` | 商品ID、商品名称、出现次数 |
| `/learning-library` | #5 | `views/vision/LearningLibraryView.vue` | `shelfResult.devices` | 柜机、结果、识别编号、说明 |
| `/learning-library` | #6 | `views/vision/LearningLibraryView.vue` | `studyingRows` | 商品、状态、驳回原因、图片 |
| `/learning-library` | #8 | `views/vision/LearningLibraryView.vue` | `consolePending` | 商品、品牌、规格、图片 |
| `/learning-library` | #9 | `views/vision/LearningLibraryView.vue` | `consoleRun.upload` | 条目、上传、厂商判词 |
| `/dicts` | #1 | `views/system/DictManageView.vue` | `filteredTypes` | 类型、项数、操作 |
| `/audit` | #1 | `views/system/AuditLogView.vue` | `summaryCrud` | 动作、次数、最近一次 |

## 5. 已挂路由但无表格的页面

- `/analytics` 数据分析 → `views/analytics/AnalyticsView.vue`
- `/device-map` 投放地图 → `views/devices/DeviceMapView.vue`
- `/device-kpi` 设备可用性 → `views/devices/DeviceKpiView.vue`
- `/sku-vision` 识别入驻 → `views/skus/SkuVisionEnrollView.vue`
- `/warehouse/reconciliation` 供应商对账单 → `views/warehouse/SupplierReconciliationView.vue`
- `/sla` 服务时限监控 → `views/sla/SlaView.vue`
- `/devops` DevOps 中心 → `views/system/DevOpsHubView.vue`
- `/observability` 日志中心 → `views/system/ObservabilityView.vue`
- `/profile` 个人中心 → `views/profile/ProfileView.vue`
- `/forbidden` 无权访问 → `views/error/ForbiddenView.vue`
- `/login` login → `views/LoginView.vue`
- `/print` 打印单据 → `views/print/PrintView.vue`
- `/big-screen` 运营大屏 → `views/dashboard/BigScreenView.vue`
- `/:pathMatch(.*)*` 页面不存在 → `views/error/NotFoundView.vue`

