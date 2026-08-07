"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_notify = require("../../utils/notify.js");
const utils_disputeCopy = require("../../utils/dispute-copy.js");
const PAGE_SIZE = 20;
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "orders",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const loadingMore = common_vendor.ref(false);
    const error = common_vendor.ref("");
    const authed = common_vendor.ref(false);
    const orders = common_vendor.ref([]);
    const disputes = common_vendor.ref([]);
    const pageIndex = common_vendor.ref(0);
    const hasMore = common_vendor.ref(false);
    const ordersTotal = common_vendor.ref(0);
    const filter = common_vendor.ref("all");
    const timeRange = common_vendor.ref("all");
    const reviewingDisputes = common_vendor.computed(
      () => disputes.value.filter((d) => d.status === "OPEN" && !orders.value.some((o) => o.sessionId === d.sessionId))
    );
    const filters = [
      { label: "全部", value: "all" },
      { label: "已完成", value: "paid" },
      { label: "待支付", value: "pending" },
      { label: "有疑问", value: "issue" },
      { label: "已退款", value: "refunded" },
      { label: "已取消", value: "cancelled" }
    ];
    const timeFilters = [
      { label: "全部时间", value: "all" },
      { label: "今天", value: "today" },
      { label: "近7天", value: "7d" },
      { label: "近30天", value: "30d" }
    ];
    const visibleOrders = common_vendor.computed(
      () => orders.value.filter((o) => matchesFilter(o, filter.value) && matchesTimeRange(o.createdAt, timeRange.value))
    );
    function startOfTodayShanghai() {
      var _a, _b, _c;
      const parts = new Intl.DateTimeFormat("en-CA", {
        timeZone: "Asia/Shanghai",
        year: "numeric",
        month: "2-digit",
        day: "2-digit"
      }).formatToParts(/* @__PURE__ */ new Date());
      const y = (_a = parts.find((p) => p.type === "year")) == null ? void 0 : _a.value;
      const m = (_b = parts.find((p) => p.type === "month")) == null ? void 0 : _b.value;
      const d = (_c = parts.find((p) => p.type === "day")) == null ? void 0 : _c.value;
      return (/* @__PURE__ */ new Date(`${y}-${m}-${d}T00:00:00+08:00`)).getTime();
    }
    function matchesTimeRange(createdAt, range) {
      if (range === "all" || !createdAt) return range === "all" ? true : false;
      const ts = new Date(createdAt).getTime();
      if (Number.isNaN(ts)) return false;
      const now = Date.now();
      if (range === "today") return ts >= startOfTodayShanghai();
      if (range === "7d") return ts >= now - 7 * 24 * 60 * 60 * 1e3;
      if (range === "30d") return ts >= now - 30 * 24 * 60 * 60 * 1e3;
      return true;
    }
    function matchesFilter(order, value) {
      if (value === "paid") return order.status === "PAID" || order.status === "COMPLETED";
      if (value === "pending") return order.status === "PENDING" || order.status === "PROCESSING";
      if (value === "issue") return order.status === "DISPUTED" || order.status === "FAILED";
      if (value === "refunded") return order.status === "REFUNDED" || order.status === "PARTIAL_REFUNDED";
      if (value === "cancelled") return order.status === "CANCELLED";
      return true;
    }
    function countBy(value) {
      return orders.value.filter(
        (order) => matchesFilter(order, value) && matchesTimeRange(order.createdAt, timeRange.value)
      ).length;
    }
    function filterCountSuffix(value) {
      if (hasMore.value) {
        if (value === "all" && timeRange.value === "all" && ordersTotal.value > 0) {
          return ` ${ordersTotal.value}`;
        }
        return "";
      }
      return ` ${countBy(value)}`;
    }
    function shortId(id) {
      if (!id) return "暂无单号";
      return id.length > 12 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id;
    }
    function deviceDisplay(deviceId) {
      if (!deviceId) return "无柜机";
      const lastId = common_vendor.index.getStorageSync("last_device_id");
      const lastName = common_vendor.index.getStorageSync("last_device_name");
      if (lastId === deviceId && lastName) return String(lastName);
      return deviceId;
    }
    function orderSummaryText(o) {
      const n = o.lineCount || 0;
      if (n > 0) return `共 ${n} 件商品`;
      return "购物账单";
    }
    function formatTime(value) {
      return common_vendor.formatDateTimeShort(value);
    }
    function reviewCopy(d) {
      return utils_disputeCopy.consumerDisputeReviewCopy(d);
    }
    function statusLabel(status) {
      return common_vendor.orderStatusLabel(status);
    }
    function payChannelText(channel) {
      return common_vendor.displayLabel("pay_channel", channel, "未知渠道");
    }
    function chipClass(status) {
      if (status === "PAID" || status === "COMPLETED") return "paid";
      if (status === "PENDING" || status === "PROCESSING") return "pending";
      if (status === "DISPUTED" || status === "FAILED") return "disputed";
      if (status === "REFUNDED" || status === "PARTIAL_REFUNDED") return "refunded";
      if (status === "CANCELLED") return "cancelled";
      return "default";
    }
    function goBack() {
      const pages = getCurrentPages();
      if (pages.length > 1) {
        common_vendor.index.navigateBack({ delta: 1 });
        return;
      }
      common_vendor.index.navigateBack({
        fail: () => common_vendor.index.switchTab({ url: "/pages/mine/mine" })
      });
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
      pageIndex.value = 0;
      hasMore.value = false;
      ordersTotal.value = 0;
      await utils_consumerApi.ensureConsumerAuth();
      authed.value = !!utils_consumerApi.getConsumerToken();
      if (!authed.value) {
        loading.value = false;
        return;
      }
      try {
        const [page, mine] = await Promise.all([
          utils_consumerApi.consumerApi.listOrders(0, PAGE_SIZE),
          utils_consumerApi.consumerApi.listMyDisputes()
        ]);
        orders.value = page.items || [];
        const total = Number(page.total ?? 0);
        ordersTotal.value = total;
        hasMore.value = orders.value.length < total;
        pageIndex.value = 0;
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
    async function loadMore() {
      if (!authed.value || loading.value || loadingMore.value || !hasMore.value) return;
      loadingMore.value = true;
      try {
        const nextPage = pageIndex.value + 1;
        const page = await utils_consumerApi.consumerApi.listOrders(nextPage, PAGE_SIZE);
        const items = page.items || [];
        if (!items.length) {
          hasMore.value = false;
          return;
        }
        const seen = new Set(orders.value.map((o) => o.orderId));
        const appended = items.filter((o) => o.orderId && !seen.has(o.orderId));
        orders.value = orders.value.concat(appended);
        pageIndex.value = nextPage;
        const total = Number(page.total ?? 0);
        hasMore.value = orders.value.length < total && items.length >= PAGE_SIZE;
      } catch (e) {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "加载失败", icon: "none" });
      } finally {
        loadingMore.value = false;
      }
    }
    function goDisputeDetail(d) {
      const q = [
        d.ticketId ? `ticketId=${encodeURIComponent(d.ticketId)}` : "",
        d.sessionId ? `sessionId=${encodeURIComponent(d.sessionId)}` : ""
      ].filter(Boolean).join("&");
      common_vendor.index.navigateTo({ url: `/pages/dispute/detail?${q}` });
    }
    function goDetail(o) {
      common_vendor.index.navigateTo({
        url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(o.orderId)}`
      });
    }
    function goReport() {
      common_vendor.index.navigateTo({ url: "/pages/report/report" });
    }
    function goHelp() {
      common_vendor.index.navigateTo({ url: "/pages/help/help" });
    }
    common_vendor.onShow(load);
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.o(goBack),
        b: loading.value
      }, loading.value ? {} : error.value ? {
        d: common_vendor.t(error.value),
        e: common_vendor.o(load),
        f: common_vendor.o(goShop)
      } : !authed.value ? {
        h: common_vendor.o(onAuth),
        i: common_vendor.o(goShop)
      } : !orders.value.length && !reviewingDisputes.value.length ? {
        k: common_vendor.o(goShop)
      } : common_vendor.e({
        l: reviewingDisputes.value.length
      }, reviewingDisputes.value.length ? {
        m: common_vendor.f(reviewingDisputes.value, (d, k0, i0) => {
          return {
            a: common_vendor.t(reviewCopy(d).icon),
            b: common_vendor.t(reviewCopy(d).title),
            c: common_vendor.t(reviewCopy(d).detail),
            d: common_vendor.t(formatTime(d.createdAt)),
            e: d.ticketId,
            f: common_vendor.n("tone-" + reviewCopy(d).tone),
            g: common_vendor.o(($event) => goDisputeDetail(d), d.ticketId)
          };
        })
      } : {}, {
        n: common_vendor.f(filters, (f, k0, i0) => {
          return {
            a: common_vendor.t(f.label),
            b: common_vendor.t(filterCountSuffix(f.value)),
            c: f.value,
            d: filter.value === f.value ? 1 : "",
            e: common_vendor.o(($event) => filter.value = f.value, f.value)
          };
        }),
        o: common_vendor.f(timeFilters, (t, k0, i0) => {
          return {
            a: common_vendor.t(t.label),
            b: t.value,
            c: timeRange.value === t.value ? 1 : "",
            d: common_vendor.o(($event) => timeRange.value = t.value, t.value)
          };
        }),
        p: common_vendor.f(visibleOrders.value, (o, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(deviceDisplay(o.deviceId)),
            b: common_vendor.t(shortId(o.orderId)),
            c: common_vendor.t(statusLabel(o.status)),
            d: common_vendor.n(chipClass(o.status)),
            e: common_vendor.t(orderSummaryText(o)),
            f: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(o.totalAmountCents || 0)),
            g: common_vendor.t(payChannelText(o.payChannel)),
            h: common_vendor.t(formatTime(o.createdAt)),
            i: o.status === "DISPUTED"
          }, o.status === "DISPUTED" ? {} : {}, {
            j: o.orderId,
            k: common_vendor.o(($event) => goDetail(o), o.orderId)
          });
        }),
        q: !visibleOrders.value.length
      }, !visibleOrders.value.length ? {} : {}, {
        r: loadingMore.value
      }, loadingMore.value ? {} : hasMore.value && orders.value.length ? {
        t: common_vendor.o(loadMore)
      } : orders.value.length && !hasMore.value ? {} : {}, {
        s: hasMore.value && orders.value.length,
        v: orders.value.length && !hasMore.value,
        w: common_vendor.o(goReport),
        x: common_vendor.o(goHelp),
        y: common_vendor.o(loadMore)
      }), {
        c: error.value,
        g: !authed.value,
        j: !orders.value.length && !reviewingDisputes.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-926fbf1b"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/orders/orders.vue"]]);
wx.createPage(MiniProgramPage);
