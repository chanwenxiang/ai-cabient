/** API — 对接 trade-service v2，统一错误与 401 处理 */
const common = require('./common');
const config = require('./config');

const BASE_URL = config.BASE_URL;

const SESSION_TTL_MS = 30 * 60 * 1000;
const REFRESH_BEFORE_MS = 8 * 60 * 1000;

let tokenExpiresAt = wx.getStorageSync('token_expires') || 0;
let sessionTtlMs = SESSION_TTL_MS;
let lastActivityAt = Date.now();
let refreshInFlight = null;

function isDevBaseUrl() {
  return /localhost|127\.0\.0\.1|192\.168\.|10\.\d+\./.test(BASE_URL);
}

function getToken() {
  return wx.getStorageSync('token') || '';
}

function noteActivity() {
  lastActivityAt = Date.now();
}

function applyTokenSession(data) {
  wx.setStorageSync('token', data.token);
  wx.setStorageSync('userId', data.userId);
  sessionTtlMs = (data.expiresInSeconds || 1800) * 1000;
  tokenExpiresAt = Date.now() + sessionTtlMs;
  wx.setStorageSync('token_expires', tokenExpiresAt);
  if (data.serverBootEpoch != null) {
    wx.setStorageSync('server_boot', data.serverBootEpoch);
  }
  noteActivity();
}

function refreshTokenSilently() {
  if (!getToken()) return Promise.resolve(false);
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + '/api/v2/auth/refresh',
      method: 'POST',
      header: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + getToken()
      },
      success(res) {
        if (res.statusCode === 200 && res.data && res.data.code === 0) {
          applyTokenSession(res.data.data);
          resolve(true);
          return;
        }
        reject({
          status: res.statusCode,
          message: (res.data && res.data.message) || '登录已失效'
        });
      },
      fail(err) {
        reject({ message: (err && err.errMsg) || '网络错误' });
      }
    });
  }).finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

function maybeRefreshToken() {
  if (!getToken()) return Promise.resolve();
  if (Date.now() - lastActivityAt > sessionTtlMs) return Promise.resolve();
  if (tokenExpiresAt - Date.now() > REFRESH_BEFORE_MS) return Promise.resolve();
  return refreshTokenSilently().catch(() => {});
}

