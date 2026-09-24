/**
 * 金钱相关 UI 契约（可单测）：状态门闩 + 写请求体形状。
 * View 必须复用本模块，禁止再手写一套可退款状态 / 提现审核体（debt-tracker D5）。
 */

export const REFUNDABLE_ORDER_STATUSES = [
  'PAID',
  'COMPLETED',
  'DISPUTED',
  'PARTIAL_REFUNDED'
] as const;

export type RefundableOrderStatus = (typeof REFUNDABLE_ORDER_STATUSES)[number];

export function canRefundOrderStatus(status?: string | null): boolean {
  return (
    status === 'PAID' ||
    status === 'COMPLETED' ||
    status === 'DISPUTED' ||
    status === 'PARTIAL_REFUNDED'
  );
}

export function buildOrderRefundBody(input: { reason: string; restoreInventory: boolean }): {
  reason: string;
  restoreInventory: boolean;
} {
  return {
    reason: input.reason,
    restoreInventory: input.restoreInventory
  };
}

export type DisputeResolutionType = 'KEEP' | 'WAIVE' | 'CONFIRM' | 'ADJUST';

export function buildDisputeResolveBody<TItem>(input: {
  resolutionType: DisputeResolutionType;
  restoreInventory?: boolean;
  items?: TItem[];
}): {
  resolutionType: DisputeResolutionType;
  restoreInventory?: boolean;
  items: TItem[];
} {
  const needsItems = input.resolutionType === 'ADJUST' || input.resolutionType === 'CONFIRM';
  return {
    resolutionType: input.resolutionType,
    restoreInventory: input.restoreInventory,
    items: needsItems ? (input.items ?? []) : []
  };
}

export function canReviewMerchantWithdraw(
  status: string | undefined | null,
  hasReviewPerm: boolean
): boolean {
  return status === 'PENDING_REVIEW' && hasReviewPerm;
}

export function canRetryMerchantWithdrawPayout(
  status: string | undefined | null,
  hasReviewPerm: boolean
): boolean {
  return (status === 'APPROVED' || status === 'FAILED') && hasReviewPerm;
}

export function canCancelFailedMerchantWithdraw(
  status: string | undefined | null,
  hasReviewPerm: boolean
): boolean {
  return status === 'FAILED' && hasReviewPerm;
}

export function buildMerchantWithdrawReviewBody(
  approve: boolean,
  options?: { batch?: boolean }
): {
  approve: boolean;
  remark: string;
} {
  const batch = options?.batch === true;
  return {
    approve,
    remark: batch ? (approve ? '批量审核通过' : '批量审核驳回') : approve ? '审核通过' : '审核驳回'
  };
}
