/**
 * 结算页支付方式选择的**规则** —— 纯函数，便于单测。
 *
 * 为什么单独抽出来：与 `promo-slot.ts` 同一个理由 —— vitest 跑在 `environment: 'node'`
 * （无 jsdom/组件测试），判据散在 `.vue` 里就**一条都落不下来**。而这里恰是最该有判据的地方：
 *
 * - 「哪些渠道该出现」必须与后端 `PayScoreService.isChannelUsable` **同源**，
 *   否则会出现「前端给了一个扣不动款的选项」（用户点了才报错，体验最差的一种）；
 * - 「什么时候该问用户」错成「单渠道也弹」= 给「去支付」白加一步；错成「该弹不弹」=
 *   功能等于没做。两者都是**静默的**功能缺陷。
 *
 * 见 `docs/COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md` F6。
 */
import type { AccountDto } from '@aicabinet/shared-types';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import { availableCents } from './account';

/** 显式选择用到的渠道值域（与后端 `PayChannels` 一致）。 */
export const PAY_CHANNEL_KEYS = ['BALANCE', 'WECHAT', 'ALIPAY'] as const;

export type PayChannelKey = (typeof PAY_CHANNEL_KEYS)[number];

export interface PayChannelOption {
  key: PayChannelKey;
  label: string;
}

/**
 * 结算页可选的支付方式（顺序固定：余额 → 微信免密 → 支付宝免密）。
 *
 * 就绪判据与后端 `PayScoreService.isChannelUsable` 同源：**余额恒可用**、
 * 微信看 `payscoreEnabled`（含协议号非空）、支付宝看 `alipayAgreementEnabled`
 * （后端用 `isActiveAlipayAgreementId`，PENDING 协议不算就绪）。
 *
 * 🔴 **余额不按余额多少过滤**：本单可能由开门预授权冻结额覆盖，够不够由服务端说了算；
 * 前端再定一套「够不够」的规则只会与服务端漂移。所以余额永远在列表里，
 * 真的不够时由服务端返回「余额不足」，用户再改选。
 */
export function payChannelOptions(acc: AccountDto | null | undefined): PayChannelOption[] {
  if (!acc) return [];
  const opts: PayChannelOption[] = [
    { key: 'BALANCE', label: `账户余额 · ${fmtMoney(availableCents(acc))}` }
  ];
  if (acc.payscoreEnabled) opts.push({ key: 'WECHAT', label: '微信免密' });
  if (acc.alipayAgreementEnabled) opts.push({ key: 'ALIPAY', label: '支付宝免密' });
  return opts;
}

/**
 * 是否要先弹渠道选择。
 *
 * **单渠道不弹** —— 只有一个可选项时多问一次毫无信息量，只是白加一步点击；
 * 此时直接走原有「去支付」，服务端按该渠道扣款，与开关关闭时的表现一致。
 */
export function shouldAskPayChannel(
  enabled: boolean,
  options: readonly PayChannelOption[]
): boolean {
  return enabled && options.length >= 2;
}
