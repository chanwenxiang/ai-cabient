const { clearAuth } = require('./utils/common');

App({
  globalData: {
    /** 与 utils/api.js 中 BASE_URL 保持一致 */
    baseUrl: 'http://localhost:8080'
  },

  onLaunch() {
    const token = wx.getStorageSync('token');
    if (!token) {
      wx.reLaunch({ url: '/pages/login/login' });
    }
  },

  clearAuth
});
