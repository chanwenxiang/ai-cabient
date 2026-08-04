"use strict";
const common_vendor = require("../common/vendor.js");
const config_api = require("../config/api.js");
function getToken() {
  return common_vendor.index.getStorageSync("merchant_token") || "";
}
function clearSession() {
  common_vendor.index.removeStorageSync("merchant_token");
  common_vendor.index.removeStorageSync("merchant_user_id");
  common_vendor.index.removeStorageSync("merchant_me");
}
function request(path, method = "GET", data, auth = true) {
  return new Promise((resolve, reject) => {
    const header = { "Content-Type": "application/json" };
    if (auth && getToken())
      header.Authorization = "Bearer " + getToken();
    common_vendor.index.request({
      url: config_api.API_BASE_URL + path,
      method,
      data,
      header,
      success(res) {
        const body = res.data;
        if (res.statusCode === 401 || res.statusCode === 403) {
          clearSession();
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
        reject(new Error(err.errMsg || "网络错误"));
      }
    });
  });
}
function merchantLogin(phone, password) {
  return request(
    "/api/v2/auth/admin-password-login",
    "POST",
    { phoneNumber: phone, password },
    false
  ).then((data) => {
    common_vendor.index.setStorageSync("merchant_token", data.token);
    common_vendor.index.setStorageSync("merchant_user_id", data.userId);
    return data;
  });
}
const merchantApi = {
  me: () => request("/api/v2/merchant/me"),
  stats: () => request("/api/v2/merchant/stats"),
  trend: (days = 7) => request(`/api/v2/merchant/trend?days=${days}`),
  devices: () => request("/api/v2/merchant/devices"),
  deviceSettings: (id) => request(`/api/v2/merchant/devices/${encodeURIComponent(id)}/settings`),
  updateDeviceSettings: (id, body) => request(`/api/v2/merchant/devices/${encodeURIComponent(id)}/settings`, "PATCH", body),
  deviceSlots: (id) => request(`/api/v2/merchant/devices/${encodeURIComponent(id)}/slots`),
  upsertSlots: (id, body) => request(`/api/v2/merchant/devices/${encodeURIComponent(id)}/slots`, "PUT", body),
  pricing: (deviceId) => request(
    `/api/v2/merchant/pricing/skus${deviceId ? `?deviceId=${encodeURIComponent(deviceId)}` : ""}`
  ),
  updatePricing: (skuId, body) => request(`/api/v2/merchant/pricing/skus/${encodeURIComponent(skuId)}`, "PATCH", body),
  workbench: () => request("/api/v2/merchant/workbench"),
  exceptions: (status = "OPEN") => request(`/api/v2/merchant/exceptions?status=${encodeURIComponent(status)}`),
  resolveInventoryException: (id, resolution) => request(`/api/v2/merchant/exceptions/${encodeURIComponent(id)}/resolve`, "POST", { resolution }),
  analytics: (days = 30) => request(`/api/v2/merchant/analytics/overview?days=${days}`),
  settlements: () => request("/api/v2/merchant/settlements/overview"),
  skuSales: (days = 30) => request(`/api/v2/merchant/analytics/sku-sales?days=${days}`),
  replenishmentSuggestions: (deviceId) => request(`/api/v2/merchant/replenishment/suggestions?deviceId=${encodeURIComponent(deviceId)}`),
  replenishmentTasks: (status) => request(`/api/v2/merchant/replenishment/tasks${status ? `?status=${encodeURIComponent(status)}` : ""}`),
  replenishmentTaskLines: (taskId) => request(`/api/v2/merchant/replenishment/tasks/${taskId}/lines`),
  checkInReplenishmentTask: (taskId, body) => request(`/api/v2/merchant/replenishment/tasks/${taskId}/check-in`, "POST", body || {}),
  confirmReplenishmentLines: (taskId, lines) => request(`/api/v2/merchant/replenishment/tasks/${taskId}/lines`, "POST", { lines }),
  completeReplenishmentTask: (taskId) => request(`/api/v2/merchant/replenishment/tasks/${taskId}/complete`, "POST")
};
function hasPerm(me, code) {
  return ((me == null ? void 0 : me.permissions) || []).includes(code);
}
function alertTypeLabel(type) {
  return common_vendor.dictLabel("exception_type", type);
}
exports.alertTypeLabel = alertTypeLabel;
exports.clearSession = clearSession;
exports.hasPerm = hasPerm;
exports.merchantApi = merchantApi;
exports.merchantLogin = merchantLogin;
