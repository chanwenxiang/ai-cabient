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
    `${ops}/expiry/alerts/${encodeURIComponent(String(taskId))}/create-replenishment`,

  /** 仓配 / 供应商 / 采购 */
  warehouseList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/warehouse/list?${query}`
      : `${ops}/warehouse/list?${query.toString()}`,
  warehouseListAll: `${ops}/warehouse/list?page=0&size=500`,
  warehouseItem: (warehouseId: string) =>
    `${ops}/warehouse/${encodeURIComponent(warehouseId)}`,
  warehouseExport: (tab: string) =>
    `${ops}/warehouse/export?tab=${encodeURIComponent(tab)}`,
  warehouseInbound: `${ops}/warehouse/inbound`,
  warehouseOutbounds: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/warehouse/outbounds?${query}`
      : `${ops}/warehouse/outbounds?${query.toString()}`,
  warehouseOutboundsAll: `${ops}/warehouse/outbounds?page=0&size=500`,
  warehouseOutbound: (outboundId: string | number) =>
    `${ops}/warehouse/outbounds/${encodeURIComponent(String(outboundId))}`,
  warehouseOutboundAction: (outboundId: string | number, action: string) =>
    `${ops}/warehouse/outbounds/${encodeURIComponent(String(outboundId))}/${encodeURIComponent(action)}`,
  warehouseOutboundsCleanupStale: `${ops}/warehouse/outbounds/cleanup-stale`,
  warehouseInTransit: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/warehouse/in-transit?${query}`
      : `${ops}/warehouse/in-transit?${query.toString()}`,
  warehouseInventory: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/warehouse/inventory?${query}`
      : `${ops}/warehouse/inventory?${query.toString()}`,
  warehouseMovements: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/warehouse/movements?${query}`
      : `${ops}/warehouse/movements?${query.toString()}`,
  warehouseStocktakes: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/warehouse/stocktakes?${query}`
      : `${ops}/warehouse/stocktakes?${query.toString()}`,
  warehouseStocktakesCreate: `${ops}/warehouse/stocktakes`,
  warehouseStocktake: (stocktakeId: string | number) =>
    `${ops}/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}`,
  warehouseStocktakeScanPhoto: (stocktakeId: string | number) =>
    `${ops}/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/scan-photo`,
  warehouseStocktakeLine: (stocktakeId: string | number, lineId: string | number) =>
    `${ops}/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/lines/${encodeURIComponent(String(lineId))}`,
  warehouseStocktakeComplete: (stocktakeId: string | number) =>
    `${ops}/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/complete`,
  warehouseStocktakeAdjust: (stocktakeId: string | number) =>
    `${ops}/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/adjust`,
  warehouseStocktakeCancel: (stocktakeId: string | number) =>
    `${ops}/warehouse/stocktakes/${encodeURIComponent(String(stocktakeId))}/cancel`,
  warehouseBins: `${ops}/warehouse/bins`,
  warehouseBinsStock: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/warehouse/bins/stock?${query}`
      : `${ops}/warehouse/bins/stock?${query.toString()}`,
  warehouseBinsStockInbound: `${ops}/warehouse/bins/stock/inbound`,
  warehouseBinsStockMove: `${ops}/warehouse/bins/stock/move`,
  warehouseTransfers: (query?: URLSearchParams | string) => {
    if (query == null || query === '') return `${ops}/warehouse/transfers`;
    const qs = typeof query === 'string' ? query : query.toString();
    return qs ? `${ops}/warehouse/transfers?${qs}` : `${ops}/warehouse/transfers`;
  },
  warehouseTransferShip: (transferId: string | number) =>
    `${ops}/warehouse/transfers/${encodeURIComponent(String(transferId))}/ship`,
  warehouseTransferReceive: (transferId: string | number) =>
    `${ops}/warehouse/transfers/${encodeURIComponent(String(transferId))}/receive`,
  warehouseTransferCancel: (transferId: string | number) =>
    `${ops}/warehouse/transfers/${encodeURIComponent(String(transferId))}/cancel`,
  suppliersList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/suppliers?${query}`
      : `${ops}/suppliers?${query.toString()}`,
  suppliersListAll: `${ops}/suppliers?page=0&size=500`,
  supplier: (supplierId: string) => `${ops}/suppliers/${encodeURIComponent(supplierId)}`,
  suppliersPayables: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/suppliers/payables?${query}`
      : `${ops}/suppliers/payables?${query.toString()}`,
  suppliersPayablesSummary: `${ops}/suppliers/payables/summary`,
  suppliersPayablePay: (payableId: string | number) =>
    `${ops}/suppliers/payables/${encodeURIComponent(String(payableId))}/pay`,
  purchaseOrders: `${ops}/purchase-orders`,
  purchaseOrdersList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/purchase-orders?${query}`
      : `${ops}/purchase-orders?${query.toString()}`,
  purchaseOrdersReturnable: `${ops}/purchase-orders?returnableOnly=true&page=0&size=500`,
  purchaseOrder: (purchaseOrderId: string | number) =>
    `${ops}/purchase-orders/${encodeURIComponent(String(purchaseOrderId))}`,
  purchaseOrderReview: (purchaseOrderId: string | number) =>
    `${ops}/purchase-orders/${encodeURIComponent(String(purchaseOrderId))}/review`,
  purchaseOrderReceive: (purchaseOrderId: string | number) =>
    `${ops}/purchase-orders/${encodeURIComponent(String(purchaseOrderId))}/receive`,
  purchaseReturns: `${ops}/purchase-returns`,
  purchaseReturnsList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/purchase-returns?${query}`
      : `${ops}/purchase-returns?${query.toString()}`,
  procurementSuggestions: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/procurement/suggestions?${query}`
      : `${ops}/procurement/suggestions?${query.toString()}`,

  /** 财务 / 资金 / 提现 / 发票 / 线长 */
  financeReport: (days: number | string) =>
    `${ops}/finance/report?days=${encodeURIComponent(String(days))}`,
  financeMarginLocksSolidify: `${ops}/finance/margin-locks/solidify`,
  fundDailyBills: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/fund/daily-bills?${query}`
      : `${ops}/fund/daily-bills?${query.toString()}`,
  fundDailyBillsExport: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/fund/daily-bills/export?${query}`
      : `${ops}/fund/daily-bills/export?${query.toString()}`,
  fundLedger: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/fund/ledger?${query}`
      : `${ops}/fund/ledger?${query.toString()}`,
  balanceRefundsList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/balance-refunds?${query}`
      : `${ops}/balance-refunds?${query.toString()}`,
  balanceRefundReview: (requestId: string | number) =>
    `${ops}/balance-refunds/${encodeURIComponent(String(requestId))}/review`,
  invoicesList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/invoices?${query}`
      : `${ops}/invoices?${query.toString()}`,
  invoiceIssue: (invoiceId: string | number) =>
    `${ops}/invoices/${encodeURIComponent(String(invoiceId))}/issue`,
  invoiceReject: (invoiceId: string | number) =>
    `${ops}/invoices/${encodeURIComponent(String(invoiceId))}/reject`,
  lineManagers: `${ops}/line-managers`,
  lineManagersList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/line-managers?${query}`
      : `${ops}/line-managers?${query.toString()}`,
  lineManagerDevices: (managerId: string | number) =>
    `${ops}/line-managers/${encodeURIComponent(String(managerId))}/devices`,
  lineManagerAdjust: (managerId: string | number) =>
    `${ops}/line-managers/${encodeURIComponent(String(managerId))}/adjust`,
  lineManagerLedgers: (managerId: string | number, limit = 50) =>
    `${ops}/line-managers/${encodeURIComponent(String(managerId))}/ledgers?limit=${limit}`,
  lineManagerKpi: (managerId: string | number) =>
    `${ops}/line-managers/${encodeURIComponent(String(managerId))}/kpi`,
  lineManagerWithdraw: (managerId: string | number) =>
    `${ops}/line-managers/${encodeURIComponent(String(managerId))}/withdraw`,
  lineWithdrawsList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/line-withdraws?${query}`
      : `${ops}/line-withdraws?${query.toString()}`,
  lineWithdrawsPayoutMode: `${ops}/line-withdraws/payout-mode`,
  lineWithdrawReview: (requestId: string | number) =>
    `${ops}/line-withdraws/${encodeURIComponent(String(requestId))}/review`,
  lineWithdrawPayout: (requestId: string | number) =>
    `${ops}/line-withdraws/${encodeURIComponent(String(requestId))}/payout`,
  lineWithdrawCancel: (requestId: string | number) =>
    `${ops}/line-withdraws/${encodeURIComponent(String(requestId))}/cancel`,
  linePromoTasks: `${ops}/line-promo-tasks`,
  merchantWalletsList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/merchant-wallets?${query}`
      : `${ops}/merchant-wallets?${query.toString()}`,
  merchantWalletAdjust: (merchantId: string) =>
    `${ops}/merchant-wallets/${encodeURIComponent(merchantId)}/adjust`,
  merchantWalletLedgers: (merchantId: string, limit = 50) =>
    `${ops}/merchant-wallets/${encodeURIComponent(merchantId)}/ledgers?limit=${limit}`,
  merchantWalletWithdraw: (merchantId: string) =>
    `${ops}/merchant-wallets/${encodeURIComponent(merchantId)}/withdraw`,
  merchantWithdrawsList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/merchant-withdraws?${query}`
      : `${ops}/merchant-withdraws?${query.toString()}`,
  merchantWithdrawsPayoutMode: `${ops}/merchant-withdraws/payout-mode`,
  merchantWithdrawReview: (requestId: string | number) =>
    `${ops}/merchant-withdraws/${encodeURIComponent(String(requestId))}/review`,
  merchantWithdrawPayout: (requestId: string | number) =>
    `${ops}/merchant-withdraws/${encodeURIComponent(String(requestId))}/payout`,
  merchantWithdrawCancel: (requestId: string | number) =>
    `${ops}/merchant-withdraws/${encodeURIComponent(String(requestId))}/cancel`,

  /** RBAC / 部门 */
  rbacRoles: `${ops}/rbac/roles`,
  rbacRole: (roleId: string | number) =>
    `${ops}/rbac/roles/${encodeURIComponent(String(roleId))}`,
  rbacRolePermissions: (roleId: string | number) =>
    `${ops}/rbac/roles/${encodeURIComponent(String(roleId))}/permissions`,
  rbacPermissions: `${ops}/rbac/permissions`,
  rbacPermissionsIncludeInactive: `${ops}/rbac/permissions?includeInactive=true`,
  rbacPermission: (permissionId: string | number) =>
    `${ops}/rbac/permissions/${encodeURIComponent(String(permissionId))}`,
  rbacOperators: `${ops}/rbac/operators`,
  rbacOperatorsList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/rbac/operators?${query}`
      : `${ops}/rbac/operators?${query.toString()}`,
  rbacOperatorsPage: (page = 0, size = 200) =>
    `${ops}/rbac/operators?page=${page}&size=${size}`,
  rbacOperator: (userId: string | number) =>
    `${ops}/rbac/operators/${encodeURIComponent(String(userId))}`,
  rbacOperatorResetPassword: (userId: string | number) =>
    `${ops}/rbac/operators/${encodeURIComponent(String(userId))}/reset-password`,
  rbacUserRoles: (userId: string | number) =>
    `${ops}/rbac/users/${encodeURIComponent(String(userId))}/roles`,
  rbacUserMerchants: (userId: string | number) =>
    `${ops}/rbac/users/${encodeURIComponent(String(userId))}/merchants`,
  rbacUserDevices: (userId: string | number) =>
    `${ops}/rbac/users/${encodeURIComponent(String(userId))}/devices`,
  rbacMe: `${ops}/rbac/me`,
  rbacMePermissions: `${ops}/rbac/me/permissions`,
  rbacMeNav: `${ops}/rbac/me/nav`,
  rbacMePassword: `${ops}/rbac/me/password`,
  rbacMeAvatar: `${ops}/rbac/me/avatar`,
  rbacMeTwoFactorStatus: `${ops}/rbac/me/two-factor/status`,
  rbacMeTwoFactorEnroll: `${ops}/rbac/me/two-factor/enroll`,
  rbacMeTwoFactorConfirm: `${ops}/rbac/me/two-factor/confirm`,
  rbacMeTwoFactorDisable: `${ops}/rbac/me/two-factor/disable`,
  departments: `${ops}/departments`,
  department: (deptId: string | number) =>
    `${ops}/departments/${encodeURIComponent(String(deptId))}`,
  departmentMembers: (deptId: string | number) =>
    `${ops}/departments/${encodeURIComponent(String(deptId))}/members`,

  /** 系统配置 / 字典 / 定时任务 / 审批 / 组织 / DevOps / 审计 */
  systemConfigs: `${ops}/system-configs`,
  systemConfig: (configKey: string) =>
    `${ops}/system-configs/${encodeURIComponent(configKey)}`,
  systemConfigBrandLogo: `${ops}/system-configs/brand-logo`,
  dicts: `${ops}/dicts`,
  dictTypes: `${ops}/dicts/types`,
  dictType: (dictType: string) =>
    `${ops}/dicts/types/${encodeURIComponent(dictType)}`,
  dictItems: (dictType: string) =>
    `${ops}/dicts/${encodeURIComponent(dictType)}/items`,
  dictItem: (dictType: string, dictDataId: string | number) =>
    `${ops}/dicts/${encodeURIComponent(dictType)}/items/${encodeURIComponent(String(dictDataId))}`,
  dictItemById: (dictDataId: string | number) =>
    `${ops}/dicts/items/${encodeURIComponent(String(dictDataId))}`,
  scheduledTasks: `${ops}/scheduled-tasks`,
  scheduledTask: (taskKey: string) =>
    `${ops}/scheduled-tasks/${encodeURIComponent(taskKey)}`,
  scheduledTaskEnabled: (taskKey: string) =>
    `${ops}/scheduled-tasks/${encodeURIComponent(taskKey)}/enabled`,
  scheduledTaskRun: (taskKey: string) =>
    `${ops}/scheduled-tasks/${encodeURIComponent(taskKey)}/run`,
  approvalsDefinitions: `${ops}/approvals/definitions`,
  approvalsDefinition: (defId: string | number) =>
    `${ops}/approvals/definitions/${encodeURIComponent(String(defId))}`,
  approvalsInbox: (limit = 15) => `${ops}/approvals/inbox?limit=${limit}`,
  approvalsTaskRead: (taskId: string | number) =>
    `${ops}/approvals/tasks/${encodeURIComponent(String(taskId))}/read`,
  approvalsMessageRead: (messageId: string | number) =>
    `${ops}/approvals/messages/${encodeURIComponent(String(messageId))}/read`,
  orgTree: `${ops}/org/tree`,
  orgNodes: `${ops}/org/nodes`,
  orgNode: (nodeId: string | number) =>
    `${ops}/org/nodes/${encodeURIComponent(String(nodeId))}`,
  orgNodeToggle: (nodeId: string | number, enabled: boolean) =>
    `${ops}/org/nodes/${encodeURIComponent(String(nodeId))}/toggle?enabled=${enabled}`,
  orgNodeDevices: (nodeId: string | number) =>
    `${ops}/org/nodes/${encodeURIComponent(String(nodeId))}/devices`,
  devopsHub: `${ops}/devops/hub`,
  devopsSonarScan: `${ops}/devops/sonar/scan`,
  auditLogsList: (query: URLSearchParams | string) =>
    typeof query === 'string'
      ? `${ops}/audit-logs?${query}`
      : `${ops}/audit-logs?${query.toString()}`
} as const;

