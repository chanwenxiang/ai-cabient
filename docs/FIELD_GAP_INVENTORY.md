# 三端字段差距清单（全页面/表格盘点）

> 自动扫描生成：对比「页面已展示字段」与「shared-types / 常见竞品必填列」。
> 优先级：P0 严重影响对账/履约 · P1 日常运营 · P2 增强体验。

## 0. 总览

- 运营后台 views：68 个
- 商户端 pages：21 个
- 消费端 pages：23 个
- 复现：`python scripts/gen-field-gap-inventory.py`

### 0.1 本轮已闭合（2026-08-30）

- 订单：`refundedCents` / `payTradeNo` / `paidAt` / **`splitStatus`**
- 争议：建议 vs 已退；Admin SLA 列；商户列表 `hasVideo` + `claimedAmountCents`；**Admin `assignee` 处理人**
- 补货：商户任务 **`routeName` + `plannedDate`（计划/截止日）**
- 会员：权益由 **`/member/profile.levels`**（积分倍率 / `priceDiscountPct` / 升级进度）生成；Admin 可编辑折扣
- 设备 Admin：`currentTempC` / `firmwareVersion` / `salesLockReason`
- 商户柜机：`latitude` / `longitude` / `firmwareVersion` + 导航
- 设备报表：Admin/商户柜机报表补商户·线路·地址·停售·温度·固件·客单
- 体验项复核：券门槛/有效期/适用柜、钱包冻结·手续费·回执、争议时间线、附近柜营业与库存、订单渠道/优惠/货道/开票均已落 UI
- 部署：trade-service 正式 Docker 镜像重建

### 0.2 剩余高优先

见 §1；本轮 FIELD_GAP P0/P1 主线已收口，余量多为体验增强。

## 1. 高优先差距（跨端共性，先修）

| 域 | 剩余 P0/P1 | 说明 |
|----|------------|------|
| 订单 | ~~退款额 / 外部单号 / 支付时间 / 分账状态~~ | Admin/商户列表与详情已透出 `splitStatus` |
| 设备 | ~~Admin 温/固件/停售原因~~ → ~~商户端 lat/lng、固件~~ | 商户列表/详情透出坐标与固件，「导航到柜」 |
| 开门会话 | 基本齐全 | 入口/预授权/视频/识别耗时/失败原因已有 |
| 争议 | ~~金额三列 / Admin SLA / hasVideo / 处理人~~ | 结案写入 `assignee`；Admin 列已透出 |
| 补货 | ~~线路名 / 截止时间~~ | 任务联 `replenishment_route` 透出 `routeName`/`plannedDate` |
| 钱包/分账 | 基本齐全 | 冻结/手续费/外部单号已透出 |
| 会员/券 | ~~等级权益接 API~~ | 消费端权益由 profile.levels 生成；Admin 可配折扣 |

## 2. 运营后台（admin-vue）— 全页面表格列

### `analytics/AnalyticsView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `analytics/FootfallView.vue` （列数 10）

| # | 列 |
|---|----|
| 1 | deviceName:柜机 |
| 2 | opens:开门 |
| 3 | orders:订单 |
| 4 | 转化率 |
| 5 | 营收 |
| 6 | skuName:商品 |
| 7 | skuId:SKU |
| 8 | qtySold:销量 |
| 9 | 件均价 |
| 10 | 营收占比 |

### `announcements/AnnouncementsView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | announceId:公告编号 |
| 2 | 公告 |
| 3 | 优先级 |
| 4 | 目标 |
| 5 | 状态 |
| 6 | 发布时间 |
| 7 | 操作 |

### `consistency/ConsistencyView.vue` （列数 10）

| # | 列 |
|---|----|
| 1 | 类型 |
| 2 | 键 |
| 3 | tableName:表 |
| 4 | 级别 |
| 5 | 基准 |
| 6 | 对照 |
| 7 | 说明 |
| 8 | 状态 |
| 9 | 检出时间 |
| 10 | 操作 |

### `dashboard/BigScreenView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `dashboard/DashboardView.vue` （列数 6）

| # | 列 |
|---|----|
| 1 | 优先级 |
| 2 | 类型 |
| 3 | title:标题 |
| 4 | 关联 |
| 5 | detail:详情 |
| 6 | 操作 |

### `devices/DeviceDetailView.vue` （列数 20）

| # | 列 |
|---|----|
| 1 | ticketId:单号 |
| 2 | title:标题 |
| 3 | status:状态 |
| 4 | 优先级 |
| 5 | createdAt:创建 |
| 6 | 更新 |
| 7 | 指标 |
| 8 | 数值 |
| 9 | 上报时间 |
| 10 | 会话 |
| 11 | 类型 |
| 12 | 入口 |
| 13 | 录像 |
| 14 | 订单 |
| 15 | 失败原因 |
| 16 | 时间 |
| 17 | 操作 |
| 18 | 渠道 |
| 19 | 金额 |
| 20 | 优惠 |

### `devices/DeviceKpiView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `devices/DeviceListView.vue` （列数 19）

| # | 列 |
|---|----|
| 1 | deviceId:设备编号 |
| 2 | 设备 |
| 3 | 类型 |
| 4 | 状态 |
| 5 | 运营态 |
| 6 | 生命周期 |
| 7 | 柜内温度 |
| 8 | 停售原因 |
| 9 | 固件 |
| 10 | 地址 |
| 11 | IMEI |
| 12 | 资产方 |
| 13 | 路线 |
| 14 | 商户 |
| 15 | 退款方式 |
| 16 | 最近会话 |
| 17 | 会话状态 |
| 18 | 更新时间 |
| 19 | 操作 |

### `devices/DeviceMapView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `devices/DeviceOpsMonitorView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | eventId:事件ID |
| 2 | eventType:类型 |
| 3 | 级别 |
| 4 | 设备名称 |
| 5 | deviceId:设备编号 |
| 6 | title:标题 |
| 7 | detail:详情 |
| 8 | 账龄 |
| 9 | 时间 |

### `devices/RepairTicketsView.vue` （列数 12）

| # | 列 |
|---|----|
| 1 | ticketId:工单号 |
| 2 | deviceId:设备 |
| 3 | merchantName:商户 |
| 4 | title:标题 |
| 5 | faultType:故障类型 |
| 6 | priority:优先级 |
| 7 | status:状态 |
| 8 | 负责人 |
| 9 | 备注 |
| 10 | 创建时间 |
| 11 | 关闭时间 |
| 12 | 操作 |

### `disputes/DisputeListView.vue` （列数 23）

