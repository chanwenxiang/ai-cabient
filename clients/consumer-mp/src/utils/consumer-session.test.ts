import { describe, expect, it } from 'vitest';
import { isConsumerBearerExpired, parseConsumerExpiresAt } from './consumer-session';

describe('consumer-session · expires 读校验（C3）', () => {
  it('parse：无效/缺失为 0；合法数字保留', () => {
    expect(parseConsumerExpiresAt(undefined)).toBe(0);
    expect(parseConsumerExpiresAt('')).toBe(0);
    expect(parseConsumerExpiresAt('nan')).toBe(0);
    expect(parseConsumerExpiresAt('1700000000000')).toBe(1700000000000);
    expect(parseConsumerExpiresAt(1700000000000)).toBe(1700000000000);
  });

  it('Bearer：仅 expiresAt>0 且已到期才过期', () => {
    const now = 2_000;
    expect(isConsumerBearerExpired(0, now)).toBe(false);
    expect(isConsumerBearerExpired(3_000, now)).toBe(false);
    expect(isConsumerBearerExpired(2_000, now)).toBe(true);
    expect(isConsumerBearerExpired(1_999, now)).toBe(true);
  });
});
