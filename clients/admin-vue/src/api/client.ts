import { ApiClient } from '@aicabinet/shared-api';

const TOKEN_KEY = 'admin_token';
const USER_KEY = 'admin_userId';
const EXPIRES_KEY = 'admin_token_expires';
const COOKIE_AUTH_KEY = 'admin_cookie_auth';

function getBaseUrl() {
  return (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(EXPIRES_KEY);
  localStorage.removeItem(COOKIE_AUTH_KEY);
  localStorage.removeItem('admin_permissions');
  localStorage.removeItem('admin_active_nav');
}

/** Cookie 会话或 Bearer：统一鉴权头（写操作必须带 X-Requested-With）。 */
export function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = {
    'X-Requested-With': 'XMLHttpRequest',
    ...extra
  };
  const token = localStorage.getItem(TOKEN_KEY);
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
  getToken: () => localStorage.getItem(TOKEN_KEY),
  hasSession: isLoggedIn,
  setToken: (token: string, userId: string, expiresInSeconds?: number) => {
    // HttpOnly Cookie 模式下 refresh 也不要把 JWT 写回 localStorage。
    if (localStorage.getItem(COOKIE_AUTH_KEY) === '1') {
      localStorage.removeItem(TOKEN_KEY);
    } else {
      localStorage.setItem(TOKEN_KEY, token);
    }
    localStorage.setItem(USER_KEY, userId);
    const ms = (expiresInSeconds ?? 1800) * 1000;
    localStorage.setItem(EXPIRES_KEY, String(Date.now() + ms));
  },
  clearSession,
  onUnauthorized: () => {
    if (!globalThis.location.pathname.includes('/login')) {
      globalThis.location.assign('/admin/login');
    }
  }
});

export function getStoredUserId() {
  return localStorage.getItem(USER_KEY) || '';
}

export function applyLoginSession(data: {
  token: string;
  userId: string;
  expiresInSeconds?: number;
  cookieEnabled?: boolean;
}) {
  if (data.cookieEnabled) {
    // 服务端已写入 HttpOnly Cookie：token 不再落 localStorage，缩小 XSS 暴露面。
    // 请求凭 Cookie 自动携带，getToken() 返回空即不拼 Authorization 头。
    localStorage.removeItem(TOKEN_KEY);
    localStorage.setItem(COOKIE_AUTH_KEY, '1');
  } else {
    // prod/staging 由 ProductionStartupValidator 强制 cookieEnabled=true；此处仅本地/dev 回退 Bearer。
    if (import.meta.env.PROD) {
      console.warn(
        '[auth] cookieEnabled=false in production build; JWT would fall back to localStorage. Check AUTH_COOKIE_ENABLED.'
      );
    }
    localStorage.setItem(TOKEN_KEY, data.token);
    localStorage.removeItem(COOKIE_AUTH_KEY);
  }
  localStorage.setItem(USER_KEY, data.userId);
  const ms = (data.expiresInSeconds ?? 1800) * 1000;
  localStorage.setItem(EXPIRES_KEY, String(Date.now() + ms));
}

/** 登录态判定：本地 token 或 HttpOnly Cookie 会话标记任一存在即视为已登录。 */
export function isLoggedIn() {
  return Boolean(localStorage.getItem(TOKEN_KEY)) || localStorage.getItem(COOKIE_AUTH_KEY) === '1';
}

/** True when a local expiry was recorded and has passed (soft-expired; refresh may still work). */
export function isSessionSoftExpired() {
  const expiresAt = Number(localStorage.getItem(EXPIRES_KEY) || 0);
  return expiresAt > 0 && Date.now() >= expiresAt;
}

/** 登出：先通知服务端清除会话 Cookie，再清理本地状态（服务端调用失败不阻塞）。 */
export async function logoutSession() {
  try {
    await api.request<unknown>('/api/v2/auth/logout', 'POST', undefined, false);
  } catch {
    // 网络异常时 Cookie 仍会随过期时间失效；本地会话照常清理。
  } finally {
    clearSession();
  }
}

/** Compatibility helpers for views that expect `{ data }` wrappers. */
export async function get(path: string): Promise<{ data: any }> {
  const data = await api.request<any>(path, 'GET');
  return { data };
}

export async function post(path: string, body?: unknown): Promise<{ data: any }> {
  const data = await api.request<any>(path, 'POST', body);
  return { data };
}

export async function put(path: string, body?: unknown): Promise<{ data: any }> {
  const data = await api.request<any>(path, 'PUT', body);
  return { data };
}

export async function del(path: string): Promise<{ data: any }> {
  const data = await api.request<any>(path, 'DELETE');
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
