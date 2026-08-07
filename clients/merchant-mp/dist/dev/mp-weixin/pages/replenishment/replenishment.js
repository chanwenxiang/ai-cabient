"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const utils_scanCabinet = require("../../utils/scan-cabinet.js");
const utils_preferredDevice = require("../../utils/preferred-device.js");
if (!Math) {
  EmptyState();
}
const EmptyState = () => "../../components/empty-state.js";
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "replenishment",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const canReplenish = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:replenishment:view"));
    const canRequest = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:replenishment:request"));
    const preferredId = common_vendor.ref(utils_preferredDevice.getPreferredDeviceId());
    const loading = common_vendor.ref(false);
    let loadSeq = 0;
    const detailLoading = common_vendor.ref(false);
    const submitting = common_vendor.ref(false);
    const scanning = common_vendor.ref(false);
    const status = common_vendor.ref("");
    const filterDeviceId = common_vendor.ref("");
    const focusTaskId = common_vendor.ref(null);
    let pendingDeepLink = false;
    const allTasks = common_vendor.ref([]);
    const devices = common_vendor.ref([]);
    const skus = common_vendor.ref([]);
    const detailVisible = common_vendor.ref(false);
    const sheetCloseArmed = common_vendor.ref(false);
    const selected = common_vendor.ref(null);
    const lines = common_vendor.ref([]);
    const linesConfirmed = common_vendor.ref(false);
    const evidenceItems = common_vendor.ref([]);
    const doorOpened = common_vendor.ref(false);
    const openSessionId = common_vendor.ref("");
    const slotCaps = common_vendor.ref({});
    const deviceSlotsList = common_vendor.ref([]);
    const heroSubtitle = common_vendor.computed(() => "扫码到柜 → 签到 → 开门 → 核对履约");
    const detailIsPullOff = common_vendor.computed(() => {
      var _a;
      if (!lines.value.length) {
        const notes = String(((_a = selected.value) == null ? void 0 : _a.notes) || "");
        return /from-expiry|PULL_OFF|下架/i.test(notes);
      }
      return lines.value.every((l) => isPullOffType(l.lineType));
    });
    function isPullOffType(type) {
      const code = String(type || "RESTOCK").toUpperCase();
      return code === "PULL_OFF" || code === "REMOVE" || code === "PULL";
    }
    function lineTypeLabel(type) {
      return isPullOffType(type) ? "下架" : "上架";
    }
    function lineStatusLabel(line) {
      if (line.applied) return isPullOffType(line.lineType) ? "已下架" : "已入柜";
      return isPullOffType(line.lineType) ? "待下架" : "待上架";
    }
    function taskLooksPullOff(task) {
      return /from-expiry|PULL_OFF|下架/i.test(String(task.notes || ""));
    }
    function taskActionLabel(task) {
      if (task.status === "COMPLETED") return "查看完成明细";
      const pull = taskLooksPullOff(task);
      if (task.checkInAt) return pull ? "继续下架" : "继续补货";
      return pull ? "开始下架" : "开始补货";
    }
    const confirmDialog = common_vendor.ref({
      visible: false,
      title: "",
      content: "",
      confirmText: "确定",
      cancelText: "取消",
      resolve: null
    });
    function askConfirm(opts) {
      return new Promise((resolve) => {
        if (confirmDialog.value.visible && confirmDialog.value.resolve) {
          confirmDialog.value.resolve(false);
        }
        confirmDialog.value = {
          visible: true,
          title: opts.title,
          content: opts.content,
          confirmText: opts.confirmText || "确定",
          cancelText: opts.cancelText || "取消",
          resolve
        };
      });
    }
    function resolveConfirm(ok) {
      const resolver = confirmDialog.value.resolve;
      confirmDialog.value = {
        visible: false,
        title: "",
        content: "",
        confirmText: "确定",
        cancelText: "取消",
        resolve: null
      };
      resolver == null ? void 0 : resolver(ok);
    }
    const statusOptions = common_vendor.computed(() => [
      { value: "", label: "全部" },
      ...common_vendor.dictOptions("replenishment_task_status").filter(
        (item) => ["PENDING", "IN_PROGRESS", "COMPLETED"].includes(item.value)
      )
    ]);
    const tasks = common_vendor.computed(() => {
      let rows = allTasks.value.filter((t) => t.status !== "CANCELLED");
      if (filterDeviceId.value) {
        const key = filterDeviceId.value.trim().toUpperCase();
        rows = rows.filter((t) => String(t.deviceId || "").trim().toUpperCase() === key);
      }
      if (status.value) {
        rows = rows.filter((t) => t.status === status.value);
      }
      const preferred = preferredId.value;
      if (!preferred || filterDeviceId.value) return rows;
      return [...rows].sort((a, b) => {
        if (a.deviceId === preferred) return -1;
        if (b.deviceId === preferred) return 1;
        return 0;
      });
    });
    const pendingCount = common_vendor.computed(
      () => allTasks.value.filter((item) => item.status !== "COMPLETED" && item.status !== "CANCELLED").length
    );
    const completedCount = common_vendor.computed(
      () => allTasks.value.filter((item) => item.status === "COMPLETED").length
    );
    const emptyHint = common_vendor.computed(() => {
      if (filterDeviceId.value) {
        return status.value ? `该柜机暂无「${common_vendor.displayLabel("replenishment_task_status", status.value, "该状态")}」任务` : "该柜机暂无补货任务";
      }
      if (status.value === "IN_PROGRESS" && pendingCount.value === 0 && completedCount.value > 0) {
        return "暂无进行中的任务，可查看已完成记录";
      }
      if (status.value) {
        return `暂无「${common_vendor.displayLabel("replenishment_task_status", status.value, "该状态")}」任务`;
      }
      return "当前没有补货任务";
    });
    function applyRouteQuery(opts) {
      const deviceId = (opts == null ? void 0 : opts.deviceId) || readHashQuery("deviceId");
      const taskIdRaw = (opts == null ? void 0 : opts.taskId) || readHashQuery("taskId");
      let changed = false;
      if (deviceId) {
        filterDeviceId.value = String(deviceId).trim().toUpperCase();
        changed = true;
      }
      if (taskIdRaw) {
        const id = Number(taskIdRaw);
        if (Number.isFinite(id) && id > 0) {
          focusTaskId.value = id;
          changed = true;
        }
      }
      if (deviceId || taskIdRaw) {
        status.value = "";
      }
      if (changed) pendingDeepLink = true;
    }
    function readHashQuery(key) {
      if (typeof location === "undefined") return void 0;
      const m = location.hash.match(new RegExp(`[?&]${key}=([^&]+)`));
      return m ? decodeURIComponent(m[1]) : void 0;
    }
    function clearDeepLinkQuery() {
      pendingDeepLink = false;
      focusTaskId.value = null;
      if (typeof location === "undefined" || typeof history === "undefined") return;
      const hash = location.hash || "";
      const qIndex = hash.indexOf("?");
      if (qIndex < 0) return;
      const path = hash.slice(0, qIndex);
      history.replaceState(null, "", `${location.pathname}${location.search}${path}`);
    }
    function usePreferredDevice() {
      const id = preferredId.value;
      if (!id) return;
      filterDeviceId.value = id.trim().toUpperCase();
      status.value = "";
      void load();
    }
    function goRequest() {
      const q = filterDeviceId.value ? `?deviceId=${encodeURIComponent(filterDeviceId.value)}` : "";
      common_vendor.index.navigateTo({ url: `/pages/request/request${q}` });
    }
    common_vendor.onLoad((opts) => {
      applyRouteQuery(opts);
      preferredId.value = utils_preferredDevice.getPreferredDeviceId();
    });
    function deviceName(id) {
      const d = devices.value.find((item) => item.deviceId === id);
      return (d == null ? void 0 : d.deviceName) || common_vendor.emptyDisplay(id, "device");
    }
    function skuName(id) {
      const s = skus.value.find((item) => item.skuId === id);
      return (s == null ? void 0 : s.skuName) || id;
    }
    function productIcon(id) {
      if (id.includes("WATER")) return "💧";
      if (id.includes("MILK")) return "🥛";
      if (id.includes("NOODLE")) return "🍜";
      if (id.includes("SNACK")) return "🥔";
      return "🥤";
    }
    function formatTime(value) {
      return common_vendor.formatDateTimeShort(value, "暂无");
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
      if (!canReplenish.value) {
        common_vendor.index.showToast({ title: "无补货权限", icon: "none" });
        common_vendor.index.switchTab({ url: "/pages/home/home" });
        return;
      }
      loading.value = true;
      try {
        const [taskRows, deviceRows, skuRows] = await Promise.all([
          utils_merchantApi.merchantApi.replenishmentTasks().catch(() => []),
          utils_merchantApi.merchantApi.devices().catch(() => []),
          utils_merchantApi.merchantApi.pricing().catch(() => [])
        ]);
        if (seq !== loadSeq) return;
        allTasks.value = taskRows;
        devices.value = deviceRows;
        skus.value = skuRows || [];
        let open;
        const wantedTaskId = focusTaskId.value;
        if (pendingDeepLink && focusTaskId.value) {
          open = allTasks.value.find(
            (t) => t.taskId === focusTaskId.value && t.status !== "CANCELLED"
          );
          focusTaskId.value = null;
        } else if (pendingDeepLink && !detailVisible.value && filterDeviceId.value) {
          const key = filterDeviceId.value.trim().toUpperCase();
          open = allTasks.value.find(
            (t) => String(t.deviceId || "").trim().toUpperCase() === key && t.status !== "COMPLETED" && t.status !== "CANCELLED"
          );
        }
        if (pendingDeepLink) {
          clearDeepLinkQuery();
        }
        if (open) {
          await openTask(open);
        } else if (wantedTaskId) {
          common_vendor.index.showToast({ title: `任务 #${wantedTaskId} 不可用或已取消`, icon: "none" });
        }
      } catch (error) {
        if (seq !== loadSeq) return;
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "加载失败", icon: "none" });
      } finally {
        if (seq === loadSeq) {
          loading.value = false;
          common_vendor.index.stopPullDownRefresh();
        }
      }
    }
    function changeStatus(value) {
      status.value = value;
    }
    function clearDeviceFilter() {
      filterDeviceId.value = "";
      clearDeepLinkQuery();
    }
    async function onScan() {
      if (scanning.value) return;
      scanning.value = true;
      try {
        const id = await utils_scanCabinet.scanCabinetDeviceId();
        if (!id) return;
        const key = id.trim().toUpperCase();
        filterDeviceId.value = key;
        status.value = "";
        const open = allTasks.value.find(
          (t) => String(t.deviceId || "").trim().toUpperCase() === key && t.status !== "COMPLETED" && t.status !== "CANCELLED"
        );
        if (open) {
          await openTask(open);
        } else {
          common_vendor.index.showToast({ title: "该柜暂无任务，已筛选列表", icon: "none" });
        }
      } finally {
        scanning.value = false;
      }
    }
    function doorCacheKey(taskId) {
      return `replenish_door_${taskId}`;
    }
    function restoreDoorState(taskId) {
      try {
        const raw = common_vendor.index.getStorageSync(doorCacheKey(taskId));
        if (!raw) {
          doorOpened.value = false;
          openSessionId.value = "";
          return;
        }
        const cached = typeof raw === "string" ? JSON.parse(raw) : raw;
        doorOpened.value = !!(cached == null ? void 0 : cached.sessionId);
        openSessionId.value = (cached == null ? void 0 : cached.sessionId) || "";
      } catch {
        doorOpened.value = false;
        openSessionId.value = "";
      }
    }
    function persistDoorState(taskId, sessionId) {
      common_vendor.index.setStorageSync(doorCacheKey(taskId), { sessionId, at: Date.now() });
    }
    function currentStep() {
      if (!selected.value) return 1;
      if (selected.value.status === "COMPLETED") return 5;
      if (linesConfirmed.value) return 4;
      if (doorOpened.value) return 3;
      if (selected.value.checkInAt) return 2;
      return 1;
    }
    function stepClass(step) {
      var _a;
      if (((_a = selected.value) == null ? void 0 : _a.status) === "COMPLETED") {
        return { done: true, current: false };
      }
      const cur = currentStep();
      return { done: step < cur, current: step === cur };
    }
    function syncTaskInList(task) {
      const idx = allTasks.value.findIndex((t) => t.taskId === task.taskId);
      if (idx >= 0) {
        allTasks.value[idx] = { ...allTasks.value[idx], ...task };
      }
    }
    async function addEvidence() {
      if (!selected.value || !canRequest.value) return;
      if (!selected.value.checkInAt) {
        common_vendor.index.showToast({ title: "请先签到再拍照", icon: "none" });
        return;
      }
      if (evidenceItems.value.length >= 5) {
        common_vendor.index.showToast({ title: "最多 5 张", icon: "none" });
        return;
      }
      const paths = await new Promise((resolve) => {
        common_vendor.index.chooseImage({
          count: 5 - evidenceItems.value.length,
          sizeType: ["compressed"],
          sourceType: ["album", "camera"],
          success: (res) => resolve(res.tempFilePaths || []),
          fail: () => resolve([])
        });
      });
      for (const path of paths) {
        try {
          const uploaded = await utils_merchantApi.merchantApi.uploadReplenishmentEvidence(selected.value.taskId, path);
          evidenceItems.value.push({ localPath: path, fileId: uploaded.fileId });
        } catch (e) {
          common_vendor.index.showToast({
            title: e instanceof Error ? e.message : "上传失败",
            icon: "none"
          });
          break;
        }
      }
    }
    function previewEvidence(index) {
      const urls = evidenceItems.value.map((i) => i.localPath).filter(Boolean);
      if (!urls.length) return;
      common_vendor.index.previewImage({ urls, current: urls[index] || urls[0] });
    }
    async function openTask(task) {
      const fromList = allTasks.value.find((t) => t.taskId === task.taskId);
      selected.value = { ...fromList || task };
      sheetCloseArmed.value = false;
      detailVisible.value = true;
      linesConfirmed.value = selected.value.status === "COMPLETED";
      evidenceItems.value = [];
      restoreDoorState(selected.value.taskId);
      detailLoading.value = true;
      slotCaps.value = {};
      deviceSlotsList.value = [];
      await common_vendor.nextTick$1();
      setTimeout(() => {
        sheetCloseArmed.value = true;
      }, 280);
      try {
        try {
          const latest = await utils_merchantApi.merchantApi.replenishmentTasks();
          allTasks.value = latest;
          const fresh = latest.find((t) => t.taskId === task.taskId);
          if (fresh) selected.value = { ...fresh };
        } catch {
        }
        const [taskLines, slots, evidence] = await Promise.all([
          utils_merchantApi.merchantApi.replenishmentTaskLines(task.taskId),
          utils_merchantApi.merchantApi.deviceSlots(task.deviceId).catch(() => []),
          utils_merchantApi.merchantApi.listReplenishmentEvidence(task.taskId).catch(() => [])
        ]);
        lines.value = taskLines;
        deviceSlotsList.value = slots || [];
        const mapped = await Promise.all(
          (evidence || []).map(async (f) => {
            const fileId = f.fileId;
            if (!fileId) return { localPath: f.url || "", fileId };
            try {
              const localPath = await utils_merchantApi.merchantApi.downloadReplenishmentEvidence(task.taskId, fileId);
              return { localPath, fileId };
            } catch {
              return { localPath: f.url || "", fileId };
            }
          })
        );
        evidenceItems.value = mapped;
        const map = {};
        for (const s of deviceSlotsList.value) {
          const code = String(s.slotCode || "").toUpperCase();
          if (!code) continue;
          map[code] = {
            maxLevel: Number(s.maxLevel) || 0,
            bookQty: Number(s.bookQty) || 0
          };
        }
        slotCaps.value = map;
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "明细加载失败", icon: "none" });
      } finally {
        detailLoading.value = false;
      }
    }
    function slotOptionsFor(line) {
      return deviceSlotsList.value.filter((s) => s.enabled !== false).filter((s) => !s.assignedSkuId || s.assignedSkuId === line.skuId).map((s) => {
        const slotCode = String(s.slotCode || "").toUpperCase();
        const maxLevel = Number(s.maxLevel) || 0;
        const bookQty = Number(s.bookQty) || 0;
        const room = maxLevel > 0 ? Math.max(0, maxLevel - bookQty) : 99;
        return { slotCode, room, label: s.assignedSkuName || slotCode };
      }).filter((s) => !!s.slotCode).sort((a, b) => b.room - a.room || a.slotCode.localeCompare(b.slotCode));
    }
    function assignSlot(line, opt) {
      if (!opt.slotCode) return;
      if (opt.room <= 0) {
        common_vendor.index.showToast({ title: "该货道已满", icon: "none" });
        return;
      }
      line.slotId = opt.slotCode;
      if ((Number(line.quantity) || 0) > opt.room) {
        line.quantity = opt.room;
      }
      linesConfirmed.value = false;
    }
    function slotHeadroom(line) {
      const code = String(line.slotId || "").toUpperCase();
      if (!code) {
        const rooms = slotOptionsFor(line).map((o) => o.room).filter((n) => n > 0);
        return rooms.length ? Math.max(...rooms) : 0;
      }
      const cap = slotCaps.value[code];
      if (!cap || cap.maxLevel <= 0) return 99;
      return Math.max(0, cap.maxLevel - cap.bookQty);
    }
    function slotHint(line) {
      if (isPullOffType(line.lineType)) return "";
      const code = String(line.slotId || "").toUpperCase();
      const cap = slotCaps.value[code];
      if (!cap || cap.maxLevel <= 0) return "";
      const room = slotHeadroom(line);
      if (room <= 0) return `货道已满（${cap.bookQty}/${cap.maxLevel}），请将数量调为 0 或换货道`;
      if (line.quantity > room) return `超出容量：最多再补 ${room}（已有 ${cap.bookQty}/${cap.maxLevel}）`;
      return `还可补 ${room}（已有 ${cap.bookQty}/${cap.maxLevel}）`;
    }
    function closeDetail() {
      if (!sheetCloseArmed.value) return;
      if (!submitting.value) {
        detailVisible.value = false;
        sheetCloseArmed.value = false;
        clearDeepLinkQuery();
      }
    }
    function getLocationWithTimeout(timeoutMs = 5e3) {
      return new Promise((resolve, reject) => {
        let settled = false;
        const timer = setTimeout(() => {
          if (settled) return;
          settled = true;
          reject(new Error("定位超时"));
        }, timeoutMs);
        common_vendor.index.getLocation({
          type: "gcj02",
          success(res) {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve(res);
          },
          fail(err) {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            reject(err instanceof Error ? err : new Error(String((err == null ? void 0 : err.errMsg) || "定位失败")));
          }
        });
      });
    }
    async function checkIn() {
      if (!selected.value || submitting.value) return;
      if (!canRequest.value) {
        common_vendor.index.showToast({ title: "无补货操作权限", icon: "none" });
        return;
      }
      submitting.value = true;
      let body = {};
      let locationOk = false;
      try {
        const location2 = await getLocationWithTimeout(5e3);
        body = { latitude: location2.latitude, longitude: location2.longitude };
        locationOk = true;
      } catch {
        submitting.value = false;
        const cont = await askConfirm({
          title: "定位失败",
          content: "无法获取当前位置，仍可继续签到，但无法校验是否到店。是否继续？",
          confirmText: "继续签到",
          cancelText: "取消"
        });
        if (!cont) return;
        submitting.value = true;
      }
      try {
        selected.value = await utils_merchantApi.merchantApi.checkInReplenishmentTask(selected.value.taskId, body);
        syncTaskInList(selected.value);
        common_vendor.index.showToast({
          title: locationOk ? "签到成功" : "已签到（未带定位）",
          icon: locationOk ? "success" : "none"
        });
      } catch (error) {
        const msg = error instanceof Error ? error.message : "签到失败";
        if (locationOk && (msg.includes("签到位置") || msg.includes("超出") || msg.includes("米"))) {
          const retry = await askConfirm({
            title: "距离柜机过远",
            content: `${msg}

若你已在柜前（定位漂移），可改为不校验距离继续签到。`,
            confirmText: "继续签到",
            cancelText: "取消"
          });
          if (retry) {
            try {
              selected.value = await utils_merchantApi.merchantApi.checkInReplenishmentTask(selected.value.taskId, {});
              syncTaskInList(selected.value);
              common_vendor.index.showToast({ title: "已签到（未校验距离）", icon: "none" });
            } catch (e2) {
              common_vendor.index.showToast({
                title: e2 instanceof Error ? e2.message : "签到失败",
                icon: "none",
                duration: 3600
              });
            }
          }
        } else {
          common_vendor.index.showToast({ title: msg, icon: "none", duration: 3600 });
        }
      } finally {
        submitting.value = false;
      }
    }
    async function openDoor() {
      if (!selected.value || submitting.value) return;
      if (!canRequest.value) {
        common_vendor.index.showToast({ title: "无补货操作权限", icon: "none" });
        return;
      }
      if (!selected.value.checkInAt) {
        common_vendor.index.showToast({ title: "请先现场签到", icon: "none" });
        return;
      }
      const ok = await askConfirm({
        title: doorOpened.value ? "再次开门" : detailIsPullOff.value ? "下架开门" : "补货开门",
        content: "将下发开门指令，本次为补货会话，不会按购物扣款。请确认人在柜前。",
        confirmText: "开门",
        cancelText: "取消"
      });
      if (!ok) return;
      submitting.value = true;
      try {
        const session = await utils_merchantApi.merchantApi.openReplenishmentDoor(selected.value.taskId);
        doorOpened.value = true;
        openSessionId.value = session.sessionId || "";
        if (session.sessionId) persistDoorState(selected.value.taskId, session.sessionId);
        selected.value = {
          ...selected.value,
          status: selected.value.status === "PENDING" ? "IN_PROGRESS" : selected.value.status
        };
        common_vendor.index.showToast({ title: "开门指令已下发", icon: "success" });
        await load();
        const fresh = allTasks.value.find((t) => {
          var _a;
          return t.taskId === ((_a = selected.value) == null ? void 0 : _a.taskId);
        });
        if (fresh) selected.value = { ...fresh };
      } catch (error) {
        const msg = error instanceof Error ? error.message : "开门失败";
        common_vendor.index.showToast({ title: msg, icon: "none", duration: 3200 });
      } finally {
        submitting.value = false;
      }
    }
    function adjustQty(line, delta) {
      var _a;
      if (!canRequest.value) return;
      if (linesConfirmed.value || line.applied || ((_a = selected.value) == null ? void 0 : _a.status) === "COMPLETED") return;
      const cur = Number(line.quantity) || 0;
      if (delta > 0) {
        if (!isPullOffType(line.lineType)) {
          const room = slotHeadroom(line);
          if (cur >= room) {
            common_vendor.index.showToast({
              title: room <= 0 ? "货道已满，无法再加" : `最多再补 ${room}`,
              icon: "none"
            });
            return;
          }
          line.quantity = Math.min(room, cur + delta);
          return;
        }
        line.quantity = cur + delta;
        return;
      }
      line.quantity = Math.max(0, cur + delta);
    }
    function clampLinesToCapacity() {
      let changed = false;
      for (const line of lines.value) {
        if (line.applied || isPullOffType(line.lineType)) continue;
        const room = slotHeadroom(line);
        const qty = Number(line.quantity) || 0;
        if (qty > room) {
          line.quantity = room;
          changed = true;
        }
      }
      return changed;
    }
    async function confirmLines() {
      if (!selected.value || submitting.value) return;
      if (!canRequest.value) {
        common_vendor.index.showToast({ title: "无补货操作权限", icon: "none" });
        return;
      }
      const over = lines.value.filter(
        (l) => !l.applied && !isPullOffType(l.lineType) && (Number(l.quantity) || 0) > slotHeadroom(l)
      );
      if (over.length) {
        const ok = await askConfirm({
          title: "货道容量不足",
          content: `${over.map((l) => `${l.slotId || "?"} 最多再补 ${slotHeadroom(l)}`).join("；")}。是否自动调低数量后继续？`,
          confirmText: "自动调低",
          cancelText: "手动改"
        });
        if (!ok) return;
        clampLinesToCapacity();
      }
      const positive = lines.value.filter((l) => (Number(l.quantity) || 0) > 0);
      if (!positive.length) {
        common_vendor.index.showToast({ title: "调低后无有效数量，请换货道或取消该行", icon: "none" });
        return;
      }
      const unassigned = positive.filter((l) => !isPullOffType(l.lineType) && !String(l.slotId || "").trim());
      if (unassigned.length) {
        common_vendor.index.showToast({ title: "请先为待分配行选择货道", icon: "none" });
        return;
      }
      submitting.value = true;
      try {
        lines.value = await utils_merchantApi.merchantApi.confirmReplenishmentLines(
          selected.value.taskId,
          positive.map(({ lineId, ...line }) => ({
            ...line,
            lineType: line.lineType || "RESTOCK"
          }))
        );
        linesConfirmed.value = true;
        common_vendor.index.showToast({ title: "清单已确认", icon: "success" });
      } catch (error) {
        const msg = error instanceof Error ? error.message : "确认失败";
        if (msg.includes("容量不足")) {
          const auto = await askConfirm({
            title: "确认失败",
            content: `${msg}

是否按货道余量自动调低？`,
            confirmText: "自动调低",
            cancelText: "知道了"
          });
          if (auto) clampLinesToCapacity();
        } else {
          common_vendor.index.showToast({ title: msg, icon: "none", duration: 3600 });
        }
      } finally {
        submitting.value = false;
      }
    }
    async function completeTask() {
      if (!selected.value || submitting.value) return;
      if (!canRequest.value) {
        common_vendor.index.showToast({ title: "无补货操作权限", icon: "none" });
        return;
      }
      if (!linesConfirmed.value) {
        common_vendor.index.showToast({ title: "请先确认商品与数量", icon: "none" });
        return;
      }
      if (!doorOpened.value) {
        const cont = await askConfirm({
          title: "尚未开门",
          content: detailIsPullOff.value ? "还未下发下架开门。若已现场开门完成下架，仍可继续确认完成。" : "还未下发补货开门。若已现场开门完成上架，仍可继续确认完成。",
          confirmText: "继续完成",
          cancelText: "去开门"
        });
        if (!cont) return;
      }
      if (evidenceItems.value.length === 0) {
        const photoOk = await askConfirm({
          title: "未上传照片",
          content: detailIsPullOff.value ? "建议先拍照留存下架证据，确认仍要完成任务？" : "建议先拍照留存补货证据，确认仍要完成任务？",
          confirmText: "仍完成",
          cancelText: "去拍照"
        });
        if (!photoOk) return;
      }
      const ok = await askConfirm({
        title: detailIsPullOff.value ? "确认全部下架" : "确认全部上架",
        content: detailIsPullOff.value ? "完成后将扣减柜机库存，请确认下架商品、批次和数量无误。" : "完成后将更新柜机库存并签收在途商品，请确认商品、批次和货道无误。",
        confirmText: "确认完成",
        cancelText: "取消"
      });
      if (!ok) return;
      submitting.value = true;
      try {
        const taskId = selected.value.taskId;
        selected.value = await utils_merchantApi.merchantApi.completeReplenishmentTask(taskId);
        lines.value = lines.value.map((line) => ({ ...line, applied: true }));
        try {
          common_vendor.index.removeStorageSync(doorCacheKey(taskId));
        } catch {
        }
        doorOpened.value = false;
        openSessionId.value = "";
        common_vendor.index.showToast({ title: detailIsPullOff.value ? "下架完成" : "补货完成", icon: "success" });
        await load();
        const fresh = allTasks.value.find((t) => t.taskId === taskId);
        if (fresh) selected.value = { ...fresh };
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "完成失败", icon: "none" });
      } finally {
        submitting.value = false;
      }
    }
    common_vendor.onShow(() => {
      preferredId.value = utils_preferredDevice.getPreferredDeviceId();
      void load();
    });
    common_vendor.onPullDownRefresh(load);
    return (_ctx, _cache) => {
      var _a, _b, _c, _d, _e, _f, _g, _h, _i, _j, _k, _l, _m, _n, _o, _p, _q, _r, _s, _t, _u, _v, _w, _x, _y, _z, _A, _B, _C, _D, _E, _F, _G, _H;
      return common_vendor.e({
        a: common_vendor.t(heroSubtitle.value),
        b: common_vendor.t(pendingCount.value),
        c: common_vendor.t(completedCount.value),
        d: scanning.value,
        e: common_vendor.o(onScan),
        f: common_vendor.o(goRequest),
        g: preferredId.value && filterDeviceId.value !== preferredId.value
      }, preferredId.value && filterDeviceId.value !== preferredId.value ? {
        h: common_vendor.o(usePreferredDevice)
      } : {}, {
        i: filterDeviceId.value
      }, filterDeviceId.value ? {
        j: common_vendor.o(clearDeviceFilter)
      } : {}, {
        k: filterDeviceId.value
      }, filterDeviceId.value ? common_vendor.e({
        l: common_vendor.t(filterDeviceId.value),
        m: filterDeviceId.value === preferredId.value
      }, filterDeviceId.value === preferredId.value ? {} : {}) : preferredId.value ? {
        o: common_vendor.t(preferredId.value)
      } : {}, {
        n: preferredId.value,
        p: !loading.value && pendingCount.value === 0
      }, !loading.value && pendingCount.value === 0 ? {} : {}, {
        q: common_vendor.f(statusOptions.value, (item, k0, i0) => {
          return {
            a: common_vendor.t(item.label),
            b: item.value,
            c: status.value === item.value ? 1 : "",
            d: common_vendor.o(($event) => changeStatus(item.value), item.value)
          };
        }),
        r: loading.value
      }, loading.value ? {} : !tasks.value.length ? common_vendor.e({
        t: pendingCount.value === 0 && completedCount.value > 0 && status.value !== "COMPLETED"
      }, pendingCount.value === 0 && completedCount.value > 0 && status.value !== "COMPLETED" ? {
        v: common_vendor.o(($event) => changeStatus("COMPLETED"))
      } : {}, {
        w: status.value && pendingCount.value === 0 && completedCount.value > 0
      }, status.value && pendingCount.value === 0 && completedCount.value > 0 ? {
        x: common_vendor.o(($event) => changeStatus(""))
      } : {}, {
        y: common_vendor.o(onScan),
        z: common_vendor.p({
          icon: "补",
          title: emptyHint.value,
          hint: "扫码到柜可查看缺货；新任务由调度下发"
        })
      }) : {}, {
        s: !tasks.value.length,
        A: common_vendor.f(tasks.value, (task, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(deviceName(task.deviceId)),
            b: common_vendor.t(task.deviceId),
            c: common_vendor.t(common_vendor.unref(common_vendor.displayLabel)("replenishment_task_status", task.status, "未知状态")),
            d: common_vendor.n(task.status.toLowerCase()),
            e: common_vendor.t(task.taskId),
            f: common_vendor.t(formatTime(task.createdAt)),
            g: task.notes
          }, task.notes ? {
            h: common_vendor.t(task.notes)
          } : {}, {
            i: common_vendor.t(taskActionLabel(task)),
            j: task.taskId,
            k: common_vendor.o(($event) => openTask(task), task.taskId)
          });
        }),
        B: detailVisible.value
      }, detailVisible.value ? common_vendor.e({
        C: common_vendor.t(deviceName((_a = selected.value) == null ? void 0 : _a.deviceId)),
        D: common_vendor.t((_b = selected.value) == null ? void 0 : _b.taskId),
        E: common_vendor.t((_c = selected.value) == null ? void 0 : _c.deviceId),
        F: common_vendor.o(closeDetail),
        G: common_vendor.t(((_d = selected.value) == null ? void 0 : _d.status) === "COMPLETED" || ((_e = selected.value) == null ? void 0 : _e.checkInAt) ? "✓" : "1"),
        H: common_vendor.n(stepClass(1)),
        I: common_vendor.t(((_f = selected.value) == null ? void 0 : _f.status) === "COMPLETED" || doorOpened.value || currentStep() > 2 ? "✓" : "2"),
        J: common_vendor.n(stepClass(2)),
        K: common_vendor.t(((_g = selected.value) == null ? void 0 : _g.status) === "COMPLETED" || linesConfirmed.value || currentStep() > 3 ? "✓" : "3"),
        L: common_vendor.n(stepClass(3)),
        M: common_vendor.t(((_h = selected.value) == null ? void 0 : _h.status) === "COMPLETED" ? "✓" : "4"),
        N: common_vendor.t(detailIsPullOff.value ? "下架" : "上架"),
        O: common_vendor.n(stepClass(4)),
        P: canRequest.value && ((_i = selected.value) == null ? void 0 : _i.status) !== "COMPLETED" && !((_j = selected.value) == null ? void 0 : _j.checkInAt)
      }, canRequest.value && ((_k = selected.value) == null ? void 0 : _k.status) !== "COMPLETED" && !((_l = selected.value) == null ? void 0 : _l.checkInAt) ? {
        Q: submitting.value,
        R: common_vendor.o(checkIn)
      } : {}, {
        S: canRequest.value && ((_m = selected.value) == null ? void 0 : _m.status) !== "COMPLETED" && ((_n = selected.value) == null ? void 0 : _n.checkInAt)
      }, canRequest.value && ((_o = selected.value) == null ? void 0 : _o.status) !== "COMPLETED" && ((_p = selected.value) == null ? void 0 : _p.checkInAt) ? {
        T: common_vendor.t(doorOpened.value ? "再次开门" : detailIsPullOff.value ? "下架开门" : "补货开门"),
        U: submitting.value,
        V: common_vendor.o(openDoor)
      } : {}, {
        W: !canRequest.value && ((_q = selected.value) == null ? void 0 : _q.status) !== "COMPLETED"
      }, !canRequest.value && ((_r = selected.value) == null ? void 0 : _r.status) !== "COMPLETED" ? {
        X: common_vendor.t(detailIsPullOff.value ? "下架" : "上架")
      } : {}, {
        Y: doorOpened.value && openSessionId.value
      }, doorOpened.value && openSessionId.value ? {
        Z: common_vendor.t(common_vendor.unref(common_vendor.emptyDisplay)(openSessionId.value, "session")),
        aa: common_vendor.t(detailIsPullOff.value ? "下架" : "上架")
      } : {}, {
        ab: common_vendor.t(((_s = selected.value) == null ? void 0 : _s.checkInAt) ? "最多 5 张，便于后台核对履约" : "签到后可拍照留存，最多 5 张"),
        ac: common_vendor.t(evidenceItems.value.length),
        ad: common_vendor.f(evidenceItems.value, (item, idx, i0) => {
          return {
            a: item.fileId || item.localPath || idx,
            b: item.localPath,
            c: `现场照片 ${idx + 1}`,
            d: common_vendor.o(($event) => previewEvidence(idx), item.fileId || item.localPath || idx)
          };
        }),
        ae: canRequest.value && ((_t = selected.value) == null ? void 0 : _t.status) !== "COMPLETED" && ((_u = selected.value) == null ? void 0 : _u.checkInAt) && evidenceItems.value.length < 5
      }, canRequest.value && ((_v = selected.value) == null ? void 0 : _v.status) !== "COMPLETED" && ((_w = selected.value) == null ? void 0 : _w.checkInAt) && evidenceItems.value.length < 5 ? {
        af: common_vendor.o(addEvidence)
      } : canRequest.value && ((_x = selected.value) == null ? void 0 : _x.status) !== "COMPLETED" && !((_y = selected.value) == null ? void 0 : _y.checkInAt) ? {} : {}, {
        ag: canRequest.value && ((_z = selected.value) == null ? void 0 : _z.status) !== "COMPLETED" && !((_A = selected.value) == null ? void 0 : _A.checkInAt),
        ah: common_vendor.t(detailIsPullOff.value ? "本次下架商品" : "本次补货商品"),
        ai: common_vendor.t(detailIsPullOff.value ? "请逐项核对下架数量与批次" : ((_B = selected.value) == null ? void 0 : _B.outboundId) ? `仓配出库 #${selected.value.outboundId} · 核对后完成将签收在途` : "请逐项核对商品、批次和货道"),
        aj: common_vendor.t(lines.value.length),
        ak: detailLoading.value
      }, detailLoading.value ? {} : !lines.value.length ? {
        am: common_vendor.t(detailIsPullOff.value ? "暂无下架明细" : "暂无补货明细"),
        an: common_vendor.t(detailIsPullOff.value ? "可先开门执行下架；有任务明细时会显示在此核对" : "可先开门上架；有出库明细时会显示在此核对")
      } : {}, {
        al: !lines.value.length,
        ao: common_vendor.f(lines.value, (line, k0, i0) => {
          var _a2, _b2, _c2, _d2, _e2, _f2;
          return common_vendor.e({
            a: common_vendor.t(productIcon(line.skuId)),
            b: common_vendor.t(skuName(line.skuId)),
            c: common_vendor.t(line.skuId),
            d: canRequest.value && ((_a2 = selected.value) == null ? void 0 : _a2.status) !== "COMPLETED" && !linesConfirmed.value && !line.applied
          }, canRequest.value && ((_b2 = selected.value) == null ? void 0 : _b2.status) !== "COMPLETED" && !linesConfirmed.value && !line.applied ? {
            e: common_vendor.o(($event) => adjustQty(line, -1), line.lineId || `${line.skuId}-${line.batchNo}-${line.slotId}`),
            f: common_vendor.t(line.quantity),
            g: common_vendor.o(($event) => adjustQty(line, 1), line.lineId || `${line.skuId}-${line.batchNo}-${line.slotId}`)
          } : {
            h: common_vendor.t(line.quantity)
          }, {
            i: common_vendor.t(line.batchNo || "无批次"),
            j: common_vendor.t(line.slotId || "待分配"),
            k: common_vendor.t(lineTypeLabel(line.lineType)),
            l: canRequest.value && ((_c2 = selected.value) == null ? void 0 : _c2.status) !== "COMPLETED" && !linesConfirmed.value && !line.applied && !isPullOffType(line.lineType) && !line.slotId
          }, canRequest.value && ((_d2 = selected.value) == null ? void 0 : _d2.status) !== "COMPLETED" && !linesConfirmed.value && !line.applied && !isPullOffType(line.lineType) && !line.slotId ? common_vendor.e({
            m: slotOptionsFor(line).length
          }, slotOptionsFor(line).length ? {
            n: common_vendor.f(slotOptionsFor(line), (opt, k1, i1) => {
              return {
                a: common_vendor.t(opt.slotCode),
                b: common_vendor.t(opt.room),
                c: opt.slotCode,
                d: opt.room <= 0 ? 1 : "",
                e: line.slotId === opt.slotCode ? 1 : "",
                f: common_vendor.o(($event) => assignSlot(line, opt), opt.slotCode)
              };
            })
          } : {}) : {}, {
            o: common_vendor.t(line.expiryDate || "未填"),
            p: common_vendor.t(lineStatusLabel(line)),
            q: ((_e2 = selected.value) == null ? void 0 : _e2.status) !== "COMPLETED" && line.slotId && slotHint(line)
          }, ((_f2 = selected.value) == null ? void 0 : _f2.status) !== "COMPLETED" && line.slotId && slotHint(line) ? {
            r: common_vendor.t(slotHint(line)),
            s: slotHeadroom(line) <= 0 ? 1 : "",
            t: slotHeadroom(line) > 0 && line.quantity > slotHeadroom(line) ? 1 : ""
          } : {}, {
            v: line.lineId || `${line.skuId}-${line.batchNo}-${line.slotId}`
          });
        }),
        ap: canRequest.value && ((_C = selected.value) == null ? void 0 : _C.status) !== "COMPLETED" && ((_D = selected.value) == null ? void 0 : _D.checkInAt)
      }, canRequest.value && ((_E = selected.value) == null ? void 0 : _E.status) !== "COMPLETED" && ((_F = selected.value) == null ? void 0 : _F.checkInAt) ? common_vendor.e({
        aq: !linesConfirmed.value
      }, !linesConfirmed.value ? {
        ar: submitting.value || !lines.value.length,
        as: common_vendor.o(confirmLines)
      } : {}, {
        at: common_vendor.t(detailIsPullOff.value ? "确认全部下架" : "确认全部上架"),
        av: submitting.value || !lines.value.length || !linesConfirmed.value,
        aw: common_vendor.o(completeTask)
      }) : {}, {
        ax: ((_G = selected.value) == null ? void 0 : _G.status) === "COMPLETED"
      }, ((_H = selected.value) == null ? void 0 : _H.status) === "COMPLETED" ? {
        ay: common_vendor.t(detailIsPullOff.value ? "任务已完成，下架库存已同步更新" : "任务已完成，商品库存和在途状态已同步更新")
      } : {}, {
        az: common_vendor.o(() => {
        }),
        aA: common_vendor.o(closeDetail),
        aB: common_vendor.o(() => {
        })
      }) : {}, {
        aC: confirmDialog.value.visible
      }, confirmDialog.value.visible ? {
        aD: common_vendor.t(confirmDialog.value.title),
        aE: common_vendor.t(confirmDialog.value.content),
        aF: common_vendor.t(confirmDialog.value.cancelText),
        aG: confirmDialog.value.cancelText,
        aH: common_vendor.o(($event) => resolveConfirm(false)),
        aI: common_vendor.t(confirmDialog.value.confirmText),
        aJ: confirmDialog.value.confirmText,
        aK: common_vendor.o(($event) => resolveConfirm(true)),
        aL: common_vendor.o(() => {
        }),
        aM: confirmDialog.value.title,
        aN: common_vendor.o(($event) => resolveConfirm(false)),
        aO: common_vendor.o(() => {
        })
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-6f040ac7"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/replenishment/replenishment.vue"]]);
wx.createPage(MiniProgramPage);
