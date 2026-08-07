"use strict";
const common_vendor = require("../common/vendor.js");
const config_api = require("../config/api.js");
const BASE_URL = config_api.API_BASE_URL;
function formatRequestError(errMsg, path) {
  const raw = errMsg || "网络错误";
  if (raw === "request:fail" || raw.includes("request:fail")) {
    return "网络不太稳定，请稍后再试。开发调试时可在微信开发者工具勾选「不校验合法域名」";
  }
  return common_vendor.localizeApiMessage(raw);
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
  common_vendor.clearDictOverrides();
}
function randomId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}
function getOrCreateOpenAttempt(deviceId) {
  const normalized = deviceId.trim().toUpperCase();
  const saved = common_vendor.index.getStorageSync(OPEN_ATTEMPT_KEY);
  if (saved && saved.deviceId === normalized && saved.idempotencyKey) return saved;
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
  void "./dict-runtime.js".then((m) => m.loadRuntimeDict());
}
async function refreshTokenSilently() {
  if (!getConsumerToken()) return false;
  if (refreshInFlight) return refreshInFlight;
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
        reject(new Error(formatRequestError(err.errMsg)));
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
function request(path, method = "GET", data, auth = true, retried = false) {
  return new Promise((resolve, reject) => {
    const header = { "Content-Type": "application/json" };
    if (auth && getConsumerToken()) header.Authorization = "Bearer " + getConsumerToken();
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
          reject(new Error(common_vendor.localizeApiMessage(body == null ? void 0 : body.message, "登录已失效")));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && (body == null ? void 0 : body.code) === 0) {
          resolve(body.data);
          return;
        }
        const err = new Error(
          common_vendor.localizeApiMessage(body == null ? void 0 : body.message, `请求失败 (${res.statusCode})`)
        );
        err.status = res.statusCode;
        reject(err);
      },
      fail(err) {
        reject(new Error(formatRequestError(err.errMsg)));
      }
    });
  });
}
function uploadDisputeEvidenceFile(filePath) {
  return new Promise((resolve, reject) => {
    if (!getConsumerToken()) {
      reject(new Error("请先登录"));
      return;
    }
    common_vendor.index.uploadFile({
      url: BASE_URL + "/api/v2/disputes/evidence",
      filePath,
      name: "file",
      header: { Authorization: "Bearer " + getConsumerToken() },
      timeout: 3e4,
      success(res) {
        try {
          const body = JSON.parse(String(res.data || "{}"));
          if (res.statusCode >= 200 && res.statusCode < 300 && (body == null ? void 0 : body.code) === 0 && body.data) {
            resolve(body.data);
            return;
          }
          reject(new Error(common_vendor.localizeApiMessage(body == null ? void 0 : body.message, `上传失败 (${res.statusCode})`)));
        } catch {
          reject(new Error("上传响应解析失败"));
        }
      },
      fail(err) {
        reject(new Error(formatRequestError(err.errMsg)));
      }
    });
  });
}
async function bootstrapConsumerSession() {
  if (!getConsumerToken()) return false;
  let bootEpoch;
  try {
    const boot = await request("/api/v2/auth/server-boot", "GET", void 0, false);
    bootEpoch = boot.serverBootEpoch;
  } catch {
    return !!getConsumerToken();
  }
  const saved = common_vendor.index.getStorageSync("consumer_server_boot");
  if (saved !== "" && saved != null && bootEpoch != null && String(saved) !== String(bootEpoch)) {
    clearConsumerSession();
    return false;
  }
  try {
    const ok = await refreshTokenSilently();
    if (ok && bootEpoch != null) {
      common_vendor.index.setStorageSync("consumer_server_boot", bootEpoch);
    }
    return ok;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const authFail = msg.includes("登录已失效") || /401|403/.test(msg);
    if (authFail) {
      clearConsumerSession();
      return false;
    }
    return !!getConsumerToken();
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
        if (res.code) resolve(res.code);
        else reject(new Error("微信授权失败"));
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
    if (ok) return true;
  }
  try {
    const code = await wxLoginCode();
    await consumerWxLogin(code);
    return true;
  } catch {
    return false;
  }
}
function currentPagePath() {
  try {
    const pages = getCurrentPages();
    const cur = pages[pages.length - 1];
    if (!(cur == null ? void 0 : cur.route)) return "/pages/index/index";
    const base = "/" + cur.route;
    const opts = cur.options || {};
    const qs = Object.keys(opts).filter((k) => opts[k] != null && opts[k] !== "").map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(String(opts[k]))}`).join("&");
    return qs ? `${base}?${qs}` : base;
  } catch {
    return "/pages/index/index";
  }
}
function requireConsumerAuth(message = "请先完成微信授权", redirect) {
  return ensureConsumerAuth().then((ok) => {
    if (!ok) {
      const target = redirect || currentPagePath();
      common_vendor.index.showModal({
        title: "需要授权",
        content: message,
        confirmText: "去验证",
        success(res) {
          if (res.confirm) {
            common_vendor.index.navigateTo({
              url: "/pages/login/login?redirect=" + encodeURIComponent(target)
            });
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
  cancelRecharge: (orderId) => request(
    `/api/v2/payment/recharge/${encodeURIComponent(orderId)}/cancel`,
    "POST"
  ),
  balanceTransactions: (page = 0, size = 20) => request(
    `/api/v2/account/transactions?page=${page}&size=${size}`
  ),
  verifyIdentity: (body) => request("/api/v2/account/verify", "POST", body),
  signPayScore: () => request("/api/v2/account/payscore/sign", "POST"),
  signAlipayAgreement: () => request("/api/v2/account/alipay-agreement/sign", "POST"),
  deviceStatus: (deviceId) => request(
    `/api/v2/devices/${encodeURIComponent(deviceId)}/status`
  ),
  deviceProducts: (deviceId) => request(
    `/api/v2/devices/${encodeURIComponent(deviceId)}/products`
  ),
  createSession: async (deviceId, entryChannel) => {
    const attempt = getOrCreateOpenAttempt(deviceId);
    const body = {
      deviceId: attempt.deviceId,
      idempotencyKey: attempt.idempotencyKey
    };
    const channel = String(entryChannel || "").trim().toUpperCase();
    if (channel === "WECHAT" || channel === "ALIPAY") {
      body.entryChannel = channel;
    }
    try {
      return await request("/api/v2/sessions", "POST", body);
    } catch (firstError) {
      await new Promise((resolve) => setTimeout(resolve, 600));
      try {
        return await request("/api/v2/sessions", "POST", body);
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
  getMyDispute: (opts) => {
    const q = [
      opts.ticketId ? `ticketId=${encodeURIComponent(opts.ticketId)}` : "",
      opts.sessionId ? `sessionId=${encodeURIComponent(opts.sessionId)}` : ""
    ].filter(Boolean).join("&");
    return request(
      `/api/v2/disputes/mine/detail${q ? `?${q}` : ""}`
    );
  },
  uploadDisputeEvidence: (filePath) => uploadDisputeEvidenceFile(filePath),
  refundOrder: (orderId, body) => request(
    `/api/v2/orders/${encodeURIComponent(orderId)}/refund`,
    "POST",
    body
  ),
  consumerPublicConfig: () => request("/api/v2/public/consumer-config", "GET", null, false),
  reportDeviceFault: (deviceId, body) => request(
    `/api/v2/devices/${encodeURIComponent(deviceId)}/fault-report`,
    "POST",
    body
  ),
  submitFeedback: (body) => request("/api/v2/feedback", "POST", body),
  listMyFeedback: () => request("/api/v2/feedback/mine"),
  memberProfile: () => request("/api/v2/member/profile"),
  marketingBanners: () => request("/api/v2/marketing/banners", "GET", void 0, false),
  marketingCampaigns: () => request("/api/v2/marketing/campaigns/active", "GET", void 0, false),
  claimCampaign: (activityId) => request(`/api/v2/marketing/campaigns/${activityId}/claim`, "POST"),
  myCoupons: (status) => request(status ? `/api/v2/coupons?status=${encodeURIComponent(status)}` : "/api/v2/coupons"),
  couponCount: () => request("/api/v2/coupons/count"),
  listAnnouncements: () => request(
    "/api/v2/announcements",
    "GET",
    void 0,
    false
  ),
  getAnnouncement: (id) => request(
    `/api/v2/announcements/${id}`,
    "GET",
    void 0,
    false
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
exports.request = request;
exports.requireConsumerAuth = requireConsumerAuth;
exports.sendSmsCode = sendSmsCode;
