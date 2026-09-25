/**
 * 订单申诉/退款表单种子与校验（debt-tracker C6）。
 * order-detail 与 result 共用，禁止再各写一套 openDispute/openRefund/校验文案。
 */
import { DISPUTE_REASON_CHIPS, type DisputeReasonChip } from '@/utils/dispute-form';

export const DEFAULT_REFUND_REASON = '申请退回本单已扣款项';
export const DEFAULT_REFUND_CHIP_LABEL = '申请退款';

export type AppealFormSeed = {
  refundMode: boolean;
  disputeReason: string;
  selectedCategory: string;
  selectedChip: DisputeReasonChip | null;
};

export function seedDisputeForm(): AppealFormSeed {
  return {
    refundMode: false,
    disputeReason: '',
    selectedCategory: 'USER_APPEAL',
    selectedChip: null
  };
}

export function seedRefundForm(): AppealFormSeed {
  return {
    refundMode: true,
    disputeReason: DEFAULT_REFUND_REASON,
    selectedCategory: 'USER_APPEAL',
    selectedChip: DISPUTE_REASON_CHIPS.find((c) => c.label === DEFAULT_REFUND_CHIP_LABEL) || null
  };
}

export function appealReasonError(reason: string, kind: 'dispute' | 'refund'): string | null {
  if (reason.trim().length < 4) {
    return kind === 'refund' ? '请至少填写 4 字退款原因' : '请至少填写 4 个字';
  }
  return null;
}

export function evidenceUploadingError(evidence: { uploading?: boolean }[]): string | null {
  if (evidence.some((e) => e.uploading)) return '图片仍在上传';
  return null;
}

export function buildFileDisputeBody(input: {
  sessionId: string;
  reason: string;
  category?: string;
  evidenceFileIds: number[];
}): {
  sessionId: string;
  reason: string;
  category: string;
  priority: 'NORMAL';
  evidenceFileIds: number[];
} {
  return {
    sessionId: input.sessionId,
    reason: input.reason.trim(),
    category: input.category?.trim() || 'USER_APPEAL',
    priority: 'NORMAL',
    evidenceFileIds: input.evidenceFileIds
  };
}
