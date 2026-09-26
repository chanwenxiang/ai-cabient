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
 * `utils/merchant-api.ts` 按域分批迁入（M3b 已迁 me/stats/devices/证据/导出；余 → M3c）。
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
  /** 商户 me / 工作台 */
  me: `${API_PREFIX}/merchant/me`,
  stats: `${API_PREFIX}/merchant/stats`,
  devices: `${API_PREFIX}/merchant/devices`,
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
