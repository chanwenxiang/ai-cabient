export class ApiClient {
    constructor(opts) {
        this.refreshPromise = null;
        this.baseUrl = opts.baseUrl.replace(/\/$/, '');
        this.getToken = opts.getToken;
        this.setToken = opts.setToken;
        this.clearSession = opts.clearSession;
        this.onUnauthorized = opts.onUnauthorized;
        this.fetchImpl = opts.fetchImpl ?? fetch.bind(globalThis);
    }
    async request(path, method = 'GET', body, auth = true, retried = false) {
        const headers = { 'Content-Type': 'application/json' };
        if (auth && this.getToken())
            headers.Authorization = `Bearer ${this.getToken()}`;
        const res = await this.fetchImpl(`${this.baseUrl}${path}`, {
            method,
            headers,
            body: body != null ? JSON.stringify(body) : undefined
        });
        const json = (await res.json().catch(() => ({})));
        if (res.status === 401 && auth && !retried) {
            const ok = await this.refreshSilently();
            if (ok)
                return this.request(path, method, body, auth, true);
        }
        if (res.status === 401 || res.status === 403) {
            this.clearSession();
            this.onUnauthorized?.();
            throw new Error(json.message || (res.status === 403 ? '权限不足' : '登录已失效'));
        }
        if (!res.ok || json.code !== 0) {
            throw new Error(json.message || `请求失败 (${res.status})`);
        }
        return json.data;
    }
    async refreshSilently() {
        if (!this.getToken())
            return false;
        if (this.refreshPromise)
            return this.refreshPromise;
        this.refreshPromise = (async () => {
            try {
                const data = await this.request('/api/v2/auth/refresh', 'POST', undefined, true, true);
                this.setToken(data.token, data.userId);
                return true;
            }
            catch {
                return false;
            }
            finally {
                this.refreshPromise = null;
            }
        })();
        return this.refreshPromise;
    }
    loginByPassword(phone, password) {
        return this.request('/api/v2/auth/admin-password-login', 'POST', { phoneNumber: phone, password }, false);
    }
    merchantLogin(phone, password) {
        return this.request('/api/v2/auth/admin-password-login', 'POST', { phoneNumber: phone, password }, false);
    }
}
export * from '@aicabinet/shared-types';
