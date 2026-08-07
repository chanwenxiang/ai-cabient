"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
if (!Math) {
  EmptyState();
}
const EmptyState = () => "../../components/empty-state.js";
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "settlements",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const canViewSettlements = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:settlements:view"));
    const canExport = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:settlements:export"));
    const canViewSplits = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:splits:list"));
    const isH5 = typeof document !== "undefined";
    function localDateISO(d) {
      const y = d.getFullYear();
      const m = String(d.getMonth() + 1).padStart(2, "0");
      const day = String(d.getDate()).padStart(2, "0");
      return `${y}-${m}-${day}`;
    }
    function batchStatusLabel(status) {
      return common_vendor.displayLabel("settlement_batch_status", status, "未知状态");
    }
    const today = localDateISO(/* @__PURE__ */ new Date());
    const sevenDaysAgo = localDateISO(new Date(Date.now() - 7 * 864e5));
    const startDate = common_vendor.ref(sevenDaysAgo);
    const endDate = common_vendor.ref(today);
    const summary = common_vendor.ref({
      gross: "0.00",
      platformFee: "0.00",
      merchantIncome: "0.00",
      pending: "0.00",
      settledMonth: "0.00"
    });
    const daily = common_vendor.ref([]);
    const batches = common_vendor.ref([]);
    const profitNote = common_vendor.ref("");
    const loading = common_vendor.ref(false);
    const loadError = common_vendor.ref("");
    const batchWarn = common_vendor.ref("");
    let loadSeq = 0;
    function readDateEvent(e) {
      var _a, _b;
      return String(((_a = e == null ? void 0 : e.detail) == null ? void 0 : _a.value) ?? ((_b = e == null ? void 0 : e.target) == null ? void 0 : _b.value) ?? "").trim();
    }
    function onStartDate(e) {
      const v = readDateEvent(e);
      if (!v) return;
      startDate.value = v;
      void load();
    }
    function onEndDate(e) {
      const v = readDateEvent(e);
      if (!v) return;
      endDate.value = v;
      void load();
    }
    common_vendor.onShow(() => load());
    common_vendor.onPullDownRefresh(() => load().finally(() => common_vendor.index.stopPullDownRefresh()));
    async function load() {
      var _a;
      if (!utils_merchantApi.getToken()) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      if (startDate.value > endDate.value) {
        loadError.value = "开始日期不能晚于结束日期";
        daily.value = [];
        batches.value = [];
        summary.value = { gross: "0.00", platformFee: "0.00", merchantIncome: "0.00", pending: "0.00", settledMonth: "0.00" };
        profitNote.value = "";
        loading.value = false;
        return;
      }
      const seq = ++loadSeq;
      try {
        await refreshMe();
      } catch {
        if (!utils_merchantApi.getToken()) return;
        me.value = me.value || common_vendor.index.getStorageSync("merchant_me") || null;
      }
      if (seq !== loadSeq) return;
      if (!canViewSettlements.value) {
        common_vendor.index.showToast({ title: "无结算权限", icon: "none" });
        common_vendor.index.navigateBack({ fail: () => common_vendor.index.switchTab({ url: "/pages/home/home" }) });
        return;
      }
      loading.value = true;
      loadError.value = "";
      batchWarn.value = "";
      try {
        const [overviewRes, daysRes, batchRes] = await Promise.allSettled([
          utils_merchantApi.merchantApi.settlements(),
          utils_merchantApi.merchantApi.dailySettlements(startDate.value, endDate.value),
          utils_merchantApi.merchantApi.settlementBatches(startDate.value, endDate.value)
        ]);
        if (seq !== loadSeq) return;
        if (overviewRes.status === "rejected" && daysRes.status === "rejected") {
          throw overviewRes.reason instanceof Error ? overviewRes.reason : new Error("结算数据加载失败");
        }
        const days = daysRes.status === "fulfilled" ? daysRes.value || [] : [];
        daily.value = days;
        if (daysRes.status === "rejected") {
          loadError.value = daysRes.reason instanceof Error ? daysRes.reason.message : "按日汇总加载失败";
        }
        if (batchRes.status === "fulfilled") {
          batches.value = batchRes.value || [];
        } else {
          batches.value = [];
          batchWarn.value = batchRes.reason instanceof Error ? batchRes.reason.message : "结算批次加载失败";
        }
        const overview = overviewRes.status === "fulfilled" ? overviewRes.value : { pendingAmountCents: 0, settledMonthCents: 0 };
        const gross = days.reduce((s, d) => s + (d.grossCents || 0), 0);
        const platform = days.reduce((s, d) => s + (d.platformCents || 0), 0);
        const merchant = days.reduce((s, d) => s + (d.merchantCents || 0), 0);
        summary.value = {
          gross: (gross / 100).toFixed(2),
          platformFee: (platform / 100).toFixed(2),
          merchantIncome: (merchant / 100).toFixed(2),
          pending: ((overview.pendingAmountCents || 0) / 100).toFixed(2),
          settledMonth: ((overview.settledMonthCents || 0) / 100).toFixed(2)
        };
        profitNote.value = ((_a = overview.profitSharing) == null ? void 0 : _a.note) || "";
      } catch (e) {
        if (seq !== loadSeq) return;
        loadError.value = e instanceof Error ? e.message : "加载失败";
        common_vendor.index.showToast({ title: loadError.value, icon: "none" });
      } finally {
        if (seq === loadSeq) loading.value = false;
      }
    }
    function goSplits() {
      common_vendor.index.navigateTo({ url: "/pages/splits/splits" });
    }
    function onExport() {
      if (!canExport.value) {
        common_vendor.index.showToast({ title: "无导出权限", icon: "none" });
        return;
      }
      if (startDate.value > endDate.value) {
        common_vendor.index.showToast({ title: "开始日期不能晚于结束日期", icon: "none" });
        return;
      }
      const url = utils_merchantApi.merchantApi.exportSettlementsUrl(startDate.value, endDate.value);
      void utils_merchantApi.downloadAuthedFile(url).then(async (tempFilePath) => {
        await utils_merchantApi.openExportedFile(tempFilePath, `settlements-${startDate.value}-${endDate.value}.xlsx`);
        common_vendor.index.showToast({ title: "导出成功", icon: "success" });
      }).catch((e) => {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "导出失败", icon: "none" });
      });
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: isH5
      }, isH5 ? {
        b: startDate.value,
        c: endDate.value,
        d: common_vendor.o(onStartDate)
      } : {
        e: common_vendor.t(startDate.value),
        f: startDate.value,
        g: endDate.value,
        h: common_vendor.o(onStartDate)
      }, {
        i: isH5
      }, isH5 ? {
        j: endDate.value,
        k: startDate.value,
        l: common_vendor.o(onEndDate)
      } : {
        m: common_vendor.t(endDate.value),
        n: endDate.value,
        o: startDate.value,
        p: common_vendor.o(onEndDate)
      }, {
        q: loadError.value
      }, loadError.value ? {
        r: common_vendor.t(loadError.value),
        s: common_vendor.o(load)
      } : {}, {
        t: common_vendor.t(summary.value.gross),
        v: common_vendor.t(summary.value.platformFee),
        w: common_vendor.t(summary.value.merchantIncome),
        x: common_vendor.t(summary.value.pending),
        y: common_vendor.t(summary.value.settledMonth),
        z: profitNote.value
      }, profitNote.value ? {
        A: common_vendor.t(profitNote.value)
      } : {}, {
        B: canViewSplits.value
      }, canViewSplits.value ? {
        C: common_vendor.o(goSplits)
      } : {}, {
        D: loading.value
      }, loading.value ? {} : common_vendor.e({
        E: common_vendor.f(daily.value, (d, k0, i0) => {
          return {
            a: common_vendor.t(d.date),
            b: common_vendor.t(d.orderCount),
            c: common_vendor.t((d.grossCents / 100).toFixed(2)),
            d: common_vendor.t((d.platformCents / 100).toFixed(2)),
            e: common_vendor.t((d.pendingCents / 100).toFixed(2)),
            f: common_vendor.t((d.settledCents / 100).toFixed(2)),
            g: common_vendor.t((d.merchantCents / 100).toFixed(2)),
            h: d.date
          };
        }),
        F: !daily.value.length
      }, !daily.value.length ? {
        G: common_vendor.p({
          compact: true,
          icon: "📅",
          title: "所选日期暂无结算数据",
          hint: "可调整上方日期范围，或等待订单完成分账"
        })
      } : {}), {
        H: batchWarn.value
      }, batchWarn.value ? {
        I: common_vendor.t(batchWarn.value)
      } : {}, {
        J: loading.value
      }, loading.value ? {} : common_vendor.e({
        K: common_vendor.f(batches.value, (b, k0, i0) => {
          return {
            a: common_vendor.t(b.batchNo),
            b: common_vendor.t(batchStatusLabel(b.batchStatus)),
            c: common_vendor.t(b.orderCount),
            d: common_vendor.t((b.merchantCents / 100).toFixed(2)),
            e: b.batchNo
          };
        }),
        L: !batches.value.length
      }, !batches.value.length ? {
        M: common_vendor.p({
          compact: true,
          icon: "📦",
          title: "暂无结算批次",
          hint: "平台定期提交分账后，批次会显示在这里"
        })
      } : {}), {
        N: canExport.value
      }, canExport.value ? {
        O: common_vendor.o(onExport)
      } : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-12534684"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/settlements/settlements.vue"]]);
wx.createPage(MiniProgramPage);