function request(path, method, data, auth = true, retried = false) {
  return new Promise((resolve, reject) => {
    const run = () => {
      const header = { 'Content-Type': 'application/json' };
      if (auth && getToken()) header['Authorization'] = 'Bearer ' + getToken();
      wx.request({
        url: BASE_URL + path,
        method,
        data,
        header,
        success(res) {
          if (res.statusCode === 401 && auth && !retried) {
            refreshTokenSilently()
              .then(() => request(path, method, data, auth, true).then(resolve).catch(reject))
              .catch((e) => {
                common.handleAuthError(e);
                reject(e);
              });
            return;
          }
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
    };

    if (auth && getToken()) {
      noteActivity();
      maybeRefreshToken().finally(run);
    } else {
      run();
    }
  });
}

module.exports = {
  BASE_URL,
  SUPPORT_PHONE: config.SUPPORT_PHONE,
  isDevBaseUrl,
  getToken,
  applyTokenSession,
  refreshSessionOnLaunch: () => refreshTokenSilently().catch(() => false),
  sendSmsCode: (phone) => request(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(phone)}`, 'POST', null, false),
  getServerBoot: () => request('/api/v2/auth/server-boot', 'GET', null, false),
  login: (phone, code) => request('/api/v2/auth/login', 'POST', { phoneNumber: phone, code }, false),
  passwordLogin: (phone, password) => request('/api/v2/auth/password-login', 'POST', { phoneNumber: phone, password }, false),
  wxLogin: (code, phoneNumber) => request('/api/v2/auth/wx-login', 'POST', { code, phoneNumber: phoneNumber || null }, false),
  getAccount: () => request('/api/v2/account', 'GET'),
  signPayScore: () => request('/api/v2/account/payscore/sign', 'POST'),
  signAlipayAgreement: () => request('/api/v2/account/alipay-agreement/sign', 'POST'),
  verifyIdentity: (realName, idCardLast4) => request('/api/v2/account/verify', 'POST', { realName, idCardLast4 }),
  getDeviceStatus: (deviceId) => request(`/api/v2/devices/${encodeURIComponent(deviceId)}/status`, 'GET'),
  listDeviceProducts: (deviceId) => request(`/api/v2/devices/${encodeURIComponent(deviceId)}/products`, 'GET'),
  listMyDisputes: () => request('/api/v2/disputes/mine', 'GET'),
  fileDispute: (sessionId, reason) => request('/api/v2/disputes', 'POST', { sessionId, reason }),
  createSession: (deviceId) => request('/api/v2/sessions', 'POST', { deviceId }),
  getSession: (id) => request(`/api/v2/sessions/${id}`, 'GET'),
  getOrder: (id) => request(`/api/v2/sessions/${id}/order`, 'GET'),
  listOrders: (page = 0, size = 20) => request(`/api/v2/orders?page=${page}&size=${size}`, 'GET'),
  getOrderById: (orderId) => request(`/api/v2/orders/${orderId}`, 'GET'),
  listRecharges: (page = 0, size = 20) => request(`/api/v2/payment/recharges?page=${page}&size=${size}`, 'GET'),
  rechargePrepay: (amountCents, channel = 'WECHAT') =>
    request('/api/v2/payment/recharge/prepay', 'POST', { channel, amountCents }),
  confirmRechargeMock: (orderId, channel = 'WECHAT') => {
    const path = channel === 'ALIPAY'
      ? `/api/v2/payment/alipay/notify/mock/${orderId}`
      : `/api/v2/payment/wechat/notify/mock/${orderId}`;
    return request(path, 'POST', null, false);
  },
  opsOpenDoor: (deviceId, taskId) =>
    request('/api/v2/ops/restock/open-door', 'POST', { deviceId, taskId }),
  listReplenishmentTasks: () => request('/api/v2/ops/admin/replenishment/my-tasks', 'GET'),
  listWarehouseInTransit: (deviceId) => {
    const q = deviceId ? `?deviceId=${encodeURIComponent(deviceId)}` : '';
    return request(`/api/v2/ops/admin/warehouse/in-transit${q}`, 'GET');
  },
  listReplenishmentTaskLines: (taskId) => request(`/api/v2/ops/admin/replenishment/tasks/${taskId}/lines`, 'GET'),
  submitReplenishmentLines: (taskId, lines) =>
    request(`/api/v2/ops/admin/replenishment/tasks/${taskId}/lines`, 'POST', { lines }),
  completeReplenishmentTask: (taskId) => request(`/api/v2/ops/admin/replenishment/tasks/${taskId}/complete`, 'POST'),
  checkInReplenishmentTask: (taskId, latitude, longitude) =>
    request(`/api/v2/ops/admin/replenishment/tasks/${taskId}/check-in`, 'POST', {
      latitude,
      longitude
    }),
  listOpsSkus: () => request('/api/v2/ops/skus', 'GET'),
  listDeviceSlots: (deviceId) =>
    request(`/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/slots`, 'GET'),
  listDisputes: () => request('/api/v2/ops/disputes', 'GET'),
  resolveDispute: (ticketId, payload) =>
    request(`/api/v2/ops/disputes/${ticketId}/resolve`, 'POST', payload),
  /** 运营端：上传商品图识别预览（不创建会话、不扣款） */
  uploadOpsRecognitionPreview(filePath) {
    return new Promise((resolve, reject) => {
      if (!getToken()) {
        reject({ message: '请先登录' });
        return;
      }
      wx.uploadFile({
        url: BASE_URL + '/api/v2/ops/recognition-preview',
        filePath,
        name: 'image',
        header: { Authorization: 'Bearer ' + getToken() },
        success(res) {
          let body = res.data;
          if (typeof body === 'string') {
            try {
              body = JSON.parse(body);
            } catch (e) {
              reject({ message: '响应解析失败' });
              return;
            }
          }
          if (res.statusCode >= 200 && res.statusCode < 300 && body && body.code === 0) {
            resolve(body.data);
            return;
          }
          reject({ message: (body && body.message) || `识别失败 (${res.statusCode})` });
        },
        fail(err) {
          reject({ message: (err && err.errMsg) || '上传失败' });
        }
      });
    });
  }
};
