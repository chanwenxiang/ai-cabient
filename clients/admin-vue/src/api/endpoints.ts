/**
 * A-P2-005 试点：运营后台高频端点集中定义。
 * 新调用优先从此处取路径，禁止业务层再散落同款裸字符串。
 * 与后端 `ApiVersions.PATH_PREFIX` / shared-api `API_PREFIX` 对齐（当前 v2）。
 */
export const API_PREFIX = '/api/v2' as const;

const ops = `${API_PREFIX}/ops/admin` as const;

/** 鉴权相关（与 AdminEndpoints 并列，供 client 与门禁使用）。 */
export const AuthEndpoints = {
  logout: `${API_PREFIX}/auth/logout`
} as const;

export const AdminEndpoints = {
  /** 工作台 KPI */
  stats: `${ops}/stats`,
  workbench: `${ops}/workbench`,
  workbenchBundle: `${ops}/workbench-bundle`,
  dataScope: `${ops}/data-scope`,
  sla: `${ops}/sla`,
  financeStats: `${ops}/finance/stats`,
  deviceAvailabilityKpi: `${ops}/device-availability-kpi`,
  deviceAvailabilityKpiOn: (date?: string) =>
    date
      ? `${ops}/device-availability-kpi?date=${encodeURIComponent(date)}`
      : `${ops}/device-availability-kpi`,
  /** 设备下拉参照 */
  devicesRef: `${ops}/devices/ref`,
  trend: (days: number) => `${ops}/trend?days=${days}`,
  trendOps: (days: number) => `${ops}/trend/ops?days=${days}`,
  trendChannels: (days: number) => `${ops}/trend/channels?days=${days}`,

  /** 异常中心（列表 / 详情 / 动作） */
  exceptions: `${ops}/exceptions`,
  exceptionsList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/exceptions?${query}`
      : `${ops}/exceptions?${query.toString()}`,
  exceptionsOpenCount: `${ops}/exceptions?status=OPEN&page=0&size=1`,
  exception: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}`,
  exceptionArchive: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/archive`,
  exceptionUnarchive: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/unarchive`,
  exceptionClaim: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/claim`,
  exceptionResolve: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/resolve`,
  exceptionNotes: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/notes`,
  exceptionTransfer: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/transfer`,
  exceptionCancelSession: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/cancel-session`,
  exceptionResolveWithRepair: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/resolve-with-repair`,
  exceptionRetry: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/retry`,
  exceptionManualResolve: (exceptionId: string) =>
    `${ops}/exceptions/${encodeURIComponent(exceptionId)}/manual-resolve`,

  /** 订单中心 */
  orders: `${ops}/orders`,
  ordersList: (query: URLSearchParams | string) =>
    typeof query === 'string' ? `${ops}/orders?${query}` : `${ops}/orders?${query.toString()}`,
  ordersExport: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/orders/export?${query}`
      : `${ops}/orders/export?${query.toString()}`,
  order: (orderId: string) => `${ops}/orders/${encodeURIComponent(orderId)}`,
  orderRefund: (orderId: string) => `${ops}/orders/${encodeURIComponent(orderId)}/refund`,
  orderRemind: (orderId: string) => `${ops}/orders/${encodeURIComponent(orderId)}/remind`,
  orderCollect: (orderId: string) => `${ops}/orders/${encodeURIComponent(orderId)}/collect`,
  orderCancel: (orderId: string) => `${ops}/orders/${encodeURIComponent(orderId)}/cancel`,

  /** 开门会话 */
  sessions: `${ops}/sessions`,
  sessionsList: (query: URLSearchParams | string) =>
    typeof query === 'string' ? `${ops}/sessions?${query}` : `${ops}/sessions?${query.toString()}`,
  sessionsExport: (query?: URLSearchParams | string) => {
    if (query == null || query === '') return `${ops}/sessions/export`;
    const qs = typeof query === 'string' ? query : query.toString();
    return qs ? `${ops}/sessions/export?${qs}` : `${ops}/sessions/export`;
  },
  sessionCancel: (sessionId: string) =>
    `${ops}/sessions/${encodeURIComponent(sessionId)}/cancel`,
  sessionVideo: (sessionId: string) =>
    `${ops}/sessions/${encodeURIComponent(sessionId)}/video`,

  /** 柜机中心（list / detail / 生命周期 / 指令 / 货道 / 温控） */
  devices: `${ops}/devices`,
  devicesList: (query: URLSearchParams | string) =>
    typeof query === 'string' ? `${ops}/devices?${query}` : `${ops}/devices?${query.toString()}`,
  devicesMapPoints: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/devices/map-points?${query}`
      : `${ops}/devices/map-points?${query.toString()}`,
  device: (deviceId: string) => `${ops}/devices/${encodeURIComponent(deviceId)}`,
  deviceDetail: (deviceId: string) => `${ops}/devices/${encodeURIComponent(deviceId)}/detail`,
  devicePolicy: (deviceId: string) => `${ops}/devices/${encodeURIComponent(deviceId)}/policy`,
  deviceLifecycle: (deviceId: string) =>
    `${ops}/devices/${encodeURIComponent(deviceId)}/lifecycle`,
  deviceLifecycleEvents: (deviceId: string, limit = 40) =>
    `${ops}/devices/${encodeURIComponent(deviceId)}/lifecycle-events?limit=${limit}`,
  deviceCommands: (deviceId: string) =>
    `${ops}/devices/${encodeURIComponent(deviceId)}/commands`,
  deviceQrLink: (deviceId: string) => `${ops}/devices/${encodeURIComponent(deviceId)}/qr-link`,
  deviceQrPng: (deviceId: string) => `${ops}/devices/${encodeURIComponent(deviceId)}/qr.png`,
  deviceTempPlan: (deviceId: string) =>
    `${ops}/devices/${encodeURIComponent(deviceId)}/temp-plan`,
  deviceTempPlanApply: (deviceId: string) =>
    `${ops}/devices/${encodeURIComponent(deviceId)}/temp-plan/apply`,
  deviceEnvReadings: (deviceId: string, query: URLSearchParams | string = 'hours=24&limit=200') =>
    typeof query === 'string'
      ? `${ops}/devices/${encodeURIComponent(deviceId)}/env-readings?${query}`
      : `${ops}/devices/${encodeURIComponent(deviceId)}/env-readings?${query.toString()}`,
  deviceResetHardwareBinding: (deviceId: string) =>
    `${ops}/devices/${encodeURIComponent(deviceId)}/reset-hardware-binding`,
  deviceRegenerateId: (deviceId: string) =>
    `${ops}/devices/${encodeURIComponent(deviceId)}/regenerate-id`,
  deviceSlots: (deviceId: string) => `${ops}/devices/${encodeURIComponent(deviceId)}/slots`,
  deviceSlotsApplyTemplate: (deviceId: string) =>
    `${ops}/devices/${encodeURIComponent(deviceId)}/slots/apply-template`,
  deviceSlotsStocktake: (deviceId: string) =>
    `${ops}/devices/${encodeURIComponent(deviceId)}/slots/stocktake`,

  /** 商户 / 分账 */
  merchants: `${ops}/merchants`,
  merchantsList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/merchants?${query}`
      : `${ops}/merchants?${query.toString()}`,
  merchantsProfitSharingStatus: `${ops}/merchants/profit-sharing/status`,
  merchantsRevenueSplits: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/merchants/revenue-splits?${query}`
      : `${ops}/merchants/revenue-splits?${query.toString()}`,
  merchantOpsConfig: (merchantId: string) =>
    `${ops}/merchants/${encodeURIComponent(merchantId)}/ops-config`,
  merchantRevenueSplitConfirmLedger: (splitId: string) =>
    `${ops}/merchants/revenue-splits/${encodeURIComponent(splitId)}/confirm-ledger`,
  merchantRevenueSplitWechatSubmit: (splitId: string) =>
    `${ops}/merchants/revenue-splits/${encodeURIComponent(splitId)}/wechat-submit`,
  merchantRevenueSplitWechatRefresh: (splitId: string) =>
    `${ops}/merchants/revenue-splits/${encodeURIComponent(splitId)}/wechat-refresh`,
  merchantRoleTemplates: `${ops}/merchant-role-templates`,

  /** 商户入驻 */
  merchantOnboarding: `${ops}/merchant-onboarding`,
  merchantOnboardingList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/merchant-onboarding?${query}`
      : `${ops}/merchant-onboarding?${query.toString()}`,
  merchantOnboardingSubmittedCount: `${ops}/merchant-onboarding?status=SUBMITTED&page=0&size=1`,
  merchantOnboardingLiveHints: `${ops}/merchant-onboarding/live-hints`,
  merchantOnboardingItem: (onboardingId: string | number) =>
    `${ops}/merchant-onboarding/${encodeURIComponent(String(onboardingId))}`,
  merchantOnboardingReview: (onboardingId: string | number) =>
    `${ops}/merchant-onboarding/${encodeURIComponent(String(onboardingId))}/review`,

  /** SKU 目录 */
  skus: `${ops}/skus`,
  skusList: (query: URLSearchParams | string) =>
    typeof query === 'string' ? `${ops}/skus?${query}` : `${ops}/skus?${query.toString()}`,
  skusCatalogPage: `${ops}/skus?page=0&size=500`,
  sku: (skuId: string) => `${ops}/skus/${encodeURIComponent(skuId)}`,
  skusImage: `${ops}/skus/image`,

  /** SKU 视觉建档 */
  skuVisionRows: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/sku-vision/rows?${query}`
      : `${ops}/sku-vision/rows?${query.toString()}`,
  skuVisionRowsAll: `${ops}/sku-vision/rows?status=ALL&page=0&size=500`,
  skuVisionEnroll: `${ops}/sku-vision/enroll`,
  skuVisionPipeline: `${ops}/sku-vision/pipeline`,
  skuVisionSuggestClass: `${ops}/sku-vision/suggest-class`,
  skuVisionSuggestClassName: (skuName: string) =>
    `${ops}/sku-vision/suggest-class-name?skuName=${encodeURIComponent(skuName)}`,
  skuVisionAdvance: (skuId: string) =>
    `${ops}/sku-vision/${encodeURIComponent(skuId)}/advance`,
  skuVisionStatus: (skuId: string, status: string) =>
    `${ops}/sku-vision/${encodeURIComponent(skuId)}/status?status=${encodeURIComponent(status)}`,

  /** 补货 / 效期 */
  replenishmentSummary: `${ops}/replenishment/summary`,
  replenishmentPlan: `${ops}/replenishment/plan`,
  replenishmentRequestsExport: `${ops}/replenishment/requests/export`,
  replenishmentRoutesExport: `${ops}/replenishment/routes/export`,
  replenishmentRoutes: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/replenishment/routes?${query}`
      : `${ops}/replenishment/routes?${query.toString()}`,
  replenishmentFulfillmentTasks: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/replenishment/fulfillment-tasks?${query}`
      : `${ops}/replenishment/fulfillment-tasks?${query.toString()}`,
  replenishmentRequests: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/replenishment/requests?${query}`
      : `${ops}/replenishment/requests?${query.toString()}`,
  replenishmentShortage: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/replenishment/shortage?${query}`
      : `${ops}/replenishment/shortage?${query.toString()}`,
  replenishmentTaskLines: (taskId: string | number) =>
    `${ops}/replenishment/tasks/${encodeURIComponent(String(taskId))}/lines`,
  replenishmentTaskEvidence: (taskId: string | number) =>
    `${ops}/replenishment/tasks/${encodeURIComponent(String(taskId))}/evidence`,
  replenishmentTaskEvidenceFile: (taskId: string | number, fileId: string | number) =>
    `${ops}/replenishment/tasks/${encodeURIComponent(String(taskId))}/evidence/${encodeURIComponent(String(fileId))}`,
  replenishmentTaskCheckIn: (taskId: string | number) =>
    `${ops}/replenishment/tasks/${encodeURIComponent(String(taskId))}/check-in`,
  replenishmentTaskComplete: (taskId: string | number) =>
    `${ops}/replenishment/tasks/${encodeURIComponent(String(taskId))}/complete`,
  replenishmentRouteCancelEmpty: (routeId: string | number) =>
    `${ops}/replenishment/routes/${encodeURIComponent(String(routeId))}/cancel-empty`,
  replenishmentRequestAccept: (requestId: string | number) =>
    `${ops}/replenishment/requests/${encodeURIComponent(String(requestId))}/accept`,
  replenishmentRequestReject: (requestId: string | number) =>
    `${ops}/replenishment/requests/${encodeURIComponent(String(requestId))}/reject`,
  replenishmentRequestEvidence: (requestId: string | number) =>
    `${ops}/replenishment/requests/${encodeURIComponent(String(requestId))}/evidence`,
  replenishmentRequestEvidenceFile: (requestId: string | number, fileId: string | number) =>
    `${ops}/replenishment/requests/${encodeURIComponent(String(requestId))}/evidence/${encodeURIComponent(String(fileId))}`,
  replenishmentReportStaff: (days: number | string) =>
    `${ops}/replenishment-report/staff?days=${encodeURIComponent(String(days))}`,
  expiryAlerts: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/expiry/alerts?${query}`
      : `${ops}/expiry/alerts?${query.toString()}`,
  expiryAlertsEnsure: `${ops}/expiry/alerts/ensure`,
  expiryAlertCreateReplenishment: (taskId: string | number) =>
    `${ops}/expiry/alerts/${encodeURIComponent(String(taskId))}/create-replenishment`
} as const;

/** 门禁扫描用：这些字面量不得再出现在 views/composables（endpoints.ts 除外）。 */
export const ADMIN_ENDPOINT_PILOT_LITERALS = [
  '/api/v2/ops/admin/stats',
  '/api/v2/ops/admin/workbench',
  '/api/v2/ops/admin/workbench-bundle',
  '/api/v2/ops/admin/data-scope',
  '/api/v2/ops/admin/sla',
  '/api/v2/ops/admin/finance/stats',
  '/api/v2/ops/admin/device-availability-kpi',
  '/api/v2/ops/admin/devices/ref',
  '/api/v2/ops/admin/devices',
  '/api/v2/ops/admin/exceptions',
  '/api/v2/ops/admin/orders',
  '/api/v2/ops/admin/sessions',
  '/api/v2/ops/admin/merchants',
  '/api/v2/ops/admin/merchant-onboarding',
  '/api/v2/ops/admin/skus',
  '/api/v2/ops/admin/sku-vision',
  '/api/v2/ops/admin/replenishment/',
  '/api/v2/ops/admin/replenishment-report',
  '/api/v2/ops/admin/expiry'
] as const;
