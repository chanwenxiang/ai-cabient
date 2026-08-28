import type { LoginResponse } from '@aicabinet/shared-types';
import { clearDictOverrides } from '@aicabinet/shared-dict';
import { localizeApiMessage } from '@aicabinet/shared-uni/format';
import { parseQuery, queryGet } from '@aicabinet/shared-uni/query';
import { loadRuntimeDict as sharedLoadRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
import {
  formatMpRequestError,
  mpRequest,
  refreshTokenSilently as sharedRefreshToken,
  type MpApiSession
} from '@aicabinet/shared-uni/request';
import { API_BASE_URL } from '@/config/api';
import { isDevBuild } from '@/utils/runtime-flags';
import { secureRandomToken } from '@/utils/secure-id';

const BASE_URL = API_BASE_URL;

function formatRequestError(errMsg: string | undefined, path: string) {
  return formatMpRequestError(errMsg, path, isDevBuild, BASE_URL);
}
const TOKEN_KEY = 'consumer_token';
const USER_KEY = 'consumer_user_id';
const EXPIRES_KEY = 'consumer_token_expires';
const OPEN_ATTEMPT_KEY = 'consumer_open_attempt';
/** 用户主动退出后禁止静默微信建档，直到再次点登录 */
const SKIP_SILENT_AUTH_KEY = 'consumer_skip_silent_auth';
const REQUEST_TIMEOUT_MS = 12_000;

const mpApiSession: MpApiSession = {
  baseUrl: BASE_URL,
  isDevBuild,
  timeoutMs: REQUEST_TIMEOUT_MS,
  getToken: getConsumerToken,
  clearSession: clearConsumerSession,
  applyRefreshedToken: (data) => applyTokenSession(data as LoginResponse),
  handleUnauthorized: (message) => {
    clearConsumerSession();
    return new Error(localizeApiMessage(message, '登录已失效'));
  }
};

export function getConsumerToken() {
  return uni.getStorageSync(TOKEN_KEY) || '';
}

export function clearConsumerSession() {
  uni.removeStorageSync(TOKEN_KEY);
  uni.removeStorageSync(USER_KEY);
  uni.removeStorageSync(EXPIRES_KEY);
  uni.removeStorageSync('consumer_server_boot');
  uni.removeStorageSync('active_session_id');
  uni.removeStorageSync(OPEN_ATTEMPT_KEY);
  clearDictOverrides();
}

type OpenAttempt = { deviceId: string; idempotencyKey: string; createdAt: number };

function isOpenAttempt(value: unknown): value is OpenAttempt {
  if (!value || typeof value !== 'object') return false;
  const row = value as OpenAttempt;
  return typeof row.deviceId === 'string' && typeof row.idempotencyKey === 'string';
}

function randomId() {
  return `${Date.now().toString(36)}-${secureRandomToken(6)}-${secureRandomToken(6)}`;
}

export function getOrCreateOpenAttempt(deviceId: string): OpenAttempt {
  const normalized = deviceId.trim().toUpperCase();
  const saved = uni.getStorageSync(OPEN_ATTEMPT_KEY);
  if (isOpenAttempt(saved) && saved.deviceId === normalized && saved.idempotencyKey) return saved;
  const attempt = {
    deviceId: normalized,
    idempotencyKey: `consumer-open-${randomId()}`,
    createdAt: Date.now()
  };
  uni.setStorageSync(OPEN_ATTEMPT_KEY, attempt);
  return attempt;
}

export function clearOpenAttempt() {
  uni.removeStorageSync(OPEN_ATTEMPT_KEY);
}

function applyTokenSession(data: LoginResponse) {
  uni.removeStorageSync(SKIP_SILENT_AUTH_KEY);
  uni.setStorageSync(TOKEN_KEY, data.token);
  uni.setStorageSync(USER_KEY, data.userId);
  const ms = (data.expiresInSeconds ?? 1800) * 1000;
  uni.setStorageSync(EXPIRES_KEY, String(Date.now() + ms));
  if (data.serverBootEpoch != null) {
    uni.setStorageSync('consumer_server_boot', data.serverBootEpoch);
  }
  void sharedLoadRuntimeDict({
    getToken: getConsumerToken,
    fetchRuntime: () => request('/api/v2/dicts/runtime', 'GET')
  });
}

async function refreshTokenSilently(): Promise<boolean> {
  return sharedRefreshToken(mpApiSession);
}

/** Thin wrappers for pages that expect `{ data }` like axios-style clients. */
export async function get<T = unknown>(path: string, auth = true) {
  return { data: await request<T>(path, 'GET', undefined, auth) };
}

export async function post<T = unknown>(path: string, data?: unknown, auth = true) {
  return { data: await request<T>(path, 'POST', data, auth) };
}

export function request<T>(
  path: string,
  method: UniApp.RequestOptions['method'] = 'GET',
  data?: unknown,
  auth = true,
  retried = false
): Promise<T> {
  return mpRequest<T>(mpApiSession, path, method, data, auth, retried);
}

export function uploadDisputeEvidenceFile(
  filePath: string
): Promise<import('@aicabinet/shared-types').FileAttachmentDto> {
  return new Promise((resolve, reject) => {
    if (!getConsumerToken()) {
      reject(new Error('请先登录'));
      return;
    }
    uni.uploadFile({
      url: BASE_URL + '/api/v2/disputes/evidence',
      filePath,
      name: 'file',
      header: { Authorization: 'Bearer ' + getConsumerToken() },
      timeout: 30_000,
      success(res) {
        try {
          const body = JSON.parse(String(res.data || '{}')) as {
            code?: number;
            message?: string;
            data?: import('@aicabinet/shared-types').FileAttachmentDto;
          };
          if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0 && body.data) {
            resolve(body.data);
            return;
          }
          reject(new Error(localizeApiMessage(body?.message, `上传失败 (${res.statusCode})`)));
        } catch {
          reject(new Error('上传响应解析失败'));
        }
      },
      fail(err) {
        reject(new Error(formatRequestError(err.errMsg, '/api/v2/disputes/evidence')));
      }
    });
  });
}

