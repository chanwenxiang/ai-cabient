"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_formBind = require("../../utils/form-bind.js");
const utils_runtimeFlags = require("../../utils/runtime-flags.js");
const common_assets = require("../../common/assets.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "login",
  setup(__props) {
    const redirect = common_vendor.ref("/pages/index/index");
    const showPhoneForm = common_vendor.ref(typeof window !== "undefined");
    const mode = common_vendor.ref("sms");
    const phone = common_vendor.ref("13800138000");
    const password = common_vendor.ref("");
    const code = common_vendor.ref("123456");
    const loading = common_vendor.ref(false);
    const sendingCode = common_vendor.ref(false);
    const wxMode = common_vendor.ref(false);
    const err = common_vendor.ref("");
    const codeCooldown = common_vendor.ref(0);
    const isDev = utils_runtimeFlags.showDevTools();
    let codeTimer = null;
    function clearCodeTimer() {
      if (codeTimer) {
        clearInterval(codeTimer);
        codeTimer = null;
      }
    }
    common_vendor.onLoad((opts) => {
      if (opts == null ? void 0 : opts.redirect) redirect.value = decodeRedirectParam(String(opts.redirect));
    });
    common_vendor.onUnload(() => clearCodeTimer());
    function decodeRedirectParam(raw) {
      let cur = String(raw || "").trim();
      for (let i = 0; i < 3; i++) {
        try {
          const next = decodeURIComponent(cur);
          if (next === cur) break;
          cur = next;
        } catch {
          break;
        }
      }
      if (!cur.startsWith("/")) cur = "/" + cur.replace(/^\/+/, "");
      return cur || "/pages/index/index";
    }
    function finishLogin() {
      const target = redirect.value.split("?")[0];
      if (target.startsWith("/pages/index") || target.startsWith("/pages/orders") || target.startsWith("/pages/mine")) {
        common_vendor.index.switchTab({ url: target });
      } else {
        common_vendor.index.redirectTo({
          url: redirect.value,
          fail: () => common_vendor.index.switchTab({ url: "/pages/index/index" })
        });
      }
    }
    function goBack() {
      common_vendor.index.navigateBack({
        fail: () => common_vendor.index.switchTab({ url: "/pages/index/index" })
      });
    }
    async function onWxLogin() {
      if (loading.value) return;
      loading.value = true;
      wxMode.value = true;
      err.value = "";
      try {
        const ok = await utils_consumerApi.ensureConsumerAuth();
        if (!ok) {
          showPhoneForm.value = true;
          err.value = "当前环境无法微信授权，请使用手机号登录";
          return;
        }
        finishLogin();
      } catch (e) {
        err.value = e instanceof Error ? e.message : "微信授权失败";
        showPhoneForm.value = true;
      } finally {
        loading.value = false;
        wxMode.value = false;
      }
    }
    async function onSendCode() {
      if (codeCooldown.value || sendingCode.value) return;
      let phoneNum = phone.value.trim();
      if (!phoneNum) phoneNum = utils_formBind.readDomFieldValue("input");
      phone.value = phoneNum;
      if (!/^1\d{10}$/.test(phoneNum)) {
        err.value = "请输入11位有效手机号";
        return;
      }
      sendingCode.value = true;
      err.value = "";
      try {
        await utils_consumerApi.sendSmsCode(phoneNum);
        clearCodeTimer();
        codeCooldown.value = 60;
        codeTimer = setInterval(() => {
          codeCooldown.value -= 1;
          if (codeCooldown.value <= 0) clearCodeTimer();
        }, 1e3);
        common_vendor.index.showToast({ title: "验证码已发送", icon: "none" });
      } catch (e) {
        err.value = e instanceof Error ? e.message : "发送失败";
      } finally {
        sendingCode.value = false;
      }
    }
    async function onLogin() {
      if (loading.value) return;
      let phoneNum = phone.value.trim();
      if (!phoneNum) phoneNum = utils_formBind.readDomFieldValue("input");
      phone.value = phoneNum;
      if (!/^1\d{10}$/.test(phoneNum)) {
        err.value = "请输入11位有效手机号";
        return;
      }
      if (mode.value === "password") {
        let pwd = password.value;
        if (!pwd) pwd = utils_formBind.readDomPassword();
        password.value = pwd;
        if (!pwd) {
          err.value = "请输入登录密码";
          return;
        }
      } else {
        let sms = code.value.trim();
        if (!sms) {
          err.value = "请输入验证码";
          return;
        }
        if (!/^\d{4,6}$/.test(sms)) {
          err.value = "请输入4-6位验证码";
          return;
        }
        code.value = sms;
      }
      loading.value = true;
      wxMode.value = false;
      err.value = "";
      try {
        if (mode.value === "password") {
          await utils_consumerApi.consumerPasswordLogin(phoneNum, password.value);
        } else {
          await utils_consumerApi.consumerSmsLogin(phoneNum, code.value.trim());
        }
        try {
          const wxCode = await new Promise((resolve, reject) => {
            common_vendor.index.login({ provider: "weixin", success: (r) => r.code ? resolve(r.code) : reject(), fail: reject });
          });
          await utils_consumerApi.consumerWxLogin(wxCode, phoneNum);
        } catch {
        }
        finishLogin();
      } catch (e) {
        err.value = e instanceof Error ? e.message : "验证失败";
      } finally {
        loading.value = false;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.unref(common_assets.loginBgUrl),
        b: showPhoneForm.value ? 1 : "",
        c: common_vendor.t(loading.value && wxMode.value ? "授权中…" : "微信授权登录"),
        d: loading.value ? 1 : "",
        e: common_vendor.o(onWxLogin),
        f: common_vendor.t(showPhoneForm.value ? "收起手机号登录" : "其他方式"),
        g: showPhoneForm.value ? "true" : "false",
        h: showPhoneForm.value ? "收起手机号登录" : "其他方式登录",
        i: common_vendor.o(($event) => showPhoneForm.value = !showPhoneForm.value),
        j: showPhoneForm.value
      }, showPhoneForm.value ? common_vendor.e({
        k: common_vendor.n(mode.value === "sms" ? "on" : ""),
        l: common_vendor.o(($event) => mode.value = "sms"),
        m: common_vendor.n(mode.value === "password" ? "on" : ""),
        n: common_vendor.o(($event) => mode.value = "password"),
        o: phone.value,
        p: common_vendor.o(($event) => phone.value = common_vendor.unref(utils_formBind.eventInputValue)($event)),
        q: mode.value === "password"
      }, mode.value === "password" ? {
        r: password.value,
        s: common_vendor.o(($event) => password.value = common_vendor.unref(utils_formBind.eventInputValue)($event))
      } : {
        t: code.value,
        v: common_vendor.o(($event) => code.value = common_vendor.unref(utils_formBind.eventInputValue)($event)),
        w: common_vendor.t(sendingCode.value ? "发送中…" : codeCooldown.value ? codeCooldown.value + "s" : "获取验证码"),
        x: !!codeCooldown.value || sendingCode.value ? 1 : "",
        y: common_vendor.o(onSendCode)
      }, {
        z: common_vendor.t(loading.value && !wxMode.value ? "验证中…" : "验证并继续"),
        A: loading.value ? 1 : "",
        B: common_vendor.o(onLogin),
        C: common_vendor.unref(isDev)
      }, common_vendor.unref(isDev) ? {} : {}) : {}, {
        D: common_vendor.o(goBack),
        E: err.value
      }, err.value ? {
        F: common_vendor.t(err.value)
      } : {}, {
        G: showPhoneForm.value ? 1 : ""
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-cdfe2409"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/login/login.vue"]]);
wx.createPage(MiniProgramPage);
