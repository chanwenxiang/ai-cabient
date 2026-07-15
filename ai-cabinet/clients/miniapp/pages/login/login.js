const api = require('../../utils/api');
const { showError, normalizePhone, isValidPhone, invalidPhoneMessage } = require('../../utils/common');

Page({
  data: {
    loginMode: 'password',
    phone: '',
    password: '',
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

  onSwitchMode(e) {
    const mode = e.currentTarget.dataset.mode === 'sms' ? 'sms' : 'password';
    this.setData({ loginMode: mode });
  },

  onPhoneInput(e) {
    this.setData({ phone: normalizePhone(e.detail.value) });
  },
  onPasswordInput(e) { this.setData({ password: e.detail.value }); },
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

  _afterLogin(res) {
    api.applyTokenSession(res);
    wx.switchTab({ url: '/pages/index/index' });
  },

  _requireValidPhone(optional = false) {
    const phone = normalizePhone(this.data.phone);
    if (!phone) {
      if (optional) return '';
      wx.showToast({ title: '请输入手机号', icon: 'none' });
      return null;
    }
    if (!isValidPhone(phone)) {
      wx.showToast({ title: invalidPhoneMessage(), icon: 'none' });
      return null;
    }
    return phone;
  },

  async onSendCode() {
    const phone = this._requireValidPhone();
    if (!phone) return;
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

  async onPasswordLogin() {
    const phone = this._requireValidPhone();
    const password = this.data.password;
    if (!phone) return;
    if (!password) return wx.showToast({ title: '请输入密码', icon: 'none' });
    if (this.data.logging) return;
    this.setData({ logging: true });
    try {
      wx.showLoading({ title: '登录中' });
      const res = await api.passwordLogin(phone, password);
      wx.hideLoading();
      this._afterLogin(res);
    } catch (e) {
      wx.hideLoading();
      showError('登录失败', e);
    } finally {
      this.setData({ logging: false });
    }
  },

  async onLogin() {
    const phone = this._requireValidPhone();
    const code = this.data.code.trim();
    if (!phone) return;
    if (!code) return wx.showToast({ title: '请输入验证码', icon: 'none' });
    if (this.data.logging) return;
    this.setData({ logging: true });
    try {
      wx.showLoading({ title: '登录中' });
      const res = await api.login(phone, code);
      wx.hideLoading();
      this._afterLogin(res);
    } catch (e) {
      wx.hideLoading();
      showError('登录失败', e);
    } finally {
      this.setData({ logging: false });
    }
  },

  onWxLogin() {
    const phone = this._requireValidPhone(true);
    if (phone === null) return;
    wx.login({
      success: async (r) => {
        if (!r.code) return;
        try {
          wx.showLoading({ title: '微信登录' });
          const res = await api.wxLogin(r.code, phone || null);
          wx.hideLoading();
          this._afterLogin(res);
        } catch (e) {
          wx.hideLoading();
          if (!phone && (e.message || '').includes('绑定')) {
            wx.showModal({
              title: '需要绑定手机号',
              content: '请先在上方输入已注册的手机号，再点微信登录完成绑定。',
              showCancel: false
            });
          } else {
            showError('微信登录失败', e);
          }
        }
      }
    });
  }
});
