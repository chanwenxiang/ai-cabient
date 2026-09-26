/**
 * 消费端小程序高频端点集中定义（debt-tracker C2）。
 * 新调用优先从此处取路径；pages/composables 禁止再散落试点字面量。
 * 与后端 `ApiVersions.PATH_PREFIX` 对齐（当前 v2）。
 *
 * ## 前缀约定
 * | 前缀 | 用途 |
 * |------|------|
 * | `/api/v2/auth/*` | 登录登出（见 `AuthEndpoints`） |
 * | `/api/v2/sessions/*` | 开门会话 / 购物车 |
 * | `/api/v2/orders/*` | 订单 / 支付 / 视频 |
 * | `/api/v2/devices/*` | 柜机状态 / 商品 / 广告 |
 * | `/api/v2/account*`、`/payment/*` | 账户 / 充值 |
 * | `/api/v2/disputes*` | 消费者争议 |
 * | `/api/v2/member/*` | 会员 / 积分 / 通知 |
 * | `/api/v2/marketing/*`、`/coupons*`、`/announcements*` | 营销 |
 * | `/api/v2/public/*` | 无鉴权公开配置 |
 * | `/api/v2/dicts/*` | 运行时字典 |
 *
 * 门禁：`scripts/check-consumer-endpoints.mjs` 扫 pages/composables + `CONSUMER_ENDPOINT_PILOT_LITERALS`。
 * `utils/consumer-api.ts`：C2b 已迁 auth/account/payment/orders/sessions/disputes；
 * C2c 已迁 devices/account-invoices/public/feedback/member/marketing/coupons/announcements；余 → C2d。
 */
export const API_PREFIX = '/api/v2' as const;

/** 鉴权相关（与 ConsumerEndpoints 并列）。 */
export const AuthEndpoints = {
  serverBoot: `${API_PREFIX}/auth/server-boot`,
  passwordLogin: `${API_PREFIX}/auth/password-login`,
  login: `${API_PREFIX}/auth/login`,
  wxLogin: `${API_PREFIX}/auth/wx-login`,
  alipayLogin: `${API_PREFIX}/auth/alipay/login`,
  wxH5Login: `${API_PREFIX}/auth/wx-h5-login`,
  captcha: `${API_PREFIX}/auth/captcha`,
  smsCode: (query: string) => `${API_PREFIX}/auth/sms-code?${query}`,
  logout: `${API_PREFIX}/auth/logout`
} as const;

