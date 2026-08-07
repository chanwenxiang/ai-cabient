"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
if (!Array) {
  const _component_empty_state = common_vendor.resolveComponent("empty-state");
  _component_empty_state();
}
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
    const loadError = common_vendor.ref("");
    const list = common_vendor.ref([]);
    const emptyTitle = common_vendor.computed(() => {
      if (activeTab.value === "UNUSED") return "暂无未使用优惠券";
      if (activeTab.value === "USED") return "暂无已使用优惠券";
      if (activeTab.value === "EXPIRED") return "暂无已过期优惠券";
      return "暂无优惠券";
    });
    const emptyHint = common_vendor.computed(
      () => activeTab.value ? "可切换状态再试，或去热门活动领券" : "可先去逛逛热门活动，或扫码购物后领取优惠券"
    );
    common_vendor.onShow(async () => {
      if (!await utils_consumerApi.ensureConsumerAuth()) {
        common_vendor.index.navigateTo({ url: "/pages/login/login?redirect=" + encodeURIComponent("/pages/coupons/coupons") });
        return;
      }
      await load();
    });
    common_vendor.watch(activeTab, () => load());
    async function load() {
      loading.value = true;
      loadError.value = "";
      try {
        list.value = await utils_consumerApi.consumerApi.myCoupons(activeTab.value || void 0);
      } catch (e) {
        list.value = [];
        loadError.value = (e == null ? void 0 : e.message) || "加载失败";
        common_vendor.index.showToast({ title: loadError.value, icon: "none" });
      } finally {
        loading.value = false;
      }
    }
    function typeText(t) {
      return common_vendor.displayLabel("coupon_type", t, "优惠券");
    }
    function formatTime(t) {
      return common_vendor.formatDateTimeMinute(t, "暂无").slice(0, 10);
    }
    function goShop() {
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    function goMarketing() {
      common_vendor.index.navigateTo({ url: "/pages/marketing/index" });
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
      }, loading.value ? {} : loadError.value ? {
        d: common_vendor.o(load),
        e: common_vendor.p({
          icon: "!",
          title: "优惠券加载失败",
          hint: loadError.value
        })
      } : !list.value.length ? {
        g: common_vendor.o(goShop),
        h: common_vendor.o(goMarketing),
        i: common_vendor.p({
          icon: "券",
          title: emptyTitle.value,
          hint: emptyHint.value
        })
      } : {
        j: common_vendor.f(list.value, (c, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(c.denominationCents)),
            b: common_vendor.t(typeText(c.couponType)),
            c: common_vendor.t(c.couponName),
            d: c.minSpendCents > 0
          }, c.minSpendCents > 0 ? {
            e: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(c.minSpendCents))
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
        c: loadError.value,
        f: !list.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-e7f1cf28"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/coupons/coupons.vue"]]);
wx.createPage(MiniProgramPage);
