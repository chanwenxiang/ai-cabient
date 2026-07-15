"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "coupons",
  setup(__props) {
    const tabs = [
      { key: "", label: "全部" },
      { key: "UNUSED", label: "未使用" },
      { key: "USED", label: "已使用" },
      { key: "EXPIRED", label: "已过期" }
    ];
    const activeTab = common_vendor.ref("");
    const loading = common_vendor.ref(false);
    const list = common_vendor.ref([]);
    common_vendor.onShow(() => load());
    common_vendor.watch(activeTab, () => load());
    async function load() {
      loading.value = true;
      try {
        const params = activeTab.value ? `?status=${activeTab.value}` : "";
        const res = await utils_consumerApi.get("/api/v2/coupons" + params);
        list.value = res.data ?? [];
      } catch {
        list.value = [];
      } finally {
        loading.value = false;
      }
    }
    function typeText(t) {
      const map = { AMOUNT_OFF: "满减券", PERCENT_OFF: "折扣券", FREE_SHIPPING: "免运费", EXCHANGE: "兑换券" };
      return map[t] || t;
    }
    function formatTime(t) {
      if (!t)
        return "";
      return t.substring(0, 10);
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.f(tabs, (tab, k0, i0) => {
          return {
            a: common_vendor.t(tab.label),
            b: tab.key,
            c: activeTab.value === tab.key ? 1 : "",
            d: common_vendor.o(($event) => activeTab.value = tab.key, tab.key)
          };
        }),
        b: loading.value
      }, loading.value ? {} : !list.value.length ? {} : {
        d: common_vendor.f(list.value, (c, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t((c.denominationCents / 100).toFixed(0)),
            b: common_vendor.t(typeText(c.couponType)),
            c: common_vendor.t(c.couponName),
            d: c.minSpendCents > 0
          }, c.minSpendCents > 0 ? {
            e: common_vendor.t((c.minSpendCents / 100).toFixed(0))
          } : {}, {
            f: common_vendor.t(formatTime(c.expireAt)),
            g: c.status === "USED"
          }, c.status === "USED" ? {} : c.status === "EXPIRED" ? {} : {}, {
            h: c.status === "EXPIRED",
            i: c.couponId,
            j: c.status === "EXPIRED" ? 1 : "",
            k: c.status === "USED" ? 1 : ""
          });
        })
      }, {
        c: !list.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-e7f1cf28"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/coupons/coupons.vue"]]);
wx.createPage(MiniProgramPage);
