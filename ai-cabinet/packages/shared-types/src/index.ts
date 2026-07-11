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
  stockStatus?: string;
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
  failureReason?: string;
  createdAt?: string;
}

export interface OrderSummary {
  orderId: string;
  sessionId?: string;
  userId?: string;
  deviceId?: string;
  totalAmountCents: number;
  status?: string;
  lineCount?: number;
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
  skuName: string;
  priceCents: number;
  status?: string;
}

export interface MerchantSkuPricing {
  deviceId: string;
  deviceName?: string;
  skuId: string;
  skuName: string;
  basePriceCents: number;
  overridePriceCents?: number | null;
  effectivePriceCents: number;
  quantity?: number;
}

export interface MerchantMe {
  userId: string;
  phoneNumber?: string;
  displayName?: string;
  merchants: MerchantDto[];
  permissions: string[];
  canEditPricing?: boolean;
}

export interface MerchantWorkbench {
  openDisputes: number;
  offlineDevices: number;
  lowStockItems: number;
  expiryAlerts: number;
  slotDiscrepancies?: number;
  pendingSplits?: number;
  actionItems: { type: string; title: string; detail?: string; deviceId?: string; ticketId?: string }[];
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

export interface MerchantAnalyticsOverview {
  days: number;
  revenueCents: number;
  cogsCents: number;
  grossMarginCents: number;
  writeOffCostCents: number;
  topSkus: MerchantSkuSales[];
}

export interface MerchantSettlementOverview {
  pendingAmountCents: number;
  pendingSplitCount: number;
  settledMonthCents: number;
  failedSplitCount: number;
  recentFailures?: RevenueSplit[];
}

export interface AccountDto {
  userId?: string | number;
  phoneNumber?: string;
  balanceCents: number;
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
  debugInfo?: Record<string, string>;
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

export interface VerifyIdentityRequest {
  realName: string;
  idCardLast4: string;
}

export interface PayContractDto {
  channel: string;
  active: boolean;
  contractId: string;
  message: string;
}

export interface DeviceStatusDto {
  deviceId: string;
  deviceName?: string;
  onlineStatus?: string;
  available?: boolean;
  busy?: boolean;
  replenishmentMode?: boolean;
  message?: string;
}

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
}

export interface DeviceFaultReportRequest {
  issueType: string;
  description?: string;
}

export interface DisputeTicketDto {
  ticketId: string;
  sessionId?: string;
  deviceId?: string;
  reason?: string;
  status: string;
  createdAt?: string;
  resolvedAt?: string;
  orderId?: string;
  billedAmountCents?: number;
  category?: string;
  priority?: string;
}

export interface SessionCartRequest {
  items: { skuId: string; qty: number }[];
}

export interface SessionDto {
  sessionId: string;
  deviceId: string;
  state: string;
  orderId?: string;
  failureReason?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface OrderLineDto {
  skuId: string;
  skuName?: string;
  quantity: number;
  unitPriceCents: number;
  lineAmountCents: number;
}

export interface OrderDetailDto {
  orderId: string;
  sessionId?: string;
  deviceId?: string;
  status: string;
  payChannel?: string;
  paymentOperationId?: string;
  balanceBeforeCents?: number;
  balanceAfterCents?: number;
  totalAmountCents: number;
  lines?: OrderLineDto[];
  createdAt?: string;
}
