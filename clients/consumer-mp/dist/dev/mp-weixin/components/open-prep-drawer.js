"use strict";
const common_vendor = require("../common/vendor.js");
const utils_consumerApi = require("../utils/consumer-api.js");
const utils_account = require("../utils/account.js");
const utils_recharge = require("../utils/recharge.js");
const utils_runtimeFlags = require("../utils/runtime-flags.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "open-prep-drawer",
  props: {
    account: {},
    entryChannel: {},
    devicePreauthCents: {}
  },
  emits: ["done", "cancel"],
  setup(__props, { emit: __emit }) {
    const props = __props;
    const emit = __emit;
    const devTools = utils_runtimeFlags.showDevTools();
    const account = common_vendor.ref(props.account);
    const realName = common_vendor.ref("");
    const idCardLast4 = common_vendor.ref("");
    const busy = common_vendor.ref(false);
    const err = common_vendor.ref("");
    const mockRechargeEnabled = common_vendor.ref(false);
    const alipayRechargeEnabled = common_vendor.ref(false);
    const wechatRechargeEnabled = common_vendor.ref(false);
    const wechatPayLive = common_vendor.ref(false);
    const payScoreSignEnabled = common_vendor.ref(true);
    const configPreauthCents = common_vendor.ref(null);
    const pickedChannel = common_vendor.ref(utils_account.normalizeEntryChannel(props.entryChannel));
    common_vendor.watch(
      () => props.account,
      (v) => {
        account.value = v;
      }
    );
    common_vendor.watch(
      () => props.entryChannel,
      (v) => {
        const n = utils_account.normalizeEntryChannel(v);
        if (n) pickedChannel.value = n;
      }
    );
    utils_consumerApi.consumerApi.consumerPublicConfig().then((cfg) => {
      mockRechargeEnabled.value = utils_runtimeFlags.resolveMockEnabled(cfg == null ? void 0 : cfg.mockEnabled);
      alipayRechargeEnabled.value = utils_runtimeFlags.resolveSandboxRecharge(cfg == null ? void 0 : cfg.alipayRechargeEnabled);
      wechatPayLive.value = (cfg == null ? void 0 : cfg.wechatPayLive) === "true";
      wechatRechargeEnabled.value = utils_runtimeFlags.resolveWechatRechargeVisible({
        wechatRechargeEnabled: cfg == null ? void 0 : cfg.wechatRechargeEnabled,
        wechatPayLive: cfg == null ? void 0 : cfg.wechatPayLive
      });
      payScoreSignEnabled.value = (cfg == null ? void 0 : cfg.payScoreSignEnabled) !== "false";
      const p = Number(cfg == null ? void 0 : cfg.preauthCents);
      configPreauthCents.value = Number.isFinite(p) && p > 0 ? p : null;
    }).catch(() => {
      mockRechargeEnabled.value = false;
      alipayRechargeEnabled.value = false;
      wechatRechargeEnabled.value = false;
      wechatPayLive.value = false;
      payScoreSignEnabled.value = true;
    });
    const entryChannel = common_vendor.computed(() => utils_account.normalizeEntryChannel(props.entryChannel) || pickedChannel.value);
    const preauthCents = common_vendor.computed(
      () => utils_account.resolveClientPreauthCents({
        devicePreauthCents: props.devicePreauthCents,
        configPreauthCents: configPreauthCents.value
      })
    );
    const needYuan = common_vendor.computed(() => utils_account.preauthYuanLabel(preauthCents.value));
    const balanceYuan = common_vendor.computed(() => common_vendor.fmtMoney(utils_account.availableCents(account.value)));
    const frozenYuan = common_vendor.computed(() => {
      var _a;
      return common_vendor.fmtMoney(Math.max(0, ((_a = account.value) == null ? void 0 : _a.frozenCents) || 0));
    });
    const payReady = common_vendor.computed(() => utils_account.isPayReady(account.value, entryChannel.value, preauthCents.value));
    const payReadyHintText = common_vendor.computed(() => utils_account.payReadyHint(account.value, entryChannel.value, preauthCents.value));
    const balanceInsufficient = common_vendor.computed(() => {
      if (!account.value || payReady.value) return false;
      return utils_account.availableCents(account.value) < preauthCents.value;
    });
    const payDesc = common_vendor.computed(() => {
      const c = entryChannel.value;
      if (c === "WECHAT") return "推荐开通微信支付分：关门后自动扣款，无需每次确认。";
      if (c === "ALIPAY") return "推荐开通支付宝免密：关门后自动扣款，无需每次确认。";
      return "请开通对应渠道免密支付；可用余额满足预授权也可临时开门。";
    });
    const showWechatSign = common_vendor.computed(
      () => payScoreSignEnabled.value && (!entryChannel.value || entryChannel.value === "WECHAT")
    );
    const showAlipaySign = common_vendor.computed(() => !entryChannel.value || entryChannel.value === "ALIPAY");
    function goRechargePage() {
      emit("cancel");
      common_vendor.index.navigateTo({ url: "/pages/recharge/recharge" });
    }
    common_vendor.watch(payReady, (ready) => {
      var _a;
      if (ready && ((_a = account.value) == null ? void 0 : _a.verified)) {
        emit("done", entryChannel.value);
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
      busy.value = true;
      err.value = "";
      try {
        account.value = await utils_consumerApi.consumerApi.verifyIdentity({ realName: name, idCardLast4: last4 });
        if (payReady.value) emit("done", entryChannel.value);
      } catch (e) {
        err.value = e instanceof Error ? e.message : "认证失败";
      } finally {
        busy.value = false;
      }
    }
    async function onSignPayScore() {
      if (busy.value) return;
      if (!pickedChannel.value && !props.entryChannel) pickedChannel.value = "WECHAT";
      busy.value = true;
      err.value = "";
      try {
        await utils_consumerApi.consumerApi.signPayScore();
        account.value = await utils_consumerApi.consumerApi.account();
        common_vendor.index.showToast({ title: "支付分已开通", icon: "success" });
        if (payReady.value) emit("done", entryChannel.value || "WECHAT");
      } catch (e) {
        err.value = e instanceof Error ? e.message : "开通失败";
      } finally {
        busy.value = false;
      }
    }
    async function onSignAlipay() {
      if (busy.value) return;
      if (!pickedChannel.value && !props.entryChannel) pickedChannel.value = "ALIPAY";
      busy.value = true;
      err.value = "";
      try {
        await utils_consumerApi.consumerApi.signAlipayAgreement();
        account.value = await utils_consumerApi.consumerApi.account();
        common_vendor.index.showToast({ title: "支付宝免密已开通", icon: "success" });
        if (payReady.value) emit("done", entryChannel.value || "ALIPAY");
      } catch (e) {
        err.value = e instanceof Error ? e.message : "开通失败";
      } finally {
        busy.value = false;
      }
    }
    async function onWeChatRecharge() {
      if (busy.value) return;
      busy.value = true;
      err.value = "";
      try {
        const key = `prep-wechat-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        await utils_recharge.runWeChatRecharge(2e3, key);
        account.value = await utils_consumerApi.consumerApi.account();
        common_vendor.index.showToast({ title: "充值成功", icon: "success" });
      } catch (error) {
        err.value = error instanceof Error ? error.message : "充值失败";
      } finally {
        busy.value = false;
      }
    }
    async function onAlipayRecharge() {
      if (busy.value) return;
      busy.value = true;
      err.value = "";
      try {
        const key = `prep-alipay-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const { mode } = await utils_recharge.runAlipayRecharge(2e3, key);
        if (mode === "live") {
          common_vendor.index.showToast({ title: "请在支付宝完成支付", icon: "none" });
          return;
        }
        account.value = await utils_consumerApi.consumerApi.account();
        common_vendor.index.showToast({ title: "支付宝模拟充值成功", icon: "success" });
      } catch (error) {
        err.value = error instanceof Error ? error.message : "充值失败";
      } finally {
        busy.value = false;
      }
    }
    async function onMockRecharge() {
      if (busy.value) return;
      const confirmed = await new Promise((resolve) => common_vendor.index.showModal({
        title: "确认模拟充值",
        content: "将发放 ¥20.00 余额（仅开发联调，不会真实扣款）。",
        confirmText: "确认发放",
        success: (result) => resolve(result.confirm),
        fail: () => resolve(false)
      }));
      if (!confirmed) return;
      busy.value = true;
      err.value = "";
      try {
        const key = `prep-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const order = await utils_consumerApi.consumerApi.createMockRecharge(2e3, key);
        await utils_consumerApi.consumerApi.confirmMockRecharge(order.orderId);
        account.value = await utils_consumerApi.consumerApi.account();
        common_vendor.index.showToast({ title: "余额已到账", icon: "success" });
      } catch (error) {
        err.value = error instanceof Error ? error.message : "余额发放失败";
      } finally {
        busy.value = false;
      }
    }
    function contactOps() {
      common_vendor.index.showModal({
        title: "联系运营人员",
        content: "请联系柜机所在点位的现场工作人员，并提供柜机编号。运营人员可在后台发放余额。",
        showCancel: false,
        confirmText: "我知道了"
      });
    }
    function onCancel() {
      emit("cancel");
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
      }, !((_e = account.value) == null ? void 0 : _e.verified) ? {
        g: realName.value,
        h: common_vendor.o(($event) => realName.value = $event.detail.value),
        i: idCardLast4.value,
        j: common_vendor.o(($event) => idCardLast4.value = $event.detail.value),
        k: common_vendor.t(busy.value ? "提交中…" : "下一步"),
        l: busy.value,
        m: common_vendor.o(onVerify)
      } : !payReady.value ? common_vendor.e({
        o: common_vendor.t(payDesc.value),
        p: !entryChannel.value
      }, !entryChannel.value ? {
        q: pickedChannel.value === "WECHAT" ? 1 : "",
        r: common_vendor.o(($event) => pickedChannel.value = "WECHAT"),
        s: pickedChannel.value === "ALIPAY" ? 1 : "",
        t: common_vendor.o(($event) => pickedChannel.value = "ALIPAY")
      } : {}, {
        v: showWechatSign.value
      }, showWechatSign.value ? {
        w: common_vendor.t(busy.value ? "开通中…" : "开通微信支付分"),
        x: busy.value,
        y: busy.value,
        z: common_vendor.o(onSignPayScore)
      } : {}, {
        A: showAlipaySign.value
      }, showAlipaySign.value ? {
        B: common_vendor.t(busy.value ? "开通中…" : "开通支付宝免密"),
        C: busy.value,
        D: busy.value,
        E: common_vendor.o(onSignAlipay)
      } : {}, {
        F: common_vendor.t(balanceYuan.value),
        G: frozenYuan.value !== "¥0.00"
      }, frozenYuan.value !== "¥0.00" ? {
        H: common_vendor.t(frozenYuan.value)
      } : {}, {
        I: common_vendor.t(payReadyHintText.value),
        J: balanceInsufficient.value
      }, balanceInsufficient.value ? {
        K: common_vendor.t(needYuan.value)
      } : {}, {
        L: wechatPayLive.value || common_vendor.unref(devTools) && wechatRechargeEnabled.value
      }, wechatPayLive.value || common_vendor.unref(devTools) && wechatRechargeEnabled.value ? {
        M: common_vendor.t(busy.value ? "处理中…" : wechatPayLive.value ? "微信支付充值 ¥20" : "微信模拟充值 ¥20"),
        N: busy.value,
        O: busy.value,
        P: common_vendor.o(onWeChatRecharge)
      } : {}, {
        Q: common_vendor.unref(devTools) && mockRechargeEnabled.value
      }, common_vendor.unref(devTools) && mockRechargeEnabled.value ? {
        R: common_vendor.t(busy.value ? "发放中…" : "模拟充值 ¥20"),
        S: busy.value,
        T: busy.value,
        U: common_vendor.o(onMockRecharge)
      } : {}, {
        V: common_vendor.unref(devTools) && alipayRechargeEnabled.value
      }, common_vendor.unref(devTools) && alipayRechargeEnabled.value ? {
        W: common_vendor.t(busy.value ? "处理中…" : mockRechargeEnabled.value ? "支付宝模拟充值 ¥20" : "支付宝沙箱充值 ¥20"),
        X: busy.value,
        Y: busy.value,
        Z: common_vendor.o(onAlipayRecharge)
      } : {}, {
        aa: common_vendor.o(goRechargePage),
        ab: common_vendor.o(contactOps)
      }) : {}, {
        n: !payReady.value,
        ac: err.value
      }, err.value ? {
        ad: common_vendor.t(err.value)
      } : {}, {
        ae: common_vendor.o(onCancel),
        af: common_vendor.o(() => {
        }),
        ag: common_vendor.o(onCancel)
      });
    };
  }
});
const Component = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-3c6759d9"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/components/open-prep-drawer.vue"]]);
wx.createComponent(Component);
