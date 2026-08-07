"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_account = require("../../utils/account.js");
const utils_recharge = require("../../utils/recharge.js");
const utils_runtimeFlags = require("../../utils/runtime-flags.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "mine",
  setup(__props) {
    const devTools = utils_runtimeFlags.showDevTools();
    const balanceYuan = common_vendor.ref("--");
    const authed = common_vendor.ref(false);
    const account = common_vendor.ref(null);
    const showTransactions = common_vendor.ref(false);
    const transactionsLoading = common_vendor.ref(false);
    const transactions = common_vendor.ref([]);
    const rechargeLoading = common_vendor.ref(false);
    const mockRechargeEnabled = common_vendor.ref(false);
    const alipayRechargeEnabled = common_vendor.ref(false);
    const wechatRechargeEnabled = common_vendor.ref(false);
    const wechatPayLive = common_vendor.ref(false);
    const configPreauthCents = common_vendor.ref(null);
    const preauthCents = common_vendor.computed(
      () => utils_account.resolveClientPreauthCents({ configPreauthCents: configPreauthCents.value })
    );
    const frozenYuan = common_vendor.computed(() => {
      var _a;
      return common_vendor.fmtMoney(Math.max(0, ((_a = account.value) == null ? void 0 : _a.frozenCents) || 0));
    });
    const totalBalanceYuan = common_vendor.computed(() => {
      var _a;
      return common_vendor.fmtMoney(((_a = account.value) == null ? void 0 : _a.balanceCents) || 0);
    });
    const verified = common_vendor.computed(() => {
      var _a;
      return !!((_a = account.value) == null ? void 0 : _a.verified);
    });
    const payReady = common_vendor.computed(() => utils_account.isPayReady(account.value, null, preauthCents.value));
    const needsSetup = common_vendor.computed(() => !verified.value || !payReady.value);
    const displayName = common_vendor.computed(() => {
      var _a;
      return ((_a = account.value) == null ? void 0 : _a.realName) || "我的账户";
    });
    const avatarText = common_vendor.computed(() => {
      var _a, _b;
      return ((_b = (_a = account.value) == null ? void 0 : _a.realName) == null ? void 0 : _b.slice(0, 1)) || "我";
    });
    const setupHint = common_vendor.computed(() => {
      if (!verified.value) return "完成实名并开通免密支付后即可开门";
      return utils_account.payReadyHint(account.value, null, preauthCents.value);
    });
    function syncBalanceDisplay(acc) {
      if (!acc) {
        balanceYuan.value = "--";
        return;
      }
      balanceYuan.value = common_vendor.fmtMoney(utils_account.availableCents(acc));
    }
    common_vendor.onShow(async () => {
      await utils_consumerApi.ensureConsumerAuth();
      authed.value = !!utils_consumerApi.getConsumerToken();
      try {
        const cfg = await utils_consumerApi.consumerApi.consumerPublicConfig();
        mockRechargeEnabled.value = utils_runtimeFlags.resolveMockEnabled(cfg == null ? void 0 : cfg.mockEnabled);
        alipayRechargeEnabled.value = utils_runtimeFlags.resolveSandboxRecharge(cfg == null ? void 0 : cfg.alipayRechargeEnabled);
        wechatPayLive.value = (cfg == null ? void 0 : cfg.wechatPayLive) === "true";
        wechatRechargeEnabled.value = utils_runtimeFlags.resolveWechatRechargeVisible({
          wechatRechargeEnabled: cfg == null ? void 0 : cfg.wechatRechargeEnabled,
          wechatPayLive: cfg == null ? void 0 : cfg.wechatPayLive
        });
        const p = Number(cfg == null ? void 0 : cfg.preauthCents);
        configPreauthCents.value = Number.isFinite(p) && p > 0 ? p : null;
      } catch {
        mockRechargeEnabled.value = false;
        alipayRechargeEnabled.value = false;
        wechatRechargeEnabled.value = false;
        wechatPayLive.value = false;
      }
      if (!authed.value) {
        syncBalanceDisplay(null);
        account.value = null;
        return;
      }
      try {
        account.value = await utils_consumerApi.consumerApi.account();
        syncBalanceDisplay(account.value);
      } catch (e) {
        syncBalanceDisplay(null);
        account.value = null;
        authed.value = !!utils_consumerApi.getConsumerToken();
        if (!authed.value) {
          common_vendor.index.showToast({ title: "登录已失效，请重新登录", icon: "none" });
          return;
        }
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "账户加载失败", icon: "none" });
      }
      const resumed = await utils_recharge.resumePendingRechargeIfAny();
      if (resumed) {
        try {
          account.value = await utils_consumerApi.consumerApi.account();
          syncBalanceDisplay(account.value);
        } catch {
        }
      }
      if (!authed.value) return;
      if (showTransactions.value) loadTransactions();
    });
    function loadTransactions() {
      transactionsLoading.value = true;
      utils_consumerApi.consumerApi.balanceTransactions(0, 10).then((page) => {
        transactions.value = page.items || [];
      }).catch(() => {
      }).finally(() => {
        transactionsLoading.value = false;
      });
    }
    function toggleTransactions() {
      showTransactions.value = !showTransactions.value;
      if (showTransactions.value) loadTransactions();
    }
    function transactionLabel(type) {
      if (type === "CHARGE") return "购物扣款";
      if (type === "REFUND") return "订单退款";
      if (type === "ADMIN_ADJUST") return "运营调整";
      if (type === "ADJUST_CHARGE") return "订单补扣";
      if (type === "RECHARGE") return "余额充值";
      return "余额变动";
    }
    function formatTransactionTime(value) {
      return common_vendor.formatDateTimeShort(value);
    }
    function formatTransactionAmount(cents) {
      const signed = common_vendor.fmtMoney(Math.abs(cents || 0));
      return `${cents > 0 ? "+" : cents < 0 ? "-" : ""}${signed}`;
    }
    async function refreshAccount() {
      account.value = await utils_consumerApi.consumerApi.account();
      syncBalanceDisplay(account.value);
      if (showTransactions.value) {
        const page = await utils_consumerApi.consumerApi.balanceTransactions(0, 10);
        transactions.value = page.items || [];
      }
    }
    async function onWeChatRecharge() {
      if (rechargeLoading.value) return;
      const confirmed = await new Promise(
        (resolve) => common_vendor.index.showModal({
          title: wechatPayLive.value ? "微信支付充值" : "微信模拟充值",
          content: wechatPayLive.value ? "将调起微信支付充值 ¥20.00。" : "将通过微信 mock 通道充值 ¥20.00 余额，不会真实扣款。",
          confirmText: "确认",
          success: (res) => resolve(!!res.confirm),
          fail: () => resolve(false)
        })
      );
      if (!confirmed) return;
      rechargeLoading.value = true;
      try {
        const key = `mine-wechat-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        await utils_recharge.runWeChatRecharge(2e3, key);
        await refreshAccount();
        common_vendor.index.showToast({ title: "充值成功", icon: "success" });
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "充值失败", icon: "none" });
      } finally {
        rechargeLoading.value = false;
      }
    }
    async function onAlipayRecharge() {
      if (rechargeLoading.value) return;
      const isMock = mockRechargeEnabled.value;
      const confirmed = await new Promise(
        (resolve) => common_vendor.index.showModal({
          title: isMock ? "支付宝模拟充值" : "支付宝沙箱充值",
          content: isMock ? "将模拟支付宝充值 ¥20.00 到余额（无需进件，不会真实扣款）。" : "将跳转支付宝沙箱支付页充值 ¥20.00 余额。",
          confirmText: isMock ? "确认到账" : "去支付",
          success: (res) => resolve(!!res.confirm),
          fail: () => resolve(false)
        })
      );
      if (!confirmed) return;
      rechargeLoading.value = true;
      try {
        const key = `alipay-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const { mode } = await utils_recharge.runAlipayRecharge(2e3, key);
        if (mode === "live") {
          common_vendor.index.showToast({ title: "请在支付宝完成支付", icon: "none" });
          return;
        }
        await refreshAccount();
        common_vendor.index.showToast({ title: "支付宝模拟充值成功", icon: "success" });
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "充值失败", icon: "none" });
      } finally {
        rechargeLoading.value = false;
      }
    }
    async function onMockRecharge() {
      if (rechargeLoading.value) return;
      const confirmed = await new Promise(
        (resolve) => common_vendor.index.showModal({
          title: "确认模拟充值",
          content: "将向当前账户发放 ¥20.00 余额（仅开发联调，不会真实扣款）。",
          confirmText: "确认发放",
          success: (res) => resolve(!!res.confirm),
          fail: () => resolve(false)
        })
      );
      if (!confirmed) return;
      rechargeLoading.value = true;
      try {
        const key = `mock-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const prepay = await utils_consumerApi.consumerApi.createMockRecharge(2e3, key);
        await utils_consumerApi.consumerApi.confirmMockRecharge(prepay.orderId);
        await refreshAccount();
        common_vendor.index.showToast({ title: "余额已到账", icon: "success" });
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
    function goCoupons() {
      common_vendor.index.navigateTo({ url: "/pages/coupons/coupons" });
    }
    function goMember() {
      common_vendor.index.navigateTo({ url: "/pages/member/index" });
    }
    function goMarketing() {
      common_vendor.index.navigateTo({ url: "/pages/marketing/index" });
    }
    function goRecharge() {
      common_vendor.index.navigateTo({ url: "/pages/recharge/recharge" });
    }
    function goReport() {
      const id = common_vendor.index.getStorageSync("last_device_id") || "";
      common_vendor.index.navigateTo({
        url: id ? `/pages/report/report?deviceId=${encodeURIComponent(id)}` : "/pages/report/report"
      });
    }
    function goAnnouncements() {
      common_vendor.index.navigateTo({ url: "/pages/announcements/announcements" });
    }
    function goHelp() {
      common_vendor.index.navigateTo({ url: "/pages/help/help" });
    }
    function goFeedback() {
      const id = common_vendor.index.getStorageSync("last_device_id") || "";
      common_vendor.index.navigateTo({
        url: id ? `/pages/feedback/feedback?deviceId=${encodeURIComponent(id)}` : "/pages/feedback/feedback"
      });
    }
    function onLogout() {
      common_vendor.index.showModal({
        title: "退出登录",
        content: "确定退出当前账户吗？",
        confirmText: "退出",
        success(res) {
          if (!res.confirm) return;
          utils_consumerApi.clearConsumerSession();
          authed.value = false;
          account.value = null;
          balanceYuan.value = "--";
          transactions.value = [];
          showTransactions.value = false;
          common_vendor.index.showToast({ title: "已退出", icon: "none" });
        }
      });
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.t(avatarText.value),
        b: common_vendor.t(authed.value ? displayName.value : "未登录"),
        c: authed.value
      }, authed.value ? {
        d: common_vendor.t(balanceYuan.value),
        e: common_vendor.o(goRecharge)
      } : {}, {
        f: authed.value && frozenYuan.value !== "¥0.00"
      }, authed.value && frozenYuan.value !== "¥0.00" ? {
        g: common_vendor.t(frozenYuan.value),
        h: common_vendor.t(totalBalanceYuan.value)
      } : {}, {
        i: authed.value
      }, authed.value ? {
        j: common_vendor.t(verified.value ? "已实名" : "待实名"),
        k: common_vendor.n(verified.value ? "ok" : "warn"),
        l: common_vendor.t(payReady.value ? "可开门" : "待开通支付"),
        m: common_vendor.n(payReady.value ? "ok" : "warn")
      } : {}, {
        n: !authed.value
      }, !authed.value ? {
        o: common_vendor.o(goLogin)
      } : needsSetup.value ? {
        q: common_vendor.t(setupHint.value),
        r: common_vendor.o(goVerify)
      } : {}, {
        p: needsSetup.value,
        s: common_vendor.o(goOrders),
        t: common_vendor.o(goCoupons),
        v: common_vendor.o(goMember),
        w: common_vendor.o(goRecharge),
        x: common_vendor.o(goIndex),
        y: common_vendor.o(goMarketing),
        z: authed.value
      }, authed.value ? {
        A: common_vendor.t(showTransactions.value ? "∨" : "›"),
        B: common_vendor.o(toggleTransactions)
      } : {}, {
        C: showTransactions.value
      }, showTransactions.value ? common_vendor.e({
        D: transactionsLoading.value
      }, transactionsLoading.value ? {} : !transactions.value.length ? {} : {}, {
        E: !transactions.value.length,
        F: common_vendor.f(transactions.value, (item, k0, i0) => {
          return {
            a: common_vendor.t(transactionLabel(item.businessType)),
            b: common_vendor.t(formatTransactionTime(item.createdAt)),
            c: common_vendor.t(formatTransactionAmount(item.amountCents)),
            d: item.amountCents > 0 ? 1 : "",
            e: item.transactionId
          };
        })
      }) : {}, {
        G: common_vendor.o(goAnnouncements),
        H: common_vendor.o(goHelp),
        I: common_vendor.o(goReport),
        J: common_vendor.o(goFeedback),
        K: common_vendor.unref(devTools) && authed.value
      }, common_vendor.unref(devTools) && authed.value ? common_vendor.e({
        L: wechatRechargeEnabled.value
      }, wechatRechargeEnabled.value ? {
        M: common_vendor.t(wechatPayLive.value ? "微信支付充值" : "微信模拟充值"),
        N: common_vendor.t(wechatPayLive.value ? "调起真实微信支付" : "mock 预下单即时到账 ¥20"),
        O: common_vendor.t(rechargeLoading.value ? "处理中" : "充 ¥20"),
        P: rechargeLoading.value ? 1 : "",
        Q: common_vendor.o(onWeChatRecharge)
      } : {}, {
        R: alipayRechargeEnabled.value
      }, alipayRechargeEnabled.value ? {
        S: common_vendor.t(mockRechargeEnabled.value ? "支付宝模拟充值" : "支付宝沙箱充值"),
        T: common_vendor.t(mockRechargeEnabled.value ? "mock 预下单即时到账 ¥20（无需进件）" : "跳转沙箱收银台充 ¥20"),
        U: common_vendor.t(rechargeLoading.value ? "处理中" : "充 ¥20"),
        V: rechargeLoading.value ? 1 : "",
        W: common_vendor.o(onAlipayRecharge)
      } : {}, {
        X: mockRechargeEnabled.value
      }, mockRechargeEnabled.value ? {
        Y: common_vendor.t(rechargeLoading.value ? "处理中" : "充 ¥20"),
        Z: rechargeLoading.value ? 1 : "",
        aa: common_vendor.o(onMockRecharge)
      } : {}, {
        ab: common_vendor.o(goLogin)
      }) : {}, {
        ac: authed.value
      }, authed.value ? {
        ad: common_vendor.o(onLogout)
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-d41d38da"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/mine/mine.vue"]]);
wx.createPage(MiniProgramPage);
