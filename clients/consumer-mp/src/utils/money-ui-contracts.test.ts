import { describe, expect, it } from 'vitest';
import {
  buildBalanceRefundBody,
  buildOrderRefundBody,
  buildOrderRefundLines,
  buildRechargePrepayBody,
  canRefundOnOrderDetail,
  canRefundOnResultPage,
  DEFAULT_BALANCE_REFUND_REASON,
  refundConfirmContent,
  resultRefundConfirmContent,
  validatePositiveCents
} from './money-ui-contracts';

describe('money-ui-contracts · 订单退款门闩', () => {
  it('订单详情：PAID/COMPLETED/PARTIAL_REFUNDED；DISPUTE_ONLY 关', () => {
    expect(
      canRefundOnOrderDetail({
        status: 'PAID',
        orderId: 'O1',
        refundDone: false,
        refundPolicy: 'AUTO'
      })
    ).toBe(true);
    expect(
      canRefundOnOrderDetail({
        status: 'PARTIAL_REFUNDED',
        orderId: 'O1',
        refundDone: false
      })
    ).toBe(true);
    expect(
      canRefundOnOrderDetail({
        status: 'PAID',
        orderId: 'O1',
        refundDone: false,
        refundPolicy: 'DISPUTE_ONLY'
      })
    ).toBe(false);
    expect(canRefundOnOrderDetail({ status: 'REFUNDED', orderId: 'O1', refundDone: false })).toBe(
      false
    );
  });

  it('结果页：仅 PAID/COMPLETED，且需金额>0、未申诉', () => {
    expect(
      canRefundOnResultPage({
        status: 'PAID',
        orderId: 'O1',
        refundDone: false,
        disputeFiled: false,
        totalAmountCents: 100
      })
    ).toBe(true);
    expect(
      canRefundOnResultPage({
        status: 'PARTIAL_REFUNDED',
        orderId: 'O1',
        refundDone: false,
        disputeFiled: false,
        totalAmountCents: 100
      })
    ).toBe(false);
    expect(
      canRefundOnResultPage({
        status: 'PAID',
        orderId: 'O1',
        refundDone: false,
        disputeFiled: true,
        totalAmountCents: 100
      })
    ).toBe(false);
  });
});

describe('money-ui-contracts · 退款请求体', () => {
  it('全额：reason + evidence；可选 restoreInventory；无 lines', () => {
    expect(
      buildOrderRefundBody({
        reason: '没拿',
        evidenceFileIds: [1],
        restoreInventory: true
      })
    ).toEqual({ reason: '没拿', evidenceFileIds: [1], restoreInventory: true });
    expect(buildOrderRefundBody({ reason: '没拿', evidenceFileIds: [] })).toEqual({
      reason: '没拿',
      evidenceFileIds: []
    });
  });

  it('按行：lines 带 quantity；qty=0 剔除', () => {
    const lines = buildOrderRefundLines({
      rows: [
        { skuId: 'A', qty: 2 },
        { skuId: 'B', qty: 0 }
      ],
      restoreInventory: false
    });
    expect(lines).toEqual([{ skuId: 'A', quantity: 2, restoreInventory: false }]);
    expect(
      buildOrderRefundBody({
        reason: '部分',
        evidenceFileIds: [],
        lines
      })
    ).toEqual({
      reason: '部分',
      evidenceFileIds: [],
      lines
    });
  });

  it('确认文案：全额/按行 × 回库三态', () => {
    expect(
      refundConfirmContent({ isPartial: false, restoreInventory: true, lineCount: 0 })
    ).toContain('回库');
    expect(
      refundConfirmContent({ isPartial: true, restoreInventory: false, lineCount: 2 })
    ).toContain('不回库');
    expect(resultRefundConfirmContent(undefined)).toContain('平台规则');
  });
});

describe('money-ui-contracts · 充值 / 余额退', () => {
  it('充值预下单体', () => {
    expect(
      buildRechargePrepayBody({
        channel: 'WECHAT',
        amountCents: 2000,
        idempotencyKey: 'k1'
      })
    ).toEqual({ channel: 'WECHAT', amountCents: 2000, idempotencyKey: 'k1' });
  });

  it('余额退申请体 + 默认原因', () => {
    expect(buildBalanceRefundBody({ amountCents: 500 })).toEqual({
      amountCents: 500,
      reason: DEFAULT_BALANCE_REFUND_REASON
    });
    expect(buildBalanceRefundBody({ amountCents: 500, reason: ' 自定义 ' })).toEqual({
      amountCents: 500,
      reason: '自定义'
    });
  });

  it('正数分校验与上限', () => {
    expect(validatePositiveCents({ amountCents: 0 })).toBe('INVALID_AMOUNT');
    expect(validatePositiveCents({ amountCents: 100, maxCents: 50 })).toBe('EXCEEDS_MAX');
    expect(validatePositiveCents({ amountCents: 100, maxCents: 200 })).toBeNull();
  });
});
