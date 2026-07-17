import type { LoginResponse } from '@aicabinet/shared-types';
import { API_BASE_URL } from '@/config/api';

const BASE_URL = API_BASE_URL;

function formatRequestError(errMsg: string | undefined, path: string) {
  const raw = errMsg || '网络错误';
  if (raw === 'request:fail' || raw.includes('request:fail')) {
    // #ifdef H5
    return `暂时无法连接服务（${path}），请确认本机 gateway / trade-service 已启动后重试`;
    // #endif
    // #ifndef H5
    return `无法连接服务器 ${BASE_URL}${path}。请确认 trade-service 已启动，并在微信开发者工具勾选「不校验合法域名」`;
    // #endif
  }
  return raw;
}
const TOKEN_KEY = 'consumer_token';
const USER_KEY = 'consumer_user_id';
const EXPIRES_KEY = 'consumer_token_expires';
const OPEN_ATTEMPT_KEY = 'consumer_open_attempt';
const REQUEST_TIMEOUT_MS = 12_000;

let refreshInFlight: Promise<boolean> | null = null;

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
}

type OpenAttempt = { deviceId: string; idempotencyKey: string; createdAt: number };

function randomId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

export function getOrCreateOpenAttempt(deviceId: string): OpenAttempt {
  const normalized = deviceId.trim().toUpperCase();
  const saved = uni.getStorageSync(OPEN_ATTEMPT_KEY) as OpenAttempt | '';
  if (saved && saved.deviceId === normalized && saved.idempotencyKey) return saved;
  const attempt = { deviceId: normalized, idempotencyKey: `consumer-open-${randomId()}`, createdAt: Date.now() };
  uni.setStorageSync(OPEN_ATTEMPT_KEY, attempt);
  return attempt;
}

export function clearOpenAttempt() {
  uni.removeStorageSync(OPEN_ATTEMPT_KEY);
}

function applyTokenSession(data: LoginResponse) {
  uni.setStorageSync(TOKEN_KEY, data.token);
  uni.setStorageSync(USER_KEY, data.userId);
  const ms = (data.expiresInSeconds ?? 1800) * 1000;
  uni.setStorageSync(EXPIRES_KEY, String(Date.now() + ms));
  if (data.serverBootEpoch != null) {
    uni.setStorageSync('consumer_server_boot', data.serverBootEpoch);
  }
}

async function refreshTokenSilently(): Promise<boolean> {
  if (!getConsumerToken()) return false;
  if (refreshInFlight) return refreshInFlight;
  const pending = new Promise<boolean>((resolve, reject) => {
    uni.request({
      url: BASE_URL + '/api/v2/auth/refresh',
      method: 'POST',
      header: { Authorization: 'Bearer ' + getConsumerToken(), 'Content-Type': 'application/json' },
      success(res) {
        const body = res.data as { code?: number; data?: LoginResponse };
        if (res.statusCode === 200 && body?.code === 0 && body.data) {
          applyTokenSession(body.data);
          resolve(true);
          return;
        }
        reject(new Error('登录已失效'));
      },
      fail(err) {
        reject(new Error(formatRequestError(err.errMsg, '/api/v2/auth/refresh')));
      }
    });
  }).finally(() => {
    refreshInFlight = null;
  });
  refreshInFlight = pending;
  return pending;
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
  return new Promise((resolve, reject) => {
    const header: Record<string, string> = { 'Content-Type': 'application/json' };
    if (auth && getConsumerToken()) header.Authorization = 'Bearer ' + getConsumerToken();
    uni.request({
      url: BASE_URL + path,
      method,
      data: data as UniApp.RequestOptions['data'],
      header,
      timeout: REQUEST_TIMEOUT_MS,
      async success(res) {
        const body = res.data as { code?: number; message?: string; data?: T };
        if (res.statusCode === 401 && auth && !retried) {
          try {
            await refreshTokenSilently();
            resolve(await request(path, method, data, auth, true));
          } catch (e) {
            clearConsumerSession();
            reject(e);
          }
          return;
        }
        if (res.statusCode === 401 || res.statusCode === 403) {
          clearConsumerSession();
          reject(new Error(body?.message || '登录已失效'));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0) {
          resolve(body.data as T);
          return;
        }
        reject(new Error(body?.message || `请求失败 (${res.statusCode})`));
      },
      fail(err) {
        reject(new Error(formatRequestError(err.errMsg, path)));
      }
    });
  });
}

