"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "settlements",
  setup(__props) {
    const today = (/* @__PURE__ */ new Date()).toISOString().substring(0, 10);
    const sevenDaysAgo = new Date(Date.now() - 7 * 864e5).toISOString().substring(0, 10);
    const startDate = common_vendor.ref(sevenDaysAgo);
    const endDate = common_vendor.ref(today);
    const summary = common_vendor.ref({ gross: "0.00", platformFee: "0.00", merchantIncome: "0.00" });
    const deviceDetails = common_vendor.ref([]);
    common_vendor.onShow(() => load());
    async function load() {
      try {
        const res = await utils_merchantApi.merchantApi.get("/api/v2/merchant/settlements", {
          params: { startDate: startDate.value, endDate: endDate.value }
        });
        const data = res.data ?? {};
        summary.value = {
          gross: ((data.grossCents || 0) / 100).toFixed(2),
          platformFee: ((data.platformFeeCents || 0) / 100).toFixed(2),
          merchantIncome: ((data.merchantIncomeCents || 0) / 100).toFixed(2)
        };
        deviceDetails.value = data.devices ?? [];
      } catch {
        common_vendor.index.showToast({ title: "加载失败", icon: "none" });
      }
    }
    async function onExport() {
      try {
        const blob = await utils_merchantApi.merchantApi.get("/api/v2/merchant/settlements/export", {
          params: { startDate: startDate.value, endDate: endDate.value },
          responseType: "blob"
        });
        common_vendor.index.showToast({ title: "导出成功", icon: "success" });
      } catch {
        common_vendor.index.showToast({ title: "导出失败", icon: "error" });
      }
    }
    return (_ctx, _cache) => {
      return {
        a: common_vendor.t(startDate.value),
        b: startDate.value,
        c: common_vendor.o((e) => {
          startDate.value = e.detail.value;
          load();
        }),
        d: common_vendor.t(endDate.value),
        e: endDate.value,
        f: common_vendor.o((e) => {
          endDate.value = e.detail.value;
          load();
        }),
        g: common_vendor.t(summary.value.gross || "0.00"),
        h: common_vendor.t(summary.value.platformFee || "0.00"),
        i: common_vendor.t(summary.value.merchantIncome || "0.00"),
        j: common_vendor.f(deviceDetails.value, (d, k0, i0) => {
          return {
            a: common_vendor.t(d.deviceName || d.deviceId),
            b: common_vendor.t(d.orderCount),
            c: common_vendor.t((d.grossCents / 100).toFixed(2)),
            d: d.deviceId
          };
        }),
        k: common_vendor.o(onExport)
      };
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-12534684"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/settlements/settlements.vue"]]);
wx.createPage(MiniProgramPage);
