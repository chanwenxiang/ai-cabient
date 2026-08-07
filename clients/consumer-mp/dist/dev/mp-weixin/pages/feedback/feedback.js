"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_formBind = require("../../utils/form-bind.js");
if (!Array) {
  const _component_empty_state = common_vendor.resolveComponent("empty-state");
  _component_empty_state();
}
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "feedback",
  setup(__props) {
    const tab = common_vendor.ref("submit");
    const feedbackType = common_vendor.ref("SUGGESTION");
    const content = common_vendor.ref("");
    const contactInfo = common_vendor.ref("");
    const deviceId = common_vendor.ref("");
    const submitting = common_vendor.ref(false);
    const err = common_vendor.ref("");
    const historyLoading = common_vendor.ref(false);
    const historyError = common_vendor.ref("");
    const history = common_vendor.ref([]);
    const typeOptions = common_vendor.dictOptions("feedback_type");
    common_vendor.onLoad((opts) => {
      const fromQuery = (opts == null ? void 0 : opts.deviceId) || "";
      const fromStorage = common_vendor.index.getStorageSync("last_device_id") || "";
      deviceId.value = fromQuery || fromStorage || "";
      if (String((opts == null ? void 0 : opts.tab) || "") === "mine") {
        tab.value = "mine";
      }
    });
    common_vendor.onShow(() => {
      if (tab.value === "mine") void loadHistory();
    });
    function typeLabel(t) {
      return common_vendor.displayLabel("feedback_type", t, "反馈");
    }
    function statusLabel(s) {
      return common_vendor.displayLabel("feedback_status", s, "处理中");
    }
    function statusClass(s) {
      if (s === "HANDLED" || s === "REPLIED") return "ok";
      if (s === "CLOSED") return "muted";
      return "pending";
    }
    function formatTime(t) {
      return common_vendor.formatDateTimeMinute(t, "");
    }
    async function onMineTab() {
      tab.value = "mine";
      await loadHistory();
    }
    async function loadHistory() {
      historyLoading.value = true;
      historyError.value = "";
      try {
        if (!await utils_consumerApi.ensureConsumerAuth()) {
          common_vendor.index.navigateTo({
            url: "/pages/login/login?redirect=" + encodeURIComponent("/pages/feedback/feedback?tab=mine")
          });
          return;
        }
        history.value = await utils_consumerApi.consumerApi.listMyFeedback() || [];
      } catch (e) {
        history.value = [];
        historyError.value = (e == null ? void 0 : e.message) || "加载失败";
      } finally {
        historyLoading.value = false;
      }
    }
    async function onSubmit() {
      let text = content.value.trim();
      if (!text) text = utils_formBind.readDomTextarea();
      if (text.length < 4) {
        err.value = "请至少填写 4 个字";
        return;
      }
      if (submitting.value) return;
      if (!await utils_consumerApi.ensureConsumerAuth()) {
        common_vendor.index.navigateTo({
          url: "/pages/login/login?redirect=" + encodeURIComponent("/pages/feedback/feedback")
        });
        return;
      }
      submitting.value = true;
      err.value = "";
      try {
        let contact = contactInfo.value.trim();
        let device = deviceId.value.trim().toUpperCase();
        if (!contact) contact = utils_formBind.readDomFieldValue("input");
        await utils_consumerApi.consumerApi.submitFeedback({
          feedbackType: feedbackType.value,
          content: text,
          contactInfo: contact || void 0,
          deviceId: device || void 0
        });
        common_vendor.index.showToast({ title: "已提交", icon: "success" });
        content.value = "";
        tab.value = "mine";
        await loadHistory();
      } catch (e) {
        err.value = e instanceof Error ? e.message : "提交失败";
      } finally {
        submitting.value = false;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: tab.value === "submit" ? 1 : "",
        b: common_vendor.o(($event) => tab.value = "submit"),
        c: tab.value === "mine" ? 1 : "",
        d: common_vendor.o(onMineTab),
        e: tab.value === "submit"
      }, tab.value === "submit" ? common_vendor.e({
        f: common_vendor.f(common_vendor.unref(typeOptions), (item, k0, i0) => {
          return {
            a: common_vendor.t(item.label),
            b: item.value,
            c: feedbackType.value === item.value ? 1 : "",
            d: common_vendor.o(($event) => feedbackType.value = item.value, item.value)
          };
        }),
        g: content.value,
        h: common_vendor.o(($event) => content.value = common_vendor.unref(utils_formBind.eventInputValue)($event)),
        i: common_vendor.t(content.value.length),
        j: contactInfo.value,
        k: common_vendor.o(($event) => contactInfo.value = common_vendor.unref(utils_formBind.eventInputValue)($event)),
        l: deviceId.value,
        m: common_vendor.o(($event) => deviceId.value = common_vendor.unref(utils_formBind.eventInputValue)($event)),
        n: common_vendor.t(submitting.value ? "提交中…" : "提交反馈"),
        o: submitting.value,
        p: submitting.value,
        q: common_vendor.o(onSubmit),
        r: err.value
      }, err.value ? {
        s: common_vendor.t(err.value)
      } : {}) : common_vendor.e({
        t: historyLoading.value
      }, historyLoading.value ? {} : historyError.value ? {
        w: common_vendor.t(historyError.value),
        x: common_vendor.o(loadHistory)
      } : !history.value.length ? {
        z: common_vendor.p({
          icon: "馈",
          title: "暂无反馈记录",
          hint: "提交后可在这里查看处理进度与回复"
        })
      } : {
        A: common_vendor.f(history.value, (item, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(typeLabel(item.feedbackType)),
            b: common_vendor.t(statusLabel(item.status)),
            c: common_vendor.n(statusClass(item.status)),
            d: common_vendor.t(item.content),
            e: common_vendor.t(formatTime(item.createdAt)),
            f: item.reply
          }, item.reply ? common_vendor.e({
            g: common_vendor.t(item.reply),
            h: item.handledAt
          }, item.handledAt ? {
            i: common_vendor.t(formatTime(item.handledAt))
          } : {}) : {}, {
            j: item.feedbackId
          });
        })
      }, {
        v: historyError.value,
        y: !history.value.length
      }));
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-6cdbb6ab"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/feedback/feedback.vue"]]);
wx.createPage(MiniProgramPage);