/** 门禁扫描用：这些字面量不得再出现在 views/composables（endpoints.ts 除外）。 */
export const ADMIN_ENDPOINT_PILOT_LITERALS = [
  '/api/v2/ops/admin/stats',
  '/api/v2/ops/admin/workbench',
  '/api/v2/ops/admin/workbench-bundle',
  '/api/v2/ops/admin/data-scope',
  '/api/v2/ops/admin/sla',
  '/api/v2/ops/admin/finance/stats',
  '/api/v2/ops/admin/finance/',
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
  '/api/v2/ops/admin/expiry',
  '/api/v2/ops/admin/warehouse',
  '/api/v2/ops/admin/suppliers',
  '/api/v2/ops/admin/purchase-orders',
  '/api/v2/ops/admin/purchase-returns',
  '/api/v2/ops/admin/procurement',
  '/api/v2/ops/admin/fund',
  '/api/v2/ops/admin/balance-refunds',
  '/api/v2/ops/admin/invoices',
  '/api/v2/ops/admin/line-managers',
  '/api/v2/ops/admin/line-withdraws',
  '/api/v2/ops/admin/line-promo-tasks',
  '/api/v2/ops/admin/merchant-wallets',
  '/api/v2/ops/admin/merchant-withdraws',
  '/api/v2/ops/admin/rbac',
  '/api/v2/ops/admin/departments',
  '/api/v2/ops/admin/system-configs',
  '/api/v2/ops/admin/dicts',
  '/api/v2/ops/admin/scheduled-tasks',
  '/api/v2/ops/admin/approvals',
  '/api/v2/ops/admin/org',
  '/api/v2/ops/admin/devops',
  '/api/v2/ops/admin/audit-logs'
] as const;
