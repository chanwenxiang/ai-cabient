# 三端字段差距清单（全页面/表格盘点）

> 自动扫描生成：对比「页面已展示字段」与「shared-types / 常见竞品必填列」。
> 优先级：P0 严重影响对账/履约 · P1 日常运营 · P2 增强体验。

## 0. 总览

- 运营后台 views：65 个
- 商户端 pages：21 个
- 消费端 pages：23 个
- 全文：下文第 2–5 节为**全量页面扫描**（表格列 / UI 文案 / 绑定字段）
- 复现：`python scripts/gen-field-gap-inventory.py`

### 0.1 实测结论（相对「全缺」的修正）

并非所有页都空——例如：

| 端 | 已较完整的例子 | 仍明显偏薄的例子 |
|----|----------------|------------------|
| 运营后台 | 订单列表（渠道/优惠/退款状态等）；设备列表（温度/IMEI/线路/生命周期） | 会话 DTO 本身字段少；部分报表/增长页无表或列少 |
| 商户端 | 订单卡已有渠道与券优惠 | 柜机列表缺温度/线路/停售原因；补货行明细与凭证弱；钱包缺冻结/手续费 |
| 消费端 | 订单详情已有渠道/支付时间/券优惠/退款 | 订单**列表**信息密度低；会员/券门槛与适用柜弱；附近柜缺营业/库存摘要 |

**真正痛点**：同一业务在三端字段不一致 + 商户/C 端列表「卡片信息太少」+ 部分 DTO 未建模（如订单货道、分账状态、退款累计额）。

## 1. 高优先差距（跨端共性，先修）

| 域 | P0 建议补齐 | 说明 |
|----|-------------|------|
| 订单列表/详情 | 支付渠道、支付时间、优惠/会员价、退款额、货道、分账状态、外部支付单号 | 三端对账与售后刚需 |
| 设备列表/详情 | 线路 routeCode、经纬度、温度/温控、固件版本、停售原因、缺货 SKU 数、商户名 | 运营调度与商户端柜机页 |
| 开门会话 | 入口渠道、预授权/免密标记、视频状态、识别耗时、失败原因 | 履约排障 |
| 争议/售后 | 视频入口、申请金额 vs 退款金额、处理人、时限 SLA | 商户端+运营端+C端 |
| 补货任务 | 备货单行明细、实盘/补后数量、拍照凭证、线路、截止时间 | 商户补货体验 |
| 钱包/提现/分账 | 可用/冻结、手续费、到账渠道、失败原因、外部单号 | 财务信任 |
| 会员/券/积分 | 券门槛、有效期、适用柜、积分过期、等级权益明细 | C 端转化 |

## 2. 运营后台（admin-vue）— 全页面表格列

### `analytics/AnalyticsView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `analytics/FootfallView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | deviceName:柜机 |
| 2 | opens:开门 |
| 3 | orders:订单 |
| 4 | 转化率 |
| 5 | 营收 |
| 6 | skuName:商品 |
| 7 | qtySold:销量 |

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

### `consistency/ConsistencyView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | 类型 |
| 2 | 键 |
| 3 | tableName:表 |
| 4 | 期望 |
| 5 | 实际 |
| 6 | 说明 |
| 7 | 状态 |
| 8 | 检出时间 |
| 9 | 操作 |

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

### `devices/DeviceDetailView.vue` （列数 12）

| # | 列 |
|---|----|
| 1 | ticketId:单号 |
| 2 | title:标题 |
| 3 | status:状态 |
| 4 | createdAt:创建 |
| 5 | 指标 |
| 6 | 数值 |
| 7 | 上报时间 |
| 8 | 会话 |
| 9 | 订单 |
| 10 | 时间 |
| 11 | 操作 |
| 12 | 金额 |

### `devices/DeviceKpiView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `devices/DeviceListView.vue` （列数 17）

| # | 列 |
|---|----|
| 1 | deviceId:设备编号 |
| 2 | 设备 |
| 3 | 类型 |
| 4 | 状态 |
| 5 | 运营态 |
| 6 | 生命周期 |
| 7 | 柜内温度 |
| 8 | 地址 |
| 9 | IMEI |
| 10 | 资产方 |
| 11 | 路线 |
| 12 | 商户 |
| 13 | 退款方式 |
| 14 | 最近会话 |
| 15 | 会话状态 |
| 16 | 更新时间 |
| 17 | 操作 |

### `devices/DeviceMapView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `devices/DeviceOpsMonitorView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | eventId:事件ID |
| 2 | eventType:类型 |
| 3 | 级别 |
| 4 | 设备名称 |
| 5 | deviceId:设备编号 |
| 6 | title:标题 |
| 7 | detail:详情 |
| 8 | 时间 |

### `devices/RepairTicketsView.vue` （列数 11）

