# 运营后台全页面 × 全数据表格盘点

> 扫描：`clients/admin-vue` 全部业务路由 + `views/**/*.vue` 中每一个 `<el-table>` 及其列。
> 复现：`python scripts/gen-admin-table-inventory.py`

## 0. 总览

| 指标 | 数量 |
|------|------|
| 业务路由页 | 65 |
| views 文件 | 65 |
| 含表格的视图 | 56 |
| el-table 实例总数 | 99 |
| 数据列总数（不含 selection/index） | 791 |

### 视图存在但未挂 router 懒加载

- （无）

## 1. 路由总表（一页一行）

| 分组 | 路径 | 标题 | 组件 | 表数 | 列合计 |
|------|------|------|------|------|--------|
| 概览 | `/dashboard` | 运营工作台 | `dashboard/DashboardView.vue` | 1 | 6 |
| 概览 | `/big-screen` | 运营大屏 | `dashboard/BigScreenView.vue` | 0 | 0 |
| 概览 | `/analytics` | 数据分析 | `analytics/AnalyticsView.vue` | 0 | 0 |
| 概览 | `/footfall` | 客流坪效 | `analytics/FootfallView.vue` | 2 | 8 |
| 概览 | `/reports` | 设备报表 | `reports/DeviceReportView.vue` | 1 | 10 |
| 概览 | `/finance` | 财务毛利 | `finance/FinanceView.vue` | 1 | 7 |
| 财务商户 | `/fund-bills` | 资金账单 | `finance/FundBillView.vue` | 2 | 18 |
| 概览 | `/sales-reports` | 销售报表 | `reports/SalesReportsView.vue` | 1 | 7 |
| 概览 | `/stock-health` | 库存健康 | `reports/StockHealthView.vue` | 1 | 14 |
| 设备商品 | `/devices` | 设备管理 | `devices/DeviceListView.vue` | 1 | 17 |
| 设备商品 | `/device-map` | 投放地图 | `devices/DeviceMapView.vue` | 0 | 0 |
| 设备商品 | `/device-kpi` | 设备可用性 | `devices/DeviceKpiView.vue` | 0 | 0 |
| 设备商品 | `/repair-tickets` | 维修工单 | `devices/RepairTicketsView.vue` | 1 | 11 |
| 设备商品 | `/device-ops` | 设备运维 | `devices/DeviceOpsMonitorView.vue` | 1 | 8 |
| 设备商品 | `/devices/:id` | 设备详情 | `devices/DeviceDetailView.vue` | 4 | 17 |
| 交易履约 | `/sessions` | 开门记录 | `sessions/SessionListView.vue` | 1 | 12 |
| 设备商品 | `/upload-queue` | 录像上传 | `upload/UploadQueueView.vue` | 1 | 10 |
| 交易履约 | `/orders` | 订单管理 | `orders/OrderListView.vue` | 3 | 24 |
| 设备商品 | `/skus` | 商品管理 | `skus/SkuListView.vue` | 1 | 13 |
| 设备商品 | `/sku-vision` | 识别入驻 | `skus/SkuVisionEnrollView.vue` | 2 | 16 |
| 交易履约 | `/disputes` | 争议审核 | `disputes/DisputeListView.vue` | 2 | 17 |
| 交易履约 | `/exceptions` | 异常中心 | `exceptions/ExceptionListView.vue` | 1 | 13 |
| 履约仓储 | `/replenishment` | 补货调度 | `replenishment/ReplenishmentView.vue` | 6 | 62 |
| 财务商户 | `/merchants` | 商户与分账 | `merchants/MerchantSplitsView.vue` | 3 | 19 |
| 财务商户 | `/line-managers` | 线长钱包 | `finance/LineManagerView.vue` | 5 | 46 |
| 财务商户 | `/merchant-withdraw` | 商户提现 | `finance/MerchantWithdrawView.vue` | 3 | 26 |
| 财务商户 | `/reconciliation` | 对账 | `reconciliation/ReconciliationView.vue` | 2 | 10 |
| 财务商户 | `/consistency` | 数据一致性 | `consistency/ConsistencyView.vue` | 1 | 9 |
| 履约仓储 | `/warehouse` | 仓库 | `warehouse/WarehouseView.vue` | 17 | 137 |
| 财务商户 | `/recharges` | 充值管理 | `recharges/RechargeListView.vue` | 1 | 7 |
| 财务商户 | `/balance-refunds` | 余额退款 | `finance/BalanceRefundView.vue` | 1 | 9 |
| 财务商户 | `/invoices` | 开票申请 | `finance/InvoiceListView.vue` | 1 | 8 |
| 财务商户 | `/merchant-onboarding` | 进件工作台 | `merchants/MerchantOnboardingView.vue` | 1 | 8 |
| 财务商户 | `/users` | 用户余额 | `users/UserListView.vue` | 1 | 11 |
| 增长风控 | `/phone-verify` | 手机验证 | `users/PhoneVerifyView.vue` | 1 | 7 |
| 设备商品 | `/vision-mappings` | 识别映射 | `vision/VisionMappingView.vue` | 2 | 11 |
| 履约仓储 | `/ota` | 固件版本 | `ota/OtaView.vue` | 1 | 10 |
| 履约仓储 | `/sla` | 服务时限监控 | `sla/SlaView.vue` | 0 | 0 |
| 增长风控 | `/risk` | 风控 | `risk/RiskView.vue` | 2 | 9 |
| 系统 | `/operators` | 运营账号 | `system/OperatorManageView.vue` | 1 | 7 |
| 系统 | `/roles` | 角色管理 | `system/RoleManageView.vue` | 1 | 7 |
| 系统 | `/menus` | 菜单管理 | `system/MenuManageView.vue` | 1 | 7 |
| 系统 | `/dicts` | 字典管理 | `system/DictManageView.vue` | 2 | 9 |
| 系统 | `/system-configs` | 参数配置 | `system/SystemConfigView.vue` | 1 | 5 |
| 系统 | `/alert-rules` | 告警规则 | `system/AlertRuleView.vue` | 1 | 5 |
| 系统 | `/scheduled-tasks` | 定时任务 | `system/ScheduledTaskView.vue` | 1 | 9 |
| 系统 | `/org-sites` | 组织与点位 | `system/OrgSitesView.vue` | 1 | 8 |
| 系统 | `/announcements` | 通知公告 | `announcements/AnnouncementsView.vue` | 1 | 7 |
| 系统 | `/audit` | 审计日志 | `system/AuditLogView.vue` | 1 | 7 |
| 增长风控 | `/promotions` | 营销活动 | `promotions/PromotionsView.vue` | 1 | 8 |
| 增长风控 | `/coupons` | 优惠券 | `promotions/CouponsView.vue` | 1 | 9 |
| 增长风控 | `/ad-assets` | 素材库 | `growth/AdAssetsView.vue` | 1 | 8 |
| 增长风控 | `/ad-campaigns` | 投放计划 | `growth/AdCampaignsView.vue` | 1 | 9 |
| 增长风控 | `/points-redeem` | 积分兑换管理 | `growth/PointsRedeemView.vue` | 1 | 8 |
| 增长风控 | `/member-levels` | 会员等级规则 | `growth/MemberLevelsView.vue` | 1 | 8 |
| 增长风控 | `/marketing-roi` | 活动效果分析 | `growth/MarketingRoiView.vue` | 1 | 11 |
| 履约仓储 | `/replenishment-staff` | 补货员效率 | `growth/ReplenishmentStaffView.vue` | 1 | 9 |
| 设备商品 | `/sku-review` | 选品诊断 | `growth/SkuReviewView.vue` | 1 | 9 |
| 概览 | `/user-analysis` | 用户分析 | `growth/UserAnalysisView.vue` | 2 | 8 |
| 增长风控 | `/notifications` | 消息记录 | `growth/NotificationsView.vue` | 1 | 7 |
| 增长风控 | `/feedback` | 用户反馈 | `feedback/FeedbackView.vue` | 1 | 9 |
| 系统 | `/profile` | 个人中心 | `profile/ProfileView.vue` | 0 | 0 |
| 业务 | `/recognition-demo` | 识别演示 | `vision/RecognitionDemoView.vue` | 0 | 0 |
| 未分组 | `/login` | login | `LoginView.vue` | 0 | 0 |
| 未分组 | `/print` | 打印单据 | `print/PrintView.vue` | 0 | 0 |

