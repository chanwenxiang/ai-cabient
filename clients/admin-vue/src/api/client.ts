import { ApiClient } from '@aicabinet/shared-api';
import {
  applyLoginSessionStorage,
  clearAuthStorage,
  clearBearerToken,
  getBearerToken,
  getStoredUserId as readStoredUserId,
  isCookieAuthMode,
  isLoggedIn as readIsLoggedIn,
  isSessionSoftExpired as readIsSessionSoftExpired,
  migrateLegacyTokenStorage,
  setBearerToken,
  setStoredUserMeta
} from './auth-storage';
import { AuthEndpoints } from './endpoints';

migrateLegacyTokenStorage();

/** 主动退出中：抑制在途请求 401 触发的「请先登录」提示与重复跳转。 */
let loggingOut = false;
let endLogoutTimer: ReturnType<typeof setTimeout> | undefined;

export function beginLogout() {
  loggingOut = true;
  if (endLogoutTimer !== undefined) {
    clearTimeout(endLogoutTimer);
    endLogoutTimer = undefined;
  }
}

export function endLogout() {
  loggingOut = false;
  if (endLogoutTimer !== undefined) {
    clearTimeout(endLogoutTimer);
    endLogoutTimer = undefined;
  }
}

export function isLoggingOut() {
  return loggingOut;
}

function getBaseUrl() {
  return (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
}

export function clearSession() {
  clearAuthStorage();
}

/** Cookie 会话或 Bearer：统一鉴权头（写操作必须带 X-Requested-With）。 */
export function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = {
    'X-Requested-With': 'XMLHttpRequest',
    ...extra
  };
  const token = getBearerToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

/** 同源带 Cookie 的 fetch，供上传/下载等非 JSON ApiClient 路径使用。 */
export function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const mergedHeaders = {
    ...authHeaders(),
    ...(init.headers as Record<string, string> | undefined)
  };
  return fetch(input, {
    ...init,
    credentials: 'same-origin',
    headers: mergedHeaders
  });
}

export const api = new ApiClient({
  baseUrl: getBaseUrl(),
  getToken: () => getBearerToken(),
  hasSession: isLoggedIn,
  setToken: (token: string, userId: string, expiresInSeconds?: number) => {
    // HttpOnly Cookie：refresh 也不要把 JWT 写入任何 Storage。
    if (isCookieAuthMode()) {
      clearBearerToken();
      setStoredUserMeta(userId, expiresInSeconds);
      return;
    }
    // A-P2-006：生产构建禁止 Bearer 回退持久化
    if (import.meta.env.PROD) {
      console.error(
        '[auth] production build requires cookie session; refusing to persist JWT on refresh. Check AUTH_COOKIE_ENABLED.'
      );
      clearAuthStorage();
      return;
    }
    setBearerToken(token);
    setStoredUserMeta(userId, expiresInSeconds);
  },
  clearSession,
  onUnauthorized: () => {
    if (loggingOut) return;
    if (!globalThis.location.pathname.includes('/login')) {
      globalThis.location.assign('/admin/login');
    }
  }
});

export function getStoredUserId() {
  return readStoredUserId();
}

/**
 * 应用登录会话。生产且 cookieEnabled=false 时抛错（fail-closed）。
 * Cookie 模式不落 JWT；dev 非 Cookie 仅用 sessionStorage。
 */
export function applyLoginSession(data: {
  token: string;
  userId: string;
  expiresInSeconds?: number;
  cookieEnabled?: boolean;
}) {
  const ok = applyLoginSessionStorage(data);
  if (!ok) {
    throw new Error('生产环境必须启用 Cookie 会话（AUTH_COOKIE_ENABLED），无法持久化 JWT');
  }
}

/** 登录态判定：sessionStorage Bearer 或 HttpOnly Cookie 会话标记。 */
export function isLoggedIn() {
  return readIsLoggedIn();
}

/** True when a local expiry was recorded and has passed (soft-expired; refresh may still work). */
export function isSessionSoftExpired() {
  return readIsSessionSoftExpired();
}

/** 登出：先通知服务端清除会话 Cookie，再清理本地状态（服务端调用失败不阻塞）。 */
export async function logoutSession() {
  beginLogout();
  try {
    await api.request<unknown>(AuthEndpoints.logout, 'POST', undefined, false);
  } catch {
    // 网络异常时 Cookie 仍会随过期时间失效；本地会话照常清理。
  } finally {
    clearSession();
    // A-P2-001：无论从哪条路径 logout，都必须在收尾窗口后放开抑制，避免长期吞 401 Toast
    if (typeof window !== 'undefined') {
      if (endLogoutTimer !== undefined) clearTimeout(endLogoutTimer);
      endLogoutTimer = setTimeout(() => endLogout(), 2500);
    } else {
      endLogout();
    }
  }
}

/** Compatibility helpers for views that expect `{ data }` wrappers. */
export async function get<T = unknown>(path: string): Promise<{ data: T }> {
  const data = await api.request<T>(path, 'GET');
  return { data };
}

export async function post<T = unknown>(path: string, body?: unknown): Promise<{ data: T }> {
  const data = await api.request<T>(path, 'POST', body);
  return { data };
}

export async function put<T = unknown>(path: string, body?: unknown): Promise<{ data: T }> {
  const data = await api.request<T>(path, 'PUT', body);
  return { data };
}

export async function del<T = unknown>(path: string): Promise<{ data: T }> {
  const data = await api.request<T>(path, 'DELETE');
  return { data };
}

/** Download authenticated CSV/binary endpoints (not JSON ApiResponse). */
export async function downloadAuthFile(path: string, fallbackName: string) {
  const res = await authFetch(`${getBaseUrl()}${path}`);
  if (res.status === 401) {
    clearSession();
    if (!globalThis.location.pathname.includes('/login')) {
      globalThis.location.assign('/admin/login');
    }
    throw new Error('登录已失效');
  }
  if (res.status === 403) {
    throw new Error('权限不足');
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({}) as { message?: string });
    throw new Error(err.message || `下载失败 (${res.status})`);
  }
  const blob = await res.blob();
  const cd = res.headers.get('Content-Disposition') || '';
  const match = /filename\*?=(?:UTF-8''|")?([^";]+)/i.exec(cd);
  const rawName = match ? decodeURIComponent(match[1].replaceAll('"', '')) : fallbackName;
  // Strip path segments / traversal so download attribute cannot escape intended name.
  const filename =
    rawName
      .replace(/[/\\]/g, '_')
      .replace(/\.\./g, '_')
      .replace(/[^\w.\u4e00-\u9fff\-()[\]]+/g, '_')
      .replace(/^\.+/, '')
      .slice(0, 180) || fallbackName;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
