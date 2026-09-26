/**
 * 订单申诉/退款表单种子与校验（debt-tracker C6 → C6b）。
 * order-detail 与 result 共用，禁止再各写一套 openDispute/openRefund/校验文案。
 */
import { DISPUTE_REASON_CHIPS, type DisputeReasonChip } from '@/utils/dispute-form';

export const DEFAULT_REFUND_REASON = '申请退回本单已扣款项';
export const DEFAULT_REFUND_CHIP_LABEL = '申请退款';

export type AppealSurface = 'order-detail' | 'result';

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

/** 弹层标题：两页退款态同为「立即退款」；申诉态文案按 surface。 */
export function appealPanelTitle(refundMode: boolean, surface: AppealSurface): string {
  if (refundMode) return '立即退款';
  return surface === 'order-detail' ? '申请退款 / 账单申诉' : '账单申诉';
}

export function appealPanelSubtitle(refundMode: boolean, surface: AppealSurface): string {
  if (refundMode) {
    return surface === 'order-detail'
      ? '将原路退回本单已扣款项。选「没拿/识别有误」会回库；选「质量问题(已拿走)」仅退款不回库。'
      : '将原路退回本单已扣款项，可上传凭证图片';
  }
  return surface === 'order-detail'
    ? '仅提交申诉工单，运营审核后再退款。可上传凭证图片。'
    : '提交申诉后由运营审核；可上传凭证图片';
}

export function appealEvidenceLabel(surface: AppealSurface): string {
  return surface === 'order-detail' ? '申诉附图（选填，最多 5 张）' : '申诉附图（选填）';
}

export function appealSubmitLabel(input: {
  refundMode: boolean;
  refundLoading: boolean;
  disputeLoading: boolean;
}): string {
  if (input.refundMode) {
    return input.refundLoading ? '退款中…' : '确认退款';
  }
  return input.disputeLoading ? '提交中…' : '提交申诉';
}

export function validateAppealForm(
  reason: string,
  evidence: { uploading?: boolean }[],
  kind: 'dispute' | 'refund'
): string | null {
  return appealReasonError(reason, kind) || evidenceUploadingError(evidence);
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
