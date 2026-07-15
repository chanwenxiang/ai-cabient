"use strict";
const common_vendor = require("../common/vendor.js");
const config_api = require("../config/api.js");
const BASE_URL = config_api.API_BASE_URL;
function formatRequestError(errMsg, path) {
  const raw = errMsg || "网络错误";
  if (raw === "request:fail" || raw.includes("request:fail")) {
    return `无法连接服务器 ${BASE_URL}${path}。请确认 trade-service 已启动，并在微信开发者工具勾选「不校验合法域名」`;
  }
  return raw;
}
const TOKEN_KEY = "consumer_token";
const USER_KEY = "consumer_user_id";
const EXPIRES_KEY = "consumer_token_expires";
const OPEN_ATTEMPT_KEY = "consumer_open_attempt";
const REQUEST_TIMEOUT_MS = 12e3;
let refreshInFlight = null;
function getConsumerToken() {
  return common_vendor.index.getStorageSync(TOKEN_KEY) || "";
}
function clearConsumerSession() {
  common_vendor.index.removeStorageSync(TOKEN_KEY);
  common_vendor.index.removeStorageSync(USER_KEY);
  common_vendor.index.removeStorageSync(EXPIRES_KEY);
  common_vendor.index.removeStorageSync("consumer_server_boot");
  common_vendor.index.removeStorageSync("active_session_id");
  common_vendor.index.removeStorageSync(OPEN_ATTEMPT_KEY);
}
function randomId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}
function getOrCreateOpenAttempt(deviceId) {
  const normalized = deviceId.trim().toUpperCase();
  const saved = common_vendor.index.getStorageSync(OPEN_ATTEMPT_KEY);
  if (saved && saved.deviceId === normalized && saved.idempotencyKey)
    return saved;
  const attempt = { deviceId: normalized, idempotencyKey: `consumer-open-${randomId()}`, createdAt: Date.now() };
  common_vendor.index.setStorageSync(OPEN_ATTEMPT_KEY, attempt);
  return attempt;
}
function clearOpenAttempt() {
  common_vendor.index.removeStorageSync(OPEN_ATTEMPT_KEY);
}
function applyTokenSession(data) {
  common_vendor.index.setStorageSync(TOKEN_KEY, data.token);
  common_vendor.index.setStorageSync(USER_KEY, data.userId);
  const ms = (data.expiresInSeconds ?? 1800) * 1e3;
  common_vendor.index.setStorageSync(EXPIRES_KEY, String(Date.now() + ms));
  if (data.serverBootEpoch != null) {
    common_vendor.index.setStorageSync("consumer_server_boot", data.serverBootEpoch);
  }
}
async function refreshTokenSilently() {
  if (!getConsumerToken())
    return false;
  if (refreshInFlight)
    return refreshInFlight;
  const pending = new Promise((resolve, reject) => {
    common_vendor.index.request({
      url: BASE_URL + "/api/v2/auth/refresh",
      method: "POST",
      header: { Authorization: "Bearer " + getConsumerToken(), "Content-Type": "application/json" },
      success(res) {
        const body = res.data;
        if (res.statusCode === 200 && (body == null ? void 0 : body.code) === 0 && body.data) {
          applyTokenSession(body.data);
          resolve(true);
          return;
        }
        reject(new Error("登录已失效"));
      },
      fail(err) {
        reject(new Error(formatRequestError(err.errMsg, "/api/v2/auth/refresh")));
      }
    });
  }).finally(() => {
    refreshInFlight = null;
  });
  refreshInFlight = pending;
  return pending;
}
async function get(path, auth = true) {
  return { data: await request(path, "GET", void 0, auth) };
}
async function post(path, data, auth = true) {
  return { data: await request(path, "POST", data, auth) };
}
function request(path, method = "GET", data, auth = true, retried = false) {
  return new Promise((resolve, reject) => {
    const header = { "Content-Type": "application/json" };
    if (auth && getConsumerToken())
      header.Authorization = "Bearer " + getConsumerToken();
    common_vendor.index.request({
      url: BASE_URL + path,
      method,
      data,
      header,
      timeout: REQUEST_TIMEOUT_MS,
      async success(res) {
        const body = res.data;
        if (res.statusCode === 401 && auth && !retried) {
          try {
            await refreshTokenSilently();
            resolve(await request(path, method, data, auth, true));
          } catch (e) {
            clearConsumerSession();
            reject(e);
          }
          return;
        }
        if (res.statusCode === 401 || res.statusCode === 403) {
          clearConsumerSession();
          reject(new Error((body == null ? void 0 : body.message) || "登录已失效"));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && (body == null ? void 0 : body.code) === 0) {
          resolve(body.data);
          return;
        }
        reject(new Error((body == null ? void 0 : body.message) || `请求失败 (${res.statusCode})`));
      },
      fail(err) {
        reject(new Error(formatRequestError(err.errMsg, path)));
      }
    });
  });
}
async function bootstrapConsumerSession() {
  if (!getConsumerToken())
    return false;
  try {
    const boot = await request("/api/v2/auth/server-boot", "GET", void 0, false);
    const saved = common_vendor.index.getStorageSync("consumer_server_boot");
    if (saved && boot.serverBootEpoch != null && String(saved) !== String(boot.serverBootEpoch)) {
      clearConsumerSession();
      return false;
    }
    return await refreshTokenSilently();
  } catch {
    clearConsumerSession();
    return false;
  }
}
function consumerPasswordLogin(phone, password) {
  return request("/api/v2/auth/password-login", "POST", { phoneNumber: phone, password }, false).then(
    (data) => {
      applyTokenSession(data);
      return data;
    }
  );
}
function consumerSmsLogin(phone, code) {
  return request("/api/v2/auth/login", "POST", { phoneNumber: phone, code }, false).then((data) => {
    applyTokenSession(data);
    return data;
  });
}
function consumerWxLogin(code, phoneNumber) {
  return request(
    "/api/v2/auth/wx-login",
    "POST",
    { code, phoneNumber: phoneNumber || void 0 },
    false
  ).then((data) => {
    applyTokenSession(data);
    return data;
  });
}
function wxLoginCode() {
  return new Promise((resolve, reject) => {
    common_vendor.index.login({
      provider: "weixin",
      success(res) {
        if (res.code)
          resolve(res.code);
        else
          reject(new Error("微信授权失败"));
      },
      fail(err) {
        reject(new Error(err.errMsg || "微信授权失败"));
      }
    });
  });
}
async function ensureConsumerAuth() {
  if (getConsumerToken()) {
    const ok = await bootstrapConsumerSession();
    if (ok)
      return true;
  }
  try {
    const code = await wxLoginCode();
    await consumerWxLogin(code);
    return true;
  } catch {
    return false;
  }
}
function requireConsumerAuth(message = "请先完成微信授权") {
  return ensureConsumerAuth().then((ok) => {
    if (!ok) {
      common_vendor.index.showModal({
        title: "需要授权",
        content: message,
        confirmText: "去验证",
        success(res) {
          if (res.confirm) {
            common_vendor.index.navigateTo({ url: "/pages/login/login" });
          }
        }
      });
    }
    return ok;
  });
}
function sendSmsCode(phone) {
  return request(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(phone)}`, "POST", null, false);
}
const consumerApi = {
  account: () => request("/api/v2/account"),
  createRechargePrepay: (channel, amountCents, idempotencyKey) => request("/api/v2/payment/recharge/prepay", "POST", {
    channel,
    amountCents,
    idempotencyKey
  }),
  getRechargeOrder: (orderId) => request(
    `/api/v2/payment/recharge/${encodeURIComponent(orderId)}`
  ),
  createMockRecharge: (amountCents, idempotencyKey) => request("/api/v2/payment/recharge/prepay", "POST", {
    channel: "WECHAT",
    amountCents,
    idempotencyKey
  }),
  confirmMockRecharge: (orderId) => request(
    `/api/v2/payment/recharge/${encodeURIComponent(orderId)}/mock-success`,
    "POST"
  ),
  balanceTransactions: (page = 0, size = 20) => request(
    `/api/v2/account/transactions?page=${page}&size=${size}`
  ),
  verifyIdentity: (body) => request("/api/v2/account/verify", "POST", body),
  signPayScore: () => request("/api/v2/account/payscore/sign", "POST"),
  deviceStatus: (deviceId) => request(
    `/api/v2/devices/${encodeURIComponent(deviceId)}/status`
  ),
  deviceProducts: (deviceId) => request(
    `/api/v2/devices/${encodeURIComponent(deviceId)}/products`
  ),
  createSession: async (deviceId) => {
    const attempt = getOrCreateOpenAttempt(deviceId);
    try {
      return await request("/api/v2/sessions", "POST", {
        deviceId: attempt.deviceId,
        idempotencyKey: attempt.idempotencyKey
      });
    } catch (firstError) {
      await new Promise((resolve) => setTimeout(resolve, 600));
      try {
        return await request("/api/v2/sessions", "POST", {
          deviceId: attempt.deviceId,
          idempotencyKey: attempt.idempotencyKey
        });
      } catch {
        throw firstError;
      }
    }
  },
  activeSession: () => request("/api/v2/sessions/active"),
  getSession: (sessionId) => request(`/api/v2/sessions/${sessionId}`),
  cancelSession: (sessionId) => request(`/api/v2/sessions/${sessionId}/cancel`, "POST"),
  updateSessionCart: (sessionId, body) => request(`/api/v2/sessions/${sessionId}/cart`, "PUT", body),
  getSessionOrder: (sessionId) => request(`/api/v2/sessions/${sessionId}/order`),
  listOrders: (page = 0, size = 20) => request(
    `/api/v2/orders?page=${page}&size=${size}`
  ),
  getOrder: (orderId) => request(`/api/v2/orders/${orderId}`),
  fileDispute: (body) => request("/api/v2/disputes", "POST", body),
  listMyDisputes: () => request("/api/v2/disputes/mine"),
  consumerPublicConfig: () => request("/api/v2/public/consumer-config", "GET", null, false),
  reportDeviceFault: (deviceId, body) => request(
    `/api/v2/devices/${encodeURIComponent(deviceId)}/fault-report`,
    "POST",
    body
  )
};
exports.clearConsumerSession = clearConsumerSession;
exports.clearOpenAttempt = clearOpenAttempt;
exports.consumerApi = consumerApi;
exports.consumerPasswordLogin = consumerPasswordLogin;
exports.consumerSmsLogin = consumerSmsLogin;
exports.consumerWxLogin = consumerWxLogin;
exports.ensureConsumerAuth = ensureConsumerAuth;
exports.get = get;
exports.getConsumerToken = getConsumerToken;
exports.post = post;
exports.requireConsumerAuth = requireConsumerAuth;
exports.sendSmsCode = sendSmsCode;
