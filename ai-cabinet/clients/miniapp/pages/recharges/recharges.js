const api = require('../../utils/api');
const { showError, formatYuan, formatDateTime, rechargeStatusLabel } = require('../../utils/common');

Page({
  data: {
    records: [],
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
    this.loadRecords(true);
  },

  onPullDownRefresh() {
    this.loadRecords(true).finally(() => wx.stopPullDownRefresh());
  },

  onRefresh() {
    this.loadRecords(true);
  },

  onReachBottom() {
    this.loadRecords(false);
  },

  async loadRecords(reset) {
    if (this.data.loading || this.data.loadingMore) return;
    if (!reset && !this.data.hasMore) return;

    const page = reset ? 0 : this.data.page + 1;
    this.setData(reset ? { loading: true } : { loadingMore: true });

    try {
      const res = await api.listRecharges(page, this.data.size);
      const items = (res.items || []).map((r) => ({
        ...r,
        amountYuan: formatYuan(r.amountCents),
        statusLabel: rechargeStatusLabel(r.status),
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
    } catch (e) {
      showError('加载充值记录失败', e);
    } finally {
      this.setData({ loading: false, loadingMore: false });
    }
  }
});