## 2. 按路由展开：每一张表的每一列

### 概览

#### `/dashboard` · 运营工作台

组件：`views/dashboard/DashboardView.vue`

**表 1** — `:data="pagedActions"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 优先级 |
| 2 | `—` | 类型 |
| 3 | `title` | 标题 |
| 4 | `—` | 关联 |
| 5 | `detail` | 详情 |
| 6 | `—` | 操作 |

#### `/big-screen` · 运营大屏

组件：`views/dashboard/BigScreenView.vue`

无 el-table（图表 / 表单 / 卡片页）。

#### `/analytics` · 数据分析

组件：`views/analytics/AnalyticsView.vue`

无 el-table。详情描述项：`累计营收`、`累计订单`、`待审争议`、`24h 争议率`、`今日毛利率`

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
| 2 | `qtySold` | 销量 |
| 3 | `—` | 营收 |

#### `/reports` · 设备报表

组件：`views/reports/DeviceReportView.vue`

**表 1** — `:data="paged"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `deviceId` | 设备编号 |
| 2 | `—` | 设备 |
| 3 | `—` | 状态 |
| 4 | `orderTotal` | 累计订单 |
| 5 | `—` | 累计营收 |
| 6 | `orderToday` | 今日订单 |
| 7 | `—` | 今日营收 |
| 8 | `sessionTotal` | 累计会话 |
| 9 | `sessionActive` | 进行中 |
| 10 | `—` | 操作 |

#### `/finance` · 财务毛利

组件：`views/finance/FinanceView.vue`

**表 1** — `:data="displayTopSkus"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `skuId` | 商品编号 |
| 2 | `—` | 商品 |
| 3 | `qtySold` | 销量 |
| 4 | `—` | 营收 |
| 5 | `—` | 成本 |
| 6 | `—` | 毛利 |
| 7 | `—` | 毛利率 |

同页 el-descriptions：`累计营收`、`累计成本`、`累计毛利`、`今日报废金额`、`今日报废件数`

#### `/sales-reports` · 销售报表

组件：`views/reports/SalesReportsView.vue`

**表 1** — `:data="rows"`

| # | prop | label |
|---|------|-------|
| 1 | `dimKey` | 编码 |
| 2 | `dimLabel` | 名称 |
| 3 | `orderCount` | 订单数 |
| 4 | `qty` | 销量 |
| 5 | `—` | 营收 |
| 6 | `—` | 成本 |
| 7 | `—` | 毛利 |

#### `/stock-health` · 库存健康

组件：`views/reports/StockHealthView.vue`

**表 1** — `:data="rows"`

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
| 14 | `—` | 操作 |

#### `/user-analysis` · 用户分析

组件：`views/growth/UserAnalysisView.vue`

**表 1** — `:data="s?.topRepeatBuyers || []"`

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 用户ID |
| 2 | `—` | 姓名/手机 |
| 3 | `orderCount` | 订单数 |
| 4 | `—` | 累计消费 |

**表 2** — `:data="s?.dormantUsers || []"`

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 用户ID |
| 2 | `—` | 姓名/手机 |
| 3 | `orderCount` | 累计订单 |
| 4 | `—` | 上次消费 |

### 财务商户

#### `/fund-bills` · 资金账单

组件：`views/finance/FundBillView.vue`

**表 1** — `:data="pagedBills"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `bizDate` | 账期 |
| 2 | `merchantId` | 商户编号 |
| 3 | `—` | 商户 |
| 4 | `—` | 订单实付 |
| 5 | `—` | 平台抽成 |
| 6 | `—` | 通道费(估) |
| 7 | `—` | 已入账 |
| 8 | `—` | 待入账 |
| 9 | `orderCount` | 笔数 |
| 10 | `—` | 固化 |

**表 2** — `:data="filteredLedger"`

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

#### `/merchants` · 商户与分账

组件：`views/merchants/MerchantSplitsView.vue`

**表 1** — `:data="pagedMerchants"`

特殊列：selection

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

**表 2** — `:data="roleTemplates"`

| # | prop | label |
|---|------|-------|
| 1 | `templateName` | 岗位 |
| 2 | `description` | 说明 |
| 3 | `permissionHint` | 权限提示 |

