"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "disputes",
  setup(__props) {
    const tabs = [
      { key: "OPEN", label: "待处理" },
      { key: "PROCESSING", label: "处理中" },
      { key: "RESOLVED", label: "已解决" }
    ];
    const activeTab = common_vendor.ref("OPEN");
    const loading = common_vendor.ref(false);
    const list = common_vendor.ref([]);
    const activeTabLabel = common_vendor.computed(() => {
      var _a;
      return ((_a = tabs.find((t) => t.key === activeTab.value)) == null ? void 0 : _a.label) || "";
    });
    common_vendor.onShow(() => load());
    async function load() {
      loading.value = true;
      try {
        const res = await utils_merchantApi.merchantApi.get("/api/v2/merchant/disputes", {
          params: { status: activeTab.value }
        });
        list.value = res.data ?? [];
      } catch {
        list.value = [];
      } finally {
        loading.value = false;
      }
    }
    function statusText(s) {
      const m = { OPEN: "待处理", PROCESSING: "处理中", RESOLVED: "已解决" };
      return m[s] || s;
    }
    function formatTime(t) {
      if (!t)
        return "";
      return t.substring(0, 16).replace("T", " ");
    }
    function onDetail(item) {
      common_vendor.index.navigateTo({ url: `/pages/dispute-detail/dispute-detail?ticketId=${item.ticketId}` });
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.f(tabs, (t, k0, i0) => {
          return {
            a: common_vendor.t(t.label),
            b: t.key,
            c: activeTab.value === t.key ? 1 : "",
            d: common_vendor.o(($event) => {
              activeTab.value = t.key;
              load();
            }, t.key)
          };
        }),
        b: loading.value
      }, loading.value ? {} : !list.value.length ? {
        d: common_vendor.t(activeTabLabel.value)
      } : {
        e: common_vendor.f(list.value, (item, k0, i0) => {
          var _a;
          return common_vendor.e({
            a: common_vendor.t((_a = item.ticketId) == null ? void 0 : _a.substring(0, 12)),
            b: common_vendor.t(statusText(item.status)),
            c: common_vendor.n(item.status),
            d: common_vendor.t(item.reason || "争议"),
            e: common_vendor.t(item.deviceId || "-"),
            f: common_vendor.t(formatTime(item.createdAt)),
            g: item.lastMessage
          }, item.lastMessage ? {
            h: common_vendor.t(item.lastMessage)
          } : {}, {
            i: item.canReply
          }, item.canReply ? {} : {}, {
            j: item.ticketId,
            k: common_vendor.o(($event) => onDetail(item), item.ticketId)
          });
        })
      }, {
        c: !list.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-a3dc0987"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/disputes/disputes.vue"]]);
wx.createPage(MiniProgramPage);
