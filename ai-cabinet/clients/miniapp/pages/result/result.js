const api = require('../../utils/api');
const { showError, formatDateTime, formatError, orderStatusLabel, payChannelLabel, orderStatusTone } = require('../../utils/common');

function isOrderNotFound(err) {
  if (err && err.status === 404) return true;
  return /订单不存在|尚未生成|order not found/i.test(formatError(err));
}

function loadCartEstimate(sessionId) {
  if (!sessionId) return null;
  try {
    return wx.getStorageSync('cart_estimate_' + sessionId) || null;
  } catch (e) {
    return null;
  }
}

function buildCompare(order, estimate) {
  if (!estimate || !estimate.items || !estimate.items.length) return null;
  const actualCents = order.totalAmountCents || 0;
  const estCents = estimate.totalCents || 0;
  const diff = actualCents - estCents;
  let diffLabel = '与预估一致';
  let diffTone = 'match';
  if (diff > 0) {
    diffLabel = `比预估多 ¥${(diff / 100).toFixed(2)}`;
    diffTone = 'more';
  } else if (diff < 0) {
    diffLabel = `比预估少 ¥${((-diff) / 100).toFixed(2)}`;
    diffTone = 'less';
  }
  return {
    estimateYuan: (estCents / 100).toFixed(2),
    actualYuan: (actualCents / 100).toFixed(2),
    diffLabel,
    diffTone,
    items: estimate.items
  };
}

Page({
  data: {
    order: null,
    createdAtText: '',
    statusLabel: '',
    statusTone: 'default',
    payMethodLabel: '账户余额',
    loading: true,
    loadError: '',
    noOrder: false,
    sessionSummary: null,
    compare: null
  },

  onLoad(options) {
    this._sessionId = options.sessionId || '';
    const orderId = options.orderId && options.orderId !== 'undefined' ? options.orderId : '';
    if (orderId) {
      this.loadOrderById(orderId);
    } else if (this._sessionId) {
      this.loadOrderBySession(this._sessionId);
    } else {
      this.setData({ loading: false, loadError: '缺少会话或订单信息' });
    }
  },

  applyOrderView(order) {
    const compare = buildCompare(order, loadCartEstimate(this._sessionId || order.sessionId));
    this.setData({
      order,
      createdAtText: formatDateTime(order.createdAt),
      statusLabel: orderStatusLabel(order.status),
      statusTone: orderStatusTone(order.status),
      payMethodLabel: payChannelLabel(order.payChannel),
      compare,
      loading: false
    });
  },

  async loadOrderBySession(sessionId) {
    this.setData({ loading: true, loadError: '', noOrder: false, order: null, compare: null });
    try {
      wx.showLoading({ title: '加载账单...' });
      const order = await api.getOrder(sessionId);
      wx.hideLoading();
      this.applyOrderView(order);
    } catch (e) {
      wx.hideLoading();
      const msg = formatError(e);
      if (isOrderNotFound(e)) {
        await this.showSessionFallback(sessionId);
        return;
      }
      this.setData({ loading: false, loadError: msg });
      showError('加载失败', e);
    }
  },

  async loadOrderById(orderId) {
    this.setData({ loading: true, loadError: '', noOrder: false, order: null, compare: null });
    try {
      wx.showLoading({ title: '加载账单...' });
      const order = await api.getOrderById(orderId);
      wx.hideLoading();
      this._sessionId = order.sessionId || this._sessionId;
      this.applyOrderView(order);
    } catch (e) {
      wx.hideLoading();
      const msg = formatError(e);
      if (isOrderNotFound(e) && this._sessionId) {
        await this.showSessionFallback(this._sessionId);
        return;
      }
      this.setData({ loading: false, loadError: msg });
      showError('加载失败', e);
    }
  },

  async showSessionFallback(sessionId) {
    try {
      const session = await api.getSession(sessionId);
      if (session.orderId) {
        return this.loadOrderById(session.orderId);
      }
      if (session.state === 'DISPUTED') {
        this.setData({ loading: false });
        wx.showModal({
          title: '订单待审核',
          content: '识别结果需人工确认，审核通过后将生成账单。',
          confirmText: '查看进度',
          cancelText: '返回首页',
          success: (r) => {
            if (r.confirm) wx.navigateTo({ url: '/pages/dispute-mine/dispute-mine' });
            else wx.switchTab({ url: '/pages/index/index' });
          }
        });
        return;
      }
      if (session.state === 'FAILED') {
        const reason = session.failReason || '购物未完成';
        this.setData({
          loading: false,
          loadError: /余额|不足/i.test(reason) ? '当前余额不足，请先充值。' : reason
        });
        return;
      }
      if (session.state === 'COMPLETED') {
        let balanceCents = 0;
        try {
          const acc = await api.getAccount();
          balanceCents = acc.balanceCents || 0;
        } catch (e) { /* ignore */ }
        if (balanceCents < 500) {
          this.setData({
            loading: false,
            loadError: '当前余额不足，请先充值。'
          });
          return;
        }
        this.setData({
          loading: false,
          noOrder: true,
          sessionSummary: {
            sessionId: session.sessionId,
            deviceId: session.deviceId || '-'
          }
        });
        return;
      }
      this.setData({
        loading: false,
        loadError: `会话状态：${session.state || '-'}，账单尚未生成，请稍后在「我的订单」查看`
      });
    } catch (e) {
      this.setData({ loading: false, loadError: formatError(e) || '订单不存在' });
    }
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' });
  },

  goOrders() {
    wx.navigateTo({ url: '/pages/orders/orders' });
  }
});
