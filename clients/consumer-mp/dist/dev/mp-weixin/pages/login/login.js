"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const common_assets = require("../../common/assets.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "login",
  setup(__props) {
    const redirect = common_vendor.ref("/pages/index/index");
    const mode = common_vendor.ref("sms");
    const phone = common_vendor.ref("");
    const password = common_vendor.ref("");
    const code = common_vendor.ref("");
    const loading = common_vendor.ref(false);
    const err = common_vendor.ref("");
    const codeCooldown = common_vendor.ref(0);
    let codeTimer = null;
    common_vendor.onLoad((opts) => {
      if (opts == null ? void 0 : opts.redirect)
        redirect.value = decodeURIComponent(String(opts.redirect));
    });
    function goBack() {
      if (redirect.value.startsWith("/pages/index") || redirect.value.startsWith("/pages/orders") || redirect.value.startsWith("/pages/mine")) {
        common_vendor.index.switchTab({ url: redirect.value.split("?")[0] });
      } else {
        common_vendor.index.redirectTo({ url: redirect.value });
      }
    }
    async function onSendCode() {
      if (codeCooldown.value || !phone.value.trim())
        return;
      try {
        await utils_consumerApi.sendSmsCode(phone.value.trim());
        codeCooldown.value = 60;
        codeTimer = setInterval(() => {
          codeCooldown.value -= 1;
          if (codeCooldown.value <= 0 && codeTimer)
            clearInterval(codeTimer);
        }, 1e3);
        common_vendor.index.showToast({ title: "验证码已发送", icon: "none" });
      } catch (e) {
        err.value = e instanceof Error ? e.message : "发送失败";
      }
    }
    async function onLogin() {
      loading.value = true;
      err.value = "";
      try {
        if (mode.value === "password") {
          await utils_consumerApi.consumerPasswordLogin(phone.value.trim(), password.value);
        } else {
          await utils_consumerApi.consumerSmsLogin(phone.value.trim(), code.value.trim());
        }
        try {
          const wxCode = await new Promise((resolve, reject) => {
            common_vendor.index.login({ provider: "weixin", success: (r) => r.code ? resolve(r.code) : reject(), fail: reject });
          });
          await utils_consumerApi.consumerWxLogin(wxCode, phone.value.trim());
        } catch {
        }
        goBack();
      } catch (e) {
        err.value = e instanceof Error ? e.message : "验证失败";
      } finally {
        loading.value = false;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.unref(common_assets.loginBgUrl),
        b: common_vendor.n(mode.value === "sms" ? "on" : ""),
        c: common_vendor.o(($event) => mode.value = "sms"),
        d: common_vendor.n(mode.value === "password" ? "on" : ""),
        e: common_vendor.o(($event) => mode.value = "password"),
        f: phone.value,
        g: common_vendor.o(($event) => phone.value = $event.detail.value),
        h: mode.value === "password"
      }, mode.value === "password" ? {
        i: password.value,
        j: common_vendor.o(($event) => password.value = $event.detail.value)
      } : {
        k: code.value,
        l: common_vendor.o(($event) => code.value = $event.detail.value),
        m: common_vendor.t(codeCooldown.value ? codeCooldown.value + "s" : "获取验证码"),
        n: common_vendor.o(onSendCode)
      }, {
        o: common_vendor.t(loading.value ? "验证中…" : "验证并继续"),
        p: common_vendor.o(onLogin),
        q: common_vendor.o(goBack),
        r: err.value
      }, err.value ? {
        s: common_vendor.t(err.value)
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-cdfe2409"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/login/login.vue"]]);
wx.createPage(MiniProgramPage);
