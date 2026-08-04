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
    fetchImpl?: typeof fetch;
}
export declare class ApiClient {
    private readonly baseUrl;
    private readonly getToken;
    private readonly setToken;
    private readonly clearSession;
    private readonly onUnauthorized?;
    private readonly fetchImpl;
    private refreshPromise;
    constructor(opts: ApiClientOptions);
    request<T>(path: string, method?: string, body?: unknown, auth?: boolean, retried?: boolean): Promise<T>;
    refreshSilently(): Promise<boolean>;
    loginByPassword(phone: string, password: string): Promise<LoginResponse>;
    merchantLogin(phone: string, password: string): Promise<LoginResponse>;
}
export * from '@aicabinet/shared-types';
