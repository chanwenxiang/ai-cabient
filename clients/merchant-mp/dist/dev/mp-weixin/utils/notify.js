"use strict";
const common_vendor = require("../common/vendor.js");
const SUBSCRIBE_TMPL_IDS = "".split(",").map((s) => s.trim()).filter(Boolean);
const MERCHANT_ALERT_TYPES = [
  { value: "DISPUTE", label: "争议待审" },
  { value: "DEVICE_OFFLINE", label: "柜机离线" },
  { value: "LOW_STOCK", label: "低库存" },
  { value: "EXPIRY", label: "临期下架" },
  { value: "SLOT_DISCREPANCY", label: "货道差异" },
  { value: "REPLENISHMENT", label: "补货任务" },
  { value: "EXCEPTION", label: "识别/故障异常" }
];
function hasSubscribeTemplates() {
  return SUBSCRIBE_TMPL_IDS.length > 0;
}
async function requestMerchantSubscribe() {
  if (!SUBSCRIBE_TMPL_IDS.length) return "skipped";
  return await new Promise((resolve) => {
    common_vendor.index.requestSubscribeMessage({
      tmplIds: SUBSCRIBE_TMPL_IDS,
      success: () => resolve("ok"),
      fail: () => resolve("failed"),
      complete: () => {
      }
    });
  });
}
function wxLoginCode() {
  return new Promise((resolve, reject) => {
    common_vendor.index.login({
      provider: "weixin",
      success: (res) => {
        if (res.code) resolve(res.code);
        else reject(new Error("未获取到微信登录码"));
      },
      fail: (err) => reject(new Error(err.errMsg || "微信登录失败"))
    });
  });
}
exports.MERCHANT_ALERT_TYPES = MERCHANT_ALERT_TYPES;
exports.hasSubscribeTemplates = hasSubscribeTemplates;
exports.requestMerchantSubscribe = requestMerchantSubscribe;
exports.wxLoginCode = wxLoginCode;
