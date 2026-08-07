"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
if (!Array) {
  const _component_empty_state = common_vendor.resolveComponent("empty-state");
  _component_empty_state();
}
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "announcements",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const list = common_vendor.ref([]);
    common_vendor.onShow(() => {
      void load();
    });
    async function load() {
      loading.value = true;
      error.value = "";
      try {
        list.value = await utils_consumerApi.consumerApi.listAnnouncements() || [];
      } catch (e) {
        list.value = [];
        error.value = (e == null ? void 0 : e.message) || "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function goDetail(id) {
      if (!id) return;
      common_vendor.index.navigateTo({ url: `/pages/announcements/detail?id=${id}` });
    }
    function formatTime(t) {
      return common_vendor.formatDateTimeMinute(t, "");
    }
    function previewText(content) {
      const text = String(content || "").replace(/\s+/g, " ").trim();
      return text.length > 72 ? `${text.slice(0, 72)}…` : text;
    }
    function priorityLabel(p) {
      if (p === "URGENT") return "紧急";
      if (p === "HIGH") return "重要";
      return "";
    }
    function priorityClass(p) {
      if (p === "URGENT") return "urgent";
      if (p === "HIGH") return "high";
      return "";
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value),
        d: common_vendor.o(load)
      } : !list.value.length ? {
        f: common_vendor.p({
          icon: "告",
          title: "暂无通知公告",
          hint: "平台维护、活动与规则变更会在这里发布"
        })
      } : {
        g: common_vendor.f(list.value, (item, k0, i0) => {
          return common_vendor.e({
            a: priorityLabel(item.priority)
          }, priorityLabel(item.priority) ? {
            b: common_vendor.t(priorityLabel(item.priority)),
            c: common_vendor.n(priorityClass(item.priority))
          } : {}, {
            d: common_vendor.t(formatTime(item.publishAt)),
            e: common_vendor.t(item.title),
            f: common_vendor.t(previewText(item.content)),
            g: item.announceId,
            h: common_vendor.o(($event) => goDetail(item.announceId), item.announceId)
          });
        })
      }, {
        b: error.value,
        e: !list.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-642ecaa9"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/announcements/announcements.vue"]]);
wx.createPage(MiniProgramPage);
