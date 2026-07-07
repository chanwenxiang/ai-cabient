const api = require('../../utils/api');
const { showError } = require('../../utils/common');

Page({
  data: {
    phone: '',
    code: '',
    sending: false,
    logging: false,
    showDevHint: false,
    codeCooldown: 0
  },

  onLoad() {
    this.setData({ showDevHint: api.isDevBaseUrl() });
  },

  onUnload() {
    this._clearCodeTimer();
  },

  onPhoneInput(e) { this.setData({ phone: e.detail.value.replace(/\s/g, '') }); },
  onCodeInput(e) { this.setData({ code: e.detail.value.trim() }); },

  _clearCodeTimer() {
    if (this._codeTimer) {
      clearInterval(this._codeTimer);
      this._codeTimer = null;
    }
  },

  _startCodeCooldown(sec) {
    this._clearCodeTimer();
    this.setData({ codeCooldown: sec });
    this._codeTimer = setInterval(() => {
      const next = this.data.codeCooldown - 1;
      if (next <= 0) {
        this._clearCodeTimer();
        this.setData({ codeCooldown: 0 });
      } else {
        this.setData({ codeCooldown: next });
      }
    }, 1000);
  },

  async onSendCode() {
    const phone = this.data.phone.replace(/\s/g, '');
    if (!phone) return wx.showToast({ title: '请输入手机号', icon: 'none' });
    if (this.data.sending || this.data.codeCooldown > 0) return;
    this.setData({ sending: true });
    try {
      await api.sendSmsCode(phone);
      wx.showToast({ title: '验证码已发送', icon: 'success' });
      this._startCodeCooldown(60);
    } catch (e) {
      showError('发送失败', e);
    } finally {
      this.setData({ sending: false });
    }
  },

  async onLogin() {
    const phone = this.data.phone.replace(/\s/g, '');
    const code = this.data.code.trim();
    if (!phone || !code) return wx.showToast({ title: '请填写手机号和验证码', icon: 'none' });
    if (this.data.logging) return;
    this.setData({ logging: true });
    try {
      wx.showLoading({ title: '登录中' });
      const res = await api.login(phone, code);
      wx.setStorageSync('token', res.token);
      wx.setStorageSync('userId', res.userId);
      wx.hideLoading();
      wx.switchTab({ url: '/pages/index/index' });
    } catch (e) {
      wx.hideLoading();
      showError('登录失败', e);
    } finally {
      this.setData({ logging: false });
    }
  },

  onWxLogin() {
    wx.login({
      success: async (r) => {
        if (!r.code) return;
        try {
          wx.showLoading({ title: '微信登录' });
          const res = await api.wxLogin(r.code, this.data.phone.replace(/\s/g, '') || null);
          wx.setStorageSync('token', res.token);
          wx.setStorageSync('userId', res.userId);
          wx.hideLoading();
          wx.switchTab({ url: '/pages/index/index' });
        } catch (e) {
          wx.hideLoading();
          showError('微信登录失败', e);
        }
      }
    });
  }
});
