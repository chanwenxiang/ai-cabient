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
  return SESSION_STATE_LABEL[state] || '-';
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
  FAILED: '购物未完成，请检查余额或稍后重试',
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

/** 中国大陆手机号：11 位，1 开头 */
function normalizePhone(v) {
  return (v || '').replace(/\D/g, '').slice(0, 11);
}

function isValidPhone(v) {
  return /^1\d{10}$/.test(normalizePhone(v));
}

function invalidPhoneMessage() {
  return '请输入11位有效手机号';
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
    if (/申诉|dispute already/i.test(msg)) {
      return '该订单已提交过申诉';
    }
    if (/blacklisted/i.test(msg)) return '账号受限，请联系客服';
    if (/余额|balance/i.test(msg)) return '余额不足，请先充值';
    if (/订单不存在|order not found/i.test(msg)) return '订单不存在或尚未生成';
    if (/occupied|busy|占用/i.test(msg)) return '设备使用中，请稍后再试';
    if (/device not found/i.test(msg)) return '设备不存在，请检查设备编号';
    if (/session_state|session state/i.test(msg)) return '会话状态异常，请稍后重试';
    if (/[\u4e00-\u9fff]/.test(msg)) return msg;
    return '操作失败，请稍后重试';
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
  wx.removeStorageSync('server_boot');
  wx.removeStorageSync('token_expires');
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

const PAY_CHANNEL_LABEL = {
  BALANCE: '账户余额',
  WECHAT: '微信支付',
  ALIPAY: '支付宝'
};

function orderStatusLabel(status) {
  return ORDER_STATUS_LABEL[(status || '').toUpperCase()] || status || '-';
}

function payChannelLabel(channel) {
  const key = (channel || 'BALANCE').toUpperCase();
  return PAY_CHANNEL_LABEL[key] || channel || '账户余额';
}

function orderStatusTone(status) {
  const s = (status || '').toUpperCase();
  if (s === 'PAID') return 'paid';
  if (s === 'PENDING') return 'pending';
  if (s === 'REFUNDED') return 'refunded';
  if (s === 'CANCELLED') return 'cancelled';
  return 'default';
}

function rechargeStatusLabel(status) {
  return RECHARGE_STATUS_LABEL[status] || '-';
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

function formatRelativeTime(iso) {
  if (!iso) return '-';
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 0) return '刚刚';
  const minutes = Math.floor(ms / 60000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days} 天前`;
  return formatDateTime(iso);
}

const DISPUTE_STATUS_LABEL = {
  OPEN: '审核中',
  RESOLVED: '已处理',
  CLOSED: '已处理'
};

function disputeStatusLabel(status) {
  return DISPUTE_STATUS_LABEL[status] || '-';
}

function formatLineItem(item) {
  if (!item) return '';
  const name = item.skuName || item.skuId || '-';
  const batch = item.batchNo ? ` @${item.batchNo}` : '';
  return `${name} × ${item.quantity}${batch}`;
}

function formatLineItems(items) {
  if (!items || !items.length) return '';
  return items.map(formatLineItem).join('；');
}

function orderDisputeTag(status) {
  if (!status) return '';
  const map = { OPEN: '申诉中', CLOSED: '申诉已处理', RESOLVED: '申诉已处理' };
  return map[status] || status;
}

module.exports = {
  SESSION_STATE_LABEL,
  sessionStateLabel,
  sessionStateHint,
  sessionStateTone,
  formatError,
  formatYuan,
  formatDateTime,
  formatRelativeTime,
  disputeStatusLabel,
  orderDisputeTag,
  formatLineItem,
  formatLineItems,
  rechargeStatusLabel,
  orderStatusLabel,
  payChannelLabel,
  orderStatusTone,
  normalizePhone,
  isValidPhone,
  invalidPhoneMessage,
  isAuthError,
  clearAuth,
  handleAuthError,
  showError
};
