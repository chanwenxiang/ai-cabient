"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_disputeForm = require("../../utils/dispute-form.js");
const utils_disputeCopy = require("../../utils/dispute-copy.js");
const utils_disputeEvidence = require("../../utils/dispute-evidence.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "order-detail",
  setup(__props) {
    const orderId = common_vendor.ref("");
    const order = common_vendor.ref(null);
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const videoUrl = common_vendor.ref("");
    const showDispute = common_vendor.ref(false);
    const refundMode = common_vendor.ref(false);
    const disputeReason = common_vendor.ref("");
    const disputeLoading = common_vendor.ref(false);
    const refundLoading = common_vendor.ref(false);
    const disputeFiled = common_vendor.ref(false);
    const refundDone = common_vendor.ref(false);
    const reasonChips = utils_disputeForm.DISPUTE_REASON_CHIPS;
    const selectedCategory = common_vendor.ref("USER_APPEAL");
    const evidence = common_vendor.ref([]);
    const supportPhoneDisplay = common_vendor.ref("400-888-0018");
    const supportPhoneDial = common_vendor.ref("4008880018");
    let bootstrapPromise = null;
    let bootstrapTarget = "";
    function resolveOrderId(opt) {
      const fromOpt = String((opt == null ? void 0 : opt.orderId) || (opt == null ? void 0 : opt.id) || "").trim();
      if (fromOpt) return fromOpt;
      if (typeof window === "undefined" || typeof window.location === "undefined") return "";
      try {
        const hash = String(window.location.hash || "");
        const hashQuery = hash.includes("?") ? hash.slice(hash.indexOf("?") + 1) : "";
        const search = String(window.location.search || "").replace(/^\?/, "");
        const q = new URLSearchParams(hashQuery || search);
        return String(q.get("orderId") || q.get("id") || "").trim();
      } catch {
        return "";
      }
    }
    async function bootstrap(opt) {
      const nextId = resolveOrderId(opt);
      if (!nextId) {
        orderId.value = "";
        error.value = "缺少订单编号";
        loading.value = false;
        return;
      }
      if (bootstrapPromise && bootstrapTarget === nextId) {
        await bootstrapPromise;
        return;
      }
      const idChanged = nextId !== orderId.value;
      orderId.value = nextId;
      if (idChanged) {
        disputeFiled.value = false;
        refundDone.value = false;
        showDispute.value = false;
      }
      bootstrapTarget = nextId;
      bootstrapPromise = (async () => {
        void loadSupportPhone();
        await reload();
      })().finally(() => {
        if (bootstrapTarget === nextId) {
          bootstrapPromise = null;
          bootstrapTarget = "";
        }
      });
      await bootstrapPromise;
    }
    common_vendor.onLoad((opt) => {
      void bootstrap(opt);
    });
    common_vendor.onShow(() => {
      void bootstrap();
    });
    function onHashChange() {
      void bootstrap();
    }
    common_vendor.onMounted(() => {
      if (typeof window !== "undefined") {
        window.addEventListener("hashchange", onHashChange);
      }
    });
    common_vendor.onUnmounted(() => {
      if (typeof window !== "undefined") {
        window.removeEventListener("hashchange", onHashChange);
      }
    });
    async function loadSupportPhone() {
      try {
        const cfg = await utils_consumerApi.consumerApi.consumerPublicConfig();
        const phone = String((cfg == null ? void 0 : cfg.servicePhone) || (cfg == null ? void 0 : cfg["consumer.service_phone"]) || "").trim();
        if (phone) {
          supportPhoneDisplay.value = phone;
          supportPhoneDial.value = phone.replace(/[^\d+]/g, "");
        }
      } catch {
      }
    }
    async function reload() {
      var _a, _b, _c;
      if (!orderId.value) {
        error.value = "缺少订单编号";
        loading.value = false;
        return;
      }
      loading.value = true;
      error.value = "";
      try {
        const res = await utils_consumerApi.get("/api/v2/orders/" + orderId.value);
        order.value = res.data;
        if ((_a = order.value) == null ? void 0 : _a.videoUri) videoUrl.value = order.value.videoUri;
        if (((_b = order.value) == null ? void 0 : _b.status) === "DISPUTED") disputeFiled.value = true;
        if (((_c = order.value) == null ? void 0 : _c.status) === "REFUNDED") {
          refundDone.value = true;
          disputeFiled.value = true;
        }
      } catch (e) {
        error.value = (e == null ? void 0 : e.message) || "加载失败";
      } finally {
        loading.value = false;
      }
    }
    const statusIcon = common_vendor.computed(() => {
      var _a;
      const map = {
        paid: "✓",
        completed: "✓",
        refunded: "↩",
        partial_refunded: "↩",
        disputed: "!",
        failed: "✕",
        cancelled: "无"
      };
      return map[(((_a = order.value) == null ? void 0 : _a.status) || "").toLowerCase()] || "✓";
    });
    const statusTitle = common_vendor.computed(() => {
      var _a;
      return common_vendor.orderStatusLabel((_a = order.value) == null ? void 0 : _a.status) || "订单详情";
    });
    const canDispute = common_vendor.computed(() => {
      var _a, _b;
      const s = (_a = order.value) == null ? void 0 : _a.status;
      if (!((_b = order.value) == null ? void 0 : _b.sessionId) || disputeFiled.value) return false;
      if (s === "REFUNDED" || s === "PARTIAL_REFUNDED" || s === "DISPUTED" || s === "CANCELLED" || s === "FAILED") {
        return false;
      }
      return s === "PAID" || s === "COMPLETED";
    });
    const autoRefundEnabled = common_vendor.computed(() => {
      var _a;
      return ((_a = order.value) == null ? void 0 : _a.refundPolicy) !== "DISPUTE_ONLY";
    });
    const canRefund = common_vendor.computed(() => {
      var _a, _b;
      const s = (_a = order.value) == null ? void 0 : _a.status;
      return autoRefundEnabled.value && !!((_b = order.value) == null ? void 0 : _b.orderId) && !refundDone.value && (s === "PAID" || s === "COMPLETED");
    });
    const statusDetail = common_vendor.computed(() => {
      var _a, _b, _c, _d, _e, _f, _g, _h;
      if (((_a = order.value) == null ? void 0 : _a.status) === "PAID" || ((_b = order.value) == null ? void 0 : _b.status) === "COMPLETED") {
        return autoRefundEnabled.value ? "关门自动扣款成功，如有疑问可立即退款或提交申诉" : "关门自动扣款成功，如有疑问请提交账单申诉，由运营审核后退款";
      }
      if (((_c = order.value) == null ? void 0 : _c.status) === "REFUNDED") return "已退款至原支付渠道或账户余额";
      if (((_d = order.value) == null ? void 0 : _d.status) === "PARTIAL_REFUNDED") return "本单已部分退款，可在账单明细中核对金额";
      if (((_e = order.value) == null ? void 0 : _e.status) === "DISPUTED") return "账单审核中，请耐心等待";
      if (((_f = order.value) == null ? void 0 : _f.status) === "PENDING" || ((_g = order.value) == null ? void 0 : _g.status) === "PROCESSING") {
        return "订单待支付，请完成补扣后再继续购物";
      }
      if (((_h = order.value) == null ? void 0 : _h.status) === "CANCELLED") return "本次购物已取消，未产生扣款";
      return "";
    });
    const payChannelText = common_vendor.computed(() => {
      var _a;
      const ch = (_a = order.value) == null ? void 0 : _a.payChannel;
      if (!ch) return "未记录";
      return common_vendor.displayLabel("pay_channel", ch, "未知渠道");
    });
    function formatTime(t) {
      return common_vendor.formatDateTimeMinute(t, "暂无");
    }
    function playVideo() {
      if (!videoUrl.value) return;
      common_vendor.index.setClipboardData({
        data: videoUrl.value,
        success: () => common_vendor.index.showToast({ title: "视频链接已复制，请到浏览器打开", icon: "none" })
      });
    }
    function openDispute() {
      refundMode.value = false;
      disputeReason.value = "";
      selectedCategory.value = "USER_APPEAL";
      evidence.value = [];
      showDispute.value = true;
    }
    function openRefund() {
      refundMode.value = true;
      disputeReason.value = "申请退回本单已扣款项";
      selectedCategory.value = "USER_APPEAL";
      evidence.value = [];
      showDispute.value = true;
    }
    function closeDispute() {
      showDispute.value = false;
    }
    function pickChip(chip) {
      selectedCategory.value = chip.category;
      disputeReason.value = utils_disputeForm.appendChipToReason(disputeReason.value, chip);
    }
    async function onAddEvidence() {
      evidence.value = await utils_disputeEvidence.pickAndUploadEvidence(evidence.value);
    }
    async function removeEvidence(idx) {
      const confirmed = await new Promise((resolve) => {
        common_vendor.index.showModal({
          title: "删除图片",
          content: "确定删除这张申诉附图吗？",
          confirmText: "删除",
          cancelText: "保留",
          success: (res) => resolve(!!res.confirm),
          fail: () => resolve(false)
        });
      });
      if (!confirmed) return;
      evidence.value = utils_disputeEvidence.removeEvidenceAt(evidence.value, idx);
    }
    async function submitAction() {
      if (refundMode.value) await submitRefund();
      else await submitDispute();
    }
    async function submitDispute() {
      var _a;
      const sessionId = (_a = order.value) == null ? void 0 : _a.sessionId;
      const reason = disputeReason.value.trim();
      if (!sessionId) {
        common_vendor.index.showToast({ title: "缺少订单信息", icon: "none" });
        return;
      }
      if (reason.length < 4) {
        common_vendor.index.showToast({ title: "请至少填写 4 个字", icon: "none" });
        return;
      }
      if (evidence.value.some((e) => e.uploading)) {
        common_vendor.index.showToast({ title: "图片仍在上传", icon: "none" });
        return;
      }
      disputeLoading.value = true;
      try {
        await utils_consumerApi.consumerApi.fileDispute({
          sessionId,
          reason,
          category: selectedCategory.value || "USER_APPEAL",
          priority: "NORMAL",
          evidenceFileIds: utils_disputeEvidence.evidenceFileIds(evidence.value)
        });
        disputeFiled.value = true;
        showDispute.value = false;
        common_vendor.index.showToast({ title: "申诉已提交", icon: "success" });
        await reload();
      } catch (e) {
        common_vendor.index.showToast({ title: utils_disputeCopy.consumerAppealErrorMessage(e, "提交失败"), icon: "none" });
      } finally {
        disputeLoading.value = false;
      }
    }
    async function submitRefund() {
      var _a;
      const oid = (_a = order.value) == null ? void 0 : _a.orderId;
      const reason = disputeReason.value.trim();
      if (!oid) {
        common_vendor.index.showToast({ title: "缺少订单编号", icon: "none" });
        return;
      }
      if (reason.length < 4) {
        common_vendor.index.showToast({ title: "请至少填写 4 字退款原因", icon: "none" });
        return;
      }
      if (evidence.value.some((e) => e.uploading)) {
        common_vendor.index.showToast({ title: "图片仍在上传", icon: "none" });
        return;
      }
      const confirmed = await new Promise(
        (resolve) => common_vendor.index.showModal({
          title: "确认退款",
          content: "将立即原路退回本单金额，是否继续？",
          confirmText: "确认退款",
          success: (r) => resolve(!!r.confirm),
          fail: () => resolve(false)
        })
      );
      if (!confirmed) return;
      refundLoading.value = true;
      try {
        const result = await utils_consumerApi.consumerApi.refundOrder(oid, {
          reason,
          evidenceFileIds: utils_disputeEvidence.evidenceFileIds(evidence.value)
        });
        refundDone.value = true;
        disputeFiled.value = true;
        showDispute.value = false;
        common_vendor.index.showToast({ title: result.message || "退款成功", icon: "success" });
        await reload();
      } catch (e) {
        common_vendor.index.showToast({ title: utils_disputeCopy.consumerAppealErrorMessage(e, "退款失败"), icon: "none" });
      } finally {
        refundLoading.value = false;
      }
    }
    function reopenCabinet() {
      var _a;
      const id = (_a = order.value) == null ? void 0 : _a.deviceId;
      if (!id) {
        common_vendor.index.showToast({ title: "缺少柜机编号", icon: "none" });
        return;
      }
      common_vendor.index.setStorageSync("reopen_device_id", id);
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    function goHelp() {
      common_vendor.index.navigateTo({ url: "/pages/help/help" });
    }
    function callSupport() {
      common_vendor.index.makePhoneCall({
        phoneNumber: supportPhoneDial.value,
        fail: () => common_vendor.index.showToast({ title: `请拨打 ${supportPhoneDisplay.value}`, icon: "none" })
      });
    }
    return (_ctx, _cache) => {
      var _a, _b, _c, _d, _e, _f, _g, _h, _i, _j, _k, _l, _m, _n, _o, _p, _q;
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value),
        d: common_vendor.o(reload)
      } : common_vendor.e({
        e: common_vendor.t(statusIcon.value),
        f: common_vendor.t(statusTitle.value),
        g: common_vendor.t(statusDetail.value),
        h: common_vendor.n("status-" + (((_a = order.value) == null ? void 0 : _a.status) || "").toLowerCase()),
        i: common_vendor.f(((_b = order.value) == null ? void 0 : _b.lines) || [], (item, k0, i0) => {
          return {
            a: common_vendor.t(item.skuName || item.skuId || "商品"),
            b: common_vendor.t(item.quantity),
            c: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(item.lineAmountCents)),
            d: item.skuId
          };
        }),
        j: !(((_c = order.value) == null ? void 0 : _c.lines) || []).length
      }, !(((_d = order.value) == null ? void 0 : _d.lines) || []).length ? {} : {}, {
        k: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(((_e = order.value) == null ? void 0 : _e.originalAmountCents) || ((_f = order.value) == null ? void 0 : _f.totalAmountCents) || 0)),
        l: (_g = order.value) == null ? void 0 : _g.couponDiscountCents
      }, ((_h = order.value) == null ? void 0 : _h.couponDiscountCents) ? {
        m: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(order.value.couponDiscountCents))
      } : {}, {
        n: (_i = order.value) == null ? void 0 : _i.couponDiscountCents
      }, ((_j = order.value) == null ? void 0 : _j.couponDiscountCents) ? {
        o: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(((_k = order.value) == null ? void 0 : _k.totalAmountCents) || 0))
      } : {}, {
        p: common_vendor.t(payChannelText.value),
        q: common_vendor.t(formatTime(((_l = order.value) == null ? void 0 : _l.payTime) || ((_m = order.value) == null ? void 0 : _m.createdAt))),
        r: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)((_n = order.value) == null ? void 0 : _n.orderId, "order")),
        s: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)((_o = order.value) == null ? void 0 : _o.deviceId, "device")),
        t: (_p = order.value) == null ? void 0 : _p.deviceId
      }, ((_q = order.value) == null ? void 0 : _q.deviceId) ? {
        v: common_vendor.o(reopenCabinet)
      } : {}, {
        w: videoUrl.value
      }, videoUrl.value ? {
        x: common_vendor.o(playVideo)
      } : {}, {
        y: canRefund.value
      }, canRefund.value ? {
        z: common_vendor.t(refundDone.value ? "已退款" : "立即退款"),
        A: refundLoading.value || disputeLoading.value,
        B: common_vendor.o(openRefund)
      } : {}, {
        C: canDispute.value
      }, canDispute.value ? {
        D: common_vendor.t(disputeFiled.value ? "申诉已提交" : autoRefundEnabled.value ? "提交账单申诉" : "申请退款 / 账单申诉"),
        E: disputeLoading.value || refundLoading.value,
        F: common_vendor.o(openDispute)
      } : {}, {
        G: common_vendor.o(goHelp),
        H: common_vendor.t(supportPhoneDisplay.value),
        I: common_vendor.o(callSupport)
      }), {
        b: error.value,
        J: showDispute.value
      }, showDispute.value ? common_vendor.e({
        K: common_vendor.t(refundMode.value ? "立即退款" : "申请退款 / 账单申诉"),
        L: common_vendor.t(refundMode.value ? "将原路退回本单已扣款项（余额/微信/支付宝）。可上传凭证图片辅助核对。" : "仅提交申诉工单，运营审核后再退款。可上传凭证图片。"),
        M: common_vendor.f(common_vendor.unref(reasonChips), (chip, k0, i0) => {
          return {
            a: common_vendor.t(chip.label),
            b: chip.label,
            c: selectedCategory.value === chip.category ? 1 : "",
            d: common_vendor.o(($event) => pickChip(chip), chip.label)
          };
        }),
        N: disputeReason.value,
        O: common_vendor.o(($event) => disputeReason.value = $event.detail.value),
        P: common_vendor.f(evidence.value, (img, idx, i0) => {
          return common_vendor.e({
            a: common_vendor.unref(utils_disputeEvidence.previewEvidenceSrc)(img),
            b: `证据图 ${idx + 1}`,
            c: common_vendor.o(($event) => removeEvidence(idx), img.localPath + idx),
            d: img.uploading
          }, img.uploading ? {} : {}, {
            e: img.localPath + idx
          });
        }),
        Q: evidence.value.length < 5
      }, evidence.value.length < 5 ? {
        R: common_vendor.o(onAddEvidence)
      } : {}, {
        S: common_vendor.t(refundMode.value ? refundLoading.value ? "退款中…" : "确认退款" : disputeLoading.value ? "提交中…" : "提交申诉"),
        T: disputeLoading.value || refundLoading.value,
        U: disputeLoading.value || refundLoading.value,
        V: common_vendor.o(submitAction),
        W: common_vendor.o(closeDispute),
        X: common_vendor.o(() => {
        }),
        Y: common_vendor.o(closeDispute)
      }) : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-48de6e3f"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/order-detail/order-detail.vue"]]);
wx.createPage(MiniProgramPage);
