const api = require('../../utils/api');
const { sessionStateLabel, sessionStateHint, sessionStateTone, showError, formatError } = require('../../utils/common');

Page({
  data: {
    deviceId: 'CAB-001',
    deviceOnline: null,
    deviceStatusText: '',
    sessionId: '',
    state: '',
    stateLabel: '',
    stateHint: '',
    stateTone: 'idle',
    balance: '',
    opening: false,
    polling: false
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadBalance();
    if (this.data.deviceId) {
      this.refreshDeviceStatus(this.data.deviceId);
    }
    if (!this.data.polling && !this.data.opening) {
      this.resetSessionIfDone();
    }
  },

  onUnload() {
    this.stopPolling();
  },

  onPullDownRefresh() {
    Promise.all([
      this.loadBalance(),
      this.data.sessionId && this.data.polling
        ? api.getSession(this.data.sessionId).then((s) => this.setData(this.sessionView(s)))
        : Promise.resolve()
    ]).finally(() => wx.stopPullDownRefresh());
  },

  async loadBalance() {
    try {
      const acc = await api.getAccount();
      this.setData({ balance: (acc.balanceCents / 100).toFixed(2) });
    } catch (e) {
      /* 首页余额非关键路径 */
    }
  },

  onScan() {
    wx.scanCode({
      success: (res) => {
        const deviceId = (res.result || '').trim() || this.data.deviceId;
        this.setData({ deviceId });
        this.onScanOpen();
      },
      fail: () => wx.showToast({ title: '扫码取消', icon: 'none' })
    });
  },

  onDeviceInput(e) {
    const deviceId = e.detail.value.trim();
    this.setData({ deviceId, deviceOnline: null, deviceStatusText: '' });
    if (deviceId.length >= 3) {
      this.refreshDeviceStatus(deviceId);
    }
  },

  async refreshDeviceStatus(deviceId) {
    const id = (deviceId || this.data.deviceId).trim();
    if (!id) return;
    try {
      const status = await api.getDeviceStatus(id);
      const online = !!status.online;
      let text = online ? '设备在线，可以开门' : '设备离线，开门可能失败';
      if (online && !status.available) {
        text = '设备使用中，请稍后再试';
      }
      this.setData({ deviceOnline: online, deviceStatusText: text });
    } catch (e) {
      this.setData({ deviceOnline: null, deviceStatusText: '' });
    }
  },

  async onScanOpen() {
    if (this.data.opening || this.data.polling) return;
    const deviceId = this.data.deviceId.trim();
    if (!deviceId) {
      wx.showToast({ title: '请输入设备 ID', icon: 'none' });
      return;
    }
    this.setData({ opening: true });
    try {
      const status = await api.getDeviceStatus(deviceId);
      if (!status.online) {
        this.setData({ opening: false });
        const proceed = await new Promise((resolve) => {
          wx.showModal({
            title: '设备离线',
            content: '当前设备未联网，继续开门可能失败。是否仍要尝试？',
            confirmText: '仍要开门',
            cancelText: '取消',
            success: (res) => resolve(!!res.confirm)
          });
        });
        if (!proceed) return;
        this.setData({ opening: true });
      } else if (!status.available) {
        this.setData({ opening: false });
        wx.showModal({
          title: '设备使用中',
          content: '该柜机正在使用中，请稍后再试。',
          showCancel: false
        });
        return;
      }
      wx.showLoading({ title: '开门中...' });
      const session = await api.createSession(deviceId);
      wx.hideLoading();
      this.setData(this.sessionView(session));
      wx.showToast({ title: '指令已下发', icon: 'success' });
      this.startPolling(session.sessionId);
    } catch (e) {
      wx.hideLoading();
      this.setData({ opening: false });
      const msg = formatError(e);
      if (/余额|balance/i.test(msg)) {
        wx.showModal({
          title: '开门失败',
          content: msg,
          confirmText: '去充值',
          cancelText: '关闭',
          success: (res) => {
            if (res.confirm) wx.navigateTo({ url: '/pages/recharge/recharge' });
          }
        });
      } else {
        showError('开门失败', e);
      }
    }
  },

  stopPolling() {
    if (this._pollTimer) {
      clearInterval(this._pollTimer);
      this._pollTimer = null;
    }
    this.setData({ polling: false });
  },

  startPolling(sessionId) {
    this.stopPolling();
    this.setData({ polling: true });
    this._pollTimer = setInterval(async () => {
      try {
        const session = await api.getSession(sessionId);
        this.setData(this.sessionView(session));
        if (session.state === 'COMPLETED') {
          this.stopPolling();
          wx.navigateTo({ url: `/pages/result/result?sessionId=${sessionId}` });
        } else if (session.state === 'DISPUTED') {
          this.stopPolling();
          wx.showModal({
            title: '订单待审核',
            content: '识别结果需人工确认，运营审核通过后将自动扣款。您可在「我的 → 申诉进度」查看处理状态。',
            confirmText: '知道了',
            showCancel: false,
            success: () => this.resetSessionIfDone()
          });
        } else if (session.state === 'FAILED' || session.state === 'CANCELLED') {
          this.stopPolling();
          this.resetSessionIfDone();
          wx.showModal({
            title: '购物未完成',
            content: sessionStateHint(session.state) || sessionStateLabel(session.state),
            confirmText: '知道了',
            showCancel: false
          });
        }
      } catch (e) {
        console.error('poll session failed', e);
      }
    }, 2000);
  },

  resetSessionIfDone() {
    const terminal = ['COMPLETED', 'FAILED', 'CANCELLED', 'DISPUTED'];
    if (this.data.sessionId && terminal.includes(this.data.state)) {
      this.setData({
        sessionId: '',
        state: '',
        stateLabel: '',
        stateHint: '',
        stateTone: 'idle',
        opening: false
      });
    }
  },

  sessionView(session) {
    const state = session.state;
    return {
      sessionId: session.sessionId,
      state,
      stateLabel: sessionStateLabel(state),
      stateHint: sessionStateHint(state),
      stateTone: sessionStateTone(state)
    };
  }
});
