"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const utils_scanCabinet = require("../../utils/scan-cabinet.js");
const utils_preferredDevice = require("../../utils/preferred-device.js");
if (!Math) {
  EmptyState();
}
const EmptyState = () => "../../components/empty-state.js";
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "devices",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const canListDevices = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:devices:list"));
    const canReplenishment = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:replenishment:view"));
    const loading = common_vendor.ref(true);
    const scanning = common_vendor.ref(false);
    const error = common_vendor.ref("");
    let loadSeq = 0;
    const devices = common_vendor.ref([]);
    const keyword = common_vendor.ref("");
    const filter = common_vendor.ref("all");
    const preferredId = common_vendor.ref("");
    const onlyPreferred = common_vendor.ref(false);
    const filters = [
      { label: "全部", value: "all" },
      { label: "在线", value: "online" },
      { label: "离线", value: "offline" },
      { label: "停售", value: "locked" }
    ];
    const preferredLabel = common_vendor.computed(() => {
      const hit = devices.value.find((d) => d.deviceId === preferredId.value);
      return (hit == null ? void 0 : hit.deviceName) || preferredId.value || "未设置";
    });
    const visibleDevices = common_vendor.computed(() => {
      const q = keyword.value.trim().toLowerCase();
      const list = devices.value.filter((d) => {
        let statusMatch = true;
        if (filter.value === "online") statusMatch = !!d.online;
        else if (filter.value === "offline") statusMatch = !d.online;
        else if (filter.value === "locked") statusMatch = !!d.salesLocked;
        const keywordMatch = !q || `${d.deviceName || ""} ${d.deviceId}`.toLowerCase().includes(q);
        const preferredMatch = !onlyPreferred.value || d.deviceId === preferredId.value;
        return statusMatch && keywordMatch && preferredMatch;
      });
      if (!preferredId.value) return list;
      return [...list].sort((a, b) => {
        if (a.deviceId === preferredId.value) return -1;
        if (b.deviceId === preferredId.value) return 1;
        return 0;
      });
    });
    const emptyHint = common_vendor.computed(() => {
      if (onlyPreferred.value && preferredId.value) return "常驻柜不在当前筛选结果中";
      return devices.value.length ? "没有符合条件的柜机" : "暂无柜机";
    });
    function countFor(value) {
      if (value === "all") return devices.value.length;
      if (value === "locked") return devices.value.filter((d) => !!d.salesLocked).length;
      return devices.value.filter((d) => value === "online" ? d.online : !d.online).length;
    }
    function toggleOnlyPreferred() {
      if (!preferredId.value) {
        common_vendor.index.showToast({ title: "先点 ★ 设常驻柜", icon: "none" });
        return;
      }
      onlyPreferred.value = !onlyPreferred.value;
    }
    function togglePreferred(id) {
      if (preferredId.value === id) {
        utils_preferredDevice.clearPreferredDeviceId();
        preferredId.value = "";
        onlyPreferred.value = false;
        common_vendor.index.showToast({ title: "已取消常驻", icon: "none" });
        return;
      }
      utils_preferredDevice.setPreferredDeviceId(id);
      preferredId.value = id;
      common_vendor.index.showToast({ title: "已设为常驻柜", icon: "success" });
    }
    function clearPreferred() {
      utils_preferredDevice.clearPreferredDeviceId();
      preferredId.value = "";
      onlyPreferred.value = false;
    }
    async function load() {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      const seq = ++loadSeq;
      preferredId.value = utils_preferredDevice.getPreferredDeviceId();
      try {
        await refreshMe();
      } catch {
        if (!common_vendor.index.getStorageSync("merchant_token")) return;
        me.value = me.value || common_vendor.index.getStorageSync("merchant_me") || null;
      }
      if (seq !== loadSeq) return;
      if (!me.value) {
        me.value = common_vendor.index.getStorageSync("merchant_me") || null;
      }
      if (!canListDevices.value) {
        common_vendor.index.showToast({ title: "无柜机权限", icon: "none" });
        common_vendor.index.switchTab({ url: "/pages/home/home" });
        return;
      }
      loading.value = true;
      error.value = "";
      try {
        const list = await utils_merchantApi.merchantApi.devices();
        if (seq !== loadSeq) return;
        devices.value = list.map((d) => ({ ...d, online: (d.onlineStatus || "").toUpperCase() === "ONLINE" }));
      } catch (e) {
        if (seq !== loadSeq) return;
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        if (seq === loadSeq) loading.value = false;
      }
    }
    function goDetail(id) {
      common_vendor.index.navigateTo({ url: `/pages/device-detail/device-detail?id=${encodeURIComponent(id)}` });
    }
    function goReplenishment() {
      if (!canReplenishment.value) {
        common_vendor.index.showToast({ title: "无补货权限", icon: "none" });
        return;
      }
      common_vendor.index.navigateTo({ url: "/pages/replenishment/replenishment" });
    }
    async function onScan() {
      if (scanning.value) return;
      scanning.value = true;
      try {
        const id = await utils_scanCabinet.scanCabinetDeviceId();
        if (!id) return;
        const key = id.trim().toUpperCase();
        const hit = devices.value.find((d) => String(d.deviceId || "").trim().toUpperCase() === key);
        if (!hit) {
          common_vendor.index.showToast({ title: "未找到该柜机或无权限", icon: "none" });
          return;
        }
        utils_preferredDevice.setPreferredDeviceId(hit.deviceId);
        preferredId.value = hit.deviceId;
        goDetail(hit.deviceId);
      } finally {
        scanning.value = false;
      }
    }
    common_vendor.onShow(load);
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: scanning.value,
        b: common_vendor.o(onScan),
        c: canReplenishment.value
      }, canReplenishment.value ? {
        d: common_vendor.o(goReplenishment)
      } : {}, {
        e: loading.value
      }, loading.value ? {} : error.value ? {
        g: common_vendor.t(error.value),
        h: common_vendor.o(load)
      } : common_vendor.e({
        i: keyword.value,
        j: common_vendor.o(($event) => keyword.value = $event.detail.value),
        k: common_vendor.f(filters, (f, k0, i0) => {
          return {
            a: common_vendor.t(f.label),
            b: common_vendor.t(countFor(f.value)),
            c: f.value,
            d: filter.value === f.value ? 1 : "",
            e: common_vendor.o(($event) => filter.value = f.value, f.value)
          };
        }),
        l: common_vendor.t(preferredId.value ? "1" : "0"),
        m: onlyPreferred.value ? 1 : "",
        n: common_vendor.o(toggleOnlyPreferred),
        o: preferredId.value
      }, preferredId.value ? {
        p: common_vendor.t(preferredLabel.value),
        q: common_vendor.o(clearPreferred)
      } : {}, {
        r: common_vendor.f(visibleDevices.value, (d, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.n(d.online ? "on" : "off"),
            b: common_vendor.t(d.deviceName || d.deviceId),
            c: common_vendor.t(d.deviceId),
            d: preferredId.value === d.deviceId ? "取消常驻柜" : "设为常驻柜",
            e: preferredId.value === d.deviceId ? 1 : "",
            f: common_vendor.o(($event) => togglePreferred(d.deviceId), d.deviceId),
            g: d.salesLocked
          }, d.salesLocked ? {} : {}, {
            h: common_vendor.t(d.online ? "在线" : "离线"),
            i: common_vendor.n(d.online ? "status-on" : "status-off"),
            j: d.deviceId,
            k: common_vendor.o(($event) => goDetail(d.deviceId), d.deviceId)
          });
        }),
        s: !visibleDevices.value.length
      }, !visibleDevices.value.length ? {
        t: common_vendor.p({
          icon: "柜",
          title: emptyHint.value,
          hint: "可切换筛选或扫码绑定常驻柜"
        })
      } : {}), {
        f: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-4e1d99d4"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/devices/devices.vue"]]);
wx.createPage(MiniProgramPage);
