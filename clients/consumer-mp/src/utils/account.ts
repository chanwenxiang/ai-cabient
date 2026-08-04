import type { AccountDto } from '@aicabinet/shared-types';
import { DEFAULT_PREAUTH_CENTS } from '@aicabinet/shared-types';

/** 未开通免密时默认最低开门预授权（分）；实际以服务端配置 / 柜机押金为准 */
export const MIN_BALANCE_CENTS = DEFAULT_PREAUTH_CENTS;

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

/** 可用余额口径：优先后端 availableCents，否则 balance - frozen */
export function availableCents(acc?: Pick<AccountDto, 'balanceCents' | 'availableCents' | 'frozenCents'> | null): number {
  if (!acc) return 0;
  if (acc.availableCents != null) return Math.max(0, acc.availableCents);
  return Math.max(0, (acc.balanceCents || 0) - Math.max(0, acc.frozenCents || 0));
}

/**
 * 解析开门预授权门槛（分）：
 * 1) 柜机 deposit / DeviceStatus.preauthCents
 * 2) 公共配置 checkout.preauth_cents
 * 3) 默认 DEFAULT_PREAUTH_CENTS
 */
export function resolveClientPreauthCents(opts?: {
  devicePreauthCents?: number | null;
  deviceDepositCents?: number | null;
  configPreauthCents?: number | string | null;
}): number {
  const device = Number(opts?.devicePreauthCents ?? opts?.deviceDepositCents);
  if (Number.isFinite(device) && device > 0) return Math.floor(device);
  const cfg = Number(opts?.configPreauthCents);
  if (Number.isFinite(cfg) && cfg > 0) return Math.floor(cfg);
  return MIN_BALANCE_CENTS;
}

export function preauthYuanLabel(preauthCents: number = MIN_BALANCE_CENTS): string {
  const yuan = Math.max(preauthCents, 1) / 100;
  return Number.isInteger(yuan) ? String(yuan) : yuan.toFixed(2);
}

/**
 * 是否满足开门支付条件（真实业务优先免密）：
 * - 运营账号
 * - 指定渠道已开通免密（支付分 / 支付宝代扣）
 * - 或可用余额 ≥ 预授权门槛（默认 ¥20）
 */
export function isPayReady(
  acc?: Pick<
    AccountDto,
    | 'operator'
    | 'passwordFreeReady'
    | 'balanceCents'
    | 'availableCents'
    | 'frozenCents'
    | 'payscoreEnabled'
    | 'alipayAgreementEnabled'
  > | null,
  entryChannel?: string | null,
  preauthCents: number = MIN_BALANCE_CENTS
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
  return availableCents(acc) >= Math.max(preauthCents, 1);
}

export function payReadyHint(
  acc?: Pick<
    AccountDto,
    | 'passwordFreeReady'
    | 'balanceCents'
    | 'availableCents'
    | 'frozenCents'
    | 'verified'
    | 'payscoreEnabled'
    | 'alipayAgreementEnabled'
  > | null,
  entryChannel?: string | null,
  preauthCents: number = MIN_BALANCE_CENTS
): string {
  if (!acc) return '请先登录';
  if (!acc.verified) return '需先完成实名认证';
  const needYuan = preauthYuanLabel(preauthCents);
  const channel = normalizeEntryChannel(entryChannel);
  if (channel === 'WECHAT') {
    if (acc.payscoreEnabled) return '已开通微信支付分，购物后自动扣款';
    if (availableCents(acc) >= preauthCents) return `可用余额可预授权开门（约 ¥${needYuan}）`;
    return `请开通微信支付分（推荐），或充值可用余额至 ¥${needYuan} 以上`;
  }
  if (channel === 'ALIPAY') {
    if (acc.alipayAgreementEnabled) return '已开通支付宝免密，购物后自动扣款';
    if (availableCents(acc) >= preauthCents) return `可用余额可预授权开门（约 ¥${needYuan}）`;
    return `请开通支付宝免密代扣（推荐），或充值可用余额至 ¥${needYuan} 以上`;
  }
  if (acc.passwordFreeReady) return '已开通免密支付';
  if (availableCents(acc) >= preauthCents) return `可用余额可预授权开门（约 ¥${needYuan}）`;
  return `请开通微信/支付宝免密，或充值可用余额至 ¥${needYuan} 以上`;
}
