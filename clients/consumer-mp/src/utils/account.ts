import type { AccountDto } from '@aicabinet/shared-types';

/** 未开通免密支付时的最低开门余额（分）——仅作兜底 */
export const MIN_BALANCE_CENTS = 500;

export type EntryChannel = 'WECHAT' | 'ALIPAY';

export function normalizeEntryChannel(channel?: string | null): EntryChannel | null {
  const c = String(channel || '').trim().toUpperCase();
  if (c === 'WECHAT' || c === 'ALIPAY') return c;
  return null;
}

/** 从运行环境推断扫码渠道（小程序 / 内置浏览器） */
export function detectRuntimeEntryChannel(): EntryChannel | null {
  // #ifdef MP-WEIXIN
  return 'WECHAT';
  // #endif
  // #ifdef MP-ALIPAY
  return 'ALIPAY';
  // #endif
  // #ifdef H5
  if (typeof navigator !== 'undefined') {
    const ua = navigator.userAgent || '';
    if (/AlipayClient/i.test(ua)) return 'ALIPAY';
    if (/MicroMessenger/i.test(ua)) return 'WECHAT';
  }
  // #endif
  return null;
}

/** 扫码解析结果 + 运行环境 → 最终入口渠道 */
export function resolveEntryChannel(scanChannel?: string | null): EntryChannel | null {
  return normalizeEntryChannel(scanChannel) || detectRuntimeEntryChannel();
}

export function channelLabel(channel?: string | null): string {
  const c = normalizeEntryChannel(channel);
  if (c === 'WECHAT') return '微信';
  if (c === 'ALIPAY') return '支付宝';
  return '未知';
}

/**
 * 是否满足开门支付条件（真实业务优先免密）：
 * - 运营账号
 * - 指定渠道已开通免密（支付分 / 支付宝代扣）
 * - 或测试余额 ≥ ¥5（兜底）
 */
export function isPayReady(
  acc?: Pick<
    AccountDto,
    'operator' | 'passwordFreeReady' | 'balanceCents' | 'payscoreEnabled' | 'alipayAgreementEnabled'
  > | null,
  entryChannel?: string | null
): boolean {
  if (!acc) return false;
  if (acc.operator) return true;
  const channel = normalizeEntryChannel(entryChannel);
  if (channel === 'WECHAT') {
    if (acc.payscoreEnabled) return true;
  } else if (channel === 'ALIPAY') {
    if (acc.alipayAgreementEnabled) return true;
  } else if (acc.passwordFreeReady) {
    return true;
  }
  return (acc.balanceCents || 0) >= MIN_BALANCE_CENTS;
}

export function payReadyHint(
  acc?: Pick<
    AccountDto,
    'passwordFreeReady' | 'balanceCents' | 'verified' | 'payscoreEnabled' | 'alipayAgreementEnabled'
  > | null,
  entryChannel?: string | null
): string {
  if (!acc) return '请先登录';
  if (!acc.verified) return '需先完成实名认证';
  const channel = normalizeEntryChannel(entryChannel);
  if (channel === 'WECHAT') {
    if (acc.payscoreEnabled) return '已开通微信支付分，购物后自动扣款';
    if ((acc.balanceCents || 0) >= MIN_BALANCE_CENTS) return '可用测试余额兜底开门';
    return '请开通微信支付分（推荐），或充值测试余额至 ¥5 以上';
  }
  if (channel === 'ALIPAY') {
    if (acc.alipayAgreementEnabled) return '已开通支付宝免密，购物后自动扣款';
    if ((acc.balanceCents || 0) >= MIN_BALANCE_CENTS) return '可用测试余额兜底开门';
    return '请开通支付宝免密代扣（推荐），或充值测试余额至 ¥5 以上';
  }
  if (acc.passwordFreeReady) return '已开通免密支付';
  if ((acc.balanceCents || 0) >= MIN_BALANCE_CENTS) return '测试余额已满足开门条件';
  return '请开通微信/支付宝免密，或充值测试余额至 ¥5 以上';
}
