const api = require('../../utils/api');
const { showError } = require('../../utils/common');

Page({
  data: {
    realName: '',
    idCardLast4: '',
    loading: false,
    pageLoading: true,
    verified: false,
    isDev: false
  },

  onLoad() {
    this.setData({ isDev: api.isDevBaseUrl() });
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadAccount();
  },

  async loadAccount() {
    this.setData({ pageLoading: true });
    try {
      const account = await api.getAccount();
      this.setData({ verified: !!(account && account.verified) });
    } catch (e) {
      showError('加载账户失败', e);
    } finally {
      this.setData({ pageLoading: false });
    }
  },

  onNameInput(e) {
    this.setData({ realName: e.detail.value.trim() });
  },

  onIdInput(e) {
    this.setData({ idCardLast4: e.detail.value.trim() });
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' });
  },

  goMine() {
    wx.switchTab({ url: '/pages/mine/mine' });
  },

  async onSubmit() {
    const { realName, idCardLast4, loading, verified } = this.data;
    if (loading || verified) return;
    if (!realName || realName.length < 2) {
      return wx.showToast({ title: '请输入真实姓名', icon: 'none' });
    }
    if (!/^\d{4}$/.test(idCardLast4)) {
      return wx.showToast({ title: '请输入身份证后4位', icon: 'none' });
    }
    this.setData({ loading: true });
    try {
      await api.verifyIdentity(realName, idCardLast4);
      this.setData({ verified: true, realName: '', idCardLast4: '' });
      wx.showToast({ title: '认证成功', icon: 'success' });
    } catch (e) {
      showError('认证失败', e);
    } finally {
      this.setData({ loading: false });
    }
  }
});
