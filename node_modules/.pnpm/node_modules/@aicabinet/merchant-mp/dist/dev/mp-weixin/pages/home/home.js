"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "home",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const meName = common_vendor.ref("");
    const merchantNames = common_vendor.ref("");
    const stats = common_vendor.ref({});
    const revenueToday = common_vendor.ref("-");
    const incomeToday = common_vendor.ref("-");
    const trendBars = common_vendor.ref([]);
    const pendingCount = common_vendor.ref(0);
    const offlineCount = common_vendor.ref(0);
    const actionItems = common_vendor.ref([]);
    function fmtMoney(cents) {
      if (cents == null)
        return "-";
      return "¥" + (cents / 100).toFixed(2);
    }
    function goReplenishment() {
      common_vendor.index.navigateTo({ url: "/pages/replenishment/replenishment" });
    }
    async function load() {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      loading.value = true;
      error.value = "";
      try {
        const me = await utils_merchantApi.merchantApi.me();
        common_vendor.index.setStorageSync("merchant_me", me);
        const [s, trend, workbench] = await Promise.all([
          utils_merchantApi.merchantApi.stats(),
          utils_merchantApi.merchantApi.trend(7),
          utils_merchantApi.merchantApi.workbench()
        ]);
        const days = trend.last7Days || [];
        const maxRev = Math.max(...days.map((d) => d.revenueCents), 1);
        meName.value = me.displayName || me.phoneNumber || "商户";
        merchantNames.value = (me.merchants || []).map((m) => m.merchantName).join("、") || "未绑定";
        stats.value = s;
        revenueToday.value = fmtMoney(s.revenueTodayCents);
        incomeToday.value = fmtMoney(s.merchantIncomeTodayCents);
        offlineCount.value = workbench.offlineDevices || 0;
        pendingCount.value = (workbench.openDisputes || 0) + (workbench.offlineDevices || 0) + (workbench.lowStockItems || 0) + (workbench.expiryAlerts || 0);
        actionItems.value = (workbench.actionItems || []).slice(0, 3);
        trendBars.value = days.map((d) => ({
          date: d.date,
          label: d.date.slice(5),
          height: Math.max(16, Math.round(d.revenueCents / maxRev * 120))
        }));
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function goTab(url) {
      common_vendor.index.switchTab({ url });
    }
    function goPricing() {
      common_vendor.index.navigateTo({ url: "/pages/pricing/pricing" });
    }
    common_vendor.onShow(load);
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value)
      } : {
        d: common_vendor.t(meName.value),
        e: common_vendor.t(merchantNames.value),
        f: common_vendor.t(revenueToday.value),
        g: common_vendor.t(incomeToday.value),
        h: common_vendor.t(stats.value.deviceOnline ?? "-"),
        i: common_vendor.t(stats.value.deviceTotal ?? "-"),
        j: common_vendor.t(pendingCount.value),
        k: pendingCount.value > 0 ? 1 : "",
        l: common_vendor.o(($event) => goTab("/pages/alerts/alerts")),
        m: common_vendor.t(offlineCount.value),
        n: common_vendor.o(($event) => goTab("/pages/devices/devices")),
        o: common_vendor.o(goPricing),
        p: common_vendor.o(goReplenishment),
        q: common_vendor.o(
          //@ts-ignore
          (...args) => _ctx.goSettlements && _ctx.goSettlements(...args)
        ),
        r: common_vendor.o(
          //@ts-ignore
          (...args) => _ctx.goDisputes && _ctx.goDisputes(...args)
        )
      }, {
        b: error.value,
        s: actionItems.value.length
      }, actionItems.value.length ? common_vendor.e({
        t: actionItems.value.length
      }, actionItems.value.length ? {
        v: common_vendor.f(actionItems.value, (item, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(item.title),
            b: item.detail
          }, item.detail ? {
            c: common_vendor.t(item.detail)
          } : {}, {
            d: item.type + item.title
          });
        }),
        w: common_vendor.o(($event) => goTab("/pages/alerts/alerts"))
      } : {}, {
        x: common_vendor.f(trendBars.value, (b, k0, i0) => {
          return {
            a: b.height + "rpx",
            b: common_vendor.t(b.label),
            c: b.date
          };
        }),
        y: common_vendor.o(($event) => goTab("/pages/alerts/alerts"))
      }) : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-0cd09a48"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/home/home.vue"]]);
wx.createPage(MiniProgramPage);
