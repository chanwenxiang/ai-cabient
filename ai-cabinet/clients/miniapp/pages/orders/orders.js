const api = require('../../utils/api');
const {
  showError,
  formatYuan,
  formatDateTime,
  orderStatusLabel,
  payChannelLabel,
  orderStatusTone,
  orderDisputeTag
} = require('../../utils/common');

Page({
  data: {
    orders: [],
    page: 0,
    size: 20,
    total: 0,
    loading: false,
    loadingMore: false,
    refreshing: false,
    hasMore: true,
    disputeBySession: {}
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadOrders(true, true);
  },

  onPullDownRefresh() {
    this.loadOrders(true, true, true).finally(() => wx.stopPullDownRefresh());
  },

  onRefresh() {
    this.loadOrders(true, true, true);
  },

  onReachBottom() {
    this.loadOrders(false);
  },

  mapOrder(o, disputeBySession) {
    const ds = disputeBySession[o.sessionId] || '';
    const status = o.status || 'PAID';
    return {
      ...o,
      amountYuan: formatYuan(o.totalAmountCents),
      createdAtText: formatDateTime(o.createdAt),
      statusLabel: orderStatusLabel(status),
      statusTone: orderStatusTone(status),
      payMethodLabel: payChannelLabel(o.payChannel),
      lineSummary: o.lineSummary || (o.lineCount ? `${o.lineCount} 件商品` : ''),
      disputeStatus: ds,
      disputeLabel: orderDisputeTag(ds)
    };
  },

  async loadOrders(reset, force, manual) {
    if (!force && (this.data.loading || this.data.loadingMore || this.data.refreshing)) return;
    if (!reset && !this.data.hasMore) return;

    const page = reset ? 0 : this.data.page + 1;
    const seq = (this._loadSeq = (this._loadSeq || 0) + 1);
    this.setData(reset ? { loading: manual ? false : true, refreshing: !!manual } : { loadingMore: true });

    try {
      const [res, disputes] = await Promise.all([
        api.listOrders(page, this.data.size),
        reset ? api.listMyDisputes().catch(() => []) : Promise.resolve(null)
      ]);
      const disputeBySession = reset ? {} : { ...(this.data.disputeBySession || {}) };
      if (Array.isArray(disputes)) {
        disputes.forEach((d) => {
          if (d.sessionId) disputeBySession[d.sessionId] = d.status;
        });
      }
      const items = (res.items || []).map((o) => this.mapOrder(o, disputeBySession));
      if (seq !== this._loadSeq) return;
      const orders = reset ? items : this.data.orders.concat(items);
      const hasMore = orders.length < res.total;
      this.setData({
        orders,
        page: res.page,
        total: res.total,
        hasMore,
        disputeBySession
      });
      if (manual) {
        wx.showToast({ title: '已刷新', icon: 'none', duration: 1200 });
      }
    } catch (e) {
      if (seq === this._loadSeq) showError('加载订单失败', e);
    } finally {
      if (seq === this._loadSeq) {
        this.setData({ loading: false, loadingMore: false, refreshing: false });
      }
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
  },

  noop() {},

  onDispute(e) {
    const sessionId = e.currentTarget.dataset.session;
    if (!sessionId) {
      return wx.showToast({ title: '缺少会话信息', icon: 'none' });
    }
    wx.showModal({
      title: '提交申诉',
      editable: true,
      placeholderText: '请描述问题，如商品识别有误',
      success: async (res) => {
        if (!res.confirm) return;
        const reason = (res.content || '').trim();
        if (!reason) {
          return wx.showToast({ title: '请填写申诉原因', icon: 'none' });
        }
        try {
          wx.showLoading({ title: '提交中' });
          await api.fileDispute(sessionId, reason);
          wx.hideLoading();
          wx.showToast({ title: '已提交', icon: 'success' });
          this.loadOrders(true, true, true);
        } catch (err) {
          wx.hideLoading();
          showError('申诉失败', err);
        }
      }
    });
  }
});