| # | 列 |
|---|----|
| 1 | ticketId:工单号 |
| 2 | 工单 |
| 3 | 置信度 |
| 4 | 设备 |
| 5 | 会话 |
| 6 | 关联订单 |
| 7 | 状态 |
| 8 | 分类 |
| 9 | 优先级 |
| 10 | 已扣金额 |
| 11 | 建议金额 |
| 12 | 已退金额 |
| 13 | SLA |
| 14 | 证据 |
| 15 | 创建时间 |
| 16 | 结案时间 |
| 17 | 操作 |
| 18 | skuName:商品 |
| 19 | skuId:SKU |
| 20 | quantity:数量 |
| 21 | 单价 |
| 22 | 小计 |
| 23 | slotId:货道 |

### `exceptions/ExceptionListView.vue` （列数 13）

| # | 列 |
|---|----|
| 1 | exceptionId:异常编号 |
| 2 | 异常 |
| 3 | 级别 |
| 4 | 类型 |
| 5 | 设备 |
| 6 | 会话 |
| 7 | 订单 |
| 8 | 用户 |
| 9 | 状态 |
| 10 | 处理时限 |
| 11 | 负责人 |
| 12 | 创建时间 |
| 13 | 操作 |

### `feedback/FeedbackView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | feedbackId:反馈编号 |
| 2 | 类型 |
| 3 | 内容 |
| 4 | 用户 |
| 5 | 设备 |
| 6 | 评分 |
| 7 | 状态 |
| 8 | 时间 |
| 9 | 操作 |

### `finance/BalanceRefundView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | requestNo:申请号 |
| 2 | 用户 |
| 3 | 金额 |
| 4 | 状态 |
| 5 | 申请原因 |
| 6 | 审核备注 |
| 7 | 失败原因 |
| 8 | 申请时间 |
| 9 | 操作 |

### `finance/FinanceView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | skuId:商品编号 |
| 2 | 商品 |
| 3 | qtySold:销量 |
| 4 | 营收 |
| 5 | 成本 |
| 6 | 毛利 |
| 7 | 毛利率 |
| 8 | 件均价 |
| 9 | 件均成本 |

### `finance/FundBillView.vue` （列数 17）

| # | 列 |
|---|----|
| 1 | bizDate:账期 |
| 2 | merchantId:商户编号 |
| 3 | 商户 |
| 4 | 订单实付 |
| 5 | 平台抽成 |
| 6 | 通道费(估) |
| 7 | 已入账 |
| 8 | 待入账 |
| 9 | orderCount:笔数 |
| 10 | 固化 |
| 11 | entryId:分录号 |
| 12 | 财务类型 |
| 13 | 收支 |
| 14 | 金额 |
| 15 | orderId:订单 |
| 16 | deviceId:货柜 |
| 17 | 时间 |

### `finance/InvoiceListView.vue` （列数 12）

| # | 列 |
|---|----|
| 1 | 申请号 |
| 2 | 订单 |
| 3 | 用户 |
| 4 | title:抬头 |
| 5 | 税号 |
| 6 | 邮箱 |
| 7 | 金额 |
| 8 | 状态 |
| 9 | 驳回原因 |
| 10 | 申请时间 |
| 11 | 开票时间 |
| 12 | 操作 |

### `finance/LineManagerView.vue` （列数 44）

| # | 列 |
|---|----|
| 1 | managerId:经理编号 |
| 2 | 姓名 |
| 3 | phone:手机 |
| 4 | orgName:组织 |
| 5 | userId:绑定用户 |
| 6 | wxOpenid:微信 OpenID |
| 7 | 余额(元) |
| 8 | 冻结(元) |
| 9 | 绑柜 |
| 10 | commissionRateBps:佣金比例 |
| 11 | commissionFixedCents:固定分/单 |
| 12 | status:状态 |
| 13 | 创建时间 |
| 14 | 操作 |
| 15 | requestId:单号 |
| 16 | requestNo:幂等号 |
| 17 | managerName:线长 |
| 18 | 金额(元) |
| 19 | 通道 |
| 20 | payoutRef:回执 |
| 21 | payoutMessage:打款说明 |
| 22 | reviewRemark:审核备注 |
| 23 | 申请时间 |
| 24 | 打款时间 |
| 25 | taskId:编号 |
| 26 | managerId:线长ID |
| 27 | title:任务 |
| 28 | routeCode:线路 |
| 29 | 进度 |
| 30 | 奖金(元) |
| 31 | 截止 |
| 32 | 类型 |
| 33 | 变动(元) |
| 34 | 余额后 |
| 35 | 冻结后 |
| 36 | 关联 |
| 37 | remark:备注 |
| 38 | 时间 |
| 39 | bizDate:日期 |
| 40 | 成交总额 |
| 41 | 佣金 |
| 42 | orderCount:单量 |
| 43 | 客单价 |
| 44 | 佣金率 |

### `finance/MerchantWithdrawView.vue` （列数 24）

| # | 列 |
|---|----|
| 1 | merchantId:商户编号 |
| 2 | merchantName:名称 |
| 3 | 联系电话 |
| 4 | 余额(元) |
| 5 | 冻结(元) |
| 6 | 可用(元) |
| 7 | status:状态 |
| 8 | 操作 |
| 9 | requestId:单号 |
| 10 | requestNo:幂等号 |
| 11 | merchantName:商户 |
| 12 | 金额(元) |
| 13 | 通道 |
| 14 | payoutRef:回执 |
| 15 | payoutMessage:打款说明 |
| 16 | reviewRemark:审核备注 |
| 17 | 申请时间 |
| 18 | 类型 |
| 19 | 变动(元) |
| 20 | 余额后 |
| 21 | 冻结后 |
| 22 | 关联单号 |
| 23 | remark:备注 |
| 24 | 时间 |

### `growth/AdAssetsView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | assetId:ID |
| 2 | title:标题 |
| 3 | 类型 |
| 4 | 预览 |
| 5 | durationSeconds:时长(秒) |
| 6 | 状态 |
| 7 | 上传时间 |
| 8 | 操作 |

### `growth/AdCampaignsView.vue` （列数 11）

| # | 列 |
|---|----|
| 1 | campaignId:ID |
| 2 | name:名称 |
| 3 | 状态 |
| 4 | 范围 |
| 5 | 素材 |
| 6 | 曝光 |
| 7 | 完播 |
| 8 | 完播率 |
| 9 | 柜机数 |
| 10 | 时间窗 |
| 11 | 操作 |

### `growth/MarketingRoiView.vue` （列数 11）

| # | 列 |
|---|----|
| 1 | activityName:活动 |
| 2 | 类型 |
| 3 | 状态 |
| 4 | 预算(元) |
| 5 | 预算已用(元) |
| 6 | claimedCount:发券数 |
| 7 | usedCount:核销数 |
| 8 | 核销率 |
| 9 | 订单优惠(元) |
| 10 | orderCount:带动订单 |
| 11 | 带动营收(元) |

### `growth/MemberLevelsView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | levelCode:等级编码 |
| 2 | levelName:等级名称 |
| 3 | 累计消费区间(元) |
| 4 | 累计积分区间 |
| 5 | 积分倍率 |
| 6 | 会员折扣 |
| 7 | sortOrder:排序 |
| 8 | 状态 |
| 9 | 操作 |

