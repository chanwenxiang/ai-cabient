import { describe, expect, it } from 'vitest';
import type { AccountDto } from '@aicabinet/shared-types';
import { DEFAULT_PREAUTH_CENTS } from '@aicabinet/shared-types';
import {
  MIN_BALANCE_CENTS,
  availableCents,
  canUsePayoutAuthorization,
  channelLabel,
  detectRuntimeEntryChannel,
  isPayReady,
  normalizeEntryChannel,
  parsePreauthCents,
  payReadyHint,
  preauthYuanLabel,
  resolveClientPreauthCents,
  resolveEntryChannel
} from './account';

/** AccountDto 的三个金额字段是必填；over 覆写，传 undefined 可测「字段缺失」回落分支 */
type MoneyAcc = Pick<AccountDto, 'balanceCents' | 'availableCents' | 'frozenCents'>;
type PayAcc = MoneyAcc & Partial<AccountDto>;

function acc(over: Partial<AccountDto> = {}): PayAcc {
  return { balanceCents: 0, frozenCents: 0, availableCents: 0, ...over };
}

describe('canUsePayoutAuthorization', () => {
  it('仅明确运营/商户角色可见可用', () => {
    expect(canUsePayoutAuthorization(acc({ operator: true }))).toBe(true);
    expect(canUsePayoutAuthorization(acc({ operator: false }))).toBe(false);
    expect(canUsePayoutAuthorization(acc())).toBe(false);
    expect(canUsePayoutAuthorization(null)).toBe(false);
  });
});

describe('normalizeEntryChannel', () => {
  it('大小写与首尾空白归一化', () => {
    expect(normalizeEntryChannel(' wechat ')).toBe('WECHAT');
    expect(normalizeEntryChannel('Alipay')).toBe('ALIPAY');
  });

  it('未知渠道一律 null（不猜）', () => {
    expect(normalizeEntryChannel('unionpay')).toBeNull();
    expect(normalizeEntryChannel('')).toBeNull();
    expect(normalizeEntryChannel('   ')).toBeNull();
    expect(normalizeEntryChannel(undefined)).toBeNull();
    expect(normalizeEntryChannel(null)).toBeNull();
  });
});

describe('resolveEntryChannel', () => {
  it('扫码解析出的渠道优先于运行环境推断', () => {
    expect(resolveEntryChannel('alipay')).toBe('ALIPAY');
    expect(resolveEntryChannel(' WECHAT ')).toBe('WECHAT');
  });

  it('无扫码渠道时回落到运行环境推断（与 detectRuntimeEntryChannel 同源）', () => {
    expect(resolveEntryChannel(null)).toBe(detectRuntimeEntryChannel());
    expect(resolveEntryChannel('')).toBe(detectRuntimeEntryChannel());
    expect(resolveEntryChannel('unionpay')).toBe(detectRuntimeEntryChannel());
  });
});

describe('channelLabel', () => {
  it('按渠道给出中文标签，未知为「未知」', () => {
    expect(channelLabel('wechat')).toBe('微信');
    expect(channelLabel('ALIPAY')).toBe('支付宝');
    expect(channelLabel('unionpay')).toBe('未知');
    expect(channelLabel(undefined)).toBe('未知');
  });
});

describe('availableCents', () => {
  it('优先后端 availableCents，不再用 balance - frozen 重算', () => {
    expect(
      availableCents(acc({ availableCents: 1234, balanceCents: 9999, frozenCents: 500 }))
    ).toBe(1234);
  });

  it('无 availableCents 时回落到 balance - frozen', () => {
    expect(
      availableCents(acc({ balanceCents: 5000, frozenCents: 2000, availableCents: undefined }))
    ).toBe(3000);
  });

  it('负值不钳制（C-21：可用余额为负必须显形，不要伪装成 0）', () => {
    expect(
      availableCents(acc({ balanceCents: 100, frozenCents: 500, availableCents: undefined }))
    ).toBe(-400);
  });

  it('非有限数与空账号按 0 处理', () => {
    expect(availableCents(acc({ availableCents: Number.NaN }))).toBe(0);
    expect(availableCents(null)).toBe(0);
    expect(availableCents(undefined)).toBe(0);
  });
});