export async function bootstrapConsumerSession() {
  if (!getConsumerToken()) return false;
  let bootEpoch: number | string | undefined;
  try {
    const boot = await request<{ serverBootEpoch?: number }>('/api/v2/auth/server-boot', 'GET', undefined, false);
    bootEpoch = boot.serverBootEpoch;
  } catch {
    // 仅网关/服务短暂不可达：不清会话（不等于服务已重启）
    return !!getConsumerToken();
  }

  const saved = uni.getStorageSync('consumer_server_boot');
  // 服务重启会换 boot epoch → 必须清会话，要求重新登录
  if (
    saved !== '' &&
    saved != null &&
    bootEpoch != null &&
    String(saved) !== String(bootEpoch)
  ) {
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
  return request<LoginResponse>('/api/v2/auth/password-login', 'POST', { phoneNumber: phone, password }, false).then(
    (data) => {
      applyTokenSession(data);
      return data;
    }
  );
}

export function consumerSmsLogin(phone: string, code: string) {
  return request<LoginResponse>('/api/v2/auth/login', 'POST', { phoneNumber: phone, code }, false).then((data) => {
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

/** 竞品式静默登录：扫码进小程序即完成微信建档，无需先填手机号 */
export async function ensureConsumerAuth(): Promise<boolean> {
  if (getConsumerToken()) {
    const ok = await bootstrapConsumerSession();
    if (ok) return true;
    // bootstrap clears stale token; fall through to silent wx login on MP.
  }
  // #ifdef MP-WEIXIN
  try {
    const code = await wxLoginCode();
    await consumerWxLogin(code);
    return true;
  } catch {
    return false;
  }
  // #endif
  // #ifndef MP-WEIXIN
  return false;
  // #endif
}

export function requireConsumerAuth(message = '请先完成微信授权'): Promise<boolean> {
  return ensureConsumerAuth().then((ok) => {
    if (!ok) {
      uni.showModal({
        title: '需要授权',
        content: message,
        confirmText: '去验证',
        success(res) {
          if (res.confirm) {
            uni.navigateTo({ url: '/pages/login/login' });
          }
        }
      });
    }
    return ok;
  });
}

export function sendSmsCode(phone: string) {
  return request<void>(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(phone)}`, 'POST', null, false);
}

export const consumerApi = {
  account: () => request<import('@aicabinet/shared-types').AccountDto>('/api/v2/account'),
  createRechargePrepay: (channel: 'WECHAT' | 'ALIPAY', amountCents: number, idempotencyKey: string) =>
    request<import('@aicabinet/shared-types').RechargePrepayResponse>('/api/v2/payment/recharge/prepay', 'POST', {
      channel, amountCents, idempotencyKey
    }),
  getRechargeOrder: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      `/api/v2/payment/recharge/${encodeURIComponent(orderId)}`
    ),
  createMockRecharge: (amountCents: number, idempotencyKey: string) =>
    request<import('@aicabinet/shared-types').RechargePrepayResponse>('/api/v2/payment/recharge/prepay', 'POST', {
      channel: 'WECHAT', amountCents, idempotencyKey
    }),
  confirmMockRecharge: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      `/api/v2/payment/recharge/${encodeURIComponent(orderId)}/mock-success`, 'POST'
    ),
  cancelRecharge: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      `/api/v2/payment/recharge/${encodeURIComponent(orderId)}/cancel`,
      'POST'
    ),
  balanceTransactions: (page = 0, size = 20) =>
    request<import('@aicabinet/shared-types').PageResult<import('@aicabinet/shared-types').BalanceTransactionDto>>(
      `/api/v2/account/transactions?page=${page}&size=${size}`
    ),
  verifyIdentity: (body: import('@aicabinet/shared-types').VerifyIdentityRequest) =>
    request<import('@aicabinet/shared-types').AccountDto>('/api/v2/account/verify', 'POST', body),
  signPayScore: () =>
    request<import('@aicabinet/shared-types').PayContractDto>('/api/v2/account/payscore/sign', 'POST'),
  signAlipayAgreement: () =>
    request<import('@aicabinet/shared-types').PayContractDto>('/api/v2/account/alipay-agreement/sign', 'POST'),
  deviceStatus: (deviceId: string) =>
    request<import('@aicabinet/shared-types').DeviceStatusDto>(
      `/api/v2/devices/${encodeURIComponent(deviceId)}/status`
    ),
  deviceProducts: (deviceId: string) =>
    request<import('@aicabinet/shared-types').DeviceProduct[]>(
      `/api/v2/devices/${encodeURIComponent(deviceId)}/products`
    ),
  createSession: async (deviceId: string, entryChannel?: string | null) => {
    const attempt = getOrCreateOpenAttempt(deviceId);
    const body: { deviceId: string; idempotencyKey: string; entryChannel?: string } = {
      deviceId: attempt.deviceId,
      idempotencyKey: attempt.idempotencyKey
    };
    const channel = String(entryChannel || '').trim().toUpperCase();
    if (channel === 'WECHAT' || channel === 'ALIPAY') {
      body.entryChannel = channel;
    }
    try {
      return await request<import('@aicabinet/shared-types').SessionDto>('/api/v2/sessions', 'POST', body);
    } catch (firstError) {
      await new Promise((resolve) => setTimeout(resolve, 600));
      try {
        return await request<import('@aicabinet/shared-types').SessionDto>('/api/v2/sessions', 'POST', body);
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
    request<import('@aicabinet/shared-types').SessionDto>(`/api/v2/sessions/${sessionId}/cancel`, 'POST'),
  updateSessionCart: (sessionId: string, body: import('@aicabinet/shared-types').SessionCartRequest) =>
    request<import('@aicabinet/shared-types').SessionDto>(`/api/v2/sessions/${sessionId}/cart`, 'PUT', body),
  getSessionOrder: (sessionId: string) =>
    request<import('@aicabinet/shared-types').OrderDetailDto>(`/api/v2/sessions/${sessionId}/order`),
  listOrders: (page = 0, size = 20) =>
    request<import('@aicabinet/shared-types').PageResult<import('@aicabinet/shared-types').OrderSummary>>(
      `/api/v2/orders?page=${page}&size=${size}`
    ),
  getOrder: (orderId: string) =>
    request<import('@aicabinet/shared-types').OrderDetailDto>(`/api/v2/orders/${orderId}`),
  fileDispute: (body: import('@aicabinet/shared-types').FileDisputeRequest) =>
    request<import('@aicabinet/shared-types').DisputeTicketDto>('/api/v2/disputes', 'POST', body),
  listMyDisputes: () =>
    request<import('@aicabinet/shared-types').DisputeTicketDto[]>('/api/v2/disputes/mine'),
  consumerPublicConfig: () =>
    request<Record<string, string>>('/api/v2/public/consumer-config', 'GET', null, false),
  reportDeviceFault: (deviceId: string, body: import('@aicabinet/shared-types').DeviceFaultReportRequest) =>
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
  memberPointsSummary: () => request<MemberPointsSummaryDto>('/api/v2/member/points/summary'),
  memberPointsHistory: (type?: string) =>
    request<MemberPointsLogDto[]>(
      type ? `/api/v2/member/points?type=${encodeURIComponent(type)}` : '/api/v2/member/points'
    ),
  redeemItems: () => request<PointsRedeemItemDto[]>('/api/v2/member/redeem-items'),
  redeemPoints: (itemId: number) =>
    request<CouponDto>('/api/v2/member/redeem', 'POST', { itemId }),
  marketingBanners: () =>
    request<MarketingBannerDto[]>('/api/v2/marketing/banners', 'GET', undefined, false),
  marketingCampaigns: () =>
    request<MarketingCampaignDto[]>('/api/v2/marketing/campaigns/active', 'GET', undefined, false),
  claimCampaign: (activityId: number) =>
    request<CouponDto>(`/api/v2/marketing/campaigns/${activityId}/claim`, 'POST'),
  myCoupons: (status?: string) =>
    request<CouponDto[]>(status ? `/api/v2/coupons?status=${encodeURIComponent(status)}` : '/api/v2/coupons'),
  couponCount: () => request<number>('/api/v2/coupons/count')
};

export type MemberProfileDto = {
  memberId: number;
  userId: number;
  levelCode: string;
  levelName: string;
  availablePoints: number;
  totalPoints: number;
  usedPoints: number;
  totalSpent: number;
  orderCount: number;
  inviteCode?: string;
  pointsToNextLevel: number;
  nextLevelName?: string | null;
  progressPercent: number;
  pointsRate: number;
  levels: Array<{
    levelCode: string;
    levelName: string;
    minSpent: number;
    maxSpent?: number | null;
    minPoints: number;
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
  earnedThisMonth: number;
  usedThisMonth: number;
};

export type MemberPointsLogDto = {
  id: number;
  points: number;
  pointsType: string;
  sourceType?: string;
  sourceId?: string;
  description?: string;
  createdAt?: string;
  expireAt?: string;
};

export type PointsRedeemItemDto = {
  itemId: number;
  title: string;
  subtitle?: string;
  coverEmoji: string;
  pointsCost: number;
  couponDefId: number;
  couponName: string;
  denominationCents: number;
  minSpendCents: number;
  couponType: string;
  stockLeft: number;
  canRedeem: boolean;
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
};
