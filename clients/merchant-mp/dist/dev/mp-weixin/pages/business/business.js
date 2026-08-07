"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "business",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const canViewBusiness = common_vendor.computed(
      () => utils_merchantApi.hasPerm(me.value, "merchant:reports:view") || utils_merchantApi.hasPerm(me.value, "merchant:analytics:view")
    );
    const canExport = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:reports:export"));
    const periods = [7, 30, 90];
    const days = common_vendor.ref(30);
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    let loadSeq = 0;
    const analytics = common_vendor.ref({ days: 30, revenueCents: 0, cogsCents: 0, grossMarginCents: 0, writeOffCostCents: 0, topSkus: [] });
    const settlement = common_vendor.ref({ pendingAmountCents: 0, pendingSplitCount: 0, settledMonthCents: 0, failedSplitCount: 0 });
    const marginRate = common_vendor.computed(() => analytics.value.revenueCents ? `${(analytics.value.grossMarginCents / analytics.value.revenueCents * 100).toFixed(1)}%` : "暂无");
    const money = (cents = 0) => `¥${(cents / 100).toFixed(2)}`;
    function skuMarginRate(sku) {
      return sku.revenueCents ? `${(sku.grossMarginCents / sku.revenueCents * 100).toFixed(1)}%` : "暂无";
    }
    async function ensureAccess() {
      if (!utils_merchantApi.getToken()) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return false;
      }
      try {
        await refreshMe();
      } catch {
        if (!utils_merchantApi.getToken()) return false;
        me.value = common_vendor.index.getStorageSync("merchant_me") || null;
      }
      if (!canViewBusiness.value) {
        loading.value = false;
        common_vendor.index.showToast({ title: "无经营分析权限", icon: "none" });
        common_vendor.index.navigateBack({ fail: () => common_vendor.index.switchTab({ url: "/pages/home/home" }) });
        return false;
      }
      return true;
    }
    async function load(soft = false) {
      var _a;
      const seq = ++loadSeq;
      if (!await ensureAccess()) {
        if (seq === loadSeq) loading.value = false;
        return;
      }
      if (seq !== loadSeq) return;
      if (!soft || !((_a = analytics.value.topSkus) == null ? void 0 : _a.length)) loading.value = true;
      error.value = "";
      try {
        const [a, s] = await Promise.all([
          utils_merchantApi.merchantApi.analytics(days.value).catch(() => null),
          utils_merchantApi.merchantApi.settlements().catch(() => null)
        ]);
        if (seq !== loadSeq) return;
        if (!a && !s) {
          error.value = "经营数据加载失败";
          return;
        }
        analytics.value = a || analytics.value;
        settlement.value = s || settlement.value;
      } catch (e) {
        if (seq !== loadSeq) return;
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        if (seq === loadSeq) loading.value = false;
      }
    }
    function changeDays(value) {
      if (days.value === value) return;
      days.value = value;
      void load(true);
    }
    function goFailedSplits() {
      if (!utils_merchantApi.hasPerm(me.value, "merchant:splits:list")) {
        common_vendor.index.showToast({ title: "无分账明细权限", icon: "none" });
        return;
      }
      common_vendor.index.navigateTo({ url: "/pages/splits/splits?status=FAILED" });
    }
    function onExport() {
      if (!canExport.value) {
        common_vendor.index.showToast({ title: "无导出权限", icon: "none" });
        return;
      }
      const url = utils_merchantApi.merchantApi.exportDeviceReportsUrl();
      void utils_merchantApi.downloadAuthedFile(url).then(async (tempFilePath) => {
        await utils_merchantApi.openExportedFile(tempFilePath, `device-reports-${days.value}d.xlsx`);
        common_vendor.index.showToast({ title: "导出成功", icon: "success" });
      }).catch((e) => {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "导出失败", icon: "none" });
      });
    }
    common_vendor.onLoad(() => void load(false));
    common_vendor.onShow(() => {
      if (!loading.value) void load(true);
    });
    common_vendor.onPullDownRefresh(() => load(false).finally(() => common_vendor.index.stopPullDownRefresh()));
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
        p: common_vendor.t(settlement.value.failedSplitCount),
        q: common_vendor.o(goFailedSplits)
      } : {}, {
        r: canExport.value
      }, canExport.value ? {
        s: common_vendor.o(onExport)
      } : {}), {
        c: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-11e37ba9"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/business/business.vue"]]);
wx.createPage(MiniProgramPage);
