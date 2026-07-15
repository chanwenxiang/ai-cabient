"use strict";
const common_vendor = require("../common/vendor.js");
var define_import_meta_env_default = {};
const SUBSCRIBE_TMPL_IDS = (define_import_meta_env_default.VITE_WX_SUBSCRIBE_TMPL_IDS || "").split(",").map((s) => s.trim()).filter(Boolean);
async function requestOrderSubscribe() {
  if (!SUBSCRIBE_TMPL_IDS.length)
    return;
  await new Promise((resolve) => {
    common_vendor.index.requestSubscribeMessage({
      tmplIds: SUBSCRIBE_TMPL_IDS,
      complete: () => resolve()
    });
  });
}
function showBillToast(totalCents) {
  const yuan = (totalCents / 100).toFixed(2);
  const title = totalCents <= 0 ? "本次未消费" : `已扣款 ¥${yuan}`;
  common_vendor.index.showToast({
    title,
    icon: totalCents <= 0 ? "none" : "success",
    duration: 2e3
  });
}
async function requestDisputeSubscribe() {
  return requestOrderSubscribe();
}
function showDisputeResolvedToast(ticket) {
  const amount = ticket.billedAmountCents ?? 0;
  let title = "人工审核已完成";
  if (ticket.status === "RESOLVED" && amount > 0) {
    title = `审核完成，已扣款 ¥${(amount / 100).toFixed(2)}`;
  } else if (ticket.status === "RESOLVED" && amount <= 0) {
    title = "审核完成，本次未扣款";
  }
  common_vendor.index.showToast({ title, icon: "success", duration: 2500 });
}
function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
exports.delay = delay;
exports.requestDisputeSubscribe = requestDisputeSubscribe;
exports.requestOrderSubscribe = requestOrderSubscribe;
exports.showBillToast = showBillToast;
exports.showDisputeResolvedToast = showDisputeResolvedToast;
