"use strict";
const common_vendor = require("../common/vendor.js");
const utils_textPrompt = require("./text-prompt.js");
function isBrowserH5() {
  return typeof window !== "undefined" && typeof document !== "undefined";
}
function resolveDeviceId(raw) {
  const trimmed = String(raw || "").trim();
  if (!trimmed) return "";
  const parsed = common_vendor.parseCabinetScan(trimmed);
  return parsed.deviceId || common_vendor.normalizeDeviceId(trimmed);
}
async function promptManualDeviceId(hint) {
  const value = await utils_textPrompt.promptText({
    title: "输入柜机编号",
    hint: isBrowserH5() ? "浏览器无法调起扫码，请输入柜门上的编号" : void 0,
    placeholder: "例如 CAB-001",
    defaultValue: "",
    required: true,
    requiredMessage: "柜机编号无效",
    maxLength: 32,
    singleLine: true,
    testId: "device-id-prompt"
  });
  if (value == null) return "";
  const id = resolveDeviceId(value);
  if (!id) {
    common_vendor.index.showToast({ title: "柜机编号无效", icon: "none" });
    return "";
  }
  return id;
}
function scanCabinetDeviceId() {
  return new Promise((resolve) => {
    common_vendor.index.scanCode({
      onlyFromCamera: false,
      scanType: ["qrCode", "barCode"],
      success(res) {
        const parsed = common_vendor.parseCabinetScan(res.result || "");
        const id = parsed.deviceId || "";
        if (!id) {
          if (isBrowserH5()) {
            void promptManualDeviceId().then(resolve);
            return;
          }
          common_vendor.index.showToast({ title: "未识别到柜机编号", icon: "none" });
          resolve("");
          return;
        }
        resolve(id);
      },
      fail(err) {
        const msg = (err == null ? void 0 : err.errMsg) || "";
        if (/cancel|取消/i.test(msg)) {
          resolve("");
          return;
        }
        if (isBrowserH5()) {
          void promptManualDeviceId().then(resolve);
          return;
        }
        common_vendor.index.showToast({ title: "扫码失败，请重试", icon: "none" });
        resolve("");
      }
    });
  });
}
exports.scanCabinetDeviceId = scanCabinetDeviceId;