| # | 列 |
|---|----|
| 1 | ticketId:工单号 |
| 2 | deviceId:设备 |
| 3 | title:标题 |
| 4 | faultType:故障类型 |
| 5 | priority:优先级 |
| 6 | status:状态 |
| 7 | 负责人 |
| 8 | 备注 |
| 9 | 创建时间 |
| 10 | 关闭时间 |
| 11 | 操作 |

### `disputes/DisputeListView.vue` （列数 17）

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
| 11 | 创建时间 |
| 12 | 结案时间 |
| 13 | 操作 |
| 14 | skuName:商品 |
| 15 | skuId:SKU |
| 16 | quantity:数量 |
| 17 | 单价 |

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

### `finance/FinanceView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | skuId:商品编号 |
| 2 | 商品 |
| 3 | qtySold:销量 |
| 4 | 营收 |
| 5 | 成本 |
| 6 | 毛利 |
| 7 | 毛利率 |

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

### `finance/InvoiceListView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | 申请号 |
| 2 | 订单 |
| 3 | title:抬头 |
| 4 | taxNo:税号 |
| 5 | 金额 |
| 6 | 状态 |
| 7 | 申请时间 |
| 8 | 操作 |

### `finance/LineManagerView.vue` （列数 42）

| # | 列 |
|---|----|
| 1 | managerId:经理编号 |
| 2 | 姓名 |
| 3 | phone:手机 |
| 4 | orgName:组织 |
| 5 | userId:绑定用户 |
| 6 | wxOpenid:openid |
| 7 | 余额(元) |
| 8 | 冻结(元) |
| 9 | 绑柜 |
| 10 | commissionRateBps:佣金bps |
| 11 | commissionFixedCents:固定分/单 |
| 12 | status:状态 |
| 13 | 创建时间 |
| 14 | 操作 |
| 15 | requestId:单号 |
| 16 | requestNo:幂等号 |
| 17 | managerName:线长 |
| 18 | 金额(元) |
| 19 | payChannel:通道 |
| 20 | payoutRef:回执 |
| 21 | payoutMessage:打款说明 |
| 22 | reviewRemark:审核备注 |
| 23 | 申请时间 |
| 24 | 打款时间 |
| 25 | taskId:ID |
| 26 | managerId:线长ID |
| 27 | title:任务 |
| 28 | routeCode:线路 |
| 29 | 进度 |
| 30 | 奖金(元) |
| 31 | dueDate:截止 |
| 32 | 类型 |
| 33 | 变动(元) |
| 34 | 余额后 |
| 35 | 冻结后 |
| 36 | refType:关联 |
| 37 | remark:备注 |
| 38 | 时间 |
| 39 | bizDate:日期 |
| 40 | GMV |
| 41 | 佣金 |
| 42 | orderCount:单量 |

### `finance/MerchantWithdrawView.vue` （列数 23）

| # | 列 |
|---|----|
| 1 | merchantId:商户ID |
| 2 | merchantName:名称 |
| 3 | contactPhone:联系电话 |
| 4 | 余额(元) |
| 5 | 冻结(元) |
| 6 | 可用(元) |
| 7 | status:状态 |
| 8 | 操作 |
| 9 | requestId:单号 |
| 10 | requestNo:幂等号 |
| 11 | merchantName:商户 |
| 12 | 金额(元) |
| 13 | payChannel:通道 |
| 14 | payoutRef:回执 |
| 15 | payoutMessage:打款说明 |
| 16 | reviewRemark:审核备注 |
| 17 | 申请时间 |
| 18 | 类型 |
| 19 | 变动(元) |
| 20 | 余额后 |
| 21 | 冻结后 |
| 22 | remark:备注 |
| 23 | 时间 |

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

### `growth/AdCampaignsView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | campaignId:ID |
| 2 | name:名称 |
| 3 | 状态 |
| 4 | 范围 |
| 5 | 素材 |
| 6 | 曝光 |
| 7 | 完播 |
| 8 | 时间窗 |
| 9 | 操作 |

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

### `growth/MemberLevelsView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | levelCode:等级编码 |
| 2 | levelName:等级名称 |
| 3 | 累计消费区间(元) |
| 4 | 累计积分区间 |
| 5 | 积分倍率 |
| 6 | sortOrder:排序 |
| 7 | 状态 |
| 8 | 操作 |

### `growth/NotificationsView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | id:ID |
| 2 | 时间 |
| 3 | 受众 |
| 4 | title:标题 |
| 5 | 内容 |
| 6 | bizType:业务 |
| 7 | 关联单号 |

### `growth/PointsRedeemView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | itemId:ID |
| 2 | 兑换项 |
| 3 | pointsCost:所需积分 |
| 4 | couponName:兑换优惠券 |
| 5 | 库存 / 已兑 |
| 6 | sortOrder:排序 |
| 7 | 状态 |
| 8 | 操作 |

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

### `growth/UserAnalysisView.vue` （列数 6）

| # | 列 |
|---|----|
| 1 | userId:用户ID |
| 2 | 姓名/手机 |
| 3 | orderCount:订单数 |
| 4 | 累计消费 |
| 5 | orderCount:累计订单 |
| 6 | 上次消费 |

