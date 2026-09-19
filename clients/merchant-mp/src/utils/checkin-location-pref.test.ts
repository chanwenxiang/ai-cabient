import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getSkipCheckInLocation, setSkipCheckInLocation } from './checkin-location-pref';

/** 与源码约定的存储键；写成字面量，防止改名后旧数据静默失效却没人发现 */
const STORAGE_KEY = 'merchant_skip_checkin_location';

const storage = new Map<string, unknown>();

beforeEach(() => {
  storage.clear();
  vi.stubGlobal('uni', {
    getStorageSync: (key: string) => (storage.has(key) ? storage.get(key) : ''),
    setStorageSync: (key: string, value: unknown) => {
      storage.set(key, value);
    }
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('checkin-location-pref', () => {
  it('未设置时默认不跳过定位', () => {
    expect(getSkipCheckInLocation()).toBe(false);
  });

  it('开启后读回 true，并以 "1" 落盘（兼容旧值形态）', () => {
    setSkipCheckInLocation(true);
    expect(getSkipCheckInLocation()).toBe(true);
    expect(storage.get(STORAGE_KEY)).toBe('1');
  });

  it('关闭后读回 false，并以 "0" 落盘而非删除', () => {
    setSkipCheckInLocation(true);
    setSkipCheckInLocation(false);
    expect(getSkipCheckInLocation()).toBe(false);
    expect(storage.get(STORAGE_KEY)).toBe('0');
  });

  it('存储不可用时读回 false（fail-closed：禁止默认跳过定位）', () => {
    vi.stubGlobal('uni', {
      getStorageSync: () => {
        throw new Error('storage unavailable');
      },
      setStorageSync: () => undefined
    });
    expect(getSkipCheckInLocation()).toBe(false);
  });

  it('存储不可用时写入不冒泡异常（不阻断补货流程）', () => {
    vi.stubGlobal('uni', {
      getStorageSync: () => '',
      setStorageSync: () => {
        throw new Error('quota exceeded');
      }
    });
    expect(() => setSkipCheckInLocation(true)).not.toThrow();
  });
});
