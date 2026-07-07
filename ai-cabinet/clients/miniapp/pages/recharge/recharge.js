const api = require('../../utils/api');
const { showError } = require('../../utils/common');

Page({
  data: { amountYuan: '10', balance: 0, loading: false, quickAmounts: ['10', '20', '50', '100'] },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadBalance();
  },

  async loadBalance() {
    try {
      const acc = await api.getAccount();
      this.setData({ balance: (acc.balanceCents / 100).toFixed(2) });
    } catch (e) {
      showError('加载余额失败', e);
    }
  },

  onAmountInput(e) { this.setData({ amountYuan: e.detail.value }); },

  onQuickAmount(e) {
    this.setData({ amountYuan: e.currentTarget.dataset.amount });
  },

  async onRecharge() {
    const cents = Math.round(parseFloat(this.data.amountYuan) * 100);
    if (!cents || cents <= 0) return wx.showToast({ title: '请输入金额', icon: 'none' });
    if (this.data.loading) return;
    this.setData({ loading: true });
    try {
      wx.showLoading({ title: '创建订单' });
      const pay = await api.rechargePrepay(cents);
      wx.hideLoading();
      if (pay.debugInfo && pay.debugInfo.mode === 'mock') {
        await api.confirmRechargeMock(pay.debugInfo.orderId);
        wx.showToast({ title: '充值成功', icon: 'success' });
        this.loadBalance();
      } else {
        wx.requestPayment({
          timeStamp: pay.timeStamp,
          nonceStr: pay.nonceStr,
          package: pay.packageValue,
          signType: pay.signType,
          paySign: pay.paySign,
          success: () => {
            wx.showToast({ title: '支付成功', icon: 'success' });
            this.loadBalance();
          },
          fail: () => wx.showToast({ title: '支付取消', icon: 'none' })
        });
      }
    } catch (e) {
      wx.hideLoading();
      showError('充值失败', e);
    } finally {
      this.setData({ loading: false });
    }
  }
});
