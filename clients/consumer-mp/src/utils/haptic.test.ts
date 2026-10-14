import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 触感反馈开关（2026-10-14 用户要求做成「我的-偏好设置」可配置）：
 * 全部用 mock 的 uni 全局，不碰真机。
 */
type StorageMap = Record<string, unknown>;

function installUniMock(storage: StorageMap, vibrated: boolean[] = []) {
  (globalThis as Record<string, unknown>).uni = {
    getStorageSync: (key: string) => storage[key],
    setStorageSync: (key: string, value: unknown) => {
      storage[key] = value;
    },
    vibrateShort: () => {
      vibrated.push(true);
    }
  };
  return storage;
}

async function loadHaptic() {
  const mod = await import('./haptic');
  return mod;
}

describe('haptic 触感反馈', () => {
  let storage: StorageMap;
  let vibrated: boolean[];

  beforeEach(() => {
    vi.resetModules();
    storage = {};
    vibrated = [];
    installUniMock(storage, vibrated);
  });

  it('默认开启：未写过开关时 isHapticEnabled()=true，hapticLight() 会震动', async () => {
    const h = await loadHaptic();
    expect(h.isHapticEnabled()).toBe(true);
    h.hapticLight();
    expect(vibrated.length).toBe(1);
  });

  it('关闭后不震动；重新开启恢复', async () => {
    const h = await loadHaptic();
    h.setHapticEnabled(false);
    expect(h.isHapticEnabled()).toBe(false);
    h.hapticLight();
    expect(vibrated.length).toBe(0);
    h.setHapticEnabled(true);
    h.hapticLight();
    expect(vibrated.length).toBe(1);
  });

  it('🔴 存储异常 fail-open：读失败按「开」处理，写失败不抛错', async () => {
    (globalThis as Record<string, unknown>).uni = {
      getStorageSync: () => {
        throw new Error('storage broken');
      },
      setStorageSync: () => {
        throw new Error('storage full');
      },
      vibrateShort: () => vibrated.push(true)
    };
    const h = await loadHaptic();
    expect(h.isHapticEnabled()).toBe(true);
    expect(() => h.setHapticEnabled(false)).not.toThrow();
    expect(() => h.hapticLight()).not.toThrow();
    expect(vibrated.length).toBe(1);
  });

  it('vibrateShort 不存在的平台静默（H5 老机型兜底）', async () => {
    (globalThis as Record<string, unknown>).uni = {
      getStorageSync: () => undefined,
      setStorageSync: () => undefined
    };
    const h = await loadHaptic();
    expect(() => h.hapticLight()).not.toThrow();
  });
});
