"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
if (!Math) {
  EmptyState();
}
const EmptyState = () => "../../components/empty-state.js";
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "orders",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const canList = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:orders:list"));
    const loading = common_vendor.ref(false);
    const error = common_vendor.ref("");
    const list = common_vendor.ref([]);
    let loadSeq = 0;
    const listTotal = common_vendor.ref(0);
    const listTruncated = common_vendor.computed(
      () => listTotal.value > 0 && list.value.length > 0 && listTotal.value > list.value.length
    );
    common_vendor.onShow(() => load());
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    async function load() {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      const seq = ++loadSeq;
      try {
        await refreshMe();
      } catch {
        if (!common_vendor.index.getStorageSync("merchant_token")) return;
        me.value = me.value || common_vendor.index.getStorageSync("merchant_me") || null;
      }
      if (seq !== loadSeq) return;
      if (!me.value) {
        me.value = common_vendor.index.getStorageSync("merchant_me") || null;
      }
      if (!canList.value) {
        common_vendor.index.showToast({ title: "无订单权限", icon: "none" });
        common_vendor.index.navigateBack({ fail: () => common_vendor.index.switchTab({ url: "/pages/home/home" }) });
        return;
      }
      loading.value = true;
      error.value = "";
      try {
        const res = await utils_merchantApi.merchantApi.orders(void 0, 0, 50);
        if (seq !== loadSeq) return;
        if (Array.isArray(res)) {
          list.value = res;
          listTotal.value = res.length;
        } else {
          list.value = (res == null ? void 0 : res.items) || [];
          listTotal.value = (res == null ? void 0 : res.total) ?? list.value.length;
        }
      } catch (e) {
        if (seq !== loadSeq) return;
        list.value = [];
        listTotal.value = 0;
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        if (seq === loadSeq) loading.value = false;
      }
    }
    function statusText(s) {
      return common_vendor.orderStatusLabel(s);
    }
    function money(cents) {
      return common_vendor.fmtMoney(cents);
    }
    function shortId(id) {
      if (!id) return common_vendor.emptyDisplay(id, "order");
      return id.length > 14 ? id.substring(0, 14) : id;
    }
    function formatTime(t) {
      return common_vendor.formatDateTimeShort(t, "暂无");
    }
    function onDetail(item) {
      if (!item.orderId) return;
      common_vendor.index.navigateTo({
        url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(item.orderId)}`
      });
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value),
        d: common_vendor.o(load)
      } : !list.value.length ? {
        f: common_vendor.p({
          icon: "单",
          title: "暂无柜机订单",
          hint: "有成交后会显示在这里"
        })
      } : common_vendor.e({
        g: common_vendor.f(list.value, (item, k0, i0) => {
          return {
            a: common_vendor.t(shortId(item.orderId)),
            b: common_vendor.t(statusText(item.status)),
            c: common_vendor.n(item.status),
            d: common_vendor.t(money(item.totalAmountCents)),
            e: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(item.deviceId, "device")),
            f: common_vendor.t(item.lineCount || 0),
            g: common_vendor.t(formatTime(item.createdAt)),
            h: item.orderId,
            i: `订单 ${shortId(item.orderId)} ${statusText(item.status)} ${money(item.totalAmountCents)}`,
            j: common_vendor.o(($event) => onDetail(item), item.orderId)
          };
        }),
        h: listTruncated.value
      }, listTruncated.value ? {
        i: common_vendor.t(list.value.length),
        j: common_vendor.t(listTotal.value)
      } : {}), {
        b: error.value,
        e: !list.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-926fbf1b"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/orders/orders.vue"]]);
wx.createPage(MiniProgramPage);
