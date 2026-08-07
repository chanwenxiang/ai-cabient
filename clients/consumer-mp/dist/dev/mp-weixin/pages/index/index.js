"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const utils_recharge = require("../../utils/recharge.js");
const utils_account = require("../../utils/account.js");
const utils_productThumb = require("../../utils/product-thumb.js");
const common_assets = require("../../common/assets.js");
const utils_disputeCopy = require("../../utils/dispute-copy.js");
const utils_notify = require("../../utils/notify.js");
const utils_runtimeFlags = require("../../utils/runtime-flags.js");
if (!Math) {
  OpenPrepDrawer();
}
const OpenPrepDrawer = () => "../../components/open-prep-drawer.js";
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "index",
  setup(__props) {
    const devTools = utils_runtimeFlags.showDevTools();
    const isH5 = common_vendor.ref(false);
    const showManualEntry = common_vendor.computed(() => devTools);
    const manualEntryLabel = common_vendor.computed(() => isH5.value ? "手动输入柜机编号" : "开发：手动输入柜机编号");
    const deviceInput = common_vendor.ref("");
    const deviceId = common_vendor.ref("");
    const deviceName = common_vendor.ref("");
    const entryChannel = common_vendor.ref(null);
    const scanned = common_vendor.ref(false);
    const enteringFlow = common_vendor.ref(false);
    const showManual = common_vendor.ref(false);
    const products = common_vendor.ref([]);
    const productsLoading = common_vendor.ref(false);
    const deviceStatusText = common_vendor.ref("");
    const deviceOffline = common_vendor.ref(false);
    const devicePreauthCents = common_vendor.ref(null);
    const sessionId = common_vendor.ref("");
    const state = common_vendor.ref("");
    const stateLabel = common_vendor.ref("");
    const stateHint = common_vendor.ref("");
    const stateTone = common_vendor.ref("idle");
    const opening = common_vendor.ref(false);
    const cancelling = common_vendor.ref(false);
    const pollError = common_vendor.ref("");
    const landingError = common_vendor.ref("");
    const landingErrorKind = common_vendor.ref("other");
    const lastFailedDeviceId = common_vendor.ref("");
    const lastFailedChannel = common_vendor.ref(null);
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
    const recognitionDeferred = common_vendor.ref(false);
    const recognitionElapsedSec = common_vendor.ref(0);
    let recognitionTimer = null;
    let pollTimer = null;
    let devicePollTimer = null;
    let countdownTimer = null;
    let prepResolve = null;
    const recognitionSlow = common_vendor.computed(
      () => ["RECOGNIZING", "WAITING_UPLOAD", "SETTLING"].includes(state.value) && recognitionElapsedSec.value >= 90
    );
    const sessionActive = common_vendor.computed(
      () => !!sessionId.value && ["CREATED", "OPENING", "SHOPPING", "RECOGNIZING", "WAITING_UPLOAD", "SETTLING"].includes(state.value)
    );
    const showLanding = common_vendor.computed(() => !scanned.value && !enteringFlow.value);
    const landingErrorTitle = common_vendor.computed(() => {
      switch (landingErrorKind.value) {
        case "balance":
          return "余额不足";
        case "device_not_found":
          return "柜机不存在";
        case "device_paused":
          return "柜机暂停营业";
        case "device_busy":
          return "柜机正忙";
        case "rate_limit":
          return "开门过于频繁";
        default:
          return "暂时无法开门";
      }
    });
    const canReopen = common_vendor.computed(
      () => scanned.value && !!deviceId.value && !sessionActive.value && !opening.value && !enteringFlow.value
    );
    const flowOverlayVisible = common_vendor.computed(() => {
      if (showPrepDrawer.value) return false;
      if (recognitionDeferred.value && ["RECOGNIZING", "WAITING_UPLOAD", "SETTLING"].includes(state.value)) {
        return false;
      }
      if (enteringFlow.value && !scanned.value) return true;
      if (opening.value && !sessionId.value) return true;
      if (["OPENING", "CREATED", "RECOGNIZING", "WAITING_UPLOAD", "SETTLING"].includes(state.value)) return true;
      return false;
    });
    const flowOverlayPulse = common_vendor.computed(
      () => ["OPENING", "CREATED", "RECOGNIZING", "SETTLING", "WAITING_UPLOAD"].includes(state.value) || opening.value
    );
    const flowOverlayTitle = common_vendor.computed(() => {
      if (opening.value && !sessionId.value) return "正在开门";
      if (recognitionSlow.value) return "识别时间较长";
      if (stateLabel.value && stateLabel.value !== "-") return stateLabel.value;
      return "准备中";
    });
    const flowOverlayHint = common_vendor.computed(() => {
      if (state.value === "OPENING" || state.value === "CREATED") {
        const elapsed = Math.max(0, 90 - openingSeconds.value);
        return `已等待 ${elapsed} 秒，柜门无响应时可安全取消本次开门`;
      }
      if (recognitionSlow.value) {
        return `已识别 ${recognitionElapsedSec.value} 秒，结果出来后会生成账单，也可稍后再看`;
      }
      if (stateHint.value) return stateHint.value;
      if (opening.value) return "正在连接柜机并验证开门资格…";
      return "请稍候";
    });
    const shoppingBannerTitle = common_vendor.computed(() => {
      if (state.value === "SHOPPING") return "柜门已开，请自由取货";
      if (state.value === "OPENING" || state.value === "CREATED") return "正在开门，请稍候";
      if (["RECOGNIZING", "WAITING_UPLOAD", "SETTLING"].includes(state.value)) return "正在识别结算";
      if (canReopen.value) return "本柜可继续购物";
      return "选好商品后请关好柜门";
    });
    const shoppingBannerSub = common_vendor.computed(() => {
      if (state.value === "SHOPPING") return "无需在手机上点选商品，拿了就走";
      if (["RECOGNIZING", "WAITING_UPLOAD", "SETTLING"].includes(state.value)) {
        return "可先离开，账单会在「订单」中展示";
      }
      if (canReopen.value) return "点击下方再次开门，或扫其他柜机";
      return "实际扣款以视觉识别结果为准";
    });
    const cartBarHint = common_vendor.computed(() => {
      if (state.value === "SHOPPING") return "请取货后关好柜门";
      if (!sessionActive.value) return "可再次开门继续购买";
      return "关门后自动识别并扣款";
    });
    const cartBarAction = common_vendor.computed(() => {
      if (state.value === "SHOPPING") return "请关门";
      if (state.value === "OPENING" || state.value === "CREATED") return "开门中";
      if (state.value === "RECOGNIZING" || state.value === "WAITING_UPLOAD") return "识别中";
      if (state.value === "SETTLING") return "结算中";
      return "进行中";
    });
    function payReady(acc) {
      return utils_account.isPayReady(acc, entryChannel.value, devicePreauthCents.value || void 0);
    }
    common_vendor.onLoad(async (opts) => {
      let launch = common_vendor.parseLaunchOptions(opts || {});
      if (!launch.deviceId && typeof window !== "undefined") {
        try {
          const fromSearch = common_vendor.parseLaunchOptions(
            Object.fromEntries(new URLSearchParams(window.location.search).entries())
          );
          if (fromSearch.deviceId) {
            launch = fromSearch;
          } else if (window.location.hash.includes("deviceId=")) {
            const hashQuery = window.location.hash.split("?")[1] || "";
            const fromHash = common_vendor.parseLaunchOptions(Object.fromEntries(new URLSearchParams(hashQuery).entries()));
            if (fromHash.deviceId) launch = fromHash;
          }
        } catch {
        }
      }
      if (launch.channel) {
        entryChannel.value = utils_account.resolveEntryChannel(launch.channel);
      }
      if (launch.deviceId) {
        await startShoppingFlow(launch.deviceId, launch.channel);
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
        if (scanned.value && deviceId.value) refreshDeviceStatus();
        const reopen = common_vendor.index.getStorageSync("reopen_device_id");
        if (reopen) {
          common_vendor.index.removeStorageSync("reopen_device_id");
          const ch = common_vendor.index.getStorageSync("reopen_entry_channel");
          if (ch) {
            common_vendor.index.removeStorageSync("reopen_entry_channel");
            entryChannel.value = utils_account.resolveEntryChannel(ch);
          }
          await startShoppingFlow(reopen, ch || void 0);
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
      stopRecognitionTimer();
    });
    function thumbTone(p) {
      const c = p.category || "";
      if (c.includes("饮料")) return "drink";
      if (c.includes("零食")) return "snack";
      if (c.includes("乳品")) return "dairy";
      if (c.includes("方便")) return "food";
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
    async function startShoppingFlow(id, scanChannel) {
      const cabinetId = id.trim().toUpperCase();
      if (!cabinetId || opening.value || enteringFlow.value) return;
      if (!/^[A-Z0-9][A-Z0-9_-]{1,63}$/.test(cabinetId)) {
        setLandingError("柜机编号无效，请扫描柜门二维码或输入如 CAB-001。", "device_not_found");
        lastFailedDeviceId.value = "";
        common_vendor.index.showToast({ title: "柜机编号无效", icon: "none" });
        return;
      }
      const resolved = utils_account.resolveEntryChannel(scanChannel) || entryChannel.value;
      if (resolved) entryChannel.value = resolved;
      enteringFlow.value = true;
      landingError.value = "";
      landingErrorKind.value = "other";
      try {
        if (!await utils_consumerApi.requireConsumerAuth("扫码开门需先完成微信授权")) {
          common_vendor.index.setStorageSync("reopen_device_id", cabinetId);
          if (entryChannel.value) {
            common_vendor.index.setStorageSync("reopen_entry_channel", entryChannel.value);
          }
          return;
        }
        if (!await ensureCanOpenDoor()) {
          return;
        }
        opening.value = true;
        deviceId.value = cabinetId;
        scanned.value = true;
        const status = await utils_consumerApi.consumerApi.deviceStatus(cabinetId);
        deviceName.value = status.deviceName || cabinetId;
        const pre = Number(status.preauthCents);
        devicePreauthCents.value = Number.isFinite(pre) && pre > 0 ? pre : null;
        const online = status.online === true || (status.onlineStatus || "").toUpperCase() === "ONLINE";
        const reason = String(status.busyReason || "").toUpperCase();
        deviceOffline.value = !online;
        if (!online) {
          deviceStatusText.value = "离线";
        } else if (status.available === false && reason === "LOCKED") {
          deviceStatusText.value = "暂停营业";
        } else if (status.available === false && reason === "REPLENISHMENT") {
          deviceStatusText.value = "补货中";
        } else if (status.available === false || reason === "SESSION") {
          deviceStatusText.value = "使用中";
        } else {
          deviceStatusText.value = "在线 · 可开门";
        }
        if (!online || status.available === false) {
          scanned.value = false;
          deviceId.value = "";
          lastFailedDeviceId.value = cabinetId;
          lastFailedChannel.value = entryChannel.value;
          let kind = "other";
          let msg = deviceStatusText.value;
          if (!online) {
            kind = "other";
            msg = "该柜机当前离线，请稍后再试或更换其他柜机。";
          } else if (reason === "LOCKED") {
            kind = "device_paused";
            msg = "柜机已暂停营业，请稍后再试或换一台";
          } else if (reason === "REPLENISHMENT") {
            kind = "device_busy";
            msg = "柜机正在补货，请稍后再试";
          } else if (reason === "SESSION") {
            kind = "device_busy";
            msg = "柜机正在被使用，请稍后再试";
          }
          setLandingError(msg, kind);
          common_vendor.index.showToast({
            title: kind === "device_paused" ? "柜机暂停营业" : kind === "device_busy" ? "柜机正忙" : "暂时无法开门",
            icon: "none"
          });
          return;
        }
        productsLoading.value = true;
        const OPEN_TIMEOUT_MS = 2e4;
        const [productsResult, sessionResult] = await Promise.allSettled([
          withTimeout(utils_consumerApi.consumerApi.deviceProducts(cabinetId), OPEN_TIMEOUT_MS, "商品加载超时，请重试"),
          withTimeout(
            utils_consumerApi.consumerApi.createSession(cabinetId, entryChannel.value),
            OPEN_TIMEOUT_MS,
            "开门请求超时，请检查网络后重试"
          )
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
          lastFailedDeviceId.value = cabinetId;
          lastFailedChannel.value = entryChannel.value;
          const failReason = sessionResult.reason;
          const kind = common_vendor.classifyOpenError(failReason);
          setLandingError(common_vendor.formatError(failReason), kind);
          common_vendor.index.showToast({ title: landingError.value, icon: "none" });
          return;
        }
        const s = sessionResult.value;
        lastFailedDeviceId.value = "";
        lastFailedChannel.value = null;
        sessionId.value = s.sessionId;
        common_vendor.index.setStorageSync("active_session_id", s.sessionId);
        applySessionView(s);
        startPoll();
      } catch (e) {
        setLandingError(common_vendor.formatError(e), "other");
        common_vendor.index.showToast({ title: common_vendor.formatError(e), icon: "none" });
      } finally {
        productsLoading.value = false;
        opening.value = false;
        enteringFlow.value = false;
      }
    }
    function setLandingError(message, kind = "other") {
      landingError.value = message;
      landingErrorKind.value = kind;
    }
    function goRechargeFromError() {
      landingError.value = "";
      common_vendor.index.navigateTo({ url: "/pages/recharge/recharge" });
    }
    function withTimeout(promise, ms, timeoutMessage) {
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(timeoutMessage)), ms);
        promise.then(
          (value) => {
            clearTimeout(timer);
            resolve(value);
          },
          (err) => {
            clearTimeout(timer);
            reject(err);
          }
        );
      });
    }
    function retryLastOpen() {
      const id = lastFailedDeviceId.value;
      if (!id) return;
      landingError.value = "";
      startShoppingFlow(id, lastFailedChannel.value);
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
        if (phone) servicePhone.value = phone;
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
        reviewSessionId.value = "";
        reviewTicket.value = null;
      }
    }
    function dismissReview() {
      reviewSessionId.value = "";
      reviewTicket.value = null;
      common_vendor.index.removeStorageSync("last_disputed_session_id");
    }
    function goReviewDetail() {
      var _a;
      const sid = reviewSessionId.value || String(common_vendor.index.getStorageSync("last_disputed_session_id") || "");
      const tid = ((_a = reviewTicket.value) == null ? void 0 : _a.ticketId) || "";
      const q = [
        tid ? `ticketId=${encodeURIComponent(tid)}` : "",
        sid ? `sessionId=${encodeURIComponent(sid)}` : ""
      ].filter(Boolean).join("&");
      if (!q) {
        goOrders();
        return;
      }
      common_vendor.index.navigateTo({ url: `/pages/dispute/detail?${q}` });
    }
    function ensureCanOpenDoor() {
      return utils_consumerApi.consumerApi.account().then((acc) => {
        if (acc.operator || acc.verified && payReady(acc)) return true;
        prepAccount.value = acc;
        showPrepDrawer.value = true;
        return new Promise((resolve) => {
          prepResolve = resolve;
        });
      }).catch((e) => {
        common_vendor.index.showToast({ title: common_vendor.formatError(e) || "账户信息加载失败", icon: "none" });
        return false;
      });
    }
    function onPrepDone(channel) {
      if (channel) entryChannel.value = channel;
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
          const raw = String(res.result || "").trim();
          if (!raw) {
            common_vendor.index.showToast({ title: "未识别到有效内容，请对准柜门二维码", icon: "none" });
            return;
          }
          const parsed = common_vendor.parseCabinetScan(raw);
          if (parsed.alipayOnly) {
            common_vendor.index.showToast({ title: "请使用支付宝扫码", icon: "none" });
            return;
          }
          if (!parsed.deviceId) {
            landingError.value = "无法识别柜机二维码，请扫描柜门上的专用码，或手动输入柜机编号。";
            landingErrorKind.value = "device_not_found";
            if (showManualEntry.value) showManual.value = true;
            common_vendor.index.showToast({ title: "无法识别柜机二维码", icon: "none" });
            return;
          }
          startShoppingFlow(parsed.deviceId, parsed.channel);
        },
        fail() {
          if (isH5.value) {
            showManual.value = true;
            common_vendor.index.showToast({ title: "浏览器请手动输入柜机编号", icon: "none" });
            return;
          }
          common_vendor.index.showToast({ title: "扫码取消或失败", icon: "none" });
        }
      });
    }
    function confirmDevice() {
      let raw = deviceInput.value;
      deviceInput.value = raw;
      const parsed = common_vendor.parseCabinetScan(raw);
      const id = parsed.deviceId || raw.trim().toUpperCase();
      if (!id) {
        landingError.value = "请输入柜机编号，例如 CAB-001。";
        landingErrorKind.value = "device_not_found";
        common_vendor.index.showToast({ title: "请输入柜机编号", icon: "none" });
        return;
      }
      startShoppingFlow(id, parsed.channel);
    }
    async function loadDeviceAndProducts() {
      if (!await utils_consumerApi.ensureConsumerAuth()) return;
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
        const pre = Number(s.preauthCents);
        devicePreauthCents.value = Number.isFinite(pre) && pre > 0 ? pre : null;
        const online = s.online === true || (s.onlineStatus || "").toUpperCase() === "ONLINE";
        const reason = String(s.busyReason || "").toUpperCase();
        const unavailable = s.available === false;
        deviceOffline.value = !online;
        if (!online) {
          deviceStatusText.value = "离线";
        } else if (state.value === "SHOPPING") {
          deviceStatusText.value = "门已开 · 购物中";
        } else if (state.value === "CREATED" || state.value === "OPENING") {
          deviceStatusText.value = "正在开门";
        } else if (unavailable && reason === "LOCKED") {
          deviceStatusText.value = "暂停营业";
        } else if (unavailable && reason === "REPLENISHMENT") {
          deviceStatusText.value = "补货中";
        } else if (unavailable || s.busy || reason === "SESSION") {
          deviceStatusText.value = "使用中";
        } else {
          deviceStatusText.value = "在线 · 可开门";
        }
      } catch (e) {
        const kind = common_vendor.classifyOpenError(e);
        deviceOffline.value = kind !== "device_paused" && kind !== "device_busy";
        deviceStatusText.value = common_vendor.formatError(e);
      }
    }
    async function reopenShop() {
      if (!deviceId.value) return;
      await startShoppingFlow(deviceId.value);
    }
    async function cancelOpening() {
      if (cancelling.value) return;
      if (!sessionId.value) {
        opening.value = false;
        enteringFlow.value = false;
        scanned.value = false;
        deviceId.value = "";
        products.value = [];
        common_vendor.index.showToast({ title: "已取消开门", icon: "none" });
        return;
      }
      const confirmed = await new Promise((resolve) => {
        common_vendor.index.showModal({
          title: "取消开门",
          content: "确定取消本次开门吗？已创建的会话将被关闭。",
          confirmText: "取消开门",
          cancelText: "继续等待",
          success: (res) => resolve(!!res.confirm),
          fail: () => resolve(false)
        });
      });
      if (!confirmed) return;
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
      recognitionDeferred.value = false;
      stopRecognitionTimer();
    }
    function startRecognitionTimer(since) {
      stopRecognitionTimer();
      const started = since ? new Date(since).getTime() : Date.now();
      const tick = () => {
        recognitionElapsedSec.value = Math.max(0, Math.floor((Date.now() - started) / 1e3));
      };
      tick();
      recognitionTimer = setInterval(tick, 1e3);
    }
    function stopRecognitionTimer() {
      if (recognitionTimer) clearInterval(recognitionTimer);
      recognitionTimer = null;
      recognitionElapsedSec.value = 0;
    }
    function deferRecognitionWait() {
      recognitionDeferred.value = true;
      common_vendor.index.showToast({ title: "可稍后在订单页查看", icon: "none" });
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
        setTimeout(() => {
          common_vendor.index.navigateTo({
            url: `/pages/dispute/detail?sessionId=${encodeURIComponent(sid)}`,
            fail: () => {
            }
          });
        }, 600);
      }
    }
    function applySessionView(s) {
      state.value = s.state;
      stateLabel.value = common_vendor.sessionStateLabel(s.state);
      stateHint.value = common_vendor.sessionStateHint(s.state);
      stateTone.value = common_vendor.sessionStateTone(s.state);
      if (s.state === "OPENING" || s.state === "CREATED") startOpeningCountdown(s.createdAt);
      else stopOpeningCountdown();
      if (["RECOGNIZING", "WAITING_UPLOAD", "SETTLING"].includes(s.state)) {
        startRecognitionTimer(s.closeTime || s.createdAt);
      } else {
        stopRecognitionTimer();
        recognitionDeferred.value = false;
      }
      if (s.deviceId && deviceId.value) refreshDeviceStatus();
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
      if (countdownTimer) clearInterval(countdownTimer);
      countdownTimer = null;
      openingSeconds.value = 90;
    }
    async function restoreActiveSession() {
      const saved = common_vendor.index.getStorageSync("active_session_id");
      if (sessionId.value) return;
      try {
        const s = saved ? await utils_consumerApi.consumerApi.getSession(saved) : await utils_consumerApi.consumerApi.activeSession();
        if (!s) return;
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
        if (!sessionId.value) return;
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
            const hint = common_vendor.sessionStateHint(s.state) || (s.state === "CANCELLED" ? "会话已取消" : "购物未完成");
            common_vendor.index.removeStorageSync("active_session_id");
            utils_consumerApi.clearOpenAttempt();
            clearSessionUi();
            common_vendor.index.showToast({ title: hint, icon: "none", duration: 2800 });
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
        if (!opening.value && scanned.value && deviceId.value) refreshDeviceStatus();
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
      }, landingError.value ? common_vendor.e({
        g: common_vendor.t(landingErrorTitle.value),
        h: common_vendor.t(landingError.value),
        i: landingErrorKind.value === "balance"
      }, landingErrorKind.value === "balance" ? {
        j: common_vendor.o(goRechargeFromError)
      } : lastFailedDeviceId.value ? {
        l: common_vendor.o(retryLastOpen)
      } : {}, {
        k: lastFailedDeviceId.value,
        m: landingErrorKind.value === "device_not_found"
      }, landingErrorKind.value === "device_not_found" ? {
        n: common_vendor.o(onScan)
      } : {}, {
        o: common_vendor.o(($event) => {
          landingError.value = "";
          showManual.value = true;
        }),
        p: common_vendor.o(($event) => landingError.value = ""),
        q: common_vendor.n("kind-" + landingErrorKind.value)
      }) : {}, {
        r: common_vendor.t(opening.value ? "连接中…" : "扫码购物"),
        s: opening.value || enteringFlow.value,
        t: common_vendor.o(onScan),
        v: showManualEntry.value
      }, showManualEntry.value ? common_vendor.e({
        w: common_vendor.t(showManual.value ? "收起" : manualEntryLabel.value),
        x: common_vendor.o(($event) => showManual.value = !showManual.value),
        y: showManual.value
      }, showManual.value ? {
        z: deviceInput.value,
        A: common_vendor.o(($event) => deviceInput.value = $event.detail.value),
        B: common_vendor.t(opening.value ? "开门中…" : "确认并开门"),
        C: opening.value,
        D: opening.value,
        E: common_vendor.o(confirmDevice)
      } : {}) : {}) : {}, {
        F: scanned.value
      }, scanned.value ? common_vendor.e({
        G: common_vendor.t(deviceName.value || deviceId.value),
        H: common_vendor.t(deviceStatusText.value),
        I: deviceOffline.value ? 1 : "",
        J: common_vendor.o(goReport),
        K: common_vendor.o(resetDevice),
        L: reviewSessionId.value && !sessionActive.value
      }, reviewSessionId.value && !sessionActive.value ? {
        M: common_vendor.t(reviewCopy.value.icon),
        N: common_vendor.n("tone-" + reviewCopy.value.tone),
        O: common_vendor.t(reviewCopy.value.title),
        P: common_vendor.t(reviewCopy.value.detail),
        Q: common_vendor.o(goReviewDetail),
        R: common_vendor.o(goOrders),
        S: common_vendor.o(contactOps),
        T: common_vendor.o(dismissReview),
        U: common_vendor.n("tone-" + reviewCopy.value.tone)
      } : {}, {
        V: common_vendor.t(shoppingBannerTitle.value),
        W: common_vendor.t(shoppingBannerSub.value),
        X: common_vendor.n(stateTone.value),
        Y: productsLoading.value
      }, productsLoading.value ? {} : !products.value.length ? {
        aa: common_vendor.o(goReport),
        ab: common_vendor.o(resetDevice)
      } : {
        ac: common_vendor.f(products.value, (p, k0, i0) => {
          return common_vendor.e({
            a: showThumb(p)
          }, showThumb(p) ? {
            b: common_vendor.unref(utils_productThumb.productThumb)(p),
            c: common_vendor.o(($event) => onThumbError(p.skuId), p.skuId)
          } : {
            d: common_vendor.t(common_vendor.unref(utils_productThumb.productGlyph)(p))
          }, {
            e: common_vendor.n("cat-" + thumbTone(p)),
            f: common_vendor.t(p.skuName),
            g: common_vendor.t(common_vendor.unref(common_vendor.fmtMoney)(p.priceCents)),
            h: p.skuId
          });
        })
      }, {
        Z: !products.value.length,
        ad: common_vendor.t(cartBarHint.value),
        ae: sessionActive.value && state.value === "SHOPPING"
      }, sessionActive.value && state.value === "SHOPPING" ? {} : {}, {
        af: sessionActive.value
      }, sessionActive.value ? {
        ag: common_vendor.t(cartBarAction.value),
        ah: common_vendor.n(stateTone.value)
      } : canReopen.value ? {
        aj: common_vendor.t(opening.value ? "开门中…" : "再次开门"),
        ak: opening.value,
        al: opening.value,
        am: common_vendor.o(reopenShop)
      } : {}, {
        ai: canReopen.value
      }) : {}, {
        an: flowOverlayVisible.value
      }, flowOverlayVisible.value ? common_vendor.e({
        ao: flowOverlayPulse.value ? 1 : "",
        ap: common_vendor.t(flowOverlayTitle.value),
        aq: common_vendor.t(flowOverlayHint.value),
        ar: deviceId.value
      }, deviceId.value ? {
        as: common_vendor.t(deviceName.value || deviceId.value)
      } : {}, {
        at: pollError.value
      }, pollError.value ? {
        av: common_vendor.t(pollError.value)
      } : {}, {
        aw: state.value === "CREATED" || state.value === "OPENING" || opening.value && !sessionId.value
      }, state.value === "CREATED" || state.value === "OPENING" || opening.value && !sessionId.value ? {
        ax: cancelling.value,
        ay: cancelling.value,
        az: common_vendor.o(cancelOpening)
      } : {}, {
        aA: recognitionSlow.value
      }, recognitionSlow.value ? {
        aB: common_vendor.o(deferRecognitionWait)
      } : {}, {
        aC: recognitionSlow.value
      }, recognitionSlow.value ? {} : {}, {
        aD: common_vendor.n(stateTone.value)
      }) : {}, {
        aE: showPrepDrawer.value
      }, showPrepDrawer.value ? {
        aF: common_vendor.o(onPrepDone),
        aG: common_vendor.o(onPrepCancel),
        aH: common_vendor.p({
          account: prepAccount.value,
          ["entry-channel"]: entryChannel.value,
          ["device-preauth-cents"]: devicePreauthCents.value
        })
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-83a5a03c"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/index/index.vue"]]);
wx.createPage(MiniProgramPage);