**表 3** — `:data="splits"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `—` | 分账编号 |
| 2 | `—` | 订单 |
| 3 | `—` | 商户 |
| 4 | `—` | 商户收入 |
| 5 | `—` | 状态 |
| 6 | `—` | 失败原因 |
| 7 | `—` | 操作 |

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
| 6 | `wxOpenid` | openid |
| 7 | `—` | 余额(元) |
| 8 | `—` | 冻结(元) |
| 9 | `—` | 绑柜 |
| 10 | `commissionRateBps` | 佣金bps |
| 11 | `commissionFixedCents` | 固定分/单 |
| 12 | `status` | 状态 |
| 13 | `—` | 创建时间 |
| 14 | `—` | 操作 |

**表 2** — `:data="withdraws"`

| # | prop | label |
|---|------|-------|
| 1 | `requestId` | 单号 |
| 2 | `requestNo` | 幂等号 |
| 3 | `managerName` | 线长 |
| 4 | `phone` | 手机 |
| 5 | `—` | 金额(元) |
| 6 | `status` | 状态 |
| 7 | `payChannel` | 通道 |
| 8 | `payoutRef` | 回执 |
| 9 | `payoutMessage` | 打款说明 |
| 10 | `reviewRemark` | 审核备注 |
| 11 | `—` | 申请时间 |
| 12 | `—` | 打款时间 |
| 13 | `—` | 操作 |

**表 3** — `:data="promoTasks"`

| # | prop | label |
|---|------|-------|
| 1 | `taskId` | ID |
| 2 | `managerId` | 线长ID |
| 3 | `title` | 任务 |
| 4 | `routeCode` | 线路 |
| 5 | `—` | 进度 |
| 6 | `—` | 奖金(元) |
| 7 | `status` | 状态 |
| 8 | `dueDate` | 截止 |

**表 4** — `:data="ledgers"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `—` | 变动(元) |
| 3 | `—` | 余额后 |
| 4 | `—` | 冻结后 |
| 5 | `refType` | 关联 |
| 6 | `remark` | 备注 |
| 7 | `—` | 时间 |

**表 5** — `:data="kpi?.dailies || []"`

| # | prop | label |
|---|------|-------|
| 1 | `bizDate` | 日期 |
| 2 | `—` | GMV |
| 3 | `—` | 佣金 |
| 4 | `orderCount` | 单量 |

#### `/merchant-withdraw` · 商户提现

组件：`views/finance/MerchantWithdrawView.vue`

**表 1** — `:data="wallets"`

| # | prop | label |
|---|------|-------|
| 1 | `merchantId` | 商户ID |
| 2 | `merchantName` | 名称 |
| 3 | `contactPhone` | 联系电话 |
| 4 | `—` | 余额(元) |
| 5 | `—` | 冻结(元) |
| 6 | `—` | 可用(元) |
| 7 | `status` | 状态 |
| 8 | `—` | 操作 |

**表 2** — `:data="withdraws"`

| # | prop | label |
|---|------|-------|
| 1 | `requestId` | 单号 |
| 2 | `requestNo` | 幂等号 |
| 3 | `merchantId` | 商户ID |
| 4 | `merchantName` | 商户 |
| 5 | `—` | 金额(元) |
| 6 | `status` | 状态 |
| 7 | `payChannel` | 通道 |
| 8 | `payoutRef` | 回执 |
| 9 | `payoutMessage` | 打款说明 |
| 10 | `reviewRemark` | 审核备注 |
| 11 | `—` | 申请时间 |
| 12 | `—` | 操作 |

**表 3** — `:data="ledgers"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `—` | 变动(元) |
| 3 | `—` | 余额后 |
| 4 | `—` | 冻结后 |
| 5 | `remark` | 备注 |
| 6 | `—` | 时间 |

#### `/reconciliation` · 对账

组件：`views/reconciliation/ReconciliationView.vue`

**表 1** — `:data="paged"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `—` | 对账 |
| 2 | `—` | 渠道 |
| 3 | `—` | 状态 |
| 4 | `—` | 差异笔数 |
| 5 | `—` | 创建时间 |
| 6 | `—` | 操作 |

**表 2** — `:data="detail.lines || []"`

| # | prop | label |
|---|------|-------|
| 1 | `platformTradeNo` | 平台流水 |
| 2 | `merchantOrderNo` | 商户单号 |
| 3 | `—` | 金额 |
| 4 | `—` | 匹配 |

同页 el-descriptions：`对账ID`、`日期`、`渠道`、`状态`、`差异笔数`

#### `/consistency` · 数据一致性

组件：`views/consistency/ConsistencyView.vue`

**表 1** — `:data="paged"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `—` | 键 |
| 3 | `tableName` | 表 |
| 4 | `—` | 期望 |
| 5 | `—` | 实际 |
| 6 | `—` | 说明 |
| 7 | `—` | 状态 |
| 8 | `—` | 检出时间 |
| 9 | `—` | 操作 |

#### `/recharges` · 充值管理

组件：`views/recharges/RechargeListView.vue`

**表 1** — `:data="displayItems"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `orderId` | 充值单 |
| 2 | `—` | 用户 |
| 3 | `—` | 金额 |
| 4 | `—` | 渠道 |
| 5 | `—` | 状态 |
| 6 | `—` | 时间 |
| 7 | `—` | 操作 |

#### `/balance-refunds` · 余额退款

组件：`views/finance/BalanceRefundView.vue`

**表 1** — `:data="displayRows"`

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
| 9 | `—` | 操作 |

#### `/invoices` · 开票申请

组件：`views/finance/InvoiceListView.vue`

**表 1** — `:data="rows"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 申请号 |
| 2 | `—` | 订单 |
| 3 | `title` | 抬头 |
| 4 | `taxNo` | 税号 |
| 5 | `—` | 金额 |
| 6 | `—` | 状态 |
| 7 | `—` | 申请时间 |
| 8 | `—` | 操作 |

#### `/merchant-onboarding` · 进件工作台

组件：`views/merchants/MerchantOnboardingView.vue`

**表 1** — `:data="rows"`

| # | prop | label |
|---|------|-------|
| 1 | `merchantId` | 商户 |
| 2 | `channel` | 渠道 |
| 3 | `status` | 状态 |
| 4 | `externalMchId` | 外部商户号 |
| 5 | `—` | 支付模式 |
| 6 | `note` | 备注 |
| 7 | `—` | 更新时间 |
| 8 | `—` | 操作 |

#### `/users` · 用户余额

组件：`views/users/UserListView.vue`

**表 1** — `:data="items"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 用户编号 |
| 2 | `—` | 余额 |
| 3 | `—` | 用户 |
| 4 | `—` | 手机号 |
| 5 | `—` | 角色 |
| 6 | `—` | 实名 |
| 7 | `—` | 会员等级 |
| 8 | `—` | 积分 |
| 9 | `—` | 黑名单 |
| 10 | `—` | 注册时间 |
| 11 | `—` | 操作 |