### `LoginView.vue` （列数 0）

_无 el-table-column（卡片/图表/表单页）_

### `merchants/MerchantOnboardingView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | merchantId:商户 |
| 2 | channel:渠道 |
| 3 | status:状态 |
| 4 | externalMchId:外部商户号 |
| 5 | 支付模式 |
| 6 | note:备注 |
| 7 | 更新时间 |
| 8 | 操作 |

### `merchants/MerchantSplitsView.vue` （列数 18）

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
| 10 | templateName:岗位 |
| 11 | description:说明 |
| 12 | permissionHint:权限提示 |
| 13 | 分账编号 |
| 14 | 订单 |
| 15 | 商户收入 |
| 16 | 状态 |
| 17 | 失败原因 |
| 18 | 操作 |

### `orders/OrderListView.vue` （列数 22）

| # | 列 |
|---|----|
| 1 | orderId:订单号 |
| 2 | 会话 |
| 3 | 用户 |
| 4 | 设备 |
| 5 | 流水号 |
| 6 | 订单状态 |
| 7 | 支付状态 |
| 8 | 退款状态 |
| 9 | 支付渠道 |
| 10 | 扣库存 |
| 11 | 商品 |
| 12 | 金额 |
| 13 | 优惠 |
| 14 | 账龄 |
| 15 | 创建时间 |
| 16 | 操作 |
| 17 | quantity:数量 |
| 18 | 小计 |
| 19 | skuId:SKU |
| 20 | maxQty:可退 |
| 21 | 退款数量 |
| 22 | 回库 |

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

### `promotions/CouponsView.vue` （列数 9）

| # | 列 |
|---|----|
| 1 | couponDefId:券定义编号 |
| 2 | 优惠券 |
| 3 | 类型 |
| 4 | 面值 |
| 5 | 最低消费 |
| 6 | 有效期 |
| 7 | 发行/总量 |
| 8 | 状态 |
| 9 | 操作 |

### `promotions/PromotionsView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | activityId:活动编号 |
| 2 | 活动 |
| 3 | 类型 |
| 4 | 时间 |
| 5 | 预算 |
| 6 | 已使用 |
| 7 | 状态 |
| 8 | 操作 |

### `recharges/RechargeListView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | orderId:充值单 |
| 2 | 用户 |
| 3 | 金额 |
| 4 | 渠道 |
| 5 | 状态 |
| 6 | 时间 |
| 7 | 操作 |

### `reconciliation/ReconciliationView.vue` （列数 10）

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
| 10 | 匹配 |

### `replenishment/ReplenishmentView.vue` （列数 38）

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
| 18 | requestId:要货单 |
| 19 | merchantName:商户 |
| 20 | 目标设备 |
| 21 | 明细 |
| 22 | 驳回原因 |
| 23 | 补货任务 |
| 24 | 提交时间 |
| 25 | slotCode:货道 |
| 26 | assignedSkuName:商品 |
| 27 | bookQty:账面 |
| 28 | minLevel:最低 |
| 29 | parLevel:目标 |
| 30 | skuId:商品 SKU |
| 31 | batchNo:批次 |
| 32 | lotId:批次 ID |
| 33 | quantity:数量 |
| 34 | 原因 |
| 35 | 创建时间 |
| 36 | 类型 |
| 37 | 效期 |
| 38 | 已入账 |

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

### `reports/SalesReportsView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | dimKey:编码 |
| 2 | dimLabel:名称 |
| 3 | orderCount:订单数 |
| 4 | qty:销量 |
| 5 | 营收 |
| 6 | 成本 |
| 7 | 毛利 |

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

### `risk/RiskView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | 事件 |
| 2 | 用户 |
| 3 | 级别 |
| 4 | 处置 |
| 5 | 时间 |
| 6 | 原因 |
| 7 | 加入时间 |
| 8 | 操作 |

### `sessions/SessionListView.vue` （列数 12）

| # | 列 |
|---|----|
| 1 | sessionId:会话编号 |
| 2 | 类型 |
| 3 | 用户 |
| 4 | 设备 |
| 5 | 订单 |
| 6 | 状态 |
| 7 | 等待原因 |
| 8 | 滞留 / 时限 |
| 9 | 失败原因 |
| 10 | 更新时间 |
| 11 | 时长 |
| 12 | 操作 |

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

### `system/AlertRuleView.vue` （列数 5）

| # | 列 |
|---|----|
| 1 | 分组 |
| 2 | 规则说明 |
| 3 | 配置键 |
| 4 | 当前值 |
| 5 | 操作 |

### `system/AuditLogView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | logId:日志编号 |
| 2 | 时间 |
| 3 | 操作人 |
| 4 | 动作 |
| 5 | 对象类型 |
| 6 | 对象ID |
| 7 | 详情 |

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

### `system/OperatorManageView.vue` （列数 7）

