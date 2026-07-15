const api = require('./utils/api');
const config = require('./utils/config');
const { clearAuth } = require('./utils/common');

App({
  globalData: {
    baseUrl: config.BASE_URL,
    supportPhone: config.SUPPORT_PHONE
  },

  onLaunch() {
    const token = wx.getStorageSync('token');
    if (!token) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    api.getServerBoot().then((boot) => {
      const saved = wx.getStorageSync('server_boot');
      if (!saved || String(saved) !== String(boot)) {
        clearAuth();
        wx.reLaunch({ url: '/pages/login/login' });
        return;
      }
      if (boot != null) {
        wx.setStorageSync('server_boot', boot);
      }
      return api.refreshSessionOnLaunch();
    }).catch(() => { /* 网络异常时保留 token，由后续请求 401 处理 */ });
  },

  clearAuth
});
