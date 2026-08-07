"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_formBind = require("../../utils/form-bind.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "report",
  setup(__props) {
    const deviceId = common_vendor.ref("");
    const issueType = common_vendor.ref("DOOR_OPEN");
    const description = common_vendor.ref("");
    const submitting = common_vendor.ref(false);
    const err = common_vendor.ref("");
    const issueOptions = [
      { value: "DOOR_OPEN", label: "打不开门" },
      { value: "DOOR_CLOSE", label: "门关不上" },
      { value: "PRODUCT", label: "商品异常" },
      { value: "PAYMENT", label: "扣款问题" },
      { value: "OTHER", label: "其他" }
    ];
    common_vendor.onLoad((opts) => {
      const fromQuery = (opts == null ? void 0 : opts.deviceId) || "";
      const fromStorage = common_vendor.index.getStorageSync("last_device_id") || "";
      deviceId.value = fromQuery || fromStorage || "";
    });
    function onDeviceInput(e) {
      deviceId.value = utils_formBind.eventInputValue(e);
    }
    function onSubmit() {
      let id = deviceId.value.trim().toUpperCase();
      if (!id) id = utils_formBind.readDomFieldValue("input").toUpperCase();
      deviceId.value = id;
      if (!id) {
        err.value = "请输入柜机编号";
        return;
      }
      if (!/^[A-Z0-9][A-Z0-9\-]{2,31}$/.test(id)) {
        err.value = "柜机编号格式不正确，例如 CAB-001";
        return;
      }
      if (submitting.value) return;
      submitting.value = true;
      err.value = "";
      utils_consumerApi.ensureConsumerAuth().then(async (ok) => {
        if (!ok) {
          submitting.value = false;
          common_vendor.index.navigateTo({
            url: "/pages/login/login?redirect=" + encodeURIComponent("/pages/report/report")
          });
          return;
        }
        try {
          const res = await utils_consumerApi.consumerApi.reportDeviceFault(id, {
            issueType: issueType.value,
            description: description.value.trim() || void 0
          });
          common_vendor.index.showToast({ title: res.message || "已提交", icon: "success" });
          setTimeout(() => common_vendor.index.navigateBack(), 800);
        } catch (e) {
          err.value = e instanceof Error ? e.message : "提交失败";
        } finally {
          submitting.value = false;
        }
      });
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: deviceId.value,
        b: common_vendor.o(onDeviceInput),
        c: common_vendor.f(issueOptions, (item, k0, i0) => {
          return {
            a: common_vendor.t(item.label),
            b: item.value,
            c: issueType.value === item.value ? 1 : "",
            d: common_vendor.o(($event) => issueType.value = item.value, item.value)
          };
        }),
        d: description.value,
        e: common_vendor.o(($event) => description.value = $event.detail.value),
        f: common_vendor.t(submitting.value ? "提交中…" : "提交报修"),
        g: submitting.value,
        h: submitting.value,
        i: common_vendor.o(onSubmit),
        j: err.value
      }, err.value ? {
        k: common_vendor.t(err.value)
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-e12457dc"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/report/report.vue"]]);
wx.createPage(MiniProgramPage);
