const api = require('../../utils/api');
const { showError } = require('../../utils/common');

Page({
  data: {
    amountYuan: '10',
    balance: 0,
    loading: false,
    channel: 'WECHAT',
    quickAmounts: ['10', '20', '50', '100'],
    channels: [
      { id: 'WECHAT', label: '微信支付' },
      { id: 'ALIPAY', label: '支付宝' }
    ]
  },

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

  onChannelChange(e) {
    this.setData({ channel: e.currentTarget.dataset.channel });
  },

  async onRecharge() {
    const cents = Math.round(parseFloat(this.data.amountYuan) * 100);
    if (!cents || cents <= 0) return wx.showToast({ title: '请输入金额', icon: 'none' });
    if (this.data.loading) return;
    const channel = this.data.channel;
    this.setData({ loading: true });
    try {
      wx.showLoading({ title: '创建订单' });
      const pay = await api.rechargePrepay(cents, channel);
      wx.hideLoading();
      const debug = pay.debugInfo || {};
      const orderId = debug.orderId || pay.orderId;

      if (debug.mode === 'mock') {
        await api.confirmRechargeMock(orderId, channel);
        wx.showToast({ title: '充值成功', icon: 'success' });
        this.loadBalance();
        return;
      }

      if (channel === 'WECHAT' && pay.wxPay) {
        const wxPay = pay.wxPay;
        wx.requestPayment({
          timeStamp: wxPay.timeStamp,
          nonceStr: wxPay.nonceStr,
          package: wxPay.packageValue,
          signType: wxPay.signType,
          paySign: wxPay.paySign,
          success: () => {
            wx.showToast({ title: '支付成功', icon: 'success' });
            this.loadBalance();
          },
          fail: () => wx.showToast({ title: '支付取消', icon: 'none' })
        });
        return;
      }

      if (channel === 'ALIPAY' && pay.alipayPay && pay.alipayPay.payUrl) {
        wx.setClipboardData({
          data: pay.alipayPay.payUrl,
          success: () => {
            wx.showModal({
              title: '支付宝支付',
              content: '支付链接已复制。请在手机浏览器中粘贴打开，完成支付后返回小程序刷新余额。',
              confirmText: '知道了',
              showCancel: false
            });
          }
        });
        return;
      }

      wx.showToast({ title: '未获取到支付参数', icon: 'none' });
    } catch (e) {
      wx.hideLoading();
      showError('充值失败', e);
    } finally {
      this.setData({ loading: false });
    }
  }
});
