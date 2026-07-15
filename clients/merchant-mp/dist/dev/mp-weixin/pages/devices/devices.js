"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "devices",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const devices = common_vendor.ref([]);
    const keyword = common_vendor.ref("");
    const filter = common_vendor.ref("all");
    const filters = [
      { label: "全部", value: "all" },
      { label: "在线", value: "online" },
      { label: "离线", value: "offline" }
    ];
    const visibleDevices = common_vendor.computed(() => {
      const q = keyword.value.trim().toLowerCase();
      return devices.value.filter((d) => {
        const statusMatch = filter.value === "all" || (filter.value === "online" ? d.online : !d.online);
        const keywordMatch = !q || `${d.deviceName || ""} ${d.deviceId}`.toLowerCase().includes(q);
        return statusMatch && keywordMatch;
      });
    });
    function countFor(value) {
      if (value === "all")
        return devices.value.length;
      return devices.value.filter((d) => value === "online" ? d.online : !d.online).length;
    }
    async function load() {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      loading.value = true;
      try {
        const list = await utils_merchantApi.merchantApi.devices();
        devices.value = list.map((d) => ({ ...d, online: (d.onlineStatus || "").toUpperCase() === "ONLINE" }));
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function goDetail(id) {
      common_vendor.index.navigateTo({ url: `/pages/device-detail/device-detail?id=${encodeURIComponent(id)}` });
    }
    common_vendor.onShow(load);
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value)
      } : common_vendor.e({
        d: keyword.value,
        e: common_vendor.o(($event) => keyword.value = $event.detail.value),
        f: common_vendor.f(filters, (f, k0, i0) => {
          return {
            a: common_vendor.t(f.label),
            b: common_vendor.t(countFor(f.value)),
            c: f.value,
            d: filter.value === f.value ? 1 : "",
            e: common_vendor.o(($event) => filter.value = f.value, f.value)
          };
        }),
        g: common_vendor.f(visibleDevices.value, (d, k0, i0) => {
          return {
            a: common_vendor.n(d.online ? "on" : "off"),
            b: common_vendor.t(d.deviceName || d.deviceId),
            c: common_vendor.t(d.deviceId),
            d: common_vendor.t(d.online ? "在线" : "离线"),
            e: common_vendor.n(d.online ? "status-on" : "status-off"),
            f: d.deviceId,
            g: common_vendor.o(($event) => goDetail(d.deviceId), d.deviceId)
          };
        }),
        h: !visibleDevices.value.length
      }, !visibleDevices.value.length ? {
        i: common_vendor.t(devices.value.length ? "没有符合条件的柜机" : "暂无柜机")
      } : {}), {
        b: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-4e1d99d4"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/devices/devices.vue"]]);
wx.createPage(MiniProgramPage);
