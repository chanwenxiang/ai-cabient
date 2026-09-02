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

/** 已结案：按金额组合生成结论，避免仍展示 OPEN 态「暂未扣款」话术 */
function resolvedDisputeSummary(ticket: MerchantDisputeCopyInput): string {
  const billed = Number(ticket.billedAmountCents ?? 0);
  const refunded = Number(ticket.refundedAmountCents ?? 0);

  if (refunded > 0 && billed <= 0) {
    return `已结案：已免单退款 ${fmtMoney(refunded)}`;
  }
  if (refunded > 0 && billed > 0) {
    return `已结案：扣款 ${fmtMoney(billed)}，已退 ${fmtMoney(refunded)}`;
  }
  if (billed > 0) {
    return `已结案：按识别扣款 ${fmtMoney(billed)}`;
  }
  if (refunded > 0) {
    return `已结案：已退款 ${fmtMoney(refunded)}`;
  }
  return '已结案：未产生扣款';
}

/**
 * 商户争议列表/详情主文案：OPEN 展示 reason；已结案展示结论摘要。
 */
export function merchantDisputeDisplayCopy(ticket?: MerchantDisputeCopyInput | null): string {
  if (!ticket) return '';
  if (isTerminalStatus(ticket.status)) {
    return resolvedDisputeSummary(ticket);
  }
  return localizeDisputeReason(ticket.reason) || '争议待处理';
}
