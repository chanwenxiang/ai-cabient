"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
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
    async function onSubmit() {
      const id = deviceId.value.trim().toUpperCase();
      if (!id) {
        err.value = "请输入柜机编号";
        return;
      }
      if (!await utils_consumerApi.ensureConsumerAuth()) {
        err.value = "请先完成微信授权";
        return;
      }
      submitting.value = true;
      err.value = "";
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
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: deviceId.value,
        b: common_vendor.o(($event) => deviceId.value = $event.detail.value),
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
        h: common_vendor.o(onSubmit),
        i: err.value
      }, err.value ? {
        j: common_vendor.t(err.value)
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-e12457dc"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/report/report.vue"]]);
wx.createPage(MiniProgramPage);
