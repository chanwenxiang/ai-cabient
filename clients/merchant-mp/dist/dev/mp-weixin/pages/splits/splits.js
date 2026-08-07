"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
if (!Array) {
  const _easycom_empty_state2 = common_vendor.resolveComponent("empty-state");
  _easycom_empty_state2();
}
const _easycom_empty_state = () => "../../components/empty-state.js";
if (!Math) {
  _easycom_empty_state();
}
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "splits",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const tab = common_vendor.ref("FAILED");
    const list = common_vendor.ref([]);
    common_vendor.onLoad((query) => {
      const status = String((query == null ? void 0 : query.status) || "").toUpperCase();
      if (status === "ALL") tab.value = "ALL";
      else tab.value = "FAILED";
    });
    common_vendor.onShow(() => {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      void load();
    });
    common_vendor.onPullDownRefresh(async () => {
      try {
        await load();
      } finally {
        common_vendor.index.stopPullDownRefresh();
      }
    });
    function switchTab(next) {
      if (tab.value === next) return;
      tab.value = next;
      void load();
    }
    function money(cents = 0) {
      return ((Number(cents) || 0) / 100).toFixed(2);
    }
    function formatTime(t) {
      return common_vendor.formatDateTimeMinute(t, "暂无");
    }
    function statusLabel(status) {
      return common_vendor.displayLabel("split_status", status, "未知状态");
    }
    function statusClass(status) {
      const s = String(status || "").toUpperCase();
      if (s === "WECHAT_FAILED" || s === "FAILED") return "fail";
      if (s === "SUCCESS" || s === "SETTLED") return "ok";
      if (s === "LEDGER_ONLY") return "warn";
      return "warn";
    }
    async function load() {
      loading.value = true;
      error.value = "";
      try {
        await refreshMe();
        if (!utils_merchantApi.hasPerm(me.value, "merchant:splits:list")) {
          error.value = "无分账明细权限";
          list.value = [];
          return;
        }
        if (tab.value === "ALL") {
          const res = await utils_merchantApi.merchantApi.revenueSplits(0, 100);
          list.value = (res == null ? void 0 : res.items) || [];
        } else {
          const [a, b] = await Promise.all([
            utils_merchantApi.merchantApi.revenueSplits(0, 50, "WECHAT_FAILED"),
            utils_merchantApi.merchantApi.revenueSplits(0, 50, "FAILED")
          ]);
          const merged = [...(a == null ? void 0 : a.items) || [], ...(b == null ? void 0 : b.items) || []];
          const seen = /* @__PURE__ */ new Set();
          list.value = merged.filter((x) => {
            if (!(x == null ? void 0 : x.splitId) || seen.has(x.splitId)) return false;
            seen.add(x.splitId);
            return true;
          }).sort((x, y) => String(y.createdAt || "").localeCompare(String(x.createdAt || "")));
        }
      } catch (e) {
        if (!common_vendor.index.getStorageSync("merchant_token")) return;
        me.value = common_vendor.index.getStorageSync("merchant_me") || null;
        list.value = [];
        error.value = (e == null ? void 0 : e.message) || "加载失败";
      } finally {
        loading.value = false;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: tab.value === "FAILED" ? 1 : "",
        b: common_vendor.o(($event) => switchTab("FAILED")),
        c: tab.value === "ALL" ? 1 : "",
        d: common_vendor.o(($event) => switchTab("ALL")),
        e: loading.value
      }, loading.value ? {} : error.value ? {
        g: common_vendor.t(error.value),
        h: common_vendor.o(load)
      } : !list.value.length ? {
        j: common_vendor.p({
          icon: "💱",
          title: tab.value === "FAILED" ? "暂无分账异常" : "暂无分账记录",
          hint: "订单分账后会出现在这里；失败单请核对微信收款账户"
        })
      } : {
        k: common_vendor.f(list.value, (s, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(statusLabel(s.status)),
            b: common_vendor.n(statusClass(s.status)),
            c: common_vendor.t(formatTime(s.createdAt)),
            d: common_vendor.t(s.orderId),
            e: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(s.deviceId, "device")),
            f: common_vendor.t(money(s.merchantCents)),
            g: s.failureReason
          }, s.failureReason ? {
            h: common_vendor.t(s.failureReason)
          } : {}, {
            i: s.splitId
          });
        })
      }, {
        f: error.value,
        i: !list.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-e0383a2c"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/splits/splits.vue"]]);
wx.createPage(MiniProgramPage);
