export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface LoginResponse {
  token: string;
  userId: string;
  expiresInSeconds?: number;
  serverBootEpoch?: number;
  /** 服务端已写入 HttpOnly 会话 Cookie 时，浏览器端无需持久化 token */
  cookieEnabled?: boolean;
  /** 密码已通过但需完成双因子认证；token 为短时 challenge，须调用 2FA 完成登录 */
  twoFactorRequired?: boolean;
}

export interface TwoFactorEnroll {
  secret: string;
  otpauthUri: string;
  recoveryCodes: string[];
}

export interface TwoFactorStatus {
  enabled: boolean;
}

export interface DeviceTempPlanEntry {
  entryId?: number;
  startMinute: number;
  targetTempC: number;
}

export interface DeviceTempPlan {
  deviceId: string;
  enabled: boolean;
  entries: DeviceTempPlanEntry[];
}

export interface DeviceEnvReading {
  deviceId: string;
  metricType: string;
  value: number;
  reportedAt?: string;
}

export interface MediaAssetDto {
  assetId: number;
  title: string;
  assetType: string;
  storageUri?: string;
  previewUrl?: string;
  durationSeconds: number;
  status: string;
  createdAt?: string;
}

export interface FootfallOverview {
  totalOpens: number;
  totalPaidOrders: number;
  revenueCents: number;
  conversionRate: number;
  avgOrderValueCents: number;
  repeatBuyers: number;
  deviceCount: number;
}

export interface FootfallDevice {
  deviceId: string;
  deviceName: string;
  opens: number;
  orders: number;
  revenueCents: number;
  conversionRate: number;
  revenuePerDeviceCents: number;
}

export interface HourlyHeat {
  hour: number;
  orders: number;
  revenueCents: number;
}

export interface SkuHeat {
  skuId: string;
  skuName: string;
  qtySold: number;
  revenueCents: number;
}

export interface FootfallAnalytics {
  overview: FootfallOverview;
  devices: FootfallDevice[];
  hourly: HourlyHeat[];
  topSkus: SkuHeat[];
}

export interface SlotHeat {
  slotId: string;
  rowNo: number;
  colNo: number;
  skuId: string;
  skuName: string;
  qtySold: number;
  revenueCents: number;
  heatLevel: number;
}

export interface OrgNodeDto {
  nodeId: number;
  parentId?: number;
  name: string;
  nodeType: string;
  sortOrder: number;
  enabled: boolean;
  deviceIds: string[];
  children: OrgNodeDto[];
}

export interface SiteContractDto {
  contractId: number;
  deviceId: string;
  deviceName: string;
  siteName: string;
  address?: string;
  landlordName?: string;
  landlordPhone?: string;
  startDate?: string;
  endDate?: string;
  monthlyFeeCents: number;
  status: string;
  remark?: string;
  updatedAt?: string;
}

export interface AdCampaignDto {
  campaignId: number;
  name: string;
  status: string;
  deviceScope: string;
  startAt?: string;
  endAt?: string;
  assetIds: number[];
  deviceIds: string[];
  createdAt?: string;
  updatedAt?: string;
  impressionCount?: number;
  completeCount?: number;
}

export interface DeviceInfo {
  deviceId: string;
  deviceName?: string;
  deviceType?: string;
  merchantId?: string;
  merchantName?: string;
  onlineStatus?: string;
  activeSessionId?: string;
  activeSessionState?: string;
  updatedAt?: string;
  /** 设备覆盖：AUTO_REFUND | DISPUTE_ONLY | null/空=继承全局 */
  refundPolicy?: string | null;
  /** 生效策略（已解析全局默认） */
  effectiveRefundPolicy?: string;
  /** 锁机停售 */
  salesLocked?: boolean;
  /** 停售原因 */
  salesLockReason?: string;
  replenishmentInProgress?: boolean;
  /** INBOUND|IDLE|DEPLOYED|RETURNING|RETIRED */
  lifecycleStatus?: string;
  imei?: string;
  assetOwner?: string;
  /** SELF|FRANCHISE|CONSIGN */
  coopMode?: string;
  depositCents?: number;
  dataFeeCents?: number;
  opsTags?: string;
  routeCode?: string;
  latitude?: number;
  longitude?: number;
  address?: string;
  deployedAt?: string;
  lifecycleRemark?: string;
  /** 柜机最近上报温度（℃） */
  currentTempC?: number | null;
  /** 商户端列表可选：缺货/低库存货道数 */
  oosSlotCount?: number | null;
  lowStockSlotCount?: number | null;
}

