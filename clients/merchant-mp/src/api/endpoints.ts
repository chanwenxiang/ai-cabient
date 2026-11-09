/**
 * 商户端小程序高频端点集中定义（debt-tracker M3）。
 * 新调用优先从此处取路径；pages/composables 禁止再散落试点字面量。
 * 与后端 `ApiVersions.PATH_PREFIX` 对齐（当前 v2）。
 *
 * ## 前缀约定
 * | 前缀 | 用途 |
 * |------|------|
 * | `/api/v2/auth/*` | 商户登录（见 `AuthEndpoints`） |
 * | `/api/v2/merchant/orders*` | 订单 / 购物视频 |
 * | `/api/v2/merchant/wallet*`、`line-wallet*` | 钱包提现 |
 * | `/api/v2/merchant/disputes*` | 争议结案 |
 * | `/api/v2/merchant/replenishment/*` | 补货 |
 * | `/api/v2/dicts/*`、`/public/merchant-config` | 字典 / 公开配置 |
 *
 * 门禁：`scripts/check-merchant-endpoints.mjs` 扫 pages/composables + `MERCHANT_ENDPOINT_PILOT_LITERALS`。
 * `utils/merchant-api.ts`：M3b–M3d；M3e 扫完 devices/pricing/team/notify/exceptions/通知；merchant-api 字面量应收口。
 */
// 铁律 40 家族：mp 运行时无 URLSearchParams（修复 2026-10-16 商户本月账单页真机 ReferenceError）。
import { withQuery } from '@aicabinet/shared-uni/query';
export const API_PREFIX = '/api/v2' as const;

/** 鉴权相关（与 MerchantEndpoints 并列）。 */
export const AuthEndpoints = {
  merchantPasswordLogin: `${API_PREFIX}/auth/merchant-password-login`,
  /** L2-2：图形验证码（连续登录失败后强制校验） */
  captcha: `${API_PREFIX}/auth/captcha`
} as const;

