"use strict";
const common_vendor = require("../common/vendor.js");
const utils_merchantApi = require("../utils/merchant-api.js");
const meRef = common_vendor.ref(null);
const loadingRef = common_vendor.ref(false);
let meSeq = 0;
let inflight = null;
async function refreshMerchantMe() {
  if (inflight) return inflight;
  const seq = ++meSeq;
  const p = (async () => {
    loadingRef.value = true;
    try {
      const me = await utils_merchantApi.merchantApi.me();
      if (seq === meSeq) {
        common_vendor.index.setStorageSync("merchant_me", me);
        meRef.value = me;
      }
      return me;
    } finally {
      if (seq === meSeq) loadingRef.value = false;
      if (inflight === p) inflight = null;
    }
  })();
  inflight = p;
  return p;
}
function useMerchantMe() {
  common_vendor.onShow(() => {
    if (utils_merchantApi.getToken()) {
      refreshMerchantMe().catch(() => {
        if (!utils_merchantApi.getToken()) return;
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
function hasPack(me, pack) {
  if (!me) return false;
  if (Array.isArray(me.enabledPacks)) {
    return me.enabledPacks.includes(pack);
  }
  const merchants = me.merchants || [];
  if (!merchants.length) return false;
  return merchants.some((m) => {
    if (pack === "field") return m.packFieldEnabled !== false;
    if (pack === "biz") return m.packBizEnabled !== false;
    return m.packTeamEnabled !== false;
  });
}
function canAccessNav(me, item) {
  if (!hasPack(me, item.pack)) return false;
  const perms = Array.isArray(item.perm) ? item.perm : [item.perm];
  return perms.some((p) => utils_merchantApi.hasPerm(me, p));
}
function canEditPricingForMe(me) {
  if (!me) return false;
  if (!hasPack(me, "biz")) return false;
  if (me.canEditPricing) return true;
  return (me.merchants || []).some((m) => m.allowMerchantPricingEdit);
}
function canEditPlanogramForMerchant(me, merchantId) {
  if (!me || !merchantId) return false;
  if (!hasPack(me, "field")) return false;
  const m = (me.merchants || []).find((x) => x.merchantId === merchantId);
  return !!(m == null ? void 0 : m.allowMerchantPlanogramEdit) && utils_merchantApi.hasPerm(me, "merchant:slots:edit");
}
function canEditPricingWithPerm(me) {
  return canEditPricingForMe(me) && utils_merchantApi.hasPerm(me, "merchant:pricing:edit");
}
exports.canAccessNav = canAccessNav;
exports.canEditPlanogramForMerchant = canEditPlanogramForMerchant;
exports.canEditPricingWithPerm = canEditPricingWithPerm;
exports.hasPack = hasPack;
exports.useMerchantMe = useMerchantMe;
