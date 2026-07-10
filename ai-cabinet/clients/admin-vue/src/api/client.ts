import { ApiClient } from '@aicabinet/shared-api';

const TOKEN_KEY = 'admin_token';
const USER_KEY = 'admin_userId';
const EXPIRES_KEY = 'admin_token_expires';

function getBaseUrl() {
  return (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin;
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(EXPIRES_KEY);
  localStorage.removeItem('admin_permissions');
}

export const api = new ApiClient({
  baseUrl: getBaseUrl(),
  getToken: () => localStorage.getItem(TOKEN_KEY),
  setToken: (token: string, userId: string) => {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, userId);
    localStorage.setItem(EXPIRES_KEY, String(Date.now() + 30 * 60 * 1000));
  },
  clearSession,
  onUnauthorized: () => {
    if (!window.location.hash.includes('/login')) {
      window.location.hash = '#/login';
    }
  }
});

export function getStoredUserId() {
  return localStorage.getItem(USER_KEY) || '';
}

export function applyLoginSession(data: { token: string; userId: string; expiresInSeconds?: number }) {
  localStorage.setItem(TOKEN_KEY, data.token);
  localStorage.setItem(USER_KEY, data.userId);
  const ms = (data.expiresInSeconds ?? 1800) * 1000;
  localStorage.setItem(EXPIRES_KEY, String(Date.now() + ms));
}

export function isLoggedIn() {
  return !!localStorage.getItem(TOKEN_KEY);
}