| # | 列 |
|---|----|
| 1 | userId:用户编号 |
| 2 | 账号 |
| 3 | 手机号 |
| 4 | 状态 |
| 5 | 角色 |
| 6 | 数据范围 |
| 7 | 操作 |

### `system/OrgSitesView.vue` （列数 8）

| # | 列 |
|---|----|
| 1 | deviceName:柜机 |
| 2 | siteName:场地 |
| 3 | address:地址 |
| 4 | landlordName:场地主 |
| 5 | 月费 |
| 6 | 到期 |
| 7 | 状态 |
| 8 | 操作 |

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
| 2 | 余额 |
| 3 | 用户 |
| 4 | 手机号 |
| 5 | 角色 |
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

### `warehouse/WarehouseView.vue` （列数 81）

| # | 列 |
|---|----|
| 1 | warehouseId:仓库编号 |
| 2 | 仓库 |
| 3 | address:地址 |
| 4 | 状态 |
| 5 | 操作 |
| 6 | transferNo:调拨单号 |
| 7 | fromWarehouseId:调出仓 |
| 8 | toWarehouseId:调入仓 |
| 9 | 明细 |
| 10 | supplierId:供应商编号 |
| 11 | 供应商 |
| 12 | contactName:联系人 |
| 13 | contactPhone:联系电话 |
| 14 | paymentTermsDays:账期(天) |
| 15 | 商品 |
| 16 | batchNo:批次 |
| 17 | orderedQty:采购数 |
| 18 | receivedQty:已收数 |
| 19 | returnedQty:已退数 |
| 20 | 成本 |
| 21 | expiryDate:到期日期 |
| 22 | purchaseOrderId:采购单 |
| 23 | refNo:外部单号 |
| 24 | 入库仓库 |
| 25 | soldQty7d:近7日销量 |
| 26 | soldQty14d:近14日销量 |
| 27 | 日均销量 |
| 28 | 预测日均 |
| 29 | 日均趋势 |
| 30 | onHandQty:仓库库存 |
| 31 | pendingPoQty:待收采购 |
| 32 | coverageDays:覆盖天数 |
| 33 | 建议采购量 |
| 34 | 安全库存 |
| 35 | 建议理由 |
| 36 | quantity:退货数 |
| 37 | returnId:退货单 |
| 38 | 创建时间 |
| 39 | 付款时间 |
| 40 | 付款金额 |
| 41 | notes:备注 |
| 42 | 关联采购单 |
| 43 | 应付金额 |
| 44 | 已付 |
| 45 | 未付余额 |
| 46 | 到期日 |
| 47 | 逾期 |
| 48 | 盘点单号 |
| 49 | 模式 |
| 50 | bookQty:账面件数 |
| 51 | countedQty:实盘件数 |
| 52 | 差异件数 |
| 53 | diffLineCount:差异行数 |
| 54 | 货位编码 |
| 55 | binName:货位名称 |
| 56 | 货位 |
| 57 | productionDate:生产日期 |
| 58 | quantity:数量 |
| 59 | 目标设备 |
| 60 | 货道 |
| 61 | 交接状态 |
| 62 | 出库单 |
| 63 | routeId:路线 |
| 64 | 出库仓库 |
| 65 | 在途 / 时限 |
| 66 | 发运时间 |
| 67 | quantity:库存 |
| 68 | 效期 |
| 69 | movementId:流水 |
| 70 | 类型 |
| 71 | deltaQty:变动 |
| 72 | 关联业务 |
| 73 | 关联单号 |
| 74 | 时间 |
| 75 | bookQty:账面 |
| 76 | 实盘 |
| 77 | 差异 |
| 78 | 累计收货 |
| 79 | receivedQty:已收 |
| 80 | returnedQty:已退 |
| 81 | 本次退货 |

### 后台疑似偏薄页（需优先对照 DTO）

- `LoginView.vue`
- `analytics/AnalyticsView.vue`
- `dashboard/BigScreenView.vue`
- `devices/DeviceKpiView.vue`
- `devices/DeviceMapView.vue`
- `print/PrintView.vue`
- `profile/ProfileView.vue`
- `sla/SlaView.vue`
- `vision/RecognitionDemoView.vue`

## 3. shared-types 关键 DTO 字段（后端已有、前端常漏展）

### 订单 — `OrderDetailDto`

DTO 字段（16）：`orderId`, `sessionId`, `deviceId`, `status`, `payChannel`, `payTime`, `videoUri`, `paymentOperationId`, `balanceBeforeCents`, `balanceAfterCents`, `totalAmountCents`, `couponDiscountCents`, `originalAmountCents`, `lines`, `createdAt`, `refundPolicy`

竞品/体验期望列：订单号、状态、金额、设备、支付渠道、下单时间、退款、优惠券、会员价、货道、支付时间、分账

### 设备 — `DeviceInfo`