export interface DeviceSlot {
  deviceId: string;
  slotCode: string;
  rowNo: number;
  colNo: number;
  slotType?: string;
  assignedSkuId?: string;
  assignedSkuName?: string;
  parLevel: number;
  minLevel: number;
  maxLevel: number;
  enabled: boolean;
  bookQty: number;
  lastPhysicalQty?: number | null;
  lastPhysicalAt?: string;
  lastRestockAt?: string;
  fillRatePct?: number;
  stockStatus?: string;
  qtyDiff?: number;
  hasDiscrepancy?: boolean;
}

export interface UpsertDeviceSlotRequest {
  slotCode: string;
  rowNo?: number;
  colNo?: number;
  slotType?: string;
  assignedSkuId?: string;
  parLevel?: number;
  minLevel?: number;
  maxLevel?: number;
  enabled?: boolean;
}

export interface MerchantDto {
  merchantId: string;
  merchantName: string;
  contactPhone?: string;
  platformRateBps: number;
  wechatReceiverId?: string;
  status: string;
  remark?: string;
  deviceCount?: number;
  allowMerchantPlanogramEdit?: boolean;
  allowMerchantPricingEdit?: boolean;
  /** 功能包：现场作业 */
  packFieldEnabled?: boolean;
  /** 功能包：经营工具 */
  packBizEnabled?: boolean;
  /** 功能包：团队与设置 */
  packTeamEnabled?: boolean;
  parentMerchantId?: string | null;
}

export interface RevenueSplit {
  splitId: string;
  orderId: string;
  merchantId: string;
  merchantName?: string;
  deviceId: string;
  grossCents: number;
  platformCents: number;
  merchantCents: number;
  status: string;
  wechatOutOrderNo?: string;
  wechatTransactionId?: string;
  failureReason?: string;
  createdAt?: string;
  settlementBatchNo?: string;
  settleAfter?: string;
  settledAt?: string;
}

export interface MerchantUserDto {
  userId: number;
  phoneNumber?: string;
  displayName?: string;
  roleKey?: string;
  roleName?: string;
  status?: string;
  self?: boolean;
}

export interface MerchantTeamRoleDto {
  roleKey: string;
  roleName: string;
  description?: string;
}

export interface ProfitSharingStatus {
  enabled: boolean;
  apiReady: boolean;
  retryEnabled: boolean;
  retryBatchSize: number;
  wechatPayConfigured: string;
  note: string;
}

export interface OrderSummary {
  orderId: string;
  sessionId?: string;
  userId?: string | number;
  deviceId?: string;
  merchantId?: string;
  totalAmountCents: number;
  originalAmountCents?: number;
  status?: string;
  payChannel?: string;
  lineCount?: number;
  lineSummary?: string;
  payTradeNo?: string;
  paymentOperationId?: string;
  refundedAt?: string;
  refundedCents?: number;
  couponDiscountCents?: number;
  memberDiscountCents?: number;
  inventoryDeducted?: boolean;
  refundPolicy?: string;
  createdAt?: string;
}

export interface DisputeSummary {
  ticketId: string;
  sessionId?: string;
  deviceId?: string;
  status: string;
  reason?: string;
  createdAt?: string;
}

