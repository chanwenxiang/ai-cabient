import { describe, expect, it, vi } from 'vitest';
import {
  blockedDeviceLandingError,
  isAdoptableSession,
  isCabinetIdInvalid,
  normalizeCabinetId,
  settleWithin,
  SESSION_ACTIVE_STATES
} from './landing-session';

describe('landing-session · C5', () => {
  it('normalizeCabinetId 去空白并大写', () => {
    expect(normalizeCabinetId('  cab-01 ')).toBe('CAB-01');
  });

  it('isCabinetIdInvalid 拒绝空/非法字符', () => {
    expect(isCabinetIdInvalid('AB')).toBe(false);
    expect(isCabinetIdInvalid('A')).toBe(true);
    expect(isCabinetIdInvalid('')).toBe(true);
    expect(isCabinetIdInvalid('中文柜')).toBe(true);
  });

  it('isAdoptableSession：同柜 + 进行中态', () => {
    expect(isAdoptableSession({ deviceId: 'cab-1', state: 'SHOPPING' }, 'CAB-1')).toBe(true);
    expect(isAdoptableSession({ deviceId: 'cab-1', state: 'PAID' }, 'CAB-1')).toBe(false);
    expect(isAdoptableSession({ deviceId: 'other', state: 'SHOPPING' }, 'CAB-1')).toBe(false);
    expect(SESSION_ACTIVE_STATES).toContain('OPENING');
  });

  it('blockedDeviceLandingError 按 reason 分支', () => {
    expect(blockedDeviceLandingError(false, '').kind).toBe('other');
    expect(blockedDeviceLandingError(true, 'LOCKED').kind).toBe('device_paused');
    expect(blockedDeviceLandingError(true, 'REPLENISHMENT').kind).toBe('device_busy');
    expect(blockedDeviceLandingError(true, 'SESSION').toastTitle).toBe('柜机正忙');
    expect(blockedDeviceLandingError(true, 'OTHER', '状态文案').msg).toBe('状态文案');
  });

  it('settleWithin：超时返回 null', async () => {
    vi.useFakeTimers();
    const p = settleWithin(new Promise(() => {}), 50);
    vi.advanceTimersByTime(50);
    await expect(p).resolves.toBeNull();
    vi.useRealTimers();
  });

  it('settleWithin：在途失败也返回 null（故意，非 softFallback）', async () => {
    await expect(settleWithin(Promise.reject(new Error('boom')), 50)).resolves.toBeNull();
  });

  it('settleWithin：成功返回值', async () => {
    await expect(settleWithin(Promise.resolve(42), 50)).resolves.toBe(42);
  });
});
