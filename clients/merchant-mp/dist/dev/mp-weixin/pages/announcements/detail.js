"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "detail",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const item = common_vendor.ref(null);
    let announceId = 0;
    common_vendor.onLoad((query) => {
      announceId = Number((query == null ? void 0 : query.id) || 0);
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      void load();
    });
    async function load() {
      if (!announceId) {
        loading.value = false;
        error.value = "公告不存在";
        return;
      }
      loading.value = true;
      error.value = "";
      try {
        item.value = await utils_merchantApi.merchantApi.getAnnouncement(announceId);
      } catch (e) {
        item.value = null;
        error.value = (e == null ? void 0 : e.message) || "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function formatTime(t) {
      return common_vendor.formatDateTimeMinute(t, "暂无");
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
      } : item.value ? common_vendor.e({
        f: priorityLabel(item.value.priority)
      }, priorityLabel(item.value.priority) ? {
        g: common_vendor.t(priorityLabel(item.value.priority)),
        h: common_vendor.n(priorityClass(item.value.priority))
      } : {}, {
        i: common_vendor.t(formatTime(item.value.publishAt)),
        j: common_vendor.t(item.value.title),
        k: common_vendor.t(item.value.content)
      }) : {}, {
        b: error.value,
        e: item.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-4d11c301"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/announcements/detail.vue"]]);
wx.createPage(MiniProgramPage);