export interface SkuCatalog {
  skuId: string;
  skuCode?: number;
  skuName: string;
  priceCents: number;
  weightGrams?: number;
  visionEnabled?: boolean;
  imageUrl?: string;
  description?: string;
  category?: string;
  barcode?: string;
  brand?: string;
  spec?: string;
  unit?: string;
  status?: string;
  shelfLifeDays?: number;
  nearExpiryDays?: number;
  blockSaleDaysBeforeExpiry?: number;
  storageType?: string;
  purchaseCostCents?: number;
  nearExpiryPriceCents?: number;
  maxPriceCents?: number;
  minChargeConfidence?: number;
  yoloClassName?: string;
  visionEnrollmentStatus?: string;
  detectionMinConfidence?: number;
  referenceImageUrlsJson?: string;
  createdAt?: string;
  updatedByUserId?: number;
  updatedByName?: string;
}

export interface SkuVisionEnrollmentRow {
  sku: SkuCatalog;
  mappingEffective: boolean;
  modelPipelineStatus: string;
  nextAction: string;
  nextStatus?: string | null;
}

export interface SkuVisionEnrollmentPipeline {
  modelPipelineStatus: string;
  modelPipelineHint: string;
  statusOrder: string[];
  steps: Array<{ status: string; label: string; description: string }>;
}

export interface UpsertSkuRequest {
  /** 新建可空，服务端生成 SKU-{skuCode} */
  skuId?: string;
  skuName: string;
  priceCents: number;
  weightGrams?: number;
  visionEnabled?: boolean;
  imageUrl?: string;
  description?: string;
  category?: string;
  barcode?: string;
  brand?: string;
  spec?: string;
  unit?: string;
  status?: string;
  shelfLifeDays?: number;
  nearExpiryDays?: number;
  blockSaleDaysBeforeExpiry?: number;
  storageType?: string;
  purchaseCostCents?: number;
  nearExpiryPriceCents?: number;
  minChargeConfidence?: number;
  yoloClassName?: string;
  visionEnrollmentStatus?: string;
  detectionMinConfidence?: number;
  referenceImageUrlsJson?: string;
  /** 只读；写入时服务端忽略 */
  skuCode?: number;
}

export interface UpsertSkuVisionEnrollmentRequest {
  sku: UpsertSkuRequest;
  yoloClassName?: string;
  visionEnrollmentStatus?: string;
  detectionMinConfidence?: number;
  referenceImageUrlsJson?: string;
  mappingSource?: string;
}

export interface DevRecognitionItemDto {
  skuId: string;
  skuName?: string;
  quantity: number;
  confidence: number;
  unitPriceCents?: number;
  lineAmountCents?: number;
}

export interface DevRecognitionPreviewDto {
  items: DevRecognitionItemDto[];
  detectedClasses?: string[];
  overallConfidence?: number;
  needReview?: boolean;
  modelVersion?: string;
  hint?: string;
}

export interface MerchantSkuPricing {
  deviceId: string;
  deviceName?: string;
  skuId: string;
  skuName: string;
  /** 商品条码，用于补货/出入库扫码匹配 */
  barcode?: string;
  /** 商品主图（后台在商品管理中上传，三端共用） */
  imageUrl?: string;
  basePriceCents: number;
  overridePriceCents?: number | null;
  effectivePriceCents: number;
  quantity?: number;
  /** 商户可改价下限（分），空表示不限制 */
  minPriceCents?: number | null;
  /** 商户可改价上限（分），空表示不限制 */
  maxPriceCents?: number | null;
}

export interface MerchantMe {
  userId: string;
  phoneNumber?: string;
  displayName?: string;
  merchants: MerchantDto[];
  permissions: string[];
  canEditPricing?: boolean;
  /** 绑定商户功能包并集：field / biz / team */
  enabledPacks?: string[];
}

export interface MerchantWorkbench {
  openDisputes: number;
  offlineDevices: number;
  lowStockItems: number;
  expiryAlerts: number;
  slotDiscrepancies?: number;
  pendingSplits?: number;
  actionItems: {
    type: string;
    title: string;
    detail?: string;
    deviceId?: string;
    ticketId?: string;
  }[];
}

export interface MerchantSkuPerformance {
  skuId: string;
  skuName: string;
  qtySold: number;
  revenueCents: number;
  grossMarginCents: number;
  grossMarginRate: number;
  currentStock: number;
  averageDailySales: number;
  daysOfCover?: number | null;
  performanceLevel?: string;
  recommendation?: string;
}

