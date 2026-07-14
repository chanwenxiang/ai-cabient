import { fmtMoney, formatError, orderStatusLabel } from './format';

export { fmtMoney, formatError, orderStatusLabel };

const SESSION_STATE_LABEL: Record<string, string> = {
  CREATED: '已创建',
  OPENING: '开门中',
  SHOPPING: '购物中，取货后请关门',
  RECOGNIZING: '识别中',
  WAITING_UPLOAD: '等待上传视频',
  SETTLING: '结算中',
  COMPLETED: '已完成',
  DISPUTED: '待人工审核',
  FAILED: '失败',
  CANCELLED: '已取消'
};

const SESSION_STATE_HINT: Record<string, string> = {
  CREATED: '正在准备开门，请稍候',
  OPENING: '柜门正在打开，请稍候',
  SHOPPING: '门已打开，请随意取货；关柜门后自动识别并结算',
  RECOGNIZING: '已关门，正在识别您取走的商品',
  WAITING_UPLOAD: '等待设备上传购物视频',
  SETTLING: '识别完成，正在扣款结算',
  COMPLETED: '购物已完成，可查看账单',
  DISPUTED: '识别结果需人工确认，请稍后再查或联系客服',
  FAILED: '购物未完成，请检查余额或稍后重试',
  CANCELLED: '会话已取消'
};

export function sessionStateLabel(state?: string) {
  return (state && SESSION_STATE_LABEL[state]) || '-';
}

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
