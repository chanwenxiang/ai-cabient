import { describe, expect, it } from 'vitest';
import {
  MINE_DEV_RECHARGE_CENTS,
  mineAlipayRechargeConfirm,
  mineMockRechargeConfirm,
  mineRechargeIdempotencyKey,
  mineWechatRechargeConfirm
} from './mine-recharge-copy';

describe('mine-recharge-copy · C7', () => {
  it('固定体验金额 20 元 = 2000 分', () => {
    expect(MINE_DEV_RECHARGE_CENTS).toBe(2000);
  });

  it('微信确认文案随 live 切换', () => {
    expect(mineWechatRechargeConfirm(true).title).toBe('微信支付充值');
    expect(mineWechatRechargeConfirm(false).content).toContain('体验到账');
  });

  it('支付宝确认文案随 mock 切换', () => {
    expect(mineAlipayRechargeConfirm(true).confirmText).toBe('确认到账');
    expect(mineAlipayRechargeConfirm(false).confirmText).toBe('去支付');
  });

  it('余额 mock 确认与幂等键前缀', () => {
    expect(mineMockRechargeConfirm().confirmText).toBe('确认发放');
    expect(mineRechargeIdempotencyKey('wechat', 1, 'aa')).toBe('mine-wechat-1-aa');
    expect(mineRechargeIdempotencyKey('alipay', 1, 'bb')).toBe('alipay-recharge-1-bb');
    expect(mineRechargeIdempotencyKey('mock', 1, 'cc')).toBe('mock-recharge-1-cc');
  });
});
