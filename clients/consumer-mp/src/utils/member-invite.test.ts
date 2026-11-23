import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 邀请码落地（营销 2 / V501）。
 *
 * 钉三件事：① 非法码不进存储；② 过期码不生效（活动结束后不该再发券）；
 * ③ 终态错误（400/404/409）必须清掉本地码，可重试错误（网络抖动）必须保留。
 */
const mocks = vi.hoisted(() => ({
  bindMemberInvite: vi.fn(),
  isConsumerLoggedIn: vi.fn()
}));

vi.mock('@/utils/consumer-api', () => ({
  consumerApi: { bindMemberInvite: mocks.bindMemberInvite },
  isConsumerLoggedIn: () => mocks.isConsumerLoggedIn()
}));

import {
  clearPendingInviteCode,
  flushPendingInvite,
  readPendingInviteCode,
  rememberInviteCode
} from './member-invite';

type StorageMap = Record<string, unknown>;

function installUniMock(storage: StorageMap) {
  (globalThis as Record<string, unknown>).uni = {
    getStorageSync: (key: string) => storage[key],
    setStorageSync: (key: string, value: unknown) => {
      storage[key] = value;
    },
    removeStorageSync: (key: string) => {
      delete storage[key];
    }
  };
  return storage;
}

describe('member-invite', () => {
  let storage: StorageMap;

  beforeEach(() => {
    storage = installUniMock({});
    mocks.bindMemberInvite.mockReset();
    mocks.isConsumerLoggedIn.mockReset();
  });

  it('记住合法码并归一化为大写', () => {
    rememberInviteCode(' abcd2345 ');
    expect(readPendingInviteCode()).toBe('ABCD2345');
  });

  it('非法码不落存储（不打断浏览）', () => {
    rememberInviteCode('!!');
    expect(Object.keys(storage)).toHaveLength(0);
    expect(readPendingInviteCode()).toBe('');
  });

  it('超过 7 天的码读取时失效并清除', () => {
    storage['consumer.pendingInviteCode'] = JSON.stringify({
      code: 'OLD23456',
      at: Date.now() - 8 * 24 * 60 * 60 * 1000
    });

    expect(readPendingInviteCode()).toBe('');
    expect(storage['consumer.pendingInviteCode']).toBeUndefined();
  });

  it('未登录不动网络请求', async () => {
    mocks.isConsumerLoggedIn.mockReturnValue(false);
    rememberInviteCode('ABCD2345');

    await flushPendingInvite();

    expect(mocks.bindMemberInvite).not.toHaveBeenCalled();
    expect(readPendingInviteCode()).toBe('ABCD2345');
  });

  it('登录后绑定成功即清码', async () => {
    mocks.isConsumerLoggedIn.mockReturnValue(true);
    mocks.bindMemberInvite.mockResolvedValue({ inviteCode: 'ABCD2345' });
    rememberInviteCode('ABCD2345');

    await flushPendingInvite();

    expect(mocks.bindMemberInvite).toHaveBeenCalledWith('ABCD2345');
    expect(readPendingInviteCode()).toBe('');
  });

  it('409（已绑过/已有消费记录）属终态：清码，不再每次登录重撞', async () => {
    mocks.isConsumerLoggedIn.mockReturnValue(true);
    mocks.bindMemberInvite.mockRejectedValue(
      Object.assign(new Error('已绑定过邀请人'), { status: 409 })
    );
    rememberInviteCode('ABCD2345');

    await flushPendingInvite();

    expect(readPendingInviteCode()).toBe('');
  });

  it('网络类失败保留码，等下次登录重试', async () => {
    mocks.isConsumerLoggedIn.mockReturnValue(true);
    mocks.bindMemberInvite.mockRejectedValue(new Error('网络不太稳定'));
    rememberInviteCode('ABCD2345');

    await flushPendingInvite();

    expect(readPendingInviteCode()).toBe('ABCD2345');
  });

  it('显式清除', () => {
    rememberInviteCode('ABCD2345');
    clearPendingInviteCode();
    expect(readPendingInviteCode()).toBe('');
  });
});
