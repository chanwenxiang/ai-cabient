const api = require('../../utils/api');
const { showError, formatDateTime, formatError } = require('../../utils/common');

Page({
  data: { order: null, createdAtText: '', loading: true, loadError: '' },

  onLoad(options) {
    if (options.orderId) {
      this.loadOrderById(options.orderId);
    } else if (options.sessionId) {
      this.loadOrderBySession(options.sessionId);
    }
  },

  async loadOrderBySession(sessionId) {
    this.setData({ loading: true, loadError: '' });
    try {
      wx.showLoading({ title: '加载账单...' });
      const order = await api.getOrder(sessionId);
      this.setData({
        order,
        createdAtText: formatDateTime(order.createdAt),
        loading: false
      });
      wx.hideLoading();
    } catch (e) {
      wx.hideLoading();
      this.setData({ loading: false, loadError: formatError(e) });
      showError('加载失败', e);
    }
  },

  async loadOrderById(orderId) {
    this.setData({ loading: true, loadError: '' });
    try {
      wx.showLoading({ title: '加载账单...' });
      const order = await api.getOrderById(orderId);
      this.setData({
        order,
        createdAtText: formatDateTime(order.createdAt),
        loading: false
      });
      wx.hideLoading();
    } catch (e) {
      wx.hideLoading();
      this.setData({ loading: false, loadError: formatError(e) });
      showError('加载失败', e);
    }
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' });
  },

  goOrders() {
    wx.navigateTo({ url: '/pages/orders/orders' });
  }
});
