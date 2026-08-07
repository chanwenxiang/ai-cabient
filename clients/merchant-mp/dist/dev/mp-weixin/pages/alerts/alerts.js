"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const utils_preferredDevice = require("../../utils/preferred-device.js");
const utils_textPrompt = require("../../utils/text-prompt.js");
const utils_todoBadge = require("../../utils/todo-badge.js");
const utils_todoList = require("../../utils/todo-list.js");
if (!Math) {
  EmptyState();
}
const EmptyState = () => "../../components/empty-state.js";
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "alerts",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const canViewAlerts = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:alerts:view"));
    const canResolveInventory = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:inventory:view"));
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const preferredId = common_vendor.ref("");
    const onlyPreferred = common_vendor.ref(false);
    const counts = common_vendor.ref({ disputes: 0, offline: 0, lowStock: 0, expiry: 0 });
    const items = common_vendor.ref([]);
    let loadSeq = 0;
    const visibleItems = common_vendor.computed(() => {
      if (!onlyPreferred.value || !preferredId.value) return items.value;
      return items.value.filter((a) => !a.deviceId || a.deviceId === preferredId.value);
    });
    function tagClass(type) {
      if (type === "DISPUTE") return "dispute";
      if (type === "DEVICE_OFFLINE") return "offline";
      if (type === "LOW_STOCK") return "stock";
      if (type === "EXPIRY") return "expiry";
      if (type === "REPLENISHMENT" || type === "REPLENISHMENT_REQUIRED") return "stock";
      return "default";
    }
    function actionHint(item) {
      const type = String(item.type || "").toUpperCase();
      if (type === "DISPUTE") return item.ticketId ? "去处理争议 ›" : "查看争议 ›";
      if (type.startsWith("RECOGNITION")) return item.deviceId ? "查看柜机 ›" : "查看争议 ›";
      if (type === "EXPIRY") return "去处理临期任务 ›";
      if (type === "LOW_STOCK") return "去发起要货 ›";
      if (type === "REPLENISHMENT" || type === "REPLENISHMENT_REQUIRED") return "去补货任务 ›";
      if (type === "DEVICE_OFFLINE" || type === "DEVICE_FAULT") return "查看柜机 ›";
      if (item.deviceId) return "查看柜机 ›";
      return "查看详情 ›";
    }
    async function load() {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      const seq = ++loadSeq;
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
      if (!canViewAlerts.value) {
        common_vendor.index.showToast({ title: "无待办权限", icon: "none" });
        common_vendor.index.switchTab({ url: "/pages/home/home" });
        return;
      }
      preferredId.value = utils_preferredDevice.getPreferredDeviceId();
      loading.value = true;
      error.value = "";
      try {
        const [wb, exceptionPage, expiryRows] = await Promise.all([
          utils_merchantApi.merchantApi.workbench().catch(() => ({
            offlineDevices: 0,
            openDisputes: 0,
            lowStockItems: 0,
            expiryAlerts: 0,
            slotDiscrepancies: 0,
            actionItems: []
          })),
          utils_merchantApi.merchantApi.openExceptions(100).catch(() => ({ items: [], total: 0 })),
          utils_merchantApi.merchantApi.expiryAlerts().catch(() => [])
        ]);
        if (seq !== loadSeq) return;
        const deduped = utils_todoList.mergeTodoItems({
          exceptions: exceptionPage.items || [],
          actionItems: wb.actionItems || [],
          expiryRows: expiryRows || []
        });
        items.value = deduped;
        const typeOf = (t) => String(t || "").toUpperCase();
        const audit = deduped.filter(
          (a) => typeOf(a.type) === "DISPUTE" || typeOf(a.type).startsWith("RECOGNITION")
        ).length;
        const fault = deduped.filter(
          (a) => ["DEVICE_OFFLINE", "DEVICE_FAULT", "DOOR_OPEN_TOO_LONG"].includes(typeOf(a.type))
        ).length;
        const stock = deduped.filter(
          (a) => ["LOW_STOCK", "SLOT_DISCREPANCY", "INVENTORY_MISMATCH", "REPLENISHMENT", "REPLENISHMENT_REQUIRED"].includes(
            typeOf(a.type)
          )
        ).length;
        const expiry = deduped.filter((a) => typeOf(a.type) === "EXPIRY").length;
        counts.value = {
          disputes: audit,
          offline: fault,
          lowStock: stock,
          expiry
        };
        utils_todoBadge.setAlertsTabBadge(deduped.length);
      } catch (e) {
        if (seq !== loadSeq) return;
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        if (seq === loadSeq) loading.value = false;
      }
    }
    function handleItem(item) {
      const type = String(item.type || "").toUpperCase();
      if (type === "DISPUTE") {
        common_vendor.index.navigateTo({ url: "/pages/disputes/disputes" });
        return;
      }
      if (type.startsWith("RECOGNITION")) {
        if (item.deviceId) {
          common_vendor.index.navigateTo({
            url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}`
          });
          return;
        }
        common_vendor.index.navigateTo({ url: "/pages/disputes/disputes" });
        return;
      }
      if (type === "EXPIRY" || type === "REPLENISHMENT" || type === "REPLENISHMENT_REQUIRED") {
        const q = item.deviceId ? `?deviceId=${encodeURIComponent(item.deviceId)}` : "";
        common_vendor.index.navigateTo({ url: `/pages/replenishment/replenishment${q}` });
        return;
      }
      if (type === "LOW_STOCK") {
        const q = item.deviceId ? `?deviceId=${encodeURIComponent(item.deviceId)}` : "";
        common_vendor.index.navigateTo({ url: `/pages/request/request${q}` });
        return;
      }
      if (item.deviceId) {
        common_vendor.index.navigateTo({
          url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}`
        });
        return;
      }
      common_vendor.index.showToast({ title: "暂无跳转目标", icon: "none" });
    }
    function goDevices() {
      common_vendor.index.switchTab({ url: "/pages/devices/devices" });
    }
    function isInventoryException(type) {
      return ["INVENTORY_MISMATCH", "LOW_STOCK", "REPLENISHMENT_REQUIRED"].includes(
        String(type || "").toUpperCase()
      );
    }
    async function resolveInventory(item) {
      if (!item.exceptionId) return;
      if (!canResolveInventory.value) {
        common_vendor.index.showToast({ title: "无库存处理权限", icon: "none" });
        return;
      }
      const resolution = await utils_textPrompt.promptText({
        title: "确认完成库存核对",
        hint: "请填写盘点结果或补货说明，便于后台留痕",
        placeholder: "填写盘点结果或补货说明",
        required: true,
        requiredMessage: "必须填写处理结果",
        maxLength: 200,
        testId: "inventory-resolve-prompt"
      });
      if (resolution == null) return;
      try {
        await utils_merchantApi.merchantApi.resolveInventoryException(item.exceptionId, resolution);
        common_vendor.index.showToast({ title: "库存异常已处理", icon: "success" });
        await load();
      } catch (e) {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "处理失败", icon: "none" });
      }
    }
    common_vendor.onShow(load);
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value),
        d: common_vendor.o(load)
      } : common_vendor.e({
        e: preferredId.value
      }, preferredId.value ? {
        f: common_vendor.t(preferredId.value),
        g: common_vendor.t(onlyPreferred.value ? "显示全部" : "仅看常驻"),
        h: common_vendor.o(($event) => onlyPreferred.value = !onlyPreferred.value)
      } : {}, {
        i: common_vendor.t(counts.value.disputes),
        j: common_vendor.t(counts.value.offline),
        k: common_vendor.t(counts.value.lowStock),
        l: common_vendor.t(counts.value.expiry),
        m: common_vendor.f(visibleItems.value, (a, i, i0) => {
          return common_vendor.e({
            a: common_vendor.t(a.typeLabel),
            b: common_vendor.n(tagClass(a.type)),
            c: common_vendor.t(a.title),
            d: a.deviceId
          }, a.deviceId ? {
            e: common_vendor.t(a.deviceId)
          } : {}, {
            f: a.detail
          }, a.detail ? {
            g: common_vendor.t(a.detail)
          } : {}, {
            h: actionHint(a)
          }, actionHint(a) ? {
            i: common_vendor.t(actionHint(a))
          } : {}, {
            j: canResolveInventory.value && a.exceptionId && isInventoryException(a.type)
          }, canResolveInventory.value && a.exceptionId && isInventoryException(a.type) ? {
            k: common_vendor.o(($event) => resolveInventory(a), a.exceptionId || a.ticketId || `${a.type}-${a.deviceId}-${i}`)
          } : {}, {
            l: a.exceptionId || a.ticketId || `${a.type}-${a.deviceId}-${i}`,
            m: common_vendor.o(($event) => handleItem(a), a.exceptionId || a.ticketId || `${a.type}-${a.deviceId}-${i}`)
          });
        }),
        n: !visibleItems.value.length
      }, !visibleItems.value.length ? {
        o: common_vendor.o(goDevices),
        p: common_vendor.p({
          icon: "✅",
          title: "暂无待办事项",
          hint: "争议、离线、低库存与临期告警都会集中显示在这里"
        })
      } : {}), {
        b: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-102b3300"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/alerts/alerts.vue"]]);
wx.createPage(MiniProgramPage);
