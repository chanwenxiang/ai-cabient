"use strict";
const common_vendor = require("../common/vendor.js");
const utils_consumerApi = require("./consumer-api.js");
const PENDING_RECHARGE_KEY = "pending_recharge_order_id";
function savePendingRechargeOrder(orderId) {
  common_vendor.index.setStorageSync(PENDING_RECHARGE_KEY, orderId);
}
function takePendingRechargeOrder() {
  const id = String(common_vendor.index.getStorageSync(PENDING_RECHARGE_KEY) || "");
  if (id)
    common_vendor.index.removeStorageSync(PENDING_RECHARGE_KEY);
  return id;
}
function openAlipayPayUrl(payUrl) {
  if (!payUrl) {
    throw new Error("支付宝支付链接为空");
  }
  throw new Error("支付宝沙箱充值请在 H5 浏览器中打开");
}
function openAlipayPayForm(payFormHtml) {
  if (!payFormHtml) {
    throw new Error("支付宝支付表单为空");
  }
  throw new Error("支付宝沙箱充值请在 H5 浏览器中打开");
}
function openAlipayPrepay(alipayPay) {
  if (alipayPay == null ? void 0 : alipayPay.payFormHtml) {
    openAlipayPayForm(alipayPay.payFormHtml);
    return;
  }
  if (alipayPay == null ? void 0 : alipayPay.payUrl) {
    openAlipayPayUrl(alipayPay.payUrl);
    return;
  }
  throw new Error("未获取到支付宝支付参数");
}
async function pollRechargePaid(orderId, attempts = 30, intervalMs = 2e3) {
  for (let i = 0; i < attempts; i++) {
    const order = await utils_consumerApi.consumerApi.getRechargeOrder(orderId);
    if (order.status === "PAID")
      return order;
    if (order.status === "CANCELLED" || order.status === "REFUNDED") {
      throw new Error("充值订单已取消或关闭");
    }
    await delay(intervalMs);
  }
  throw new Error("充值结果确认超时，请稍后在「我的」页刷新余额");
}
function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
async function resumePendingRechargeIfAny() {
  const orderId = takePendingRechargeOrder();
  if (!orderId)
    return false;
  try {
    await pollRechargePaid(orderId, 15, 1500);
    common_vendor.index.showToast({ title: "充值已到账", icon: "success" });
    return true;
  } catch (e) {
    common_vendor.index.showToast({
      title: e instanceof Error ? e.message : "充值确认失败",
      icon: "none",
      duration: 3e3
    });
    return false;
  }
}
exports.openAlipayPrepay = openAlipayPrepay;
exports.resumePendingRechargeIfAny = resumePendingRechargeIfAny;
exports.savePendingRechargeOrder = savePendingRechargeOrder;
