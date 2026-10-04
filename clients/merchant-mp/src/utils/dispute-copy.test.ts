import { describe, expect, it } from 'vitest';
import { merchantDisputeAmountDiffNote } from './dispute-copy';

describe('merchantDisputeAmountDiffNote', () => {
  it('有退款时按已扣/已退/实收，不用识别参考相减', () => {
    expect(
      merchantDisputeAmountDiffNote({
        billedAmountCents: 800,
        refundedAmountCents: 400,
        claimedAmountCents: 450
      })
    ).toBe('已扣 ¥8.00，已退 ¥4.00，实收 ¥4.00');
  });

  it('识别参考与实扣不同且无退款时只说明口径，不算差额', () => {
    expect(
      merchantDisputeAmountDiffNote({
        billedAmountCents: 800,
        claimedAmountCents: 450
      })
    ).toBe('识别参考是建议商品估价，扣款以支付流水为准，两者不能相减');
  });
});
