"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const utils_preferredDevice = require("../../utils/preferred-device.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "device-detail",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    let loadSeq = 0;
    const deviceId = common_vendor.ref("");
    const merchantId = common_vendor.ref("");
    const deviceName = common_vendor.ref("");
    const online = common_vendor.ref(false);
    const salesLocked = common_vendor.ref(false);
    const currentTemp = common_vendor.ref("暂无");
    const targetTemp = common_vendor.ref("未设置");
    const formName = common_vendor.ref("");
    const formTargetTemp = common_vendor.ref("");
    const formRemark = common_vendor.ref("");
    const saving = common_vendor.ref(false);
    const savingSlots = common_vendor.ref(false);
    const slots = common_vendor.ref([]);
    const slotPar = common_vendor.ref({});
    const isPreferred = common_vendor.ref(false);
    const canView = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:devices:detail"));
    const canEditDevice = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:devices:edit"));
    const canEditSlots = common_vendor.computed(() => composables_useMerchantMe.canEditPlanogramForMerchant(me.value, merchantId.value));
    const canReplenishView = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:replenishment:view"));
    const canRequest = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:replenishment:request"));
    common_vendor.onLoad((opts) => {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      try {
        deviceId.value = decodeURIComponent((opts == null ? void 0 : opts.id) || "");
      } catch {
        deviceId.value = String((opts == null ? void 0 : opts.id) || "");
      }
      if (!deviceId.value) {
        error.value = "柜机不存在";
        loading.value = false;
        return;
      }
      loadDetail();
    });
    async function loadDetail() {
      const seq = ++loadSeq;
      try {
        await refreshMe();
      } catch {
        if (!common_vendor.index.getStorageSync("merchant_token")) return;
        me.value = common_vendor.index.getStorageSync("merchant_me") || null;
      }
      if (seq !== loadSeq) return;
      if (!canView.value) {
        loading.value = false;
        common_vendor.index.showToast({ title: "无柜机详情权限", icon: "none" });
        common_vendor.index.navigateBack({ fail: () => common_vendor.index.switchTab({ url: "/pages/home/home" }) });
        return;
      }
      loading.value = true;
      error.value = "";
      try {
        const settings = await utils_merchantApi.merchantApi.deviceSettings(deviceId.value);
        if (seq !== loadSeq) return;
        merchantId.value = settings.merchantId || "";
        deviceName.value = settings.deviceName || deviceId.value;
        online.value = (settings.onlineStatus || "").toUpperCase() === "ONLINE";
        salesLocked.value = !!settings.salesLocked;
        currentTemp.value = settings.currentTempC != null ? settings.currentTempC + "°C" : "暂无";
        targetTemp.value = settings.targetTempC != null ? settings.targetTempC + "°C" : "未设置";
        formName.value = settings.deviceName || "";
        formTargetTemp.value = settings.targetTempC != null ? String(settings.targetTempC) : "";
        formRemark.value = settings.opsRemark || "";
        const list = await utils_merchantApi.merchantApi.deviceSlots(deviceId.value);
        if (seq !== loadSeq) return;
        slots.value = list;
        const par = {};
        list.forEach((s) => {
          par[s.slotCode] = s.parLevel != null ? String(s.parLevel) : "";
        });
        slotPar.value = par;
        isPreferred.value = String(utils_preferredDevice.getPreferredDeviceId() || "").trim().toUpperCase() === String(deviceId.value || "").trim().toUpperCase();
      } catch (e) {
        if (seq !== loadSeq) return;
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        if (seq === loadSeq) loading.value = false;
      }
    }
    function togglePreferred() {
      if (!deviceId.value) return;
      if (isPreferred.value) {
        utils_preferredDevice.clearPreferredDeviceId();
        isPreferred.value = false;
        common_vendor.index.showToast({ title: "已取消常驻", icon: "none" });
        return;
      }
      utils_preferredDevice.setPreferredDeviceId(deviceId.value);
      isPreferred.value = true;
      common_vendor.index.showToast({ title: "已设为常驻柜", icon: "success" });
    }
    function goReplenishment() {
      common_vendor.index.navigateTo({
        url: `/pages/replenishment/replenishment?deviceId=${encodeURIComponent(deviceId.value)}`
      });
    }
    function goRequest() {
      common_vendor.index.navigateTo({
        url: `/pages/request/request?deviceId=${encodeURIComponent(deviceId.value)}`
      });
    }
    async function saveSettings() {
      if (saving.value) return;
      const body = {
        deviceName: formName.value.trim() || null,
        opsRemark: formRemark.value.trim() || null
      };
      if (formTargetTemp.value !== "") {
        const temp = Number.parseInt(formTargetTemp.value, 10);
        if (!Number.isFinite(temp)) {
          common_vendor.index.showToast({ title: "目标温度须为整数", icon: "none" });
          return;
        }
        body.targetTempC = temp;
      }
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
      if (savingSlots.value) return;
      const body = [];
      for (const s of slots.value) {
        const par = Number.parseInt(slotPar.value[s.slotCode] || String(s.parLevel), 10);
        if (!Number.isFinite(par) || par < 0) {
          common_vendor.index.showToast({ title: `货道 ${s.slotCode} 容量无效`, icon: "none" });
          return;
        }
        body.push({
          slotCode: s.slotCode,
          rowNo: s.rowNo,
          colNo: s.colNo,
          slotType: s.slotType,
          assignedSkuId: s.assignedSkuId,
          parLevel: par,
          minLevel: s.minLevel,
          maxLevel: s.maxLevel,
          enabled: s.enabled
        });
      }
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
        a: !canView.value
      }, !canView.value ? {} : loading.value ? {} : error.value ? {
        d: common_vendor.t(error.value)
      } : common_vendor.e({
        e: common_vendor.t(deviceName.value),
        f: common_vendor.t(deviceId.value),
        g: common_vendor.t(online.value ? "在线" : "离线"),
        h: common_vendor.t(salesLocked.value ? " · 停售中" : ""),
        i: salesLocked.value
      }, salesLocked.value ? {} : {}, {
        j: common_vendor.t(currentTemp.value),
        k: common_vendor.t(targetTemp.value),
        l: isPreferred.value ? 1 : "",
        m: common_vendor.t(isPreferred.value ? "常驻柜（点击取消）" : "设为常驻柜"),
        n: common_vendor.o(togglePreferred),
        o: canReplenishView.value
      }, canReplenishView.value ? {
        p: common_vendor.o(goReplenishment)
      } : {}, {
        q: canRequest.value
      }, canRequest.value ? {
        r: common_vendor.o(goRequest)
      } : {}, {
        s: canEditDevice.value
      }, canEditDevice.value ? {
        t: formName.value,
        v: common_vendor.o(($event) => formName.value = $event.detail.value),
        w: formTargetTemp.value,
        x: common_vendor.o(($event) => formTargetTemp.value = $event.detail.value),
        y: formRemark.value,
        z: common_vendor.o(($event) => formRemark.value = $event.detail.value),
        A: common_vendor.t(saving.value ? "保存中…" : "保存设置"),
        B: common_vendor.o(saveSettings)
      } : {}, {
        C: !canEditSlots.value
      }, !canEditSlots.value ? {} : {}, {
        D: common_vendor.f(slots.value, (s, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(s.slotCode),
            b: common_vendor.t(s.assignedSkuName || "空"),
            c: common_vendor.t(s.bookQty),
            d: common_vendor.t(s.maxLevel || s.parLevel || "未设上限")
          }, canEditSlots.value ? {
            e: slotPar.value[s.slotCode],
            f: common_vendor.o(($event) => slotPar.value[s.slotCode] = $event.detail.value, s.slotCode)
          } : {}, {
            g: s.slotCode
          });
        }),
        E: canEditSlots.value,
        F: canEditSlots.value
      }, canEditSlots.value ? {
        G: common_vendor.t(savingSlots.value ? "保存中…" : "保存货道"),
        H: common_vendor.o(saveSlots)
      } : {}), {
        b: loading.value,
        c: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-ec644104"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/device-detail/device-detail.vue"]]);
wx.createPage(MiniProgramPage);