### `growth/NotificationsView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | id:ID |
| 2 | 时间 |
| 3 | 受众 |
| 4 | title:标题 |
| 5 | 内容 |
| 6 | 业务 |
| 7 | 关联单号 |
| 8 | 操作 |

### `growth/PointsRedeemView.vue` （列数 11）

| # | 列 |
|---|----|
| 1 | itemId:ID |
| 2 | 兑换项 |
| 3 | pointsCost:所需积分 |
| 4 | couponName:兑换优惠券 |
| 5 | 券定义 |
| 6 | 库存 / 已兑 |
| 7 | 可兑 |
| 8 | sortOrder:排序 |
| 9 | 状态 |
| 10 | 创建时间 |
| 11 | 操作 |

### `growth/ReplenishmentStaffView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | userId:工号 |
| 2 | 姓名 |
| 3 | 手机 |
| 4 | totalTasks:任务数 |
| 5 | completedTasks:已完成 |
| 6 | 完成率 |
| 7 | 平均耗时(分) |
| 8 | openTasks:待办 |
| 9 | avgDailyTasks:日均任务 |

### `growth/SkuReviewView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | skuId:SKU |
| 2 | skuName:商品 |
| 3 | category:分类 |
| 4 | 动销表现 |
| 5 | salesQty:销量 |
| 6 | 营收(元) |
| 7 | stockDays:库存天数 |
| 8 | 评审状态 |
| 9 | 操作 |

### `growth/UserAnalysisView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | userId:用户ID |
| 2 | 姓名/手机 |
| 3 | orderCount:订单数 |
| 4 | 累计消费 |
| 5 | 客单价 |
| 6 | orderCount:累计订单 |
| 7 | 上次消费 |

### `LoginView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `merchants/MerchantOnboardingView.vue` （列数 11）

| # | 列 |
|---|----|
| 1 | merchantId:商户 |
| 2 | channel:渠道 |
| 3 | status:状态 |
| 4 | 外部商户号 |
| 5 | 外部单号 |
| 6 | 支付模式 |
| 7 | 备注 |
| 8 | 最近同步 |
| 9 | 创建时间 |
| 10 | 更新时间 |
| 11 | 操作 |

### `merchants/MerchantSplitsView.vue` （列数 30）

| # | 列 |
|---|----|
| 1 | merchantId:商户编号 |
| 2 | 商户 |
| 3 | 抽成 |
| 4 | 现场作业 |
| 5 | 经营工具 |
| 6 | 团队设置 |
| 7 | 商户改货道 |
| 8 | 商户改价 |
| 9 | deviceCount:设备数 |
| 10 | 商户状态 |
| 11 | 联系人 |
| 12 | 电话 |
| 13 | 备注 |
| 14 | templateName:岗位 |
| 15 | templateCode:编码 |
| 16 | description:说明 |
| 17 | permissionHint:权限提示 |
| 18 | 权限数 |
| 19 | 分账编号 |
| 20 | 订单 |
| 21 | 商户收入 |
| 22 | 平台抽成 |
| 23 | 订单总额 |
| 24 | 设备 |
| 25 | 结算批次 |
| 26 | 状态 |
| 27 | 失败原因 |
| 28 | 创建时间 |
| 29 | 结算时间 |
| 30 | 操作 |

### `orders/OrderListView.vue` （列数 28）

| # | 列 |
|---|----|
| 1 | orderId:订单号 |
| 2 | 商品 |
| 3 | 金额 |
| 4 | 优惠 |
| 5 | 会话 |
| 6 | 用户 |
| 7 | 设备 |
| 8 | 商户 |
| 9 | 流水号 |
| 10 | 订单状态 |
| 11 | 支付状态 |
| 12 | 退款状态 |
| 13 | 支付渠道 |
| 14 | 扣库存 |
| 15 | 退款策略 |
| 16 | 退款时间 |
| 17 | 账龄 |
| 18 | 创建时间 |
| 19 | 操作 |
| 20 | skuId:SKU |
| 21 | slotId:货道 |
| 22 | batchNo:批次 |
| 23 | quantity:数量 |
| 24 | 单价 |
| 25 | 小计 |
| 26 | maxQty:可退 |
| 27 | 退款数量 |
| 28 | 回库 |

### `ota/OtaView.vue` （列数 10）

| # | 列 |
|---|----|
| 1 | appVersion:版本 |
| 2 | channel:渠道 |
| 3 | status:状态 |
| 4 | 强制 |
| 5 | 灰度% |
| 6 | 定向设备 |
| 7 | minVersion:最低版本 |
| 8 | 发布时间 |
| 9 | releaseNotes:说明 |
| 10 | 操作 |

### `print/PrintView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `profile/ProfileView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `promotions/CouponsView.vue` （列数 11）

| # | 列 |
|---|----|
| 1 | couponDefId:券定义编号 |
| 2 | 优惠券 |
| 3 | 类型 |
| 4 | 面值 |
| 5 | 最低消费 |
| 6 | 有效期 |
| 7 | 发行/总量 |
| 8 | 剩余 |
| 9 | 说明 |
| 10 | 状态 |
| 11 | 操作 |

### `promotions/PromotionsView.vue` （列数 11）

| # | 列 |
|---|----|
| 1 | activityId:活动编号 |
| 2 | 活动 |
| 3 | 类型 |
| 4 | 时间 |
| 5 | 预算 |
| 6 | 已使用 |
| 7 | 剩余预算 |
| 8 | 每人限次 |
| 9 | 适用柜 |
| 10 | 状态 |
| 11 | 操作 |

### `recharges/RechargeListView.vue` （列数 10）

| # | 列 |
|---|----|
| 1 | orderId:充值单 |
| 2 | 用户 |
| 3 | 金额 |
| 4 | 渠道 |
| 5 | 外部单号 |
| 6 | 状态 |
| 7 | 创建时间 |
| 8 | 支付时间 |
| 9 | 退款时间 |
| 10 | 操作 |

### `reconciliation/ReconciliationView.vue` （列数 12）

| # | 列 |
|---|----|
| 1 | 对账 |
| 2 | 渠道 |
| 3 | 状态 |
| 4 | 差异笔数 |
| 5 | 创建时间 |
| 6 | 操作 |
| 7 | platformTradeNo:平台流水 |
| 8 | merchantOrderNo:商户单号 |
| 9 | 金额 |
| 10 | 交易类型 |
| 11 | 交易时间 |
| 12 | 匹配 |

### `replenishment/ReplenishmentView.vue` （列数 41）

