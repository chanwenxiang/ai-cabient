/**
 * 金钱相关 UI 契约（可单测）：状态门闩 + 写请求体形状。
 * View 必须复用本模块，禁止再手写一套可退款状态 / 提现审核体（debt-tracker D5）。
 */
import { errorMessage } from './error-message';

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

/**
 * 充值单可退款状态（充值域独立状态集，与订单域不同）——
 * RechargeListView 必须复用本判定，禁止再手写（A1 复审发现）。
 */
export const REFUNDABLE_RECHARGE_STATUSES = ['PAID', 'SUCCESS'] as const;

export function canRefundRechargeStatus(status?: string | null): boolean {
  return status === 'PAID' || status === 'SUCCESS';
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

// 审计 P2-15：线长提现与商户提现同族——状态机一致（PENDING_REVIEW/APPROVED/FAILED），
// 审核体形状一致。契约单点化后，新增提现状态（如 PAYOUT_IN_PROGRESS）只改这里。
export function canReviewLineWithdraw(
  status: string | undefined | null,
  hasReviewPerm: boolean
): boolean {
  return canReviewMerchantWithdraw(status, hasReviewPerm);
}

export function canRetryLineWithdrawPayout(
  status: string | undefined | null,
  hasReviewPerm: boolean
): boolean {
  return canRetryMerchantWithdrawPayout(status, hasReviewPerm);
}

export function canCancelFailedLineWithdraw(
  status: string | undefined | null,
  hasReviewPerm: boolean
): boolean {
  return canCancelFailedMerchantWithdraw(status, hasReviewPerm);
}

export function buildLineWithdrawReviewBody(approve: boolean, options?: { batch?: boolean }) {
  return buildMerchantWithdrawReviewBody(approve, options);
}

/**
 * 资金审核「批量执行」的唯一实现：**串行逐条**，失败记名后继续。
 *
 * 为什么必须串行（2026-10-20 决策 C）：一次「批量通过」= N 条独立 POST，每条都会抢
 * 按钱包粒度的 Redisson 锁（`aicabinet:lock:merchant:wallet:<merchantId>`，见
 * `MerchantWithdrawService`）。`Promise.allSettled` 并发 fan-out 时，同一商户的兄弟请求
 * 必然互相撞锁 ⇒ 409「该提现单已有出款在进行中」，而这类失败**不是**业务失败，是自家
 * 并发造出来的；运营看到「成功 3，失败 2」无法归因，重试又可能撞上真在途的出款。
 * 串行后每条读到的都是前一条**已提交**的状态，失败清单能指认到具体单据。
 *
 * `rowLabel` 由调用方给（申请单号/商户名），用于失败归因。
 */
export async function runFundReviewBatch<R>(
  targets: R[],
  rowLabel: (row: R) => string,
  request: (row: R) => Promise<unknown>
): Promise<{ total: number; ok: number; failures: string[] }> {
  let ok = 0;
  const failures: string[] = [];
  for (const row of targets) {
    try {
      await request(row);
      ok += 1;
    } catch (e) {
      failures.push(`${rowLabel(row)}：${errorMessage(e, '审核请求失败')}`);
    }
  }
  return { total: targets.length, ok, failures };
}
