const api = require('../../utils/api');
const { clearAuth, showError, formatYuan } = require('../../utils/common');

Page({
  data: {
    account: null,
    balanceYuan: '',
    phoneMasked: '',
    loading: false,
    openDisputeCount: 0,
    supportPhone: ''
  },

  onLoad() {
    this.setData({ supportPhone: api.SUPPORT_PHONE || '400-888-0001' });
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadAccount(true);
    this.loadDisputeBadge(true);
  },

  onPullDownRefresh() {
    Promise.all([this.loadAccount(true), this.loadDisputeBadge(true)])
      .finally(() => wx.stopPullDownRefresh());
  },

  async loadDisputeBadge(force) {
    if (!force && this._disputeLoading) return;
    this._disputeLoading = true;
    try {
      const tickets = await api.listMyDisputes();
      const openDisputeCount = (tickets || []).filter((t) => t.status === 'OPEN').length;
      this.setData({ openDisputeCount });
    } catch (e) {
      /* 非关键 */
    } finally {
      this._disputeLoading = false;
    }
  },

  async loadAccount(force) {
    if (!force && this.data.loading) return;
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

  onSignPayScore() {
    wx.showActionSheet({
      itemList: ['微信支付分免密', '支付宝免密代扣'],
      success: async (res) => {
        try {
          wx.showLoading({ title: '开通中…' });
          const result = res.tapIndex === 1
            ? await api.signAlipayAgreement()
            : await api.signPayScore();
          wx.hideLoading();
          wx.showToast({ title: result.message || '已开通', icon: 'success' });
          this.loadAccount(true);
        } catch (e) {
          wx.hideLoading();
          showError('开通失败', e);
        }
      }
    });
  },

  goVerify() {
    wx.navigateTo({ url: '/pages/verify/verify' });
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
  },

  onCallSupport() {
    const phone = this.data.supportPhone || api.SUPPORT_PHONE;
    wx.showModal({
      title: '联系客服',
      content: `客服电话：${phone}\n（工作时间 9:00-18:00）`,
      confirmText: '拨打',
      success: (res) => {
        if (res.confirm) wx.makePhoneCall({ phoneNumber: phone.replace(/-/g, '') });
      }
    });
  }
});

function maskPhone(phone) {
  if (!phone || phone.length < 7) return phone || '-';
  return phone.slice(0, 3) + '****' + phone.slice(-4);
}