export async function bootstrapConsumerSession() {
  if (!getConsumerToken()) return false;
  let bootEpoch: number | string | undefined;
  try {
    const boot = await request<{ serverBootEpoch?: number }>(
      '/api/v2/auth/server-boot',
      'GET',
      undefined,
      false
    );
    bootEpoch = boot.serverBootEpoch;
  } catch {
    // 仅网关/服务短暂不可达：不清会话（不等于服务已重启）
    return !!getConsumerToken();
  }

  const saved = uni.getStorageSync('consumer_server_boot');
  // 服务重启会换 boot epoch → 必须清会话，要求重新登录
  if (saved !== '' && saved != null && bootEpoch != null && String(saved) !== String(bootEpoch)) {
    clearConsumerSession();
    return false;
  }

  try {
    const ok = await refreshTokenSilently();
    if (ok && bootEpoch != null) {
      uni.setStorageSync('consumer_server_boot', bootEpoch);
    }
    return ok;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const authFail = msg.includes('登录已失效') || /401|403/.test(msg);
    // token 过期/鉴权失败清会话；瞬时网络错误保留本地 token
    if (authFail) {
      clearConsumerSession();
      return false;
    }
    return !!getConsumerToken();
  }
}

export function consumerPasswordLogin(phone: string, password: string) {
  return request<LoginResponse>(
    '/api/v2/auth/password-login',
    'POST',
    { phoneNumber: phone, password },
    false
  ).then((data) => {
    applyTokenSession(data);
    return data;
  });
}

export function consumerSmsLogin(phone: string, code: string) {
  return request<LoginResponse>(
    '/api/v2/auth/login',
    'POST',
    { phoneNumber: phone, code },
    false
  ).then((data) => {
    applyTokenSession(data);
    return data;
  });
}

export function consumerWxLogin(code: string, phoneNumber?: string) {
  return request<LoginResponse>(
    '/api/v2/auth/wx-login',
    'POST',
    { code, phoneNumber: phoneNumber || undefined },
    false
  ).then((data) => {
    applyTokenSession(data);
    return data;
  });
}

