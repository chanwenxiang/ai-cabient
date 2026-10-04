import { displayLabel } from '@aicabinet/shared-dict';
import { fmtMoney, localizeDisputeReason } from '@aicabinet/shared-uni/format';

export type MerchantDisputeCopyInput = {
  status?: string | null;
  reason?: string | null;
  billedAmountCents?: number | null;
  refundedAmountCents?: number | null;
  claimedAmountCents?: number | null;
};

function isTerminalStatus(status?: string | null) {
  const s = (status || '').toUpperCase();
  return s === 'RESOLVED' || s === 'CLOSED';
}

/** 已结案金额结论：「已结案」词条走字典，金额仍按账单字段组合。 */
function resolvedDisputeSummary(ticket: MerchantDisputeCopyInput): string {
  const prefix = displayLabel('dispute_status', 'RESOLVED');
  const billed = Number(ticket.billedAmountCents ?? 0);
  const refunded = Number(ticket.refundedAmountCents ?? 0);

  if (refunded > 0 && billed <= 0) {
    return `${prefix}：已免单退款 ${fmtMoney(refunded)}`;
  }
  if (refunded > 0 && billed > 0) {
    return `${prefix}：扣款 ${fmtMoney(billed)}，已退 ${fmtMoney(refunded)}`;
  }
  if (billed > 0) {
    return `${prefix}：按识别扣款 ${fmtMoney(billed)}`;
  }
  if (refunded > 0) {
    return `${prefix}：已退款 ${fmtMoney(refunded)}`;
  }
  return `${prefix}：未产生扣款`;
}

/**
 * 商户资金说明：已扣/已退/实收。识别参考是建议商品估价，禁止与实扣相减当「差额」。
 */
export function merchantDisputeAmountDiffNote(ticket?: MerchantDisputeCopyInput | null): string {
  if (!ticket) return '';
  const billed = Number(ticket.billedAmountCents ?? 0);
  const refunded = Number(ticket.refundedAmountCents ?? 0);
  const claimed = Number(ticket.claimedAmountCents ?? 0);
  if (billed > 0 && refunded > 0) {
    const received = Math.max(0, billed - refunded);
    return `已扣 ${fmtMoney(billed)}，已退 ${fmtMoney(refunded)}，实收 ${fmtMoney(received)}`;
  }
  if (claimed > 0 && billed > 0 && claimed !== billed) {
    return '识别参考是建议商品估价，扣款以支付流水为准，两者不能相减';
  }
  return '';
}

/**
 * 商户争议列表/详情主文案：OPEN 展示 reason；已结案展示结论摘要（状态词条同源 shared-dict）。
 */
export function merchantDisputeDisplayCopy(ticket?: MerchantDisputeCopyInput | null): string {
  if (!ticket) return '';
  if (isTerminalStatus(ticket.status)) {
    return resolvedDisputeSummary(ticket);
  }
  return (
    localizeDisputeReason(ticket.reason) ||
    displayLabel('dispute_status', ticket.status, '争议待处理')
  );
}