export interface MerchantSkuSales {
  skuId: string;
  skuName: string;
  qtySold: number;
  revenueCents: number;
  cogsCents: number;
  grossMarginCents: number;
}

/** 商品动销/补货点（/merchant/analytics/velocity） */
export interface MerchantSkuVelocity {
  skuId: string;
  skuName: string;
  soldQty7d: number;
  soldQty14d: number;
  avgDailySales: number;
  ropPoint: number;
}

/** 温度历史读数（/merchant/devices/{id}/temperature-history） */
export interface DeviceTemperatureReading {
  deviceId: string;
  tempC: number;
  reportedAt?: string;
}

/** 调价历史（/merchant/pricing/history） */
export interface MerchantSkuPriceChange {
  deviceId?: string;
  skuId: string;
  detail?: string;
  changedAt?: string;
}

/** AI 经营洞察（/merchant/analytics/ai-insight） */
export interface MerchantAiInsight {
  source?: string;
  model?: string;
  insight?: string;
  generatedAt?: string;
  skuPerformance?: MerchantSkuPerformance[];
}

/** 临期摘要（/merchant/analytics/expiry-summary） */
export interface MerchantExpirySummary {
  openPullOffTasks: number;
  writeOffQty30d: number;
  writeOffCostCents30d: number;
}

export interface MerchantAnalyticsOverview {
  days: number;
  revenueCents: number;
  cogsCents: number;
  grossMarginCents: number;
  writeOffCostCents: number;
  topSkus: MerchantSkuSales[];
  /** 区间订单数 */
  orderCount?: number;
  /** 客单价（订单实付合计/订单数） */
  avgOrderValueCents?: number;
  /** 销售件数 */
  itemQtySold?: number;
  /** 件均价（行营收/件数） */
  avgUnitPriceCents?: number;
  /** 上一同等天数窗口营收 */
  prevRevenueCents?: number;
  prevGrossMarginCents?: number;
  /** 营收环比 %；上一窗为 0 且本期有值时为 null */
  revenueChangePct?: number | null;
  marginChangePct?: number | null;
  /** 当前缺货且窗口内有销量的 SKU 数 */
  stockoutSkuCount?: number;
  /** 缺货损失毛利估算（分） */
  stockoutLossEstimateCents?: number;
}

export interface MerchantSettlementOverview {
  pendingAmountCents: number;
  pendingSplitCount: number;
  settledMonthCents: number;
  failedSplitCount: number;
  profitSharing?: ProfitSharingStatus;
  recentFailures?: RevenueSplit[];
}

export interface MerchantDailySettlement {
  date: string;
  orderCount: number;
  grossCents: number;
  platformCents: number;
  merchantCents: number;
  settledCents: number;
  pendingCents: number;
  failedCount: number;
}

export interface MerchantSettlementBatch {
  batchNo: string;
  merchantId: string;
  merchantName?: string;
  settleAfter?: string;
  settledAt?: string;
  orderCount: number;
  grossCents: number;
  platformCents: number;
  merchantCents: number;
  settledCents: number;
  pendingCents: number;
  failedCount: number;
  batchStatus: string;
}

export interface AccountDto {
  userId?: string | number;
  phoneNumber?: string;
  balanceCents: number;
  /** 开门预授权等冻结金额（分）；与后端 AccountDto 同源 */
  frozenCents: number;
  /** 可用余额 = balance - frozen */
  availableCents: number;
  verified?: boolean;
  operator?: boolean;
  payPreferredChannel?: string;
  payscoreEnabled?: boolean;
  alipayAgreementEnabled?: boolean;
  passwordFreeReady?: boolean;
  realName?: string;
}

export interface BalanceTransactionDto {
  transactionId: string;
  userId: string | number;
  businessType: string;
  businessId: string;
  amountCents: number;
  balanceBeforeCents: number;
  balanceAfterCents: number;
  reason?: string;
  createdAt: string;
}

export interface RechargePrepayResponse {
  channel: string;
  orderId: string;
  wxPay?: Record<string, unknown>;
  alipayPay?: AlipayPayParams;
  debugInfo?: Record<string, string>;
}

