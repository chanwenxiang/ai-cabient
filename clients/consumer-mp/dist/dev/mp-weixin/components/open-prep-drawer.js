"use strict";
const common_vendor = require("../common/vendor.js");
const utils_consumerApi = require("../utils/consumer-api.js");
const utils_recharge = require("../utils/recharge.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "open-prep-drawer",
  props: {
    account: {}
  },
  emits: ["done", "cancel"],
  setup(__props, { emit: __emit }) {
    const props = __props;
    const emit = __emit;
    const account = common_vendor.ref(props.account);
    const realName = common_vendor.ref("");
    const idCardLast4 = common_vendor.ref("");
    const busy = common_vendor.ref(false);
    const err = common_vendor.ref("");
    const mockRechargeEnabled = common_vendor.ref(true);
    const alipayRechargeEnabled = common_vendor.ref(false);
    common_vendor.watch(
      () => props.account,
      (v) => {
        account.value = v;
      }
    );
    utils_consumerApi.consumerApi.consumerPublicConfig().then((cfg) => {
      mockRechargeEnabled.value = (cfg == null ? void 0 : cfg.mockEnabled) !== "false";
      alipayRechargeEnabled.value = (cfg == null ? void 0 : cfg.alipayRechargeEnabled) === "true";
    }).catch(() => {
      mockRechargeEnabled.value = true;
      alipayRechargeEnabled.value = false;
    });
    const balanceYuan = common_vendor.computed(() => {
      var _a;
      return ((((_a = account.value) == null ? void 0 : _a.balanceCents) || 0) / 100).toFixed(2);
    });
    const payReady = common_vendor.computed(
      () => {
        var _a;
        return (((_a = account.value) == null ? void 0 : _a.balanceCents) || 0) >= 500;
      }
    );
    common_vendor.watch(payReady, (ready) => {
      var _a;
      if (ready && ((_a = account.value) == null ? void 0 : _a.verified)) {
        emit("done");
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
        if (payReady.value)
          emit("done");
      } catch (e) {
        err.value = e instanceof Error ? e.message : "认证失败";
      } finally {
        busy.value = false;
      }
    }
    async function onAlipayRecharge() {
      var _a, _b;
      if (busy.value)
        return;
      busy.value = true;
      err.value = "";
      try {
        const key = `prep-alipay-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const prepay = await utils_consumerApi.consumerApi.createRechargePrepay("ALIPAY", 2e3, key);
        if (!((_a = prepay.alipayPay) == null ? void 0 : _a.payFormHtml) && !((_b = prepay.alipayPay) == null ? void 0 : _b.payUrl)) {
          throw new Error("未获取到支付宝支付链接");
        }
        utils_recharge.savePendingRechargeOrder(prepay.orderId);
        utils_recharge.openAlipayPrepay(prepay.alipayPay);
      } catch (error) {
        err.value = error instanceof Error ? error.message : "充值失败";
      } finally {
        busy.value = false;
      }
    }
    async function onMockRecharge() {
      if (busy.value)
        return;
      const confirmed = await new Promise((resolve) => common_vendor.index.showModal({
        title: "确认模拟充值",
        content: "将发放 ¥20.00 测试余额，不会从微信、支付宝或银行卡扣款。",
        confirmText: "确认发放",
        success: (result) => resolve(result.confirm),
        fail: () => resolve(false)
      }));
      if (!confirmed)
        return;
      busy.value = true;
      err.value = "";
      try {
        const key = `prep-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const order = await utils_consumerApi.consumerApi.createMockRecharge(2e3, key);
        await utils_consumerApi.consumerApi.confirmMockRecharge(order.orderId);
        account.value = await utils_consumerApi.consumerApi.account();
        common_vendor.index.showToast({ title: "测试余额已到账", icon: "success" });
      } catch (error) {
        err.value = error instanceof Error ? error.message : "测试余额发放失败";
      } finally {
        busy.value = false;
      }
    }
    function contactOps() {
      common_vendor.index.showModal({
        title: "联系运营人员",
        content: "请联系柜机所在点位的现场工作人员，并提供柜机编号。运营人员可在后台发放测试余额。",
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
        k: common_vendor.t(busy.value ? "提交中…" : "完成实名认证"),
        l: busy.value,
        m: common_vendor.o(onVerify)
      } : !payReady.value ? common_vendor.e({
        o: common_vendor.t(balanceYuan.value),
        p: mockRechargeEnabled.value
      }, mockRechargeEnabled.value ? {
        q: common_vendor.t(busy.value ? "发放中…" : "模拟充值 ¥20 测试余额"),
        r: busy.value,
        s: busy.value,
        t: common_vendor.o(onMockRecharge)
      } : alipayRechargeEnabled.value ? {
        w: common_vendor.t(busy.value ? "跳转中…" : "支付宝沙箱充值 ¥20"),
        x: busy.value,
        y: busy.value,
        z: common_vendor.o(onAlipayRecharge)
      } : {}, {
        v: alipayRechargeEnabled.value,
        A: common_vendor.o(contactOps)
      }) : {}, {
        n: !payReady.value,
        B: err.value
      }, err.value ? {
        C: common_vendor.t(err.value)
      } : {}, {
        D: common_vendor.o(onCancel),
        E: common_vendor.o(() => {
        }),
        F: common_vendor.o(onCancel)
      });
    };
  }
});
const Component = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-3c6759d9"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/components/open-prep-drawer.vue"]]);
wx.createComponent(Component);
