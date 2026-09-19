import { afterEach, describe, expect, it, vi } from 'vitest';
import { secureRandomToken } from './secure-id';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('secureRandomToken', () => {
  it('默认 8 字节产出 16 位小写十六进制', () => {
    expect(secureRandomToken()).toMatch(/^[0-9a-f]{16}$/);
  });

  it('长度跟随 byteLen（用于幂等键/请求号，长度不能漂）', () => {
    expect(secureRandomToken(1)).toMatch(/^[0-9a-f]{2}$/);
    expect(secureRandomToken(4)).toMatch(/^[0-9a-f]{8}$/);
    expect(secureRandomToken(16)).toMatch(/^[0-9a-f]{32}$/);
  });

  it('连续调用不重复（幂等键相撞会被服务端判为重放）', () => {
    const tokens = new Set(Array.from({ length: 200 }, () => secureRandomToken()));
    expect(tokens.size).toBe(200);
  });

  it('无 Web Crypto 的运行时走兜底实现，仍是合法 hex 且不重复', () => {
    vi.stubGlobal('crypto', undefined);
    const first = secureRandomToken(8);
    const second = secureRandomToken(8);
    expect(first).toMatch(/^[0-9a-f]{16}$/);
    expect(second).toMatch(/^[0-9a-f]{16}$/);
    expect(first).not.toBe(second);
    const fallbackTokens = new Set(Array.from({ length: 50 }, () => secureRandomToken()));
    expect(fallbackTokens.size).toBe(50);
  });
});
