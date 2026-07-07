const api = require('../../utils/api');
const { showError, formatDateTime } = require('../../utils/common');

const STATUS_LABEL = {
  OPEN: '审核中',
  RESOLVED: '已处理'
};

Page({
  data: {
    tickets: [],
    loading: false
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadTickets();
  },

  onPullDownRefresh() {
    this.loadTickets().finally(() => wx.stopPullDownRefresh());
  },

  async loadTickets() {
    if (this.data.loading) return;
    this.setData({ loading: true });
    try {
      const tickets = await api.listMyDisputes();
      this.setData({
        tickets: (tickets || []).map((t) => ({
          ...t,
          statusLabel: STATUS_LABEL[t.status] || t.status || '-',
          statusClass: t.status === 'RESOLVED' ? 'resolved' : 'open',
          createdAtText: formatDateTime(t.createdAt)
        }))
      });
    } catch (e) {
      showError('加载申诉进度失败', e);
    } finally {
      this.setData({ loading: false });
    }
  }
});