DTO 字段（27）：`deviceId`, `deviceName`, `deviceType`, `merchantId`, `merchantName`, `onlineStatus`, `activeSessionId`, `activeSessionState`, `updatedAt`, `refundPolicy`, `effectiveRefundPolicy`, `salesLocked`, `replenishmentInProgress`, `lifecycleStatus`, `imei`, `assetOwner`, `coopMode`, `depositCents`, `dataFeeCents`, `opsTags`, `routeCode`, `latitude`, `longitude`, `address`, `deployedAt`, `lifecycleRemark`, `currentTempC`

竞品/体验期望列：设备号、名称、在线、商户、地址、停售、温度、线路、经纬度、固件、库存健康

### 会话 — `SessionDto`

DTO 字段（8）：`sessionId`, `deviceId`, `state`, `orderId`, `failureReason`, `createdAt`, `updatedAt`, `closeTime`

竞品/体验期望列：会话号、用户、设备、状态、开门时间、渠道、预授权、视频

### 争议 — `DisputeTicketDto`

DTO 字段（16）：`ticketId`, `sessionId`, `deviceId`, `reason`, `status`, `createdAt`, `resolvedAt`, `orderId`, `billedAmountCents`, `suggestedItems`, `resolutionItems`, `category`, `priority`, `evidence`, `reviewCode`, `detectedClasses`

竞品/体验期望列：工单号、订单、状态、原因、金额、视频、处理结果、创建时间

### 附近柜机 — `NearbyDeviceDto`

DTO 字段（15）：`deviceId`, `deviceName`, `address`, `latitude`, `longitude`, `distanceMeters`, `onlineStatus`, `available`, `sellableSkuCount`, `sellableItemCount`, `previewSkus`, `skuId`, `skuName`, `quantity`, `unitPriceCents`

竞品/体验期望列：名称、距离、地址、在线、营业

## 4. 商户端（merchant-mp）— 全页面

### `pages/alerts/alerts.vue`

- UI 文案：审核；故障；库存；临期；重试；查看柜机；货道差异（账实不符）；待办；暂无待办事项
- 绑定字段：type, ticketId, deviceId, exceptionId, dispute, offline, stock, expiry, disputes, lowStock, typeLabel, title, detail, deviceName, slotCode, assignedSkuName, bookQty, physicalQty, qtyDiff

### `pages/announcements/announcements.vue`

- UI 文案：重试；新；通知公告；暂无平台公告；未读
- 绑定字段：announceId, priority, publishAt, title, content

### `pages/announcements/detail.vue`

- UI 文案：重试；公告详情
- 绑定字段：priority, publishAt, title, content

### `pages/business/business.vue`

- UI 文案：重试；经营毛利；本月已结算；待结算；重点商品；分账异常；商品经营表现；按销售额排序；暂无可分析的销售数据；销售四表；该区间暂无销售明细；临期摘要；待下架任务；报损件数；报损成本；开票税号资料；月结对账开票用；暂无绑定商户；保存税号资料；柜机报表；导出柜机报表；经营分析；公司名称；纳税人识别号；地址（选填）；电话（选填）
- 绑定字段：label, dimKey, dimLabel, orderCount, qty, marginCents, revenueCents, companyName, taxNo, address, phone, deviceId, deviceName, onlineStatus, orderToday, revenueTodayCents, orderTotal, revenueTotalCents, sessionTotal, sessionActive, getMonth, getDate, getHours, getMinutes, setDate, getFullYear, xlsx, active, topSkus, failedSplitCount, skuName, qtySold, insight, recommendation, openPullOffTasks, writeOffQty30d
- **建议补齐**：毛利/客单/缺货损失/同比

### `pages/device-detail/device-detail.vue`

- UI 文案：当前账号无柜机详情权限；补货任务；发起要货；柜机设置；显示名称；备注；货道；只读（平台未开启或未授权）；柜机详情；请输入柜机显示名称；例如 5；选填运维备注；目标库存
- 绑定字段：reportedAt, tempC, isFinite, getHours, getMinutes, isInteger, on, slotCode, assignedSkuName, bookQty, maxLevel, skuName, skuId, soldQty7d, soldQty14d, ropPoint
- **建议补齐**：温度/线路/停售原因/缺货数/地址完整度

### `pages/devices/devices.vue`

- UI 文案：扫码到柜；清除；重试；停售；柜机；搜索柜机名称或编号；搜索柜机名称或编号…
- 绑定字段：deviceId, online, deviceName, address, lifecycleStatus, currentTempC, salesLocked, onlineStatus, on, label
- **建议补齐**：温度/线路/停售原因/缺货数/地址完整度

### `pages/disputes/disputes.vue`

- UI 文案：重试；单号；状态；柜机；订单；金额；最新；购物录像；回复；同意免单；维持原单；按识别结案；关闭；争议处理；加载更多争议
- 绑定字段：ticketId, status, reason, deviceId, createdAt, slaOverdue, slaHoursRemaining, lastMessage, substring, confirm, label, orderId
- **建议补齐**：视频/退款金额拆分/处理时限

