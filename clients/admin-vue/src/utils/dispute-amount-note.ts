import { fmtMoney } from '@aicabinet/shared-uni/format';

type DisputeAmountInput = {
  claimedAmountCents?: number | null;
  billedAmountCents?: number | null;
  suggestedItems?: Array<{ lineAmountCents?: number | null }> | null;
};

function sumLineAmountCents(lines?: Array<{ lineAmountCents?: number | null }> | null): number {
  if (!lines?.length) return 0;
  return lines.reduce((sum, line) => sum + Math.max(0, Number(line.lineAmountCents ?? 0)), 0);
}

/** IMP-026：建议价与实扣不一致时的差额说明 */
export function disputeAmountDiffNote(ticket?: DisputeAmountInput | null): string {
  if (!ticket) return '';
  const claimedRaw = ticket.claimedAmountCents;
  const claimed =
    claimedRaw != null && Number.isFinite(Number(claimedRaw))
      ? Number(claimedRaw)
      : sumLineAmountCents(ticket.suggestedItems);
  const billed = Number(ticket.billedAmountCents ?? 0);
  if (claimed <= 0 || billed < 0 || claimed === billed) return '';
  const diff = claimed - billed;
  if (diff > 0) {
    return `识别参考 ${fmtMoney(claimed)}，实扣 ${fmtMoney(billed)}（优惠/折扣 ${fmtMoney(diff)}）`;
  }
  return `识别参考 ${fmtMoney(claimed)}，实扣 ${fmtMoney(billed)}（差额 ${fmtMoney(Math.abs(diff))}）`;
}

type OrderAmountInput = {
  originalAmountCents?: number | null;
  totalAmountCents?: number | null;
  couponDiscountCents?: number | null;
  memberDiscountCents?: number | null;
};

/** IMP-026 延伸：订单原价与实付差额说明 */
export function orderAmountDiffNote(order?: OrderAmountInput | null): string {
  if (!order) return '';
  const original = Number(order.originalAmountCents ?? 0);
  const total = Number(order.totalAmountCents ?? 0);
  if (original <= 0 || total < 0 || original === total) return '';
  const coupon = Number(order.couponDiscountCents ?? 0);
  const member = Number(order.memberDiscountCents ?? 0);
  const parts: string[] = [];
  if (coupon > 0) parts.push(`券 ${fmtMoney(coupon)}`);
  if (member > 0) parts.push(`会员 ${fmtMoney(member)}`);
  const reason =
    parts.length > 0 ? parts.join('、') : `差额 ${fmtMoney(Math.abs(original - total))}`;
  return `原价 ${fmtMoney(original)}，实付 ${fmtMoney(total)}（${reason}）`;
}
