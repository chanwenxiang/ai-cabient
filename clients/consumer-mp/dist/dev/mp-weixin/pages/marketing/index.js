"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_uiGlyph = require("../../utils/ui-glyph.js");
if (!Array) {
  const _component_empty_state = common_vendor.resolveComponent("empty-state");
  _component_empty_state();
}
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "index",
  setup(__props) {
    const banners = common_vendor.ref([]);
    const campaigns = common_vendor.ref([]);
    const couponCount = common_vendor.ref(0);
    const authed = common_vendor.ref(false);
    const loading = common_vendor.ref(false);
    const claimingId = common_vendor.ref(null);
    const couponEntrySub = common_vendor.computed(
      () => authed.value ? `${couponCount.value} 张可用 · 结算时自动选用最优券抵扣` : "登录后查看可用优惠券 · 结算时自动选用最优券"
    );
    common_vendor.onShow(() => load());
    async function load() {
      loading.value = true;
      authed.value = !!utils_consumerApi.getConsumerToken();
      try {
        const [b, c] = await Promise.all([
          utils_consumerApi.consumerApi.marketingBanners(),
          utils_consumerApi.consumerApi.marketingCampaigns()
        ]);
        banners.value = (b == null ? void 0 : b.length) ? b : [{
          id: 0,
          title: "领券更优惠",
          subtitle: "满减与新客礼等你领取",
          tone: "mint",
          emoji: "惠",
          ctaPath: "/pages/coupons/coupons"
        }];
        campaigns.value = c || [];
        if (authed.value) {
          try {
            couponCount.value = Number(await utils_consumerApi.consumerApi.couponCount()) || 0;
          } catch {
            couponCount.value = 0;
          }
        } else {
          couponCount.value = 0;
        }
      } catch (e) {
        banners.value = [];
        campaigns.value = [];
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "加载失败", icon: "none" });
      } finally {
        loading.value = false;
      }
    }
    function openPath(path) {
      if (!path) return;
      if (path.startsWith("/pages/index") || path.startsWith("/pages/orders") || path.startsWith("/pages/mine")) {
        common_vendor.index.switchTab({ url: path });
        return;
      }
      common_vendor.index.navigateTo({ url: path });
    }
    async function onCampaignClick(c) {
      if (!(c == null ? void 0 : c.id)) return;
      if (c.type === "POINTS") {
        common_vendor.index.showToast({ title: "该活动类型已下线", icon: "none" });
        return;
      }
      if (c.claimed || claimingId.value === c.id) {
        openPath("/pages/coupons/coupons");
        return;
      }
      if (!await utils_consumerApi.requireConsumerAuth("领取活动需先完成登录", "/pages/marketing/index")) return;
      claimingId.value = c.id;
      try {
        const coupon = await utils_consumerApi.consumerApi.claimCampaign(c.id);
        const name = (coupon == null ? void 0 : coupon.couponName) || "优惠券";
        common_vendor.index.showToast({ title: `已领取 ${name}`, icon: "success" });
        c.claimed = true;
        c.claimable = false;
        c.ctaLabel = "已领取";
        try {
          couponCount.value = await utils_consumerApi.consumerApi.couponCount();
        } catch {
        }
        setTimeout(() => openPath("/pages/coupons/coupons"), 400);
      } catch (e) {
        const msg = (e == null ? void 0 : e.message) || "领取失败";
        common_vendor.index.showToast({ title: msg, icon: "none" });
        if (String(msg).includes("已领取")) {
          c.claimed = true;
          c.ctaLabel = "已领取";
          openPath("/pages/coupons/coupons");
        }
      } finally {
        claimingId.value = null;
      }
    }
    function goCoupons() {
      common_vendor.index.navigateTo({ url: "/pages/coupons/coupons" });
    }
    function goShop() {
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    function formatRange(start, end) {
      const s = start ? String(start).substring(5, 10).replace("-", "/") : "";
      const e = end ? String(end).substring(5, 10).replace("-", "/") : "";
      if (!s && !e) return "长期有效";
      return `${s} - ${e}`;
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.f(banners.value, (b, k0, i0) => {
          return {
            a: common_vendor.t(b.title),
            b: common_vendor.t(b.subtitle),
            c: common_vendor.t(common_vendor.unref(utils_uiGlyph.uiGlyph)(b.emoji, "惠")),
            d: common_vendor.n("tone-" + b.tone),
            e: common_vendor.o(($event) => openPath(b.ctaPath), b.id),
            f: b.id
          };
        }),
        b: common_vendor.t(couponEntrySub.value),
        c: common_vendor.o(goCoupons),
        d: loading.value
      }, loading.value ? {} : !campaigns.value.length ? {
        f: common_vendor.o(goShop),
        g: common_vendor.o(goCoupons),
        h: common_vendor.p({
          icon: "热",
          title: "暂无进行中活动",
          hint: "可先领券，或扫码开门购物"
        })
      } : {
        i: common_vendor.f(campaigns.value, (c, k0, i0) => {
          return {
            a: common_vendor.t(c.typeLabel),
            b: common_vendor.n("tone-" + c.coverColor),
            c: common_vendor.t(c.title),
            d: common_vendor.t(c.description),
            e: common_vendor.t(formatRange(c.startTime, c.endTime)),
            f: common_vendor.t(claimingId.value === c.id ? "领取中…" : c.ctaLabel),
            g: c.claimed || claimingId.value === c.id ? 1 : "",
            h: c.id,
            i: common_vendor.o(($event) => onCampaignClick(c), c.id)
          };
        })
      }, {
        e: !campaigns.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-d5cb2932"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/marketing/index.vue"]]);
wx.createPage(MiniProgramPage);
