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
 * `utils/consumer-api.ts` 首期不扫，路径迁移按域分批（C2b）。
 */
export const API_PREFIX = '/api/v2' as const;

/** 鉴权相关（与 ConsumerEndpoints 并列）。 */
export const AuthEndpoints = {
  logout: `${API_PREFIX}/auth/logout`
} as const;

export const ConsumerEndpoints = {
  /** 订单购物视频（旁路 fetch / download，禁止页内裸拼） */
  orderVideo: (orderId: string) => `${API_PREFIX}/orders/${encodeURIComponent(orderId)}/video`,
  /** 运行时字典 */
  dictsRuntime: `${API_PREFIX}/dicts/runtime`
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
