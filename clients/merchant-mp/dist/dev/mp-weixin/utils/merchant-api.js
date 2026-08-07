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
  common_vendor.clearDictOverrides();
}
function handleUnauthorized(message) {
  var _a;
  clearSession();
  const pages = getCurrentPages();
  const route = ((_a = pages[pages.length - 1]) == null ? void 0 : _a.route) || "";
  if (!route.includes("login")) {
    common_vendor.index.reLaunch({ url: "/pages/login/login" });
  }
  return new Error(common_vendor.localizeApiMessage(message, "登录已失效，请重新登录"));
}
function downloadAuthedFile(url) {
  return new Promise((resolve, reject) => {
    const token = getToken();
    if (!token) {
      reject(new Error("请先登录"));
      return;
    }
    common_vendor.index.downloadFile({
      url,
      header: { Authorization: "Bearer " + token },
      timeout: 6e4,
      success(res) {
        if (res.statusCode === 401) {
          reject(handleUnauthorized());
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && res.tempFilePath) {
          resolve(res.tempFilePath);
          return;
        }
        reject(new Error(`下载失败 (${res.statusCode})`));
      },
      fail(err) {
        reject(new Error(err.errMsg || "下载失败"));
      }
    });
  });
}
function openExportedFile(tempFilePath, fileName = "export.xlsx") {
  return new Promise((resolve) => {
    common_vendor.index.openDocument({
      filePath: tempFilePath,
      showMenu: true,
      success() {
        resolve();
      },
      fail() {
        if (typeof document !== "undefined") {
          const a = document.createElement("a");
          a.href = tempFilePath;
          a.download = fileName;
          a.rel = "noopener";
          document.body.appendChild(a);
          a.click();
          a.remove();
          resolve();
          return;
        }
        common_vendor.index.showToast({ title: "文件已下载，请从文件管理打开", icon: "none" });
        resolve();
      }
    });
  });
}
function request(path, method = "GET", data, auth = true) {
  return new Promise((resolve, reject) => {
    const header = { "Content-Type": "application/json" };
    if (auth && getToken()) header.Authorization = "Bearer " + getToken();
    common_vendor.index.request({
      url: config_api.API_BASE_URL + path,
      method,
      data,
      header,
      timeout: 2e4,
      success(res) {
        const body = res.data;
        if (res.statusCode === 401) {
          reject(handleUnauthorized(body == null ? void 0 : body.message));
          return;
        }
        if (res.statusCode === 403) {
          reject(new Error(common_vendor.localizeApiMessage(body == null ? void 0 : body.message, "权限不足")));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && (body == null ? void 0 : body.code) === 0) {
          resolve(body.data);
          return;
        }
        reject(new Error(common_vendor.localizeApiMessage(body == null ? void 0 : body.message, `请求失败 (${res.statusCode})`)));
      },
      fail(err) {
        reject(new Error(common_vendor.localizeApiMessage(err.errMsg, "网络错误")));
      }
    });
  });
}
function merchantLogin(phone, password) {
  return request(
    "/api/v2/auth/merchant-password-login",
    "POST",
    { phoneNumber: phone, password },
    false
  ).then(async (data) => {
    common_vendor.index.setStorageSync("merchant_token", data.token);
    common_vendor.index.setStorageSync("merchant_user_id", data.userId);
    const { loadRuntimeDict } = await "./dict-runtime.js";
    await loadRuntimeDict();
    return data;
  });
}
function uploadReplenishmentEvidenceFile(taskId, filePath) {
  return new Promise((resolve, reject) => {
    if (!getToken()) {
      reject(new Error("请先登录"));
      return;
    }
    common_vendor.index.uploadFile({
      url: `${config_api.API_BASE_URL}/api/v2/merchant/replenishment/tasks/${taskId}/evidence`,
      filePath,
      name: "file",
      header: { Authorization: "Bearer " + getToken() },
      timeout: 3e4,
      success(res) {
        if (res.statusCode === 401) {
          reject(handleUnauthorized());
          return;
        }
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
        reject(new Error(err.errMsg || "网络错误"));
      }
    });
  });
}
function downloadReplenishmentEvidenceFile(taskId, fileId) {
  const url = `${config_api.API_BASE_URL}/api/v2/merchant/replenishment/tasks/${taskId}/evidence/${fileId}`;
  return downloadAuthedFile(url);
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
  updatePricing: (skuId, body) => request(
    `/api/v2/merchant/pricing/skus/${encodeURIComponent(skuId)}`,
    "PATCH",
    body
  ),
  workbench: () => request("/api/v2/merchant/workbench"),
  listAnnouncements: () => request("/api/v2/merchant/announcements"),
  getAnnouncement: (id) => request(`/api/v2/merchant/announcements/${id}`),
  teamUsers: () => request("/api/v2/merchant/team/users"),
  teamRoles: () => request("/api/v2/merchant/team/roles"),
  createTeamUser: (body) => request("/api/v2/merchant/team/users", "POST", body),
  updateTeamUser: (userId, body) => request(
    `/api/v2/merchant/team/users/${userId}`,
    "PATCH",
    body
  ),
  disableTeamUser: (userId) => request(
    `/api/v2/merchant/team/users/${userId}/disable`,
    "POST"
  ),
  enableTeamUser: (userId) => request(
    `/api/v2/merchant/team/users/${userId}/enable`,
    "POST"
  ),
  resetTeamUserPassword: (userId, password) => request(
    `/api/v2/merchant/team/users/${userId}/reset-password`,
    "POST",
    { password }
  ),
  notifyPrefs: () => request("/api/v2/merchant/notify/prefs"),
  notifyWxBind: (code) => request("/api/v2/merchant/notify/wx-bind", "POST", {
    code
  }),
  notifySubscribe: (alertTypes) => request("/api/v2/merchant/notify/subscribe", "POST", {
    alertTypes
  }),
  exceptions: (status = "OPEN", page = 0, size = 100) => request(
    `/api/v2/merchant/exceptions?status=${encodeURIComponent(status)}&page=${page}&size=${size}`
  ),
  /** OPEN + PROCESSING；按页拉满，返回去重后的 items 与合计 total */
  openExceptions: async (pageSize = 100) => {
    const size = Math.min(Math.max(pageSize, 1), 100);
    const mergePages = async (status) => {
      const first = await merchantApi.exceptions(status, 0, size);
      const items = [...first.items || []];
      const total = first.total ?? items.length;
      const pages = Math.ceil(total / size);
      for (let p = 1; p < pages; p++) {
        const next = await merchantApi.exceptions(status, p, size);
        items.push(...next.items || []);
      }
      return { items, total };
    };
    const [open, processing] = await Promise.all([
      mergePages("OPEN").catch(() => ({ items: [], total: 0 })),
      mergePages("PROCESSING").catch(() => ({ items: [], total: 0 }))
    ]);
    const byId = /* @__PURE__ */ new Map();
    for (const row of [...open.items, ...processing.items]) {
      if (row == null ? void 0 : row.exceptionId) byId.set(row.exceptionId, row);
    }
    return {
      items: [...byId.values()],
      total: (open.total || 0) + (processing.total || 0)
    };
  },
  resolveInventoryException: (id, resolution) => request(`/api/v2/merchant/exceptions/${encodeURIComponent(id)}/resolve`, "POST", { resolution }),
  analytics: (days = 30) => request(`/api/v2/merchant/analytics/overview?days=${days}`),
  settlements: () => request(
    "/api/v2/merchant/settlements/overview"
  ),
  lineWallet: () => request("/api/v2/merchant/line-wallet"),
  lineWalletWithdraw: (body) => request("/api/v2/merchant/line-wallet/withdraw", "POST", body),
  wallet: () => request("/api/v2/merchant/wallet"),
  walletWithdraw: (body) => request("/api/v2/merchant/wallet/withdraw", "POST", body),
  dailySettlements: (from, to) => request(
    `/api/v2/merchant/settlements/daily?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
  ),
  settlementBatches: (from, to) => request(
    `/api/v2/merchant/settlements/batches?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
  ),
  revenueSplits: (page = 0, size = 50, status, from, to) => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) q.set("status", status);
    if (from) q.set("from", from);
    if (to) q.set("to", to);
    return request(
      `/api/v2/merchant/revenue-splits?${q}`
    );
  },
  exportSettlementsUrl: (from, to) => `${config_api.API_BASE_URL}/api/v2/merchant/settlements/export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  exportDeviceReportsUrl: () => `${config_api.API_BASE_URL}/api/v2/merchant/device-reports/export`,
  replenishmentSuggestions: (deviceId) => request(
    `/api/v2/merchant/replenishment/suggestions?deviceId=${encodeURIComponent(deviceId)}`
  ),
  replenishmentRequests: (status, deviceId) => {
    const q = new URLSearchParams();
    if (status) q.set("status", status);
    if (deviceId) q.set("deviceId", deviceId);
    const qs = q.toString();
    return request(
      `/api/v2/merchant/replenishment/requests${qs ? `?${qs}` : ""}`
    );
  },
  submitReplenishmentRequest: (body) => request("/api/v2/merchant/replenishment/requests", "POST", body),
  replenishmentTasks: (status) => request(`/api/v2/merchant/replenishment/tasks${status ? `?status=${encodeURIComponent(status)}` : ""}`),
  replenishmentTaskLines: (taskId) => request(`/api/v2/merchant/replenishment/tasks/${taskId}/lines`),
  checkInReplenishmentTask: (taskId, body) => request(`/api/v2/merchant/replenishment/tasks/${taskId}/check-in`, "POST", body || {}),
  /** 补货员开门：签到后调用，绑定补货任务，不产生消费者账单 */
  openReplenishmentDoor: (taskId) => request(
    `/api/v2/merchant/replenishment/tasks/${taskId}/open-door`,
    "POST"
  ),
  confirmReplenishmentLines: (taskId, lines) => request(`/api/v2/merchant/replenishment/tasks/${taskId}/lines`, "POST", { lines }),
  completeReplenishmentTask: (taskId) => request(`/api/v2/merchant/replenishment/tasks/${taskId}/complete`, "POST"),
  listReplenishmentEvidence: (taskId) => request(
    `/api/v2/merchant/replenishment/tasks/${taskId}/evidence`
  ),
  uploadReplenishmentEvidence: (taskId, filePath) => uploadReplenishmentEvidenceFile(taskId, filePath),
  downloadReplenishmentEvidence: (taskId, fileId) => downloadReplenishmentEvidenceFile(taskId, fileId),
  expiryAlerts: () => request("/api/v2/merchant/expiry-alerts"),
  disputes: (status, page = 0, size = 100) => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) q.set("status", status);
    return request(
      `/api/v2/merchant/disputes?${q}`
    );
  },
  orders: (deviceId, page = 0, size = 50) => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (deviceId) q.set("deviceId", deviceId);
    return request(
      `/api/v2/merchant/orders?${q}`
    );
  },
  orderDetail: (orderId) => request(`/api/v2/merchant/orders/${encodeURIComponent(orderId)}`),
  disputeDetail: (ticketId) => request(`/api/v2/merchant/disputes/${encodeURIComponent(ticketId)}`),
  disputeReply: (ticketId, body) => request(
    `/api/v2/merchant/disputes/${encodeURIComponent(ticketId)}/reply`,
    "POST",
    { body }
  )
};
function hasPerm(me, code) {
  return common_vendor.matchPermission(me == null ? void 0 : me.permissions, code);
}
const MERCHANT_ALERT_TYPE_LABELS = {
  DEVICE_OFFLINE: "柜机离线",
  DEVICE_FAULT: "柜机故障",
  REPLENISHMENT: "补货任务",
  REPLENISHMENT_REQUIRED: "需补货",
  LOW_STOCK: "低库存",
  EXPIRY: "临期",
  DISPUTE: "消费争议"
};
function alertTypeLabel(type) {
  return MERCHANT_ALERT_TYPE_LABELS[type] || common_vendor.displayLabel("exception_type", type, "告警");
}
function merchantAlertTitle(_type, title) {
  return String(title || "").replaceAll("设备", "柜机");
}
exports.alertTypeLabel = alertTypeLabel;
exports.clearSession = clearSession;
exports.downloadAuthedFile = downloadAuthedFile;
exports.getToken = getToken;
exports.handleUnauthorized = handleUnauthorized;
exports.hasPerm = hasPerm;
exports.merchantAlertTitle = merchantAlertTitle;
exports.merchantApi = merchantApi;
exports.merchantLogin = merchantLogin;
exports.openExportedFile = openExportedFile;
exports.request = request;
