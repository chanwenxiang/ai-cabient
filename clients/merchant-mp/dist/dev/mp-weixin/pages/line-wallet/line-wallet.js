"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
if (!Math) {
  EmptyState();
}
const EmptyState = () => "../../components/empty-state.js";
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "line-wallet",
  setup(__props) {
    const loading = common_vendor.ref(false);
    const submitting = common_vendor.ref(false);
    const loadError = common_vendor.ref("");
    const amountYuan = common_vendor.ref("");
    const overview = common_vendor.ref(null);
    function yuan(cents) {
      return ((Number(cents) || 0) / 100).toFixed(2);
    }
    function formatSigned(cents) {
      const n = Number(cents) || 0;
      const abs = Math.abs(n) / 100;
      const sign = n > 0 ? "+" : n < 0 ? "-" : "";
      return `${sign}¥${abs.toFixed(2)}`;
    }
    function withdrawStatus(status) {
      return common_vendor.displayLabel("line_withdraw_status", status, "未知状态");
    }
    function ledgerLabel(type) {
      return common_vendor.displayLabel("wallet_ledger_type", type, common_vendor.emptyDisplay(type, "text"));
    }
    async function load() {
      if (!utils_merchantApi.getToken()) {
        utils_merchantApi.handleUnauthorized();
        return;
      }
      loading.value = true;
      loadError.value = "";
      try {
        overview.value = await utils_merchantApi.merchantApi.lineWallet();
      } catch (e) {
        loadError.value = (e == null ? void 0 : e.message) || "加载失败";
      } finally {
        loading.value = false;
      }
    }
    async function submitWithdraw() {
      const yuanNum = Number(amountYuan.value);
      if (!yuanNum || yuanNum <= 0) {
        common_vendor.index.showToast({ title: "请输入金额", icon: "none" });
        return;
      }
      submitting.value = true;
      try {
        await utils_merchantApi.merchantApi.lineWalletWithdraw({
          amountCents: Math.round(yuanNum * 100),
          requestNo: "MP-" + Date.now()
        });
        common_vendor.index.showToast({ title: "已提交", icon: "success" });
        amountYuan.value = "";
        await load();
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "提交失败", icon: "none" });
      } finally {
        submitting.value = false;
      }
    }
    common_vendor.onShow(load);
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loadError.value
      }, loadError.value ? {
        b: common_vendor.t(loadError.value),
        c: common_vendor.o(load)
      } : {}, {
        d: !loading.value && overview.value && !overview.value.bound
      }, !loading.value && overview.value && !overview.value.bound ? {
        e: common_vendor.p({
          icon: "线",
          title: "未绑定线长身份",
          hint: "线长钱包仅对已绑定的线长成员开放。商户主体提现请使用「商户钱包」"
        })
      } : overview.value && overview.value.bound ? common_vendor.e({
        g: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(overview.value.managerName, "text")),
        h: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(overview.value.phone, "text")),
        i: common_vendor.t(yuan(overview.value.availableCents)),
        j: common_vendor.t(yuan(overview.value.balanceCents)),
        k: common_vendor.t(yuan(overview.value.frozenCents)),
        l: amountYuan.value,
        m: common_vendor.o(($event) => amountYuan.value = $event.detail.value),
        n: submitting.value,
        o: common_vendor.o(submitWithdraw),
        p: common_vendor.f(overview.value.recentWithdraws || [], (w, k0, i0) => {
          return {
            a: common_vendor.t(yuan(w.amountCents)),
            b: common_vendor.t(withdrawStatus(w.status)),
            c: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(w.requestNo, "order")),
            d: w.requestId
          };
        }),
        q: !(overview.value.recentWithdraws || []).length
      }, !(overview.value.recentWithdraws || []).length ? {
        r: common_vendor.p({
          compact: true,
          icon: "提",
          title: "暂无提现记录",
          hint: "提交提现后会出现在这里"
        })
      } : {}, {
        s: common_vendor.f(overview.value.recentLedgers || [], (l, k0, i0) => {
          return {
            a: common_vendor.t(ledgerLabel(l.entryType)),
            b: common_vendor.t(formatSigned(l.amountCents)),
            c: Number(l.amountCents) > 0 ? 1 : "",
            d: Number(l.amountCents) < 0 ? 1 : "",
            e: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(l.remark, "text")),
            f: l.ledgerId
          };
        }),
        t: !(overview.value.recentLedgers || []).length
      }, !(overview.value.recentLedgers || []).length ? {
        v: common_vendor.p({
          compact: true,
          icon: "流",
          title: "暂无流水记录",
          hint: "佣金入账与提现变动会显示在这里"
        })
      } : {}) : {}, {
        f: overview.value && overview.value.bound,
        w: loading.value
      }, loading.value ? {} : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-7a5bda52"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/line-wallet/line-wallet.vue"]]);
wx.createPage(MiniProgramPage);
