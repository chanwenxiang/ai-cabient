"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_notify = require("../../utils/notify.js");
const utils_disputeCopy = require("../../utils/dispute-copy.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "orders",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const authed = common_vendor.ref(false);
    const orders = common_vendor.ref([]);
    const disputes = common_vendor.ref([]);
    const filter = common_vendor.ref("all");
    const reviewingDisputes = common_vendor.computed(
      () => disputes.value.filter((d) => d.status === "OPEN" && !orders.value.some((o) => o.sessionId === d.sessionId))
    );
    const filters = [{ label: "全部", value: "all" }, { label: "已完成", value: "paid" }, { label: "处理中", value: "pending" }, { label: "有疑问", value: "issue" }];
    const visibleOrders = common_vendor.computed(() => orders.value.filter((o) => {
      return matchesFilter(o, filter.value);
    }));
    function matchesFilter(order, value) {
      if (value === "paid")
        return order.status === "PAID" || order.status === "COMPLETED";
      if (value === "pending")
        return order.status === "PENDING" || order.status === "PROCESSING";
      if (value === "issue")
        return order.status === "DISPUTED" || order.status === "FAILED";
      return true;
    }
    function countBy(value) {
      return orders.value.filter((order) => matchesFilter(order, value)).length;
    }
    function shortId(id) {
      return id.length > 12 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id;
    }
    function deviceDisplay(deviceId) {
      if (!deviceId)
        return "智能柜";
      const lastId = common_vendor.index.getStorageSync("last_device_id");
      const lastName = common_vendor.index.getStorageSync("last_device_name");
      if (lastId === deviceId && lastName)
        return lastName;
      return deviceId === "CAB-001" ? "测试柜-001" : "智能零售柜";
    }
    function formatTime(value) {
      return common_vendor.formatDateTimeShort(value);
    }
    function statusLabel(status) {
      return common_vendor.orderStatusLabel(status);
    }
    function chipClass(status) {
      if (status === "PAID" || status === "COMPLETED")
        return "paid";
      if (status === "PENDING" || status === "PROCESSING")
        return "pending";
      if (status === "DISPUTED" || status === "FAILED")
        return "disputed";
      return "default";
    }
    function goShop() {
      common_vendor.index.removeStorageSync("active_session_id");
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    async function onAuth() {
      const ok = await utils_consumerApi.ensureConsumerAuth();
      if (!ok) {
        common_vendor.index.navigateTo({ url: "/pages/login/login?redirect=" + encodeURIComponent("/pages/orders/orders") });
        return;
      }
      await load();
    }
    async function load() {
      loading.value = true;
      error.value = "";
      await utils_consumerApi.ensureConsumerAuth();
      authed.value = !!utils_consumerApi.getConsumerToken();
      if (!authed.value) {
        loading.value = false;
        return;
      }
      try {
        const [page, mine] = await Promise.all([
          utils_consumerApi.consumerApi.listOrders(0, 30),
          utils_consumerApi.consumerApi.listMyDisputes()
        ]);
        orders.value = page.items || [];
        disputes.value = mine || [];
        const lastSid = String(common_vendor.index.getStorageSync("last_disputed_session_id") || "");
        if (lastSid) {
          const ticket = disputes.value.find((d) => d.sessionId === lastSid);
          if (!ticket || ticket.status !== "OPEN") {
            common_vendor.index.removeStorageSync("last_disputed_session_id");
            if ((ticket == null ? void 0 : ticket.status) === "RESOLVED") {
              utils_notify.showDisputeResolvedToast(ticket);
            }
          }
        }
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    function goDisputeDetail(d) {
      if (d.orderId) {
        common_vendor.index.navigateTo({
          url: `/pages/result/result?orderId=${encodeURIComponent(d.orderId)}&sessionId=${encodeURIComponent(d.sessionId)}`
        });
        return;
      }
      common_vendor.index.setStorageSync("last_disputed_session_id", d.sessionId);
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    function goDetail(o) {
      common_vendor.index.navigateTo({
        url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(o.orderId)}`
      });
    }
    function goReport() {
      common_vendor.index.navigateTo({ url: "/pages/report/report" });
    }
    common_vendor.onShow(load);
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value),
        d: common_vendor.o(load),
        e: common_vendor.o(goShop)
      } : !authed.value ? {
        g: common_vendor.o(onAuth),
        h: common_vendor.o(goShop)
      } : !orders.value.length && !reviewingDisputes.value.length ? {
        j: common_vendor.o(goShop)
      } : common_vendor.e({
        k: reviewingDisputes.value.length
      }, reviewingDisputes.value.length ? {
        l: common_vendor.f(reviewingDisputes.value, (d, k0, i0) => {
          return {
            a: common_vendor.t(shortId(d.sessionId)),
            b: common_vendor.t(common_vendor.unref(utils_disputeCopy.consumerDisputeReviewCopy)(d).detail),
            c: common_vendor.t(formatTime(d.createdAt)),
            d: d.ticketId,
            e: common_vendor.o(($event) => goDisputeDetail(d), d.ticketId)
          };
        })
      } : {}, {
        m: common_vendor.f(filters, (f, k0, i0) => {
          return {
            a: common_vendor.t(f.label),
            b: common_vendor.t(countBy(f.value)),
            c: f.value,
            d: filter.value === f.value ? 1 : "",
            e: common_vendor.o(($event) => filter.value = f.value, f.value)
          };
        }),
        n: common_vendor.f(visibleOrders.value, (o, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(shortId(o.orderId)),
            b: common_vendor.t(statusLabel(o.status)),
            c: common_vendor.n(chipClass(o.status)),
            d: common_vendor.t(deviceDisplay(o.deviceId)),
            e: common_vendor.t(o.deviceId),
            f: common_vendor.t(formatTime(o.createdAt)),
            g: common_vendor.t(((o.totalAmountCents || 0) / 100).toFixed(2)),
            h: o.status === "DISPUTED"
          }, o.status === "DISPUTED" ? {
            i: common_vendor.o(($event) => goDetail(o), o.orderId)
          } : {}, {
            j: o.orderId,
            k: common_vendor.o(($event) => goDetail(o), o.orderId)
          });
        }),
        o: !visibleOrders.value.length
      }, !visibleOrders.value.length ? {} : {}, {
        p: common_vendor.o(goReport)
      }), {
        b: error.value,
        f: !authed.value,
        i: !orders.value.length && !reviewingDisputes.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-926fbf1b"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/orders/orders.vue"]]);
wx.createPage(MiniProgramPage);
