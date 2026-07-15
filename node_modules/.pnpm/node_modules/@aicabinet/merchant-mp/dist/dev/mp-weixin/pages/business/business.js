"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "business",
  setup(__props) {
    const periods = [7, 30, 90];
    const days = common_vendor.ref(30);
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const analytics = common_vendor.ref({ days: 30, revenueCents: 0, cogsCents: 0, grossMarginCents: 0, writeOffCostCents: 0, topSkus: [] });
    const settlement = common_vendor.ref({ pendingAmountCents: 0, pendingSplitCount: 0, settledMonthCents: 0, failedSplitCount: 0 });
    const marginRate = common_vendor.computed(() => analytics.value.revenueCents ? `${(analytics.value.grossMarginCents / analytics.value.revenueCents * 100).toFixed(1)}%` : "—");
    const money = (cents = 0) => `¥${(cents / 100).toFixed(2)}`;
    function skuMarginRate(sku) {
      return sku.revenueCents ? `${(sku.grossMarginCents / sku.revenueCents * 100).toFixed(1)}%` : "—";
    }
    async function load() {
      loading.value = true;
      error.value = "";
      try {
        [analytics.value, settlement.value] = await Promise.all([utils_merchantApi.merchantApi.analytics(days.value), utils_merchantApi.merchantApi.settlements()]);
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function changeDays(value) {
      if (days.value === value)
        return;
      days.value = value;
      load();
    }
    common_vendor.onLoad(load);
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    return (_ctx, _cache) => {
      var _a, _b, _c;
      return common_vendor.e({
        a: common_vendor.f(periods, (d, k0, i0) => {
          return {
            a: common_vendor.t(d),
            b: d,
            c: days.value === d ? 1 : "",
            d: common_vendor.o(($event) => changeDays(d), d)
          };
        }),
        b: loading.value
      }, loading.value ? {} : error.value ? {
        d: common_vendor.t(error.value),
        e: common_vendor.o(load)
      } : common_vendor.e({
        f: common_vendor.t(money(analytics.value.grossMarginCents)),
        g: common_vendor.t(money(analytics.value.revenueCents)),
        h: common_vendor.t(marginRate.value),
        i: common_vendor.t(money(settlement.value.settledMonthCents)),
        j: common_vendor.t(money(settlement.value.pendingAmountCents)),
        k: common_vendor.t(((_a = analytics.value.topSkus) == null ? void 0 : _a.length) || 0),
        l: common_vendor.t(settlement.value.failedSplitCount || 0),
        m: common_vendor.f(analytics.value.topSkus || [], (sku, k0, i0) => {
          return {
            a: common_vendor.t(sku.skuName),
            b: common_vendor.t(money(sku.grossMarginCents)),
            c: common_vendor.t(skuMarginRate(sku)),
            d: common_vendor.t(sku.qtySold),
            e: common_vendor.t(money(sku.revenueCents)),
            f: sku.skuId
          };
        }),
        n: !((_b = analytics.value.topSkus) == null ? void 0 : _b.length)
      }, !((_c = analytics.value.topSkus) == null ? void 0 : _c.length) ? {} : {}, {
        o: settlement.value.failedSplitCount
      }, settlement.value.failedSplitCount ? {
        p: common_vendor.t(settlement.value.failedSplitCount)
      } : {}), {
        c: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-11e37ba9"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/business/business.vue"]]);
wx.createPage(MiniProgramPage);