| # | 列 |
|---|----|
| 1 | 任务 |
| 2 | 设备 |
| 3 | 设备ID |
| 4 | 任务状态 |
| 5 | 人员 |
| 6 | 签到 |
| 7 | 用时 |
| 8 | 完成 |
| 9 | 出库单 |
| 10 | 操作 |
| 11 | 路线 |
| 12 | routeId:路线ID |
| 13 | 设备数 |
| 14 | plannedDate:计划日期 |
| 15 | 状态 |
| 16 | 签到 / GPS |
| 17 | 完成时间 |
| 18 | 要货单 |
| 19 | 备注 |
| 20 | merchantName:商户 |
| 21 | 目标设备 |
| 22 | 明细 |
| 23 | 审核人 |
| 24 | 审核时间 |
| 25 | 驳回原因 |
| 26 | 补货任务 |
| 27 | 提交时间 |
| 28 | slotCode:货道 |
| 29 | assignedSkuName:商品 |
| 30 | bookQty:账面 |
| 31 | minLevel:最低 |
| 32 | parLevel:目标 |
| 33 | skuId:商品 SKU |
| 34 | batchNo:批次 |
| 35 | lotId:批次 ID |
| 36 | quantity:数量 |
| 37 | 原因 |
| 38 | 创建时间 |
| 39 | 类型 |
| 40 | 效期 |
| 41 | 已入账 |

### `reports/DeviceReportView.vue` （列数 10）

| # | 列 |
|---|----|
| 1 | deviceId:设备编号 |
| 2 | 设备 |
| 3 | 状态 |
| 4 | orderTotal:累计订单 |
| 5 | 累计营收 |
| 6 | orderToday:今日订单 |
| 7 | 今日营收 |
| 8 | sessionTotal:累计会话 |
| 9 | sessionActive:进行中 |
| 10 | 操作 |

### `reports/SalesReportsView.vue` （列数 10）

| # | 列 |
|---|----|
| 1 | dimKey:编码 |
| 2 | dimLabel:名称 |
| 3 | orderCount:订单数 |
| 4 | qty:销量 |
| 5 | 营收 |
| 6 | 成本 |
| 7 | 毛利 |
| 8 | 毛利率 |
| 9 | 客单价 |
| 10 | 件均价 |

### `reports/StockHealthView.vue` （列数 14）

| # | 列 |
|---|----|
| 1 | 维度 |
| 2 | 设备 |
| 3 | 设备ID |
| 4 | 商户 |
| 5 | 路线 |
| 6 | SKU |
| 7 | SKU ID |
| 8 | 库存 |
| 9 | 容量 |
| 10 | 阈值 |
| 11 | 缺货率 |
| 12 | 断货天 |
| 13 | 到期日 |
| 14 | 操作 |

### `risk/RiskView.vue` （列数 14）

| # | 列 |
|---|----|
| 1 | 事件 |
| 2 | 用户 |
| 3 | 设备 |
| 4 | 详情 |
| 5 | 级别 |
| 6 | 处置 |
| 7 | 处置备注 |
| 8 | 处置时间 |
| 9 | 时间 |
| 10 | 原因 |
| 11 | 来源 |
| 12 | 到期 |
| 13 | 加入时间 |
| 14 | 操作 |

### `sessions/SessionListView.vue` （列数 16）

| # | 列 |
|---|----|
| 1 | sessionId:会话编号 |
| 2 | 类型 |
| 3 | 入口渠道 |
| 4 | 预授权 |
| 5 | 购物/识别 |
| 6 | 录像 |
| 7 | 用户 |
| 8 | 设备 |
| 9 | 订单 |
| 10 | 状态 |
| 11 | 等待原因 |
| 12 | 滞留 / 时限 |
| 13 | 失败原因 |
| 14 | 更新时间 |
| 15 | 时长 |
| 16 | 操作 |

### `skus/SkuListView.vue` （列数 13）

| # | 列 |
|---|----|
| 1 | skuCode:编号 |
| 2 | 主图 |
| 3 | barcode:条码 |
| 4 | 名称 |
| 5 | brand:品牌 |
| 6 | spec:规格 |
| 7 | unit:单位 |
| 8 | 售价 |
| 9 | 成本 |
| 10 | category:类目 |
| 11 | 状态 |
| 12 | 添加时间 |
| 13 | 操作 |

### `skus/SkuVisionEnrollView.vue` （列数 15）

| # | 列 |
|---|----|
| 1 | skuCode:编号 |
| 2 | 主图 |
| 3 | 商品 |
| 4 | 基准价 |
| 5 | 成本 |
| 6 | category:类目 |
| 7 | 端侧类名 |
| 8 | 识别状态 |
| 9 | 映射/模型 |
| 10 | 商品状态 |
| 11 | 扣款阈值 |
| 12 | 检测阈值 |
| 13 | 操作 |
| 14 | quantity:数量 |
| 15 | 置信度 |

### `sla/SlaView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `system/AlertRuleView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | 分组 |
| 2 | 规则说明 |
| 3 | 配置键 |
| 4 | 单位/提示 |
| 5 | 当前值 |
| 6 | 更新时间 |
| 7 | 操作 |

### `system/ApprovalConfigView.vue` （列数 6）

| # | 列 |
|---|----|
| 1 | 业务 |
| 2 | defName:名称 |
| 3 | 启用 |
| 4 | 流程预览 |
| 5 | remark:备注 |
| 6 | 操作 |

### `system/AuditLogView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | logId:日志编号 |
| 2 | 时间 |
| 3 | 操作人ID |
| 4 | 操作人 |
| 5 | 动作 |
| 6 | 对象类型 |
| 7 | 对象ID |
| 8 | 详情 |

### `system/DepartmentManageView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | deptKey:编码 |
| 2 | deptName:名称 |
| 3 | 上级 |
| 4 | memberCount:成员数 |
| 5 | sortOrder:排序 |
| 6 | 状态 |
| 7 | remark:备注 |
| 8 | 操作 |

### `system/DevOpsHubView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `system/DictManageView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | 类型 |
| 2 | itemCount:项数 |
| 3 | 操作 |
| 4 | dictDataId:数据编号 |
| 5 | 字典项 |
| 6 | 值 |
| 7 | sortOrder:排序 |
| 8 | 状态 |

### `system/MenuManageView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | 名称 |
| 2 | 类型 |
| 3 | 权限标识 |
| 4 | 路由 |
| 5 | sortOrder:排序 |
| 6 | 状态 |
| 7 | 操作 |

### `system/OperatorManageView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | userId:用户编号 |
| 2 | 账号 |
| 3 | 手机号 |
| 4 | 状态 |
| 5 | 角色 |
| 6 | 部门 |
| 7 | 数据范围 |
| 8 | 操作 |

### `system/OrgSitesView.vue` （列数 12）

| # | 列 |
|---|----|
| 1 | deviceName:柜机 |
| 2 | deviceId:设备ID |
| 3 | siteName:场地 |
| 4 | address:地址 |
| 5 | landlordName:场地主 |
| 6 | 联系电话 |
| 7 | 月费 |
| 8 | 起租 |
| 9 | 到期 |
| 10 | 状态 |
| 11 | 备注 |
| 12 | 操作 |

