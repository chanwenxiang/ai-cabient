"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_account = require("../../utils/account.js");
const utils_runtimeFlags = require("../../utils/runtime-flags.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "verify",
  setup(__props) {
    const devTools = utils_runtimeFlags.showDevTools();
    const account = common_vendor.ref(null);
    const realName = common_vendor.ref("");
    const idCardLast4 = common_vendor.ref("");
    const verifying = common_vendor.ref(false);
    const signing = common_vendor.ref(false);
    const signingAlipay = common_vendor.ref(false);
    const err = common_vendor.ref("");
    const fromOpen = common_vendor.ref(false);
    const configPreauthCents = common_vendor.ref(null);
    const preauthCents = common_vendor.computed(
      () => utils_account.resolveClientPreauthCents({ configPreauthCents: configPreauthCents.value })
    );
    const needYuan = common_vendor.computed(() => utils_account.preauthYuanLabel(preauthCents.value));
    const balanceYuan = common_vendor.computed(() => common_vendor.fmtMoney(utils_account.availableCents(account.value)));
    const frozenYuan = common_vendor.computed(() => {
      var _a;
      return common_vendor.fmtMoney(Math.max(0, ((_a = account.value) == null ? void 0 : _a.frozenCents) || 0));
    });
    const payReady = common_vendor.computed(() => utils_account.isPayReady(account.value, null, preauthCents.value));
    const wechatReady = common_vendor.computed(() => {
      var _a;
      return !!((_a = account.value) == null ? void 0 : _a.payscoreEnabled);
    });
    const alipayReady = common_vendor.computed(() => {
      var _a;
      return !!((_a = account.value) == null ? void 0 : _a.alipayAgreementEnabled);
    });
    common_vendor.onLoad((opts) => {
      fromOpen.value = (opts == null ? void 0 : opts.from) === "open";
    });
    common_vendor.onShow(async () => {
      err.value = "";
      const ok = await utils_consumerApi.ensureConsumerAuth();
      if (!ok) {
        common_vendor.index.navigateTo({
          url: "/pages/login/login?redirect=" + encodeURIComponent("/pages/verify/verify")
        });
        return;
      }
      try {
        const [acc, cfg] = await Promise.all([
          utils_consumerApi.consumerApi.account(),
          utils_consumerApi.consumerApi.consumerPublicConfig().catch(() => null)
        ]);
        account.value = acc;
        const p = Number(cfg == null ? void 0 : cfg.preauthCents);
        configPreauthCents.value = Number.isFinite(p) && p > 0 ? p : null;
      } catch (e) {
        err.value = e instanceof Error ? e.message : "加载账户失败";
      }
    });
    async function onVerify() {
      const name = realName.value.trim();
      const last4 = idCardLast4.value.trim();
      if (name.length < 2) {
        err.value = "请输入真实姓名";
        return;
      }
      if (!/^\d{4}$/.test(last4)) {
        err.value = "身份证后四位须为 4 位数字";
        return;
      }
      verifying.value = true;
      err.value = "";
      try {
        account.value = await utils_consumerApi.consumerApi.verifyIdentity({ realName: name, idCardLast4: last4 });
        common_vendor.index.showToast({ title: "实名成功", icon: "success" });
        if (payReady.value && fromOpen.value) {
          setTimeout(goShop, 600);
        }
      } catch (e) {
        err.value = e instanceof Error ? e.message : "认证失败";
      } finally {
        verifying.value = false;
      }
    }
    async function onSignPayScore() {
      signing.value = true;
      err.value = "";
      try {
        const res = await utils_consumerApi.consumerApi.signPayScore();
        account.value = await utils_consumerApi.consumerApi.account();
        common_vendor.index.showToast({ title: res.message || "开通成功", icon: "success" });
        if (fromOpen.value) {
          setTimeout(goShop, 600);
        }
      } catch (e) {
        err.value = e instanceof Error ? e.message : "开通失败";
      } finally {
        signing.value = false;
      }
    }
    async function onSignAlipay() {
      signingAlipay.value = true;
      err.value = "";
      try {
        const res = await utils_consumerApi.consumerApi.signAlipayAgreement();
        account.value = await utils_consumerApi.consumerApi.account();
        common_vendor.index.showToast({ title: res.message || "开通成功", icon: "success" });
        if (fromOpen.value) {
          setTimeout(goShop, 600);
        }
      } catch (e) {
        err.value = e instanceof Error ? e.message : "开通失败";
      } finally {
        signingAlipay.value = false;
      }
    }
    function goRecharge() {
      common_vendor.index.navigateTo({ url: "/pages/recharge/recharge" });
    }
    function goShop() {
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    return (_ctx, _cache) => {
      var _a, _b, _c, _d, _e;
      return common_vendor.e({
        a: common_vendor.t(((_a = account.value) == null ? void 0 : _a.verified) ? "✓" : "1"),
        b: ((_b = account.value) == null ? void 0 : _b.verified) ? 1 : "",
        c: ((_c = account.value) == null ? void 0 : _c.verified) ? 1 : "",
        d: common_vendor.t(payReady.value ? "✓" : "2"),
        e: payReady.value ? 1 : "",
        f: !((_d = account.value) == null ? void 0 : _d.verified)
      }, !((_e = account.value) == null ? void 0 : _e.verified) ? common_vendor.e({
        g: realName.value,
        h: common_vendor.o(($event) => realName.value = $event.detail.value),
        i: idCardLast4.value,
        j: common_vendor.o(($event) => idCardLast4.value = $event.detail.value),
        k: common_vendor.t(verifying.value ? "提交中…" : "下一步"),
        l: verifying.value,
        m: common_vendor.o(onVerify),
        n: common_vendor.unref(devTools)
      }, common_vendor.unref(devTools) ? {} : {}, {
        o: err.value
      }, err.value ? {
        p: common_vendor.t(err.value)
      } : {}) : !payReady.value ? common_vendor.e({
        r: common_vendor.t(needYuan.value),
        s: common_vendor.t(balanceYuan.value),
        t: frozenYuan.value !== "¥0.00"
      }, frozenYuan.value !== "¥0.00" ? {
        v: common_vendor.t(frozenYuan.value)
      } : {}, {
        w: common_vendor.t(wechatReady.value ? "已开通" : "未开通"),
        x: common_vendor.t(alipayReady.value ? "已开通" : "未开通"),
        y: common_vendor.t(signing.value ? "开通中…" : "开通微信支付分"),
        z: signing.value,
        A: common_vendor.o(onSignPayScore),
        B: common_vendor.t(signingAlipay.value ? "开通中…" : "开通支付宝免密"),
        C: signingAlipay.value,
        D: common_vendor.o(onSignAlipay),
        E: common_vendor.o(goRecharge),
        F: common_vendor.unref(devTools)
      }, common_vendor.unref(devTools) ? {} : {}, {
        G: err.value
      }, err.value ? {
        H: common_vendor.t(err.value)
      } : {}) : {
        I: common_vendor.o(goShop)
      }, {
        q: !payReady.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-ae6638e8"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/verify/verify.vue"]]);
wx.createPage(MiniProgramPage);
