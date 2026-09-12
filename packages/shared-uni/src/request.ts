import { localizeApiMessage } from './format';

/** 刷新接口返回的会话数据（服务端 /api/v2/auth/refresh 的 data 字段）。 */
export interface MpRefreshData {
  token: string;
  userId?: string;
  expiresInSeconds?: number;
  serverBootEpoch?: number;
}

/** 带 HTTP/业务语义的请求错误，避免调用方靠文案 includes 判断鉴权失败。 */
export type MpApiError = Error & {
  status?: number;
  code?: string;
};

export function createMpApiError(message: string, status?: number, code?: string): MpApiError {
  const err = new Error(message) as MpApiError;
  if (status != null) err.status = status;
  if (code) err.code = code;
  return err;
}

/** 401/403 或显式 UNAUTHORIZED/FORBIDDEN 码 */
export function isMpAuthFailure(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false;
  const e = err as MpApiError;
  if (e.status === 401 || e.status === 403) return true;
  const code = String(e.code || '').toUpperCase();
  return code === 'UNAUTHORIZED' || code === 'FORBIDDEN';
}

/** 小程序端会话能力：由各客户端注入存储键与登录跳转策略。 */
export interface MpApiSession {
  baseUrl: string;
  isDevBuild?: boolean;
  timeoutMs?: number;
  /** GET/HEAD 超时/网络错误额外重试次数（不含首次），默认 2 */
  getRetryCount?: number;
  refreshPath?: string;
  getToken(): string;
  clearSession(): void;
  applyRefreshedToken(data: MpRefreshData): void;
  /** 登录失效：清理会话并返回错误（可按端决定是否跳转登录页）。 */
  handleUnauthorized(message?: string): Error;
}

let refreshInFlight: Promise<boolean> | null = null;

function isH5Runtime() {
  return (
    typeof window !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    !/miniProgram|miniprogram/i.test(navigator.userAgent)
  );
}

/** 本机/局域网调试地址：即使正式包也给出可操作提示。 */
function isLocalDebugApi(baseUrl: string): boolean {
  return /localhost|127\.0\.0\.1|192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\./i.test(baseUrl);
}

/** 请求失败文案：区分 request:fail（网络层）、timeout、以及服务端消息。 */
export function formatMpRequestError(
  errMsg: string | undefined,
  path: string,
  isDevBuild = false,
  baseUrl = ''
): string {
  const raw = errMsg || '网络错误';
  const verbose = isDevBuild || isLocalDebugApi(baseUrl);
  const apiHint = verbose && baseUrl ? `（当前 API：${baseUrl}）` : '';
  if (raw === 'request:fail' || raw.includes('request:fail')) {
    const pointsToLoopback = /localhost|127\.0\.0\.1/i.test(baseUrl);
    if (!isH5Runtime() && pointsToLoopback) {
      return (
        (verbose
          ? '真机访问不了电脑的 localhost。请把 VITE_API_BASE_URL 改成电脑局域网 IP（如 http://192.168.1.8），手机与电脑同一 WiFi，并重新编译'
          : '网络不太稳定，请稍后再试') + apiHint
      );
    }
    if (isH5Runtime()) {
      return (
        (verbose
          ? `网络不太稳定（${path}），请确认本机服务已启动后重试`
          : '网络不太稳定，请稍后再试') + apiHint
      );
    }
    // 真机 / 开发者工具常见：未勾选「不校验合法域名」，或防火墙/访客 WiFi 隔离
    if (verbose) {
      return (
        '请求失败：请确认 ① 开发者工具勾选「不校验合法域名、web-view、TLS」② 手机与电脑同一 WiFi（勿用访客网络）③ 管理员运行 scripts/open-lan-api-firewall.ps1 放行 80/18080 ④ 本机网关已启动' +
        apiHint
      );
    }
    return '网络不太稳定，请稍后再试';
  }
  if (raw.includes('timeout')) {
    return '请求超时，请稍后重试' + apiHint;
  }
  return localizeApiMessage(raw, '网络错误，请稍后重试');
}

