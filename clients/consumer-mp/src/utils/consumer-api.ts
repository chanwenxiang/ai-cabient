import type { LoginResponse } from '@aicabinet/shared-types';
import { clearDictOverrides } from '@aicabinet/shared-dict';
import { localizeApiMessage } from '@aicabinet/shared-uni/format';
import { parseQuery, queryGet } from '@aicabinet/shared-uni/query';
import { loadRuntimeDict as sharedLoadRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
import {
  createMpApiError,
  formatMpRequestError,
  isMpAuthFailure,
  mpRequest,
  refreshTokenSilently as sharedRefreshToken,
  type MpApiSession
} from '@aicabinet/shared-uni/request';
import { buildRechargePrepayBody } from '@/utils/money-ui-contracts';
import { API_BASE_URL } from '@/config/api';
import { isDevBuild } from '@/utils/runtime-flags';
import { showConfirm } from '@/utils/notify';
import { isConsumerBearerExpired, parseConsumerExpiresAt } from '@/utils/consumer-session';
import {
  alipayLoginBody,
  passwordLoginBody,
  planTokenSessionApply,
  smsLoginBody,
  wxH5LoginBody,
  wxLoginBody
} from '@/utils/consumer-auth-session';
import { AuthEndpoints, ConsumerEndpoints } from '@/api/endpoints';
import { clearOpenAttempt, getOrCreateOpenAttempt } from '@/utils/consumer-open-attempt';
import { buildBearerDownloadHeader, classifyDownloadResult } from '@/utils/consumer-download';

export { clearOpenAttempt, getOrCreateOpenAttempt } from '@/utils/consumer-open-attempt';

const BASE_URL = API_BASE_URL;

function formatRequestError(errMsg: string | undefined, path: string) {
  return formatMpRequestError(errMsg, path, isDevBuild, BASE_URL);
}
const TOKEN_KEY = 'consumer_token';
const USER_KEY = 'consumer_user_id';
const EXPIRES_KEY = 'consumer_token_expires';
/** H5：服务端已写 HttpOnly Cookie 时的本地会话标记（不落 JWT） */
const COOKIE_AUTH_KEY = 'consumer_cookie_auth';
/** 用户主动退出后禁止静默微信建档，直到再次点登录 */
const SKIP_SILENT_AUTH_KEY = 'consumer_skip_silent_auth';
const REQUEST_TIMEOUT_MS = 12_000;

function isConsumerH5Runtime() {
  return (
    typeof window !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    !/miniProgram|miniprogram/i.test(navigator.userAgent)
  );
}

export function isConsumerCookieAuth() {
  return isConsumerH5Runtime() && uni.getStorageSync(COOKIE_AUTH_KEY) === '1';
}

/** 登录态：本地 JWT 或 H5 Cookie 会话标记 */
export function isConsumerLoggedIn() {
  return Boolean(getConsumerToken()) || isConsumerCookieAuth();
}

const mpApiSession: MpApiSession = {
  baseUrl: BASE_URL,
  isDevBuild,
  timeoutMs: REQUEST_TIMEOUT_MS,
  getToken: getConsumerToken,
  hasSession: isConsumerLoggedIn,
  useCookieAuth: isConsumerCookieAuth,
  clearSession: clearConsumerSession,
  applyRefreshedToken: (data) => applyTokenSession(data as LoginResponse),
  handleUnauthorized: (message) => {
    clearConsumerSession();
    return createMpApiError(localizeApiMessage(message, '登录已失效'), 401, 'UNAUTHORIZED');
  }
};

export function getConsumerToken() {
  const token = uni.getStorageSync(TOKEN_KEY) || '';
  if (!token) return '';
  // C3：Bearer 路径必须读 expires；到期清会话，禁止过期 JWT 仍当已登录
  const expiresAt = parseConsumerExpiresAt(uni.getStorageSync(EXPIRES_KEY));
  if (isConsumerBearerExpired(expiresAt)) {
    clearConsumerSession();
    return '';
  }
  return token;
}

/** 带鉴权下载到本地临时路径（小程序 video/导出等无法带 Authorization 的场景） */
export function downloadAuthedFile(url: string, timeoutMs = 60_000): Promise<string> {
  return new Promise((resolve, reject) => {
    if (!isConsumerLoggedIn()) {
      reject(new Error('请先登录'));
      return;
    }
    const header = buildBearerDownloadHeader(getConsumerToken());
    uni.downloadFile({
      url,
      header,
      // #ifdef H5
      withCredentials: true,
      // #endif
      timeout: timeoutMs,
      success(res) {
        const classified = classifyDownloadResult(res.statusCode, Boolean(res.tempFilePath));
        if (classified.unauthorized) {
          clearConsumerSession();
          reject(
            createMpApiError(
              localizeApiMessage('', classified.message || '登录已失效'),
              401,
              'UNAUTHORIZED'
            )
          );
          return;
        }
        if (classified.ok && res.tempFilePath) {
          resolve(res.tempFilePath);
          return;
        }
        reject(new Error(classified.message || `下载失败 (${res.statusCode})`));
      },
      fail(err) {
        reject(new Error(err.errMsg || '下载失败'));
      }
    });
  });
}

export function clearConsumerSession() {
  uni.removeStorageSync(TOKEN_KEY);
  uni.removeStorageSync(USER_KEY);
  uni.removeStorageSync(EXPIRES_KEY);
  uni.removeStorageSync(COOKIE_AUTH_KEY);
  uni.removeStorageSync('consumer_server_boot');
  uni.removeStorageSync('active_session_id');
  clearOpenAttempt();
  clearDictOverrides();
}

function applyTokenSession(data: LoginResponse) {
  const plan = planTokenSessionApply(data, {
    isH5: isConsumerH5Runtime(),
    alreadyCookieAuth: isConsumerCookieAuth()
  });
  uni.removeStorageSync(SKIP_SILENT_AUTH_KEY);
  if (plan.mode === 'cookie') {
    uni.removeStorageSync(TOKEN_KEY);
    uni.setStorageSync(COOKIE_AUTH_KEY, '1');
  } else {
    uni.setStorageSync(TOKEN_KEY, plan.token);
    uni.removeStorageSync(COOKIE_AUTH_KEY);
  }
  if (plan.userId) {
    uni.setStorageSync(USER_KEY, plan.userId);
  }
  uni.setStorageSync(EXPIRES_KEY, String(plan.expiresAtMs));
  if (plan.serverBootEpoch != null) {
    uni.setStorageSync('consumer_server_boot', plan.serverBootEpoch);
  }
  void sharedLoadRuntimeDict({
    getToken: getConsumerToken,
    fetchRuntime: () => request(ConsumerEndpoints.dictsRuntime, 'GET')
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
    if (!isConsumerLoggedIn()) {
      reject(new Error('请先登录'));
      return;
    }
    const header: Record<string, string> = {
      'X-Requested-With': 'XMLHttpRequest'
    };
    const token = getConsumerToken();
    if (token) header.Authorization = 'Bearer ' + token;
    uni.uploadFile({
      url: BASE_URL + ConsumerEndpoints.disputesEvidence,
      filePath,
      name: 'file',
      header,
      // #ifdef H5
      withCredentials: true,
      // #endif
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
        reject(new Error(formatRequestError(err.errMsg, ConsumerEndpoints.disputesEvidence)));
      }
    });
  });
}

