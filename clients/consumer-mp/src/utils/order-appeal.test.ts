import { describe, expect, it } from 'vitest';
import {
  DEFAULT_REFUND_REASON,
  appealReasonError,
  buildFileDisputeBody,
  evidenceUploadingError,
  seedDisputeForm,
  seedRefundForm
} from './order-appeal';

describe('order-appeal · C6', () => {
  it('seedDispute / seedRefund 形态固定', () => {
    expect(seedDisputeForm()).toEqual({
      refundMode: false,
      disputeReason: '',
      selectedCategory: 'USER_APPEAL',
      selectedChip: null
    });
    const refund = seedRefundForm();
    expect(refund.refundMode).toBe(true);
    expect(refund.disputeReason).toBe(DEFAULT_REFUND_REASON);
    expect(refund.selectedChip?.label).toBe('申请退款');
  });

  it('原因与证据校验文案', () => {
    expect(appealReasonError('短', 'dispute')).toBe('请至少填写 4 个字');
    expect(appealReasonError('短', 'refund')).toBe('请至少填写 4 字退款原因');
    expect(appealReasonError('足够长的原因', 'refund')).toBeNull();
    expect(evidenceUploadingError([{ uploading: true }])).toBe('图片仍在上传');
    expect(evidenceUploadingError([{ uploading: false }])).toBeNull();
  });

  it('申诉请求体', () => {
    expect(
      buildFileDisputeBody({
        sessionId: 'S1',
        reason: ' 没拿商品 ',
        evidenceFileIds: [9]
      })
    ).toEqual({
      sessionId: 'S1',
      reason: '没拿商品',
      category: 'USER_APPEAL',
      priority: 'NORMAL',
      evidenceFileIds: [9]
    });
  });
});
