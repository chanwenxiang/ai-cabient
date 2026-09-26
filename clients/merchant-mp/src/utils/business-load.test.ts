import { describe, expect, it } from 'vitest';
import {
  BUSINESS_BUNDLE_HARD_FAIL_MESSAGE,
  businessLoadErrorMessage,
  coalesceBusinessBundle,
  isBusinessBundleHardFail,
  isStaleBusinessLoad,
  shouldShowBusinessFullLoading
} from './business-load';

describe('business-load · M6d', () => {
  it('loading / 过期序', () => {
    expect(shouldShowBusinessFullLoading(false, 0)).toBe(true);
    expect(shouldShowBusinessFullLoading(true, 3)).toBe(false);
    expect(shouldShowBusinessFullLoading(true, 0)).toBe(true);
    expect(isStaleBusinessLoad(1, 2)).toBe(true);
    expect(isStaleBusinessLoad(2, 2)).toBe(false);
  });

  it('五路结果硬失败与合并', () => {
    expect(isBusinessBundleHardFail(null, null)).toBe(true);
    expect(isBusinessBundleHardFail({ days: 7 }, null)).toBe(false);
    expect(BUSINESS_BUNDLE_HARD_FAIL_MESSAGE).toContain('经营');
    const merged = coalesceBusinessBundle({
      analytics: null,
      settlement: { pendingAmountCents: 1 },
      prevAnalytics: { days: 30 } as { days: number },
      prevSettlement: { pendingAmountCents: 0 }
    });
    expect(merged.hardFail).toBe(false);
    expect(merged.analytics).toEqual({ days: 30 });
    expect(merged.settlement.pendingAmountCents).toBe(1);
    expect(businessLoadErrorMessage(new Error('x'))).toBe('x');
  });
});
