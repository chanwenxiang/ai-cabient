import { beforeEach, describe, expect, it } from 'vitest';
import { clearAuthStorage } from './auth-storage';

/**
 * MA-020（2026-11-01 审计订正）：应用侧不再保存登录口令。
 * 这里钉的是「登出一定抹掉历史明文」这一条腿——不依赖 `admin_remember_password` 开关，
 * 因为旧版本是「勾选 = 保留」，只要还按开关判断，历史机器上的可逆口令就永远清不掉。
 */
function fakeStorage(seed: Record<string, string> = {}) {
  const map = new Map<string, string>(Object.entries(seed));
  return {
    getItem: (k: string) => (map.has(k) ? (map.get(k) as string) : null),
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    has: (k: string) => map.has(k)
  };
}

describe('auth-storage · MA-020 登出抹掉历史口令', () => {
  let ls: ReturnType<typeof fakeStorage>;
  let ss: ReturnType<typeof fakeStorage>;

  beforeEach(() => {
    ls = fakeStorage({
      admin_token: 'jwt',
      admin_userId: '1',
      admin_token_expires: '999',
      admin_permissions: '[]',
      admin_active_nav: '[]',
      // btoa('123456') 的产物；这里只是历史遗留值，测试不依赖它能解回什么
      admin_password: 'MTIzNDU2',
      admin_remember_password: '1',
      admin_remember_phone: '1',
      admin_phone: '13900000001'
    });
    ss = fakeStorage({ admin_token: 'jwt' });
    (globalThis as unknown as { localStorage: unknown }).localStorage = ls;
    (globalThis as unknown as { sessionStorage: unknown }).sessionStorage = ss;
  });

  it('勾选着「记住」也删 admin_password（旧口径只在取消时删 ⇒ 明文会永久留机）', () => {
    expect(ls.has('admin_password')).toBe(true);
    clearAuthStorage();
    expect(ls.has('admin_password')).toBe(false);
    expect(ls.has('admin_remember_password')).toBe(false);
  });

  it('口令被清，但「记住账号」仍保留手机号（AU-144 语义不回退）', () => {
    clearAuthStorage();
    expect(ls.getItem('admin_phone')).toBe('13900000001');
  });

  it('显式取消勾选时手机号照旧被清（回归：别把 MA-020 改成一律保留）', () => {
    ls.setItem('admin_remember_phone', '0');
    clearAuthStorage();
    expect(ls.has('admin_phone')).toBe(false);
  });
});
