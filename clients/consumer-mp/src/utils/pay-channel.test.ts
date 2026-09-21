import { describe, expect, it } from 'vitest';
import type { AccountDto } from '@aicabinet/shared-types';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import { PAY_CHANNEL_KEYS, payChannelOptions, shouldAskPayChannel } from './pay-channel';

/**
 * 结算页支付方式选择（F6）的判据。
 *
 * 这一组要守住两件事，它们错了都是**静默**的：
 * ① 「哪些渠道该出现」必须与后端可扣款判据同源 —— 多给了 = 用户点了才报错；
 * ② 「什么时候该问用户」—— 单渠道还弹 = 白加一步点击；该弹不弹 = 功能等于没做。
 */
function account(patch: Partial<AccountDto> = {}): AccountDto {
  return {
    userId: 10001,
    phoneNumber: '13800000000',
    balanceCents: 0,
    frozenCents: 0,
    availableCents: 0,
    verified: true,
    operator: false,
    payPreferredChannel: 'BALANCE',
    payscoreEnabled: false,
    alipayAgreementEnabled: false,
    passwordFreeReady: false,
    ...patch
  } as AccountDto;
}

describe('payChannelOptions（可选渠道列表）', () => {
  it('账号未取到（null/undefined）⇒ 空列表，调用方据此退回「直接补缴」', () => {
    expect(payChannelOptions(null)).toEqual([]);
    expect(payChannelOptions(undefined)).toEqual([]);
  });

  it('未签约任何免密 ⇒ 只有余额这一个渠道', () => {
    const opts = payChannelOptions(account({ availableCents: 1230 }));
    expect(opts.map((o) => o.key)).toEqual(['BALANCE']);
    // 金额走 account.availableCents 口径 + fmtMoney，别自己拼字符串（口径必须同源）
    expect(opts[0].label).toBe(`账户余额 · ${fmtMoney(1230)}`);
  });

  it('已开通支付分 ⇒ 余额 + 微信免密', () => {
    const opts = payChannelOptions(account({ payscoreEnabled: true }));
    expect(opts.map((o) => o.key)).toEqual(['BALANCE', 'WECHAT']);
  });

  it('已开通支付宝免密 ⇒ 余额 + 支付宝免密', () => {
    const opts = payChannelOptions(account({ alipayAgreementEnabled: true }));
    expect(opts.map((o) => o.key)).toEqual(['BALANCE', 'ALIPAY']);
  });

  it('两者都开通 ⇒ 三个渠道，且顺序固定为 余额 → 微信 → 支付宝', () => {
    const opts = payChannelOptions(
      account({
        payscoreEnabled: true,
        alipayAgreementEnabled: true,
        availableCents: 500
      })
    );
    expect(opts.map((o) => o.key)).toEqual(['BALANCE', 'WECHAT', 'ALIPAY']);
  });

  it('🔴 余额为 0 也必须出现在列表里 —— 余额够不够由服务端/预授权决定，前端不另立一套规则', () => {
    const opts = payChannelOptions(
      account({ availableCents: 0, frozenCents: 0, payscoreEnabled: true })
    );
    expect(opts.map((o) => o.key)).toContain('BALANCE');
  });

  it('🔴 渠道之间不得串味：只开支付宝时绝不能冒出「微信免密」', () => {
    const opts = payChannelOptions(account({ payscoreEnabled: false, alipayAgreementEnabled: true }));
    const keys = opts.map((o) => o.key);
    expect(keys).not.toContain('WECHAT');
    expect(keys).toContain('ALIPAY');
  });

  it('副标题是给人看的文案，不参与判定；键值域始终在后端 PayChannels 之内', () => {
    const opts = payChannelOptions(account({ payscoreEnabled: true, alipayAgreementEnabled: true }));
    for (const o of opts) {
      expect(PAY_CHANNEL_KEYS as readonly string[]).toContain(o.key);
      expect(o.label.trim()).not.toBe('');
    }
  });
});

describe('shouldAskPayChannel（要不要先问用户）', () => {
  it('开关关闭（默认）⇒ 从不弹，即使三个渠道都可用（= 接入前行为）', () => {
    const opts = payChannelOptions(
      account({ payscoreEnabled: true, alipayAgreementEnabled: true })
    );
    expect(opts.length).toBe(3);
    expect(shouldAskPayChannel(false, opts)).toBe(false);
  });

  it('🔴 只有一个可用渠道 ⇒ 不弹（多问一次毫无信息量，只是白加一步）', () => {
    expect(shouldAskPayChannel(true, payChannelOptions(account()))).toBe(false);
  });

  it('两个及以上可用渠道 + 开关开 ⇒ 弹', () => {
    const two = payChannelOptions(account({ payscoreEnabled: true }));
    expect(shouldAskPayChannel(true, two)).toBe(true);

    const three = payChannelOptions(
      account({ payscoreEnabled: true, alipayAgreementEnabled: true })
    );
    expect(shouldAskPayChannel(true, three)).toBe(true);
  });

  it('账号未取到（空列表）+ 开关开 ⇒ 不弹（fail-closed，退回直接补缴）', () => {
    expect(shouldAskPayChannel(true, [])).toBe(false);
  });
});
