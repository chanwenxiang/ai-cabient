"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "result",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const order = common_vendor.ref(null);
    const statusLabel = common_vendor.ref("");
    let sessionId = "";
    const deviceId = common_vendor.ref("");
    const showDispute = common_vendor.ref(false);
    const disputeReason = common_vendor.ref("");
    const disputeLoading = common_vendor.ref(false);
    const disputeFiled = common_vendor.ref(false);
    common_vendor.onLoad((opts) => {
      sessionId = (opts == null ? void 0 : opts.sessionId) || "";
      const orderId = (opts == null ? void 0 : opts.orderId) || "";
      if (orderId)
        loadByOrderId(orderId);
      else if (sessionId)
        loadBySession(sessionId);
      else {
        error.value = "缺少会话或订单信息";
        loading.value = false;
      }
    });
    async function loadBySession(sid) {
      var _a;
      try {
        const sess = await utils_consumerApi.consumerApi.getSession(sid);
        deviceId.value = sess.deviceId || "";
        order.value = await utils_consumerApi.consumerApi.getSessionOrder(sid);
        statusLabel.value = common_vendor.orderStatusLabel((_a = order.value) == null ? void 0 : _a.status);
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    async function loadByOrderId(oid) {
      var _a, _b, _c;
      try {
        order.value = await utils_consumerApi.consumerApi.getOrder(oid);
        statusLabel.value = common_vendor.orderStatusLabel((_a = order.value) == null ? void 0 : _a.status);
        sessionId = ((_b = order.value) == null ? void 0 : _b.sessionId) || sessionId;
        deviceId.value = ((_c = order.value) == null ? void 0 : _c.deviceId) || deviceId.value;
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function openDispute() {
      disputeReason.value = "";
      showDispute.value = true;
    }
    function closeDispute() {
      showDispute.value = false;
    }
    async function submitDispute() {
      const reason = disputeReason.value.trim();
      if (!sessionId) {
        common_vendor.index.showToast({ title: "缺少会话信息", icon: "none" });
        return;
      }
      if (reason.length < 4) {
        common_vendor.index.showToast({ title: "请至少填写 4 个字", icon: "none" });
        return;
      }
      disputeLoading.value = true;
      try {
        await utils_consumerApi.consumerApi.fileDispute({
          sessionId,
          reason,
          category: "USER_APPEAL",
          priority: "NORMAL"
        });
        disputeFiled.value = true;
        showDispute.value = false;
        common_vendor.index.showToast({ title: "申诉已提交", icon: "success" });
      } catch (e) {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "提交失败", icon: "none" });
      } finally {
        disputeLoading.value = false;
      }
    }
    function continueShop() {
      var _a;
      const id = deviceId.value || ((_a = order.value) == null ? void 0 : _a.deviceId);
      if (id) {
        common_vendor.index.setStorageSync("reopen_device_id", id);
      }
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    function goOrders() {
      common_vendor.index.switchTab({ url: "/pages/orders/orders" });
    }
    function goReport() {
      var _a;
      const id = deviceId.value || ((_a = order.value) == null ? void 0 : _a.deviceId) || "";
      common_vendor.index.navigateTo({
        url: `/pages/report/report?deviceId=${encodeURIComponent(id)}`
      });
    }
    function goHome() {
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    return (_ctx, _cache) => {
      var _a, _b;
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value)
      } : order.value ? common_vendor.e({
        e: common_vendor.t(order.value.totalAmountCents > 0 ? "购物完成" : "感谢使用"),
        f: common_vendor.t(statusLabel.value),
        g: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(order.value.totalAmountCents)),
        h: order.value.totalAmountCents <= 0
      }, order.value.totalAmountCents <= 0 ? {} : {}, {
        i: order.value.balanceBeforeCents != null && order.value.balanceAfterCents != null
      }, order.value.balanceBeforeCents != null && order.value.balanceAfterCents != null ? {
        j: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(order.value.balanceBeforeCents)),
        k: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(order.value.balanceAfterCents))
      } : {}, {
        l: (_a = order.value.lines) == null ? void 0 : _a.length
      }, ((_b = order.value.lines) == null ? void 0 : _b.length) ? {
        m: common_vendor.f(order.value.lines, (line, i, i0) => {
          return {
            a: common_vendor.t(line.skuName || line.skuId),
            b: common_vendor.t(line.quantity),
            c: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(line.lineAmountCents)),
            d: i
          };
        })
      } : {}, {
        n: common_vendor.o(continueShop),
        o: common_vendor.o(goOrders),
        p: common_vendor.unref(sessionId) && !disputeFiled.value
      }, common_vendor.unref(sessionId) && !disputeFiled.value ? {
        q: disputeLoading.value,
        r: common_vendor.o(openDispute)
      } : disputeFiled.value ? {} : {}, {
        s: disputeFiled.value,
        t: common_vendor.o(goReport),
        v: common_vendor.o(goHome)
      }) : {}, {
        b: error.value,
        d: order.value,
        w: showDispute.value
      }, showDispute.value ? {
        x: disputeReason.value,
        y: common_vendor.o(($event) => disputeReason.value = $event.detail.value),
        z: common_vendor.t(disputeLoading.value ? "提交中…" : "提交申诉"),
        A: disputeLoading.value,
        B: disputeLoading.value,
        C: common_vendor.o(submitDispute),
        D: common_vendor.o(closeDispute),
        E: common_vendor.o(() => {
        }),
        F: common_vendor.o(closeDispute)
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-d38065ce"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/result/result.vue"]]);
wx.createPage(MiniProgramPage);
