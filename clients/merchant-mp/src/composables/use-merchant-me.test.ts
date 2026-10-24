import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { meMock } = vi.hoisted(() => ({ meMock: vi.fn() }));

vi.mock('@/utils/merchant-api', () => ({
  merchantApi: { me: (...args: unknown[]) => meMock(...args) },
  isMerchantLoggedIn: () => true,
  hasPerm: () => true,
  registerMerchantSessionClearHook: () => {}
}));
vi.mock('@dcloudio/uni-app', () => ({
  onShow: () => {},
  onPageShow: () => {}
}));

import { refreshMerchantMe } from './useMerchantMe';

/** 与源码约定的存储键；写成字面量，防止改名后旧数据静默失效却没人发现 */
const STORAGE_KEY = 'merchant_me';

const storage = new Map<string, unknown>();

beforeEach(() => {
  storage.clear();
  meMock.mockReset();
  vi.stubGlobal('uni', {
    getStorageSync: (key: string) => (storage.has(key) ? storage.get(key) : ''),
    setStorageSync: (key: string, value: unknown) => {
      storage.set(key, value);
    },
    removeStorageSync: (key: string) => {
      storage.delete(key);
    }
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/**
 * 数据最小化门禁（2026-10-24 审计留置④）：
 * 本地弱网缓存不得落手机号——离线展示名兜底链为 displayName →「同事」，
 * 不需要手机号副本（useHomeWorkbench.hydrateFromCache 同口径）。
 * 两个写入口（refreshMerchantMe / login.finishLoginFlow）都必须走剥离形态；
 * 本测试钉住 refreshMerchantMe 这条，login 侧由同构 destructuring 保证。
 */
describe('useMerchantMe 本地缓存数据最小化', () => {
  it('refreshMerchantMe 落盘的 merchant_me 不含 phoneNumber，其余字段照常', async () => {
    meMock.mockResolvedValue({
      userId: 'U1',
      phoneNumber: '13800138000',
      displayName: '张三',
      merchants: [{ merchantId: 'M1', merchantName: '默认商户' }],
      permissions: ['ops:device:edit'],
      enabledPacks: ['field']
    });
    await refreshMerchantMe();
    const cached = storage.get(STORAGE_KEY) as Record<string, unknown> | undefined;
    expect(cached).toBeTruthy();
    expect(cached).not.toHaveProperty('phoneNumber');
    expect(cached?.displayName).toBe('张三');
    expect(cached?.userId).toBe('U1');
  });

  it('内存态 meRef 不受剥离影响：refreshMerchantMe 返回值仍带完整资料', async () => {
    meMock.mockResolvedValue({
      userId: 'U2',
      phoneNumber: '13900000000',
      displayName: '李四',
      merchants: [],
      permissions: []
    });
    const me = await refreshMerchantMe();
    expect(me.phoneNumber).toBe('13900000000');
    // storage 里的副本依然剥离
    expect(storage.get(STORAGE_KEY)).not.toHaveProperty('phoneNumber');
  });
});
