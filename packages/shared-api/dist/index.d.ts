import type { LoginResponse } from '@aicabinet/shared-types';
export type HttpAdapter = (input: {
    url: string;
    method: string;
    headers: Record<string, string>;
    body?: string;
}) => Promise<{
    status: number;
    json: () => Promise<unknown>;
}>;
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
    fetchImpl?: typeof fetch;
}
export declare class ApiClient {
    private readonly baseUrl;
    private readonly getToken;
    private readonly setToken;
    private readonly clearSession;
    private readonly onUnauthorized?;
    private readonly hasSession;
    private readonly fetchImpl;
    private readonly timeoutMs;
    private refreshPromise;
    constructor(opts: ApiClientOptions);
    request<T>(path: string, method?: string, body?: unknown, auth?: boolean, retried?: boolean): Promise<T>;
    refreshSilently(): Promise<boolean>;
    loginByPassword(phone: string, password: string, captcha?: {
        captchaId: string;
        captchaCode: string;
    }): Promise<LoginResponse>;
    verifyTwoFactor(challengeToken: string, code: string): Promise<LoginResponse>;
    recoveryTwoFactor(challengeToken: string, recoveryCode: string): Promise<LoginResponse>;
    fetchCaptcha(): Promise<{
        captchaId: string;
        imageBase64: string;
    }>;
    merchantLogin(phone: string, password: string): Promise<LoginResponse>;
}
export * from '@aicabinet/shared-types';
