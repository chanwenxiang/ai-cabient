"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "device-detail",
  setup(__props) {
    const { me } = composables_useMerchantMe.useMerchantMe();
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const deviceId = common_vendor.ref("");
    const merchantId = common_vendor.ref("");
    const deviceName = common_vendor.ref("");
    const online = common_vendor.ref(false);
    const currentTemp = common_vendor.ref("-");
    const targetTemp = common_vendor.ref("-");
    const formName = common_vendor.ref("");
    const formTargetTemp = common_vendor.ref("");
    const formRemark = common_vendor.ref("");
    const saving = common_vendor.ref(false);
    const savingSlots = common_vendor.ref(false);
    const slots = common_vendor.ref([]);
    const slotPar = common_vendor.ref({});
    const canEditDevice = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:devices:edit"));
    const canEditSlots = common_vendor.computed(() => composables_useMerchantMe.canEditPlanogramForMerchant(me.value, merchantId.value));
    common_vendor.onLoad((opts) => {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      deviceId.value = decodeURIComponent((opts == null ? void 0 : opts.id) || "");
      if (!deviceId.value) {
        error.value = "设备不存在";
        loading.value = false;
        return;
      }
      loadDetail();
    });
    async function loadDetail() {
      loading.value = true;
      try {
        const settings = await utils_merchantApi.merchantApi.deviceSettings(deviceId.value);
        merchantId.value = settings.merchantId || "";
        deviceName.value = settings.deviceName || deviceId.value;
        online.value = (settings.onlineStatus || "").toUpperCase() === "ONLINE";
        currentTemp.value = settings.currentTempC != null ? settings.currentTempC + "°C" : "暂无";
        targetTemp.value = settings.targetTempC != null ? settings.targetTempC + "°C" : "未设置";
        formName.value = settings.deviceName || "";
        formTargetTemp.value = settings.targetTempC != null ? String(settings.targetTempC) : "";
        formRemark.value = settings.opsRemark || "";
        const list = await utils_merchantApi.merchantApi.deviceSlots(deviceId.value);
        slots.value = list;
        const par = {};
        list.forEach((s) => {
          par[s.slotCode] = String(s.parLevel);
        });
        slotPar.value = par;
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    async function saveSettings() {
      if (saving.value)
        return;
      const body = {
        deviceName: formName.value.trim() || null,
        opsRemark: formRemark.value.trim() || null
      };
      if (formTargetTemp.value !== "")
        body.targetTempC = parseInt(formTargetTemp.value, 10);
      saving.value = true;
      try {
        await utils_merchantApi.merchantApi.updateDeviceSettings(deviceId.value, body);
        common_vendor.index.showToast({ title: "已保存", icon: "success" });
        await loadDetail();
      } catch (e) {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "保存失败", icon: "none" });
      } finally {
        saving.value = false;
      }
    }
    async function saveSlots() {
      if (savingSlots.value)
        return;
      const body = slots.value.map((s) => ({
        slotCode: s.slotCode,
        rowNo: s.rowNo,
        colNo: s.colNo,
        slotType: s.slotType,
        assignedSkuId: s.assignedSkuId,
        parLevel: parseInt(slotPar.value[s.slotCode] || String(s.parLevel), 10),
        minLevel: s.minLevel,
        maxLevel: s.maxLevel,
        enabled: s.enabled
      }));
      savingSlots.value = true;
      try {
        await utils_merchantApi.merchantApi.upsertSlots(deviceId.value, body);
        common_vendor.index.showToast({ title: "货道已保存", icon: "success" });
        await loadDetail();
      } catch (e) {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "保存失败", icon: "none" });
      } finally {
        savingSlots.value = false;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value)
      } : common_vendor.e({
        d: common_vendor.t(deviceName.value),
        e: common_vendor.t(deviceId.value),
        f: common_vendor.t(online.value ? "在线" : "离线"),
        g: common_vendor.t(currentTemp.value),
        h: common_vendor.t(targetTemp.value),
        i: canEditDevice.value
      }, canEditDevice.value ? {
        j: formName.value,
        k: common_vendor.o(($event) => formName.value = $event.detail.value),
        l: formTargetTemp.value,
        m: common_vendor.o(($event) => formTargetTemp.value = $event.detail.value),
        n: formRemark.value,
        o: common_vendor.o(($event) => formRemark.value = $event.detail.value),
        p: common_vendor.t(saving.value ? "保存中…" : "保存设置"),
        q: common_vendor.o(saveSettings)
      } : {}, {
        r: !canEditSlots.value
      }, !canEditSlots.value ? {} : {}, {
        s: common_vendor.f(slots.value, (s, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(s.slotCode),
            b: common_vendor.t(s.assignedSkuName || "空"),
            c: common_vendor.t(s.bookQty),
            d: common_vendor.t(s.parLevel)
          }, canEditSlots.value ? {
            e: slotPar.value[s.slotCode],
            f: common_vendor.o(($event) => slotPar.value[s.slotCode] = $event.detail.value, s.slotCode)
          } : {}, {
            g: s.slotCode
          });
        }),
        t: canEditSlots.value,
        v: canEditSlots.value
      }, canEditSlots.value ? {
        w: common_vendor.t(savingSlots.value ? "保存中…" : "保存货道"),
        x: common_vendor.o(saveSlots)
      } : {}), {
        b: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-ec644104"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/device-detail/device-detail.vue"]]);
wx.createPage(MiniProgramPage);
