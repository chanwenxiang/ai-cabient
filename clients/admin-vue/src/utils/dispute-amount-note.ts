import { fmtMoney } from '@aicabinet/shared-uni/format';

type DisputeAmountInput = {
  claimedAmountCents?: number | null;
  billedAmountCents?: number | null;
  suggestedItems?: Array<{ lineAmountCents?: number | null }> | null;
};

function sumLineAmountCents(
  lines?: Array<{ lineAmountCents?: number | null }> | null
): number {
  if (!lines?.length) return 0;
  return lines.reduce((sum, line) => sum + Math.max(0, Number(line.lineAmountCents ?? 0)), 0);
}

/** IMP-026：建议价与实扣不一致时的差额说明 */
export function disputeAmountDiffNote(ticket?: DisputeAmountInput | null): string {
  if (!ticket) return '';
  const claimed =
    Number(ticket.claimedAmountCents ?? 0) || sumLineAmountCents(ticket.suggestedItems);
  const billed = Number(ticket.billedAmountCents ?? 0);
  if (claimed <= 0 || billed < 0 || claimed === billed) return '';
  const diff = claimed - billed;
  if (diff > 0) {
    return `识别参考 ${fmtMoney(claimed)}，实扣 ${fmtMoney(billed)}（优惠/折扣 ${fmtMoney(diff)}）`;
  }
  return `识别参考 ${fmtMoney(claimed)}，实扣 ${fmtMoney(billed)}（差额 ${fmtMoney(Math.abs(diff))}）`;
}