### `system/RoleManageView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | roleId:角色编号 |
| 2 | 角色 |
| 3 | 权限字符 |
| 4 | 状态 |
| 5 | 权限数 |
| 6 | remark:备注 |
| 7 | 操作 |

### `system/ScheduledTaskView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | 任务名称 |
| 2 | 任务标识 |
| 3 | 分组 |
| 4 | 调度说明 |
| 5 | 状态 |
| 6 | 最近执行 |
| 7 | 最近结果说明 |
| 8 | 备注 |
| 9 | 操作 |

### `system/SystemConfigView.vue` （列数 5）

| # | 列 |
|---|----|
| 1 | 配置键 |
| 2 | 说明 |
| 3 | 配置值 |
| 4 | 更新时间 |
| 5 | 操作 |

### `upload/UploadQueueView.vue` （列数 10）

| # | 列 |
|---|----|
| 1 | sessionId:会话编号 |
| 2 | 用户 |
| 3 | 设备 |
| 4 | 对象路径 |
| 5 | 上传状态 |
| 6 | 等待原因 |
| 7 | 滞留 / 时限 |
| 8 | 预览 |
| 9 | 关门时间 |
| 10 | 更新时间 |

### `users/PhoneVerifyView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | logId:记录ID |
| 2 | phone:手机号 |
| 3 | userId:用户ID |
| 4 | channel:渠道 |
| 5 | merchantId:商户 |
| 6 | 验证时间 |
| 7 | 操作 |

### `users/UserListView.vue` （列数 11）

| # | 列 |
|---|----|
| 1 | userId:用户编号 |
| 2 | 姓名 |
| 3 | 手机号 |
| 4 | 角色 |
| 5 | 余额 |
| 6 | 实名 |
| 7 | 会员等级 |
| 8 | 积分 |
| 9 | 黑名单 |
| 10 | 注册时间 |
| 11 | 操作 |

### `vision/RecognitionDemoView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `vision/VisionMappingView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | className:类名 |
| 2 | 商品 |
| 3 | 入驻状态 |
| 4 | 映射/模型 |
| 5 | 最低置信度 |
| 6 | 操作 |
| 7 | categoryId:类目ID |
| 8 | categoryName:类目名 |
| 9 | skuId:SKU |

### `warehouse/WarehouseView.vue` （列数 85）

| # | 列 |
|---|----|
| 1 | warehouseId:仓库编号 |
| 2 | 仓库 |
| 3 | address:地址 |
| 4 | 状态 |
| 5 | 操作 |
| 6 | transferNo:调拨单号 |
| 7 | 调出仓 |
| 8 | 调入仓 |
| 9 | 明细 |
| 10 | 发运 |
| 11 | 收货 |
| 12 | 备注 |
| 13 | supplierId:供应商编号 |
| 14 | 供应商 |
| 15 | contactName:联系人 |
| 16 | contactPhone:联系电话 |
| 17 | paymentTermsDays:账期(天) |
| 18 | 商品 |
| 19 | batchNo:批次 |
| 20 | orderedQty:采购数 |
| 21 | receivedQty:已收数 |
| 22 | returnedQty:已退数 |
| 23 | 成本 |
| 24 | expiryDate:到期日期 |
| 25 | purchaseOrderId:采购单 |
| 26 | refNo:外部单号 |
| 27 | 入库仓库 |
| 28 | soldQty7d:近7日销量 |
| 29 | soldQty14d:近14日销量 |
| 30 | 日均销量 |
| 31 | 预测日均 |
| 32 | 日均趋势 |
| 33 | onHandQty:仓库库存 |
| 34 | pendingPoQty:待收采购 |
| 35 | coverageDays:覆盖天数 |
| 36 | 建议采购量 |
| 37 | 安全库存 |
| 38 | 建议理由 |
| 39 | quantity:退货数 |
| 40 | returnId:退货单 |
| 41 | 创建时间 |
| 42 | 付款时间 |
| 43 | 付款金额 |
| 44 | 关联采购单 |
| 45 | 应付金额 |
| 46 | 已付 |
| 47 | 未付余额 |
| 48 | 到期日 |
| 49 | 逾期 |
| 50 | 盘点单号 |
| 51 | 模式 |
| 52 | bookQty:账面件数 |
| 53 | countedQty:实盘件数 |
| 54 | 差异件数 |
| 55 | diffLineCount:差异行数 |
| 56 | 货位编码 |
| 57 | binName:货位名称 |
| 58 | 货位 |
| 59 | productionDate:生产日期 |
| 60 | quantity:数量 |
| 61 | 目标设备 |
| 62 | 货道 |
| 63 | 交接状态 |
| 64 | 出库单 |
| 65 | routeId:路线 |
| 66 | 出库仓库 |
| 67 | 在途 / 时限 |
| 68 | 发运时间 |
| 69 | quantity:库存 |
| 70 | 效期 |
| 71 | movementId:流水 |
| 72 | 类型 |
| 73 | deltaQty:变动 |
| 74 | 关联业务 |
| 75 | 关联单号 |
| 76 | 时间 |
| 77 | bookQty:账面 |
| 78 | 实盘 |
| 79 | 差异 |
| 80 | 待收 |
| 81 | 累计收货 |
| 82 | receivedQty:已收 |
| 83 | returnedQty:已退 |
| 84 | 可退 |
| 85 | 本次退货 |

### 后台疑似偏薄页（需优先对照 DTO）

- `LoginView.vue`
- `analytics/AnalyticsView.vue`
- `dashboard/BigScreenView.vue`
- `devices/DeviceKpiView.vue`
- `devices/DeviceMapView.vue`
- `print/PrintView.vue`
- `profile/ProfileView.vue`
- `sla/SlaView.vue`
- `system/DevOpsHubView.vue`
- `vision/RecognitionDemoView.vue`

## 3. shared-types 关键 DTO 字段（后端已有、前端常漏展）

### 订单 — `OrderDetailDto`

DTO 字段（25）：`orderId`, `sessionId`, `deviceId`, `deviceName`, `merchantId`, `merchantName`, `status`, `payChannel`, `payTime`, `paidAt`, `videoUri`, `paymentOperationId`, `payTradeNo`, `balanceBeforeCents`, `balanceAfterCents`, `totalAmountCents`, `couponDiscountCents`, `memberDiscountCents`, `originalAmountCents`, `lines`, `createdAt`, `refundedAt`, `refundedCents`, `inventoryDeducted`, `refundPolicy`

竞品/体验期望列：订单号、状态、金额、设备、支付渠道、下单时间、退款、优惠券、会员价、货道、支付时间、分账

### 设备 — `DeviceInfo`