export const MerchantEndpoints = {
  /** CB-030：订单购物视频清单（含将邑台账预签名分片） */
  orderVideos: (orderId: string) =>
    `${API_PREFIX}/merchant/orders/${encodeURIComponent(orderId)}/videos`,
  /** 运行时字典 */
  dictsRuntime: `${API_PREFIX}/dicts/runtime`,
  publicMerchantConfig: `${API_PREFIX}/public/merchant-config`,
  /** 商户 me / 工作台 */
  me: `${API_PREFIX}/merchant/me`,
  stats: `${API_PREFIX}/merchant/stats`,
  devices: `${API_PREFIX}/merchant/devices`,
  /** 订单（withQuery 拼分页/筛选） */
  orders: `${API_PREFIX}/merchant/orders`,
  orderDetail: (orderId: string) => `${API_PREFIX}/merchant/orders/${encodeURIComponent(orderId)}`,
  /** 争议 */
  disputes: `${API_PREFIX}/merchant/disputes`,
  disputeDetail: (ticketId: string) =>
    `${API_PREFIX}/merchant/disputes/${encodeURIComponent(ticketId)}`,
  disputeReply: (ticketId: string) =>
    `${API_PREFIX}/merchant/disputes/${encodeURIComponent(ticketId)}/reply`,
  disputeResolve: (ticketId: string) =>
    `${API_PREFIX}/merchant/disputes/${encodeURIComponent(ticketId)}/resolve`,
  disputeClaim: (ticketId: string) =>
    `${API_PREFIX}/merchant/disputes/${encodeURIComponent(ticketId)}/claim`,
  /** 钱包 / 线路钱包 */
  lineWallet: `${API_PREFIX}/merchant/line-wallet`,
  lineWalletWithdraw: `${API_PREFIX}/merchant/line-wallet/withdraw`,
  wallet: `${API_PREFIX}/merchant/wallet`,
  walletWithdraw: `${API_PREFIX}/merchant/wallet/withdraw`,
  /** 结算概览 / 日结 / 批次（导出另见 settlementsExport） */
  settlementsOverview: `${API_PREFIX}/merchant/settlements/overview`,
  settlementsDaily: (from: string, to: string) =>
    `${API_PREFIX}/merchant/settlements/daily?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  settlementsBatches: (from: string, to: string) =>
    `${API_PREFIX}/merchant/settlements/batches?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  /** 商户月度结算单（V330，CB-020）：from/to 为 yyyy-MM 可省略，后端默认近 12 个月 */
  settlementsMonthlyBills: `${API_PREFIX}/merchant/settlements/monthly-bills`,
  /** 商户账单（图4 对照，2026-10-15）：销售报表口径，月×设备（deviceId 空=全部设备）+ 按日明细 */
  settlementsMonthlyBill: (month: string, deviceId?: string) =>
    withQuery(`${API_PREFIX}/merchant/settlements/monthly-bill`, { month, deviceId }),
  /** 经营分析 */
  analyticsOverview: `${API_PREFIX}/merchant/analytics/overview`,
  analyticsSalesReports: `${API_PREFIX}/merchant/analytics/sales-reports`,
  analyticsSalesReportsExport: `${API_PREFIX}/merchant/analytics/sales-reports/export`,
  analyticsSkuSales: `${API_PREFIX}/merchant/analytics/sku-sales`,
  analyticsVelocity: (deviceId: string) =>
    `${API_PREFIX}/merchant/analytics/velocity?deviceId=${encodeURIComponent(deviceId)}`,
  analyticsAiInsight: `${API_PREFIX}/merchant/analytics/ai-insight`,
  analyticsExpirySummary: `${API_PREFIX}/merchant/analytics/expiry-summary`,
  /** 税档 / 库存 */
  taxProfile: `${API_PREFIX}/merchant/tax-profile`,
  taxProfileByMerchant: (merchantId: string) =>
    `${API_PREFIX}/merchant/tax-profile?merchantId=${encodeURIComponent(merchantId)}`,
  /** 补货 JSON */
  replenishmentSuggestions: (deviceId: string) =>
    `${API_PREFIX}/merchant/replenishment/suggestions?deviceId=${encodeURIComponent(deviceId)}`,
  replenishmentEfficiency: `${API_PREFIX}/merchant/replenishment/my-efficiency`,
  /** 缺货巡柜聚合（货道级统一口径，缺口=建议 headroom） */
  replenishmentShortageSummary: `${API_PREFIX}/merchant/replenishment/shortage-summary`,
  /** 自主补货：直接生成补货任务并领货上柜（无审批） */
  replenishmentSelfInitiate: `${API_PREFIX}/merchant/replenishment/self-initiate`,
  replenishmentTasks: `${API_PREFIX}/merchant/replenishment/tasks`,
  /** 首页备货清单（CB-072）：进行中任务出库行按 SKU 聚合 + 件装数换算 + 仓库库存 */
  replenishmentPrepList: `${API_PREFIX}/merchant/replenishment/prep-list`,
  /** 手动生成备货单预览（A 方案）：按柜分组、按「补满」口径（上限−实际−已领未上柜） */
  replenishmentPrepPreview: `${API_PREFIX}/merchant/replenishment/prep-preview`,
  replenishmentTasksByStatus: (status: string) =>
    `${API_PREFIX}/merchant/replenishment/tasks?status=${encodeURIComponent(status)}`,
  replenishmentDeviceAccess: (deviceId: string) =>
    `${API_PREFIX}/merchant/replenishment/devices/${encodeURIComponent(deviceId)}/access`,
  replenishmentDoorSession: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/door-session`,
  replenishmentTaskLines: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/lines`,
  /**
   * D28-A5（CB-075）：开门柜「可补量」下发。后端 `assertDoorCabinetHeadroom` 提交时用的
   * 就是同一个数（上限 − 在架 − 他单已领未上柜），此前小程序自己只算「上限 − 在架」，
   * 少扣一层 ⇒ 加得进去、点提交才 400。货道柜返回空数组（上限来自货道，不走台账）。
   */
  replenishmentTaskHeadroom: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/headroom`,
  replenishmentTaskCheckIn: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/check-in`,
  replenishmentTaskOpenDoor: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/open-door`,
  replenishmentTaskComplete: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/complete`,
  /**
   * CB-038 任务级取消：只吃「无已上架明细」的任务，已领未上柜的货由后端回仓收口。
   * 已有上架明细时后端回 409（`任务已有上架记录，不能按空任务取消`），判官在服务端。
   */
  replenishmentTaskCancelEmpty: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/cancel-empty`,
  /**
   * CB-078：补货作业录像（与订单购物视频同形：短时效预签名分片；失败片如实回 playable=false）。
   * 数据源是将邑按 `historyNo`（= 补货会话号）上报的录像台账，与消费者订单视频同一套读口径。
   */
  replenishmentTaskVideos: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/videos`,
  /** 补货证据上传/下载（非 JSON，仍走 Endpoints 防裸拼） */
  replenishmentTaskEvidence: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/evidence`,
  replenishmentTaskEvidenceFile: (taskId: number | string, fileId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/evidence/${encodeURIComponent(String(fileId))}`,
  /** 仓库盘点（V349，服务端锁定本人负责的分仓） */
  warehouseStocktakes: (query?: URLSearchParams | string) =>
    `${API_PREFIX}/merchant/warehouse/stocktakes${query ? `?${String(query)}` : ''}`,
  warehouseStocktakeCreate: `${API_PREFIX}/merchant/warehouse/stocktakes`,
  warehouseStocktake: (stocktakeId: number | string) =>
    `${API_PREFIX}/merchant/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}`,
  warehouseStocktakeLine: (stocktakeId: number | string, lineId: number | string) =>
    `${API_PREFIX}/merchant/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/lines/${encodeURIComponent(String(lineId))}`,
  warehouseStocktakeComplete: (stocktakeId: number | string) =>
    `${API_PREFIX}/merchant/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/complete`,
  warehouseStocktakeCompleteAndAdjust: (stocktakeId: number | string) =>
    `${API_PREFIX}/merchant/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/complete-and-adjust`,
  warehouseStocktakeCancel: (stocktakeId: number | string) =>
    `${API_PREFIX}/merchant/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/cancel`,
  warehouseStocktakeEvidence: (stocktakeId: number | string) =>
    `${API_PREFIX}/merchant/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/evidence`,
  warehouseStocktakeEvidenceFile: (stocktakeId: number | string, fileId: number | string) =>
    `${API_PREFIX}/merchant/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/evidence/${encodeURIComponent(String(fileId))}`,
  /** 导出（绝对 URL 由 merchant-api 拼 base） */
  settlementsExport: (from: string, to: string) =>
    `${API_PREFIX}/merchant/settlements/export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  ordersExport: (deviceId?: string) => {
    const q = deviceId ? `?deviceId=${encodeURIComponent(deviceId)}` : '';
    return `${API_PREFIX}/merchant/orders/export${q}`;
  },
  deviceReportsExport: `${API_PREFIX}/merchant/device-reports/export`,
  /** 趋势 / 设备设置货道 / 定价 */
  trend: (days: number) => `${API_PREFIX}/merchant/trend?days=${days}`,
  deviceSettings: (id: string) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/settings`,
  deviceDetail: (id: string) => `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}`,
  /** 设备侧故障单（CB-077）：固件自报（如摄像头打开失败），reported_at 降序 */
  deviceFaultOrders: (id: string, limit = 20) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/fault-orders?limit=${limit}`,
  /** 第七章手机采集：策略 / 按条码状态 / 拍照上传 / 提交 */
  jiangyiCapturePolicy: `${API_PREFIX}/merchant/skus/jiangyi-capture/policy`,
  jiangyiCaptureState: (barCode: string) =>
    `${API_PREFIX}/merchant/skus/jiangyi-capture/${encodeURIComponent(barCode)}/state`,
  jiangyiCapturePhoto: (barCode: string) =>
    `${API_PREFIX}/merchant/skus/jiangyi-capture/${encodeURIComponent(barCode)}/photo`,
  jiangyiCaptureSubmit: (barCode: string) =>
    `${API_PREFIX}/merchant/skus/jiangyi-capture/${encodeURIComponent(barCode)}/submit`,
  /** 将邑绑定判定（开门柜判据：盘点/补货/柜机详情共用）；旧采集模式进出/学习进度端点已退场 */
  jiangyiGatherStatus: (id: string) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/jiangyi/gather/status`,
  /** CB-050 扩展：商户自助改密（须已登录，携带当前密码） */
  merchantPasswordChange: `${API_PREFIX}/merchant/password-change`,
  /** 盘点开门（2026-10-28）：先扫码选中柜机再发起；仅开门柜；关门即完成不结算 */
  deviceStocktakeDoor: (id: string) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/stocktake-door`,
  /** 开门柜盘点提交：逐商品实盘覆盖式调账 */
  deviceDoorStocktake: (id: string) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/door-stocktake`,
  /** CB-050 扩展：柜机盘点凭证上传/回看 */
  deviceStocktakeEvidence: (id: string) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/stocktake-evidence`,
  deviceStocktakeEvidenceFile: (id: string, fileId: number) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/stocktake-evidence/${encodeURIComponent(String(fileId))}`,
  /** CB-050 扩展：理货记录（配货=补货行聚合 / 盘点=AISLE_AUDIT 事件） */
  deviceTallyRecords: (id: string, page: number, size: number, days: number) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/tally-records?page=${page}&size=${size}&days=${days}`,
  deviceStocktakeEvents: (id: string, page: number, size: number) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/stocktake-events?page=${page}&size=${size}`,
  /** CB-050 扩展：可切换组织列表（绑定商户 + 下级树） */
  orgs: `${API_PREFIX}/merchant/orgs`,
  /** CB-050：柜机详情 KPI 汇总 / 设备控制指令（权限化下放，白名单 LOCK/UNLOCK/REBOOT） */
  deviceSalesSummary: (id: string) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/sales-summary`,
  deviceCommand: (id: string) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(id)}/commands`,
  deviceTemperatureHistory: (deviceId: string, hours: number) =>
    `${API_PREFIX}/merchant/devices/${encodeURIComponent(deviceId)}/temperature-history?hours=${hours}`,
  pricingSkus: `${API_PREFIX}/merchant/pricing/skus`,
  pricingSkusByDevice: (deviceId: string) =>
    `${API_PREFIX}/merchant/pricing/skus?deviceId=${encodeURIComponent(deviceId)}`,
  pricingSku: (skuId: string) => `${API_PREFIX}/merchant/pricing/skus/${encodeURIComponent(skuId)}`,
  pricingHistory: `${API_PREFIX}/merchant/pricing/history`,
  workbench: `${API_PREFIX}/merchant/workbench`,
  opsConfig: `${API_PREFIX}/merchant/ops-config`,
  announcements: `${API_PREFIX}/merchant/announcements`,
  announcement: (id: number | string) =>
    `${API_PREFIX}/merchant/announcements/${encodeURIComponent(String(id))}`,
  teamUsers: `${API_PREFIX}/merchant/team/users`,
  teamUser: (userId: number | string) =>
    `${API_PREFIX}/merchant/team/users/${encodeURIComponent(String(userId))}`,
  teamUserDisable: (userId: number | string) =>
    `${API_PREFIX}/merchant/team/users/${encodeURIComponent(String(userId))}/disable`,
  teamUserEnable: (userId: number | string) =>
    `${API_PREFIX}/merchant/team/users/${encodeURIComponent(String(userId))}/enable`,
  teamUserResetPassword: (userId: number | string) =>
    `${API_PREFIX}/merchant/team/users/${encodeURIComponent(String(userId))}/reset-password`,
  teamRoles: `${API_PREFIX}/merchant/team/roles`,
  notifyPrefs: `${API_PREFIX}/merchant/notify/prefs`,
  notifyWxBind: `${API_PREFIX}/merchant/notify/wx-bind`,
  notifySubscribe: `${API_PREFIX}/merchant/notify/subscribe`,
  exceptions: (status: string, page: number, size: number) =>
    `${API_PREFIX}/merchant/exceptions?status=${encodeURIComponent(status)}&page=${page}&size=${size}`,
  exceptionResolve: (id: string) =>
    `${API_PREFIX}/merchant/exceptions/${encodeURIComponent(id)}/resolve`,
  revenueSplits: `${API_PREFIX}/merchant/revenue-splits`,
  expiryAlerts: `${API_PREFIX}/merchant/expiry-alerts`,
  deviceReports: `${API_PREFIX}/merchant/device-reports`,
  profile: `${API_PREFIX}/merchant/profile`,
  notifications: (limit: number) => `${API_PREFIX}/merchant/notifications?limit=${limit}`,
  notificationsUnreadCount: `${API_PREFIX}/merchant/notifications/unread-count`,
  notificationRead: (id: number | string) =>
    `${API_PREFIX}/merchant/notifications/${encodeURIComponent(String(id))}/read`,
  satelliteWarehouse: `${API_PREFIX}/merchant/satellite-warehouse`,
  satellitePurchaseSuppliers: `${API_PREFIX}/merchant/satellite-purchase/suppliers`,
  satellitePurchaseSkus: `${API_PREFIX}/merchant/satellite-purchase/skus`,
  satellitePurchaseOrders: `${API_PREFIX}/merchant/satellite-purchase/orders`,
  satellitePurchaseReceive: (purchaseOrderId: number | string) =>
    `${API_PREFIX}/merchant/satellite-purchase/orders/${encodeURIComponent(String(purchaseOrderId))}/receive`
} as const;

/**
 * 试点：pages/composables 不得再出现这些字面量前缀。
 * 扩表时同步把对应路径迁入 MerchantEndpoints（见 M3b/M3c/M3d）。
 */
export const MERCHANT_ENDPOINT_PILOT_LITERALS = [
  '/api/v2/merchant/orders',
  '/api/v2/merchant/wallet',
  '/api/v2/merchant/line-wallet',
  '/api/v2/merchant/disputes',
  '/api/v2/merchant/replenishment/',
  '/api/v2/auth/',
  '/api/v2/dicts/runtime',
  '/api/v2/public/merchant-config',
  '/api/v2/merchant/settlements',
  '/api/v2/merchant/pricing/',
  '/api/v2/merchant/analytics/',
  '/api/v2/merchant/team/',
  '/api/v2/merchant/notify/',
  '/api/v2/merchant/exceptions',
  '/api/v2/merchant/notifications',
  '/api/v2/merchant/satellite-'
] as const;
