import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  decodeJwtExpirySeconds,
  ensureFreshSession,
  isTokenExpiringSoon,
  mpRequest,
  refreshTokenSilently,
  type MpApiSession
} from '@aicabinet/shared-uni/request';

/**
 * 会话续期契约（2026-11-11）：
 * ① 冷启动不再拿过期 token 去撞业务接口（`ensureFreshSession` 仅在临期时换票）；
 * ② refresh 的**失败分档**：只有 401/403 才算会话失效，429（网关限流）/5xx/契约异常
 *    必须保留登录态——旧实现无条件压成 `401 UNAUTHORIZED`，会让补货员被莫名踢回登录页。
 *
 * 这两条都在 `packages/shared-uni/src/request.ts`，三端共用，故在 merchant-mp 的 vitest 里
 * 用 alias 指向 shared-uni 源码验证（vitest.config.ts 已配）。
 */

/** base64url 编码 JWT payload（node 环境用 Buffer；shared-uni 的解码是手写实现，不吃 atob）。 */
function jwtWithPayload(payload: Record<string, unknown>) {
  const enc = (o: unknown) => Buffer.from(JSON.stringify(o)).toString('base64url');
  return `${enc({ alg: 'HS256' })}.${enc(payload)}.sig`;
}

function tokenExpiringIn(seconds: number, extra: Record<string, unknown> = {}) {
  return jwtWithPayload({
    sub: '100000002',
    scope: 'session',
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + seconds,
    ...extra
  });
}

type UniRequestOpts = {
  success?: (res: { statusCode: number; data: unknown }) => void;
  fail?: (err: { errMsg: string }) => void;
};

let uniRequest: ReturnType<typeof vi.fn>;

function mockUni() {
  uniRequest = vi.fn((opts: UniRequestOpts) => {
    opts.success?.({ statusCode: 200, data: { code: 0, data: { token: 'new-token' } } });
  });
  (globalThis as unknown as { uni: unknown }).uni = { request: uniRequest };
}

function buildSession(overrides: Partial<MpApiSession> = {}) {
  const applied: unknown[] = [];
  const unauthorized: string[] = [];
  const session: MpApiSession = {
    baseUrl: 'http://127.0.0.1',
    getToken: () => tokenExpiringIn(3600),
    hasSession: () => true,
    clearSession: vi.fn(),
    applyRefreshedToken: (data) => applied.push(data),
    handleUnauthorized: (message) => {
      unauthorized.push(String(message || ''));
      return new Error('登录已失效');
    },
    ...overrides
  };
  return { session, applied, unauthorized };
}

describe('decodeJwtExpirySeconds', () => {
  it('解出 exp（秒）', () => {
    const exp = Math.floor(Date.now() / 1000) + 1800;
    expect(decodeJwtExpirySeconds(jwtWithPayload({ exp }))).toBe(exp);
  });

  it('后端真实签发的 token（2026-11-11 商户登录快照）也能解出 exp', () => {
    // payload 逐字节取自 `POST /api/v2/auth/merchant-password-login` 的真实响应，
    // 只把签名段换成占位符（解码器只读 payload）。这条钉住「手写 base64url 解码
    // 与 jjwt 产出格式对齐」——不依赖我自造的测试向量。
    const realPayload =
      'eyJqdGkiOiIzNTFmNjRlOGI0ZDc0MTI2ODJmZWJjYTI3ZjkwYmUyMyIsInN1YiI6IjEwMDAwMDAwMiIsImJvb3QiOjE3OTMwODQ3NzY4ODQsInNjb3BlIjoic2Vzc2lvbiIsImlhdCI6MTc5NDM3ODAxMiwiZXhwIjoxNzk0Mzc5ODEyLCJhY3QiOiJPUEVSQVRPUiJ9';
    const realToken = `eyJhbGciOiJIUzI1NiJ9.${realPayload}.sig`;
    expect(decodeJwtExpirySeconds(realToken)).toBe(1794379812);
  });

  it('非 JWT / 缺 exp / 乱码一律返回 null（fail-open）', () => {
    expect(decodeJwtExpirySeconds('')).toBeNull();
    expect(decodeJwtExpirySeconds('not-a-jwt')).toBeNull();
    expect(decodeJwtExpirySeconds(jwtWithPayload({ sub: 'x' }))).toBeNull();
    expect(decodeJwtExpirySeconds('a.%%.c')).toBeNull();
  });
});