DTO 字段（30）：`deviceId`, `deviceName`, `deviceType`, `merchantId`, `merchantName`, `onlineStatus`, `activeSessionId`, `activeSessionState`, `updatedAt`, `refundPolicy`, `effectiveRefundPolicy`, `salesLocked`, `salesLockReason`, `replenishmentInProgress`, `lifecycleStatus`, `imei`, `assetOwner`, `coopMode`, `depositCents`, `dataFeeCents`, `opsTags`, `routeCode`, `latitude`, `longitude`, `address`, `deployedAt`, `lifecycleRemark`, `currentTempC`, `oosSlotCount`, `lowStockSlotCount`

竞品/体验期望列：设备号、名称、在线、商户、地址、停售、温度、线路、经纬度、固件、库存健康

### 会话 — `SessionDto`

DTO 字段（21）：`sessionId`, `deviceId`, `deviceName`, `state`, `orderId`, `failureReason`, `failReason`, `createdAt`, `updatedAt`, `closeTime`, `openTime`, `videoUri`, `uploadStatus`, `videoPreviewUrl`, `sessionKind`, `entryChannel`, `payChannel`, `preauthCents`, `preauthStatus`, `shoppingDurationMs`, `recognitionDurationMs`

竞品/体验期望列：会话号、用户、设备、状态、开门时间、渠道、预授权、视频

### 争议 — `DisputeTicketDto`

DTO 字段（23）：`ticketId`, `sessionId`, `deviceId`, `deviceName`, `reason`, `status`, `createdAt`, `resolvedAt`, `closedAt`, `orderId`, `billedAmountCents`, `refundedAmountCents`, `claimedAmountCents`, `suggestedItems`, `resolutionItems`, `category`, `priority`, `operatorNote`, `slaOverdue`, `slaHoursRemaining`, `evidence`, `reviewCode`, `detectedClasses`

竞品/体验期望列：工单号、订单、状态、原因、金额、视频、处理结果、创建时间

### 附近柜机 — `NearbyDeviceDto`

DTO 字段（15）：`deviceId`, `deviceName`, `address`, `latitude`, `longitude`, `distanceMeters`, `onlineStatus`, `available`, `sellableSkuCount`, `sellableItemCount`, `previewSkus`, `skuId`, `skuName`, `quantity`, `unitPriceCents`

竞品/体验期望列：名称、距离、地址、在线、营业

## 4. 商户端（merchant-mp）— 全页面

### `pages/alerts/alerts.vue`

- UI 文案：审核；故障；库存；临期；重试；查看柜机；货道差异（账实不符）；待办；暂无待办事项
- 绑定字段：isFinite, isNaN, getTime, getMonth, getDate, getHours, getMinutes, type, ticketId, deviceId, exceptionId, dispute, offline, stock, expiry, disputes, lowStock, typeLabel, title, detail, deviceName, slotCode, assignedSkuName, bookQty, physicalQty, qtyDiff

### `pages/announcements/announcements.vue`

- UI 文案：重试；新；通知公告；暂无平台公告；未读
- 绑定字段：announceId, priority, publishAt, title, content

### `pages/announcements/detail.vue`

- UI 文案：重试；公告详情
- 绑定字段：priority, publishAt, title, content

### `pages/business/business.vue`

- UI 文案：重试；经营毛利；客单价；毛利；本月已结算；待结算；重点商品；分账异常；商品经营表现；按销售额排序；暂无可分析的销售数据；销售四表；该区间暂无销售明细；临期摘要；待下架任务；报损件数；报损成本；开票税号资料；月结对账开票用；暂无绑定商户；保存税号资料；柜机报表；导出柜机报表；经营分析；公司名称；纳税人识别号；地址（选填）；电话（选填）
- 绑定字段：label, dimKey, dimLabel, orderCount, qty, marginCents, revenueCents, companyName, taxNo, address, phone, deviceId, deviceName, onlineStatus, routeCode, orderToday, revenueTodayCents, avgOrderValueTodayCents, orderTotal, revenueTotalCents, avgOrderValueTotalCents, sessionTotal, sessionActive, isNaN, getMonth, getDate, getHours, getMinutes, setDate, getFullYear, xlsx, active, muted, itemQtySold, topSkus, failedSplitCount, stockoutSkuCount, skuName, qtySold, insight
- **建议补齐**：~~毛利/客单/缺货损失/同比~~（经营分析卡片已有）

### `pages/device-detail/device-detail.vue`

- UI 文案：当前账号无柜机详情权限；补货任务；发起要货；柜机设置；显示名称；备注；货道；只读（平台未开启或未授权）；柜机详情；请输入柜机显示名称；例如 5；选填运维备注；目标库存
- 绑定字段：reportedAt, tempC, isFinite, getHours, getMinutes, isInteger, on, slotCode, assignedSkuName, bookQty, maxLevel, skuName, skuId, soldQty7d, soldQty14d, ropPoint
- **建议补齐**：~~温度/线路/停售原因/缺货数~~（列表/详情已有；已补导航·固件）

### `pages/home/home.vue`

- UI 文案：重试；待补货；待办；离线柜；扫码到柜；公告；补货任务；柜机列表；待办事项；今日补货；查看记录；常驻；优先待办；经营工具；要货申请；点位定价；结算对账；争议处理；经营分析；今日营收；商户收入；在线柜机；暂无待处理补货任务
- 绑定字段：taskId, deviceId, status, type, title, detail, permissions, displayName, phoneNumber, merchants, revenueCents, date, last7Days, deviceName, slice, primary, label

### `pages/line-wallet/line-wallet.vue`

- UI 文案：重试；可用余额；最近提现；最近流水；线长钱包；未绑定线长身份；提现金额（元）；暂无提现记录；暂无流水记录
- 绑定字段：payChannel, payoutRef, reviewRemark, refId
- **建议补齐**：~~冻结余额/手续费/外部单号/失败原因~~（线长钱包已展示冻结·手续费·回执·备注；流水含余额后/冻结后）

- UI 文案：补货与运营；登录；补货员与商户运营共用入口；手机号；密码；记住账号和密码；请输入11位手机号…；请输入登录密码…
- 绑定字段：startsWith, slice, parseInt

### `pages/messages/messages.vue`

- UI 文案：消息中心
- 绑定字段：id, read, bizType, title, createdAt, body, bizId, unread, label

### `pages/mine/mine.vue`

- UI 文案：编辑资料；取消；保存；现场作业；团队与设置；平台公告；通知公告；消息提醒；微信订阅提醒；保存提醒偏好；经营工具；退出登录；联系电话；告警联系人；告警电话
- 绑定字段：contactPhone, alertContactName, alertContactPhone, key, icon, title, desc, tab, url, label

### `pages/order-detail/order-detail.vue`

