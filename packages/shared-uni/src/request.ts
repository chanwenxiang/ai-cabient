import { localizeApiMessage } from './format';

/** 刷新接口返回的会话数据（服务端 /api/v2/auth/refresh 的 data 字段）。 */
export interface MpRefreshData {
  token: string;
  userId?: string;
  expiresInSeconds?: number;
  serverBootEpoch?: number;
}

/** 小程序端会话能力：由各客户端注入存储键与登录跳转策略。 */
export interface MpApiSession {
  baseUrl: string;
  isDevBuild?: boolean;
  timeoutMs?: number;
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

/** 请求失败文案：区分 request:fail（网络层）、timeout、以及服务端消息。 */
export function formatMpRequestError(
  errMsg: string | undefined,
  path: string,
  isDevBuild = false,
  baseUrl = ''
): string {
  const raw = errMsg || '网络错误';
  if (raw === 'request:fail' || raw.includes('request:fail')) {
    const pointsToLoopback = /localhost|127\.0\.0\.1/i.test(baseUrl);
    if (!isH5Runtime() && pointsToLoopback) {
      return isDevBuild
        ? '真机访问不了电脑的 localhost。请把 VITE_API_BASE_URL 改成电脑局域网 IP（如 http://192.168.1.8），手机与电脑同一 WiFi，并重启小程序编译'
        : '网络不太稳定，请稍后再试';
    }
    if (isH5Runtime()) {
      return isDevBuild
        ? `网络不太稳定（${path}），请确认本机服务已启动后重试`
        : '网络不太稳定，请稍后再试';
    }
    return isDevBuild
      ? '网络不太稳定，请稍后再试。开发调试时可在微信开发者工具勾选「不校验合法域名」，真机请用局域网 IP 作为 VITE_API_BASE_URL'
      : '网络不太稳定，请稍后再试';
  }
  if (raw.includes('timeout')) {
    return '请求超时，请稍后重试';
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
        'Content-Type': 'application/json'
      },
      timeout: opts.timeoutMs ?? 20_000,
      success(res) {
        const body = res.data as { code?: number; data?: MpRefreshData };
        if (res.statusCode === 200 && body?.code === 0 && body.data?.token) {
          opts.applyRefreshedToken(body.data);
          resolve(true);
          return;
        }
        reject(new Error('登录已失效'));
      },
      fail(err) {
        reject(new Error(formatMpRequestError(err.errMsg, refreshPath, opts.isDevBuild, opts.baseUrl)));
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
  return new Promise((resolve, reject) => {
    const header: Record<string, string> = { 'Content-Type': 'application/json' };
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
              .then(() => mpRequest<T>(opts, path, method, data, auth, true).then(resolve, reject))
              .catch(() => reject(opts.handleUnauthorized(body?.message)));
            return;
          }
          reject(opts.handleUnauthorized(body?.message));
          return;
        }
        if (res.statusCode === 403) {
          reject(new Error(localizeApiMessage(body?.message, '权限不足')));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0) {
          resolve(body.data as T);
          return;
        }
        const err = new Error(
          localizeApiMessage(body?.message, `请求失败 (${res.statusCode})`)
        ) as Error & { status?: number };
        err.status = res.statusCode;
        reject(err);
      },
      fail(err) {
        reject(new Error(formatMpRequestError(err.errMsg, path, opts.isDevBuild, opts.baseUrl)));
      }
    });
  });
}
