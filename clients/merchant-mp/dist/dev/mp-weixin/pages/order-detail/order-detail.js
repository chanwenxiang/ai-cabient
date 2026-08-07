"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "order-detail",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const canList = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:orders:list"));
    const orderId = common_vendor.ref("");
    const order = common_vendor.ref(null);
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const payChannelText = common_vendor.computed(
      () => {
        var _a;
        return common_vendor.displayLabel("pay_channel", (_a = order.value) == null ? void 0 : _a.payChannel, "未知渠道");
      }
    );
    common_vendor.onLoad((opt) => {
      orderId.value = String((opt == null ? void 0 : opt.orderId) || (opt == null ? void 0 : opt.id) || "").trim();
      void load();
    });
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    async function load() {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      if (!orderId.value) {
        error.value = "缺少订单号";
        loading.value = false;
        return;
      }
      try {
        await refreshMe();
      } catch {
        me.value = me.value || common_vendor.index.getStorageSync("merchant_me") || null;
      }
      if (!canList.value) {
        common_vendor.index.showToast({ title: "无订单权限", icon: "none" });
        common_vendor.index.navigateBack({ fail: () => common_vendor.index.switchTab({ url: "/pages/home/home" }) });
        return;
      }
      loading.value = true;
      error.value = "";
      try {
        order.value = await utils_merchantApi.merchantApi.orderDetail(orderId.value);
      } catch (e) {
        order.value = null;
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function statusText(s) {
      return common_vendor.orderStatusLabel(s);
    }
    function money(cents) {
      return common_vendor.fmtMoney(cents);
    }
    function formatTime(t) {
      return common_vendor.formatDateTimeShort(t, "暂无");
    }
    function goDevice() {
      var _a;
      const id = (_a = order.value) == null ? void 0 : _a.deviceId;
      if (!id) return;
      common_vendor.index.navigateTo({
        url: `/pages/device-detail/device-detail?id=${encodeURIComponent(id)}`
      });
    }
    function goDisputes() {
      var _a;
      const sid = (_a = order.value) == null ? void 0 : _a.sessionId;
      if (sid) {
        common_vendor.index.navigateTo({
          url: `/pages/disputes/disputes?sessionId=${encodeURIComponent(sid)}`
        });
        return;
      }
      common_vendor.index.navigateTo({ url: "/pages/disputes/disputes" });
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value),
        d: common_vendor.o(load)
      } : order.value ? common_vendor.e({
        f: common_vendor.t(statusText(order.value.status)),
        g: common_vendor.t(money(order.value.totalAmountCents)),
        h: common_vendor.n("s-" + (order.value.status || "").toLowerCase()),
        i: common_vendor.f(order.value.lines || [], (line, i, i0) => {
          return {
            a: common_vendor.t(line.skuName || line.skuId || "商品"),
            b: common_vendor.t(line.quantity),
            c: common_vendor.t(money(line.lineAmountCents)),
            d: i
          };
        }),
        j: !(order.value.lines || []).length
      }, !(order.value.lines || []).length ? {} : {}, {
        k: order.value.couponDiscountCents
      }, order.value.couponDiscountCents ? {
        l: common_vendor.t(money(order.value.couponDiscountCents))
      } : {}, {
        m: common_vendor.t(money(order.value.totalAmountCents)),
        n: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(order.value.orderId, "order")),
        o: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(order.value.sessionId, "session")),
        p: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(order.value.deviceId, "device")),
        q: common_vendor.t(payChannelText.value),
        r: common_vendor.t(formatTime(order.value.createdAt)),
        s: order.value.deviceId
      }, order.value.deviceId ? {
        t: common_vendor.o(goDevice)
      } : {}, {
        v: common_vendor.o(goDisputes)
      }) : {}, {
        b: error.value,
        e: order.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-48de6e3f"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/order-detail/order-detail.vue"]]);
wx.createPage(MiniProgramPage);