export async function bootstrapConsumerSession() {
  if (!isConsumerLoggedIn()) return false;
  let bootEpoch: number | string | undefined;
  try {
    const boot = await request<{ serverBootEpoch?: number }>(
      AuthEndpoints.serverBoot,
      'GET',
      undefined,
      false
    );
    bootEpoch = boot.serverBootEpoch;
  } catch {
    // 仅网关/服务短暂不可达：不清会话（不等于服务已重启）
    return isConsumerLoggedIn();
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
    // token 过期/鉴权失败清会话；瞬时网络错误保留本地 token
    if (isMpAuthFailure(e)) {
      clearConsumerSession();
      return false;
    }
    return isConsumerLoggedIn();
  }
}

export function consumerPasswordLogin(phone: string, password: string) {
  return request<LoginResponse>(
    AuthEndpoints.passwordLogin,
    'POST',
    passwordLoginBody(phone, password),
    false
  ).then((data) => {
    applyTokenSession(data);
    return data;
  });
}

export function consumerSmsLogin(phone: string, code: string) {
  return request<LoginResponse>(AuthEndpoints.login, 'POST', smsLoginBody(phone, code), false).then(
    (data) => {
      applyTokenSession(data);
      return data;
    }
  );
}

export function consumerWxLogin(code: string, phoneNumber?: string) {
  return request<LoginResponse>(
    AuthEndpoints.wxLogin,
    'POST',
    wxLoginBody(code, phoneNumber),
    false
  ).then((data) => {
    applyTokenSession(data);
    return data;
  });
}

