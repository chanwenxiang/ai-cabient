export function fmtMoney(cents?: number) {
  if (cents == null) return '-';
  return '¥' + (cents / 100).toFixed(2);
}

export function formatError(err: unknown): string {
  if (!err) return '未知错误';
  if (typeof err === 'string') return err;
  const e = err as { status?: number; message?: string; errMsg?: string };
  if (e.status === 429) return '开门过于频繁，请稍后再试';
  if (e.message) {
    const msg = String(e.message);
    if (/too many door open/i.test(msg)) return '开门过于频繁，请稍后再试';
    if (/余额|balance/i.test(msg)) return '余额不足，请先充值';
    if (/device not found/i.test(msg)) return '设备不存在，请检查设备编号';
    if (/[\u4e00-\u9fff]/.test(msg)) return msg;
    return '操作失败，请稍后重试';
  }
  if (e.errMsg) return String(e.errMsg);
  return '请求失败';
}

export function orderStatusLabel(status?: string) {
  const map: Record<string, string> = {
    PAID: '已支付',
    COMPLETED: '已完成',
    PENDING: '待支付',
    PROCESSING: '处理中',
    FAILED: '处理失败',
    REFUNDED: '已退款',
    DISPUTED: '争议中',
    CANCELLED: '已取消'
  };
  return (status && map[status]) || status || '-';
}