### `pages/home/home.vue`

- UI 文案：重试；待补货；待办；离线柜；扫码到柜；公告；补货任务；柜机列表；待办事项；今日补货；查看记录；常驻；优先待办；经营工具；要货申请；点位定价；结算对账；争议处理；经营分析；今日营收；商户收入；在线柜机；暂无待处理补货任务
- 绑定字段：taskId, deviceId, status, type, title, detail, permissions, displayName, phoneNumber, merchants, last7Days, revenueCents, date, deviceName, slice, primary, label

### `pages/line-wallet/line-wallet.vue`

- UI 文案：重试；可用余额；最近提现；最近流水；线长钱包；未绑定线长身份；提现金额（元）；暂无提现记录；暂无流水记录
- 绑定字段：_少_
- **建议补齐**：冻结余额/手续费/外部单号/失败原因

### `pages/login/login.vue`

- UI 文案：补货与运营；登录；补货员与商户运营共用入口；手机号；密码；记住账号和密码；请输入11位手机号…；请输入登录密码…
- 绑定字段：startsWith, slice

### `pages/messages/messages.vue`

- UI 文案：暂无消息；消息中心
- 绑定字段：id, read, title, createdAt, body, bizId, bizType, unread

### `pages/mine/mine.vue`

- UI 文案：编辑资料；取消；保存；现场作业；团队与设置；平台公告；通知公告；消息提醒；微信订阅提醒；保存提醒偏好；经营工具；退出登录；联系电话；告警联系人；告警电话
- 绑定字段：contactPhone, alertContactName, alertContactPhone, key, icon, title, desc, tab, url, label

### `pages/order-detail/order-detail.vue`

- UI 文案：重试；商品明细；无商品明细；优惠；实付；订单信息；订单号；会话；柜机；支付方式；创建时间；查看柜机；相关争议；订单详情
- 绑定字段：status, totalAmountCents, lines, couponDiscountCents, orderId, sessionId, deviceId, createdAt, s, strong, skuName, quantity
- **建议补齐**：支付渠道/退款额/优惠明细/货道

### `pages/orders/orders.vue`

- UI 文案：重试；重置；柜机订单；订单号 / 柜机 / 会话；搜索订单；加载更多订单
- 绑定字段：orderId, status, totalAmountCents, lineSummary, deviceId, lineCount, payChannel, couponDiscountCents, createdAt, getFullYear, getMonth, getDate, label, setDate, deviceName
- **建议补齐**：支付渠道/退款额/优惠明细/货道

### `pages/pricing/pricing.vue`

- UI 文案：当前账号无定价查看权限；调价历史；重试；暂无调价记录；点位定价；覆盖价(元)；暂无定价数据
- 绑定字段：deviceId, deviceName, isNaN, getMonth, getDate, getHours, getMinutes, skuName, minPriceCents, maxPriceCents, skuId, detail

### `pages/replenishment/replenishment.vue`

- UI 文案：现场补货；补货任务；待处理；已完成；今日完成率；扫码找柜；要货；常驻柜；清除筛选；今日暂无待补货；缺货巡柜；扫码到柜；签到；开门；核对；现场照片；签到后可拍照；选择货道；关闭；添加现场照片；减少数量；增加数量
- 绑定字段：deviceId, skuCount, shortageQty, label, taskId, status, createdAt, notes, fileId, localPath, outboundId, checkInAt, isFinite, lowThreshold, quantity, skuId, jpg, message, room, four, batchNo, slotId, slotCode, expiryDate, title, content, cancelText, confirmText
- **建议补齐**：行级数量/批次效期/拍照状态

### `pages/request/request.vue`

- UI 文案：发起要货；我的申请；目标柜机；当前为常驻柜；要货明细；刷新建议；备注（可选）；当前账号无要货权限；暂无要货申请；要货申请；如：周末客流大，优先补可乐
- 绑定字段：deviceName, deviceId, requestId, active, clickable, selected, skuName, skuId, currentQty, capacity, suggestQty, soldQty7d, suggestReason, qty, label, requestedQty, rejectReason, notes
- **建议补齐**：行级数量/批次效期/拍照状态

### `pages/settlements/settlements.vue`

- UI 文案：至；重试；区间营收；平台抽成；商户所得；待分账；本月已结算；按日汇总；结算批次；导出对账单；结算对账；所选日期暂无结算数据；暂无结算批次
- 绑定字段：date, orderCount, grossCents, platformCents, pendingCents, settledCents, merchantCents, getFullYear, getMonth, getDate, total, gross, platformFee, merchantIncome, pending, settledMonth, batchNo
- **建议补齐**：冻结余额/手续费/外部单号/失败原因

### `pages/splits/splits.vue`

- UI 文案：失败；全部；重试；分账明细
- 绑定字段：failureReason
- **建议补齐**：冻结余额/手续费/外部单号/失败原因

### `pages/team/team.vue`

