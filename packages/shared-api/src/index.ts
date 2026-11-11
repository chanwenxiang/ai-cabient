import type { ApiResponse, LoginResponse } from '@aicabinet/shared-types';
import { localizeApiMessage } from '@aicabinet/shared-uni/format';

/** 当前对外 HTTP API 主版本（与后端 ApiVersions.CURRENT 对齐）。 */
export const API_VERSION = 'v2' as const;
export const API_PREFIX = `/api/${API_VERSION}` as const;

export type HttpAdapter = (input: {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
}) => Promise<{ status: number; json: () => Promise<unknown> }>;

export interface ApiClientOptions {
  baseUrl: string;
  getToken: () => string | null;
  setToken: (token: string, userId: string, expiresAt?: number) => void;
  clearSession: () => void;
  onUnauthorized?: () => void;
  /** 会话存在性判定（默认取 getToken 非空；HttpOnly Cookie 模式下需自行提供） */
  hasSession?: () => boolean;
  /** 单次请求超时（毫秒），默认 30s */
  timeoutMs?: number;
  /**
   * GET/HEAD 在超时/网络错误时的额外重试次数（不含首次）。
   * 默认 2（共最多 3 次）；写操作永不自动重试。
   */
  getRetryCount?: number;
  fetchImpl?: typeof fetch;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * R15-K2：静默刷新的结果分类（口径对齐 shared-uni 2026-11-11 修正）。
 * 只有刷新端点「明确拒绝」（401/403）才是会话失效；429（网关 auth_ratelimit）、
 * 5xx（发版窗口）、网络抖动一律 transient——旧写法 catch-all 返回 false 让
 * 调用方清会话踢登录，把仍有效（7 天宽限完全可续）的会话误杀。
 */
export type RefreshOutcome = {
  kind: 'ok' | 'invalid' | 'transient' | 'no-session';
  /** transient/invalid 时刷新响应的 HTTP status（网络层失败缺省） */
  status?: number;
};

/** Web Locks API 最小子集（lib.dom 版本差异兜底，R15-K1 跨 tab 刷新串行化用）。 */
type LockManagerLike = {
  request<R>(name: string, options: { ifAvailable: false }, callback: () => Promise<R>): Promise<R>;
};

function isRetriableTransportError(err: unknown): boolean {
  if (!(err instanceof Error)) return false;
  return err.message.includes('请求超时') || err.message.includes('网络错误');
}

/**
 * 服务端拒绝（非 2xx 或业务码非 0）。带 HTTP `status`，供调用方区分 409 等——
 * 例如设备维护指令被「进行中业务」拦下时，运营二次确认后带 force 重试。
 */
export class ApiHttpError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiHttpError';
    this.status = status;
  }
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly getToken: () => string | null;
  private readonly setToken: ApiClientOptions['setToken'];
  private readonly clearSession: () => void;
  private readonly onUnauthorized?: () => void;
  private readonly hasSession: () => boolean;
  private readonly fetchImpl: typeof fetch;
  private readonly timeoutMs: number;
  private readonly getRetryCount: number;
  private refreshPromise: Promise<RefreshOutcome> | null = null;

  constructor(opts: ApiClientOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/$/, '');
    this.getToken = opts.getToken;
    this.setToken = opts.setToken;
    this.clearSession = opts.clearSession;
    this.onUnauthorized = opts.onUnauthorized;
    this.hasSession = opts.hasSession ?? (() => Boolean(this.getToken()));
    this.fetchImpl = opts.fetchImpl ?? fetch.bind(globalThis);
    this.timeoutMs = opts.timeoutMs ?? 30000;
    this.getRetryCount = Math.max(0, opts.getRetryCount ?? 2);
  }

  async request<T>(
    path: string,
    method = 'GET',
    body?: unknown,
    auth = true,
    retried = false
  ): Promise<T> {
    const methodUpper = String(method || 'GET').toUpperCase();
    const canRetry = methodUpper === 'GET' || methodUpper === 'HEAD';
    const maxAttempts = canRetry ? 1 + this.getRetryCount : 1;
    let lastError: unknown;
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        return await this.requestOnce<T>(path, method, body, auth, retried);
      } catch (err) {
        lastError = err;
        if (!canRetry || !isRetriableTransportError(err) || attempt >= maxAttempts - 1) {
          throw err;
        }
        await sleep(200 * 2 ** attempt);
      }
    }
    throw lastError instanceof Error ? lastError : new Error('网络错误，请稍后重试');
  }

  private async requestOnce<T>(
    path: string,
    method = 'GET',
    body?: unknown,
    auth = true,
    retried = false
  ): Promise<T> {
    // X-Requested-With：Cookie 会话的写请求需携带同源标记（后端 CSRF 双保险校验）
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      'X-Api-Version': API_VERSION
    };
    if (auth) {
      const token = this.getToken();
      if (token) headers.Authorization = `Bearer ${token}`;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let res: Awaited<ReturnType<typeof fetch>>;
    try {
      res = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method,
        headers,
        body: body != null ? JSON.stringify(body) : undefined,
        credentials: 'same-origin',
        signal: controller.signal
      });
    } catch {
      if (controller.signal.aborted) {
        throw new Error('请求超时，请稍后重试');
      }
      throw new Error('网络错误，请稍后重试');
    } finally {
      clearTimeout(timer);
    }
    const json = (await res.json().catch(() => ({}))) as ApiResponse<T>;
    if (res.status === 401 && auth && !retried) {
      const outcome = await this.refreshWithOutcome();
      if (outcome.kind === 'ok') return this.requestOnce(path, method, body, auth, true);
      // R15-K2：只有刷新端点明确拒绝（401/403）才判会话失效——清会话+跳登录。
      if (outcome.kind === 'invalid') {
        this.clearSession();
        this.onUnauthorized?.();
        throw new Error(localizeApiMessage(json.message, '登录已失效'));
      }
      // 无会话可续（游客/票据已被清）：不广播不跳转，按业务 401 抛出。
      if (outcome.kind === 'no-session') {
        throw new Error(localizeApiMessage(json.message, '请先登录'));
      }
      // transient（429/5xx/网络/超时）：保会话，按普通错误抛出让用户重试。
      throw new ApiHttpError('登录状态刷新失败，请稍后重试', outcome.status ?? 503);
    }
    // 401 = session invalid → clear & redirect. 403 = missing permission for this API only.
    if (res.status === 401) {
      this.clearSession();
      this.onUnauthorized?.();
      throw new Error(localizeApiMessage(json.message, '登录已失效'));
    }
    if (res.status === 403) {
      // R5-A3：403 必须带结构化 status —— Dashboard 的 statsNoPerm 判据读 e.status，
      // 裸 Error 让该判据成为死代码（A-R3-4 修复的构造侧缺口第四次复发）。
      throw new ApiHttpError(localizeApiMessage(json.message, '权限不足'), 403);
    }
    if (!res.ok || json.code !== 0) {
      throw new ApiHttpError(
        localizeApiMessage(json.message, `请求失败 (${res.status})`),
        res.status
      );
    }
    return json.data;
  }

  /**
   * 静默刷新（带成因分类）。专用通道而非走 requestOnce：
   * requestOnce 的 401 收口会把刷新端点自身的 401 直接 clearSession+跳登录，
   * 吞掉「瞬态失败 vs 明确拒绝」的区分（R15-K2）。
   */
  async refreshWithOutcome(): Promise<RefreshOutcome> {
    if (!this.hasSession()) return { kind: 'no-session' };
    if (this.refreshPromise) return this.refreshPromise;
    this.refreshPromise = this.doRefreshWithCrossTabLock();
    try {
      return await this.refreshPromise;
    } finally {
      this.refreshPromise = null;
    }
  }

  /**
   * R15-K1：生产 admin 走共享 HttpOnly Cookie 会话（跨标签页共享），而后端 refresh
   * 对同代并发零容忍（validate→revoke→refresh 串行签发）⇒ 多 tab 同刻软过期各自
   * 刷新必然互踢，败者 401 清会话跳登录（丢未保存表单）。Web Locks 把跨 tab 刷新
   * 串行化：后来者持前者刚换出的新代 token 再刷，必然成功。同 tab 并发仍由
   * refreshPromise 单飞兜底；无 navigator.locks 的环境（小程序/旧浏览器）直接执行。
   */
  private async doRefreshWithCrossTabLock(): Promise<RefreshOutcome> {
    const locks = (globalThis as { navigator?: { locks?: LockManagerLike } }).navigator?.locks;
    if (!locks) return this.doRefreshOnce();
    try {
      return await locks.request('aicabinet-auth-refresh', { ifAvailable: false }, async () =>
        this.doRefreshOnce()
      );
    } catch {
      // 拿锁异常（锁管理器被中止等罕见场景）——退化为直接刷新，与旧行为等价
      return this.doRefreshOnce();
    }
  }

  private async doRefreshOnce(): Promise<RefreshOutcome> {
    const headers: Record<string, string> = {
      // Cookie 会话写请求 CSRF 双保险（与各端对齐）
      'X-Requested-With': 'XMLHttpRequest'
    };
    const token = this.getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    try {
      const res = await this.fetchImpl(`${this.baseUrl}${API_PREFIX}/auth/refresh`, {
        method: 'POST',
        headers,
        credentials: 'same-origin'
      });
      if (res.status === 200) {
        const body = (await res.json().catch(() => ({}))) as ApiResponse<LoginResponse>;
        if (body.code === 0 && body.data) {
          // Cookie 模式下 setToken 内部不落 JWT（只续 Cookie + user meta），与登录同路径
          this.setToken(body.data.token, body.data.userId, body.data.expiresInSeconds);
          return { kind: 'ok' };
        }
        // 契约异常（200 但体不符）不该杀会话——按瞬态处理让调用方重试
        return { kind: 'transient', status: res.status };
      }
      if (res.status === 401 || res.status === 403) {
        return { kind: 'invalid', status: res.status };
      }
      // 429（网关 auth_ratelimit）/5xx（发版窗口）= 瞬态，会话保留
      return { kind: 'transient', status: res.status };
    } catch {
      // 网络层失败/超时 = 瞬态，会话保留
      return { kind: 'transient' };
    }
  }

  /** 兼容包装：true 仅当刷新成功；瞬态失败同样返回 false，但不清会话（见 refreshWithOutcome）。 */
  async refreshSilently(): Promise<boolean> {
    return (await this.refreshWithOutcome()).kind === 'ok';
  }

  loginByPassword(
    phone: string,
    password: string,
    captcha?: { captchaId: string; captchaCode: string }
  ) {
    return this.request<LoginResponse>(
      '/api/v2/auth/admin-password-login',
      'POST',
      {
        phoneNumber: phone,
        password,
        captchaId: captcha?.captchaId,
        captchaCode: captcha?.captchaCode
      },
      false
    );
  }

  verifyTwoFactor(challengeToken: string, code: string) {
    return this.request<LoginResponse>('/api/v2/auth/admin-2fa/verify', 'POST', {
      challengeToken,
      code
    });
  }

  recoveryTwoFactor(challengeToken: string, recoveryCode: string) {
    return this.request<LoginResponse>('/api/v2/auth/admin-2fa/recovery', 'POST', {
      challengeToken,
      recoveryCode
    });
  }

  fetchCaptcha() {
    return this.request<{ captchaId: string; imageBase64: string }>(
      '/api/v2/auth/captcha',
      'GET',
      undefined,
      false
    );
  }

  /** 运营后台忘记密码：发送短信验证码（须先过图形验证码）。 */
  sendAdminSmsCode(phoneNumber: string, captcha: { captchaId: string; captchaCode: string }) {
    const query = new URLSearchParams({
      phoneNumber,
      captchaId: captcha.captchaId,
      captchaCode: captcha.captchaCode
    });
    return this.request<void>(
      `/api/v2/auth/sms-code?${query.toString()}`,
      'POST',
      undefined,
      false
    );
  }

  /** 运营后台忘记密码：短信验证码 + 图形验证码重置密码。 */
  resetAdminPassword(payload: {
    phoneNumber: string;
    smsCode: string;
    captchaId: string;
    captchaCode: string;
    newPassword: string;
  }) {
    return this.request<void>('/api/v2/auth/admin-password-reset', 'POST', payload, false);
  }

  merchantLogin(phone: string, password: string) {
    return this.request<LoginResponse>(
      '/api/v2/auth/merchant-password-login',
      'POST',
      { phoneNumber: phone, password },
      false
    );
  }
}

export * from '@aicabinet/shared-types';
