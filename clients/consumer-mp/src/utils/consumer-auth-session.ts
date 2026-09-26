/**
 * 消费端登录会话规划（debt-tracker C12d；承接 C3 expires）。
 * 纯函数：禁止依赖 uni / request；落盘仍由 consumer-api.applyTokenSession 执行。
 */

export type LoginResponseSlice = {
  token?: string | null;
  userId?: string | null;
  expiresInSeconds?: number | null;
  cookieEnabled?: boolean | null;
  serverBootEpoch?: number | null;
};

export type TokenSessionApplyPlan =
  | {
      mode: 'cookie';
      userId?: string;
      expiresAtMs: number;
      serverBootEpoch?: number;
    }
  | {
      mode: 'bearer';
      token: string;
      userId?: string;
      expiresAtMs: number;
      serverBootEpoch?: number;
    };

/** 校验登录响应过期字段；非法则抛错（文案与历史 applyTokenSession 一致）。 */
export function assertLoginExpires(data: LoginResponseSlice): number {
  if (
    data.expiresInSeconds == null ||
    !Number.isFinite(Number(data.expiresInSeconds)) ||
    Number(data.expiresInSeconds) <= 0
  ) {
    throw new Error('登录响应缺少有效过期时间');
  }
  return Number(data.expiresInSeconds);
}

/**
 * 规划落盘：H5 + cookieEnabled（或已在 Cookie 会话）→ cookie 模式；否则必须有 token。
 */
export function planTokenSessionApply(
  data: LoginResponseSlice,
  opts: { isH5: boolean; alreadyCookieAuth: boolean; nowMs?: number }
): TokenSessionApplyPlan {
  const expiresInSeconds = assertLoginExpires(data);
  const nowMs = opts.nowMs ?? Date.now();
  const expiresAtMs = nowMs + expiresInSeconds * 1000;
  const userId = data.userId ? String(data.userId) : undefined;
  const serverBootEpoch = data.serverBootEpoch != null ? Number(data.serverBootEpoch) : undefined;
  const preferCookie = opts.isH5 && (Boolean(data.cookieEnabled) || opts.alreadyCookieAuth);
  if (preferCookie) {
    return { mode: 'cookie', userId, expiresAtMs, serverBootEpoch };
  }
  if (!data.token) {
    throw new Error('登录响应缺少 token');
  }
  return {
    mode: 'bearer',
    token: String(data.token),
    userId,
    expiresAtMs,
    serverBootEpoch
  };
}

export function passwordLoginBody(phone: string, password: string) {
  return { phoneNumber: phone, password };
}

export function smsLoginBody(phone: string, code: string) {
  return { phoneNumber: phone, code };
}

export function wxLoginBody(code: string, phoneNumber?: string) {
  return { code, phoneNumber: phoneNumber || undefined };
}

export function alipayLoginBody(authCode: string) {
  return { authCode };
}

export function wxH5LoginBody(code: string) {
  return { code };
}
