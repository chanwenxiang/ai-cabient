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
 * `utils/merchant-api.ts` 首期不扫，路径迁移按域分批（M3b）。
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
  dictsRuntime: `${API_PREFIX}/dicts/runtime`
} as const;

/**
 * 试点：pages/composables 不得再出现这些字面量前缀。
 * 扩表时同步把对应路径迁入 MerchantEndpoints（见 M3b）。
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
  '/api/v2/merchant/pricing/'
] as const;
