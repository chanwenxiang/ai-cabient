"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "recharge",
  setup(__props) {
    const amounts = [
      { value: 1e3, text: "10", bonus: "0" },
      { value: 2e3, text: "20", bonus: "2" },
      { value: 5e3, text: "50", bonus: "5" },
      { value: 1e4, text: "100", bonus: "10" },
      { value: 2e4, text: "200", bonus: "20" }
    ];
    const balanceYuan = common_vendor.ref("0.00");
    const balanceCents = common_vendor.ref(0);
    const selectedAmount = common_vendor.ref(0);
    const loading = common_vendor.ref(false);
    const records = common_vendor.ref([]);
    common_vendor.ref(false);
    common_vendor.ref("");
    common_vendor.onShow(() => {
      loadBalance();
      loadRecords();
    });
    async function loadBalance() {
      try {
        const res = await utils_consumerApi.get("/api/v2/account/balance");
        balanceCents.value = res.data.balanceCents ?? 0;
        balanceYuan.value = (balanceCents.value / 100).toFixed(2);
      } catch {
      }
    }
    async function loadRecords() {
      try {
        const res = await utils_consumerApi.get("/api/v2/recharges");
        records.value = res.data ?? [];
      } catch {
        records.value = [];
      }
    }
    function formatTime(t) {
      if (!t)
        return "";
      return t.substring(0, 16).replace("T", " ");
    }
    function statusText(s) {
      const map = { PENDING: "待支付", PAID: "已完成", REFUNDED: "已退款", FAILED: "失败" };
      return map[s] || s;
    }
    async function onRecharge() {
      if (!selectedAmount.value || loading.value)
        return;
      loading.value = true;
      try {
        const res = await utils_consumerApi.post("/api/v2/recharges", { amountCents: selectedAmount.value });
        const order = res.data;
        if (order.payUrl) {
          common_vendor.index.navigateTo({ url: `/pages/webview/webview?url=${encodeURIComponent(order.payUrl)}` });
        } else {
          common_vendor.index.showToast({ title: "充值成功", icon: "success" });
          loadBalance();
          loadRecords();
        }
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "充值失败", icon: "error" });
      } finally {
        loading.value = false;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.t(balanceYuan.value),
        b: common_vendor.f(amounts, (item, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(item.text),
            b: item.bonus
          }, item.bonus ? {
            c: common_vendor.t(item.bonus)
          } : {}, {
            d: item.value,
            e: selectedAmount.value === item.value ? 1 : "",
            f: common_vendor.o(($event) => selectedAmount.value = item.value, item.value)
          });
        }),
        c: common_vendor.t(loading.value ? "充值中…" : "确认充值"),
        d: !selectedAmount.value || loading.value,
        e: loading.value,
        f: common_vendor.o(onRecharge),
        g: !records.value.length
      }, !records.value.length ? {} : {}, {
        h: common_vendor.f(records.value, (r, k0, i0) => {
          return {
            a: common_vendor.t((r.amountCents / 100).toFixed(2)),
            b: common_vendor.t(formatTime(r.createdAt)),
            c: common_vendor.t(statusText(r.status)),
            d: common_vendor.n(r.status),
            e: r.orderId
          };
        })
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-d370def1"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/recharge/recharge.vue"]]);
wx.createPage(MiniProgramPage);
