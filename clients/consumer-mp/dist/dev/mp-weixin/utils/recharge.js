"use strict";
const common_vendor = require("../common/vendor.js");
const utils_consumerApi = require("./consumer-api.js");
const PENDING_RECHARGE_KEY = "pending_recharge_order_id";
const ALIPAY_RETURN_PAGE_KEY = "alipay_return_page";
const ALIPAY_RETURN_PAGE = "/pages/recharge/recharge";
function savePendingRechargeOrder(orderId) {
  common_vendor.index.setStorageSync(PENDING_RECHARGE_KEY, orderId);
}
function peekPendingRechargeOrder() {
  return String(common_vendor.index.getStorageSync(PENDING_RECHARGE_KEY) || "");
}
function clearPendingRechargeOrder() {
  common_vendor.index.removeStorageSync(PENDING_RECHARGE_KEY);
}
function rememberAlipayReturnPage(page = ALIPAY_RETURN_PAGE) {
  common_vendor.index.setStorageSync(ALIPAY_RETURN_PAGE_KEY, page);
}
function openAlipayPayUrl(payUrl) {
  if (!payUrl) {
    throw new Error("支付宝支付链接为空");
  }
  rememberAlipayReturnPage();
  throw new Error("支付宝沙箱充值请在 H5 浏览器中打开");
}
function openAlipayPayForm(payFormHtml) {
  if (!payFormHtml) {
    throw new Error("支付宝支付表单为空");
  }
  rememberAlipayReturnPage();
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
    if (order.status === "PAID") return order;
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
  const orderId = peekPendingRechargeOrder();
  if (!orderId) return false;
  try {
    await pollRechargePaid(orderId, 8, 1500);
    clearPendingRechargeOrder();
    common_vendor.index.showToast({ title: "充值已到账", icon: "success" });
    return true;
  } catch (e) {
    const msg = e instanceof Error ? e.message : "充值确认失败";
    if (/超时|timeout|无法连接|网络|request:fail/i.test(msg)) {
      return false;
    }
    if (/取消|关闭|CANCELLED|REFUNDED/i.test(msg)) {
      clearPendingRechargeOrder();
      return false;
    }
    if (/不存在|404|NOT_FOUND/i.test(msg)) {
      clearPendingRechargeOrder();
      return false;
    }
    const softKey = `recharge_resume_soft_${orderId}`;
    if (!common_vendor.index.getStorageSync(softKey)) {
      common_vendor.index.setStorageSync(softKey, "1");
      common_vendor.index.showToast({ title: "有一笔充值待确认，稍后刷新余额即可", icon: "none", duration: 2500 });
    }
    return false;
  }
}
function wxPayMode(prepay) {
  var _a, _b, _c;
  return String(((_a = prepay.debugInfo) == null ? void 0 : _a.mode) || ((_c = (_b = prepay.wxPay) == null ? void 0 : _b.debugInfo) == null ? void 0 : _c.mode) || "").toLowerCase();
}
function invokeWxRequestPayment(wxPay) {
  return new Promise((resolve, reject) => {
    const pkg = wxPay.packageValue || wxPay.package || "";
    common_vendor.index.requestPayment({
      provider: "wxpay",
      timeStamp: String(wxPay.timeStamp || wxPay.timestamp || ""),
      nonceStr: String(wxPay.nonceStr || ""),
      package: pkg,
      signType: wxPay.signType || "RSA",
      paySign: String(wxPay.paySign || ""),
      success: () => resolve(),
      fail: (err) => reject(new Error((err == null ? void 0 : err.errMsg) || "微信支付取消或失败"))
    });
  });
}
async function runWeChatRecharge(amountCents, idempotencyKey) {
  const prepay = await utils_consumerApi.consumerApi.createRechargePrepay("WECHAT", amountCents, idempotencyKey);
  const mode = wxPayMode(prepay) === "live" ? "live" : "mock";
  if (mode === "live" && prepay.wxPay) {
    savePendingRechargeOrder(prepay.orderId);
    try {
      await invokeWxRequestPayment(prepay.wxPay);
      await pollRechargePaid(prepay.orderId, 20, 1500);
      clearPendingRechargeOrder();
    } catch (e) {
      throw e;
    }
    return { orderId: prepay.orderId, mode };
  }
  await utils_consumerApi.consumerApi.confirmMockRecharge(prepay.orderId);
  return { orderId: prepay.orderId, mode: "mock" };
}
async function runAlipayRecharge(amountCents, idempotencyKey) {
  var _a, _b, _c;
  const prepay = await utils_consumerApi.consumerApi.createRechargePrepay("ALIPAY", amountCents, idempotencyKey);
  const mode = String(((_a = prepay.debugInfo) == null ? void 0 : _a.mode) || "").toLowerCase() === "live" ? "live" : "mock";
  if (mode === "live") {
    if (!((_b = prepay.alipayPay) == null ? void 0 : _b.payFormHtml) && !((_c = prepay.alipayPay) == null ? void 0 : _c.payUrl)) {
      throw new Error("未获取到支付宝支付链接，请检查沙箱配置");
    }
    savePendingRechargeOrder(prepay.orderId);
    openAlipayPrepay(prepay.alipayPay);
    return { orderId: prepay.orderId, mode };
  }
  await utils_consumerApi.consumerApi.confirmMockRecharge(prepay.orderId);
  return { orderId: prepay.orderId, mode: "mock" };
}
exports.resumePendingRechargeIfAny = resumePendingRechargeIfAny;
exports.runAlipayRecharge = runAlipayRecharge;
exports.runWeChatRecharge = runWeChatRecharge;