export interface AlipayPayParams {
  orderId?: string;
  tradeNo?: string;
  payUrl?: string;
  payFormHtml?: string;
}

export interface RechargeOrderDto {
  orderId: string;
  userId: string | number;
  amountCents: number;
  channel: string;
  status: 'PENDING' | 'PAID' | 'CANCELLED' | 'REFUNDED';
  createdAt?: string;
  paidAt?: string;
}

export interface BalanceRefundRequestDto {
  requestId: number;
  requestNo: string;
  userId: number;
  amountCents: number;
  status: string;
  reason?: string;
  reviewRemark?: string;
  reviewerId?: number;
  reviewedAt?: string;
  failReason?: string;
  createdAt?: string;
  updatedAt?: string;
  refundedAt?: string;
}

export interface ApplyBalanceRefundRequest {
  amountCents: number;
  reason?: string;
}

export interface VerifyIdentityRequest {
  realName: string;
  idCardLast4: string;
}

export interface PayContractDto {
  channel: string;
  active: boolean;
  contractId: string;
  message: string;
  /** 生产支付宝签约：待异步回调激活 */
  pending?: boolean;
  /** 支付宝内自动提交的签约表单 HTML */
  signFormHtml?: string;
}

export interface DeviceStatusDto {
  deviceId: string;
  deviceName?: string;
  onlineStatus?: string;
  online?: boolean;
  available?: boolean;
  busy?: boolean;
  replenishmentMode?: boolean;
  activeSessionId?: string | null;
  activeSessionState?: string | null;
  /** NONE | SESSION | REPLENISHMENT | LOCKED */
  busyReason?: string;
  message?: string;
  /** 本柜开门预授权门槛（分），与后端 DeviceStatusDto.preauthCents 一致 */
  preauthCents?: number;
}

/** 运营工作台指标（与 OpsWorkbenchDto 对齐） */
export interface OpsWorkbench {
  openDisputes?: number;
  overdueDisputes?: number;
  offlineDevices?: number;
  waitingUploads?: number;
  lowStockItems?: number;
  pendingReplenishments?: number;
  staleSessions?: number;
  reconciliationMismatches?: number;
  splitExceptions?: number;
  inTransitOverdue?: number;
  devicesOnSale?: number;
  devicesSalesLocked?: number;
  pendingUnpaidOrders?: number;
  actionItems?: Array<{
    type: string;
    severity?: string;
    title: string;
    detail?: string;
    deviceId?: string;
    sessionId?: string;
    ticketId?: string;
    skuId?: string;
    taskId?: number | string;
    createdAt?: string;
    dueAt?: string;
  }>;
}

/** 默认开门预授权（分）= ¥20，与 CabinetConstants.MIN_BALANCE_CENTS 一致 */
export const DEFAULT_PREAUTH_CENTS = 2000;

export interface DeviceProduct {
  skuId: string;
  skuName: string;
  priceCents: number;
  quantity?: number;
  category?: string;
  imageUrl?: string;
}

export interface FileDisputeRequest {
  sessionId: string;
  reason: string;
  category?: string;
  priority?: string;
  evidenceFileIds?: number[];
}

export interface FileAttachmentDto {
  fileId: number;
  fileName?: string;
  contentType?: string;
  fileSize?: number;
  url?: string;
}

export interface OrderRefundRequest {
  reason: string;
  evidenceFileIds?: number[];
  /** true=退货退款回库；false=仅退款不回库；省略则由服务端推断 */
  restoreInventory?: boolean;
  /** 按行部分退（运营）；空=全额退 */
  lines?: Array<{
    skuId: string;
    quantity: number;
    restoreInventory?: boolean;
  }>;
}

export interface OrderRefundResultDto {
  orderId: string;
  sessionId?: string;
  ticketId?: string;
  status: string;
  refundedCents: number;
  payChannel?: string;
  message?: string;
  inventoryRestored?: boolean;
  partial?: boolean;
}

export interface DeviceFaultReportRequest {
  issueType: string;
  description?: string;
}

