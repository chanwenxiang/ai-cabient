"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const utils_textPrompt = require("../../utils/text-prompt.js");
if (!Math) {
  EmptyState();
}
const EmptyState = () => "../../components/empty-state.js";
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "disputes",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const canListDisputes = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:disputes:list"));
    const canReply = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:disputes:reply"));
    const tabs = [
      { key: "OPEN", label: "待处理" },
      { key: "RESOLVED", label: "已结案" },
      { key: "CLOSED", label: "已关闭" }
    ];
    const activeTab = common_vendor.ref("OPEN");
    const loading = common_vendor.ref(false);
    const error = common_vendor.ref("");
    const list = common_vendor.ref([]);
    let loadSeq = 0;
    const listTotal = common_vendor.ref(0);
    const pendingTicketId = common_vendor.ref("");
    const pendingSessionId = common_vendor.ref("");
    const activeTabLabel = common_vendor.computed(() => {
      var _a;
      return ((_a = tabs.find((t) => t.key === activeTab.value)) == null ? void 0 : _a.label) || "";
    });
    const listTruncated = common_vendor.computed(
      () => listTotal.value > 0 && list.value.length > 0 && listTotal.value > list.value.length
    );
    common_vendor.onLoad((opt) => {
      pendingTicketId.value = String((opt == null ? void 0 : opt.ticketId) || "").trim();
      pendingSessionId.value = String((opt == null ? void 0 : opt.sessionId) || "").trim();
    });
    common_vendor.onShow(() => load());
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    function switchTab(key) {
      activeTab.value = key;
      load();
    }
    function canReplyTicket(item) {
      return canReply.value && (item.status || "").toUpperCase() === "OPEN";
    }
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
      if (!canListDisputes.value) {
        common_vendor.index.showToast({ title: "无争议权限", icon: "none" });
        common_vendor.index.navigateBack({ fail: () => common_vendor.index.switchTab({ url: "/pages/home/home" }) });
        return;
      }
      loading.value = true;
      error.value = "";
      try {
        const res = await utils_merchantApi.merchantApi.disputes(activeTab.value, 0, 100);
        if (seq !== loadSeq) return;
        if (Array.isArray(res)) {
          list.value = res;
          listTotal.value = res.length;
        } else {
          list.value = (res == null ? void 0 : res.items) || [];
          listTotal.value = (res == null ? void 0 : res.total) ?? list.value.length;
        }
        if (pendingSessionId.value) {
          const sid = pendingSessionId.value;
          pendingSessionId.value = "";
          const matched = list.value.filter((t) => t.sessionId === sid);
          if (matched.length === 1) {
            onDetail(matched[0]);
          } else if (matched.length > 1) {
            list.value = matched;
            listTotal.value = matched.length;
          }
        }
        if (pendingTicketId.value) {
          const tid = pendingTicketId.value;
          pendingTicketId.value = "";
          let row = list.value.find((t) => t.ticketId === tid);
          if (!row) {
            try {
              const detail = await utils_merchantApi.merchantApi.disputeDetail(tid);
              row = detail == null ? void 0 : detail.ticket;
            } catch {
              row = void 0;
            }
          }
          if (row) onDetail(row);
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
      return common_vendor.displayLabel("dispute_status", s, "未知状态");
    }
    function shortId(id) {
      if (!id) return common_vendor.emptyDisplay(id, "order");
      return id.length > 12 ? id.substring(0, 12) : id;
    }
    function formatTime(t) {
      return common_vendor.formatDateTimeShort(t, "暂无");
    }
    async function onDetail(item) {
      var _a, _b;
      let detail = { ...item };
      let canReplyFromApi;
      try {
        const res = await utils_merchantApi.merchantApi.disputeDetail(item.ticketId);
        if (res == null ? void 0 : res.ticket) detail = { ...item, ...res.ticket };
        canReplyFromApi = res == null ? void 0 : res.canReply;
        const lastMsg = ((_a = res == null ? void 0 : res.messages) == null ? void 0 : _a.length) ? (_b = res.messages[res.messages.length - 1]) == null ? void 0 : _b.body : detail.lastMessage;
        if (lastMsg) detail = { ...detail, lastMessage: lastMsg };
      } catch {
      }
      const amount = detail.billedAmountCents != null ? common_vendor.fmtMoney(detail.billedAmountCents) : "";
      const lines = [
        `单号：${common_vendor.emptyDisplay(detail.ticketId, "order")}`,
        `状态：${statusText(detail.status)}`,
        `柜机：${common_vendor.emptyDisplay(detail.deviceId, "device")}`,
        detail.orderId ? `订单：${detail.orderId}` : "",
        amount ? `金额：${amount}` : "",
        `原因：${common_vendor.localizeDisputeReason(detail.reason) || common_vendor.emptyDisplay(detail.reason, "reason")}`,
        detail.lastMessage ? `最新：${detail.lastMessage}` : ""
      ].filter(Boolean).join("\n");
      const replyable = canReplyFromApi != null ? canReplyFromApi && canReply.value : canReplyTicket(detail);
      const hasOrder = !!detail.orderId;
      common_vendor.index.showModal({
        title: "争议详情",
        content: lines,
        showCancel: true,
        cancelText: replyable ? "关闭" : hasOrder || detail.deviceId ? "关闭" : "知道了",
        confirmText: replyable ? "回复" : hasOrder ? "查看订单" : detail.deviceId ? "查看柜机" : "知道了",
        success(res) {
          if (!res.confirm) return;
          if (replyable) {
            onReply(detail);
            return;
          }
          if (hasOrder) {
            common_vendor.index.navigateTo({
              url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(detail.orderId)}`
            });
            return;
          }
          if (detail.deviceId) {
            common_vendor.index.navigateTo({
              url: `/pages/device-detail/device-detail?id=${encodeURIComponent(detail.deviceId)}`
            });
          }
        }
      });
    }
    async function onReply(item) {
      if (!canReply.value) {
        common_vendor.index.showToast({ title: "无回复权限", icon: "none" });
        return;
      }
      const body = await utils_textPrompt.promptText({
        title: "回复争议",
        hint: "回复内容将同步给消费者与运营",
        placeholder: "填写商户回复内容",
        required: true,
        requiredMessage: "请填写回复内容",
        maxLength: 200,
        testId: "dispute-reply-prompt"
      });
      if (body == null) return;
      try {
        await utils_merchantApi.merchantApi.disputeReply(item.ticketId, body);
        common_vendor.index.showToast({ title: "已回复", icon: "success" });
        await load();
      } catch (e) {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "回复失败", icon: "none" });
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.f(tabs, (t, k0, i0) => {
          return {
            a: common_vendor.t(t.label),
            b: t.key,
            c: activeTab.value === t.key ? 1 : "",
            d: common_vendor.o(($event) => switchTab(t.key), t.key)
          };
        }),
        b: loading.value
      }, loading.value ? {} : error.value ? {
        d: common_vendor.t(error.value),
        e: common_vendor.o(load)
      } : !list.value.length ? {
        g: common_vendor.p({
          icon: "审",
          title: `暂无${activeTabLabel.value}争议`,
          hint: "用户申诉与识别复核会显示在这里"
        })
      } : common_vendor.e({
        h: common_vendor.f(list.value, (item, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(shortId(item.ticketId)),
            b: common_vendor.t(statusText(item.status)),
            c: common_vendor.n(item.status),
            d: common_vendor.t(common_vendor.unref(common_vendor.localizeDisputeReason)(item.reason) || "争议"),
            e: common_vendor.t(item.deviceId || "无柜机"),
            f: common_vendor.t(formatTime(item.createdAt)),
            g: item.lastMessage
          }, item.lastMessage ? {
            h: common_vendor.t(item.lastMessage)
          } : {}, {
            i: canReplyTicket(item)
          }, canReplyTicket(item) ? {
            j: common_vendor.o(($event) => onReply(item), item.ticketId)
          } : {}, {
            k: item.ticketId,
            l: `争议 ${shortId(item.ticketId)} ${statusText(item.status)}`,
            m: common_vendor.o(($event) => onDetail(item), item.ticketId)
          });
        }),
        i: listTruncated.value
      }, listTruncated.value ? {
        j: common_vendor.t(list.value.length),
        k: common_vendor.t(listTotal.value)
      } : {}), {
        c: error.value,
        f: !list.value.length
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-a3dc0987"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/disputes/disputes.vue"]]);
wx.createPage(MiniProgramPage);
