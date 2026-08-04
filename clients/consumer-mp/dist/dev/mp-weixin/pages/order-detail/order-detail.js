"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "order-detail",
  setup(__props) {
    const orderId = common_vendor.ref("");
    const order = common_vendor.ref(null);
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const videoUrl = common_vendor.ref("");
    common_vendor.onLoad(async (opt) => {
      var _a;
      orderId.value = (opt == null ? void 0 : opt.orderId) || "";
      if (!orderId.value) {
        error.value = "缺少订单编号";
        loading.value = false;
        return;
      }
      try {
        const res = await utils_consumerApi.get("/api/v2/orders/" + orderId.value);
        order.value = res.data;
        if ((_a = order.value) == null ? void 0 : _a.videoUri)
          videoUrl.value = order.value.videoUri;
      } catch (e) {
        error.value = (e == null ? void 0 : e.message) || "加载失败";
      } finally {
        loading.value = false;
      }
    });
    const statusIcon = common_vendor.computed(() => {
      var _a;
      const map = { paid: "✓", refunded: "↩", disputed: "!", failed: "✕" };
      return map[(((_a = order.value) == null ? void 0 : _a.status) || "").toLowerCase()] || "✓";
    });
    const statusTitle = common_vendor.computed(() => {
      var _a;
      const map = { PAID: "交易完成", REFUNDED: "已退款", DISPUTED: "争议处理中", FAILED: "交易失败" };
      return map[((_a = order.value) == null ? void 0 : _a.status) || ""] || "已完成";
    });
    const statusDetail = common_vendor.computed(() => {
      var _a, _b;
      if (((_a = order.value) == null ? void 0 : _a.status) === "PAID")
        return "关门自动扣款成功，如有疑问请联系客服";
      if (((_b = order.value) == null ? void 0 : _b.status) === "REFUNDED")
        return "已退款至您的账户余额";
      return "";
    });
    const payChannelText = common_vendor.computed(() => {
      var _a, _b;
      const map = { BALANCE: "余额支付", WECHAT: "微信支付", ALIPAY: "支付宝" };
      return map[((_a = order.value) == null ? void 0 : _a.payChannel) || ""] || ((_b = order.value) == null ? void 0 : _b.payChannel) || "-";
    });
    function formatTime(t) {
      if (!t)
        return "";
      return t.substring(0, 16).replace("T", " ");
    }
    function playVideo() {
      if (videoUrl.value)
        common_vendor.index.previewImage({ urls: [videoUrl.value], current: 0 });
    }
    function onDispute() {
      common_vendor.index.showModal({
        title: "对账单有疑问？",
        content: "如您对购物清单有疑问，可提交争议，我们将人工核实处理。",
        confirmText: "提交争议",
        success: (res) => {
          var _a, _b;
          if (res.confirm) {
            common_vendor.index.navigateTo({ url: `/pages/dispute/dispute?orderId=${(_a = order.value) == null ? void 0 : _a.orderId}&sessionId=${(_b = order.value) == null ? void 0 : _b.sessionId}` });
          }
        }
      });
    }
    return (_ctx, _cache) => {
      var _a, _b, _c, _d, _e, _f, _g, _h, _i;
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value)
      } : common_vendor.e({
        d: common_vendor.t(statusIcon.value),
        e: common_vendor.t(statusTitle.value),
        f: common_vendor.t(statusDetail.value),
        g: common_vendor.n("status-" + (((_a = order.value) == null ? void 0 : _a.status) || "").toLowerCase()),
        h: common_vendor.f(((_b = order.value) == null ? void 0 : _b.lines) || [], (item, k0, i0) => {
          return {
            a: common_vendor.t(item.skuName),
            b: common_vendor.t(item.quantity),
            c: common_vendor.t((item.lineAmountCents / 100).toFixed(2)),
            d: item.skuId
          };
        }),
        i: common_vendor.t((((_c = order.value) == null ? void 0 : _c.totalAmountCents) / 100).toFixed(2)),
        j: (_d = order.value) == null ? void 0 : _d.couponDiscountCents
      }, ((_e = order.value) == null ? void 0 : _e.couponDiscountCents) ? {
        k: common_vendor.t((order.value.couponDiscountCents / 100).toFixed(2))
      } : {}, {
        l: common_vendor.t(payChannelText.value),
        m: common_vendor.t(formatTime(((_f = order.value) == null ? void 0 : _f.payTime) || ((_g = order.value) == null ? void 0 : _g.createdAt))),
        n: common_vendor.t((_h = order.value) == null ? void 0 : _h.orderId),
        o: common_vendor.t((_i = order.value) == null ? void 0 : _i.deviceId),
        p: videoUrl.value
      }, videoUrl.value ? {
        q: common_vendor.o(playVideo)
      } : {}, {
        r: common_vendor.o(onDispute)
      }), {
        b: error.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-48de6e3f"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/order-detail/order-detail.vue"]]);
wx.createPage(MiniProgramPage);