describe('isTokenExpiringSoon', () => {
  it('剩余 1 小时 → 不算临期', () => {
    expect(isTokenExpiringSoon(tokenExpiringIn(3600))).toBe(false);
  });

  it('剩余 30 秒 → 临期', () => {
    expect(isTokenExpiringSoon(tokenExpiringIn(30))).toBe(true);
  });

  it('已过期 5 分钟 → 临期', () => {
    expect(isTokenExpiringSoon(tokenExpiringIn(-300))).toBe(true);
  });

  it('解析不出 exp 时 fail-open（返回 false，交回 401 自愈）', () => {
    expect(isTokenExpiringSoon('garbage')).toBe(false);
  });
});

describe('ensureFreshSession', () => {
  beforeEach(mockUni);
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('token 充裕 → 一次网络请求都不发', async () => {
    const { session } = buildSession();
    await expect(ensureFreshSession(session)).resolves.toBe(false);
    expect(uniRequest).not.toHaveBeenCalled();
  });

  it('无本地 JWT（H5 Cookie 会话）→ 不动作', async () => {
    const { session } = buildSession({ getToken: () => '' });
    await expect(ensureFreshSession(session)).resolves.toBe(false);
    expect(uniRequest).not.toHaveBeenCalled();
  });

  it('token 临期 → 先换票再放行', async () => {
    const { session, applied } = buildSession({ getToken: () => tokenExpiringIn(10) });
    await expect(ensureFreshSession(session)).resolves.toBe(true);
    expect(uniRequest).toHaveBeenCalledTimes(1);
    expect(applied).toEqual([{ token: 'new-token' }]);
  });

  it('🔴 续期被限流（429）→ 不得清会话、不得登出', async () => {
    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      opts.success?.({ statusCode: 429, data: { code: 429, message: '请求过于频繁' } });
    });
    const { session, unauthorized, applied } = buildSession({
      getToken: () => tokenExpiringIn(-60)
    });
    await expect(ensureFreshSession(session)).resolves.toBe(false);
    expect(unauthorized).toEqual([]);
    expect(applied).toEqual([]);
    expect(session.clearSession).not.toHaveBeenCalled();
  });

  it('续期遇到 5xx / 网络失败 → 同样保留会话', async () => {
    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      opts.success?.({ statusCode: 502, data: { code: 502 } });
    });
    const { session, unauthorized } = buildSession({ getToken: () => tokenExpiringIn(-60) });
    await expect(ensureFreshSession(session)).resolves.toBe(false);
    expect(unauthorized).toEqual([]);

    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      opts.fail?.({ errMsg: 'request:fail timeout' });
    });
    await expect(ensureFreshSession(session)).resolves.toBe(false);
    expect(unauthorized).toEqual([]);
  });

  it('续期被明确拒绝（401）→ 交回上层处理，此处不自行登出', async () => {
    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      opts.success?.({ statusCode: 401, data: { code: 401, message: '登录已失效' } });
    });
    const { session, unauthorized } = buildSession({ getToken: () => tokenExpiringIn(-60) });
    await expect(ensureFreshSession(session)).resolves.toBe(false);
    // ensureFreshSession 只负责「不让过期票上路」，登出仍由业务请求的 401 分档触发
    expect(unauthorized).toEqual([]);
  });
});