### 设备商品

#### `/devices` · 设备管理

组件：`views/devices/DeviceListView.vue`

**表 1** — `:data="devices"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `deviceId` | 设备编号 |
| 2 | `—` | 设备 |
| 3 | `—` | 类型 |
| 4 | `—` | 状态 |
| 5 | `—` | 运营态 |
| 6 | `—` | 生命周期 |
| 7 | `—` | 柜内温度 |
| 8 | `—` | 地址 |
| 9 | `—` | IMEI |
| 10 | `—` | 资产方 |
| 11 | `—` | 路线 |
| 12 | `—` | 商户 |
| 13 | `—` | 退款方式 |
| 14 | `—` | 最近会话 |
| 15 | `—` | 会话状态 |
| 16 | `—` | 更新时间 |
| 17 | `—` | 操作 |

#### `/device-map` · 投放地图

组件：`views/devices/DeviceMapView.vue`

无 el-table（图表 / 表单 / 卡片页）。

#### `/device-kpi` · 设备可用性

组件：`views/devices/DeviceKpiView.vue`

无 el-table（图表 / 表单 / 卡片页）。

#### `/repair-tickets` · 维修工单

组件：`views/devices/RepairTicketsView.vue`

**表 1** — `:data="displayRows"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `ticketId` | 工单号 |
| 2 | `deviceId` | 设备 |
| 3 | `title` | 标题 |
| 4 | `faultType` | 故障类型 |
| 5 | `priority` | 优先级 |
| 6 | `status` | 状态 |
| 7 | `—` | 负责人 |
| 8 | `—` | 备注 |
| 9 | `—` | 创建时间 |
| 10 | `—` | 关闭时间 |
| 11 | `—` | 操作 |

同页 el-descriptions：`工单号`、`设备`、`标题`、`故障类型`、`状态`、`优先级`、`负责人`、`创建人`、`备注`、`创建时间`、`更新时间`、`关闭时间`

#### `/device-ops` · 设备运维

组件：`views/devices/DeviceOpsMonitorView.vue`

**表 1** — `:data="displayItems"`

| # | prop | label |
|---|------|-------|
| 1 | `eventId` | 事件ID |
| 2 | `eventType` | 类型 |
| 3 | `—` | 级别 |
| 4 | `—` | 设备名称 |
| 5 | `deviceId` | 设备编号 |
| 6 | `title` | 标题 |
| 7 | `detail` | 详情 |
| 8 | `—` | 时间 |

#### `/devices/:id` · 设备详情

组件：`views/devices/DeviceDetailView.vue`

**表 1** — `:data="repairTickets"` · v-if=`repairTickets.length`

| # | prop | label |
|---|------|-------|
| 1 | `ticketId` | 单号 |
| 2 | `title` | 标题 |
| 3 | `status` | 状态 |
| 4 | `createdAt` | 创建 |

**表 2** — `:data="envRows"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 指标 |
| 2 | `—` | 数值 |
| 3 | `—` | 上报时间 |

**表 3** — `:data="sessions"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 会话 |
| 2 | `—` | 状态 |
| 3 | `—` | 订单 |
| 4 | `—` | 时间 |
| 5 | `—` | 操作 |

**表 4** — `:data="orders"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 订单 |
| 2 | `—` | 状态 |
| 3 | `—` | 金额 |
| 4 | `—` | 时间 |
| 5 | `—` | 操作 |

同页 el-descriptions：`设备编号`、`商户`、`地址`、`App 版本`、`固件版本`、`目标温度`、`温度上报`、`告警联系人`、`联系电话`、`最近会话`、`会话状态`、`退款规则`、`最近补货`、`库存准确率`

#### `/upload-queue` · 录像上传

组件：`views/upload/UploadQueueView.vue`

**表 1** — `:data="displayItems"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `sessionId` | 会话编号 |
| 2 | `—` | 用户 |
| 3 | `—` | 设备 |
| 4 | `—` | 对象路径 |
| 5 | `—` | 上传状态 |
| 6 | `—` | 等待原因 |
| 7 | `—` | 滞留 / 时限 |
| 8 | `—` | 预览 |
| 9 | `—` | 关门时间 |
| 10 | `—` | 更新时间 |

#### `/skus` · 商品管理

组件：`views/skus/SkuListView.vue`

**表 1** — `:data="paged"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `skuCode` | 编号 |
| 2 | `—` | 主图 |
| 3 | `barcode` | 条码 |
| 4 | `—` | 名称 |
| 5 | `brand` | 品牌 |
| 6 | `spec` | 规格 |
| 7 | `unit` | 单位 |
| 8 | `—` | 售价 |
| 9 | `—` | 成本 |
| 10 | `category` | 类目 |
| 11 | `—` | 状态 |
| 12 | `—` | 添加时间 |
| 13 | `—` | 操作 |

#### `/sku-vision` · 识别入驻

组件：`views/skus/SkuVisionEnrollView.vue`

**表 1** — `:data="paged"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `skuCode` | 编号 |
| 2 | `—` | 主图 |
| 3 | `—` | 商品 |
| 4 | `—` | 基准价 |
| 5 | `—` | 成本 |
| 6 | `category` | 类目 |
| 7 | `—` | 端侧类名 |
| 8 | `—` | 识别状态 |
| 9 | `—` | 映射/模型 |
| 10 | `—` | 商品状态 |
| 11 | `—` | 扣款阈值 |
| 12 | `—` | 检测阈值 |
| 13 | `—` | 操作 |

**表 2** — `:data="testPreview.items"` · v-if=`testPreview?.items?.length`

| # | prop | label |
|---|------|-------|
| 1 | `skuName` | 商品 |
| 2 | `quantity` | 数量 |
| 3 | `—` | 置信度 |

#### `/vision-mappings` · 识别映射

组件：`views/vision/VisionMappingView.vue`

