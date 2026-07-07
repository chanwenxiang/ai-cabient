const api = require('../../utils/api');
const { showError, formatYuan, formatDateTime, orderStatusLabel } = require('../../utils/common');

Page({
  data: {
    orders: [],
    page: 0,
    size: 20,
    total: 0,
    loading: false,
    loadingMore: false,
    hasMore: true
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadOrders(true);
  },

  onPullDownRefresh() {
    this.loadOrders(true).finally(() => wx.stopPullDownRefresh());
  },

  onRefresh() {
    this.loadOrders(true);
  },

  onReachBottom() {
    this.loadOrders(false);
  },

  async loadOrders(reset) {
    if (this.data.loading || this.data.loadingMore) return;
    if (!reset && !this.data.hasMore) return;

    const page = reset ? 0 : this.data.page + 1;
    this.setData(reset ? { loading: true } : { loadingMore: true });

    try {
      const res = await api.listOrders(page, this.data.size);
      const items = (res.items || []).map((o) => ({
        ...o,
        amountYuan: formatYuan(o.totalAmountCents),
        createdAtText: formatDateTime(o.createdAt),
        statusLabel: orderStatusLabel(o.status)
      }));
      const orders = reset ? items : this.data.orders.concat(items);
      const hasMore = orders.length < res.total;
      this.setData({
        orders,
        page: res.page,
        total: res.total,
        hasMore
      });
    } catch (e) {
      showError('加载订单失败', e);
    } finally {
      this.setData({ loading: false, loadingMore: false });
    }
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' });
  },

  onOpenOrder(e) {
    const orderId = e.currentTarget.dataset.id;
    const sessionId = e.currentTarget.dataset.session;
    wx.navigateTo({
      url: `/pages/result/result?orderId=${orderId}&sessionId=${sessionId || ''}`
    });
  }
});
