import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearPreferredDeviceId,
  getPreferredDeviceId,
  setPreferredDeviceId
} from './preferred-device';

/** 与源码约定的存储键；写成字面量，防止改名后旧数据静默失效却没人发现 */
const STORAGE_KEY = 'merchant_preferred_device_id';

const storage = new Map<string, unknown>();

beforeEach(() => {
  storage.clear();
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

describe('preferred-device', () => {
  it('未设置时返回空串', () => {
    expect(getPreferredDeviceId()).toBe('');
  });

  it('设置后可读回，且写入约定的存储键（供补货/告警页共用）', () => {
    setPreferredDeviceId('DEV-1');
    expect(getPreferredDeviceId()).toBe('DEV-1');
    expect(storage.has(STORAGE_KEY)).toBe(true);
    expect(storage.get(STORAGE_KEY)).toBe('DEV-1');
  });

  it('两端空白被裁剪（扫码/手输都可能带空格）', () => {
    setPreferredDeviceId('  DEV-2  ');
    expect(getPreferredDeviceId()).toBe('DEV-2');
  });

  it('传空值等价于清除（不写入空串）', () => {
    setPreferredDeviceId('DEV-3');
    setPreferredDeviceId('');
    expect(getPreferredDeviceId()).toBe('');
    expect(storage.has(STORAGE_KEY)).toBe(false);

    setPreferredDeviceId('DEV-3');
    setPreferredDeviceId(null);
    expect(storage.has(STORAGE_KEY)).toBe(false);

    setPreferredDeviceId('DEV-3');
    setPreferredDeviceId(undefined);
    expect(storage.has(STORAGE_KEY)).toBe(false);
  });

  it('clear 后回到空串', () => {
    setPreferredDeviceId('DEV-4');
    clearPreferredDeviceId();
    expect(getPreferredDeviceId()).toBe('');
    expect(storage.has(STORAGE_KEY)).toBe(false);
  });
});