- UI 文案：重试；邀请成员；已停用；我；管理；取消；确认邀请；角色；保存角色；重置密码；确认重置；重新启用；关闭；团队成员；暂无团队成员；手机号；初始密码（至少 6 位）；显示名（选填）；新密码（至少 6 位）
- 绑定字段：png, phoneNumber, password, displayName, roleKey, roleName, self, status, wrap

### `pages/wallet/wallet.vue`

- UI 文案：重试；可用余额；最近提现；最近流水；商户钱包；暂无商户钱包；提现金额（元）；暂无提现记录；暂无流水记录
- 绑定字段：_少_
- **建议补齐**：冻结余额/手续费/外部单号/失败原因

## 5. 消费端（consumer-mp）— 全页面

### `pages/announcements/announcements.vue`

- UI 文案：重试；新；通知公告；暂无通知公告；未读
- 绑定字段：announceId, priority, publishAt, title, content

### `pages/announcements/detail.vue`

- UI 文案：重试；公告详情
- 绑定字段：priority, publishAt, title, content

### `pages/coupons/coupons.vue`

- UI 文案：重试；去扫码购物；看热门活动；已使用；已过期；我的优惠券；优惠券加载失败
- 绑定字段：isFinite, expired, used, label, couponName
- **建议补齐**：有效期；门槛；适用柜范围；过期提醒

### `pages/dispute/detail.vue`

- UI 文案：重试；审核说明；柜机；购物单号；提交时间；处理时间；申诉附图；识别参考明细；审核结果；本次未计费商品；最终扣款；账单审核；未找到审核单
- 绑定字段：sessionId, ticketId, tone, icon, title, detail, skuName, quantity
- **建议补齐**：审核进度时间线；退款到账渠道

### `pages/feedback/feedback.vue`

- UI 文案：提交反馈；我的反馈；意见反馈；反馈类型；内容；联系方式（选填）；柜机编号（选填）；重试；运营回复；请描述你的问题或建议；手机号或微信，方便回访；例如 CAB-001；暂无反馈记录
- 绑定字段：label, feedbackId, feedbackType, status, content, createdAt, reply, handledAt

### `pages/help/help.vue`

- UI 文案：帮助中心；联系客服；客服热线；拨打；平台公告；去查看；在线留言；去反馈；柜机故障；去报修；常见问题；查看我的订单
- 绑定字段：q, a, service_phone

### `pages/index/index.vue`

- UI 文案：关门自动结算；去充值；重试开门；重新扫码；换一台；继续在本柜购物；附近找柜；需要授权；扫码开门需先完成微信授权；取消；去登录；柜机编号；报修；查看审核详情；稍后查看订单；联系运营；知道了；全部；本柜暂无上架商品；故障报修；未找到匹配商品；换个关键词或分类试试；查看全部商品；关闭错误提示；例如 CAB-001…；搜索本柜商品
- 绑定字段：vue, jpg, isFinite, service_phone, sessionId, alipayOnly, deviceId, channel, get, qty, skuId, orderId, tl, tr, bl, br, wait, tone, pulse, kind, icon, title, detail, skuName

### `pages/login/login.vue`

- UI 文案：关门自动结算；登录后继续；验证码；密码；手机号；返回；请输入11位手机号；请输入登录密码；请输入验证码
- 绑定字段：jpg, startsWith, replace, message, code, on

### `pages/marketing/index.vue`

- UI 文案：我的优惠券；进行中；去扫码购物；去领券；热门活动；暂无进行中活动
- 绑定字段：tone, title, subtitle, typeLabel, description

### `pages/member/index.vue`

- UI 文案：会员俱乐部；累计消费；已达最高等级；可用积分；积分兑换；积分换券；消息中心；订单·售后；我的券；热门活动；本周上新；我的订单；消费记录；去购物；扫码开门；会员权益；等级说明；当前；会员中心
- 绑定字段：isFinite, lv, on, nextLevelName, title, desc, levelName, maxSpent
- **建议补齐**：有效期；门槛；适用柜范围；过期提醒

### `pages/messages/messages.vue`

- UI 文案：全部已读；开启微信消息提醒；暂无消息；通知偏好；关闭后对应类别的消息不再推送与提醒；消息中心
- 绑定字段：id, read, title, createdAt, body, bizId, status, bizType, unread, label
- **建议补齐**：未读类型拆分；跳转深链完整参数

### `pages/mine/mine.vue`

- UI 文案：登录后可查看订单与余额；可用余额；充值；微信授权登录；扫码开门前需完成授权；完成开门准备；优先支付方式；余额；微信免密；支付宝免密；订单；优惠券；会员；开门购物；热门活动；积分中心；消息中心；余额明细；暂无余额流水；通知公告；帮助与客服；故障报修；意见反馈；用户协议；服务条款与使用规则；隐私政策；退款规则；自助退款与人工申诉；账单说明；订单构成与余额明细；开发联调；模拟充值；手机号验证（兜底）；退出登录
- 绑定字段：transactionId, businessType, createdAt, amountCents, isFinite, message

