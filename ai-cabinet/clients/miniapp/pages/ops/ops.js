const api = require('../../utils/api');
const { showError } = require('../../utils/common');

Page({
  data: { deviceId: 'CAB-001', opening: false },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
  },

  onDeviceInput(e) { this.setData({ deviceId: e.detail.value.trim() }); },

  async onOpsOpen() {
    if (this.data.opening) return;
    const deviceId = this.data.deviceId.trim();
    if (!deviceId) return wx.showToast({ title: '请输入设备 ID', icon: 'none' });
    this.setData({ opening: true });
    try {
      wx.showLoading({ title: '开门中' });
      const session = await api.opsOpenDoor(deviceId);
      wx.hideLoading();
      wx.showModal({
        title: '补货门已开',
        content: `会话 ${session.sessionId}\n请在补货完成后手动关门`,
        showCancel: false
      });
    } catch (e) {
      wx.hideLoading();
      showError('开门失败', e);
    } finally {
      this.setData({ opening: false });
    }
  }
});
