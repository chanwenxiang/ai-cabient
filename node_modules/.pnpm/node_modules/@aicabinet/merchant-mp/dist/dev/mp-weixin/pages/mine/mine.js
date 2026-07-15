"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "mine",
  setup(__props) {
    const meName = common_vendor.ref("");
    const merchantNames = common_vendor.ref("");
    const phone = common_vendor.ref("");
    common_vendor.onShow(() => {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      const me = common_vendor.index.getStorageSync("merchant_me") || {};
      meName.value = me.displayName || me.phoneNumber || "商户";
      merchantNames.value = (me.merchants || []).map((m) => m.merchantName).join("、") || "未绑定";
      phone.value = me.phoneNumber || "";
    });
    function goPricing() {
      common_vendor.index.navigateTo({ url: "/pages/pricing/pricing" });
    }
    function goBusiness() {
      common_vendor.index.navigateTo({ url: "/pages/business/business" });
    }
    function goReplenishment() {
      common_vendor.index.navigateTo({ url: "/pages/replenishment/replenishment" });
    }
    function goSettlements() {
      common_vendor.index.navigateTo({ url: "/pages/settlements/settlements" });
    }
    function goDisputes() {
      common_vendor.index.navigateTo({ url: "/pages/disputes/disputes" });
    }
    function goCoupons() {
      common_vendor.index.navigateTo({ url: "/pages/pricing/pricing" });
    }
    function onLogout() {
      utils_merchantApi.clearSession();
      common_vendor.index.reLaunch({ url: "/pages/login/login" });
    }
    return (_ctx, _cache) => {
      return {
        a: common_vendor.t(meName.value),
        b: common_vendor.t(merchantNames.value),
        c: common_vendor.t(phone.value),
        d: common_vendor.o(goBusiness),
        e: common_vendor.o(goPricing),
        f: common_vendor.o(goReplenishment),
        g: common_vendor.o(goSettlements),
        h: common_vendor.o(goDisputes),
        i: common_vendor.o(goCoupons),
        j: common_vendor.o(onLogout)
      };
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-d41d38da"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/mine/mine.vue"]]);
wx.createPage(MiniProgramPage);
