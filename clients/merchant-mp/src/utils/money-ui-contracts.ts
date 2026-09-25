/**
 * 商户端金钱相关 UI 契约（可单测）：状态门闩 + 写请求体形状。
 * View 必须复用本模块，禁止再手写提现体 / 争议结案体（debt-tracker M2）。
 */

export type WalletWithdrawRole = 'merchant' | 'line';

export type WalletWithdrawValidationError =
  'INVALID_AMOUNT' | 'EXCEEDS_AVAILABLE' | 'MERCHANT_REQUIRED';

export function validateWalletWithdrawAmount(input: {
  amountCents: number | null | undefined;
  availableCents: number;
}): WalletWithdrawValidationError | null {
  if (input.amountCents == null || !Number.isFinite(input.amountCents) || input.amountCents <= 0) {
    return 'INVALID_AMOUNT';
  }
  if (input.amountCents > input.availableCents) {
    return 'EXCEEDS_AVAILABLE';
  }
  return null;
}

/** 多商户绑定的商户角色必须显式带 merchantId。 */
export function shouldRequireWithdrawMerchantId(input: {
  role: WalletWithdrawRole;
  boundMerchantCount: number;
}): boolean {
  return input.role === 'merchant' && input.boundMerchantCount > 1;
}

export function validateWalletWithdrawMerchant(input: {
  role: WalletWithdrawRole;
  boundMerchantCount: number;
  merchantId?: string | null;
}): WalletWithdrawValidationError | null {
  if (!shouldRequireWithdrawMerchantId(input)) return null;
  if (!String(input.merchantId || '').trim()) return 'MERCHANT_REQUIRED';
  return null;
}

export function buildWalletWithdrawRequestNo(input: {
  prefix: string;
  nowMs: number;
  randomSuffix: string;
}): string {
  return `${input.prefix}${input.nowMs}-${input.randomSuffix}`;
}

export function buildWalletWithdrawBody(input: {
  role: WalletWithdrawRole;
  amountCents: number;
  requestNo: string;
  merchantId?: string;
  boundMerchantCount: number;
}): {
  amountCents: number;
  requestNo: string;
  merchantId?: string;
} {
  const body: { amountCents: number; requestNo: string; merchantId?: string } = {
    amountCents: input.amountCents,
    requestNo: input.requestNo
  };
  if (
    shouldRequireWithdrawMerchantId({
      role: input.role,
      boundMerchantCount: input.boundMerchantCount
    }) &&
    input.merchantId
  ) {
    body.merchantId = input.merchantId;
  }
  return body;
}

export const TERMINAL_WITHDRAW_STATUSES = ['PAID', 'REJECTED', 'FAILED'] as const;

export function isTerminalWithdrawStatus(status?: string | null): boolean {
  const s = String(status || '').toUpperCase();
  return (TERMINAL_WITHDRAW_STATUSES as readonly string[]).includes(s);
}

export type MerchantDisputeResolutionType = 'KEEP' | 'WAIVE' | 'CONFIRM';

export function isTerminalDisputeStatus(status?: string | null): boolean {
  const s = String(status || '').toUpperCase();
  return s === 'RESOLVED' || s === 'CLOSED';
}

export function canReplyMerchantDispute(input: {
  status?: string | null;
  hasReplyPerm: boolean;
}): boolean {
  return input.hasReplyPerm && String(input.status || '').toUpperCase() === 'OPEN';
}

/**
 * API 若返回 canResolve 则优先；否则 OPEN + 权限。
 * 最终仍须 `hasResolvePerm`（与页面 `canResolveDetail` 一致）。
 */
export function canResolveMerchantDispute(input: {
  status?: string | null;
  hasResolvePerm: boolean;
  canResolveFromApi?: boolean | null;
}): boolean {
  if (!input.hasResolvePerm) return false;
  if (input.canResolveFromApi == null) {
    return String(input.status || '').toUpperCase() === 'OPEN';
  }
  return !!input.canResolveFromApi;
}

export function buildMerchantDisputeResolveBody(input: {
  resolutionType: MerchantDisputeResolutionType;
}): {
  resolutionType: MerchantDisputeResolutionType;
  restoreInventory?: boolean;
} {
  const body: {
    resolutionType: MerchantDisputeResolutionType;
    restoreInventory?: boolean;
  } = { resolutionType: input.resolutionType };
  if (input.resolutionType === 'WAIVE') {
    body.restoreInventory = false;
  }
  return body;
}
