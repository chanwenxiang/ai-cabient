import type { LoginResponse } from '@aicabinet/shared-types';
/** 当前对外 HTTP API 主版本（与后端 ApiVersions.CURRENT 对齐）。 */
export declare const API_VERSION: "v2";
export declare const API_PREFIX: "/api/v2";
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
    /**
     * GET/HEAD 在超时/网络错误时的额外重试次数（不含首次）。
     * 默认 2（共最多 3 次）；写操作永不自动重试。
     */
    getRetryCount?: number;
    fetchImpl?: typeof fetch;
}
/**
 * R15-K2：静默刷新的结果分类（口径对齐 shared-uni 2026-11-11 修正）。
 * 只有刷新端点「明确拒绝」（401/403）才是会话失效；429（网关 auth_ratelimit）、
 * 5xx（发版窗口）、网络抖动一律 transient——旧写法 catch-all 返回 false 让
 * 调用方清会话踢登录，把仍有效（7 天宽限完全可续）的会话误杀。
 */
export type RefreshOutcome = {
    kind: 'ok' | 'invalid' | 'transient' | 'no-session';
    /** transient/invalid 时刷新响应的 HTTP status（网络层失败缺省） */
    status?: number;
};
/**
 * 服务端拒绝（非 2xx 或业务码非 0）。带 HTTP `status`，供调用方区分 409 等——
 * 例如设备维护指令被「进行中业务」拦下时，运营二次确认后带 force 重试。
 */
export declare class ApiHttpError extends Error {
    readonly status: number;
    constructor(message: string, status: number);
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
    private readonly getRetryCount;
    private refreshPromise;
    constructor(opts: ApiClientOptions);
    request<T>(path: string, method?: string, body?: unknown, auth?: boolean, retried?: boolean): Promise<T>;
    private requestOnce;
    /**
     * 静默刷新（带成因分类）。专用通道而非走 requestOnce：
     * requestOnce 的 401 收口会把刷新端点自身的 401 直接 clearSession+跳登录，
     * 吞掉「瞬态失败 vs 明确拒绝」的区分（R15-K2）。
     */
    refreshWithOutcome(): Promise<RefreshOutcome>;
    /**
     * R15-K1：生产 admin 走共享 HttpOnly Cookie 会话（跨标签页共享），而后端 refresh
     * 对同代并发零容忍（validate→revoke→refresh 串行签发）⇒ 多 tab 同刻软过期各自
     * 刷新必然互踢，败者 401 清会话跳登录（丢未保存表单）。Web Locks 把跨 tab 刷新
     * 串行化：后来者持前者刚换出的新代 token 再刷，必然成功。同 tab 并发仍由
     * refreshPromise 单飞兜底；无 navigator.locks 的环境（小程序/旧浏览器）直接执行。
     */
    private doRefreshWithCrossTabLock;
    private doRefreshOnce;
    /** 兼容包装：true 仅当刷新成功；瞬态失败同样返回 false，但不清会话（见 refreshWithOutcome）。 */
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
    /** 运营后台忘记密码：发送短信验证码（须先过图形验证码）。 */
    sendAdminSmsCode(phoneNumber: string, captcha: {
        captchaId: string;
        captchaCode: string;
    }): Promise<void>;
    /** 运营后台忘记密码：短信验证码 + 图形验证码重置密码。 */
    resetAdminPassword(payload: {
        phoneNumber: string;
        smsCode: string;
        captchaId: string;
        captchaCode: string;
        newPassword: string;
    }): Promise<void>;
    merchantLogin(phone: string, password: string): Promise<LoginResponse>;
}
export * from '@aicabinet/shared-types';
