/** API — 对接 trade-service v2，统一错误与 401 处理 */
const common = require('./common');

/** 本机开发；真机预览改为局域网 IP，如 http://192.168.1.10:8080 */
const BASE_URL = 'http://localhost:8080';

function isDevBaseUrl() {
  return /localhost|127\.0\.0\.1|192\.168\.|10\.\d+\./.test(BASE_URL);
}

function getToken() {
  return wx.getStorageSync('token') || '';
}

function request(path, method, data, auth = true) {
  return new Promise((resolve, reject) => {
    const header = { 'Content-Type': 'application/json' };
    if (auth && getToken()) header['Authorization'] = 'Bearer ' + getToken();
    wx.request({
      url: BASE_URL + path,
      method,
      data,
      header,
      success(res) {
        if (res.statusCode === 401 || res.statusCode === 403) {
          const err = {
            status: res.statusCode,
            message: (res.data && res.data.message) || '登录已失效'
          };
          common.handleAuthError(err);
          return reject(err);
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && res.data && res.data.code === 0) {
          resolve(res.data.data);
          return;
        }
        reject({
          status: res.statusCode,
          code: res.data && res.data.code,
          message: (res.data && res.data.message) || `请求失败 (${res.statusCode})`
        });
      },
      fail(err) {
        reject({
          message: (err && err.errMsg) || '网络错误，请检查服务是否启动及 BASE_URL 配置'
        });
      }
    });
  });
}

module.exports = {
  BASE_URL,
  isDevBaseUrl,
  getToken,
  sendSmsCode: (phone) => request(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(phone)}`, 'POST', null, false),
  login: (phone, code) => request('/api/v2/auth/login', 'POST', { phoneNumber: phone, code }, false),
  wxLogin: (code, phoneNumber) => request('/api/v2/auth/wx-login', 'POST', { code, phoneNumber: phoneNumber || null }, false),
  getAccount: () => request('/api/v2/account', 'GET'),
  getDeviceStatus: (deviceId) => request(`/api/v2/devices/${encodeURIComponent(deviceId)}/status`, 'GET'),
  listMyDisputes: () => request('/api/v2/disputes/mine', 'GET'),
  createSession: (deviceId) => request('/api/v2/sessions', 'POST', { deviceId }),
  getSession: (id) => request(`/api/v2/sessions/${id}`, 'GET'),
  getOrder: (id) => request(`/api/v2/sessions/${id}/order`, 'GET'),
  listOrders: (page = 0, size = 20) => request(`/api/v2/orders?page=${page}&size=${size}`, 'GET'),
  getOrderById: (orderId) => request(`/api/v2/orders/${orderId}`, 'GET'),
  listRecharges: (page = 0, size = 20) => request(`/api/v2/payment/recharges?page=${page}&size=${size}`, 'GET'),
  rechargePrepay: (amountCents) => request('/api/v2/payment/recharge/prepay', 'POST', { channel: 'WECHAT', amountCents }),
  confirmRechargeMock: (orderId) => request(`/api/v2/payment/wechat/notify/mock/${orderId}`, 'POST', null, false),
  opsOpenDoor: (deviceId) => request('/api/v2/ops/restock/open-door', 'POST', { deviceId }),
  listDisputes: () => request('/api/v2/ops/disputes', 'GET'),
  resolveDispute: (ticketId, items) => request(`/api/v2/ops/disputes/${ticketId}/resolve`, 'POST', { items })
};
