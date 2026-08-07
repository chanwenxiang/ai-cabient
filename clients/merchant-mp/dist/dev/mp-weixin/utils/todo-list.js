"use strict";
const utils_merchantApi = require("./merchant-api.js");
function typeKey(type) {
  return String(type || "").toUpperCase();
}
function deviceKey(deviceId) {
  return String(deviceId || "").trim().toUpperCase();
}
function mergeTodoItems(input) {
  const exceptionItems = (input.exceptions || []).map((a) => ({
    type: a.exceptionType,
    typeLabel: utils_merchantApi.alertTypeLabel(a.exceptionType),
    title: utils_merchantApi.merchantAlertTitle(a.exceptionType, a.title),
    detail: utils_merchantApi.merchantAlertTitle(a.exceptionType, a.detail || ""),
    deviceId: a.deviceId,
    exceptionId: a.exceptionId
  }));
  const workbenchItems = (input.actionItems || []).map((a) => ({
    type: a.type,
    typeLabel: utils_merchantApi.alertTypeLabel(a.type),
    title: utils_merchantApi.merchantAlertTitle(a.type, a.title),
    detail: utils_merchantApi.merchantAlertTitle(a.type, a.detail || ""),
    deviceId: a.deviceId,
    ticketId: a.ticketId
  }));
  const expiryItems = (input.expiryRows || []).filter((e) => String(e.status || "OPEN").toUpperCase() === "OPEN").map((e) => ({
    type: "EXPIRY",
    typeLabel: utils_merchantApi.alertTypeLabel("EXPIRY"),
    title: `${e.skuId || "商品"} · 临期/过期 ${e.quantity || 0} 件`,
    detail: [e.deviceId, e.batchNo, e.reason].filter(Boolean).join(" · "),
    deviceId: e.deviceId
  }));
  const hasExpiryApi = expiryItems.length > 0;
  const exceptionTypeDevice = new Set(
    exceptionItems.map((a) => `${typeKey(a.type)}|${deviceKey(a.deviceId)}`)
  );
  const faultOrOfflineDevices = new Set(
    exceptionItems.filter((a) => ["DEVICE_FAULT", "DEVICE_OFFLINE"].includes(typeKey(a.type))).map((a) => deviceKey(a.deviceId)).filter(Boolean)
  );
  const workbenchFiltered = workbenchItems.filter((a) => {
    const t = typeKey(a.type);
    const d = deviceKey(a.deviceId);
    if (hasExpiryApi && t === "EXPIRY") return false;
    if (exceptionTypeDevice.has(`${t}|${d}`)) return false;
    if (t === "DEVICE_OFFLINE" && d && faultOrOfflineDevices.has(d)) return false;
    return true;
  });
  const merged = [...exceptionItems, ...workbenchFiltered, ...expiryItems];
  const seen = /* @__PURE__ */ new Set();
  return merged.filter((a) => {
    const key = `${typeKey(a.type)}|${deviceKey(a.deviceId)}|${a.ticketId || a.exceptionId || a.title}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
exports.mergeTodoItems = mergeTodoItems;