export interface SubmitFeedbackRequest {
  feedbackType: string;
  content: string;
  contactInfo?: string;
  deviceId?: string;
  sessionId?: string;
  rating?: number;
}

export interface UserFeedbackDto {
  feedbackId: number;
  userId: number;
  feedbackType: string;
  content: string;
  contactInfo?: string;
  deviceId?: string;
  sessionId?: string;
  rating?: number;
  status: string;
  handlerId?: number;
  reply?: string;
  handledAt?: string;
  createdAt?: string;
}

export interface AnnouncementDto {
  announceId: number;
  title: string;
  content: string;
  announceType?: string;
  targetScope?: string;
  priority?: string;
  publishAt?: string;
  expireAt?: string;
}

export interface DisputeTicketDto {
  ticketId: string;
  sessionId?: string;
  deviceId?: string;
  reason?: string;
  status: string;
  createdAt?: string;
  resolvedAt?: string;
  closedAt?: string;
  orderId?: string;
  billedAmountCents?: number;
  /** 关联订单累计已退（分） */
  refundedAmountCents?: number;
  suggestedItems?: OrderLineDto[];
  resolutionItems?: OrderLineDto[];
  category?: string;
  priority?: string;
  operatorNote?: string;
  slaOverdue?: boolean;
  slaHoursRemaining?: number;
  evidence?: FileAttachmentDto[];
  /** LOW_CONF | EMPTY | UNMAPPED | NEED_REVIEW | WHITELIST */
  reviewCode?: string;
  detectedClasses?: string[];
}

export interface SessionCartRequest {
  items: { skuId: string; qty: number }[];
}

/** 开门中第三方识别实时购物车（仅展示） */
export interface LiveCartDto {
  sessionId: string;
  items: Array<{
    skuId: string;
    skuName?: string;
    quantity: number;
    unitPriceCents: number;
    lineAmountCents: number;
  }>;
  totalQty: number;
  totalAmountCents: number;
}

/** 消费者附近柜机 */
export interface NearbyDeviceDto {
  deviceId: string;
  deviceName?: string;
  address?: string;
  latitude?: number;
  longitude?: number;
  distanceMeters: number;
  onlineStatus?: string;
  available: boolean;
  sellableSkuCount: number;
  sellableItemCount: number;
  previewSkus?: Array<{
    skuId: string;
    skuName?: string;
    quantity: number;
    unitPriceCents: number;
  }>;
}

export interface SessionDto {
  sessionId: string;
  deviceId: string;
  state: string;
  orderId?: string;
  failureReason?: string;
  failReason?: string;
  createdAt?: string;
  updatedAt?: string;
  /** 关门时间（识别计时起点） */
  closeTime?: string;
  openTime?: string;
  videoUri?: string;
  uploadStatus?: string;
  videoPreviewUrl?: string;
  sessionKind?: string;
  entryChannel?: string;
  payChannel?: string;
  preauthCents?: number;
  preauthStatus?: string;
  shoppingDurationMs?: number;
  recognitionDurationMs?: number;
}

export interface OrderLineDto {
  skuId: string;
  skuName?: string;
  quantity: number;
  unitPriceCents: number;
  lineAmountCents: number;
  batchNo?: string;
  slotId?: string;
}

export interface OrderDetailDto {
  orderId: string;
  sessionId?: string;
  deviceId?: string;
  merchantId?: string;
  status: string;
  payChannel?: string;
  payTime?: string;
  /** 柜机购物视频地址（可空） */
  videoUri?: string;
  paymentOperationId?: string;
  payTradeNo?: string;
  balanceBeforeCents?: number;
  balanceAfterCents?: number;
  totalAmountCents: number;
  couponDiscountCents?: number;
  memberDiscountCents?: number;
  originalAmountCents?: number;
  lines?: OrderLineDto[];
  createdAt?: string;
  refundedAt?: string;
  refundedCents?: number;
  inventoryDeducted?: boolean;
  /** 柜机生效退款策略：AUTO_REFUND | DISPUTE_ONLY */
  refundPolicy?: string;
}
