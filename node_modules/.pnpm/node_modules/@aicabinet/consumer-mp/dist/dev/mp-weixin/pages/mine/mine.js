"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_recharge = require("../../utils/recharge.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "mine",
  setup(__props) {
    const balanceYuan = common_vendor.ref("-");
    const authed = common_vendor.ref(false);
    const account = common_vendor.ref(null);
    const showTransactions = common_vendor.ref(false);
    const transactionsLoading = common_vendor.ref(false);
    const transactions = common_vendor.ref([]);
    const rechargeLoading = common_vendor.ref(false);
    const mockRechargeEnabled = common_vendor.ref(true);
    const alipayRechargeEnabled = common_vendor.ref(false);
    const verified = common_vendor.computed(() => {
      var _a;
      return !!((_a = account.value) == null ? void 0 : _a.verified);
    });
    const payReady = common_vendor.computed(
      () => {
        var _a;
        return (((_a = account.value) == null ? void 0 : _a.balanceCents) || 0) >= 500;
      }
    );
    const needsSetup = common_vendor.computed(() => !verified.value || !payReady.value);
    const displayName = common_vendor.computed(() => verified.value ? "我的账户" : "我的账户（待实名）");
    const avatarText = common_vendor.computed(() => {
      var _a, _b;
      return ((_b = (_a = account.value) == null ? void 0 : _a.realName) == null ? void 0 : _b.slice(0, 1)) || "我";
    });
    const setupHint = common_vendor.computed(() => {
      if (!verified.value)
        return "需先完成实名认证";
      if (!payReady.value)
        return "测试余额不足，请联系运营人员发放";
      return "";
    });
    common_vendor.onShow(async () => {
      await utils_consumerApi.ensureConsumerAuth();
      authed.value = !!utils_consumerApi.getConsumerToken();
      try {
        const cfg = await utils_consumerApi.consumerApi.consumerPublicConfig();
        mockRechargeEnabled.value = (cfg == null ? void 0 : cfg.mockEnabled) !== "false";
        alipayRechargeEnabled.value = (cfg == null ? void 0 : cfg.alipayRechargeEnabled) === "true";
      } catch {
        mockRechargeEnabled.value = true;
        alipayRechargeEnabled.value = false;
      }
      if (!authed.value) {
        balanceYuan.value = "-";
        account.value = null;
        return;
      }
      const resumed = await utils_recharge.resumePendingRechargeIfAny();
      try {
        account.value = await utils_consumerApi.consumerApi.account();
        balanceYuan.value = ((account.value.balanceCents || 0) / 100).toFixed(2);
        if (resumed) {
          const page = await utils_consumerApi.consumerApi.balanceTransactions(0, 10);
          transactions.value = page.items || [];
        }
        transactionsLoading.value = true;
        utils_consumerApi.consumerApi.balanceTransactions(0, 10).then((page) => {
          transactions.value = page.items || [];
        }).finally(() => {
          transactionsLoading.value = false;
        });
      } catch {
        balanceYuan.value = "-";
        account.value = null;
      }
    });
    function transactionLabel(type) {
      if (type === "CHARGE")
        return "购物扣款";
      if (type === "REFUND")
        return "订单退款";
      if (type === "ADMIN_ADJUST")
        return "运营调整";
      if (type === "ADJUST_CHARGE")
        return "订单补扣";
      if (type === "RECHARGE")
        return "余额充值";
      return "余额变动";
    }
    function formatTransactionTime(value) {
      return common_vendor.formatDateTimeShort(value);
    }
    function formatTransactionAmount(cents) {
      const amount = Math.abs(cents || 0) / 100;
      return `${cents > 0 ? "+" : cents < 0 ? "-" : ""}¥${amount.toFixed(2)}`;
    }
    async function refreshAccount() {
      account.value = await utils_consumerApi.consumerApi.account();
      balanceYuan.value = ((account.value.balanceCents || 0) / 100).toFixed(2);
      const page = await utils_consumerApi.consumerApi.balanceTransactions(0, 10);
      transactions.value = page.items || [];
    }
    async function onAlipayRecharge() {
      var _a, _b;
      if (rechargeLoading.value)
        return;
      const confirmed = await new Promise((resolve) => common_vendor.index.showModal({
        title: "支付宝沙箱充值",
        content: "将跳转支付宝沙箱支付页充值 ¥20.00 测试余额。支付完成后返回本页自动确认到账。",
        confirmText: "去支付",
        success: (res) => resolve(res.confirm),
        fail: () => resolve(false)
      }));
      if (!confirmed)
        return;
      rechargeLoading.value = true;
      try {
        const key = `alipay-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const prepay = await utils_consumerApi.consumerApi.createRechargePrepay("ALIPAY", 2e3, key);
        if (!((_a = prepay.alipayPay) == null ? void 0 : _a.payFormHtml) && !((_b = prepay.alipayPay) == null ? void 0 : _b.payUrl)) {
          throw new Error("未获取到支付宝支付链接，请检查沙箱配置");
        }
        utils_recharge.savePendingRechargeOrder(prepay.orderId);
        utils_recharge.openAlipayPrepay(prepay.alipayPay);
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "充值失败", icon: "none" });
      } finally {
        rechargeLoading.value = false;
      }
    }
    async function onMockRecharge() {
      if (rechargeLoading.value)
        return;
      const confirmed = await new Promise((resolve) => common_vendor.index.showModal({
        title: "确认模拟充值",
        content: "将向当前账户发放 ¥20.00 测试余额，不会发生真实扣款。",
        confirmText: "确认发放",
        success: (res) => resolve(res.confirm),
        fail: () => resolve(false)
      }));
      if (!confirmed)
        return;
      rechargeLoading.value = true;
      try {
        const key = `mock-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const prepay = await utils_consumerApi.consumerApi.createMockRecharge(2e3, key);
        await utils_consumerApi.consumerApi.confirmMockRecharge(prepay.orderId);
        await refreshAccount();
        common_vendor.index.showToast({ title: "测试余额已到账", icon: "success" });
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "充值失败", icon: "none" });
      } finally {
        rechargeLoading.value = false;
      }
    }
    function goVerify() {
      common_vendor.index.navigateTo({ url: "/pages/verify/verify" });
    }
    function goLogin() {
      common_vendor.index.navigateTo({ url: "/pages/login/login?redirect=" + encodeURIComponent("/pages/mine/mine") });
    }
    function goIndex() {
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    function goOrders() {
      common_vendor.index.switchTab({ url: "/pages/orders/orders" });
    }
    function goReport() {
      const id = common_vendor.index.getStorageSync("last_device_id") || "";
      common_vendor.index.navigateTo({
        url: id ? `/pages/report/report?deviceId=${encodeURIComponent(id)}` : "/pages/report/report"
      });
    }
    function onLogout() {
      utils_consumerApi.clearConsumerSession();
      authed.value = false;
      account.value = null;
      balanceYuan.value = "-";
      common_vendor.index.showToast({ title: "已退出", icon: "none" });
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.t(avatarText.value),
        b: common_vendor.t(authed.value ? displayName.value : "游客模式"),
        c: authed.value
      }, authed.value ? {
        d: common_vendor.t(balanceYuan.value)
      } : {}, {
        e: authed.value
      }, authed.value ? {
        f: common_vendor.t(verified.value ? "已实名" : "待实名"),
        g: common_vendor.n(verified.value ? "ok" : "warn"),
        h: common_vendor.t(payReady.value ? "支付已开通" : "待开通支付"),
        i: common_vendor.n(payReady.value ? "ok" : "warn")
      } : {}, {
        j: authed.value && needsSetup.value
      }, authed.value && needsSetup.value ? {
        k: common_vendor.t(setupHint.value),
        l: common_vendor.o(goVerify)
      } : {}, {
        m: authed.value && !verified.value
      }, authed.value && !verified.value ? {
        n: common_vendor.o(goVerify)
      } : {}, {
        o: authed.value && verified.value && !payReady.value
      }, authed.value && verified.value && !payReady.value ? {
        p: common_vendor.o(goVerify)
      } : {}, {
        q: common_vendor.o(goIndex),
        r: common_vendor.o(goOrders),
        s: common_vendor.o(
          //@ts-ignore
          (...args) => _ctx.goCoupons && _ctx.goCoupons(...args)
        ),
        t: common_vendor.o(
          //@ts-ignore
          (...args) => _ctx.goRecharge && _ctx.goRecharge(...args)
        ),
        v: authed.value && alipayRechargeEnabled.value
      }, authed.value && alipayRechargeEnabled.value ? {
        w: common_vendor.t(rechargeLoading.value ? "处理中" : "充 ¥20"),
        x: rechargeLoading.value ? 1 : "",
        y: common_vendor.o(onAlipayRecharge)
      } : {}, {
        z: authed.value && mockRechargeEnabled.value
      }, authed.value && mockRechargeEnabled.value ? {
        A: common_vendor.t(rechargeLoading.value ? "处理中" : "充 ¥20"),
        B: rechargeLoading.value ? 1 : "",
        C: common_vendor.o(onMockRecharge)
      } : {}, {
        D: authed.value
      }, authed.value ? {
        E: common_vendor.o(($event) => showTransactions.value = !showTransactions.value)
      } : {}, {
        F: showTransactions.value
      }, showTransactions.value ? common_vendor.e({
        G: transactionsLoading.value
      }, transactionsLoading.value ? {} : !transactions.value.length ? {} : {}, {
        H: !transactions.value.length,
        I: common_vendor.f(transactions.value, (item, k0, i0) => {
          return {
            a: common_vendor.t(transactionLabel(item.businessType)),
            b: common_vendor.t(formatTransactionTime(item.createdAt)),
            c: common_vendor.t(formatTransactionAmount(item.amountCents)),
            d: item.amountCents > 0 ? 1 : "",
            e: item.transactionId
          };
        })
      }) : {}, {
        J: common_vendor.o(goReport),
        K: common_vendor.o(goLogin),
        L: authed.value
      }, authed.value ? {
        M: common_vendor.o(onLogout)
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-d41d38da"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/mine/mine.vue"]]);
wx.createPage(MiniProgramPage);