export function consumerAlipayLogin(authCode: string) {
  return request<LoginResponse>(
    AuthEndpoints.alipayLogin,
    'POST',
    alipayLoginBody(authCode),
    false
  ).then((data) => {
    applyTokenSession(data);
    return data;
  });
}

/** H5 微信网页授权登录（公众号 OAuth code）。 */
export function consumerWxH5Login(code: string) {
  return request<LoginResponse>(AuthEndpoints.wxH5Login, 'POST', wxH5LoginBody(code), false).then(
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
  if (isConsumerLoggedIn()) {
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
  return ensureConsumerAuth().then(async (ok) => {
    if (!ok) {
      const target = redirect || currentPagePath();
      const confirmed = await showConfirm({
        title: '需要授权',
        content: message,
        confirmText: '去验证'
      });
      if (confirmed) {
        uni.navigateTo({
          url: '/pages/login/login?redirect=' + encodeURIComponent(target)
        });
      }
    }
    return ok;
  });
}

export function fetchCaptcha() {
  return request<{ captchaId: string; imageBase64: string }>(
    AuthEndpoints.captcha,
    'GET',
    null,
    false
  );
}

export function sendSmsCode(phone: string, captchaId: string, captchaCode: string) {
  const q = new URLSearchParams({
    phoneNumber: phone,
    captchaId,
    captchaCode
  });
  return request<void>(AuthEndpoints.smsCode(q.toString()), 'POST', null, false);
}

/** 登出：服务端吊销 JWT（失败也清本地会话）。 */
export async function logoutConsumerSession() {
  try {
    if (isConsumerLoggedIn()) {
      await request<void>(AuthEndpoints.logout, 'POST', null, true);
    }
  } catch {
    /* 吊销失败仍清本地，避免卡在坏会话 */
  } finally {
    clearConsumerSession();
    markConsumerExplicitLogout();
  }
}

export const consumerApi = {
  account: () => request<import('@aicabinet/shared-types').AccountDto>(ConsumerEndpoints.account),
  createRechargePrepay: (
    channel: 'WECHAT' | 'ALIPAY',
    amountCents: number,
    idempotencyKey: string
  ) =>
    request<import('@aicabinet/shared-types').RechargePrepayResponse>(
      ConsumerEndpoints.rechargePrepay,
      'POST',
      buildRechargePrepayBody({ channel, amountCents, idempotencyKey })
    ),
  getRechargeOrder: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      ConsumerEndpoints.rechargeOrder(orderId)
    ),
  createMockRecharge: (amountCents: number, idempotencyKey: string) =>
    request<import('@aicabinet/shared-types').RechargePrepayResponse>(
      ConsumerEndpoints.rechargePrepay,
      'POST',
      buildRechargePrepayBody({ channel: 'WECHAT', amountCents, idempotencyKey })
    ),
  confirmMockRecharge: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      ConsumerEndpoints.mockRechargeSuccess(orderId),
      'POST'
    ),
  cancelRecharge: (orderId: string) =>
    request<import('@aicabinet/shared-types').RechargeOrderDto>(
      ConsumerEndpoints.rechargeCancel(orderId),
      'POST'
    ),
  /** C-P2-4：充值记录统一走 consumerApi，禁止页面裸 get 路径。 */
  listRecharges: (page = 0, size = 20) =>
    request<
      import('@aicabinet/shared-types').PageResult<
        import('@aicabinet/shared-types').RechargeOrderDto
      >
    >(ConsumerEndpoints.recharges(page, size)),
  balanceTransactions: (page = 0, size = 20) =>
    request<
      import('@aicabinet/shared-types').PageResult<
        import('@aicabinet/shared-types').BalanceTransactionDto
      >
    >(ConsumerEndpoints.accountTransactions(page, size)),
  verifyIdentity: (body: import('@aicabinet/shared-types').VerifyIdentityRequest) =>
    request<import('@aicabinet/shared-types').AccountDto>(
      ConsumerEndpoints.accountVerify,
      'POST',
      body
    ),
  signPayScore: () =>
    request<import('@aicabinet/shared-types').PayContractDto>(
      ConsumerEndpoints.payscoreSign,
      'POST'
    ),
  signAlipayAgreement: () =>
    request<import('@aicabinet/shared-types').PayContractDto>(
      ConsumerEndpoints.alipayAgreementSign,
      'POST'
    ),
  /**
   * G9：用户主动解约（关闭免密代扣）。幂等 —— 没有已开通合约时同样返回 200。
   * 返回刷新后的 `AccountDto`（与 `setPayPreferred` 同款），前端整份替换即可。
   */
  unsignPayContract: () =>
    request<import('@aicabinet/shared-types').AccountDto>(
      ConsumerEndpoints.payContractUnsign,
      'POST'
    ),
  setPayPreferred: (channel: 'BALANCE' | 'WECHAT' | 'ALIPAY') =>
    request<import('@aicabinet/shared-types').AccountDto>(ConsumerEndpoints.payPreferred, 'PUT', {
      channel
    }),
  listBalanceRefunds: () =>
    request<import('@aicabinet/shared-types').BalanceRefundRequestDto[]>(
      ConsumerEndpoints.balanceRefunds
    ),
  applyBalanceRefund: (amountCents: number, reason?: string) =>
    request<import('@aicabinet/shared-types').BalanceRefundRequestDto>(
      ConsumerEndpoints.balanceRefunds,
      'POST',
      { amountCents, reason }
    ),
  deviceStatus: (deviceId: string) =>
    request<import('@aicabinet/shared-types').DeviceStatusDto>(
      ConsumerEndpoints.deviceStatus(deviceId)
    ),
  deviceProducts: (deviceId: string) =>
    request<import('@aicabinet/shared-types').DeviceProduct[]>(
      ConsumerEndpoints.deviceProducts(deviceId)
    ),
  screenContent: (deviceId: string) =>
    request<import('@aicabinet/shared-types').ScreenContentDto>(
      ConsumerEndpoints.deviceScreenContent(deviceId)
    ),
  reportAdPlay: (
    deviceId: string,
    body: { campaignId: number; assetId: number; eventType: 'IMPRESSION' | 'COMPLETE' | 'CLICK' }
  ) => request<null>(ConsumerEndpoints.deviceAdPlay(deviceId), 'POST', body),
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
      try {
        const mine = await request<Array<{ couponId?: number; status?: string }>>(
          ConsumerEndpoints.couponsUnused
        );
        const ok = (mine || []).some(
          (c) =>
            Number(c.couponId) === preferred && String(c.status || '').toUpperCase() === 'UNUSED'
        );
        if (ok) {
          body.preferredCouponId = preferred;
        } else {
          uni.removeStorageSync('preferred_coupon_id');
        }
      } catch {
        // 券列表拉取失败时不带 preferred，避免提交不可信券 id
        uni.removeStorageSync('preferred_coupon_id');
      }
    }
    try {
      return await request<import('@aicabinet/shared-types').SessionDto>(
        ConsumerEndpoints.sessions,
        'POST',
        body
      );
    } catch (firstError) {
      await new Promise((resolve) => setTimeout(resolve, 600));
      try {
        return await request<import('@aicabinet/shared-types').SessionDto>(
          ConsumerEndpoints.sessions,
          'POST',
          body
        );
      } catch {
        throw firstError;
      }
    }
  },
  activeSession: () =>
    request<import('@aicabinet/shared-types').SessionDto | null>(ConsumerEndpoints.sessionsActive),
  getSession: (sessionId: string) =>
    request<import('@aicabinet/shared-types').SessionDto>(ConsumerEndpoints.session(sessionId)),
  cancelSession: (sessionId: string) =>
    request<import('@aicabinet/shared-types').SessionDto>(
      ConsumerEndpoints.sessionCancel(sessionId),
      'POST'
    ),
  updateSessionCart: (
    sessionId: string,
    body: import('@aicabinet/shared-types').SessionCartRequest
  ) =>
    request<import('@aicabinet/shared-types').SessionDto>(
      ConsumerEndpoints.sessionCart(sessionId),
      'PUT',
      body
    ),
  /** 演示关门结算：无柜机硬件时模拟关门（后端 mockEnabled 才放行）。 */
  demoCloseSession: (sessionId: string) =>
    request<import('@aicabinet/shared-types').SessionDto>(
      ConsumerEndpoints.sessionDemoClose(sessionId),
      'POST'
    ),
  getSessionOrder: (sessionId: string) =>
    request<import('@aicabinet/shared-types').OrderDetailDto>(
      ConsumerEndpoints.sessionOrder(sessionId)
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
    }>(ConsumerEndpoints.sessionLiveCart(sessionId)),
  listOrders: (page = 0, size = 20) =>
    request<
      import('@aicabinet/shared-types').PageResult<import('@aicabinet/shared-types').OrderSummary>
    >(ConsumerEndpoints.orders(page, size)),
  /** C-P2-9：待补缴角标，勿拉整页订单再 filter。 */
  pendingOrderCount: () => request<{ count: number }>(ConsumerEndpoints.ordersPendingCount),
  getOrder: (orderId: string) =>
    request<import('@aicabinet/shared-types').OrderDetailDto>(
      ConsumerEndpoints.orderDetail(orderId)
    ),
  /**
   * 补缴待支付订单。
   *
   * @param channel F6：结算页**显式选择**的支付方式（BALANCE / WECHAT / ALIPAY）。
   *                不传 ⇒ 服务端按既有规则自动决策（与接入前一致）。
   *                传了 ⇒ 服务端只按该渠道扣款、**不降级**；渠道未就绪返回 412。
   */
  payOrder: (orderId: string, channel?: string) =>
    request<import('@aicabinet/shared-types').OrderDetailDto>(
      ConsumerEndpoints.orderPay(orderId),
      'POST',
      channel ? { channel } : undefined
    ),
  fileDispute: (body: import('@aicabinet/shared-types').FileDisputeRequest) =>
    request<import('@aicabinet/shared-types').DisputeTicketDto>(
      ConsumerEndpoints.disputes,
      'POST',
      body
    ),
  listMyDisputes: () =>
    request<import('@aicabinet/shared-types').DisputeTicketDto[]>(ConsumerEndpoints.disputesMine),
  getMyDispute: (opts: { ticketId?: string; sessionId?: string }) => {
    const q = [
      opts.ticketId ? `ticketId=${encodeURIComponent(opts.ticketId)}` : '',
      opts.sessionId ? `sessionId=${encodeURIComponent(opts.sessionId)}` : ''
    ]
      .filter(Boolean)
      .join('&');
    const path = ConsumerEndpoints.disputesMineDetail(q || undefined);
    return request<import('@aicabinet/shared-types').DisputeTicketDto>(path);
  },
  uploadDisputeEvidence: (filePath: string) => uploadDisputeEvidenceFile(filePath),
  refundOrder: (orderId: string, body: import('@aicabinet/shared-types').OrderRefundRequest) =>
    request<import('@aicabinet/shared-types').OrderRefundResultDto>(
      ConsumerEndpoints.orderRefund(orderId),
      'POST',
      body
    ),
  applyInvoice: (orderId: string, body: { title: string; taxNo?: string; email?: string }) =>
    request<{ invoiceId: number; status: string }>(
      ConsumerEndpoints.orderInvoice(orderId),
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
    >(ConsumerEndpoints.accountInvoices),
  consumerPublicConfig: () =>
    request<Record<string, string>>(ConsumerEndpoints.publicConsumerConfig, 'GET', null, false),
  reportDeviceFault: (
    deviceId: string,
    body: import('@aicabinet/shared-types').DeviceFaultReportRequest
  ) =>
    request<{ reportId: string; message: string }>(
      ConsumerEndpoints.deviceFaultReport(deviceId),
      'POST',
      body
    ),
  submitFeedback: (body: import('@aicabinet/shared-types').SubmitFeedbackRequest) =>
    request<import('@aicabinet/shared-types').UserFeedbackDto>(
      ConsumerEndpoints.feedback,
      'POST',
      body
    ),
  listMyFeedback: () =>
    request<import('@aicabinet/shared-types').UserFeedbackDto[]>(ConsumerEndpoints.feedbackMine),

  memberProfile: () => request<MemberProfileDto>(ConsumerEndpoints.memberProfile),
  memberPoints: () => request<MemberPointsSummaryDto>(ConsumerEndpoints.memberPoints),
  memberPointsLog: (limit = 50) =>
    request<MemberPointsLogDto[]>(ConsumerEndpoints.memberPointsLog(limit)),
  redeemItems: () => request<PointsRedeemItemDto[]>(ConsumerEndpoints.memberRedeemItems),
  redeemPoints: (itemId: number) =>
    request<CouponDto>(ConsumerEndpoints.memberRedeem, 'POST', { itemId }),
  notifications: (limit = 50) =>
    request<NotificationDto[]>(ConsumerEndpoints.memberNotifications(limit)),
  notificationUnreadCount: () =>
    request<{ count: number }>(ConsumerEndpoints.memberNotificationsUnreadCount),
  markNotificationRead: (id: number) =>
    request<void>(ConsumerEndpoints.memberNotificationRead(id), 'POST'),
  markAllNotificationsRead: () =>
    request<void>(ConsumerEndpoints.memberNotificationsReadAll, 'POST'),
  notifyPrefs: () => request<NotifyPrefDto[]>(ConsumerEndpoints.memberNotificationPrefs),
  updateNotifyPref: (category: string, enabled: boolean) =>
    request<NotifyPrefDto>(ConsumerEndpoints.memberNotificationPrefs, 'PUT', {
      category,
      enabled
    }),
  marketingBanners: () =>
    request<MarketingBannerDto[]>(ConsumerEndpoints.marketingBanners, 'GET', undefined, false),
  // auth=true：有 token 时带上，后端可返回「已领取/查看券包」；无 token 仍可游客浏览
  marketingCampaigns: () =>
    request<MarketingCampaignDto[]>(
      ConsumerEndpoints.marketingCampaignsActive,
      'GET',
      undefined,
      true
    ),
  claimCampaign: (activityId: number) =>
    request<CouponDto>(ConsumerEndpoints.marketingCampaignClaim(activityId), 'POST'),
  myCoupons: (status?: string) => request<CouponDto[]>(ConsumerEndpoints.coupons(status)),
  couponCount: () => request<number>(ConsumerEndpoints.couponsCount),
  listAnnouncements: () =>
    request<import('@aicabinet/shared-types').AnnouncementDto[]>(
      ConsumerEndpoints.announcements,
      'GET',
      undefined,
      false
    ),
  getAnnouncement: (id: number) =>
    request<import('@aicabinet/shared-types').AnnouncementDto>(
      ConsumerEndpoints.announcement(id),
      'GET',
      undefined,
      false
    )
};

