const api = require('../../utils/api');
const { clearAuth, showError, formatYuan } = require('../../utils/common');

Page({
  data: {
    account: null,
    balanceYuan: '',
    phoneMasked: '',
    loading: false
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadAccount();
  },

  onPullDownRefresh() {
    this.loadAccount().finally(() => wx.stopPullDownRefresh());
  },

  async loadAccount() {
    if (this.data.loading) return;
    this.setData({ loading: true });
    try {
      const account = await api.getAccount();
      this.setData({
        account,
        balanceYuan: formatYuan(account.balanceCents),
        phoneMasked: maskPhone(account.phoneNumber)
      });
    } catch (e) {
      showError('加载账户失败', e);
    } finally {
      this.setData({ loading: false });
    }
  },

  goRecharge() {
    wx.navigateTo({ url: '/pages/recharge/recharge' });
  },

  goOrders() {
    wx.navigateTo({ url: '/pages/orders/orders' });
  },

  goRecharges() {
    wx.navigateTo({ url: '/pages/recharges/recharges' });
  },

  goDisputeMine() {
    wx.navigateTo({ url: '/pages/dispute-mine/dispute-mine' });
  },

  goOps() {
    wx.navigateTo({ url: '/pages/ops/ops' });
  },

  goDisputes() {
    wx.navigateTo({ url: '/pages/disputes/disputes' });
  },

  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '确定要退出当前账号吗？',
      success: (res) => {
        if (res.confirm) clearAuth();
      }
    });
  }
});

function maskPhone(phone) {
  if (!phone || phone.length < 7) return phone || '-';
  return phone.slice(0, 3) + '****' + phone.slice(-4);
}
