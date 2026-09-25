/**
 * 消费端金钱相关 UI 契约（可单测）：状态门闩 + 写请求体形状。
 * View 必须复用本模块，禁止再手写退款/充值/余额退申请体（debt-tracker C4）。
 */

export const ORDER_DETAIL_REFUNDABLE_STATUSES = ['PAID', 'COMPLETED', 'PARTIAL_REFUNDED'] as const;

/** 结算结果页立即退款：不含 PARTIAL_REFUNDED（与 result.vue 历史行为一致） */
export const RESULT_PAGE_REFUNDABLE_STATUSES = ['PAID', 'COMPLETED'] as const;

export type OrderDetailRefundableStatus = (typeof ORDER_DETAIL_REFUNDABLE_STATUSES)[number];

export function canRefundOnOrderDetail(input: {
  status?: string | null;
  orderId?: string | null;
  refundDone: boolean;
  refundPolicy?: string | null;
}): boolean {
  if (!input.orderId || input.refundDone) return false;
  if (input.refundPolicy === 'DISPUTE_ONLY') return false;
  const s = String(input.status || '');
  return (ORDER_DETAIL_REFUNDABLE_STATUSES as readonly string[]).includes(s);
}

export function canRefundOnResultPage(input: {
  status?: string | null;
  orderId?: string | null;
  refundDone: boolean;
  disputeFiled: boolean;
  totalAmountCents?: number | null;
  refundPolicy?: string | null;
}): boolean {
  if (!input.orderId || input.refundDone || input.disputeFiled) return false;
  if (Number(input.totalAmountCents ?? 0) <= 0) return false;
  if (input.refundPolicy === 'DISPUTE_ONLY') return false;
  const s = String(input.status || '');
  return (RESULT_PAGE_REFUNDABLE_STATUSES as readonly string[]).includes(s);
}

export type OrderRefundLine = {
  skuId: string;
  quantity: number;
  restoreInventory?: boolean;
};

export function buildOrderRefundBody(input: {
  reason: string;
  evidenceFileIds: string[];
  restoreInventory?: boolean;
  lines?: OrderRefundLine[];
}): {
  reason: string;
  evidenceFileIds: string[];
  restoreInventory?: boolean;
  lines?: OrderRefundLine[];
} {
  const body: {
    reason: string;
    evidenceFileIds: string[];
    restoreInventory?: boolean;
    lines?: OrderRefundLine[];
  } = {
    reason: input.reason,
    evidenceFileIds: input.evidenceFileIds
  };
  if (input.restoreInventory != null) {
    body.restoreInventory = input.restoreInventory;
  }
  if (input.lines && input.lines.length > 0) {
    body.lines = input.lines;
  }
  return body;
}

export function buildOrderRefundLines(input: {
  rows: { skuId: string; qty: number }[];
  restoreInventory?: boolean;
}): OrderRefundLine[] {
  return input.rows
    .filter((r) => r.qty > 0)
    .map((r) => ({
      skuId: r.skuId,
      quantity: r.qty,
      ...(input.restoreInventory != null ? { restoreInventory: input.restoreInventory } : {})
    }));
}

export function refundConfirmContent(input: {
  isPartial: boolean;
  restoreInventory: boolean | undefined;
  lineCount: number;
}): string {
  const { isPartial, restoreInventory, lineCount } = input;
  if (restoreInventory == null) {
    return isPartial
      ? `将退款所选 ${lineCount} 行商品；是否回库由平台规则判定。是否继续？`
      : '将立即全额退款；是否回库由平台规则判定。是否继续？';
  }
  if (isPartial) {
    if (restoreInventory) {
      return `将退款所选 ${lineCount} 行商品并回库。是否继续？`;
    }
    return `将退款所选 ${lineCount} 行商品（不回库）。是否继续？`;
  }
  if (restoreInventory) {
    return '将立即全额退款，并把本单商品回库（适用于没拿/误识别）。是否继续？';
  }
  return '将立即全额退款，但库存不回库（货已拿走/仅退款）。是否继续？';
}

/** 结果页无行退：文案略简（历史行为） */
export function resultRefundConfirmContent(restoreInventory: boolean | undefined): string {
  if (restoreInventory == null) {
    return '将立即退款；是否回库由平台规则判定。是否继续？';
  }
  if (restoreInventory) {
    return '将立即退款，并把本单商品回库（适用于没拿/误识别）。是否继续？';
  }
  return '将立即退款，但库存不回库（货已拿走/仅退款）。是否继续？';
}

export const DEFAULT_BALANCE_REFUND_REASON = '用户申请退可用余额';

export function buildBalanceRefundBody(input: { amountCents: number; reason?: string }): {
  amountCents: number;
  reason: string;
} {
  return {
    amountCents: input.amountCents,
    reason: input.reason?.trim() || DEFAULT_BALANCE_REFUND_REASON
  };
}

export type RechargeChannel = 'WECHAT' | 'ALIPAY';

export function buildRechargePrepayBody(input: {
  channel: RechargeChannel;
  amountCents: number;
  idempotencyKey: string;
}): {
  channel: RechargeChannel;
  amountCents: number;
  idempotencyKey: string;
} {
  return {
    channel: input.channel,
    amountCents: input.amountCents,
    idempotencyKey: input.idempotencyKey
  };
}

export type MoneyAmountValidationError = 'INVALID_AMOUNT' | 'EXCEEDS_MAX';

export function validatePositiveCents(input: {
  amountCents: number | null | undefined;
  maxCents?: number;
}): MoneyAmountValidationError | null {
  if (input.amountCents == null || !Number.isFinite(input.amountCents) || input.amountCents <= 0) {
    return 'INVALID_AMOUNT';
  }
  if (
    input.maxCents != null &&
    Number.isFinite(input.maxCents) &&
    input.maxCents !== Number.POSITIVE_INFINITY &&
    input.amountCents > input.maxCents
  ) {
    return 'EXCEEDS_MAX';
  }
  return null;
}
