/**
 * 订单可退款状态 —— 三端唯一源。
 *
 * 🔴 2026-11-10 审计 P2-7：此前三处各写一份白名单且互不相等（admin 带 DISPUTED、
 * 消费者详情不带 DISPUTED、结果页连 PARTIAL_REFUNDED 都没有），同一张单在不同页面
 * 能做的操作不同，改一处漏两处。收敛到这里后，各端只允许引用本模块。
 *
 * 三个口径**故意不同**，各有业务理由，不是漏同步：
 * - `ORDER_REFUNDABLE_STATUSES`（admin）：运营可对争议中（DISPUTED）的单直接退款，
 *   这是客服/资金岗的处置手段；
 * - `CONSUMER_ORDER_REFUNDABLE_STATUSES`（消费者详情）：争议单已进入平台仲裁，
 *   消费者不能自助退款（避免与仲裁结果冲突），但仍可对已部分退款单退剩余部分；
 * - `RESULT_PAGE_REFUNDABLE_STATUSES`（结算结果页即时退款）：只能对「刚出账、未退过」
 *   的单做首次退款，不接受 PARTIAL_REFUNDED——结果页没有行级选择能力，二次退款必须去订单详情。
 */

/** admin/运营侧：含争议中（DISPUTED） */
export const ORDER_REFUNDABLE_STATUSES = [
  'PAID',
  'COMPLETED',
  'DISPUTED',
  'PARTIAL_REFUNDED'
] as const;

/** 消费者订单详情：争议单交给仲裁，不允许自助退 */
export const CONSUMER_ORDER_REFUNDABLE_STATUSES = [
  'PAID',
  'COMPLETED',
  'PARTIAL_REFUNDED'
] as const;

/** 消费者结算结果页：只做首次全额退款 */
export const RESULT_PAGE_REFUNDABLE_STATUSES = ['PAID', 'COMPLETED'] as const;

export type OrderRefundableStatus = (typeof ORDER_REFUNDABLE_STATUSES)[number];
export type ConsumerOrderRefundableStatus = (typeof CONSUMER_ORDER_REFUNDABLE_STATUSES)[number];
export type ResultPageRefundableStatus = (typeof RESULT_PAGE_REFUNDABLE_STATUSES)[number];

export type RefundScope = 'admin' | 'consumer' | 'result-page';

function statusesOf(scope: RefundScope): readonly string[] {
  switch (scope) {
    case 'admin':
      return ORDER_REFUNDABLE_STATUSES;
    case 'consumer':
      return CONSUMER_ORDER_REFUNDABLE_STATUSES;
    default:
      return RESULT_PAGE_REFUNDABLE_STATUSES;
  }
}

/** 单一判定入口：status 是否可在该场景自助退款。 */
export function isRefundableOrderStatus(
  status: string | null | undefined,
  scope: RefundScope
): boolean {
  const s = String(status ?? '');
  return statusesOf(scope).includes(s);
}
