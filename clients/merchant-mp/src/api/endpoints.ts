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
 * `utils/merchant-api.ts`：M3b 证据/导出/me；M3c 订单/争议/钱包；M3d 补货/分析/公开配置；余 → M3e。
 */
export const API_PREFIX = '/api/v2' as const;

/** 鉴权相关（与 MerchantEndpoints 并列）。 */
export const AuthEndpoints = {
  merchantPasswordLogin: `${API_PREFIX}/auth/merchant-password-login`
} as const;

export const MerchantEndpoints = {
  /** 订单购物视频（旁路 fetch / download，禁止页内裸拼） */
  orderVideo: (orderId: string) =>
    `${API_PREFIX}/merchant/orders/${encodeURIComponent(orderId)}/video`,
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
  /** 经营分析 */
  analyticsOverview: (days: number) => `${API_PREFIX}/merchant/analytics/overview?days=${days}`,
  analyticsSalesReports: `${API_PREFIX}/merchant/analytics/sales-reports`,
  analyticsSkuSales: `${API_PREFIX}/merchant/analytics/sku-sales`,
  analyticsVelocity: (deviceId: string) =>
    `${API_PREFIX}/merchant/analytics/velocity?deviceId=${encodeURIComponent(deviceId)}`,
  analyticsAiInsight: (days: number) => `${API_PREFIX}/merchant/analytics/ai-insight?days=${days}`,
  analyticsExpirySummary: `${API_PREFIX}/merchant/analytics/expiry-summary`,
  /** 税档 / 库存 */
  taxProfile: `${API_PREFIX}/merchant/tax-profile`,
  taxProfileByMerchant: (merchantId: string) =>
    `${API_PREFIX}/merchant/tax-profile?merchantId=${encodeURIComponent(merchantId)}`,
  inventoryLowStock: `${API_PREFIX}/merchant/inventory?lowStockOnly=true`,
  /** 补货 JSON */
  replenishmentSuggestions: (deviceId: string) =>
    `${API_PREFIX}/merchant/replenishment/suggestions?deviceId=${encodeURIComponent(deviceId)}`,
  replenishmentEfficiency: `${API_PREFIX}/merchant/replenishment/my-efficiency`,
  replenishmentRequests: `${API_PREFIX}/merchant/replenishment/requests`,
  replenishmentRequestEvidenceList: (requestId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/requests/${encodeURIComponent(String(requestId))}/evidence`,
  replenishmentTasks: `${API_PREFIX}/merchant/replenishment/tasks`,
  replenishmentTasksByStatus: (status: string) =>
    `${API_PREFIX}/merchant/replenishment/tasks?status=${encodeURIComponent(status)}`,
  replenishmentDeviceAccess: (deviceId: string) =>
    `${API_PREFIX}/merchant/replenishment/devices/${encodeURIComponent(deviceId)}/access`,
  replenishmentDoorSession: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/door-session`,
  replenishmentTaskLines: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/lines`,
  replenishmentTaskCheckIn: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/check-in`,
  replenishmentTaskOpenDoor: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/open-door`,
  replenishmentTaskComplete: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/complete`,
  /** 补货证据上传/下载（非 JSON，仍走 Endpoints 防裸拼） */
  replenishmentTaskEvidence: (taskId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/evidence`,
  replenishmentTaskEvidenceFile: (taskId: number | string, fileId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/tasks/${encodeURIComponent(String(taskId))}/evidence/${encodeURIComponent(String(fileId))}`,
  replenishmentRequestEvidence: `${API_PREFIX}/merchant/replenishment/requests/evidence`,
  replenishmentRequestEvidenceFile: (requestId: number | string, fileId: number | string) =>
    `${API_PREFIX}/merchant/replenishment/requests/${encodeURIComponent(String(requestId))}/evidence/${encodeURIComponent(String(fileId))}`,
  /** 导出（绝对 URL 由 merchant-api 拼 base） */
  settlementsExport: (from: string, to: string) =>
    `${API_PREFIX}/merchant/settlements/export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  ordersExport: (deviceId?: string) => {
    const q = deviceId ? `?deviceId=${encodeURIComponent(deviceId)}` : '';
    return `${API_PREFIX}/merchant/orders/export${q}`;
  },
  deviceReportsExport: `${API_PREFIX}/merchant/device-reports/export`
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
  '/api/v2/merchant/analytics/'
] as const;
