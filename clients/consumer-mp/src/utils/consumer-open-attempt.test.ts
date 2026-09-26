import { describe, expect, it } from 'vitest';
import {
  buildOpenAttemptIdempotencyKey,
  isOpenAttempt,
  normalizeOpenAttemptDeviceId
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

  it('幂等 key 带前缀', () => {
    expect(buildOpenAttemptIdempotencyKey(1_700_000_000_000)).toMatch(/^consumer-open-/);
  });
});