- UI 文案：重试；商品明细；无商品明细；原价；券优惠；会员优惠；实付；订单信息；订单号；会话；柜机；支付方式；流水号；退款策略；创建时间；退款；查看柜机；相关争议；订单详情
- 绑定字段：status, totalAmountCents, lines, originalAmountCents, couponDiscountCents, memberDiscountCents, orderId, sessionId, deviceName, deviceId, payTradeNo, paymentOperationId, refundPolicy, createdAt, refundedAt, refundedCents, s, strong, skuName, quantity, slotId, batchNo
- **建议补齐**：~~支付渠道/退款额/优惠明细/货道~~（详情与列表已透出）

- UI 文案：当前账号无定价查看权限；调价历史；重试；已覆盖；暂无调价记录；点位定价；覆盖价(元)；暂无定价数据
- 绑定字段：deviceId, deviceName, isNaN, getMonth, getDate, getHours, getMinutes, parseFloat, skuName, skuId, quantity, overridePriceCents, minPriceCents, maxPriceCents, detail

### `pages/replenishment/replenishment.vue`

- UI 文案：现场补货；补货任务；待处理；已完成；今日完成率；扫码找柜；要货；常驻柜；清除筛选；今日暂无待补货；缺货巡柜；扫码到柜；已签到；无现场照片；签到；开门；核对；现场照片；拍照；选择货道；关闭；添加现场照片；减少数量；增加数量
- 绑定字段：deviceId, skuCount, shortageQty, label, taskId, deviceName, status, createdAt, routeId, checkInAt, outboundId, notes, fileId, localPath, isFinite, lowThreshold, quantity, skuId, jpg, message, room, four, batchNo, slotId, productionDate, expiryDate, slotCode, title, content, cancelText, confirmText
- **建议补齐**：~~行级数量/批次效期/拍照状态~~（补货核对行与现场照片已支持）

### `pages/splits/splits.vue`

- UI 文案：失败；全部；重试；分账明细
- 绑定字段：wechatOutOrderNo, failureReason
- **建议补齐**：~~冻结余额/手续费/外部单号/失败原因~~（外部单 wechatOutOrderNo + failureReason 已展示）

### `pages/team/team.vue`

- UI 文案：重试；邀请成员；点击可重新启用；我；管理；取消；确认邀请；角色；保存角色；重置密码；确认重置；重新启用；关闭；团队成员；暂无团队成员；手机号；初始密码（至少 6 位）；显示名（选填）；新密码（至少 6 位）
- 绑定字段：png, phoneNumber, password, displayName, roleKey, roleName, self, status, wrap

### `pages/wallet/wallet.vue`

- UI 文案：重试；可用余额；最近提现；最近流水；商户钱包；暂无商户钱包；提现金额（元）；暂无提现记录；暂无流水记录
- 绑定字段：payChannel, payoutRef, reviewRemark, refType, refId
- **建议补齐**：~~冻结余额/手续费/外部单号/失败原因~~（冻结·手续费·回执·备注已展示）

## 5. 消费端（consumer-mp）— 全页面

### `pages/announcements/announcements.vue`

- UI 文案：重试；新；通知公告；暂无通知公告；未读
- 绑定字段：announceId, priority, announceType, publishAt, title, content, expireAt, isFinite

### `pages/announcements/detail.vue`

- UI 文案：重试；公告详情
- 绑定字段：priority, announceType, publishAt, title, expireAt, targetScope, content

### `pages/coupons/coupons.vue`

- UI 文案：重试；去扫码购物；看热门活动；已使用；已过期；无门槛；我的优惠券；优惠券加载失败
- 绑定字段：isFinite, expired, used, label, couponName, description
- **建议补齐**：~~有效期；门槛；适用柜范围；过期提醒~~（券卡已展示；即将过期徽标）

### `pages/dispute/detail.vue`

- UI 文案：重试；审核说明；柜机；购物单号；提交时间；处理时间；退款渠道；处理进度；申诉附图；识别参考明细；审核结果；本次未计费商品；最终扣款；账单审核；未找到审核单
- 绑定字段：sessionId, ticketId, orderId, message, tone, icon, title, detail, time, skuName, quantity
- **建议补齐**：~~审核进度时间线；退款到账渠道~~（处理进度时间线 + 退款渠道已展示）

### `pages/feedback/feedback.vue`

- UI 文案：提交反馈；我的反馈；意见反馈；反馈类型；内容；联系方式（选填）；柜机编号（选填）；重试；运营回复；请描述你的问题或建议；手机号或微信，方便回访；例如 CAB-001；暂无反馈记录
- 绑定字段：label, feedbackId, feedbackType, status, content, deviceId, createdAt, reply, handledAt

### `pages/help/help.vue`

- UI 文案：帮助中心；联系客服；客服热线；拨打；平台公告；去查看；在线留言；去反馈；柜机故障；去报修；消息中心；附近柜机；按距离找可开门的柜；去找柜；常见问题；查看我的订单
- 绑定字段：q, a, service_phone

### `pages/index/index.vue`

- UI 文案：关门自动结算；去充值；重试开门；重新扫码；换一台；继续在本柜购物；附近找柜；需要授权；扫码开门需先完成微信授权；取消；去登录；柜机编号；报修；查看审核详情；稍后查看订单；联系运营；知道了；全部；本柜暂无上架商品；故障报修；未找到匹配商品；换个关键词或分类试试；查看全部商品；关闭错误提示；例如 CAB-001…；搜索本柜商品
- 绑定字段：vue, jpg, msg, kind, toastTitle, isFinite, service_phone, sessionId, alipayOnly, deviceId, channel, get, qty, skuId, orderId, tl, tr, bl, br, wait, tone, pulse, icon, title, detail, skuName, category

### `pages/login/login.vue`

- UI 文案：关门自动结算；登录后继续；验证码；密码；手机号；返回；用户协议；隐私政策；退款规则；请输入11位手机号；请输入登录密码；请输入验证码
- 绑定字段：jpg, startsWith, replace, message, code, on

### `pages/marketing/index.vue`

- UI 文案：我的优惠券；进行中；去扫码购物；去领券；热门活动；暂无进行中活动
- 绑定字段：isFinite, tone, title, subtitle, typeLabel, description

### `pages/member/index.vue`

- UI 文案：会员俱乐部；累计消费；已达最高等级；可用积分；积分兑换；积分换券；消息中心；订单·售后；我的券；热门活动；本周上新；我的订单；消费记录；去购物；扫码开门；会员权益；等级说明；当前；会员中心
- 绑定字段：isFinite, lv, warn, on, nextLevelName, orderCount, title, desc, levelName, maxSpent, pointsRate
- **建议补齐**：~~有效期；门槛；适用柜范围；过期提醒~~（会员页展示即将过期券数；细则见优惠券/兑换页）

### `pages/messages/messages.vue`

- UI 文案：全部已读；开启微信消息提醒；通知偏好；关闭后对应类别的消息不再推送与提醒；消息中心
- 绑定字段：id, read, bizType, title, createdAt, body, bizId, status, unread, label
- **建议补齐**：~~未读类型拆分；跳转深链完整参数~~（分类未读角标 + bizId 深链）

