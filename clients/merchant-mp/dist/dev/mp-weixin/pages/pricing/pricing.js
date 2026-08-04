"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "pricing",
  setup(__props) {
    const { me } = composables_useMerchantMe.useMerchantMe();
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const rows = common_vendor.ref([]);
    const draft = common_vendor.ref({});
    const devices = common_vendor.ref([]);
    const selectedDeviceId = common_vendor.ref("");
    const canEdit = common_vendor.computed(() => composables_useMerchantMe.canEditPricingWithPerm(me.value));
    const deviceOptions = common_vendor.computed(
      () => [{ deviceId: "", label: "全部柜机" }, ...devices.value.map((d) => ({ deviceId: d.deviceId, label: d.deviceName || d.deviceId }))]
    );
    const selectedLabel = common_vendor.computed(() => {
      const hit = deviceOptions.value.find((d) => d.deviceId === selectedDeviceId.value);
      return (hit == null ? void 0 : hit.label) || "全部柜机";
    });
    common_vendor.watch(me, (m) => {
      if (!m && !common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
      }
    }, { immediate: true });
    common_vendor.watch(me, () => {
      if (me.value)
        load();
    }, { immediate: true });
    async function load() {
      loading.value = true;
      error.value = "";
      try {
        if (!devices.value.length) {
          const list = await utils_merchantApi.merchantApi.devices();
          devices.value = list;
        }
        rows.value = await utils_merchantApi.merchantApi.pricing(selectedDeviceId.value || void 0);
        const d = {};
        rows.value.forEach((p) => {
          d[p.skuId] = p.overridePriceCents != null ? (p.overridePriceCents / 100).toFixed(2) : "";
        });
        draft.value = d;
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function onDevicePick(e) {
      var _a;
      const idx = Number(e.detail.value);
      selectedDeviceId.value = ((_a = deviceOptions.value[idx]) == null ? void 0 : _a.deviceId) || "";
      load();
    }
    async function savePrice(p) {
      if (!canEdit.value)
        return;
      const raw = (draft.value[p.skuId] || "").trim();
      const priceCents = raw === "" ? null : Math.round(parseFloat(raw) * 100);
      if (raw !== "" && (Number.isNaN(priceCents) || priceCents < 0)) {
        common_vendor.index.showToast({ title: "价格无效", icon: "none" });
        return;
      }
      try {
        await utils_merchantApi.merchantApi.updatePricing(p.skuId, { deviceId: p.deviceId, priceCents });
        common_vendor.index.showToast({ title: "已更新", icon: "success" });
        await load();
      } catch (e) {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "保存失败", icon: "none" });
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.t(selectedLabel.value),
        b: deviceOptions.value,
        c: common_vendor.o(onDevicePick),
        d: !canEdit.value
      }, !canEdit.value ? {} : {}, {
        e: loading.value
      }, loading.value ? {} : error.value ? {
        g: common_vendor.t(error.value)
      } : common_vendor.e({
        h: common_vendor.f(rows.value, (p, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(p.skuName),
            b: common_vendor.t((p.basePriceCents / 100).toFixed(2)),
            c: common_vendor.t((p.effectivePriceCents / 100).toFixed(2))
          }, canEdit.value ? {
            d: common_vendor.o(($event) => savePrice(p), p.skuId + p.deviceId),
            e: draft.value[p.skuId],
            f: common_vendor.o(($event) => draft.value[p.skuId] = $event.detail.value, p.skuId + p.deviceId)
          } : {}, {
            g: p.skuId + p.deviceId
          });
        }),
        i: canEdit.value,
        j: !rows.value.length
      }, !rows.value.length ? {} : {}), {
        f: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-1dd96812"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/pricing/pricing.vue"]]);
wx.createPage(MiniProgramPage);