describe('parsePreauthCents', () => {
  it('保留显式 0，拒绝空值、负数、小数与非数字', () => {
    expect(parsePreauthCents(0)).toBe(0);
    expect(parsePreauthCents(' 0 ')).toBe(0);
    expect(parsePreauthCents('2000')).toBe(2000);
    expect(parsePreauthCents(null)).toBeNull();
    expect(parsePreauthCents(undefined)).toBeNull();
    expect(parsePreauthCents('')).toBeNull();
    expect(parsePreauthCents(-1)).toBeNull();
    expect(parsePreauthCents(1.5)).toBeNull();
    expect(parsePreauthCents('abc')).toBeNull();
  });
});

describe('resolveClientPreauthCents', () => {
  it('柜机 preauthCents 优先级最高', () => {
    expect(resolveClientPreauthCents({ devicePreauthCents: 500, configPreauthCents: 800 })).toBe(
      500
    );
  });

  it('柜机未配置时用柜机押金 deposit', () => {
    expect(resolveClientPreauthCents({ deviceDepositCents: 700 })).toBe(700);
  });

  it('柜机全缺时回落到公共配置（允许字符串数字）', () => {
    expect(resolveClientPreauthCents({ configPreauthCents: '900' })).toBe(900);
  });

  it('公共配置显式 0 关闭预授权，并压倒柜机正数门槛', () => {
    expect(resolveClientPreauthCents({ configPreauthCents: 0, devicePreauthCents: 500 })).toBe(0);
    expect(resolveClientPreauthCents({ configPreauthCents: '0', deviceDepositCents: 700 })).toBe(0);
  });

  it('DeviceStatus 已解析的 0 是有效值，不再回落默认', () => {
    expect(resolveClientPreauthCents({ devicePreauthCents: 0 })).toBe(0);
  });

  it('全缺、非法值或柜机 deposit=0 时用默认预授权', () => {
    expect(resolveClientPreauthCents()).toBe(MIN_BALANCE_CENTS);
    expect(resolveClientPreauthCents({ deviceDepositCents: 0 })).toBe(MIN_BALANCE_CENTS);
    expect(resolveClientPreauthCents({ devicePreauthCents: Number.NaN })).toBe(MIN_BALANCE_CENTS);
    expect(resolveClientPreauthCents({ configPreauthCents: -1 })).toBe(MIN_BALANCE_CENTS);
  });

  it('默认值来自 shared-types 的 DEFAULT_PREAUTH_CENTS', () => {
    expect(MIN_BALANCE_CENTS).toBe(DEFAULT_PREAUTH_CENTS);
  });
});

describe('preauthYuanLabel', () => {
  it('整数金额不带小数位', () => {
    expect(preauthYuanLabel(2000)).toBe('20');
    expect(preauthYuanLabel(100)).toBe('1');
  });

  it('非整数金额保留两位小数', () => {
    expect(preauthYuanLabel(2050)).toBe('20.50');
  });

  it('0 表示关闭预授权，不显示成 ¥0.01', () => {
    expect(preauthYuanLabel(0)).toBe('0');
  });
});

describe('isPayReady', () => {
  it('运营账号直接放行', () => {
    expect(isPayReady(acc({ operator: true }))).toBe(true);
  });

  it('微信渠道看支付分', () => {
    expect(isPayReady(acc({ payscoreEnabled: true }), 'WECHAT')).toBe(true);
    expect(isPayReady(acc({ payscoreEnabled: true }), 'ALIPAY')).toBe(false);
  });

  it('支付宝渠道看免密代扣', () => {
    expect(isPayReady(acc({ alipayAgreementEnabled: true }), 'ALIPAY')).toBe(true);
    expect(isPayReady(acc({ alipayAgreementEnabled: true }), 'WECHAT')).toBe(false);
  });

  it('渠道未知时看通用免密标记', () => {
    expect(isPayReady(acc({ passwordFreeReady: true }), 'unionpay')).toBe(true);
  });

  it('未开免密时按可用余额与门槛比较（刚好等于门槛算通过）', () => {
    expect(isPayReady(acc({ availableCents: 2000 }), 'WECHAT', 2000)).toBe(true);
    expect(isPayReady(acc({ availableCents: 1999 }), 'WECHAT', 2000)).toBe(false);
  });

  it('预授权关闭时不要求余额达到默认门槛', () => {
    expect(isPayReady(acc({ availableCents: 0 }), 'WECHAT', 0)).toBe(true);
    expect(isPayReady(acc({ availableCents: -100 }), null, 0)).toBe(true);
    expect(payReadyHint(acc({ availableCents: 0 }), 'WECHAT', 0)).toContain('预授权已关闭');
  });

  it('未登录返回 false', () => {
    expect(isPayReady(null)).toBe(false);
    expect(isPayReady(undefined)).toBe(false);
  });
});