**表 1** — `:data="paged"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `className` | 类名 |
| 2 | `—` | 商品 |
| 3 | `—` | 入驻状态 |
| 4 | `—` | 映射/模型 |
| 5 | `—` | 最低置信度 |
| 6 | `—` | 操作 |

**表 2** — `:data="aliyunMappings"`

| # | prop | label |
|---|------|-------|
| 1 | `categoryId` | 类目ID |
| 2 | `categoryName` | 类目名 |
| 3 | `skuId` | SKU |
| 4 | `—` | 最低置信度 |
| 5 | `—` | 操作 |

#### `/sku-review` · 选品诊断

组件：`views/growth/SkuReviewView.vue`

**表 1** — `:data="list"`

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
| 9 | `—` | 操作 |

### 交易履约

#### `/sessions` · 开门记录

组件：`views/sessions/SessionListView.vue`

**表 1** — `:data="displayItems"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `sessionId` | 会话编号 |
| 2 | `—` | 类型 |
| 3 | `—` | 用户 |
| 4 | `—` | 设备 |
| 5 | `—` | 订单 |
| 6 | `—` | 状态 |
| 7 | `—` | 等待原因 |
| 8 | `—` | 滞留 / 时限 |
| 9 | `—` | 失败原因 |
| 10 | `—` | 更新时间 |
| 11 | `—` | 时长 |
| 12 | `—` | 操作 |

同页 el-descriptions：`会话`、`设备`、`订单`、`状态`

#### `/orders` · 订单管理

组件：`views/orders/OrderListView.vue`

**表 1** — `:data="displayItems"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `orderId` | 订单号 |
| 2 | `—` | 会话 |
| 3 | `—` | 用户 |
| 4 | `—` | 设备 |
| 5 | `—` | 流水号 |
| 6 | `—` | 订单状态 |
| 7 | `—` | 支付状态 |
| 8 | `—` | 退款状态 |
| 9 | `—` | 支付渠道 |
| 10 | `—` | 扣库存 |
| 11 | `—` | 商品 |
| 12 | `—` | 金额 |
| 13 | `—` | 优惠 |
| 14 | `—` | 账龄 |
| 15 | `—` | 创建时间 |
| 16 | `—` | 操作 |

**表 2** — `:data="detail.lines || detail.items || []"`

| # | prop | label |
|---|------|-------|
| 1 | `skuName` | 商品 |
| 2 | `quantity` | 数量 |
| 3 | `—` | 小计 |

**表 3** — `:data="partialRows"`

| # | prop | label |
|---|------|-------|
| 1 | `skuName` | 商品 |
| 2 | `skuId` | SKU |
| 3 | `maxQty` | 可退 |
| 4 | `—` | 退款数量 |
| 5 | `—` | 回库 |

同页 el-descriptions：`订单号`、`会话`、`设备`、`状态`、`金额`、`支付渠道`、`创建时间`

#### `/disputes` · 争议审核

组件：`views/disputes/DisputeListView.vue`

**表 1** — `:data="items"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `ticketId` | 工单号 |
| 2 | `—` | 工单 |
| 3 | `—` | 置信度 |
| 4 | `—` | 设备 |
| 5 | `—` | 会话 |
| 6 | `—` | 关联订单 |
| 7 | `—` | 状态 |
| 8 | `—` | 分类 |
| 9 | `—` | 优先级 |
| 10 | `—` | 已扣金额 |
| 11 | `—` | 创建时间 |
| 12 | `—` | 结案时间 |
| 13 | `—` | 操作 |

**表 2** — `:data="selected.suggestedItems"`

| # | prop | label |
|---|------|-------|
| 1 | `skuName` | 商品 |
| 2 | `skuId` | SKU |
| 3 | `quantity` | 数量 |
| 4 | `—` | 单价 |

同页 el-descriptions：`工单`、`会话`、`设备`、`原因`、`检出类`、`已扣金额`、`状态`、`处理时间`、`关联订单`

#### `/exceptions` · 异常中心

组件：`views/exceptions/ExceptionListView.vue`

**表 1** — `:data="displayItems"`

特殊列：selection

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
| 13 | `—` | 操作 |

同页 el-descriptions：`异常编号`、`异常类型`、`严重级别`、`处理状态`、`异常内容`、`详细信息`、`关联设备`、`关联会话`、`关联订单`、`时限截止`

### 履约仓储

#### `/replenishment` · 补货调度

组件：`views/replenishment/ReplenishmentView.vue`

**表 1** — `:data="pagedRoutes"`

特殊列：selection、expand

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
| 11 | `—` | 路线 |
| 12 | `routeId` | 路线ID |
| 13 | `—` | 设备数 |
| 14 | `plannedDate` | 计划日期 |
| 15 | `—` | 状态 |
| 16 | `—` | 操作 |

**表 2** — `:data="pagedFulfillment"`

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
| 10 | `—` | 操作 |

**表 3** — `:data="pagedRequests"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `requestId` | 要货单 |
| 2 | `merchantName` | 商户 |
| 3 | `—` | 目标设备 |
| 4 | `—` | 设备ID |
| 5 | `—` | 明细 |
| 6 | `—` | 状态 |
| 7 | `—` | 驳回原因 |
| 8 | `—` | 补货任务 |
| 9 | `—` | 提交时间 |
| 10 | `—` | 操作 |

**表 4** — `:data="pagedShortages"`

特殊列：selection

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
| 9 | `—` | 操作 |

**表 5** — `:data="pagedExpiry"`

特殊列：selection

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

**表 6** — `:data="taskLines"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `—` | 商品 |
| 3 | `quantity` | 数量 |
| 4 | `—` | 货道 |
| 5 | `—` | 批次 |
| 6 | `—` | 效期 |
| 7 | `—` | 已入账 |

同页 el-descriptions：`设备`、`人员`、`签到`、`用时`、`完成`、`说明`

#### `/warehouse` · 仓库

组件：`views/warehouse/WarehouseView.vue`

