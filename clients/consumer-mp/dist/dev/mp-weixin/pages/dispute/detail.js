"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_disputeCopy = require("../../utils/dispute-copy.js");
const utils_disputeEvidence = require("../../utils/dispute-evidence.js");
if (!Array) {
  const _component_empty_state = common_vendor.resolveComponent("empty-state");
  _component_empty_state();
}
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "detail",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const ticket = common_vendor.ref(null);
    const sessionId = common_vendor.ref("");
    const ticketId = common_vendor.ref("");
    const servicePhone = common_vendor.ref("400-888-0018");
    const evidenceLocalSrc = common_vendor.ref({});
    const copy = common_vendor.computed(() => utils_disputeCopy.consumerDisputeReviewCopy(ticket.value));
    const isResolved = common_vendor.computed(() => {
      var _a;
      return ((_a = ticket.value) == null ? void 0 : _a.status) === "RESOLVED";
    });
    const suggestedLines = common_vendor.computed(() => {
      var _a;
      return ((_a = ticket.value) == null ? void 0 : _a.suggestedItems) || [];
    });
    const resolutionLines = common_vendor.computed(() => {
      var _a;
      return ((_a = ticket.value) == null ? void 0 : _a.resolutionItems) || [];
    });
    const statusText = common_vendor.computed(() => {
      var _a;
      const s = ((_a = ticket.value) == null ? void 0 : _a.status) || "";
      if (s === "OPEN") return "审核中 · 暂未扣款";
      if (s === "RESOLVED") return "已处理完成";
      if (s === "CLOSED") return "已关闭";
      return common_vendor.displayLabel("dispute_status", s, "处理中");
    });
    common_vendor.onLoad((opts) => {
      applyQuery(opts);
    });
    common_vendor.onShow(() => {
      const pages = getCurrentPages();
      const cur = pages[pages.length - 1];
      applyQuery({ ...readHashQuery(), ...(cur == null ? void 0 : cur.options) || {} });
      void bootstrap();
      loadServicePhone();
    });
    function readHashQuery() {
      return {};
    }
    function applyQuery(opts) {
      if (!opts) return;
      if (opts.ticketId) ticketId.value = String(opts.ticketId);
      if (opts.sessionId) sessionId.value = String(opts.sessionId);
    }
    function disputeRedirectPath() {
      const q = [
        ticketId.value ? `ticketId=${encodeURIComponent(ticketId.value)}` : "",
        sessionId.value ? `sessionId=${encodeURIComponent(sessionId.value)}` : ""
      ].filter(Boolean).join("&");
      return `/pages/dispute/detail${q ? `?${q}` : ""}`;
    }
    async function bootstrap() {
      if (!await utils_consumerApi.requireConsumerAuth("查看审核详情需先完成登录", disputeRedirectPath())) {
        loading.value = false;
        error.value = "请先登录后查看审核详情";
        ticket.value = null;
        return;
      }
      await reload();
    }
    async function loadServicePhone() {
      try {
        const cfg = await utils_consumerApi.consumerApi.consumerPublicConfig();
        if (cfg == null ? void 0 : cfg.servicePhone) servicePhone.value = cfg.servicePhone;
      } catch {
      }
    }
    async function reload() {
      if (!utils_consumerApi.getConsumerToken()) {
        error.value = "请先登录后查看审核详情";
        ticket.value = null;
        loading.value = false;
        return;
      }
      if (!ticketId.value && !sessionId.value) {
        error.value = "缺少审核单参数";
        ticket.value = null;
        loading.value = false;
        return;
      }
      loading.value = true;
      error.value = "";
      try {
        const found = await utils_consumerApi.consumerApi.getMyDispute({
          ticketId: ticketId.value || void 0,
          sessionId: sessionId.value || void 0
        });
        ticket.value = found;
        sessionId.value = found.sessionId || sessionId.value;
        ticketId.value = found.ticketId || ticketId.value;
        void hydrateEvidencePreviews();
      } catch (e) {
        try {
          const list = await utils_consumerApi.consumerApi.listMyDisputes();
          let found = null;
          if (ticketId.value) {
            found = list.find((d) => d.ticketId === ticketId.value) || null;
          }
          if (!found && sessionId.value) {
            found = list.find((d) => d.sessionId === sessionId.value) || null;
          }
          if (!found) {
            ticket.value = null;
            error.value = e instanceof Error ? e.message : "未找到该审核单，可能已归档或尚未生成";
            return;
          }
          ticket.value = found;
          sessionId.value = found.sessionId || sessionId.value;
          ticketId.value = found.ticketId || ticketId.value;
          void hydrateEvidencePreviews();
        } catch (e2) {
          error.value = e2 instanceof Error ? e2.message : "加载失败";
        }
      } finally {
        loading.value = false;
      }
    }
    function evidenceKey(img) {
      return String(img.fileId || img.url || "");
    }
    function evidenceSrc(img) {
      const key = evidenceKey(img);
      return key && evidenceLocalSrc.value[key] || "";
    }
    async function hydrateEvidencePreviews() {
      var _a;
      const list = ((_a = ticket.value) == null ? void 0 : _a.evidence) || [];
      if (!list.length) {
        evidenceLocalSrc.value = {};
        return;
      }
      const next = { ...evidenceLocalSrc.value };
      await Promise.all(
        list.map(async (img) => {
          const key = evidenceKey(img);
          if (!key || next[key]) return;
          const local = await utils_disputeEvidence.fetchEvidenceLocalPath(img.url);
          if (local) next[key] = local;
        })
      );
      evidenceLocalSrc.value = next;
    }
    function fmtLine(line) {
      const cents = line.lineAmountCents ?? 0;
      return common_vendor.fmtMoney(cents);
    }
    function shortId(id) {
      if (!id) return "暂无购物单号";
      return id.length > 14 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id;
    }
    function formatTime(v) {
      return common_vendor.formatDateTimeMinute(v, "暂无");
    }
    function goOrder() {
      var _a;
      const oid = (_a = ticket.value) == null ? void 0 : _a.orderId;
      if (!oid) return;
      common_vendor.index.navigateTo({
        url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(oid)}`
      });
    }
    function goOrders() {
      common_vendor.index.switchTab({ url: "/pages/orders/orders" });
    }
    function contactOps() {
      common_vendor.index.makePhoneCall({ phoneNumber: servicePhone.value });
    }
    function previewEvidence(img) {
      var _a;
      const src = evidenceSrc(img);
      if (!src) return;
      const list = (((_a = ticket.value) == null ? void 0 : _a.evidence) || []).map((e) => evidenceSrc(e)).filter(Boolean);
      common_vendor.index.previewImage({ urls: list.length ? list : [src], current: src });
    }
    return (_ctx, _cache) => {
      var _a, _b;
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value),
        d: common_vendor.o(bootstrap)
      } : !ticket.value ? {
        f: common_vendor.p({
          icon: "审",
          title: "未找到审核单",
          hint: "可能已归档或尚未生成"
        })
      } : ticket.value ? common_vendor.e({
        h: common_vendor.t(copy.value.icon),
        i: common_vendor.t(copy.value.title),
        j: common_vendor.t(statusText.value),
        k: common_vendor.n("tone-" + copy.value.tone),
        l: common_vendor.t(copy.value.detail),
        m: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(ticket.value.deviceId, "device")),
        n: common_vendor.t(shortId(ticket.value.sessionId)),
        o: ticket.value.createdAt
      }, ticket.value.createdAt ? {
        p: common_vendor.t(formatTime(ticket.value.createdAt))
      } : {}, {
        q: ticket.value.resolvedAt
      }, ticket.value.resolvedAt ? {
        r: common_vendor.t(formatTime(ticket.value.resolvedAt))
      } : {}, {
        s: (_a = ticket.value.evidence) == null ? void 0 : _a.length
      }, ((_b = ticket.value.evidence) == null ? void 0 : _b.length) ? {
        t: common_vendor.f(ticket.value.evidence, (img, k0, i0) => {
          return {
            a: img.fileId,
            b: evidenceSrc(img),
            c: common_vendor.o(($event) => previewEvidence(img), img.fileId)
          };
        })
      } : {}, {
        v: suggestedLines.value.length
      }, suggestedLines.value.length ? {
        w: common_vendor.f(suggestedLines.value, (line, i, i0) => {
          return {
            a: common_vendor.t(line.skuName || line.skuId),
            b: common_vendor.t(line.quantity),
            c: common_vendor.t(fmtLine(line)),
            d: "s-" + i
          };
        })
      } : {}, {
        x: isResolved.value
      }, isResolved.value ? common_vendor.e({
        y: resolutionLines.value.length
      }, resolutionLines.value.length ? {
        z: common_vendor.f(resolutionLines.value, (line, i, i0) => {
          return {
            a: common_vendor.t(line.skuName || line.skuId),
            b: common_vendor.t(line.quantity),
            c: common_vendor.t(fmtLine(line)),
            d: "r-" + i
          };
        })
      } : {}, {
        A: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(ticket.value.billedAmountCents ?? 0))
      }) : {}, {
        B: ticket.value.orderId
      }, ticket.value.orderId ? {
        C: common_vendor.o(goOrder)
      } : {}, {
        D: common_vendor.n(ticket.value.orderId ? "btn-ghost" : "btn-primary"),
        E: common_vendor.o(goOrders),
        F: common_vendor.t(servicePhone.value),
        G: common_vendor.o(contactOps)
      }) : {}, {
        b: error.value,
        e: !ticket.value,
        g: ticket.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-1c73d2c2"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/dispute/detail.vue"]]);
wx.createPage(MiniProgramPage);