### `pages/mine/mine.vue`

- UI 文案：登录后可查看订单与余额；可用余额；充值；微信授权登录；扫码开门前需完成授权；完成开门准备；优先支付方式；余额；微信免密；支付宝免密；订单；优惠券；会员；开门购物；热门活动；积分中心；消息中心；余额明细；暂无余额流水；通知公告；帮助与客服；故障报修；意见反馈；用户协议；服务条款与使用规则；隐私政策；退款规则；自助退款与人工申诉；账单说明；订单构成与余额明细；开发联调；模拟充值；手机号验证（兜底）；退出登录
- 绑定字段：transactionId, businessType, createdAt, businessId, balanceAfterCents, amountCents, isFinite, message

### `pages/nearby/nearby.vue`

- UI 文案：附近柜机；刷新；重试；导航；返回
- 绑定字段：deviceId, deviceName, distanceMeters, address, available, onlineStatus, sellableSkuCount, sellableItemCount, previewSkus, latitude, longitude, skuName, quantity, unitPriceCents
- **建议补齐**：~~营业状态；库存摘要；导航距离单位~~（可开门/在售件数 + m/km 距离）

### `pages/order-detail/order-detail.vue`

- UI 文案：重试；商品清单；本次未识别到取走商品；商品合计；优惠券抵扣；会员优惠；实付；支付信息；支付方式；流水号；扣款时间；退款；退款时间；订单编号；柜机；查看购物视频；帮助与客服；申请开票；提交后运营开具电子发票（演示环境为申请留痕）；发票抬头；税号（企业选填）；接收邮箱（选填）；取消；申诉说明；按行退款（不选则全额退）；订单详情；个人姓名或公司全称；纳税人识别号；发票发送邮箱；例如：我没有拿这个商品 / 数量不对…；删除证据图；添加证据图
- 绑定字段：skuId, slotId, skuName, quantity, batchNo, unitPriceCents, lineAmountCents, couponDiscountCents, memberDiscountCents, refundedAt, maxQty, qty, orderId, id, service_phone, refundedCents, status, totalAmountCents, parseInt, confirm, pay, label
- **建议补齐**：~~支付渠道；优惠/会员价；退款进度；货道；开票入口~~

### `pages/orders/orders.vue`

- UI 文案：重试；去扫码购物；去登录；先去扫码购物；需要关注；审核中；隐藏零元单；可开票；上拉加载更多；没有更多了；故障报修；帮助与客服；我的订单；加载失败；登录后查看订单；暂无订单；当前筛选暂无订单
- 绑定字段：ticketId, createdAt, orderId, status, lineCount, payChannel, couponDiscountCents, memberDiscountCents, originalAmountCents, totalAmountCents, refundedAt, sessionId, isNaN, deviceName, deviceId, lineSummary, refundedCents, payTradeNo, paymentOperationId, tone, label
- **建议补齐**：~~支付渠道；优惠/会员价；退款进度；货道；开票入口~~

### `pages/points/points.vue`

- UI 文案：可用积分；当前等级；积分倍率；升级还差；说明；积分明细；暂无积分记录
- 绑定字段：tip, nextLevelPointsGap, description, points
- **建议补齐**：~~有效期~~（积分明细含有效至）；门槛/适用柜见兑换页

### `pages/points/redeem.vue`

- UI 文案：我的积分；暂无兑换商品；运营上架积分兑换后即可兑换优惠券；积分兑换
- 绑定字段：itemId, coverEmoji, title, subtitle, denominationCents, minSpendCents, validityDays, deviceScope, availableStock, pointsCost
- **建议补齐**：~~有效期；门槛；适用柜范围；过期提醒~~（兑换商品卡已展示）

### `pages/policy/detail.vue`

- UI 文案：相关条款；如有疑问可前往帮助中心联系客服
- 绑定字段：title, label

### `pages/recharge/recharge.vue`

- UI 文案：当前余额；申请退余额；自定义金额（元）；返回我的；充值记录；取消；余额充值；退款金额（元）；如 33.5；暂无充值记录
- 绑定字段：requestId, amountCents, status, requestNo, reviewRemark, failReason, createdAt, refundedAt, orderId, channel, paidAt, isFinite, confirm, selected

### `pages/report/report.vue`

- UI 文案：故障报修；柜机编号；问题类型；补充说明（选填）；联系电话（选填）；处理说明；例如 CAB-001；描述具体情况，便于快速处理；方便运营回访，默认用登录手机号；提交报修
- 绑定字段：label

### `pages/result/result.vue`

- UI 文案：回首页；查看订单；实付金额；扣款前余额；扣款后余额；商品明细；本次未识别到取走商品；商品合计；会员优惠；优惠券抵扣；订单编号；柜机；扣款时间；退款；返回本柜；账单有问题；申请退款；帮助；退款已完成；暂无结算结果；申诉说明；申诉附图（选填）；取消；账单结果；例如：我没有拿这个商品 / 数量不对…；删除证据图；添加证据图
- 绑定字段：totalAmountCents, balanceBeforeCents, balanceAfterCents, lines, originalAmountCents, memberDiscountCents, couponDiscountCents, orderId, deviceId, deviceName, payTime, refundedAt, status, refundedCents, sessionId, confirm, tone, strong, discount, points, skuName, quantity, label

### `pages/verify/verify.vue`

- UI 文案：开通免密支付；实名；免密支付；实名认证；真实姓名；身份证后四位；已实名；可用余额；冻结中；优先支付；开门预授权；微信支付分；支付宝免密；可以开门购物了；扫柜门二维码即可开门取货；开通支付；真实姓名…；后四位…
- 绑定字段：isFinite

### `pages/video/video.vue`

- UI 文案：视频加载失败；复制链接；缺少视频地址；购物视频；现场录像无对白字幕；购物过程监控录像
- 绑定字段：startsWith

## 6. 推荐落地顺序（下一波实现）

1. ~~订单列表分账状态 `splitStatus`~~（已闭合）
2. ~~争议处理人 assignee~~（已闭合）
3. ~~商户补货 routeName / plannedDate~~（已闭合）
4. ~~会员权益 API 化~~（已闭合：profile.levels 驱动权益 + Admin 折扣）
5. ~~P2：商户端 lat/lng / 固件~~（已闭合：列表导航 + 详情「导航到柜」）
6. ~~P2：设备报表加列~~（Admin/商户柜机报表：商户·线路·地址·停售·温度·固件·客单）
7. ~~P2：券/钱包/争议/附近柜/订单体验项复核~~（多数已在 UI；线长钱包流水对齐商户钱包）
8. ~~消息未读分类 + 深链~~（消费/商户消息分类未读角标；bizId 跳转；临期券提醒带 couponId）
9. ~~可选余量：要货申请拍照（非主路径）~~（提交前可选上传最多 5 张；Admin 审批流可预览）

---

生成方式：`scripts/gen-field-gap-inventory.py`
