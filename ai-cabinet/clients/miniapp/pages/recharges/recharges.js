const api = require('../../utils/api');
const { showError, formatYuan, formatDateTime, rechargeStatusLabel, payChannelLabel } = require('../../utils/common');

Page({
  data: {
    records: [],
    page: 0,
    size: 20,
    total: 0,
    loading: false,
    loadingMore: false,
    refreshing: false,
    hasMore: true
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadRecords(true, true);
  },

  onPullDownRefresh() {
    this.loadRecords(true, true, true).finally(() => wx.stopPullDownRefresh());
  },

  onRefresh() {
    this.loadRecords(true, true, true);
  },

  onReachBottom() {
    this.loadRecords(false);
  },

  async loadRecords(reset, force, manual) {
    if (!force && (this.data.loading || this.data.loadingMore || this.data.refreshing)) return;
    if (!reset && !this.data.hasMore) return;

    const page = reset ? 0 : this.data.page + 1;
    const seq = (this._loadSeq = (this._loadSeq || 0) + 1);
    this.setData(reset ? { loading: manual ? false : true, refreshing: !!manual } : { loadingMore: true });

    try {
      const res = await api.listRecharges(page, this.data.size);
      if (seq !== this._loadSeq) return;
      const items = (res.items || []).map((r) => ({
        ...r,
        amountYuan: formatYuan(r.amountCents),
        statusLabel: rechargeStatusLabel(r.status),
        channelLabel: payChannelLabel(r.channel),
        createdAtText: formatDateTime(r.createdAt),
        paidAtText: formatDateTime(r.paidAt)
      }));
      const records = reset ? items : this.data.records.concat(items);
      this.setData({
        records,
        page: res.page,
        total: res.total,
        hasMore: records.length < res.total
      });
      if (manual) {
        wx.showToast({ title: '已刷新', icon: 'none', duration: 1200 });
      }
    } catch (e) {
      if (seq === this._loadSeq) showError('加载充值记录失败', e);
    } finally {
      if (seq === this._loadSeq) {
        this.setData({ loading: false, loadingMore: false, refreshing: false });
      }
    }
  }
});
