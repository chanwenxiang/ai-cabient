"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "index",
  setup(__props) {
    const profile = common_vendor.ref(null);
    const couponCount = common_vendor.ref(0);
    const progressWidth = common_vendor.computed(() => {
      var _a;
      return `${Math.min(100, Math.max(0, ((_a = profile.value) == null ? void 0 : _a.progressPercent) || 0))}%`;
    });
    const yuanFmt = new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY", maximumFractionDigits: 0 });
    function formatYuan(n) {
      return yuanFmt.format(Number.isFinite(n) ? n : 0);
    }
    const spentText = common_vendor.computed(() => {
      var _a;
      return formatYuan(Number(((_a = profile.value) == null ? void 0 : _a.totalSpent) || 0));
    });
    const benefits = [
      { mark: "券", title: "优惠券立减", desc: "结算时自动选用可用优惠券" },
      { mark: "级", title: "消费升级", desc: "累计消费提升会员等级" },
      { mark: "优", title: "活动优先", desc: "会员专享满减与新客礼，活动页直达" }
    ];
    common_vendor.onShow(async () => {
      if (!await utils_consumerApi.ensureConsumerAuth()) {
        common_vendor.index.navigateTo({ url: "/pages/login/login?redirect=" + encodeURIComponent("/pages/member/index") });
        return;
      }
      await load();
    });
    async function load() {
      try {
        const [p, count] = await Promise.all([
          utils_consumerApi.consumerApi.memberProfile(),
          utils_consumerApi.consumerApi.couponCount()
        ]);
        profile.value = p;
        couponCount.value = Number(count) || 0;
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "加载失败", icon: "none" });
      }
    }
    function goCoupons() {
      common_vendor.index.navigateTo({ url: "/pages/coupons/coupons" });
    }
    function goMarketing() {
      common_vendor.index.navigateTo({ url: "/pages/marketing/index" });
    }
    function goOrders() {
      common_vendor.index.switchTab({ url: "/pages/orders/orders" });
    }
    function goShop() {
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    return (_ctx, _cache) => {
      var _a, _b, _c, _d, _e;
      return common_vendor.e({
        a: common_vendor.t(((_a = profile.value) == null ? void 0 : _a.levelName) || "普通会员"),
        b: common_vendor.t(spentText.value),
        c: progressWidth.value,
        d: (_b = profile.value) == null ? void 0 : _b.nextLevelName
      }, ((_c = profile.value) == null ? void 0 : _c.nextLevelName) ? {
        e: common_vendor.t(profile.value.nextLevelName),
        f: common_vendor.t(formatYuan(profile.value.spentToNextLevel))
      } : {}, {
        g: common_vendor.n("lv-" + (((_d = profile.value) == null ? void 0 : _d.levelCode) || "NORMAL").toLowerCase()),
        h: common_vendor.t(couponCount.value),
        i: common_vendor.o(goCoupons),
        j: common_vendor.o(goMarketing),
        k: common_vendor.o(goOrders),
        l: common_vendor.o(goShop),
        m: common_vendor.f(benefits, (b, k0, i0) => {
          return {
            a: common_vendor.t(b.mark),
            b: common_vendor.t(b.title),
            c: common_vendor.t(b.desc),
            d: b.title
          };
        }),
        n: common_vendor.f(((_e = profile.value) == null ? void 0 : _e.levels) || [], (lv, k0, i0) => {
          var _a2, _b2, _c2;
          return common_vendor.e({
            a: common_vendor.t(lv.levelName),
            b: common_vendor.t(formatYuan(Number(lv.minSpent || 0))),
            c: common_vendor.t(lv.maxSpent != null ? " - " + formatYuan(Number(lv.maxSpent)) : "+"),
            d: lv.levelCode === ((_a2 = profile.value) == null ? void 0 : _a2.levelCode)
          }, lv.levelCode === ((_b2 = profile.value) == null ? void 0 : _b2.levelCode) ? {} : {}, {
            e: lv.levelCode,
            f: lv.levelCode === ((_c2 = profile.value) == null ? void 0 : _c2.levelCode) ? 1 : ""
          });
        })
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-97d9768f"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/member/index.vue"]]);
wx.createPage(MiniProgramPage);
