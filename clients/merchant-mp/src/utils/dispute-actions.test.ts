import { describe, expect, it } from 'vitest';
import {
  DISPUTE_REPLY_DENIED_MESSAGE,
  DISPUTE_REPLY_PROMPT,
  canStartDisputeClaim,
  canStartDisputeResolve,
  disputeActionErrorMessage,
  disputeDeviceDetailUrl,
  disputeOrderDetailUrl,
  disputeResolveTypeLabels,
  mergeClaimedDisputeDetail
} from './dispute-actions';

describe('dispute-actions · M7c', () => {
  it('认领/结案门闩', () => {
    expect(canStartDisputeClaim({ ticketId: 'T1', claiming: false })).toBe(true);
    expect(canStartDisputeClaim({ ticketId: '', claiming: false })).toBe(false);
    expect(canStartDisputeClaim({ ticketId: 'T1', claiming: true })).toBe(false);
    expect(canStartDisputeResolve({ ticketId: 'T1', resolving: true })).toBe(false);
    expect(canStartDisputeResolve({ ticketId: 'T1', resolving: false })).toBe(true);
  });

  it('认领合并与结案标签', () => {
    expect(
      mergeClaimedDisputeDetail({ ticketId: 'T1', status: 'OPEN' }, { status: 'PROCESSING' })
    ).toEqual({
      ticketId: 'T1',
      status: 'PROCESSING'
    });
    const labels = disputeResolveTypeLabels((ns, code) => `${ns}:${code}`);
    expect(labels.KEEP).toBe('dispute_resolution:KEEP');
    expect(labels.WAIVE).toBe('dispute_resolution:WAIVE');
  });

  it('回复文案常量与错误/导航', () => {
    expect(DISPUTE_REPLY_PROMPT.title).toBe('回复争议');
    expect(DISPUTE_REPLY_PROMPT.testId).toBe('dispute-reply-prompt');
    expect(DISPUTE_REPLY_DENIED_MESSAGE).toContain('权限');
    expect(disputeActionErrorMessage(new Error('x'), 'fallback')).toBe('x');
    expect(disputeActionErrorMessage('oops', 'fallback')).toBe('fallback');
    expect(disputeOrderDetailUrl('O1')).toContain('orderId=O1');
    expect(disputeDeviceDetailUrl('D1')).toContain('id=D1');
  });
});
