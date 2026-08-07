"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const config_merchantNav = require("../../config/merchant-nav.js");
const utils_scanCabinet = require("../../utils/scan-cabinet.js");
const utils_preferredDevice = require("../../utils/preferred-device.js");
const utils_merchantDisplay = require("../../utils/merchant-display.js");
const utils_todoBadge = require("../../utils/todo-badge.js");
const utils_todoList = require("../../utils/todo-list.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "home",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const preferredId = common_vendor.ref(utils_preferredDevice.getPreferredDeviceId());
    function fieldOk(key) {
      const item = config_merchantNav.MERCHANT_FIELD_NAV.find((i) => i.key === key);
      return !!item && composables_useMerchantMe.canAccessNav(me.value, item);
    }
    function bizOk(key) {
      const item = config_merchantNav.MERCHANT_BIZ_NAV.find((i) => i.key === key);
      return !!item && composables_useMerchantMe.canAccessNav(me.value, item);
    }
    const canReplenishment = common_vendor.computed(() => fieldOk("replenishment"));
    const canDevices = common_vendor.computed(() => fieldOk("devices"));
    const canAlerts = common_vendor.computed(() => fieldOk("alerts"));
    const canPricing = common_vendor.computed(() => bizOk("pricing"));
    const canSettlements = common_vendor.computed(() => bizOk("settlements"));
    const canDisputes = common_vendor.computed(() => bizOk("disputes"));
    const canBusiness = common_vendor.computed(() => bizOk("business"));
    const canTrend = common_vendor.computed(
      () => composables_useMerchantMe.hasPack(me.value, "biz") && utils_merchantApi.hasPerm(me.value, "merchant:trend:view")
    );
    const canFinanceKpi = common_vendor.computed(
      () => canBusiness.value || canSettlements.value || canTrend.value
    );
    const loading = common_vendor.ref(true);
    const taskPreviewLoading = common_vendor.ref(false);
    const scanning = common_vendor.ref(false);
    const error = common_vendor.ref("");
    const meName = common_vendor.ref("");
    const merchantNames = common_vendor.ref("");
    const revenueToday = common_vendor.ref("暂无");
    const incomeToday = common_vendor.ref("暂无");
    const trendBars = common_vendor.ref([]);
    const pendingCount = common_vendor.ref(0);
    const offlineCount = common_vendor.ref(0);
    const pendingTaskCount = common_vendor.ref(0);
    const actionItems = common_vendor.ref([]);
    const taskPreview = common_vendor.ref([]);
    const deviceMap = common_vendor.ref({});
    const stats = common_vendor.ref({});
    const latestAnnouncement = common_vendor.ref(null);
    const onlineText = common_vendor.computed(() => {
      const on = stats.value.deviceOnline;
      const total = stats.value.deviceTotal;
      if (on == null && total == null) return "暂无";
      return `${on ?? 0} / ${total ?? 0}`;
    });
    function deviceLabel(id) {
      if (!id) return "无柜机";
      return deviceMap.value[id] || id;
    }
    function statusLabel(status) {
      return common_vendor.displayLabel("replenishment_task_status", status, "未知状态");
    }
    function goTab(url) {
      common_vendor.index.switchTab({ url });
    }
    function goReplenishment(deviceId, taskId) {
      const params = [];
      if (deviceId) params.push(`deviceId=${encodeURIComponent(deviceId)}`);
      if (taskId) params.push(`taskId=${taskId}`);
      const q = params.length ? `?${params.join("&")}` : "";
      common_vendor.index.navigateTo({ url: `/pages/replenishment/replenishment${q}` });
    }
    function goRequest() {
      common_vendor.index.navigateTo({ url: "/pages/request/request" });
    }
    function goPricing() {
      common_vendor.index.navigateTo({ url: "/pages/pricing/pricing" });
    }
    function goSettlements() {
      common_vendor.index.navigateTo({ url: "/pages/settlements/settlements" });
    }
    function goDisputes() {
      common_vendor.index.navigateTo({ url: "/pages/disputes/disputes" });
    }
    function goBusiness() {
      common_vendor.index.navigateTo({ url: "/pages/business/business" });
    }
    function goAnnouncementDetail() {
      var _a;
      const id = (_a = latestAnnouncement.value) == null ? void 0 : _a.announceId;
      if (!id) {
        common_vendor.index.navigateTo({ url: "/pages/announcements/announcements" });
        return;
      }
      common_vendor.index.navigateTo({ url: `/pages/announcements/detail?id=${id}` });
    }
    async function onScan() {
      if (scanning.value) return;
      scanning.value = true;
      try {
        const deviceId = await utils_scanCabinet.scanCabinetDeviceId();
        if (!deviceId) return;
        common_vendor.index.navigateTo({
          url: `/pages/device-detail/device-detail?id=${encodeURIComponent(deviceId)}`
        });
      } finally {
        scanning.value = false;
      }
    }
    function hydrateFromCache() {
      const cached = common_vendor.index.getStorageSync("merchant_me") || {};
      if (cached && (cached.permissions || cached.displayName || cached.phoneNumber)) {
        me.value = cached;
      }
      if (cached.displayName || cached.phoneNumber) {
        meName.value = cached.displayName || cached.phoneNumber || "同事";
        merchantNames.value = utils_merchantDisplay.formatMerchantNames(cached.merchants);
      }
    }
    let loadSeq = 0;
    async function load() {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      const seq = ++loadSeq;
      hydrateFromCache();
      loading.value = !meName.value;
      taskPreviewLoading.value = canReplenishment.value;
      error.value = "";
      try {
        let profile;
        try {
          profile = await refreshMe();
        } catch {
          if (!common_vendor.index.getStorageSync("merchant_token")) return;
          profile = common_vendor.index.getStorageSync("merchant_me") || {};
          me.value = profile;
        }
        if (seq !== loadSeq) return;
        meName.value = profile.displayName || profile.phoneNumber || "同事";
        merchantNames.value = utils_merchantDisplay.formatMerchantNames(profile.merchants);
        const [s, trend, workbench, exceptionPage, expiryRows, devices, tasks, announcements] = await Promise.all([
          utils_merchantApi.merchantApi.stats().catch(() => ({})),
          canTrend.value ? utils_merchantApi.merchantApi.trend(7).catch(
            () => ({ last7Days: [] })
          ) : Promise.resolve({ last7Days: [] }),
          canAlerts.value ? utils_merchantApi.merchantApi.workbench().catch(() => ({
            offlineDevices: 0,
            openDisputes: 0,
            lowStockItems: 0,
            expiryAlerts: 0,
            slotDiscrepancies: 0,
            actionItems: []
          })) : Promise.resolve({
            offlineDevices: 0,
            openDisputes: 0,
            lowStockItems: 0,
            expiryAlerts: 0,
            slotDiscrepancies: 0,
            actionItems: []
          }),
          canAlerts.value ? utils_merchantApi.merchantApi.openExceptions(100).catch(() => ({ items: [], total: 0 })) : Promise.resolve({ items: [], total: 0 }),
          canAlerts.value ? utils_merchantApi.merchantApi.expiryAlerts().catch(() => []) : Promise.resolve([]),
          canDevices.value || canReplenishment.value ? utils_merchantApi.merchantApi.devices().catch(() => []) : Promise.resolve([]),
          canReplenishment.value ? utils_merchantApi.merchantApi.replenishmentTasks().catch(() => []) : Promise.resolve([]),
          utils_merchantApi.merchantApi.listAnnouncements().catch(() => [])
        ]);
        if (seq !== loadSeq) return;
        latestAnnouncement.value = (announcements == null ? void 0 : announcements[0]) || null;
        const days = trend.last7Days || [];
        const maxRev = Math.max(...days.map((d) => d.revenueCents), 1);
        stats.value = s;
        revenueToday.value = canFinanceKpi.value ? common_vendor.fmtMoney(s.revenueTodayCents) : "暂无";
        incomeToday.value = canFinanceKpi.value ? common_vendor.fmtMoney(s.merchantIncomeTodayCents) : "暂无";
        offlineCount.value = canAlerts.value ? workbench.offlineDevices || 0 : Number(s.deviceOffline || 0);
        const mergedTodos = canAlerts.value ? utils_todoList.mergeTodoItems({
          exceptions: exceptionPage.items || [],
          actionItems: workbench.actionItems || [],
          expiryRows: expiryRows || []
        }) : [];
        pendingCount.value = mergedTodos.length;
        utils_todoBadge.setAlertsTabBadge(pendingCount.value);
        actionItems.value = canAlerts.value ? mergedTodos.slice(0, 3).map((a) => ({
          type: a.type,
          title: a.title,
          detail: a.detail,
          deviceId: a.deviceId
        })) : [];
        trendBars.value = canFinanceKpi.value ? days.map((d) => ({
          date: d.date,
          label: d.date.slice(5),
          height: Math.max(16, Math.round(d.revenueCents / maxRev * 120))
        })) : [];
        const map = {};
        for (const d of devices) {
          map[d.deviceId] = d.deviceName || d.deviceId;
        }
        deviceMap.value = map;
        const taskRows = tasks || [];
        const openTasks = taskRows.filter((t) => t.status !== "COMPLETED" && t.status !== "CANCELLED");
        preferredId.value = utils_preferredDevice.getPreferredDeviceId();
        const preferred = preferredId.value;
        const preferredKey = String(preferred || "").trim().toUpperCase();
        const sorted = preferredKey ? [
          ...openTasks.filter((t) => String(t.deviceId || "").trim().toUpperCase() === preferredKey),
          ...openTasks.filter((t) => String(t.deviceId || "").trim().toUpperCase() !== preferredKey)
        ] : openTasks;
        pendingTaskCount.value = canReplenishment.value ? sorted.length : 0;
        taskPreview.value = canReplenishment.value ? sorted.slice(0, 5) : [];
      } catch (e) {
        if (seq !== loadSeq) return;
        error.value = e instanceof Error ? e.message : "加载失败";
      } finally {
        if (seq === loadSeq) {
          loading.value = false;
          taskPreviewLoading.value = false;
        }
      }
    }
    common_vendor.onShow(load);
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: loading.value && !meName.value
      }, loading.value && !meName.value ? {} : error.value && !meName.value ? {
        c: common_vendor.t(error.value)
      } : common_vendor.e({
        d: error.value
      }, error.value ? {
        e: common_vendor.t(error.value),
        f: common_vendor.o(load)
      } : {}, {
        g: common_vendor.t(meName.value),
        h: common_vendor.t(merchantNames.value),
        i: common_vendor.t(pendingTaskCount.value),
        j: common_vendor.t(pendingCount.value),
        k: pendingCount.value > 0 ? 1 : "",
        l: common_vendor.t(offlineCount.value),
        m: scanning.value,
        n: common_vendor.o(onScan),
        o: latestAnnouncement.value
      }, latestAnnouncement.value ? {
        p: common_vendor.t(latestAnnouncement.value.title),
        q: `公告：${latestAnnouncement.value.title}`,
        r: common_vendor.o(goAnnouncementDetail)
      } : {}, {
        s: canReplenishment.value || canDevices.value || canAlerts.value
      }, canReplenishment.value || canDevices.value || canAlerts.value ? common_vendor.e({
        t: canReplenishment.value
      }, canReplenishment.value ? common_vendor.e({
        v: pendingTaskCount.value
      }, pendingTaskCount.value ? {
        w: common_vendor.t(pendingTaskCount.value)
      } : {}, {
        x: common_vendor.o(($event) => goReplenishment())
      }) : {}, {
        y: canDevices.value
      }, canDevices.value ? {
        z: common_vendor.o(($event) => goTab("/pages/devices/devices"))
      } : {}, {
        A: canAlerts.value
      }, canAlerts.value ? common_vendor.e({
        B: pendingCount.value
      }, pendingCount.value ? {
        C: common_vendor.t(pendingCount.value)
      } : {}, {
        D: common_vendor.o(($event) => goTab("/pages/alerts/alerts"))
      }) : {}) : {}, {
        E: canReplenishment.value
      }, canReplenishment.value ? common_vendor.e({
        F: common_vendor.o(($event) => goReplenishment()),
        G: preferredId.value
      }, preferredId.value ? {
        H: common_vendor.t(preferredId.value)
      } : {}, {
        I: taskPreviewLoading.value
      }, taskPreviewLoading.value ? {} : !taskPreview.value.length ? common_vendor.e({
        K: scanning.value,
        L: common_vendor.o(onScan),
        M: canDevices.value
      }, canDevices.value ? {
        N: common_vendor.o(($event) => goTab("/pages/devices/devices"))
      } : {}, {
        O: common_vendor.o(($event) => goReplenishment())
      }) : {}, {
        J: !taskPreview.value.length,
        P: common_vendor.f(taskPreview.value, (task, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(deviceLabel(task.deviceId)),
            b: preferredId.value && task.deviceId === preferredId.value
          }, preferredId.value && task.deviceId === preferredId.value ? {} : {}, {
            c: common_vendor.t(task.deviceId),
            d: common_vendor.t(statusLabel(task.status)),
            e: task.taskId,
            f: `补货任务 ${deviceLabel(task.deviceId)} ${statusLabel(task.status)}`,
            g: common_vendor.o(($event) => goReplenishment(task.deviceId, task.taskId), task.taskId)
          });
        })
      }) : {}, {
        Q: canAlerts.value && actionItems.value.length
      }, canAlerts.value && actionItems.value.length ? {
        R: common_vendor.f(actionItems.value, (item, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(item.title),
            b: item.deviceId
          }, item.deviceId ? {
            c: common_vendor.t(item.deviceId)
          } : {}, {
            d: item.detail
          }, item.detail ? {
            e: common_vendor.t(item.detail)
          } : {}, {
            f: item.type + item.title,
            g: common_vendor.o(($event) => goTab("/pages/alerts/alerts"), item.type + item.title)
          });
        }),
        S: common_vendor.o(($event) => goTab("/pages/alerts/alerts"))
      } : {}, {
        T: canPricing.value || canSettlements.value || canDisputes.value || canBusiness.value || canReplenishment.value
      }, canPricing.value || canSettlements.value || canDisputes.value || canBusiness.value || canReplenishment.value ? common_vendor.e({
        U: canReplenishment.value
      }, canReplenishment.value ? {
        V: common_vendor.o(goRequest)
      } : {}, {
        W: canPricing.value
      }, canPricing.value ? {
        X: common_vendor.o(goPricing)
      } : {}, {
        Y: canSettlements.value
      }, canSettlements.value ? {
        Z: common_vendor.o(goSettlements)
      } : {}, {
        aa: canDisputes.value
      }, canDisputes.value ? {
        ab: common_vendor.o(goDisputes)
      } : {}, {
        ac: canBusiness.value
      }, canBusiness.value ? {
        ad: common_vendor.o(goBusiness)
      } : {}) : {}, {
        ae: canFinanceKpi.value || canDevices.value
      }, canFinanceKpi.value || canDevices.value ? common_vendor.e({
        af: common_vendor.t(canFinanceKpi.value ? "近7日营收" : "柜机概况"),
        ag: canFinanceKpi.value
      }, canFinanceKpi.value ? {
        ah: common_vendor.t(revenueToday.value)
      } : {}, {
        ai: canFinanceKpi.value
      }, canFinanceKpi.value ? {
        aj: common_vendor.t(incomeToday.value)
      } : {}, {
        ak: common_vendor.t(onlineText.value),
        al: canFinanceKpi.value && trendBars.value.length
      }, canFinanceKpi.value && trendBars.value.length ? {
        am: common_vendor.f(trendBars.value, (b, k0, i0) => {
          return {
            a: b.height + "rpx",
            b: common_vendor.t(b.label),
            c: b.date
          };
        })
      } : {}) : {}), {
        b: error.value && !meName.value
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-0cd09a48"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/home/home.vue"]]);
wx.createPage(MiniProgramPage);