**表 1** — `:data="pagedWarehouses"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `warehouseId` | 仓库编号 |
| 2 | `—` | 仓库 |
| 3 | `address` | 地址 |
| 4 | `—` | 状态 |
| 5 | `—` | 操作 |

**表 2** — `:data="transfers"`

| # | prop | label |
|---|------|-------|
| 1 | `transferNo` | 调拨单号 |
| 2 | `fromWarehouseId` | 调出仓 |
| 3 | `toWarehouseId` | 调入仓 |
| 4 | `status` | 状态 |
| 5 | `—` | 明细 |
| 6 | `—` | 操作 |

**表 3** — `:data="pagedSuppliers"`

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

**表 4** — `:data="pagedPurchaseOrders"`

特殊列：selection、expand

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `orderedQty` | 采购数 |
| 4 | `receivedQty` | 已收数 |
| 5 | `returnedQty` | 已退数 |
| 6 | `—` | 成本 |
| 7 | `expiryDate` | 到期日期 |
| 8 | `purchaseOrderId` | 采购单 |
| 9 | `refNo` | 外部单号 |
| 10 | `—` | 供应商 |
| 11 | `—` | 入库仓库 |
| 12 | `—` | 状态 |
| 13 | `—` | 操作 |

**表 5** — `:data="pagedSuggestions"`

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

**表 6** — `:data="pagedPurchaseReturns"`

特殊列：selection、expand

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `quantity` | 退货数 |
| 4 | `returnId` | 退货单 |
| 5 | `purchaseOrderId` | 采购单 |
| 6 | `—` | 供应商 |
| 7 | `—` | 仓库 |
| 8 | `—` | 状态 |
| 9 | `—` | 创建时间 |

**表 7** — `:data="pagedPayables"`

特殊列：expand

| # | prop | label |
|---|------|-------|
| 1 | `—` | 付款时间 |
| 2 | `—` | 付款金额 |
| 3 | `notes` | 备注 |
| 4 | `—` | 供应商 |
| 5 | `—` | 关联采购单 |
| 6 | `—` | 应付金额 |
| 7 | `—` | 已付 |
| 8 | `—` | 未付余额 |
| 9 | `—` | 到期日 |
| 10 | `—` | 状态 |
| 11 | `—` | 逾期 |
| 12 | `—` | 操作 |

**表 8** — `:data="pagedStocktakes"`

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

**表 9** — `:data="bins"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 货位编码 |
| 2 | `binName` | 货位名称 |
| 3 | `—` | 仓库 |
| 4 | `—` | 状态 |
| 5 | `—` | 操作 |

**表 10** — `:data="pagedBinStock"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 货位 |
| 2 | `—` | 商品 |
| 3 | `batchNo` | 批次 |
| 4 | `productionDate` | 生产日期 |
| 5 | `expiryDate` | 到期日 |
| 6 | `quantity` | 数量 |

**表 11** — `:data="pagedOutbounds"` · testid=`outbound-table`

特殊列：selection、expand

| # | prop | label |
|---|------|-------|
| 1 | `—` | 目标设备 |
| 2 | `—` | 商品 |
| 3 | `—` | 货道 |
| 4 | `batchNo` | 批次 |
| 5 | `quantity` | 数量 |
| 6 | `—` | 交接状态 |
| 7 | `—` | 出库单 |
| 8 | `routeId` | 路线 |
| 9 | `—` | 出库仓库 |
| 10 | `—` | 状态 |
| 11 | `—` | 创建时间 |
| 12 | `—` | 操作 |

**表 12** — `:data="pagedInTransit"`

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

**表 13** — `:data="pagedInventory"`

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

**表 14** — `:data="pagedMovements"`

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

**表 15** — `:data="stocktakeDetail.lines || []"`

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

**表 16** — `:data="receiveForm.lines"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `orderedQty` | 采购数 |
| 4 | `—` | 累计收货 |

**表 17** — `:data="returnForm.lines"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 商品 |
| 2 | `batchNo` | 批次 |
| 3 | `receivedQty` | 已收 |
| 4 | `returnedQty` | 已退 |
| 5 | `—` | 本次退货 |

#### `/ota` · 固件版本

组件：`views/ota/OtaView.vue`

**表 1** — `:data="items"`

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
| 10 | `—` | 操作 |

#### `/sla` · 服务时限监控

组件：`views/sla/SlaView.vue`

无 el-table。详情描述项：`24h 开门成功率`、`当前在线率`、`24h 开门均时长`、`争议时限达标率`、`开放争议`、`逾期争议`

#### `/replenishment-staff` · 补货员效率

组件：`views/growth/ReplenishmentStaffView.vue`

**表 1** — `:data="list"`

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 工号 |
| 2 | `—` | 姓名 |
| 3 | `—` | 手机 |
| 4 | `totalTasks` | 任务数 |
| 5 | `completedTasks` | 已完成 |
| 6 | `—` | 完成率 |
| 7 | `—` | 平均耗时(分) |
| 8 | `openTasks` | 待办 |
| 9 | `avgDailyTasks` | 日均任务 |

### 增长风控

#### `/phone-verify` · 手机验证

组件：`views/users/PhoneVerifyView.vue`

**表 1** — `:data="displayItems"`

| # | prop | label |
|---|------|-------|
| 1 | `logId` | 记录ID |
| 2 | `phone` | 手机号 |
| 3 | `userId` | 用户ID |
| 4 | `channel` | 渠道 |
| 5 | `merchantId` | 商户 |
| 6 | `—` | 验证时间 |
| 7 | `—` | 操作 |

#### `/risk` · 风控

组件：`views/risk/RiskView.vue`

**表 1** — `:data="events"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `—` | 事件 |
| 2 | `—` | 用户 |
| 3 | `—` | 级别 |
| 4 | `—` | 处置 |
| 5 | `—` | 时间 |

**表 2** — `:data="blacklist"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `—` | 用户 |
| 2 | `—` | 原因 |
| 3 | `—` | 加入时间 |
| 4 | `—` | 操作 |

#### `/promotions` · 营销活动

组件：`views/promotions/PromotionsView.vue`

**表 1** — `:data="paged"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `activityId` | 活动编号 |
| 2 | `—` | 活动 |
| 3 | `—` | 类型 |
| 4 | `—` | 时间 |
| 5 | `—` | 预算 |
| 6 | `—` | 已使用 |
| 7 | `—` | 状态 |
| 8 | `—` | 操作 |

#### `/coupons` · 优惠券

组件：`views/promotions/CouponsView.vue`