/** 401 时静默刷新 token（单飞）；刷新失败由调用方决定如何收尾。 */
export async function refreshTokenSilently(opts: MpApiSession): Promise<boolean> {
  if (!opts.getToken()) return false;
  if (refreshInFlight) return refreshInFlight;
  const refreshPath = opts.refreshPath ?? '/api/v2/auth/refresh';
  const pending = new Promise<boolean>((resolve, reject) => {
    uni.request({
      url: opts.baseUrl + refreshPath,
      method: 'POST',
      header: {
        Authorization: 'Bearer ' + opts.getToken(),
        'Content-Type': 'application/json',
        // Cookie 会话写请求 CSRF 双保险（与 admin/shared-api 对齐）
        'X-Requested-With': 'XMLHttpRequest'
      },
      timeout: opts.timeoutMs ?? 20_000,
      success(res) {
        const body = res.data as { code?: number; data?: MpRefreshData };
        if (res.statusCode === 200 && body?.code === 0 && body.data?.token) {
          opts.applyRefreshedToken(body.data);
          resolve(true);
          return;
        }
        reject(createMpApiError('登录已失效', 401, 'UNAUTHORIZED'));
      },
      fail(err) {
        reject(
          createMpApiError(
            formatMpRequestError(err.errMsg, refreshPath, opts.isDevBuild, opts.baseUrl)
          )
        );
      }
    });
  }).finally(() => {
    refreshInFlight = null;
  });
  refreshInFlight = pending;
  return pending;
}

/**
 * 统一请求：拼 baseUrl、带鉴权头、401 静默刷新重试、403/业务码/网络错误本地化文案。
 * GET/HEAD 在超时/网络错误时额外最多重试 2 次（指数退避）；写操作不自动重试。
 * 与各端原有 request 签名保持一致（path, method, data, auth, retried）。
 */
export function mpRequest<T>(
  opts: MpApiSession,
  path: string,
  method: UniApp.RequestOptions['method'] = 'GET',
  data?: unknown,
  auth = true,
  retried = false
): Promise<T> {
  const methodUpper = String(method || 'GET').toUpperCase();
  const canRetry = methodUpper === 'GET' || methodUpper === 'HEAD';
  if (!canRetry) {
    return mpRequestOnce<T>(opts, path, method, data, auth, retried);
  }
  const maxAttempts = 1 + (opts.getRetryCount ?? 2);
  return (async () => {
    let lastError: unknown;
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        return await mpRequestOnce<T>(opts, path, method, data, auth, retried);
      } catch (err) {
        lastError = err;
        if (!isRetriableMpTransportError(err) || attempt >= maxAttempts - 1) {
          throw err;
        }
        await sleep(200 * 2 ** attempt);
      }
    }
    throw lastError instanceof Error ? lastError : createMpApiError('网络错误，请稍后重试');
  })();
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isRetriableMpTransportError(err: unknown): boolean {
  if (!(err instanceof Error)) return false;
  // 鉴权失败不重试；仅超时/网络层
  if (isMpAuthFailure(err)) return false;
  const msg = err.message || '';
  return (
    msg.includes('请求超时') ||
    msg.includes('网络不太稳定') ||
    msg.includes('网络错误') ||
    msg.includes('timeout')
  );
}

function mpRequestOnce<T>(
  opts: MpApiSession,
  path: string,
  method: UniApp.RequestOptions['method'] = 'GET',
  data?: unknown,
  auth = true,
  retried = false
): Promise<T> {
  return new Promise((resolve, reject) => {
    // X-Requested-With：H5 Cookie 会话的写请求需同源标记（后端 AuthInterceptor CSRF 双保险）
    const header: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    };
    if (auth && opts.getToken()) header.Authorization = 'Bearer ' + opts.getToken();
    uni.request({
      url: opts.baseUrl + path,
      method,
      data: data as UniApp.RequestOptions['data'],
      header,
      timeout: opts.timeoutMs ?? 20_000,
      success(res) {
        const body = res.data as { code?: number; message?: string; data?: T };
        if (res.statusCode === 401) {
          if (auth && !retried) {
            refreshTokenSilently(opts)
              .then(() =>
                mpRequestOnce<T>(opts, path, method, data, auth, true).then(resolve, reject)
              )
              .catch(() => reject(opts.handleUnauthorized(body?.message)));
            return;
          }
          reject(opts.handleUnauthorized(body?.message));
          return;
        }
        if (res.statusCode === 403) {
          reject(createMpApiError(localizeApiMessage(body?.message, '权限不足'), 403, 'FORBIDDEN'));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0) {
          resolve(body.data as T);
          return;
        }
        const err = createMpApiError(
          localizeApiMessage(body?.message, `请求失败 (${res.statusCode})`),
          res.statusCode
        );
        reject(err);
      },
      fail(err) {
        reject(
          createMpApiError(formatMpRequestError(err.errMsg, path, opts.isDevBuild, opts.baseUrl))
        );
      }
    });
  });
}
