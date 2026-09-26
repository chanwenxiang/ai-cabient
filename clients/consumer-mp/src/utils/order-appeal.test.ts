import { describe, expect, it } from 'vitest';
import {
  DEFAULT_REFUND_REASON,
  appealEvidenceLabel,
  appealPanelSubtitle,
  appealPanelTitle,
  appealReasonError,
  appealSubmitLabel,
  buildFileDisputeBody,
  evidenceUploadingError,
  seedDisputeForm,
  seedRefundForm,
  validateAppealForm
} from './order-appeal';

describe('order-appeal · C6/C6b', () => {
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
    expect(validateAppealForm('短', [], 'dispute')).toBe('请至少填写 4 个字');
    expect(validateAppealForm('足够长的原因', [{ uploading: true }], 'dispute')).toBe(
      '图片仍在上传'
    );
  });

  it('弹层文案按 surface', () => {
    expect(appealPanelTitle(true, 'order-detail')).toBe('立即退款');
    expect(appealPanelTitle(false, 'order-detail')).toBe('申请退款 / 账单申诉');
    expect(appealPanelTitle(false, 'result')).toBe('账单申诉');
    expect(appealPanelSubtitle(true, 'result')).toContain('原路退回');
    expect(appealEvidenceLabel('order-detail')).toContain('最多 5 张');
    expect(appealEvidenceLabel('result')).toBe('申诉附图（选填）');
    expect(
      appealSubmitLabel({ refundMode: false, refundLoading: false, disputeLoading: true })
    ).toBe('提交中…');
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