export function consumerAlipayLogin(authCode: string) {
  return request<LoginResponse>('/api/v2/auth/alipay/login', 'POST', { authCode }, false).then(
    (data) => {
      applyTokenSession(data);
      return data;
    }
  );
}

/** H5 微信网页授权登录（公众号 OAuth code）。 */
export function consumerWxH5Login(code: string) {
  return request<LoginResponse>('/api/v2/auth/wx-h5-login', 'POST', { code }, false).then(
    (data) => {
      applyTokenSession(data);
      return data;
    }
  );
}

function readQueryParam(name: string): string {
  try {
    if (typeof globalThis === 'undefined') return '';
    const fromSearch = queryGet(globalThis.location.search, name);
    if (fromSearch) return fromSearch;
    const hash = globalThis.location.hash || '';
    const q = hash.includes('?') ? hash.split('?')[1] : '';
    if (q) return queryGet(q, name);
  } catch {
    /* ignore */
  }
  return '';
}

function stripAuthCodeFromUrl() {
  try {
    if (typeof globalThis === 'undefined' || !globalThis.history?.replaceState) return;
    const url = new URL(globalThis.location.href);
    url.searchParams.delete('auth_code');
    url.searchParams.delete('authCode');
    url.searchParams.delete('app_id');
    url.searchParams.delete('source');
    url.searchParams.delete('code');
    url.searchParams.delete('state');
    if (url.hash.includes('?')) {
      const [path, qs] = url.hash.split('?');
      const sp = parseQuery(qs);
      delete sp.auth_code;
      delete sp.authCode;
      delete sp.app_id;
      delete sp.source;
      delete sp.code;
      delete sp.state;
      const next = Object.entries(sp)
        .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
        .join('&');
      url.hash = next ? `${path}?${next}` : path;
    }
    globalThis.history.replaceState({}, '', url.toString());
  } catch {
    /* ignore */
  }
}

function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success(res) {
        if (res.code) resolve(res.code);
        else reject(new Error('微信授权失败'));
      },
      fail(err) {
        reject(new Error(err.errMsg || '微信授权失败'));
      }
    });
  });
}

export function markConsumerExplicitLogout() {
  uni.setStorageSync(SKIP_SILENT_AUTH_KEY, '1');
}

function shouldSkipSilentAuth() {
  return uni.getStorageSync(SKIP_SILENT_AUTH_KEY) === '1';
}

/** 竞品式静默登录：扫码进小程序即完成微信建档，无需先填手机号。主动退出后不再静默重建。 */
export async function ensureConsumerAuth(opts?: { force?: boolean }): Promise<boolean> {
  if (getConsumerToken()) {
    const ok = await bootstrapConsumerSession();
    if (ok) return true;
    // bootstrap clears stale token; fall through to silent wx login on MP.
  }
  // #ifdef H5
  try {
    const channel = (
      readQueryParam('channel') ||
      readQueryParam('entryChannel') ||
      ''
    ).toUpperCase();
    const authCode = readQueryParam('auth_code') || readQueryParam('authCode');
    if (authCode) {
      await consumerAlipayLogin(authCode);
      stripAuthCodeFromUrl();
      return true;
    }
    // 微信网页授权回跳：state=wechat + code
    const state = (readQueryParam('state') || '').toLowerCase();
    const wxCode = readQueryParam('code');
    if (wxCode && (state === 'wechat' || channel === 'WECHAT')) {
      await consumerWxH5Login(wxCode);
      stripAuthCodeFromUrl();
      return true;
    }
    if (channel === 'ALIPAY') {
      // 生产 H5 不允许 mock 建档：真实渠道必须走支付宝授权回跳（authCode）
      if (!isDevBuild) return false;
      // mock / 无授权回跳时：用稳定本地标识完成建档，便于联调开门
      const mockId = uni.getStorageSync('mock_alipay_user_id') || `mock_h5_${Date.now()}`;
      uni.setStorageSync('mock_alipay_user_id', mockId);
      await consumerAlipayLogin(String(mockId));
      return true;
    }
  } catch {
    return false;
  }
  return false;
  // #endif
  // #ifdef MP-WEIXIN
  if (!opts?.force && shouldSkipSilentAuth()) return false;
  try {
    const code = await wxLoginCode();
    await consumerWxLogin(code);
    return true;
  } catch {
    return false;
  }
  // #endif
  // #ifndef MP-WEIXIN
  // #ifndef H5
  return false;
  // #endif
  // #endif
}

