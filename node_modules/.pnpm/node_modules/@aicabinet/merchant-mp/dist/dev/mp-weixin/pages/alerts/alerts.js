"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "alerts",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const counts = common_vendor.ref({ disputes: 0, offline: 0, lowStock: 0, expiry: 0 });
    const items = common_vendor.ref([]);
    function tagClass(type) {
      if (type === "DISPUTE")
        return "dispute";
      if (type === "DEVICE_OFFLINE")
        return "offline";
      if (type === "LOW_STOCK")
        return "stock";
      if (type === "EXPIRY")
        return "expiry";
      return "default";
    }
    async function load() {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      loading.value = true;
      try {
        const [wb, exceptionPage] = await Promise.all([utils_merchantApi.merchantApi.workbench(), utils_merchantApi.merchantApi.exceptions("OPEN")]);
        counts.value = {
          disputes: wb.openDisputes || 0,
          offline: wb.offlineDevices || 0,
          lowStock: wb.lowStockItems || 0,
          expiry: wb.expiryAlerts || 0
        };
        const workbenchItems = (wb.actionItems || []).map((a) => ({
          type: a.type,
          typeLabel: utils_merchantApi.alertTypeLabel(a.type),
          title: a.title,
          detail: a.detail || "",
          deviceId: a.deviceId,
          ticketId: a.ticketId
        }));
        const exceptionItems = (exceptionPage.items || []).map((a) => ({
          type: a.exceptionType,
          typeLabel: utils_merchantApi.alertTypeLabel(a.exceptionType),
          title: a.title,
          detail: a.detail || "",
          deviceId: a.deviceId,
          exceptionId: a.exceptionId
        }));
        items.value = [...exceptionItems, ...workbenchItems].slice(0, 20);
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function handleItem(item) {
      if (item.deviceId) {
        common_vendor.index.navigateTo({ url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}` });
      }
    }
    function isInventoryException(type) {
      return ["INVENTORY_MISMATCH", "LOW_STOCK", "REPLENISHMENT_REQUIRED"].includes(type);
    }
    function resolveInventory(item) {
      if (!item.exceptionId)
        return;
      common_vendor.index.showModal({
        title: "确认完成库存核对",
        editable: true,
        placeholderText: "填写盘点结果或补货说明",
        success: async (res) => {
          const resolution = (res.content || "").trim();
          if (!res.confirm)
            return;
          if (!resolution) {
            common_vendor.index.showToast({ title: "必须填写处理结果", icon: "none" });
            return;
          }
          try {
            await utils_merchantApi.merchantApi.resolveInventoryException(item.exceptionId, resolution);
            common_vendor.index.showToast({ title: "库存异常已处理", icon: "success" });
            await load();
          } catch (e) {
            common_vendor.index.showToast({ title: e instanceof Error ? e.message : "处理失败", icon: "none" });
          }
        }
      });
    }
    common_vendor.onShow(load);
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value)
      } : common_vendor.e({
        d: common_vendor.t(counts.value.disputes),
        e: common_vendor.t(counts.value.offline),
        f: common_vendor.t(counts.value.lowStock),
        g: common_vendor.t(counts.value.expiry),
        h: common_vendor.f(items.value, (a, i, i0) => {
          return common_vendor.e({
            a: common_vendor.t(a.typeLabel),
            b: common_vendor.n(tagClass(a.type)),
            c: common_vendor.t(a.title),
            d: a.detail
          }, a.detail ? {
            e: common_vendor.t(a.detail)
          } : {}, {
            f: a.deviceId
          }, a.deviceId ? {} : {}, {
            g: a.exceptionId && isInventoryException(a.type)
          }, a.exceptionId && isInventoryException(a.type) ? {
            h: common_vendor.o(($event) => resolveInventory(a), i)
          } : {}, {
            i,
            j: common_vendor.o(($event) => handleItem(a), i)
          });
        }),
        i: !items.value.length
      }, !items.value.length ? {} : {}), {
        b: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-102b3300"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/alerts/alerts.vue"]]);
wx.createPage(MiniProgramPage);
