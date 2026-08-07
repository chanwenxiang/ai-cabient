"use strict";
const common_vendor = require("../common/vendor.js");
const KEY = "merchant_preferred_device_id";
function getPreferredDeviceId() {
  return String(common_vendor.index.getStorageSync(KEY) || "").trim();
}
function setPreferredDeviceId(deviceId) {
  const id = String(deviceId || "").trim();
  if (!id) {
    common_vendor.index.removeStorageSync(KEY);
    return;
  }
  common_vendor.index.setStorageSync(KEY, id);
}
function clearPreferredDeviceId() {
  common_vendor.index.removeStorageSync(KEY);
}
exports.clearPreferredDeviceId = clearPreferredDeviceId;
exports.getPreferredDeviceId = getPreferredDeviceId;
exports.setPreferredDeviceId = setPreferredDeviceId;
