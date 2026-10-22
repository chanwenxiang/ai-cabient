/**
 * A-P2-006：非 Cookie 模式下 JWT 只用 sessionStorage（关页即清），禁止落 localStorage。
 * Cookie 模式仍不存 JWT。生产构建若 cookieEnabled=false 则拒绝持久化 Bearer。
 */
const TOKEN_KEY = 'admin_token';
const USER_KEY = 'admin_userId';
const EXPIRES_KEY = 'admin_token_expires';
const COOKIE_AUTH_KEY = 'admin_cookie_auth';

function canUseSessionStorage(): boolean {
  try {
    return typeof sessionStorage !== 'undefined';
  } catch {
    return false;
  }
}

function canUseLocalStorage(): boolean {
  try {
    return typeof localStorage !== 'undefined';
  } catch {
    return false;
  }
}

/** 一次性把历史 localStorage JWT 迁到 sessionStorage 并删除旧键。 */
export function migrateLegacyTokenStorage(): void {
  if (!canUseLocalStorage() || !canUseSessionStorage()) return;
  const legacy = localStorage.getItem(TOKEN_KEY);
  if (!legacy) return;
  if (!sessionStorage.getItem(TOKEN_KEY)) {
    sessionStorage.setItem(TOKEN_KEY, legacy);
  }
  localStorage.removeItem(TOKEN_KEY);
}

export function getBearerToken(): string | null {
  migrateLegacyTokenStorage();
  if (canUseSessionStorage()) {
    return sessionStorage.getItem(TOKEN_KEY);
  }
  return null;
}

export function setBearerToken(token: string): void {
  clearBearerToken();
  if (!canUseSessionStorage()) return;
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearBearerToken(): void {
  if (canUseSessionStorage()) sessionStorage.removeItem(TOKEN_KEY);
  if (canUseLocalStorage()) localStorage.removeItem(TOKEN_KEY);
}

export function isCookieAuthMode(): boolean {
  return canUseLocalStorage() && localStorage.getItem(COOKIE_AUTH_KEY) === '1';
}

export function setCookieAuthMode(enabled: boolean): void {
  if (!canUseLocalStorage()) return;
  if (enabled) {
    localStorage.setItem(COOKIE_AUTH_KEY, '1');
    clearBearerToken();
  } else {
    localStorage.removeItem(COOKIE_AUTH_KEY);
  }
}

export function clearAuthStorage(): void {
  clearBearerToken();
  if (canUseLocalStorage()) {
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(EXPIRES_KEY);
    localStorage.removeItem(COOKIE_AUTH_KEY);
    localStorage.removeItem('admin_permissions');
    localStorage.removeItem('admin_active_nav');
    // AU-144（2026-10-21 语义修订，用户拍板）：登出是否清记住手机号由「记住账号」开关决定——
    // 勾选（默认）= 保留（「记住」的本意就是跨登录会话）；显式取消勾选（'0'）= 清（不记住就不预填）。
    // 原实现一律清 ⇒ 默认勾选的「记住账号」被登出击穿（用户实证：退出后账号被清空）。
    // AU-144 原担忧「换账号预填错人」仍由 opt-out 开关覆盖：取消勾选登录后即不再预填。
    // 开关键 admin_remember_phone 本身永不清——它是用户的开关状态，清了会退回默认勾选。
    if (localStorage.getItem('admin_remember_phone') === '0') {
      localStorage.removeItem('admin_phone');
    }
    // 记住密码同口径（2026-10-22）：取消勾选（'0'）= 登出即清；勾选 = 保留供下次预填。
    if (localStorage.getItem('admin_remember_password') === '0') {
      localStorage.removeItem('admin_password');
    }
  }
}

export function getStoredUserId(): string {
  return (canUseLocalStorage() && localStorage.getItem(USER_KEY)) || '';
}

export function setStoredUserMeta(userId: string, expiresInSeconds?: number): void {
  if (!canUseLocalStorage()) return;
  localStorage.setItem(USER_KEY, userId);
  const ms = (expiresInSeconds ?? 1800) * 1000;
  localStorage.setItem(EXPIRES_KEY, String(Date.now() + ms));
}

export function isSessionSoftExpired(): boolean {
  if (!canUseLocalStorage()) return false;
  const expiresAt = Number(localStorage.getItem(EXPIRES_KEY) || 0);
  return expiresAt > 0 && Date.now() >= expiresAt;
}

export function isLoggedIn(): boolean {
  return Boolean(getBearerToken()) || isCookieAuthMode();
}

/**
 * 应用登录结果。生产构建且未启用 Cookie 时拒绝落库 JWT（fail-closed）。
 * @returns false 表示生产拒绝了 Bearer 回退
 */
export function applyLoginSessionStorage(data: {
  token: string;
  userId: string;
  expiresInSeconds?: number;
  cookieEnabled?: boolean;
}): boolean {
  if (data.cookieEnabled) {
    setCookieAuthMode(true);
    setStoredUserMeta(data.userId, data.expiresInSeconds);
    return true;
  }
  if (import.meta.env.PROD) {
    clearAuthStorage();
    console.error(
      '[auth] production build requires cookieEnabled=true; refusing to persist JWT. Check AUTH_COOKIE_ENABLED.'
    );
    return false;
  }
  setCookieAuthMode(false);
  setBearerToken(data.token);
  setStoredUserMeta(data.userId, data.expiresInSeconds);
  return true;
}

export const AUTH_STORAGE_KEYS = {
  TOKEN_KEY,
  USER_KEY,
  EXPIRES_KEY,
  COOKIE_AUTH_KEY
} as const;
