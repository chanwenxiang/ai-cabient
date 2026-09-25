import { describe, expect, it } from 'vitest';
import {
  buildMerchantDisputeResolveBody,
  buildWalletWithdrawBody,
  buildWalletWithdrawRequestNo,
  canReplyMerchantDispute,
  canResolveMerchantDispute,
  isTerminalDisputeStatus,
  isTerminalWithdrawStatus,
  shouldRequireWithdrawMerchantId,
  validateWalletWithdrawAmount,
  validateWalletWithdrawMerchant
} from './money-ui-contracts';

describe('money-ui-contracts · 钱包提现', () => {
  it('金额校验：非法 / 超额', () => {
    expect(validateWalletWithdrawAmount({ amountCents: null, availableCents: 100 })).toBe(
      'INVALID_AMOUNT'
    );
    expect(validateWalletWithdrawAmount({ amountCents: 0, availableCents: 100 })).toBe(
      'INVALID_AMOUNT'
    );
    expect(validateWalletWithdrawAmount({ amountCents: -1, availableCents: 100 })).toBe(
      'INVALID_AMOUNT'
    );
    expect(validateWalletWithdrawAmount({ amountCents: 101, availableCents: 100 })).toBe(
      'EXCEEDS_AVAILABLE'
    );
    expect(validateWalletWithdrawAmount({ amountCents: 50, availableCents: 100 })).toBeNull();
  });

  it('仅商户角色且绑定数>1 必须选商户', () => {
    expect(shouldRequireWithdrawMerchantId({ role: 'merchant', boundMerchantCount: 2 })).toBe(true);
    expect(shouldRequireWithdrawMerchantId({ role: 'merchant', boundMerchantCount: 1 })).toBe(
      false
    );
    expect(shouldRequireWithdrawMerchantId({ role: 'line', boundMerchantCount: 9 })).toBe(false);
    expect(
      validateWalletWithdrawMerchant({
        role: 'merchant',
        boundMerchantCount: 2,
        merchantId: ''
      })
    ).toBe('MERCHANT_REQUIRED');
    expect(
      validateWalletWithdrawMerchant({
        role: 'merchant',
        boundMerchantCount: 2,
        merchantId: 'M1'
      })
    ).toBeNull();
  });

  it('requestNo = prefix + nowMs + - + suffix', () => {
    expect(
      buildWalletWithdrawRequestNo({ prefix: 'MW-', nowMs: 1700000000000, randomSuffix: 'abc12' })
    ).toBe('MW-1700000000000-abc12');
  });

  it('提现体：多商户带 merchantId；单商户/线长不带', () => {
    expect(
      buildWalletWithdrawBody({
        role: 'merchant',
        amountCents: 100,
        requestNo: 'MW-1',
        merchantId: 'M1',
        boundMerchantCount: 2
      })
    ).toEqual({ amountCents: 100, requestNo: 'MW-1', merchantId: 'M1' });
    expect(
      buildWalletWithdrawBody({
        role: 'merchant',
        amountCents: 100,
        requestNo: 'MW-1',
        merchantId: 'M1',
        boundMerchantCount: 1
      })
    ).toEqual({ amountCents: 100, requestNo: 'MW-1' });
    expect(
      buildWalletWithdrawBody({
        role: 'line',
        amountCents: 50,
        requestNo: 'MP-1',
        boundMerchantCount: 0
      })
    ).toEqual({ amountCents: 50, requestNo: 'MP-1' });
  });

  it('提现终态：PAID/REJECTED/FAILED', () => {
    expect(isTerminalWithdrawStatus('PAID')).toBe(true);
    expect(isTerminalWithdrawStatus('rejected')).toBe(true);
    expect(isTerminalWithdrawStatus('FAILED')).toBe(true);
    expect(isTerminalWithdrawStatus('PENDING_REVIEW')).toBe(false);
  });
});

describe('money-ui-contracts · 争议结案', () => {
  it('KEEP/CONFIRM 仅 resolutionType；WAIVE 带 restoreInventory:false', () => {
    expect(buildMerchantDisputeResolveBody({ resolutionType: 'KEEP' })).toEqual({
      resolutionType: 'KEEP'
    });
    expect(buildMerchantDisputeResolveBody({ resolutionType: 'CONFIRM' })).toEqual({
      resolutionType: 'CONFIRM'
    });
    expect(buildMerchantDisputeResolveBody({ resolutionType: 'WAIVE' })).toEqual({
      resolutionType: 'WAIVE',
      restoreInventory: false
    });
  });

  it('回复/结案门闩与 API 覆盖', () => {
    expect(canReplyMerchantDispute({ status: 'OPEN', hasReplyPerm: true })).toBe(true);
    expect(canReplyMerchantDispute({ status: 'RESOLVED', hasReplyPerm: true })).toBe(false);
    expect(canReplyMerchantDispute({ status: 'OPEN', hasReplyPerm: false })).toBe(false);

    expect(
      canResolveMerchantDispute({ status: 'OPEN', hasResolvePerm: true, canResolveFromApi: null })
    ).toBe(true);
    expect(
      canResolveMerchantDispute({
        status: 'CLOSED',
        hasResolvePerm: true,
        canResolveFromApi: true
      })
    ).toBe(true);
    expect(
      canResolveMerchantDispute({ status: 'OPEN', hasResolvePerm: false, canResolveFromApi: true })
    ).toBe(false);
  });

  it('争议终态：RESOLVED/CLOSED', () => {
    expect(isTerminalDisputeStatus('RESOLVED')).toBe(true);
    expect(isTerminalDisputeStatus('closed')).toBe(true);
    expect(isTerminalDisputeStatus('OPEN')).toBe(false);
  });
});