**表 1** — `:data="paged"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `couponDefId` | 券定义编号 |
| 2 | `—` | 优惠券 |
| 3 | `—` | 类型 |
| 4 | `—` | 面值 |
| 5 | `—` | 最低消费 |
| 6 | `—` | 有效期 |
| 7 | `—` | 发行/总量 |
| 8 | `—` | 状态 |
| 9 | `—` | 操作 |

#### `/ad-assets` · 素材库

组件：`views/growth/AdAssetsView.vue`

**表 1** — `:data="displayRows"`

| # | prop | label |
|---|------|-------|
| 1 | `assetId` | ID |
| 2 | `title` | 标题 |
| 3 | `—` | 类型 |
| 4 | `—` | 预览 |
| 5 | `durationSeconds` | 时长(秒) |
| 6 | `—` | 状态 |
| 7 | `—` | 上传时间 |
| 8 | `—` | 操作 |

#### `/ad-campaigns` · 投放计划

组件：`views/growth/AdCampaignsView.vue`

**表 1** — `:data="displayRows"`

| # | prop | label |
|---|------|-------|
| 1 | `campaignId` | ID |
| 2 | `name` | 名称 |
| 3 | `—` | 状态 |
| 4 | `—` | 范围 |
| 5 | `—` | 素材 |
| 6 | `—` | 曝光 |
| 7 | `—` | 完播 |
| 8 | `—` | 时间窗 |
| 9 | `—` | 操作 |

#### `/points-redeem` · 积分兑换管理

组件：`views/growth/PointsRedeemView.vue`

**表 1** — `:data="list"`

| # | prop | label |
|---|------|-------|
| 1 | `itemId` | ID |
| 2 | `—` | 兑换项 |
| 3 | `pointsCost` | 所需积分 |
| 4 | `couponName` | 兑换优惠券 |
| 5 | `—` | 库存 / 已兑 |
| 6 | `sortOrder` | 排序 |
| 7 | `—` | 状态 |
| 8 | `—` | 操作 |

#### `/member-levels` · 会员等级规则

组件：`views/growth/MemberLevelsView.vue`

**表 1** — `:data="list"`

| # | prop | label |
|---|------|-------|
| 1 | `levelCode` | 等级编码 |
| 2 | `levelName` | 等级名称 |
| 3 | `—` | 累计消费区间(元) |
| 4 | `—` | 累计积分区间 |
| 5 | `—` | 积分倍率 |
| 6 | `sortOrder` | 排序 |
| 7 | `—` | 状态 |
| 8 | `—` | 操作 |

#### `/marketing-roi` · 活动效果分析

组件：`views/growth/MarketingRoiView.vue`

**表 1** — `:data="list"`

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

**表 1** — `:data="displayList"`

| # | prop | label |
|---|------|-------|
| 1 | `id` | ID |
| 2 | `—` | 时间 |
| 3 | `—` | 受众 |
| 4 | `title` | 标题 |
| 5 | `—` | 内容 |
| 6 | `bizType` | 业务 |
| 7 | `—` | 关联单号 |

#### `/feedback` · 用户反馈

组件：`views/feedback/FeedbackView.vue`

**表 1** — `:data="sortedList"`

特殊列：selection

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
| 9 | `—` | 操作 |

### 系统

#### `/operators` · 运营账号

组件：`views/system/OperatorManageView.vue`

**表 1** — `:data="operators"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `userId` | 用户编号 |
| 2 | `—` | 账号 |
| 3 | `—` | 手机号 |
| 4 | `—` | 状态 |
| 5 | `—` | 角色 |
| 6 | `—` | 数据范围 |
| 7 | `—` | 操作 |

#### `/roles` · 角色管理

组件：`views/system/RoleManageView.vue`

**表 1** — `:data="filteredRoles"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `roleId` | 角色编号 |
| 2 | `—` | 角色 |
| 3 | `—` | 权限字符 |
| 4 | `—` | 状态 |
| 5 | `—` | 权限数 |
| 6 | `remark` | 备注 |
| 7 | `—` | 操作 |

#### `/menus` · 菜单管理

组件：`views/system/MenuManageView.vue`

**表 1** — `:data="tableRows"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `—` | 名称 |
| 2 | `—` | 类型 |
| 3 | `—` | 权限标识 |
| 4 | `—` | 路由 |
| 5 | `sortOrder` | 排序 |
| 6 | `—` | 状态 |
| 7 | `—` | 操作 |

#### `/dicts` · 字典管理

组件：`views/system/DictManageView.vue`

**表 1** — `:data="filteredTypes"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 类型 |
| 2 | `itemCount` | 项数 |
| 3 | `—` | 操作 |

**表 2** — `:data="displayItems"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `dictDataId` | 数据编号 |
| 2 | `—` | 字典项 |
| 3 | `—` | 值 |
| 4 | `sortOrder` | 排序 |
| 5 | `—` | 状态 |
| 6 | `—` | 操作 |

#### `/system-configs` · 参数配置

组件：`views/system/SystemConfigView.vue`

**表 1** — `:data="paged"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `—` | 配置键 |
| 2 | `—` | 说明 |
| 3 | `—` | 配置值 |
| 4 | `—` | 更新时间 |
| 5 | `—` | 操作 |

#### `/alert-rules` · 告警规则

组件：`views/system/AlertRuleView.vue`

**表 1** — `:data="rows"`

| # | prop | label |
|---|------|-------|
| 1 | `—` | 分组 |
| 2 | `—` | 规则说明 |
| 3 | `—` | 配置键 |
| 4 | `—` | 当前值 |
| 5 | `—` | 操作 |

#### `/scheduled-tasks` · 定时任务

组件：`views/system/ScheduledTaskView.vue`

**表 1** — `:data="paged"`

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
| 9 | `—` | 操作 |

#### `/org-sites` · 组织与点位

组件：`views/system/OrgSitesView.vue`

**表 1** — `:data="contracts"`

| # | prop | label |
|---|------|-------|
| 1 | `deviceName` | 柜机 |
| 2 | `siteName` | 场地 |
| 3 | `address` | 地址 |
| 4 | `landlordName` | 场地主 |
| 5 | `—` | 月费 |
| 6 | `—` | 到期 |
| 7 | `—` | 状态 |
| 8 | `—` | 操作 |

#### `/announcements` · 通知公告

组件：`views/announcements/AnnouncementsView.vue`

**表 1** — `:data="paged"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `announceId` | 公告编号 |
| 2 | `—` | 公告 |
| 3 | `—` | 优先级 |
| 4 | `—` | 目标 |
| 5 | `—` | 状态 |
| 6 | `—` | 发布时间 |
| 7 | `—` | 操作 |

同页 el-descriptions：`标题`、`优先级`、`目标`、`状态`、`发布时间`、`内容`

#### `/audit` · 审计日志

组件：`views/system/AuditLogView.vue`

**表 1** — `:data="displayItems"`

特殊列：selection

| # | prop | label |
|---|------|-------|
| 1 | `logId` | 日志编号 |
| 2 | `—` | 时间 |
| 3 | `—` | 操作人 |
| 4 | `—` | 动作 |
| 5 | `—` | 对象类型 |
| 6 | `—` | 对象ID |
| 7 | `—` | 详情 |

