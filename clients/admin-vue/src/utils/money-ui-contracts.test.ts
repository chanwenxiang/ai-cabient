import { describe, expect, it } from 'vitest';
import { AdminEndpoints } from '@/api/endpoints';
import {
  buildDisputeResolveBody,
  buildLineWithdrawReviewBody,
  buildMerchantWithdrawReviewBody,
  buildOrderRefundBody,
  canCancelFailedLineWithdraw,
  canCancelFailedMerchantWithdraw,
  canRefundOrderStatus,
  canReviewLineWithdraw,
  canReviewMerchantWithdraw,
  canRetryLineWithdrawPayout,
  canRetryMerchantWithdrawPayout
} from './money-ui-contracts';

describe('money-ui-contracts · 订单退款', () => {
  it('仅 PAID/COMPLETED/DISPUTED/PARTIAL_REFUNDED 可退', () => {
    expect(canRefundOrderStatus('PAID')).toBe(true);
    expect(canRefundOrderStatus('COMPLETED')).toBe(true);
    expect(canRefundOrderStatus('DISPUTED')).toBe(true);
    expect(canRefundOrderStatus('PARTIAL_REFUNDED')).toBe(true);
    expect(canRefundOrderStatus('PENDING')).toBe(false);
    expect(canRefundOrderStatus('REFUNDED')).toBe(false);
    expect(canRefundOrderStatus(undefined)).toBe(false);
  });

  it('退款请求体含 reason + restoreInventory', () => {
    expect(buildOrderRefundBody({ reason: '错识别', restoreInventory: true })).toEqual({
      reason: '错识别',
      restoreInventory: true
    });
    expect(buildOrderRefundBody({ reason: '仅退款', restoreInventory: false })).toEqual({
      reason: '仅退款',
      restoreInventory: false
    });
  });

  it('退款端点落在订单 refund 动作', () => {
    expect(AdminEndpoints.orderRefund('ORD-1')).toBe('/api/v2/ops/admin/orders/ORD-1/refund');
  });
});

describe('money-ui-contracts · 争议结案', () => {
  it('ADJUST/CONFIRM 带 items；KEEP/WAIVE 强制空 items', () => {
    const items = [{ skuId: 'S1', quantity: 1 }];
    expect(
      buildDisputeResolveBody({ resolutionType: 'ADJUST', restoreInventory: true, items })
    ).toEqual({
      resolutionType: 'ADJUST',
      restoreInventory: true,
      items
    });
    expect(buildDisputeResolveBody({ resolutionType: 'CONFIRM', items })).toEqual({
      resolutionType: 'CONFIRM',
      restoreInventory: undefined,
      items
    });
    expect(
      buildDisputeResolveBody({
        resolutionType: 'KEEP',
        items: [{ skuId: 'ignored', quantity: 9 }]
      })
    ).toEqual({
      resolutionType: 'KEEP',
      restoreInventory: undefined,
      items: []
    });
    expect(buildDisputeResolveBody({ resolutionType: 'WAIVE', restoreInventory: false })).toEqual({
      resolutionType: 'WAIVE',
      restoreInventory: false,
      items: []
    });
  });

  it('结案端点落在 disputes resolve', () => {
    expect(AdminEndpoints.disputeResolve('T-99')).toBe('/api/v2/ops/disputes/T-99/resolve');
  });
});

describe('money-ui-contracts · 商户提现审核', () => {
  it('审核/重试/取消解冻按状态+权限双条件', () => {
    expect(canReviewMerchantWithdraw('PENDING_REVIEW', true)).toBe(true);
    expect(canReviewMerchantWithdraw('PENDING_REVIEW', false)).toBe(false);
    expect(canReviewMerchantWithdraw('APPROVED', true)).toBe(false);

    expect(canRetryMerchantWithdrawPayout('APPROVED', true)).toBe(true);
    expect(canRetryMerchantWithdrawPayout('FAILED', true)).toBe(true);
    expect(canRetryMerchantWithdrawPayout('PENDING_REVIEW', true)).toBe(false);
    expect(canRetryMerchantWithdrawPayout('FAILED', false)).toBe(false);

    expect(canCancelFailedMerchantWithdraw('FAILED', true)).toBe(true);
    expect(canCancelFailedMerchantWithdraw('APPROVED', true)).toBe(false);
  });

  it('审核请求体 approve + 固定 remark（含批量）', () => {
    expect(buildMerchantWithdrawReviewBody(true)).toEqual({
      approve: true,
      remark: '审核通过'
    });
    expect(buildMerchantWithdrawReviewBody(false)).toEqual({
      approve: false,
      remark: '审核驳回'
    });
    expect(buildMerchantWithdrawReviewBody(true, { batch: true })).toEqual({
      approve: true,
      remark: '批量审核通过'
    });
    expect(buildMerchantWithdrawReviewBody(false, { batch: true })).toEqual({
      approve: false,
      remark: '批量审核驳回'
    });
  });

  it('提现审核端点落在 merchant-withdraws review', () => {
    expect(AdminEndpoints.merchantWithdrawReview(42)).toBe(
      '/api/v2/ops/admin/merchant-withdraws/42/review'
    );
  });
});

describe('money-ui-contracts · 线长提现（审计 P2-15 契约单点化）', () => {
  it('canReviewLineWithdraw 与商户版同语义（PENDING_REVIEW + 权限）', () => {
    expect(canReviewLineWithdraw('PENDING_REVIEW', true)).toBe(true);
    expect(canReviewLineWithdraw('PENDING_REVIEW', false)).toBe(false);
    expect(canReviewLineWithdraw('APPROVED', true)).toBe(false);
  });

  it('canRetryLineWithdrawPayout 仅 APPROVED/FAILED 可重试', () => {
    expect(canRetryLineWithdrawPayout('APPROVED', true)).toBe(true);
    expect(canRetryLineWithdrawPayout('FAILED', true)).toBe(true);
    expect(canRetryLineWithdrawPayout('PENDING_REVIEW', true)).toBe(false);
    expect(canRetryLineWithdrawPayout('FAILED', false)).toBe(false);
  });

  it('canCancelFailedLineWithdraw 仅 FAILED 可取消', () => {
    expect(canCancelFailedLineWithdraw('FAILED', true)).toBe(true);
    expect(canCancelFailedLineWithdraw('APPROVED', true)).toBe(false);
  });

  it('buildLineWithdrawReviewBody 与商户版同文案（单条/批量）', () => {
    expect(buildLineWithdrawReviewBody(true)).toEqual({ approve: true, remark: '审核通过' });
    expect(buildLineWithdrawReviewBody(false)).toEqual({ approve: false, remark: '审核驳回' });
    expect(buildLineWithdrawReviewBody(true, { batch: true })).toEqual({
      approve: true,
      remark: '批量审核通过'
    });
    expect(buildLineWithdrawReviewBody(false, { batch: true })).toEqual({
      approve: false,
      remark: '批量审核驳回'
    });
  });
});
