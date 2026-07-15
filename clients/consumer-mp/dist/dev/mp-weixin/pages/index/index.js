"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_recharge = require("../../utils/recharge.js");
const utils_productThumb = require("../../utils/product-thumb.js");
const common_assets = require("../../common/assets.js");
const utils_disputeCopy = require("../../utils/dispute-copy.js");
const utils_notify = require("../../utils/notify.js");
if (!Math) {
  OpenPrepDrawer();
}
const OpenPrepDrawer = () => "../../components/open-prep-drawer.js";
const MIN_BALANCE_CENTS = 500;
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "index",
  setup(__props) {
    const deviceInput = common_vendor.ref("");
    const deviceId = common_vendor.ref("");
    const deviceName = common_vendor.ref("");
    const scanned = common_vendor.ref(false);
    const enteringFlow = common_vendor.ref(false);
    const showManual = common_vendor.ref(false);
    const products = common_vendor.ref([]);
    const productsLoading = common_vendor.ref(false);
    const deviceStatusText = common_vendor.ref("");
    const deviceOffline = common_vendor.ref(false);
    const sessionId = common_vendor.ref("");
    const state = common_vendor.ref("");
    const stateLabel = common_vendor.ref("");
    const stateHint = common_vendor.ref("");
    const stateTone = common_vendor.ref("idle");
    const opening = common_vendor.ref(false);
    const cancelling = common_vendor.ref(false);
    const pollError = common_vendor.ref("");
    const landingError = common_vendor.ref("");
    const reviewSessionId = common_vendor.ref(String(common_vendor.index.getStorageSync("last_disputed_session_id") || ""));
    const reviewTicket = common_vendor.ref(null);
    const reviewCopy = common_vendor.computed(() => utils_disputeCopy.consumerDisputeReviewCopy(reviewTicket.value));
    const servicePhone = common_vendor.ref("400-888-0018");
    const openingSeconds = common_vendor.ref(90);
    const brokenThumbs = common_vendor.ref({});
    const lastDeviceId = common_vendor.ref("");
    const lastDeviceName = common_vendor.ref("");
    const showPrepDrawer = common_vendor.ref(false);
    const prepAccount = common_vendor.ref(null);
    let pollTimer = null;
    let devicePollTimer = null;
    let countdownTimer = null;
    let prepResolve = null;
    const sessionActive = common_vendor.computed(
      () => !!sessionId.value && ["CREATED", "OPENING", "SHOPPING", "RECOGNIZING", "WAITING_UPLOAD", "SETTLING"].includes(state.value)
    );
    const showLanding = common_vendor.computed(() => !scanned.value && !enteringFlow.value);
    const canReopen = common_vendor.computed(
      () => scanned.value && !!deviceId.value && !sessionActive.value && !opening.value && !enteringFlow.value
    );
    const flowOverlayVisible = common_vendor.computed(() => {
      if (showPrepDrawer.value)
        return false;
      if (enteringFlow.value && !scanned.value)
        return true;
      if (opening.value && !sessionId.value)
        return true;
      if (["OPENING", "CREATED", "RECOGNIZING", "WAITING_UPLOAD", "SETTLING"].includes(state.value))
        return true;
      return false;
    });
    const flowOverlayPulse = common_vendor.computed(
      () => ["OPENING", "CREATED", "RECOGNIZING", "SETTLING", "WAITING_UPLOAD"].includes(state.value) || opening.value
    );
    const flowOverlayTitle = common_vendor.computed(() => {
      if (opening.value && !sessionId.value)
        return "正在开门";
      if (stateLabel.value && stateLabel.value !== "-")
        return stateLabel.value;
      return "准备中";
    });
    const flowOverlayHint = common_vendor.computed(() => {
      if (state.value === "OPENING" || state.value === "CREATED") {
        const elapsed = Math.max(0, 90 - openingSeconds.value);
        return `已等待 ${elapsed} 秒，柜门无响应时可安全取消本次开门`;
      }
      if (stateHint.value)
        return stateHint.value;
      if (opening.value)
        return "正在连接柜机并验证开门资格…";
      return "请稍候";
    });
    const cartBarHint = common_vendor.computed(() => {
      if (state.value === "SHOPPING")
        return "门已开 · 请直接取货，无需点选";
      if (!sessionActive.value)
        return "取货后关门自动结算，可再次开门继续购";
      return "关门后自动识别并扣款";
    });
    const cartBarAction = common_vendor.computed(() => {
      if (state.value === "SHOPPING")
        return "购物中";
      if (state.value === "OPENING" || state.value === "CREATED")
        return "开门中";
      if (state.value === "RECOGNIZING" || state.value === "WAITING_UPLOAD")
        return "识别中";
      if (state.value === "SETTLING")
        return "结算中";
      return "进行中";
    });
    function payReady(acc) {
      return (acc.balanceCents || 0) >= MIN_BALANCE_CENTS;
    }
    common_vendor.onLoad(async (opts) => {
      const launch = common_vendor.parseLaunchOptions(opts || {});
      if (launch.deviceId) {
        await startShoppingFlow(launch.deviceId);
      }
    });
    common_vendor.onShow(async () => {
      lastDeviceId.value = common_vendor.index.getStorageSync("last_device_id") || "";
      lastDeviceName.value = common_vendor.index.getStorageSync("last_device_name") || "";
      await loadConsumerConfig();
      await utils_consumerApi.ensureConsumerAuth();
      if (utils_consumerApi.getConsumerToken()) {
        await utils_recharge.resumePendingRechargeIfAny();
        await refreshReviewState();
        if (scanned.value && deviceId.value)
          refreshDeviceStatus();
        const reopen = common_vendor.index.getStorageSync("reopen_device_id");
        if (reopen) {
          common_vendor.index.removeStorageSync("reopen_device_id");
          await startShoppingFlow(reopen);
          return;
        }
        restoreActiveSession();
      }
      startDevicePoll();
    });
    common_vendor.onHide(() => stopDevicePoll());
    common_vendor.onUnload(() => {
      stopPoll();
      stopDevicePoll();
      stopOpeningCountdown();
    });
    function thumbTone(p) {
      const c = p.category || "";
      if (c.includes("饮料"))
        return "drink";
      if (c.includes("零食"))
        return "snack";
      if (c.includes("乳品"))
        return "dairy";
      if (c.includes("方便"))
        return "food";
      return "default";
    }
    function onThumbError(skuId) {
      brokenThumbs.value[skuId] = true;
    }
    function showThumb(p) {
      return utils_productThumb.productThumb(p) && !brokenThumbs.value[p.skuId];
    }
    function resetDevice() {
      if (sessionActive.value) {
        common_vendor.index.showModal({
          title: "购物进行中",
          content: "请先关闭柜门完成结算，或等待当前购物流程结束",
          showCancel: false
        });
        return;
      }
      stopPoll();
      clearSessionUi();
      common_vendor.index.removeStorageSync("active_session_id");
      scanned.value = false;
      enteringFlow.value = false;
      showManual.value = false;
      deviceId.value = "";
      deviceName.value = "";
      deviceStatusText.value = "";
      products.value = [];
    }
    async function startShoppingFlow(id) {
      const cabinetId = id.trim().toUpperCase();
      if (!cabinetId || opening.value || enteringFlow.value)
        return;
      enteringFlow.value = true;
      landingError.value = "";
      if (!await utils_consumerApi.requireConsumerAuth("扫码开门需先完成微信授权")) {
        enteringFlow.value = false;
        return;
      }
      if (!await ensureCanOpenDoor()) {
        enteringFlow.value = false;
        return;
      }
      opening.value = true;
      deviceId.value = cabinetId;
      scanned.value = true;
      try {
        await refreshDeviceStatus();
        if (deviceOffline.value) {
          scanned.value = false;
          deviceId.value = "";
          landingError.value = deviceStatusText.value && deviceStatusText.value !== "离线" ? deviceStatusText.value : "该柜机当前离线或编号无效，请确认柜号后重试，或更换其他柜机。";
          common_vendor.index.showToast({ title: "柜机离线，请换一台或稍后再试", icon: "none" });
          return;
        }
        productsLoading.value = true;
        const [productsResult, sessionResult] = await Promise.allSettled([
          utils_consumerApi.consumerApi.deviceProducts(cabinetId),
          utils_consumerApi.consumerApi.createSession(cabinetId)
        ]);
        if (productsResult.status === "fulfilled") {
          products.value = productsResult.value;
        } else {
          products.value = [];
          common_vendor.index.showToast({ title: common_vendor.formatError(productsResult.reason), icon: "none" });
        }
        if (sessionResult.status !== "fulfilled") {
          scanned.value = false;
          deviceId.value = "";
          landingError.value = common_vendor.formatError(sessionResult.reason);
          common_vendor.index.showToast({ title: landingError.value, icon: "none" });
          return;
        }
        const s = sessionResult.value;
        sessionId.value = s.sessionId;
        common_vendor.index.setStorageSync("active_session_id", s.sessionId);
        applySessionView(s);
        startPoll();
      } finally {
        productsLoading.value = false;
        opening.value = false;
        enteringFlow.value = false;
      }
    }
    function goOrders() {
      common_vendor.index.switchTab({ url: "/pages/orders/orders" });
    }
    function contactOps() {
      common_vendor.index.showModal({
        title: "联系运营",
        content: `请联系客服 ${servicePhone.value}，并提供审核编号：` + reviewSessionId.value,
        showCancel: false,
        confirmText: "我知道了"
      });
    }
    async function loadConsumerConfig() {
      try {
        const cfg = await utils_consumerApi.consumerApi.consumerPublicConfig();
        const phone = (cfg == null ? void 0 : cfg.servicePhone) || (cfg == null ? void 0 : cfg["consumer.service_phone"]);
        if (phone)
          servicePhone.value = phone;
      } catch {
      }
    }
    async function refreshReviewState() {
      const sid = String(common_vendor.index.getStorageSync("last_disputed_session_id") || "");
      if (!sid || !utils_consumerApi.getConsumerToken()) {
        reviewSessionId.value = "";
        reviewTicket.value = null;
        return;
      }
      try {
        const disputes = await utils_consumerApi.consumerApi.listMyDisputes();
        const ticket = disputes.find((d) => d.sessionId === sid);
        if (!ticket || ticket.status !== "OPEN") {
          reviewSessionId.value = "";
          reviewTicket.value = null;
          common_vendor.index.removeStorageSync("last_disputed_session_id");
          if ((ticket == null ? void 0 : ticket.status) === "RESOLVED") {
            utils_notify.showDisputeResolvedToast(ticket);
          }
          return;
        }
        reviewSessionId.value = sid;
        reviewTicket.value = ticket;
      } catch {
        reviewSessionId.value = sid;
        reviewTicket.value = { sessionId: sid, status: "OPEN" };
      }
    }
    function dismissReview() {
      reviewSessionId.value = "";
      reviewTicket.value = null;
      common_vendor.index.removeStorageSync("last_disputed_session_id");
    }
    function ensureCanOpenDoor() {
      return utils_consumerApi.consumerApi.account().then((acc) => {
        if (acc.operator || acc.verified && payReady(acc))
          return true;
        prepAccount.value = acc;
        showPrepDrawer.value = true;
        return new Promise((resolve) => {
          prepResolve = resolve;
        });
      });
    }
    function onPrepDone() {
      showPrepDrawer.value = false;
      prepResolve == null ? void 0 : prepResolve(true);
      prepResolve = null;
    }
    function onPrepCancel() {
      showPrepDrawer.value = false;
      prepResolve == null ? void 0 : prepResolve(false);
      prepResolve = null;
    }
    function onScan() {
      common_vendor.index.scanCode({
        onlyFromCamera: false,
        scanType: ["qrCode", "barCode"],
        success(res) {
          const parsed = common_vendor.parseCabinetScan(res.result);
          if (parsed.alipayOnly) {
            common_vendor.index.showToast({ title: "请使用支付宝扫码", icon: "none" });
            return;
          }
          if (!parsed.deviceId) {
            common_vendor.index.showToast({ title: "无法识别柜机", icon: "none" });
            return;
          }
          startShoppingFlow(parsed.deviceId);
        }
      });
    }
    function confirmDevice() {
      const parsed = common_vendor.parseCabinetScan(deviceInput.value);
      const id = parsed.deviceId || deviceInput.value.trim().toUpperCase();
      if (!id) {
        landingError.value = "请输入柜机编号，例如 CAB-001。";
        common_vendor.index.showToast({ title: "请输入柜机编号", icon: "none" });
        return;
      }
      startShoppingFlow(id);
    }
    async function loadDeviceAndProducts() {
      if (!await utils_consumerApi.ensureConsumerAuth())
        return;
      productsLoading.value = true;
      try {
        await refreshDeviceStatus();
        products.value = await utils_consumerApi.consumerApi.deviceProducts(deviceId.value);
      } catch (e) {
        common_vendor.index.showToast({ title: common_vendor.formatError(e), icon: "none" });
      } finally {
        productsLoading.value = false;
      }
    }
    async function refreshDeviceStatus() {
      try {
        const s = await utils_consumerApi.consumerApi.deviceStatus(deviceId.value);
        deviceName.value = s.deviceName || deviceId.value;
        const online = (s.onlineStatus || "").toUpperCase() === "ONLINE";
        deviceOffline.value = !online;
        if (!online)
          deviceStatusText.value = "离线";
        else if (state.value === "SHOPPING")
          deviceStatusText.value = "门已开 · 购物中";
        else if (state.value === "CREATED" || state.value === "OPENING")
          deviceStatusText.value = "正在开门";
        else if (s.busy)
          deviceStatusText.value = "使用中";
        else
          deviceStatusText.value = "在线 · 可再次开门";
      } catch (e) {
        deviceOffline.value = true;
        deviceStatusText.value = common_vendor.formatError(e);
      }
    }
    async function reopenShop() {
      if (!deviceId.value)
        return;
      await startShoppingFlow(deviceId.value);
    }
    async function cancelOpening() {
      if (!sessionId.value || cancelling.value)
        return;
      cancelling.value = true;
      try {
        const s = await utils_consumerApi.consumerApi.cancelSession(sessionId.value);
        applySessionView(s);
        stopPoll();
        common_vendor.index.removeStorageSync("active_session_id");
        utils_consumerApi.clearOpenAttempt();
        clearSessionUi();
        scanned.value = false;
        common_vendor.index.showToast({ title: "已取消本次开门", icon: "none" });
      } catch (e) {
        common_vendor.index.showToast({ title: common_vendor.formatError(e), icon: "none" });
      } finally {
        cancelling.value = false;
      }
    }
    function goReport() {
      common_vendor.index.navigateTo({
        url: `/pages/report/report?deviceId=${encodeURIComponent(deviceId.value || "")}`
      });
    }
    function clearSessionUi() {
      sessionId.value = "";
      state.value = "";
      stateLabel.value = "";
      stateHint.value = "";
      stateTone.value = "idle";
    }
    async function finishSession(sessionState, sid) {
      common_vendor.index.removeStorageSync("active_session_id");
      utils_consumerApi.clearOpenAttempt();
      if (sessionState === "COMPLETED") {
        if (deviceId.value) {
          common_vendor.index.setStorageSync("last_device_id", deviceId.value);
          common_vendor.index.setStorageSync("last_device_name", deviceName.value || deviceId.value);
          lastDeviceId.value = deviceId.value;
          lastDeviceName.value = deviceName.value || deviceId.value;
        }
        clearSessionUi();
        let totalCents = 0;
        try {
          const order = await utils_consumerApi.consumerApi.getSessionOrder(sid);
          totalCents = (order == null ? void 0 : order.totalAmountCents) || 0;
        } catch {
        }
        await utils_notify.requestOrderSubscribe();
        utils_notify.showBillToast(totalCents);
        await utils_notify.delay(1200);
        common_vendor.index.redirectTo({ url: `/pages/result/result?sessionId=${encodeURIComponent(sid)}` });
        return;
      }
      clearSessionUi();
      if (sessionState === "DISPUTED") {
        try {
          const order = await utils_consumerApi.consumerApi.getSessionOrder(sid);
          if (order == null ? void 0 : order.orderId) {
            common_vendor.index.redirectTo({
              url: `/pages/result/result?sessionId=${encodeURIComponent(sid)}&orderId=${encodeURIComponent(order.orderId)}`
            });
            return;
          }
        } catch {
        }
        reviewSessionId.value = sid;
        common_vendor.index.setStorageSync("last_disputed_session_id", sid);
        void refreshReviewState();
        void utils_notify.requestDisputeSubscribe();
        common_vendor.index.showToast({ title: "识别完成，账单待人工确认", icon: "none" });
      }
    }
    function applySessionView(s) {
      state.value = s.state;
      stateLabel.value = common_vendor.sessionStateLabel(s.state);
      stateHint.value = common_vendor.sessionStateHint(s.state);
      stateTone.value = common_vendor.sessionStateTone(s.state);
      if (s.state === "OPENING" || s.state === "CREATED")
        startOpeningCountdown(s.createdAt);
      else
        stopOpeningCountdown();
      if (s.deviceId && deviceId.value)
        refreshDeviceStatus();
    }
    function startOpeningCountdown(createdAt) {
      stopOpeningCountdown();
      const started = createdAt ? new Date(createdAt).getTime() : Date.now();
      const tick = () => {
        openingSeconds.value = Math.max(0, 90 - Math.floor((Date.now() - started) / 1e3));
      };
      tick();
      countdownTimer = setInterval(tick, 1e3);
    }
    function stopOpeningCountdown() {
      if (countdownTimer)
        clearInterval(countdownTimer);
      countdownTimer = null;
      openingSeconds.value = 90;
    }
    async function restoreActiveSession() {
      const saved = common_vendor.index.getStorageSync("active_session_id");
      if (sessionId.value)
        return;
      try {
        const s = saved ? await utils_consumerApi.consumerApi.getSession(saved) : await utils_consumerApi.consumerApi.activeSession();
        if (!s)
          return;
        if (["COMPLETED", "FAILED", "CANCELLED", "DISPUTED"].includes(s.state)) {
          common_vendor.index.removeStorageSync("active_session_id");
          utils_consumerApi.clearOpenAttempt();
          if (s.state === "DISPUTED") {
            reviewSessionId.value = s.sessionId;
            common_vendor.index.setStorageSync("last_disputed_session_id", s.sessionId);
            void utils_notify.requestDisputeSubscribe();
          }
          return;
        }
        sessionId.value = s.sessionId;
        common_vendor.index.setStorageSync("active_session_id", s.sessionId);
        if (s.deviceId) {
          deviceId.value = s.deviceId;
          scanned.value = true;
          await loadDeviceAndProducts();
        }
        applySessionView(s);
        startPoll();
      } catch {
      }
    }
    function startPoll() {
      stopPoll();
      pollTimer = setInterval(async () => {
        if (!sessionId.value)
          return;
        try {
          const s = await utils_consumerApi.consumerApi.getSession(sessionId.value);
          applySessionView(s);
          pollError.value = "";
          if (s.state === "COMPLETED" || s.state === "DISPUTED") {
            stopPoll();
            const sid = sessionId.value;
            await finishSession(s.state, sid);
          } else if (["FAILED", "CANCELLED"].includes(s.state)) {
            stopPoll();
            common_vendor.index.removeStorageSync("active_session_id");
            utils_consumerApi.clearOpenAttempt();
            clearSessionUi();
          }
        } catch (e) {
          pollError.value = common_vendor.formatError(e);
        }
      }, 2e3);
    }
    function stopPoll() {
      if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
      }
    }
    function startDevicePoll() {
      stopDevicePoll();
      devicePollTimer = setInterval(() => {
        if (!opening.value && scanned.value && deviceId.value)
          refreshDeviceStatus();
      }, 3e4);
    }
    function stopDevicePoll() {
      if (devicePollTimer) {
        clearInterval(devicePollTimer);
        devicePollTimer = null;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: showLanding.value
      }, showLanding.value ? common_vendor.e({
        b: common_vendor.unref(common_assets.loginBgUrl),
        c: lastDeviceId.value
      }, lastDeviceId.value ? {
        d: common_vendor.t(lastDeviceName.value || lastDeviceId.value),
        e: common_vendor.o(($event) => startShoppingFlow(lastDeviceId.value))
      } : {}, {
        f: landingError.value
      }, landingError.value ? {
        g: common_vendor.t(landingError.value),
        h: common_vendor.o(($event) => landingError.value = "")
      } : {}, {
        i: common_vendor.t(opening.value ? "连接中…" : "扫码购物"),
        j: opening.value || enteringFlow.value,
        k: common_vendor.o(onScan),
        l: common_vendor.t(showManual.value ? "收起" : "手动输入柜机编号（调试）"),
        m: common_vendor.o(($event) => showManual.value = !showManual.value),
        n: showManual.value
      }, showManual.value ? {
        o: deviceInput.value,
        p: common_vendor.o(($event) => deviceInput.value = $event.detail.value),
        q: common_vendor.t(opening.value ? "开门中…" : "确认并开门"),
        r: opening.value,
        s: opening.value,
        t: common_vendor.o(confirmDevice)
      } : {}) : {}, {
        v: scanned.value
      }, scanned.value ? common_vendor.e({
        w: common_vendor.t(deviceName.value || deviceId.value),
        x: common_vendor.t(deviceStatusText.value),
        y: deviceOffline.value ? 1 : "",
        z: common_vendor.o(goReport),
        A: common_vendor.o(resetDevice),
        B: reviewSessionId.value
      }, reviewSessionId.value ? {
        C: common_vendor.t(reviewCopy.value.icon),
        D: common_vendor.n("tone-" + reviewCopy.value.tone),
        E: common_vendor.t(reviewCopy.value.title),
        F: common_vendor.t(reviewCopy.value.detail),
        G: common_vendor.o(goOrders),
        H: common_vendor.o(contactOps),
        I: common_vendor.o(dismissReview),
        J: common_vendor.n("tone-" + reviewCopy.value.tone)
      } : {}, {
        K: productsLoading.value
      }, productsLoading.value ? {} : !products.value.length ? {} : {
        M: common_vendor.f(products.value, (p, k0, i0) => {
          return common_vendor.e({
            a: showThumb(p)
          }, showThumb(p) ? {
            b: common_vendor.unref(utils_productThumb.productThumb)(p),
            c: common_vendor.o(($event) => onThumbError(p.skuId), p.skuId)
          } : {
            d: common_vendor.t(common_vendor.unref(utils_productThumb.productEmoji)(p))
          }, {
            e: common_vendor.n("cat-" + thumbTone(p)),
            f: common_vendor.t(p.skuName),
            g: common_vendor.t((p.priceCents / 100).toFixed(2)),
            h: p.skuId
          });
        })
      }, {
        L: !products.value.length,
        N: common_vendor.t(cartBarHint.value),
        O: sessionActive.value
      }, sessionActive.value ? {
        P: common_vendor.t(cartBarAction.value),
        Q: common_vendor.n(stateTone.value)
      } : canReopen.value ? {
        S: common_vendor.t(opening.value ? "开门中…" : "再次开门"),
        T: opening.value,
        U: opening.value,
        V: common_vendor.o(reopenShop)
      } : {}, {
        R: canReopen.value
      }) : {}, {
        W: flowOverlayVisible.value
      }, flowOverlayVisible.value ? common_vendor.e({
        X: flowOverlayPulse.value ? 1 : "",
        Y: common_vendor.t(flowOverlayTitle.value),
        Z: common_vendor.t(flowOverlayHint.value),
        aa: deviceId.value
      }, deviceId.value ? {
        ab: common_vendor.t(deviceName.value || deviceId.value)
      } : {}, {
        ac: pollError.value
      }, pollError.value ? {
        ad: common_vendor.t(pollError.value)
      } : {}, {
        ae: state.value === "CREATED" || state.value === "OPENING"
      }, state.value === "CREATED" || state.value === "OPENING" ? {
        af: cancelling.value,
        ag: cancelling.value,
        ah: common_vendor.o(cancelOpening)
      } : {}, {
        ai: common_vendor.n(stateTone.value)
      }) : {}, {
        aj: showPrepDrawer.value
      }, showPrepDrawer.value ? {
        ak: common_vendor.o(onPrepDone),
        al: common_vendor.o(onPrepCancel),
        am: common_vendor.p({
          account: prepAccount.value
        })
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-83a5a03c"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/index/index.vue"]]);
wx.createPage(MiniProgramPage);
