/** 小程序公共工具：错误格式化、401 处理、会话状态文案 */

const SESSION_STATE_LABEL = {
  CREATED: '已创建',
  OPENING: '开门中',
  SHOPPING: '购物中，请取货后关门',
  RECOGNIZING: '识别中',
  WAITING_UPLOAD: '等待上传视频',
  SETTLING: '结算中',
  COMPLETED: '已完成',
  DISPUTED: '待人工审核',
  FAILED: '失败',
  CANCELLED: '已取消'
};

function sessionStateLabel(state) {
  return SESSION_STATE_LABEL[state] || state || '-';
}

const SESSION_STATE_HINT = {
  CREATED: '正在准备开门，请稍候',
  OPENING: '柜门正在打开，请稍候',
  SHOPPING: '请取货后关闭柜门，系统将自动识别并结算',
  RECOGNIZING: '正在识别商品，通常需要几秒',
  WAITING_UPLOAD: '等待设备上传购物视频',
  SETTLING: '正在计算账单并扣款',
  COMPLETED: '购物已完成，可查看账单',
  DISPUTED: '识别结果需人工确认，请稍后再查或联系客服',
  FAILED: '本次购物未完成，请检查余额或联系运营',
  CANCELLED: '会话已取消'
};

function sessionStateHint(state) {
  return SESSION_STATE_HINT[state] || '';
}

function sessionStateTone(state) {
  if (!state) return 'idle';
  if (state === 'COMPLETED') return 'success';
  if (state === 'FAILED' || state === 'CANCELLED') return 'error';
  if (state === 'DISPUTED') return 'wait';
  if (state === 'SHOPPING' || state === 'OPENING' || state === 'CREATED') return 'active';
  if (state === 'RECOGNIZING' || state === 'WAITING_UPLOAD' || state === 'SETTLING') return 'wait';
  return 'idle';
}

function formatError(err) {
  if (!err) return '未知错误';
  if (typeof err === 'string') return err;
  if (err.status === 429) {
    return '开门过于频繁，请稍后再试（开发环境可重启 trade-service 或等待 1 小时）';
  }
  if (err.message) {
    const msg = String(err.message);
    if (/too many door open/i.test(msg)) {
      return '开门过于频繁，请稍后再试';
    }
    if (/blacklisted/i.test(msg)) return '账号受限，请联系客服';
    if (/余额|balance/i.test(msg)) return '余额不足，请先充值';
    if (/occupied|busy|占用/i.test(msg)) return '设备使用中，请稍后再试';
    return msg;
  }
  if (err.errMsg) return String(err.errMsg);
  return '请求失败';
}

function isAuthError(err) {
  if (!err) return false;
  if (err.status === 401 || err.status === 403) return true;
  const msg = formatError(err);
  return /401|403|unauthorized|invalid token|登录已失效/i.test(msg);
}

function clearAuth() {
  wx.removeStorageSync('token');
  wx.removeStorageSync('userId');
  wx.reLaunch({ url: '/pages/login/login' });
}

function handleAuthError(err) {
  if (!isAuthError(err)) return false;
  wx.showToast({ title: '登录已失效，请重新登录', icon: 'none' });
  setTimeout(clearAuth, 600);
  return true;
}

function showError(title, err) {
  if (handleAuthError(err)) return;
  wx.showModal({ title, content: formatError(err), showCancel: false });
}

const RECHARGE_STATUS_LABEL = {
  PENDING: '待支付',
  PAID: '已支付',
  CANCELLED: '已取消',
  REFUNDED: '已退款'
};

const ORDER_STATUS_LABEL = {
  PAID: '已支付',
  PENDING: '待支付',
  REFUNDED: '已退款',
  CANCELLED: '已取消'
};

function orderStatusLabel(status) {
  return ORDER_STATUS_LABEL[status] || status || '-';
}

function rechargeStatusLabel(status) {
  return RECHARGE_STATUS_LABEL[status] || status || '-';
}

function formatYuan(cents) {
  if (cents == null) return '0.00';
  return (cents / 100).toFixed(2);
}

function formatDateTime(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

module.exports = {
  SESSION_STATE_LABEL,
  sessionStateLabel,
  sessionStateHint,
  sessionStateTone,
  formatError,
  formatYuan,
  formatDateTime,
  rechargeStatusLabel,
  orderStatusLabel,
  isAuthError,
  clearAuth,
  handleAuthError,
  showError
};
