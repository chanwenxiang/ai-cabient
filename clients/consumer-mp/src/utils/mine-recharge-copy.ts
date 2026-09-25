/**
 * 我的页体验/渠道充值确认文案与幂等键（debt-tracker C7）。
 * 禁止在 mine.vue / open-prep 再散落一套 ¥20 确认文案。
 */
import { secureRandomToken } from '@/utils/secure-id';

export const MINE_DEV_RECHARGE_CENTS = 2000;
export const MINE_DEV_RECHARGE_YUAN_LABEL = '¥20.00';

export type ConfirmCopy = {
  title: string;
  content: string;
  confirmText: string;
};

export function mineWechatRechargeConfirm(wechatPayLive: boolean): ConfirmCopy {
  return {
    title: wechatPayLive ? '微信支付充值' : '微信充值',
    content: wechatPayLive
      ? `将调起微信支付充值 ${MINE_DEV_RECHARGE_YUAN_LABEL}。`
      : `将充值 ${MINE_DEV_RECHARGE_YUAN_LABEL} 到余额（体验到账，不会真实扣款）。`,
    confirmText: '确认'
  };
}

export function mineAlipayRechargeConfirm(isMock: boolean): ConfirmCopy {
  return {
    title: '支付宝充值',
    content: isMock
      ? `将充值 ${MINE_DEV_RECHARGE_YUAN_LABEL} 到余额（体验到账，不会真实扣款）。`
      : `将跳转支付宝支付页充值 ${MINE_DEV_RECHARGE_YUAN_LABEL}。`,
    confirmText: isMock ? '确认到账' : '去支付'
  };
}

export function mineMockRechargeConfirm(): ConfirmCopy {
  return {
    title: '确认充值',
    content: `将向当前账户发放 ${MINE_DEV_RECHARGE_YUAN_LABEL} 余额（体验到账，不会真实扣款）。`,
    confirmText: '确认发放'
  };
}

export function mineRechargeIdempotencyKey(
  kind: 'wechat' | 'alipay' | 'mock',
  nowMs: number = Date.now(),
  token: string = secureRandomToken(6)
): string {
  const prefix =
    kind === 'wechat' ? 'mine-wechat' : kind === 'alipay' ? 'alipay-recharge' : 'mock-recharge';
  return `${prefix}-${nowMs}-${token}`;
}