describe('refreshTokenSilently 失败分档', () => {
  beforeEach(mockUni);

  it('401 → 判会话失效', async () => {
    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      opts.success?.({ statusCode: 401, data: { code: 401, message: '登录已失效' } });
    });
    const { session } = buildSession();
    await expect(refreshTokenSilently(session)).rejects.toMatchObject({
      status: 401,
      code: 'UNAUTHORIZED'
    });
  });

  it('429 → REFRESH_RATE_LIMITED（不判会话失效）', async () => {
    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      opts.success?.({ statusCode: 429, data: { code: 429, message: '请求过于频繁' } });
    });
    const { session } = buildSession();
    await expect(refreshTokenSilently(session)).rejects.toMatchObject({
      status: 429,
      code: 'REFRESH_RATE_LIMITED'
    });
  });

  it('500 → 透传状态码（不判会话失效）', async () => {
    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      opts.success?.({ statusCode: 500, data: { code: 500 } });
    });
    const { session } = buildSession();
    await expect(refreshTokenSilently(session)).rejects.toMatchObject({ status: 500 });
  });

  it('200 但无 token 且非 Cookie 会话 → 契约异常，不判会话失效', async () => {
    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      opts.success?.({ statusCode: 200, data: { code: 0, data: {} } });
    });
    const { session } = buildSession();
    await expect(refreshTokenSilently(session)).rejects.toMatchObject({
      code: 'REFRESH_FAILED'
    });
  });

  it('200 + token → 换票成功', async () => {
    const { session, applied } = buildSession();
    await expect(refreshTokenSilently(session)).resolves.toBe(true);
    expect(applied).toEqual([{ token: 'new-token' }]);
  });
});

/**
 * 2026-11-11 消费者端实证：`POST /api/v2/marketing/placement-events 401 60B` 出现**两次**。
 * 60B = 后端「请先登录」（无凭证），不是「登录已失效」（81B）。成因是 `refreshTokenSilently`
 * 在无会话时 `return false`（resolve 而非 reject），而 401 分支的 `.then()` 不看返回值照样重放
 * ⇒ 一次失败变两次请求，第二次带 retried=true 还会落到 `handleUnauthorized`，
 * 把「游客态」误当「登录已失效」。下面钉住三条：不重放 / 不清会话 / 正常会话仍能自愈。
 */
describe('401 分档：无会话可续时不得重放', () => {
  const BUSINESS_PATH = '/api/v2/marketing/placement-events';

  // 顶层 describe 的 beforeEach 只作用于它自己（见上），这里必须显式重建 uni mock
  beforeEach(() => {
    mockUni();
  });

  it('游客态（无 token）：只发一次，不重放、不判登录失效', async () => {
    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      opts.success?.({ statusCode: 401, data: { code: 401, message: '请先登录' } });
    });
    const { session, unauthorized, applied } = buildSession({
      getToken: () => '',
      hasSession: () => false
    });

    await expect(mpRequest(session, BUSINESS_PATH, 'POST', {})).rejects.toMatchObject({
      status: 401,
      code: 'UNAUTHORIZED'
    });

    expect(uniRequest).toHaveBeenCalledTimes(1);
    expect(uniRequest.mock.calls[0][0]).toMatchObject({ url: session.baseUrl + BUSINESS_PATH });
    expect(unauthorized).toHaveLength(0);
    expect(applied).toHaveLength(0);
    expect(session.clearSession).not.toHaveBeenCalled();
  });

  it('已登录且刷新成功：换票后重放一次并拿到 200（自愈路径没被改坏）', async () => {
    uniRequest.mockImplementation((opts: UniRequestOpts) => {
      const url = String((opts as unknown as { url?: string }).url || '');
      if (url.includes('/api/v2/auth/refresh')) {
        opts.success?.({ statusCode: 200, data: { code: 0, data: { token: 'tk-new' } } });
        return;
      }
      const seen = uniRequest.mock.calls.filter((c) => String(c[0].url).includes(BUSINESS_PATH));
      if (seen.length <= 1) {
        opts.success?.({ statusCode: 401, data: { code: 401, message: '登录已失效' } });
        return;
      }
      opts.success?.({ statusCode: 200, data: { code: 0, data: { ok: true } } });
    });
    const { session, applied, unauthorized } = buildSession();

    await expect(mpRequest(session, BUSINESS_PATH, 'POST', {})).resolves.toEqual({ ok: true });

    expect(applied).toEqual([{ token: 'tk-new' }]);
    expect(unauthorized).toHaveLength(0);
    expect(
      uniRequest.mock.calls.filter((c) => String(c[0].url).includes(BUSINESS_PATH))
    ).toHaveLength(2);
  });
});
