import { describe, expect, it } from 'vitest';
import {
  alipayLoginBody,
  assertLoginExpires,
  passwordLoginBody,
  planTokenSessionApply,
  smsLoginBody,
  wxH5LoginBody,
  wxLoginBody
} from './consumer-auth-session';

describe('consumer-auth-session · C12d', () => {
  it('过期字段校验', () => {
    expect(() => assertLoginExpires({})).toThrow(/过期时间/);
    expect(() => assertLoginExpires({ expiresInSeconds: 0 })).toThrow(/过期时间/);
    expect(assertLoginExpires({ expiresInSeconds: 3600 })).toBe(3600);
  });

  it('H5 cookie 模式不要求 token', () => {
    const plan = planTokenSessionApply(
      { expiresInSeconds: 60, cookieEnabled: true, userId: 'u1' },
      { isH5: true, alreadyCookieAuth: false, nowMs: 1000 }
    );
    expect(plan).toEqual({
      mode: 'cookie',
      userId: 'u1',
      expiresAtMs: 1000 + 60_000,
      serverBootEpoch: undefined
    });
  });

  it('Bearer 缺 token 抛错；有 token 落 bearer', () => {
    expect(() =>
      planTokenSessionApply(
        { expiresInSeconds: 10 },
        { isH5: false, alreadyCookieAuth: false, nowMs: 0 }
      )
    ).toThrow(/token/);
    expect(
      planTokenSessionApply(
        { expiresInSeconds: 10, token: 'jwt', userId: 'u2', serverBootEpoch: 9 },
        { isH5: false, alreadyCookieAuth: false, nowMs: 100 }
      )
    ).toEqual({
      mode: 'bearer',
      token: 'jwt',
      userId: 'u2',
      expiresAtMs: 100 + 10_000,
      serverBootEpoch: 9
    });
  });

  it('登录 body', () => {
    expect(passwordLoginBody('138', 'p')).toEqual({ phoneNumber: '138', password: 'p' });
    expect(smsLoginBody('138', '1234')).toEqual({ phoneNumber: '138', code: '1234' });
    expect(wxLoginBody('c')).toEqual({ code: 'c', phoneNumber: undefined });
    expect(alipayLoginBody('a')).toEqual({ authCode: 'a' });
    expect(wxH5LoginBody('h')).toEqual({ code: 'h' });
  });
});
