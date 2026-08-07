"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_disputeForm = require("../../utils/dispute-form.js");
const utils_disputeCopy = require("../../utils/dispute-copy.js");
const utils_disputeEvidence = require("../../utils/dispute-evidence.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "result",
  setup(__props) {
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const order = common_vendor.ref(null);
    const statusLabel = common_vendor.ref("");
    const statusTone = common_vendor.computed(() => {
      var _a;
      const s = (((_a = order.value) == null ? void 0 : _a.status) || "").toUpperCase();
      if (s === "DISPUTED") return "warn";
      if (s === "REFUNDED" || s === "PARTIAL_REFUNDED") return "refund";
      if (s === "FAILED" || s === "CANCELLED") return "muted";
      if (s === "PENDING" || s === "PROCESSING") return "pending";
      return "ok";
    });
    const statusIcon = common_vendor.computed(() => {
      const map = {
        ok: "✓",
        warn: "!",
        refund: "↩",
        muted: "无",
        pending: "…"
      };
      return map[statusTone.value] || "✓";
    });
    const headerTitle = common_vendor.computed(() => {
      var _a, _b;
      const s = (((_a = order.value) == null ? void 0 : _a.status) || "").toUpperCase();
      if (s === "DISPUTED") return "账单审核中";
      if (s === "REFUNDED" || s === "PARTIAL_REFUNDED") return "退款已处理";
      if (s === "PENDING" || s === "PROCESSING") return "待支付";
      if (s === "FAILED" || s === "CANCELLED") return "本次未完成";
      return (((_b = order.value) == null ? void 0 : _b.totalAmountCents) || 0) > 0 ? "购物完成" : "感谢使用";
    });
    let sessionId = "";
    let loadedKey = "";
    const deviceId = common_vendor.ref("");
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
    const canRefundNow = common_vendor.computed(
      () => {
        var _a, _b, _c, _d;
        return !!((_a = order.value) == null ? void 0 : _a.orderId) && !refundDone.value && !disputeFiled.value && (((_b = order.value) == null ? void 0 : _b.totalAmountCents) || 0) > 0 && ((_c = order.value) == null ? void 0 : _c.refundPolicy) !== "DISPUTE_ONLY" && ["PAID", "COMPLETED"].includes(String(((_d = order.value) == null ? void 0 : _d.status) || ""));
      }
    );
    const payChannelText = common_vendor.computed(() => {
      var _a;
      const ch = String(((_a = order.value) == null ? void 0 : _a.payChannel) || "").toUpperCase();
      if (!ch) return "";
      return common_vendor.displayLabel("pay_channel", ch, "");
    });
    common_vendor.onLoad((opts) => {
      void bootstrap(opts);
    });
    common_vendor.onShow(() => {
      const pages = getCurrentPages();
      const cur = pages[pages.length - 1];
      void bootstrap({ ...readHashQuery(), ...(cur == null ? void 0 : cur.options) || {} });
    });
    function readHashQuery() {
      return {};
    }
    async function bootstrap(opts) {
      const nextSession = String((opts == null ? void 0 : opts.sessionId) || "").trim();
      const nextOrder = String((opts == null ? void 0 : opts.orderId) || "").trim();
      const key = `${nextOrder}|${nextSession}`;
      if (key === loadedKey && (order.value || error.value)) return;
      loadedKey = key;
      sessionId = nextSession;
      order.value = null;
      error.value = "";
      disputeFiled.value = false;
      refundDone.value = false;
      showDispute.value = false;
      loading.value = true;
      if (nextOrder) {
        await loadByOrderId(nextOrder);
        return;
      }
      if (nextSession) {
        await loadBySession(nextSession);
        return;
      }
      error.value = "缺少订单信息";
      loading.value = false;
    }
    async function loadBySession(sid) {
      var _a, _b, _c;
      try {
        const sess = await utils_consumerApi.consumerApi.getSession(sid);
        deviceId.value = sess.deviceId || "";
        order.value = await utils_consumerApi.consumerApi.getSessionOrder(sid);
        statusLabel.value = common_vendor.orderStatusLabel((_a = order.value) == null ? void 0 : _a.status);
        if (((_b = order.value) == null ? void 0 : _b.status) === "DISPUTED") disputeFiled.value = true;
        if (((_c = order.value) == null ? void 0 : _c.status) === "REFUNDED") {
          refundDone.value = true;
          disputeFiled.value = true;
        }
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
    }
    async function loadByOrderId(oid) {
      var _a, _b, _c, _d, _e;
      try {
        order.value = await utils_consumerApi.consumerApi.getOrder(oid);
        statusLabel.value = common_vendor.orderStatusLabel((_a = order.value) == null ? void 0 : _a.status);
        sessionId = ((_b = order.value) == null ? void 0 : _b.sessionId) || sessionId;
        deviceId.value = ((_c = order.value) == null ? void 0 : _c.deviceId) || deviceId.value;
        if (((_d = order.value) == null ? void 0 : _d.status) === "DISPUTED") disputeFiled.value = true;
        if (((_e = order.value) == null ? void 0 : _e.status) === "REFUNDED") {
          refundDone.value = true;
          disputeFiled.value = true;
        }
      } catch (e) {
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        loading.value = false;
      }
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
        loadedKey = "";
        if ((_a = order.value) == null ? void 0 : _a.orderId) {
          await loadByOrderId(order.value.orderId);
        } else if (sessionId) {
          await loadBySession(sessionId);
        }
        common_vendor.index.showToast({ title: "申诉已提交", icon: "success" });
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
        statusLabel.value = "已退款";
        common_vendor.index.showToast({ title: result.message || "退款成功", icon: "success" });
      } catch (e) {
        common_vendor.index.showToast({ title: utils_disputeCopy.consumerAppealErrorMessage(e, "退款失败"), icon: "none" });
      } finally {
        refundLoading.value = false;
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
    function goHome() {
      common_vendor.index.switchTab({ url: "/pages/index/index" });
    }
    function goOrders() {
      common_vendor.index.switchTab({ url: "/pages/orders/orders" });
    }
    function goHelp() {
      common_vendor.index.navigateTo({ url: "/pages/help/help" });
    }
    return (_ctx, _cache) => {
      var _a, _b;
      return common_vendor.e({
        a: loading.value
      }, loading.value ? {} : error.value ? {
        c: common_vendor.t(error.value),
        d: common_vendor.o(goHome),
        e: common_vendor.o(goOrders)
      } : order.value ? common_vendor.e({
        g: common_vendor.t(statusIcon.value),
        h: common_vendor.t(headerTitle.value),
        i: common_vendor.t(statusLabel.value),
        j: common_vendor.n("tone-" + statusTone.value),
        k: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(order.value.totalAmountCents)),
        l: payChannelText.value
      }, payChannelText.value ? {
        m: common_vendor.t(payChannelText.value)
      } : {}, {
        n: order.value.totalAmountCents <= 0
      }, order.value.totalAmountCents <= 0 ? {} : {}, {
        o: order.value.balanceBeforeCents != null && order.value.balanceAfterCents != null
      }, order.value.balanceBeforeCents != null && order.value.balanceAfterCents != null ? {
        p: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(order.value.balanceBeforeCents)),
        q: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(order.value.balanceAfterCents))
      } : {}, {
        r: (_a = order.value.lines) == null ? void 0 : _a.length
      }, ((_b = order.value.lines) == null ? void 0 : _b.length) ? {
        s: common_vendor.f(order.value.lines, (line, i, i0) => {
          return {
            a: common_vendor.t(line.skuName || line.skuId),
            b: common_vendor.t(line.quantity),
            c: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(line.lineAmountCents)),
            d: i
          };
        })
      } : {}, {
        t: order.value.originalAmountCents != null && order.value.originalAmountCents !== order.value.totalAmountCents
      }, order.value.originalAmountCents != null && order.value.originalAmountCents !== order.value.totalAmountCents ? {
        v: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(order.value.originalAmountCents))
      } : {}, {
        w: order.value.couponDiscountCents
      }, order.value.couponDiscountCents ? {
        x: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(order.value.couponDiscountCents))
      } : {}, {
        y: order.value.couponDiscountCents
      }, order.value.couponDiscountCents ? {} : {}, {
        z: common_vendor.o(continueShop),
        A: common_vendor.o(goOrders),
        B: common_vendor.unref(sessionId) && !disputeFiled.value && !refundDone.value
      }, common_vendor.unref(sessionId) && !disputeFiled.value && !refundDone.value ? common_vendor.e({
        C: common_vendor.o(openDispute),
        D: canRefundNow.value
      }, canRefundNow.value ? {} : {}, {
        E: canRefundNow.value
      }, canRefundNow.value ? {
        F: common_vendor.o(openRefund)
      } : {}, {
        G: common_vendor.o(goHelp)
      }) : disputeFiled.value && !refundDone.value ? {} : refundDone.value ? {} : {}, {
        H: disputeFiled.value && !refundDone.value,
        I: refundDone.value
      }) : {
        J: common_vendor.o(goHome),
        K: common_vendor.o(goOrders)
      }, {
        b: error.value,
        f: order.value,
        L: showDispute.value
      }, showDispute.value ? common_vendor.e({
        M: common_vendor.t(refundMode.value ? "立即退款" : "账单申诉"),
        N: common_vendor.t(refundMode.value ? "将原路退回本单已扣款项，可上传凭证图片" : "提交申诉后由运营审核；可上传凭证图片"),
        O: common_vendor.f(common_vendor.unref(reasonChips), (chip, k0, i0) => {
          return {
            a: common_vendor.t(chip.label),
            b: chip.label,
            c: selectedCategory.value === chip.category ? 1 : "",
            d: common_vendor.o(($event) => pickChip(chip), chip.label)
          };
        }),
        P: disputeReason.value,
        Q: common_vendor.o(($event) => disputeReason.value = $event.detail.value),
        R: common_vendor.f(evidence.value, (img, idx, i0) => {
          return common_vendor.e({
            a: common_vendor.unref(utils_disputeEvidence.previewEvidenceSrc)(img),
            b: `证据图 ${idx + 1}`,
            c: common_vendor.o(($event) => removeEvidence(idx), img.localPath + idx),
            d: img.uploading
          }, img.uploading ? {} : {}, {
            e: img.localPath + idx
          });
        }),
        S: evidence.value.length < 5
      }, evidence.value.length < 5 ? {
        T: common_vendor.o(onAddEvidence)
      } : {}, {
        U: common_vendor.t(refundMode.value ? refundLoading.value ? "退款中…" : "确认退款" : disputeLoading.value ? "提交中…" : "提交申诉"),
        V: disputeLoading.value || refundLoading.value,
        W: disputeLoading.value || refundLoading.value,
        X: common_vendor.o(submitAction),
        Y: common_vendor.o(closeDispute),
        Z: common_vendor.o(() => {
        }),
        aa: common_vendor.o(closeDispute)
      }) : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-d38065ce"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/result/result.vue"]]);
wx.createPage(MiniProgramPage);
