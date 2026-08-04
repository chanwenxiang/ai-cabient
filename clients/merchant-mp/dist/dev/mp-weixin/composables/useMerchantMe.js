"use strict";
const common_vendor = require("../common/vendor.js");
const utils_merchantApi = require("../utils/merchant-api.js");
const meRef = common_vendor.ref(null);
const loadingRef = common_vendor.ref(false);
async function refreshMerchantMe() {
  loadingRef.value = true;
  try {
    const me = await utils_merchantApi.merchantApi.me();
    common_vendor.index.setStorageSync("merchant_me", me);
    meRef.value = me;
    return me;
  } finally {
    loadingRef.value = false;
  }
}
function useMerchantMe() {
  common_vendor.onShow(() => {
    if (common_vendor.index.getStorageSync("merchant_token")) {
      refreshMerchantMe().catch(() => {
        meRef.value = common_vendor.index.getStorageSync("merchant_me") || null;
      });
    }
  });
  return {
    me: meRef,
    loading: loadingRef,
    refresh: refreshMerchantMe
  };
}
function canEditPricingForMe(me) {
  if (!me)
    return false;
  if (me.canEditPricing)
    return true;
  return (me.merchants || []).some((m) => m.allowMerchantPricingEdit);
}
function canEditPlanogramForMerchant(me, merchantId) {
  if (!me || !merchantId)
    return false;
  const m = (me.merchants || []).find((x) => x.merchantId === merchantId);
  return !!(m == null ? void 0 : m.allowMerchantPlanogramEdit) && utils_merchantApi.hasPerm(me, "merchant:slots:edit");
}
function canEditPricingWithPerm(me) {
  return canEditPricingForMe(me) && utils_merchantApi.hasPerm(me, "merchant:pricing:edit");
}
exports.canEditPlanogramForMerchant = canEditPlanogramForMerchant;
exports.canEditPricingWithPerm = canEditPricingWithPerm;
exports.useMerchantMe = useMerchantMe;
