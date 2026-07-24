import { dictLabel } from '@aicabinet/shared-dict';
import { fmtMoney, formatError, orderStatusLabel } from './format';

export { fmtMoney, formatError, orderStatusLabel };

/**
 * 会话状态短标签：与 shared-dict 的 session_state 对齐，避免三端文案漂移。
 * 更长的引导语放在 sessionStateHint。
 */
export function sessionStateLabel(state?: string) {
  return state ? dictLabel('session_state', state) : '-';
}

const SESSION_STATE_HINT: Record<string, string> = {
  CREATED: '正在准备开门，请稍候',
  OPENING: '柜门正在打开，请稍候',
  SHOPPING: '门已打开，请随意取货；关柜门后自动识别并结算',
  RECOGNIZING: '已关门，正在识别您取走的商品',
  WAITING_UPLOAD: '等待柜机上传购物视频',
  SETTLING: '识别完成，正在扣款结算',
  COMPLETED: '购物已完成，可查看账单',
  DISPUTED: '识别结果需人工确认，请稍后再查或联系客服',
  FAILED: '购物未完成，请检查余额或稍后重试',
  CANCELLED: '会话已取消'
};

export function sessionStateHint(state?: string) {
  return (state && SESSION_STATE_HINT[state]) || '';
}

export function sessionStateTone(state?: string): string {
  if (!state) return 'idle';
  if (state === 'COMPLETED') return 'success';
  if (state === 'FAILED' || state === 'CANCELLED') return 'error';
  if (state === 'DISPUTED') return 'wait';
  if (state === 'SHOPPING' || state === 'OPENING' || state === 'CREATED') return 'active';
  if (state === 'RECOGNIZING' || state === 'WAITING_UPLOAD' || state === 'SETTLING') return 'wait';
  return 'idle';
}