export const ConsumerEndpoints = {
  /** 订单购物视频（旁路 fetch / download，禁止页内裸拼） */
  orderVideo: (orderId: string) => `${API_PREFIX}/orders/${encodeURIComponent(orderId)}/video`,
  /** 运行时字典 */
  dictsRuntime: `${API_PREFIX}/dicts/runtime`,
  /** 账户 / 充值 */
  account: `${API_PREFIX}/account`,
  accountVerify: `${API_PREFIX}/account/verify`,
  accountTransactions: (page: number, size: number) =>
    `${API_PREFIX}/account/transactions?page=${page}&size=${size}`,
  payscoreSign: `${API_PREFIX}/account/payscore/sign`,
  alipayAgreementSign: `${API_PREFIX}/account/alipay-agreement/sign`,
  payContractUnsign: `${API_PREFIX}/account/pay-contract/unsign`,
  payPreferred: `${API_PREFIX}/account/pay-preferred`,
  balanceRefunds: `${API_PREFIX}/account/balance-refunds`,
  rechargePrepay: `${API_PREFIX}/payment/recharge/prepay`,
  rechargeOrder: (orderId: string) =>
    `${API_PREFIX}/payment/recharge/${encodeURIComponent(orderId)}`,
  rechargeCancel: (orderId: string) =>
    `${API_PREFIX}/payment/recharge/${encodeURIComponent(orderId)}/cancel`,
  recharges: (page: number, size: number) =>
    `${API_PREFIX}/payment/recharges?page=${page}&size=${size}`,
  mockRechargeSuccess: (orderId: string) =>
    `${API_PREFIX}/dev/payment/recharge/${encodeURIComponent(orderId)}/mock-success`,
  /** 会话 */
  sessions: `${API_PREFIX}/sessions`,
  sessionsActive: `${API_PREFIX}/sessions/active`,
  session: (sessionId: string) => `${API_PREFIX}/sessions/${sessionId}`,
  sessionCancel: (sessionId: string) => `${API_PREFIX}/sessions/${sessionId}/cancel`,
  sessionCart: (sessionId: string) => `${API_PREFIX}/sessions/${sessionId}/cart`,
  sessionDemoClose: (sessionId: string) => `${API_PREFIX}/sessions/${sessionId}/demo-close`,
  sessionOrder: (sessionId: string) => `${API_PREFIX}/sessions/${sessionId}/order`,
  sessionLiveCart: (sessionId: string) =>
    `${API_PREFIX}/sessions/${encodeURIComponent(sessionId)}/live-cart`,
  /** 订单 */
  orders: (page: number, size: number) => `${API_PREFIX}/orders?page=${page}&size=${size}`,
  ordersPendingCount: `${API_PREFIX}/orders/pending-count`,
  orderDetail: (orderId: string) => `${API_PREFIX}/orders/${orderId}`,
  orderPay: (orderId: string) => `${API_PREFIX}/orders/${encodeURIComponent(orderId)}/pay`,
  orderRefund: (orderId: string) => `${API_PREFIX}/orders/${encodeURIComponent(orderId)}/refund`,
  orderInvoice: (orderId: string) => `${API_PREFIX}/orders/${encodeURIComponent(orderId)}/invoice`,
  /** 争议 */
  disputes: `${API_PREFIX}/disputes`,
  disputesEvidence: `${API_PREFIX}/disputes/evidence`,
  disputesMine: `${API_PREFIX}/disputes/mine`,
  disputesMineDetail: (query?: string) =>
    query ? `${API_PREFIX}/disputes/mine/detail?${query}` : `${API_PREFIX}/disputes/mine/detail`,
  /** 柜机 */
  deviceStatus: (deviceId: string) =>
    `${API_PREFIX}/devices/${encodeURIComponent(deviceId)}/status`,
  deviceProducts: (deviceId: string) =>
    `${API_PREFIX}/devices/${encodeURIComponent(deviceId)}/products`,
  deviceScreenContent: (deviceId: string) =>
    `${API_PREFIX}/devices/${encodeURIComponent(deviceId)}/screen-content`,
  deviceAdPlay: (deviceId: string) =>
    `${API_PREFIX}/devices/${encodeURIComponent(deviceId)}/ad-play`,
  deviceFaultReport: (deviceId: string) =>
    `${API_PREFIX}/devices/${encodeURIComponent(deviceId)}/fault-report`,
  /** 账户发票 */
  accountInvoices: `${API_PREFIX}/account/invoices`,
  /** 公开配置（无鉴权） */
  publicConsumerConfig: `${API_PREFIX}/public/consumer-config`,
  /** 反馈 */
  feedback: `${API_PREFIX}/feedback`,
  feedbackMine: `${API_PREFIX}/feedback/mine`,
  /** 会员 / 积分 / 通知 */
  memberProfile: `${API_PREFIX}/member/profile`,
  memberPoints: `${API_PREFIX}/member/points`,
  memberPointsLog: (limit: number) => `${API_PREFIX}/member/points/log?limit=${limit}`,
  memberRedeemItems: `${API_PREFIX}/member/redeem/items`,
  memberRedeem: `${API_PREFIX}/member/redeem`,
  memberNotifications: (limit: number) => `${API_PREFIX}/member/notifications?limit=${limit}`,
  memberNotificationsUnreadCount: `${API_PREFIX}/member/notifications/unread-count`,
  memberNotificationRead: (id: number) => `${API_PREFIX}/member/notifications/${id}/read`,
  memberNotificationsReadAll: `${API_PREFIX}/member/notifications/read-all`,
  memberNotificationPrefs: `${API_PREFIX}/member/notifications/prefs`,
  /** 营销 */
  marketingBanners: `${API_PREFIX}/marketing/banners`,
  marketingCampaignsActive: `${API_PREFIX}/marketing/campaigns/active`,
  marketingCampaignClaim: (activityId: number) =>
    `${API_PREFIX}/marketing/campaigns/${activityId}/claim`,
  /** 券 */
  coupons: (status?: string) =>
    status ? `${API_PREFIX}/coupons?status=${encodeURIComponent(status)}` : `${API_PREFIX}/coupons`,
  couponsCount: `${API_PREFIX}/coupons/count`,
  couponsUnused: `${API_PREFIX}/coupons?status=UNUSED`,
  /** 公告 */
  announcements: `${API_PREFIX}/announcements`,
  announcement: (id: number) => `${API_PREFIX}/announcements/${id}`
} as const;

/**
 * 试点：pages/composables 不得再出现这些字面量前缀。
 * 扩表时同步把对应路径迁入 ConsumerEndpoints（见 C2b）。
 */
export const CONSUMER_ENDPOINT_PILOT_LITERALS = [
  '/api/v2/orders',
  '/api/v2/sessions',
  '/api/v2/devices/',
  '/api/v2/auth/',
  '/api/v2/account',
  '/api/v2/disputes',
  '/api/v2/payment/recharge',
  '/api/v2/coupons',
  '/api/v2/member/',
  '/api/v2/public/consumer-config',
  '/api/v2/dicts/runtime',
  '/api/v2/marketing/',
  '/api/v2/feedback',
  '/api/v2/announcements'
] as const;
