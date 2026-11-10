import { beforeEach, describe, expect, it } from 'vitest';

/**
 * 活动弹窗「今天已看过」标记（R2-C6）：一个活动一个键的旧写法只增不减，
 * 这里钉住三件事——有界、旧键迁移后删除、按日期淘汰最旧。
 */
type StorageMap = Record<string, unknown>;

function installUniMock(storage: StorageMap) {
  (globalThis as Record<string, unknown>).uni = {
    getStorageSync: (key: string) => storage[key],
    setStorageSync: (key: string, value: unknown) => {
      storage[key] = value;
    },
    removeStorageSync: (key: string) => {
      delete storage[key];
    },
    getStorageInfoSync: () => ({ keys: Object.keys(storage) })
  };
  return storage;
}

async function loadStore() {
  const mod = await import('./popup-seen');
  return mod.createPopupSeenStore('promoPopupSeen:');
}

describe('popup-seen', () => {
  let storage: StorageMap;

  beforeEach(() => {
    storage = installUniMock({});
  });

  it('记一次就只对当天生效', async () => {
    const store = await loadStore();
    expect(store.seenDay(7)).toBe('');

    store.markSeen(7);

    expect(store.seenDay(7)).not.toBe('');
    // 其它活动不受影响
    expect(store.seenDay(8)).toBe('');
  });

  it('历史遗留的「一活动一键」迁进 map 后删除', async () => {
    storage['promoPopupSeen:11'] = '2026-11-09';
    storage['promoPopupSeen:12'] = '2026-11-09';
    const store = await loadStore();

    expect(store.seenDay(11)).toBe('2026-11-09');

    store.markSeen(13);

    // 迁移后的旧键必须消失（否则泄漏照旧）
    expect(storage['promoPopupSeen:11']).toBeUndefined();
    expect(storage['promoPopupSeen:12']).toBeUndefined();
    expect(store.seenDay(11)).toBe('2026-11-09');
    expect(store.seenDay(13)).not.toBe('');
  });

  it('超过 50 个活动按日期淘汰最旧', async () => {
    const seeded: Record<string, string> = {};
    for (let i = 1; i <= 60; i++) {
      // 用不同月份造出严格递增的日期，避免同日并列
      const month = String(1 + Math.floor((i - 1) / 28)).padStart(2, '0');
      const day = String(1 + ((i - 1) % 28)).padStart(2, '0');
      seeded[String(i)] = `2026-${month}-${day}`;
    }
    storage['promoPopupSeen:map'] = seeded;

    const store = await loadStore();
    store.markSeen(900);

    const kept = storage['promoPopupSeen:map'] as Record<string, string>;
    expect(Object.keys(kept)).toHaveLength(50);
    // 最早的那批被淘汰，最近标记的保留
    expect(kept['1']).toBeUndefined();
    expect(kept['900']).toBeDefined();
  });
});
