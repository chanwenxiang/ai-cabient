const api = require('../../utils/api');
const { showError, formatDateTime } = require('../../utils/common');

Page({
  data: { tickets: [], loading: false, isOperator: false },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.checkAccess();
  },

  async checkAccess() {
    try {
      const account = await api.getAccount();
      if (!account.operator) {
        wx.showModal({
          title: '无权限',
          content: '争议审核需运营账号登录',
          showCancel: false,
          success: () => wx.navigateBack()
        });
        return;
      }
      this.setData({ isOperator: true });
      this.loadTickets();
    } catch (e) {
      showError('加载失败', e);
    }
  },

  onPullDownRefresh() {
    this.loadTickets().finally(() => wx.stopPullDownRefresh());
  },

  async loadTickets() {
    if (this.data.loading) return;
    this.setData({ loading: true });
    try {
      const list = await api.listDisputes();
      const tickets = (list || []).map((t) => ({
        ...t,
        createdAtText: formatDateTime(t.createdAt)
      }));
      this.setData({ tickets });
    } catch (e) {
      showError('加载失败', e);
    } finally {
      this.setData({ loading: false });
    }
  },

  async onResolve(e) {
    const ticketId = e.currentTarget.dataset.id;
    const sessionId = e.currentTarget.dataset.session;
    wx.showModal({
      title: '人工审核',
      content: `确认会话 ${sessionId} 购买 1 件演示商品并扣款？`,
      success: async (res) => {
        if (!res.confirm) return;
        try {
          wx.showLoading({ title: '处理中' });
          await api.resolveDispute(ticketId, [{ skuId: 'SKU-DEMO-001', quantity: 1 }]);
          wx.hideLoading();
          wx.showToast({ title: '已结案', icon: 'success' });
          this.loadTickets();
        } catch (err) {
          wx.hideLoading();
          showError('处理失败', err);
        }
      }
    });
  }
});