function currentPagePath(): string {
  try {
    const pages = getCurrentPages();
    const cur = pages[pages.length - 1] as
      { route?: string; options?: Record<string, string> } | undefined;
    if (!cur?.route) return '/pages/index/index';
    const base = '/' + cur.route;
    const opts = cur.options || {};
    const qs = Object.keys(opts)
      .filter((k) => opts[k] != null && opts[k] !== '')
      .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(String(opts[k]))}`)
      .join('&');
    return qs ? `${base}?${qs}` : base;
  } catch {
    return '/pages/index/index';
  }
}

export function requireConsumerAuth(
  message = '请先完成微信授权',
  redirect?: string
): Promise<boolean> {
  return ensureConsumerAuth().then((ok) => {
    if (!ok) {
      const target = redirect || currentPagePath();
      uni.showModal({
        title: '需要授权',
        content: message,
        confirmText: '去验证',
        success(res) {
          if (res.confirm) {
            uni.navigateTo({
              url: '/pages/login/login?redirect=' + encodeURIComponent(target)
            });
          }
        }
      });
    }
    return ok;
  });
}

export function sendSmsCode(phone: string) {
  return request<void>(
    `/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(phone)}`,
    'POST',
    null,
    false
  );
}

export const consumerApi = {
  account: () => request<import('@aicabinet/shared-types').AccountDto>('/api/v2/account'),
  createRechargePrepay: (
    channel: 'WECHAT' | 'ALIPAY',
    amountCents: number,
    idempotencyKey: string
  ) =>
    request<import('@aicabinet/shared-types').RechargePrepayResponse>(
      '/api/v2/payment/recharge/prepay',
      'POST',
      {
        channel,
        amountCents,
        idempotencyKey
      }
    ),
  getRechargeOrder: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      `/api/v2/payment/recharge/${encodeURIComponent(orderId)}`
    ),
  createMockRecharge: (amountCents: number, idempotencyKey: string) =>
    request<import('@aicabinet/shared-types').RechargePrepayResponse>(
      '/api/v2/payment/recharge/prepay',
      'POST',
      {
        channel: 'WECHAT',
        amountCents,
        idempotencyKey
      }
    ),
  confirmMockRecharge: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      `/api/v2/payment/recharge/${encodeURIComponent(orderId)}/mock-success`,
      'POST'
    ),
  cancelRecharge: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      `/api/v2/payment/recharge/${encodeURIComponent(orderId)}/cancel`,
      'POST'
    ),
  balanceTransactions: (page = 0, size = 20) =>
    request<
      import('@aicabinet/shared-types').PageResult<
        import('@aicabinet/shared-types').BalanceTransactionDto
      >
    >(`/api/v2/account/transactions?page=${page}&size=${size}`),
  verifyIdentity: (body: import('@aicabinet/shared-types').VerifyIdentityRequest) =>
    request<import('@aicabinet/shared-types').AccountDto>('/api/v2/account/verify', 'POST', body),
  signPayScore: () =>
    request<import('@aicabinet/shared-types').PayContractDto>(
      '/api/v2/account/payscore/sign',
      'POST'
    ),
  signAlipayAgreement: () =>
    request<import('@aicabinet/shared-types').PayContractDto>(
      '/api/v2/account/alipay-agreement/sign',
      'POST'
    ),
  setPayPreferred: (channel: 'BALANCE' | 'WECHAT' | 'ALIPAY') =>
    request<import('@aicabinet/shared-types').AccountDto>('/api/v2/account/pay-preferred', 'PUT', {
      channel
    }),
  listBalanceRefunds: () =>
    request<import('@aicabinet/shared-types').BalanceRefundRequestDto[]>(
      '/api/v2/account/balance-refunds'
    ),
  applyBalanceRefund: (amountCents: number, reason?: string) =>
    request<import('@aicabinet/shared-types').BalanceRefundRequestDto>(
      '/api/v2/account/balance-refunds',
      'POST',
      { amountCents, reason }
    ),
  deviceStatus: (deviceId: string) =>
    request<import('@aicabinet/shared-types').DeviceStatusDto>(
      `/api/v2/devices/${encodeURIComponent(deviceId)}/status`
    ),
  deviceProducts: (deviceId: string) =>
    request<import('@aicabinet/shared-types').DeviceProduct[]>(
      `/api/v2/devices/${encodeURIComponent(deviceId)}/products`
    ),
  nearbyDevices: (q: { lat: number; lng: number; radiusKm?: number; limit?: number }) => {
    const radiusKm = q.radiusKm ?? 5;
    const limit = q.limit ?? 20;
    return request<
      Array<{
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
      }>
    >(
      `/api/v2/devices/nearby?lat=${encodeURIComponent(String(q.lat))}&lng=${encodeURIComponent(String(q.lng))}&radiusKm=${radiusKm}&limit=${limit}`
    );
  },
  createSession: async (deviceId: string, entryChannel?: string | null) => {
    const attempt = getOrCreateOpenAttempt(deviceId);
    const body: {
      deviceId: string;
      idempotencyKey: string;
      entryChannel?: string;
      preferredCouponId?: number;
    } = {
      deviceId: attempt.deviceId,
      idempotencyKey: attempt.idempotencyKey
    };
    const channel = String(entryChannel || '')
      .trim()
      .toUpperCase();
    if (channel === 'WECHAT' || channel === 'ALIPAY') {
      body.entryChannel = channel;
    }
    const preferredRaw = uni.getStorageSync('preferred_coupon_id');
    const preferred = Number(preferredRaw);
    if (Number.isFinite(preferred) && preferred > 0) {
      body.preferredCouponId = preferred;
    }
    try {
      return await request<import('@aicabinet/shared-types').SessionDto>(
        '/api/v2/sessions',
        'POST',
        body
      );
    } catch (firstError) {
      await new Promise((resolve) => setTimeout(resolve, 600));
      try {
        return await request<import('@aicabinet/shared-types').SessionDto>(
          '/api/v2/sessions',
          'POST',
          body
        );
      } catch {
        throw firstError;
      }
    }
  },
  activeSession: () =>
    request<import('@aicabinet/shared-types').SessionDto | null>('/api/v2/sessions/active'),
  getSession: (sessionId: string) =>
    request<import('@aicabinet/shared-types').SessionDto>(`/api/v2/sessions/${sessionId}`),
  cancelSession: (sessionId: string) =>
    request<import('@aicabinet/shared-types').SessionDto>(
      `/api/v2/sessions/${sessionId}/cancel`,
      'POST'
    ),
  updateSessionCart: (
    sessionId: string,
    body: import('@aicabinet/shared-types').SessionCartRequest
  ) =>
    request<import('@aicabinet/shared-types').SessionDto>(
      `/api/v2/sessions/${sessionId}/cart`,
      'PUT',
      body
    ),
  /** 演示关门结算：无柜机硬件时模拟关门（后端 mockEnabled 才放行）。 */
  demoCloseSession: (sessionId: string) =>
    request<import('@aicabinet/shared-types').SessionDto>(
      `/api/v2/sessions/${sessionId}/demo-close`,
      'POST'
    ),
  getSessionOrder: (sessionId: string) =>
    request<import('@aicabinet/shared-types').OrderDetailDto>(
      `/api/v2/sessions/${sessionId}/order`
    ),
  getLiveCart: (sessionId: string) =>
    request<{
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
    }>(`/api/v2/sessions/${encodeURIComponent(sessionId)}/live-cart`),
  listOrders: (page = 0, size = 20) =>
    request<
      import('@aicabinet/shared-types').PageResult<import('@aicabinet/shared-types').OrderSummary>
    >(`/api/v2/orders?page=${page}&size=${size}`),
  getOrder: (orderId: string) =>
    request<import('@aicabinet/shared-types').OrderDetailDto>(`/api/v2/orders/${orderId}`),
  payOrder: (orderId: string) =>
    request<import('@aicabinet/shared-types').OrderDetailDto>(
      `/api/v2/orders/${encodeURIComponent(orderId)}/pay`,
      'POST'
    ),
  fileDispute: (body: import('@aicabinet/shared-types').FileDisputeRequest) =>
    request<import('@aicabinet/shared-types').DisputeTicketDto>('/api/v2/disputes', 'POST', body),
  listMyDisputes: () =>
    request<import('@aicabinet/shared-types').DisputeTicketDto[]>('/api/v2/disputes/mine'),
  getMyDispute: (opts: { ticketId?: string; sessionId?: string }) => {
    const q = [
      opts.ticketId ? `ticketId=${encodeURIComponent(opts.ticketId)}` : '',
      opts.sessionId ? `sessionId=${encodeURIComponent(opts.sessionId)}` : ''
    ]
      .filter(Boolean)
      .join('&');
    const path = q ? `/api/v2/disputes/mine/detail?${q}` : '/api/v2/disputes/mine/detail';
    return request<import('@aicabinet/shared-types').DisputeTicketDto>(path);
  },
  uploadDisputeEvidence: (filePath: string) => uploadDisputeEvidenceFile(filePath),
  refundOrder: (orderId: string, body: import('@aicabinet/shared-types').OrderRefundRequest) =>
    request<import('@aicabinet/shared-types').OrderRefundResultDto>(
      `/api/v2/orders/${encodeURIComponent(orderId)}/refund`,
      'POST',
      body
    ),
  applyInvoice: (orderId: string, body: { title: string; taxNo?: string; email?: string }) =>
    request<{ invoiceId: number; status: string }>(
      `/api/v2/orders/${encodeURIComponent(orderId)}/invoice`,
      'POST',
      body
    ),
  listMyInvoices: () =>
    request<
      Array<{
        invoiceId: number;
        orderId: string;
        title: string;
        amountCents: number;
        status: string;
      }>
    >('/api/v2/account/invoices'),
  consumerPublicConfig: () =>
    request<Record<string, string>>('/api/v2/public/consumer-config', 'GET', null, false),
  reportDeviceFault: (
    deviceId: string,
    body: import('@aicabinet/shared-types').DeviceFaultReportRequest
  ) =>
    request<{ reportId: string; message: string }>(
      `/api/v2/devices/${encodeURIComponent(deviceId)}/fault-report`,
      'POST',
      body
    ),
  submitFeedback: (body: import('@aicabinet/shared-types').SubmitFeedbackRequest) =>
    request<import('@aicabinet/shared-types').UserFeedbackDto>('/api/v2/feedback', 'POST', body),
  listMyFeedback: () =>
    request<import('@aicabinet/shared-types').UserFeedbackDto[]>('/api/v2/feedback/mine'),

  memberProfile: () => request<MemberProfileDto>('/api/v2/member/profile'),
  memberPoints: () => request<MemberPointsSummaryDto>('/api/v2/member/points'),
  memberPointsLog: (limit = 50) =>
    request<MemberPointsLogDto[]>(`/api/v2/member/points/log?limit=${limit}`),
  redeemItems: () => request<PointsRedeemItemDto[]>('/api/v2/member/redeem/items'),
  redeemPoints: (itemId: number) => request<CouponDto>('/api/v2/member/redeem', 'POST', { itemId }),
  notifications: (limit = 50) =>
    request<NotificationDto[]>(`/api/v2/member/notifications?limit=${limit}`),
  notificationUnreadCount: () =>
    request<{ count: number }>('/api/v2/member/notifications/unread-count'),
  markNotificationRead: (id: number) =>
    request<void>(`/api/v2/member/notifications/${id}/read`, 'POST'),
  markAllNotificationsRead: () => request<void>('/api/v2/member/notifications/read-all', 'POST'),
  notifyPrefs: () => request<NotifyPrefDto[]>('/api/v2/member/notifications/prefs'),
  updateNotifyPref: (category: string, enabled: boolean) =>
    request<NotifyPrefDto>('/api/v2/member/notifications/prefs', 'PUT', { category, enabled }),
  marketingBanners: () =>
    request<MarketingBannerDto[]>('/api/v2/marketing/banners', 'GET', undefined, false),
  // auth=true：有 token 时带上，后端可返回「已领取/查看券包」；无 token 仍可游客浏览
  marketingCampaigns: () =>
    request<MarketingCampaignDto[]>('/api/v2/marketing/campaigns/active', 'GET', undefined, true),
  claimCampaign: (activityId: number) =>
    request<CouponDto>(`/api/v2/marketing/campaigns/${activityId}/claim`, 'POST'),
  myCoupons: (status?: string) =>
    request<CouponDto[]>(
      status ? `/api/v2/coupons?status=${encodeURIComponent(status)}` : '/api/v2/coupons'
    ),
  couponCount: () => request<number>('/api/v2/coupons/count'),
  listAnnouncements: () =>
    request<import('@aicabinet/shared-types').AnnouncementDto[]>(
      '/api/v2/announcements',
      'GET',
      undefined,
      false
    ),
  getAnnouncement: (id: number) =>
    request<import('@aicabinet/shared-types').AnnouncementDto>(
      `/api/v2/announcements/${id}`,
      'GET',
      undefined,
      false
    )
};

export type MemberProfileDto = {
  memberId: number;
  userId: number;
  levelCode: string;
  levelName: string;
  totalSpent: number;
  availablePoints: number;
  totalPoints: number;
  orderCount: number;
  spentToNextLevel: number;
  nextLevelName?: string | null;
  progressPercent: number;
  levels: Array<{
    levelCode: string;
    levelName: string;
    minSpent: number;
    maxSpent?: number | null;
    minPoints: number;
    maxPoints?: number | null;
    pointsRate: number;
    sortOrder: number;
  }>;
  createdAt?: string;
};

export type MemberPointsSummaryDto = {
  availablePoints: number;
  totalPoints: number;
  usedPoints: number;
  expiredPoints: number;
  levelCode: string;
  levelName: string;
  pointsRate: number;
  nextLevelPointsGap: number;
};

export type MemberPointsLogDto = {
  id: number;
  points: number;
  pointsType: string;
  sourceType?: string;
  description?: string;
  createdAt: string;
  expireAt?: string | null;
};

export type PointsRedeemItemDto = {
  itemId: number;
  title: string;
  subtitle?: string;
  coverEmoji: string;
  pointsCost: number;
  couponDefId: number;
  couponName?: string;
  stockTotal: number;
  redeemedCount: number;
  availableStock: number;
  sortOrder: number;
  status: string;
  createdAt?: string;
  denominationCents?: number;
  minSpendCents?: number;
  validityDays?: number;
  deviceScope?: string;
};

export type NotificationDto = {
  id: number;
  title: string;
  body: string;
  templateCode?: string;
  channel?: string;
  audience?: string;
  bizType?: string;
  bizId?: string;
  read: boolean;
  readAt?: string | null;
  createdAt: string;
};

export type NotifyPrefDto = {
  category: string;
  label: string;
  enabled: boolean;
};

export type MarketingBannerDto = {
  id: number;
  title: string;
  subtitle?: string;
  tone: string;
  emoji: string;
  campaignId?: number | null;
  ctaPath: string;
};

export type MarketingCampaignDto = {
  id: number;
  title: string;
  description?: string;
  type: string;
  typeLabel: string;
  coverColor: string;
  coverEmoji: string;
  startTime?: string;
  endTime?: string;
  status: string;
  ctaLabel: string;
  ctaPath: string;
  claimed?: boolean | null;
  claimable?: boolean | null;
};

export type CouponDto = {
  couponId: number;
  couponName: string;
  couponType: string;
  denominationCents: number;
  minSpendCents: number;
  status: string;
  expireAt?: string;
  receivedAt?: string;
  usedAt?: string;
  couponCode?: string;
  deviceScope?: string;
  description?: string;
};
