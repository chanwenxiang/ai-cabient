"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const utils_runtimeFlags = require("../../utils/runtime-flags.js");
const common_assets = require("../../common/assets.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "login",
  setup(__props) {
    const isDev = utils_runtimeFlags.showDevTools();
    const phone = common_vendor.ref("13800138001");
    const password = common_vendor.ref("123456");
    const loading = common_vendor.ref(false);
    const err = common_vendor.ref("");
    async function onLogin() {
      if (loading.value) return;
      const phoneNumber = phone.value.trim();
      const pwd = password.value;
      if (!/^1\d{10}$/.test(phoneNumber)) {
        err.value = "请输入11位手机号";
        return;
      }
      if (!pwd) {
        err.value = "请输入登录密码";
        return;
      }
      loading.value = true;
      err.value = "";
      try {
        await utils_merchantApi.merchantLogin(phoneNumber, pwd);
        try {
          const me = await utils_merchantApi.merchantApi.me();
          common_vendor.index.setStorageSync("merchant_me", me);
        } catch {
        }
        common_vendor.index.switchTab({ url: "/pages/home/home" });
      } catch (e) {
        err.value = e instanceof Error ? e.message : "登录失败";
      } finally {
        loading.value = false;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.unref(common_assets.loginBgUrl),
        b: phone.value,
        c: common_vendor.o(($event) => phone.value = $event.detail.value),
        d: common_vendor.o(onLogin),
        e: password.value,
        f: common_vendor.o(($event) => password.value = $event.detail.value),
        g: common_vendor.t(loading.value ? "登录中…" : "登录"),
        h: loading.value ? 1 : "",
        i: common_vendor.o(onLogin),
        j: err.value
      }, err.value ? {
        k: common_vendor.t(err.value)
      } : {}, {
        l: common_vendor.unref(isDev)
      }, common_vendor.unref(isDev) ? {} : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-cdfe2409"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/login/login.vue"]]);
wx.createPage(MiniProgramPage);
