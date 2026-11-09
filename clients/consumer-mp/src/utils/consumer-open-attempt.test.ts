import { describe, expect, it } from 'vitest';
import {
  buildOpenAttemptIdempotencyKey,
  isOpenAttempt,
  isOpenAttemptFresh,
  normalizeOpenAttemptDeviceId,
  OPEN_ATTEMPT_TTL_MS
} from './consumer-open-attempt';

describe('consumer-open-attempt · C12b', () => {
  it('规范化柜机 ID', () => {
    expect(normalizeOpenAttemptDeviceId('  cab-01 ')).toBe('CAB-01');
  });

  it('识别有效 OpenAttempt', () => {
    expect(isOpenAttempt({ deviceId: 'A', idempotencyKey: 'k', createdAt: 1 })).toBe(true);
    expect(isOpenAttempt({ deviceId: 'A' })).toBe(false);
    expect(isOpenAttempt(null)).toBe(false);
  });

  // P0-4（2026-11-10）：键原先永不失效 ⇒ 后端回放终态会话，柜门不开。
  it('无 createdAt / createdAt 非有限的旧数据不算有效（防 NaN 比较让键永生）', () => {
    expect(isOpenAttempt({ deviceId: 'A', idempotencyKey: 'k' })).toBe(false);
    expect(isOpenAttempt({ deviceId: 'A', idempotencyKey: 'k', createdAt: Number.NaN })).toBe(
      false
    );
    expect(isOpenAttempt({ deviceId: 'A', idempotencyKey: 'k', createdAt: 0 })).toBe(true);
  });

  it('键有效期：到期即过期，时间回拨同样视为过期', () => {
    const t0 = 1_700_000_000_000;
    const attempt = { deviceId: 'A', idempotencyKey: 'k', createdAt: t0 };
    expect(isOpenAttemptFresh(attempt, t0)).toBe(true);
    expect(isOpenAttemptFresh(attempt, t0 + OPEN_ATTEMPT_TTL_MS - 1)).toBe(true);
    expect(isOpenAttemptFresh(attempt, t0 + OPEN_ATTEMPT_TTL_MS)).toBe(false);
    expect(isOpenAttemptFresh(attempt, t0 - 1000)).toBe(false);
  });

  it('幂等 key 带前缀', () => {
    expect(buildOpenAttemptIdempotencyKey(1_700_000_000_000)).toMatch(/^consumer-open-/);
  });
});
