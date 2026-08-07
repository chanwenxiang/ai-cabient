"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_recharge = require("../../utils/recharge.js");
const utils_runtimeFlags = require("../../utils/runtime-flags.js");
if (!Array) {
  const _component_empty_state = common_vendor.resolveComponent("empty-state");
  _component_empty_state();
}
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "recharge",
  setup(__props) {
    const devTools = utils_runtimeFlags.showDevTools();
    const amounts = [
      { value: 1e3, text: "10" },
      { value: 2e3, text: "20" },
      { value: 5e3, text: "50" },
      { value: 1e4, text: "100" },
      { value: 2e4, text: "200" }
    ];
    const balanceYuan = common_vendor.ref("0.00");
    const selectedAmount = common_vendor.ref(2e3);
    const loading = common_vendor.ref(false);
    const recordsLoading = common_vendor.ref(false);
    const cancelling = common_vendor.ref(false);
    const records = common_vendor.ref([]);
    const alipayRechargeEnabled = common_vendor.ref(false);
    const wechatRechargeEnabled = common_vendor.ref(false);
    const wechatPayLive = common_vendor.ref(false);
    const alipayPayLive = common_vendor.ref(false);
    const paymentModeHint = common_vendor.ref("");
    const mockEnabled = common_vendor.ref(false);
    const pendingCount = common_vendor.computed(() => records.value.filter((r) => r.status === "PENDING").length);
    const visibleRecords = common_vendor.computed(
      () => records.value.filter((r) => r.status !== "CANCELLED").slice(0, 20)
    );
    common_vendor.onShow(async () => {
      await utils_consumerApi.ensureConsumerAuth();
      loadConfig();
      await Promise.all([loadBalance(), loadRecords()]);
      const paid = await utils_recharge.resumePendingRechargeIfAny();
      if (paid) {
        await Promise.all([loadBalance(), loadRecords()]);
      }
    });
    function goBack() {
      const pages = getCurrentPages();
      if (pages.length > 1) {
        common_vendor.index.navigateBack({
          fail: () => common_vendor.index.switchTab({ url: "/pages/mine/mine" })
        });
        return;
      }
      common_vendor.index.switchTab({ url: "/pages/mine/mine" });
    }
    async function loadConfig() {
      try {
        const cfg = await utils_consumerApi.consumerApi.consumerPublicConfig();
        mockEnabled.value = utils_runtimeFlags.resolveMockEnabled(cfg == null ? void 0 : cfg.mockEnabled);
        alipayRechargeEnabled.value = utils_runtimeFlags.resolveSandboxRecharge(cfg == null ? void 0 : cfg.alipayRechargeEnabled);
        wechatPayLive.value = (cfg == null ? void 0 : cfg.wechatPayLive) === "true";
        alipayPayLive.value = (cfg == null ? void 0 : cfg.alipayPayLive) === "true";
        paymentModeHint.value = (cfg == null ? void 0 : cfg.paymentModeHint) || "";
        wechatRechargeEnabled.value = utils_runtimeFlags.resolveWechatRechargeVisible({
          wechatRechargeEnabled: cfg == null ? void 0 : cfg.wechatRechargeEnabled,
          wechatPayLive: cfg == null ? void 0 : cfg.wechatPayLive
        });
      } catch {
        mockEnabled.value = false;
        alipayRechargeEnabled.value = false;
        wechatRechargeEnabled.value = false;
        wechatPayLive.value = false;
        alipayPayLive.value = false;
        paymentModeHint.value = "";
      }
    }
    async function loadBalance() {
      try {
        const acc = await utils_consumerApi.consumerApi.account();
        balanceYuan.value = common_vendor.fmtMoney(acc.balanceCents || 0);
      } catch {
        balanceYuan.value = "--";
      }
    }
    async function loadRecords() {
      recordsLoading.value = true;
      try {
        const res = await utils_consumerApi.get("/api/v2/payment/recharges");
        const data = res.data;
        records.value = Array.isArray(data) ? data : (data == null ? void 0 : data.items) ?? [];
      } catch {
        records.value = [];
      } finally {
        recordsLoading.value = false;
      }
    }
    function formatTime(t) {
      return common_vendor.formatDateTimeMinute(t, "");
    }
    function statusText(s) {
      return common_vendor.displayLabel("recharge_status", s, "未知状态");
    }
    function channelText(channel) {
      return common_vendor.displayLabel("pay_channel", channel, "未知渠道");
    }
    async function cancelOne(orderId) {
      if (cancelling.value) return;
      const confirmed = await new Promise(
        (resolve) => common_vendor.index.showModal({
          title: "取消充值",
          content: "确定取消这笔待支付充值单吗？",
          confirmText: "取消订单",
          cancelText: "保留",
          success: (res) => resolve(!!res.confirm),
          fail: () => resolve(false)
        })
      );
      if (!confirmed) return;
      cancelling.value = true;
      try {
        await utils_consumerApi.consumerApi.cancelRecharge(orderId);
        common_vendor.index.showToast({ title: "已取消", icon: "none" });
        await loadRecords();
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "取消失败", icon: "none" });
      } finally {
        cancelling.value = false;
      }
    }
    async function cancelPendings() {
      if (cancelling.value || !pendingCount.value) return;
      const confirmed = await new Promise(
        (resolve) => common_vendor.index.showModal({
          title: "清理待支付",
          content: `将取消 ${pendingCount.value} 笔未完成的充值单`,
          success: (res) => resolve(!!res.confirm),
          fail: () => resolve(false)
        })
      );
      if (!confirmed) return;
      cancelling.value = true;
      try {
        const pendings = records.value.filter((r) => r.status === "PENDING");
        for (const r of pendings) {
          try {
            await utils_consumerApi.consumerApi.cancelRecharge(r.orderId);
          } catch {
          }
        }
        common_vendor.index.showToast({ title: "已清理", icon: "success" });
        await loadRecords();
      } finally {
        cancelling.value = false;
      }
    }
    async function onRecharge() {
      if (!selectedAmount.value || loading.value) return;
      if (!mockEnabled.value) {
        common_vendor.index.showToast({ title: "模拟充值未开启", icon: "none" });
        return;
      }
      loading.value = true;
      try {
        const key = `recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const prepay = await utils_consumerApi.consumerApi.createMockRecharge(selectedAmount.value, key);
        await utils_consumerApi.consumerApi.confirmMockRecharge(prepay.orderId);
        common_vendor.index.showToast({ title: "充值成功", icon: "success" });
        await loadBalance();
        await loadRecords();
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "充值失败", icon: "none" });
      } finally {
        loading.value = false;
      }
    }
    async function onWeChatRecharge() {
      if (!selectedAmount.value || loading.value) return;
      loading.value = true;
      try {
        const key = `wechat-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const { mode } = await utils_recharge.runWeChatRecharge(selectedAmount.value, key);
        common_vendor.index.showToast({
          title: mode === "live" ? "充值已到账" : "微信模拟充值成功",
          icon: "success"
        });
        await loadBalance();
        await loadRecords();
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "微信充值失败", icon: "none" });
      } finally {
        loading.value = false;
      }
    }
    async function onAlipayRecharge() {
      if (!selectedAmount.value || loading.value) return;
      loading.value = true;
      try {
        const key = `alipay-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const { mode } = await utils_recharge.runAlipayRecharge(selectedAmount.value, key);
        if (mode === "live") {
          common_vendor.index.showToast({ title: "请在支付宝完成支付", icon: "none" });
          return;
        }
        common_vendor.index.showToast({ title: "支付宝模拟充值成功", icon: "success" });
        await loadBalance();
        await loadRecords();
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "支付宝下单失败", icon: "none" });
      } finally {
        loading.value = false;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.t(balanceYuan.value),
        b: common_vendor.f(amounts, (item, k0, i0) => {
          return {
            a: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(item.value)),
            b: item.value,
            c: selectedAmount.value === item.value ? 1 : "",
            d: common_vendor.o(($event) => selectedAmount.value = item.value, item.value)
          };
        }),
        c: wechatPayLive.value || wechatRechargeEnabled.value
      }, wechatPayLive.value || wechatRechargeEnabled.value ? {
        d: common_vendor.t(loading.value ? "处理中…" : selectedAmount.value ? `${wechatPayLive.value ? "微信支付" : "微信充值"} ${common_vendor.unref(common_vendor.fmtMoney)(selectedAmount.value)}` : "微信充值"),
        e: !selectedAmount.value || loading.value,
        f: loading.value,
        g: common_vendor.o(onWeChatRecharge)
      } : {}, {
        h: common_vendor.unref(devTools) && mockEnabled.value
      }, common_vendor.unref(devTools) && mockEnabled.value ? {
        i: common_vendor.t(loading.value ? "充值中…" : selectedAmount.value ? `模拟到账 ${common_vendor.unref(common_vendor.fmtMoney)(selectedAmount.value)}` : "请选择金额"),
        j: !selectedAmount.value || loading.value,
        k: loading.value,
        l: common_vendor.o(onRecharge)
      } : {}, {
        m: common_vendor.unref(devTools) && alipayRechargeEnabled.value
      }, common_vendor.unref(devTools) && alipayRechargeEnabled.value ? {
        n: common_vendor.t(loading.value ? "处理中…" : selectedAmount.value ? `${alipayPayLive.value ? "支付宝沙箱" : "支付宝模拟充值"} ${common_vendor.unref(common_vendor.fmtMoney)(selectedAmount.value)}` : alipayPayLive.value ? "支付宝沙箱" : "支付宝模拟充值"),
        o: !selectedAmount.value || loading.value,
        p: loading.value,
        q: common_vendor.o(onAlipayRecharge)
      } : {}, {
        r: !wechatPayLive.value && !wechatRechargeEnabled.value && !(common_vendor.unref(devTools) && mockEnabled.value)
      }, !wechatPayLive.value && !wechatRechargeEnabled.value && !(common_vendor.unref(devTools) && mockEnabled.value) ? {} : common_vendor.unref(devTools) ? common_vendor.e({
        t: paymentModeHint.value
      }, paymentModeHint.value ? {
        v: common_vendor.t(paymentModeHint.value)
      } : wechatPayLive.value ? {} : wechatRechargeEnabled.value ? {} : {}, {
        w: wechatPayLive.value,
        x: wechatRechargeEnabled.value,
        y: mockEnabled.value
      }, mockEnabled.value ? {} : {}, {
        z: alipayRechargeEnabled.value && alipayPayLive.value
      }, alipayRechargeEnabled.value && alipayPayLive.value ? {} : alipayRechargeEnabled.value ? {} : {}, {
        A: alipayRechargeEnabled.value
      }) : {}, {
        s: common_vendor.unref(devTools),
        B: common_vendor.o(goBack),
        C: pendingCount.value
      }, pendingCount.value ? {
        D: common_vendor.t(pendingCount.value),
        E: common_vendor.o(cancelPendings)
      } : {}, {
        F: recordsLoading.value
      }, recordsLoading.value ? {} : !visibleRecords.value.length ? {
        H: common_vendor.p({
          compact: true,
          title: "暂无充值记录",
          hint: "充值成功后，到账明细会出现在这里"
        })
      } : {}, {
        G: !visibleRecords.value.length,
        I: common_vendor.f(visibleRecords.value, (r, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(r.amountCents || 0)),
            b: common_vendor.t(channelText(r.channel)),
            c: common_vendor.t(formatTime(r.createdAt)),
            d: common_vendor.t(statusText(r.status)),
            e: common_vendor.n(r.status),
            f: r.status === "PENDING"
          }, r.status === "PENDING" ? {
            g: common_vendor.o(($event) => cancelOne(r.orderId), r.orderId)
          } : {}, {
            h: r.orderId
          });
        }),
        J: common_vendor.unref(devTools)
      }, common_vendor.unref(devTools) ? {} : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-d370def1"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/recharge/recharge.vue"]]);
wx.createPage(MiniProgramPage);
