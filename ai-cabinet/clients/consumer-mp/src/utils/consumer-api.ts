import type { LoginResponse } from '@aicabinet/shared-types';
import { API_BASE_URL } from '@/config/api';

const BASE_URL = API_BASE_URL;

function formatRequestError(errMsg: string | undefined, path: string) {
  const raw = errMsg || '网络错误';
  if (raw === 'request:fail' || raw.includes('request:fail')) {
    return `无法连接服务器 ${BASE_URL}${path}。请确认 trade-service 已启动，并在微信开发者工具勾选「不校验合法域名」`;
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
  try {
    const boot = await request<{ serverBootEpoch?: number }>('/api/v2/auth/server-boot', 'GET', undefined, false);
    const saved = uni.getStorageSync('consumer_server_boot');
    if (saved && boot.serverBootEpoch != null && saved !== boot.serverBootEpoch) {
      clearConsumerSession();
      return false;
    }
    return await refreshTokenSilently();
  } catch {
    return false;
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
    try {
      return await bootstrapConsumerSession();
    } catch {
      clearConsumerSession();
    }
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
  createMockRecharge: (amountCents: number, idempotencyKey: string) =>
    request<import('@aicabinet/shared-types').RechargePrepayResponse>('/api/v2/payment/recharge/prepay', 'POST', {
      channel: 'WECHAT', amountCents, idempotencyKey
    }),
  confirmMockRecharge: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      `/api/v2/payment/recharge/${encodeURIComponent(orderId)}/mock-success`, 'POST'
    ),
  balanceTransactions: (page = 0, size = 20) =>
    request<import('@aicabinet/shared-types').PageResult<import('@aicabinet/shared-types').BalanceTransactionDto>>(
      `/api/v2/account/transactions?page=${page}&size=${size}`
    ),
  verifyIdentity: (body: import('@aicabinet/shared-types').VerifyIdentityRequest) =>
    request<import('@aicabinet/shared-types').AccountDto>('/api/v2/account/verify', 'POST', body),
  signPayScore: () =>
    request<import('@aicabinet/shared-types').PayContractDto>('/api/v2/account/payscore/sign', 'POST'),
  deviceStatus: (deviceId: string) =>
    request<import('@aicabinet/shared-types').DeviceStatusDto>(
      `/api/v2/devices/${encodeURIComponent(deviceId)}/status`
    ),
  deviceProducts: (deviceId: string) =>
    request<import('@aicabinet/shared-types').DeviceProduct[]>(
      `/api/v2/devices/${encodeURIComponent(deviceId)}/products`
    ),
  createSession: async (deviceId: string) => {
    const attempt = getOrCreateOpenAttempt(deviceId);
    try {
      return await request<import('@aicabinet/shared-types').SessionDto>('/api/v2/sessions', 'POST', {
        deviceId: attempt.deviceId,
        idempotencyKey: attempt.idempotencyKey
      });
    } catch (firstError) {
      await new Promise((resolve) => setTimeout(resolve, 600));
      try {
        return await request<import('@aicabinet/shared-types').SessionDto>('/api/v2/sessions', 'POST', {
          deviceId: attempt.deviceId,
          idempotencyKey: attempt.idempotencyKey
        });
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
  reportDeviceFault: (deviceId: string, body: import('@aicabinet/shared-types').DeviceFaultReportRequest) =>
    request<{ reportId: string; message: string }>(
      `/api/v2/devices/${encodeURIComponent(deviceId)}/fault-report`,
      'POST',
      body
    )
};