### `pages/nearby/nearby.vue`

- UI 文案：附近柜机；刷新；重试；导航；返回
- 绑定字段：deviceId, deviceName, distanceMeters, address, available, sellableSkuCount, sellableItemCount, previewSkus, latitude, longitude, skuName, quantity
- **建议补齐**：营业状态；库存摘要；导航距离单位

### `pages/order-detail/order-detail.vue`

- UI 文案：重试；商品清单；本次未识别到取走商品；商品合计；优惠券抵扣；实付；支付信息；支付方式；扣款时间；订单编号；柜机编号；查看购物视频；帮助与客服；申请开票；提交后运营开具电子发票（演示环境为申请留痕）；发票抬头；税号（企业选填）；接收邮箱（选填）；取消；申诉说明；按行退款（不选则全额退）；订单详情；个人姓名或公司全称；纳税人识别号；发票发送邮箱；例如：我没有拿这个商品 / 数量不对…；删除证据图；添加证据图
- 绑定字段：skuId, skuName, quantity, lineAmountCents, couponDiscountCents, maxQty, qty, orderId, id, service_phone, confirm, status, pay, label
- **建议补齐**：支付渠道；优惠/会员价；退款进度；货道；开票入口

### `pages/orders/orders.vue`

- UI 文案：重试；去扫码购物；去登录；先去扫码购物；需要关注；审核中；上拉加载更多；没有更多了；故障报修；帮助与客服；我的订单；加载失败；登录后查看订单；暂无订单；当前筛选暂无订单
- 绑定字段：ticketId, createdAt, orderId, deviceId, status, totalAmountCents, couponDiscountCents, payChannel, sessionId, isNaN, lineSummary, lineCount, tone, label
- **建议补齐**：支付渠道；优惠/会员价；退款进度；货道；开票入口

### `pages/points/points.vue`

- UI 文案：可用积分；当前等级；积分倍率；升级还差；积分明细；暂无积分记录
- 绑定字段：nextLevelPointsGap, description, points
- **建议补齐**：有效期；门槛；适用柜范围；过期提醒

### `pages/points/redeem.vue`

- UI 文案：我的积分；暂无兑换商品；运营上架积分兑换后即可兑换优惠券；积分兑换
- 绑定字段：itemId, coverEmoji, title, subtitle, availableStock, pointsCost
- **建议补齐**：有效期；门槛；适用柜范围；过期提醒

### `pages/policy/detail.vue`

- UI 文案：_少_
- 绑定字段：title

### `pages/recharge/recharge.vue`

- UI 文案：当前余额；申请退余额；自定义金额（元）；返回我的；充值记录；取消；余额充值；退款金额（元）；如 33.5；暂无充值记录
- 绑定字段：requestId, amountCents, status, createdAt, orderId, channel, isFinite, confirm, selected

### `pages/report/report.vue`

- UI 文案：故障报修；柜机编号；问题类型；补充说明（选填）；例如 CAB-001；描述具体情况，便于快速处理；提交报修
- 绑定字段：label

### `pages/result/result.vue`

- UI 文案：回首页；查看订单；实付金额；扣款前余额；扣款后余额；商品明细；本次未识别到取走商品；商品合计；优惠券抵扣；已自动选用最优优惠券；返回本柜；账单有问题；申请退款；帮助；退款已完成；暂无结算结果；申诉说明；申诉附图（选填）；取消；账单结果；例如：我没有拿这个商品 / 数量不对…；删除证据图；添加证据图
- 绑定字段：totalAmountCents, balanceBeforeCents, balanceAfterCents, lines, originalAmountCents, couponDiscountCents, sessionId, orderId, confirm, tone, strong, discount, points, skuName, quantity, label

### `pages/verify/verify.vue`

- UI 文案：开通免密支付；实名；免密支付；实名认证；真实姓名；身份证后四位；可用余额；冻结中；微信支付分；支付宝免密；可以开门购物了；扫柜门二维码即可开门取货；开通支付；真实姓名…；后四位…
- 绑定字段：isFinite

### `pages/video/video.vue`

- UI 文案：视频加载失败；复制链接；缺少视频地址；购物视频
- 绑定字段：startsWith

## 6. 推荐落地顺序（下一波实现）

1. **P0 订单三端对齐**：列表+详情统一列集（金额拆分、支付、退款、优惠）
2. **P0 设备三端对齐**：列表补 routeCode/在线/停售/地址；详情补温度与货道库存摘要
3. **P1 争议三端**：视频+金额+状态时间线
4. **P1 商户补货/要货**：行明细与凭证
5. **P1 财务钱包**：冻结/手续费/外部单号
6. **P2 C 端会员券积分**：门槛与有效期显性化
7. **P2 后台其余报表页**：按本清单「列数偏少」页逐项加列

---

生成方式：`scripts/gen-field-gap-inventory.py`
