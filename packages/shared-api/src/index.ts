import type { ApiResponse, LoginResponse } from '@aicabinet/shared-types';
import { localizeApiMessage } from '@aicabinet/shared-uni/format';

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
  fetchImpl?: typeof fetch;
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly getToken: () => string | null;
  private readonly setToken: ApiClientOptions['setToken'];
  private readonly clearSession: () => void;
  private readonly onUnauthorized?: () => void;
  private readonly fetchImpl: typeof fetch;
  private refreshPromise: Promise<boolean> | null = null;

  constructor(opts: ApiClientOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/$/, '');
    this.getToken = opts.getToken;
    this.setToken = opts.setToken;
    this.clearSession = opts.clearSession;
    this.onUnauthorized = opts.onUnauthorized;
    this.fetchImpl = opts.fetchImpl ?? fetch.bind(globalThis);
  }

  async request<T>(path: string, method = 'GET', body?: unknown, auth = true, retried = false): Promise<T> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (auth && this.getToken()) headers.Authorization = `Bearer ${this.getToken()}`;
    const res = await this.fetchImpl(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body != null ? JSON.stringify(body) : undefined
    });
    const json = (await res.json().catch(() => ({}))) as ApiResponse<T>;
    if (res.status === 401 && auth && !retried) {
      const ok = await this.refreshSilently();
      if (ok) return this.request(path, method, body, auth, true);
    }
    // 401 = session invalid → clear & redirect. 403 = missing permission for this API only.
    if (res.status === 401) {
      this.clearSession();
      this.onUnauthorized?.();
      throw new Error(localizeApiMessage(json.message, '登录已失效'));
    }
    if (res.status === 403) {
      throw new Error(localizeApiMessage(json.message, '权限不足'));
    }
    if (!res.ok || json.code !== 0) {
      throw new Error(localizeApiMessage(json.message, `请求失败 (${res.status})`));
    }
    return json.data;
  }

  async refreshSilently(): Promise<boolean> {
    if (!this.getToken()) return false;
    if (this.refreshPromise) return this.refreshPromise;
    this.refreshPromise = (async () => {
      try {
        const data = await this.request<LoginResponse>('/api/v2/auth/refresh', 'POST', undefined, true, true);
        this.setToken(data.token, data.userId, data.expiresInSeconds);
        return true;
      } catch {
        return false;
      } finally {
        this.refreshPromise = null;
      }
    })();
    return this.refreshPromise;
  }

  loginByPassword(phone: string, password: string) {
    return this.request<LoginResponse>('/api/v2/auth/admin-password-login', 'POST', { phoneNumber: phone, password }, false);
  }

  merchantLogin(phone: string, password: string) {
    return this.request<LoginResponse>('/api/v2/auth/admin-password-login', 'POST', { phoneNumber: phone, password }, false);
  }
}

export * from '@aicabinet/shared-types';