#### `/profile` · 个人中心

组件：`views/profile/ProfileView.vue`

无 el-table。详情描述项：`角色`、`数据范围`、`权限数`、`主题`、`字号`、`操作列`

### 业务

#### `/recognition-demo` · 识别演示

组件：`views/vision/RecognitionDemoView.vue`

无 el-table。详情描述项：`检测类名`、`模型`、`整体置信度`、`自动扣款`

### 未分组

#### `/login` · login

组件：`views/LoginView.vue`

无 el-table（图表 / 表单 / 卡片页）。

#### `/print` · 打印单据

组件：`views/print/PrintView.vue`

无 el-table（图表 / 表单 / 卡片页）。

## 3. 单页多表（≥2）

- `analytics/FootfallView.vue`：#1 `data?.devices || []`(5列)；#2 `data?.topSkus || []`(3列)
- `devices/DeviceDetailView.vue`：#1 `repairTickets`(4列)；#2 `envRows`(3列)；#3 `sessions`(5列)；#4 `orders`(5列)
- `disputes/DisputeListView.vue`：#1 `items`(13列)；#2 `selected.suggestedItems`(4列)
- `finance/FundBillView.vue`：#1 `pagedBills`(10列)；#2 `filteredLedger`(8列)
- `finance/LineManagerView.vue`：#1 `managers`(14列)；#2 `withdraws`(13列)；#3 `promoTasks`(8列)；#4 `ledgers`(7列)；#5 `kpi?.dailies || []`(4列)
- `finance/MerchantWithdrawView.vue`：#1 `wallets`(8列)；#2 `withdraws`(12列)；#3 `ledgers`(6列)
- `growth/UserAnalysisView.vue`：#1 `s?.topRepeatBuyers || []`(4列)；#2 `s?.dormantUsers || []`(4列)
- `merchants/MerchantSplitsView.vue`：#1 `pagedMerchants`(9列)；#2 `roleTemplates`(3列)；#3 `splits`(7列)
- `orders/OrderListView.vue`：#1 `displayItems`(16列)；#2 `detail.lines || detail.items || []`(3列)；#3 `partialRows`(5列)
- `reconciliation/ReconciliationView.vue`：#1 `paged`(6列)；#2 `detail.lines || []`(4列)
- `replenishment/ReplenishmentView.vue`：#1 `pagedRoutes`(16列)；#2 `pagedFulfillment`(10列)；#3 `pagedRequests`(10列)；#4 `pagedShortages`(9列)；#5 `pagedExpiry`(10列)；#6 `taskLines`(7列)
- `risk/RiskView.vue`：#1 `events`(5列)；#2 `blacklist`(4列)
- `skus/SkuVisionEnrollView.vue`：#1 `paged`(13列)；#2 `testPreview.items`(3列)
- `system/DictManageView.vue`：#1 `filteredTypes`(3列)；#2 `displayItems`(6列)
- `vision/VisionMappingView.vue`：#1 `paged`(6列)；#2 `aliyunMappings`(5列)
- `warehouse/WarehouseView.vue`：#1 `pagedWarehouses`(5列)；#2 `transfers`(6列)；#3 `pagedSuppliers`(7列)；#4 `pagedPurchaseOrders`(13列)；#5 `pagedSuggestions`(12列)；#6 `pagedPurchaseReturns`(9列)；#7 `pagedPayables`(12列)；#8 `pagedStocktakes`(10列)；#9 `bins`(5列)；#10 `pagedBinStock`(6列)；#11 `pagedOutbounds`(12列)；#12 `pagedInTransit`(8列)；#13 `pagedInventory`(7列)；#14 `pagedMovements`(8列)；#15 `stocktakeDetail.lines || []`(8列)；#16 `receiveForm.lines`(4列)；#17 `returnForm.lines`(5列)

## 4. 列偏少（数据列 ≤ 4）

| 视图 | 表 | data | 列 |
|------|----|------|----|
| `analytics/FootfallView.vue` | #2 | `data?.topSkus || []` | 商品、销量、营收 |
| `devices/DeviceDetailView.vue` | #1 | `repairTickets` | 单号、标题、状态、创建 |
| `devices/DeviceDetailView.vue` | #2 | `envRows` | 指标、数值、上报时间 |
| `disputes/DisputeListView.vue` | #2 | `selected.suggestedItems` | 商品、SKU、数量、单价 |
| `finance/LineManagerView.vue` | #5 | `kpi?.dailies || []` | 日期、GMV、佣金、单量 |
| `growth/UserAnalysisView.vue` | #1 | `s?.topRepeatBuyers || []` | 用户ID、姓名/手机、订单数、累计消费 |
| `growth/UserAnalysisView.vue` | #2 | `s?.dormantUsers || []` | 用户ID、姓名/手机、累计订单、上次消费 |
| `merchants/MerchantSplitsView.vue` | #2 | `roleTemplates` | 岗位、说明、权限提示 |
| `orders/OrderListView.vue` | #2 | `detail.lines || detail.items || []` | 商品、数量、小计 |
| `reconciliation/ReconciliationView.vue` | #2 | `detail.lines || []` | 平台流水、商户单号、金额、匹配 |
| `risk/RiskView.vue` | #2 | `blacklist` | 用户、原因、加入时间、操作 |
| `skus/SkuVisionEnrollView.vue` | #2 | `testPreview.items` | 商品、数量、置信度 |
| `system/DictManageView.vue` | #1 | `filteredTypes` | 类型、项数、操作 |
| `warehouse/WarehouseView.vue` | #16 | `receiveForm.lines` | 商品、批次、采购数、累计收货 |

## 5. 已挂路由但无表格的页面

- `/big-screen` 运营大屏 → `dashboard/BigScreenView.vue`
- `/analytics` 数据分析 → `analytics/AnalyticsView.vue`
- `/device-map` 投放地图 → `devices/DeviceMapView.vue`
- `/device-kpi` 设备可用性 → `devices/DeviceKpiView.vue`
- `/sla` 服务时限监控 → `sla/SlaView.vue`
- `/profile` 个人中心 → `profile/ProfileView.vue`
- `/recognition-demo` 识别演示 → `vision/RecognitionDemoView.vue`
- `/login` login → `LoginView.vue`
- `/print` 打印单据 → `print/PrintView.vue`