/** @deprecated 使用 OpenApiMemberProfileDto */
export type MemberProfileDto = import('@aicabinet/shared-types').OpenApiMemberProfileDto;

/** @deprecated 使用 OpenApiMemberPointsSummaryDto */
export type MemberPointsSummaryDto =
  import('@aicabinet/shared-types').OpenApiMemberPointsSummaryDto;

/** @deprecated 使用 OpenApiMemberPointsLogDto */
export type MemberPointsLogDto = import('@aicabinet/shared-types').OpenApiMemberPointsLogDto;

/** @deprecated 使用 OpenApiPointsRedeemItemDto */
export type PointsRedeemItemDto = import('@aicabinet/shared-types').OpenApiPointsRedeemItemDto;

/** @deprecated 使用 OpenApiNotificationDto */
export type NotificationDto = import('@aicabinet/shared-types').OpenApiNotificationDto;

/** @deprecated 使用 OpenApiNotifyPrefDto */
export type NotifyPrefDto = import('@aicabinet/shared-types').OpenApiNotifyPrefDto;

/** @deprecated 使用 OpenApiMarketingBannerDto */
export type MarketingBannerDto = import('@aicabinet/shared-types').OpenApiMarketingBannerDto;

/** @deprecated 使用 OpenApiMarketingCampaignDto */
export type MarketingCampaignDto = import('@aicabinet/shared-types').OpenApiMarketingCampaignDto;

/** @deprecated 使用 OpenApiCouponDto */
export type CouponDto = import('@aicabinet/shared-types').OpenApiCouponDto;
