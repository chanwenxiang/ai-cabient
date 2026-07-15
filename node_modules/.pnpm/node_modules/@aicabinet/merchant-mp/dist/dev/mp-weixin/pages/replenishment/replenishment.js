"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "replenishment",
  setup(__props) {
    const loading = common_vendor.ref(false);
    const detailLoading = common_vendor.ref(false);
    const submitting = common_vendor.ref(false);
    const status = common_vendor.ref("IN_PROGRESS");
    const allTasks = common_vendor.ref([]);
    const devices = common_vendor.ref([]);
    const skus = common_vendor.ref([]);
    const detailVisible = common_vendor.ref(false);
    const selected = common_vendor.ref(null);
    const lines = common_vendor.ref([]);
    const linesConfirmed = common_vendor.ref(false);
    const statusOptions = common_vendor.computed(() => [{ value: "", label: "全部" }, ...dictOptions("replenishment_task_status").filter((item) => ["PENDING", "IN_PROGRESS", "COMPLETED"].includes(item.value))]);
    const tasks = common_vendor.computed(() => status.value ? allTasks.value.filter((item) => item.status === status.value) : allTasks.value);
    const pendingCount = common_vendor.computed(() => allTasks.value.filter((item) => item.status !== "COMPLETED").length);
    const completedCount = common_vendor.computed(() => allTasks.value.filter((item) => item.status === "COMPLETED").length);
    function deviceName(id) {
      var _a;
      return ((_a = devices.value.find((item) => item.deviceId === id)) == null ? void 0 : _a.deviceName) || id || "未知柜机";
    }
    function skuName(id) {
      var _a;
      return ((_a = skus.value.find((item) => item.skuId === id)) == null ? void 0 : _a.skuName) || id;
    }
    function productIcon(id) {
      if (id.includes("WATER"))
        return "💧";
      if (id.includes("MILK"))
        return "🥛";
      if (id.includes("NOODLE"))
        return "🍜";
      if (id.includes("SNACK"))
        return "🥔";
      return "🥤";
    }
    function formatTime(value) {
      return common_vendor.formatDateTimeShort(value);
    }
    async function load() {
      if (!common_vendor.index.getStorageSync("merchant_token"))
        return common_vendor.index.reLaunch({ url: "/pages/login/login" });
      loading.value = true;
      try {
        const [taskRows, deviceRows, skuRows] = await Promise.all([utils_merchantApi.merchantApi.replenishmentTasks(), utils_merchantApi.merchantApi.devices(), utils_merchantApi.merchantApi.pricing()]);
        allTasks.value = taskRows;
        devices.value = deviceRows;
        skus.value = skuRows;
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "加载失败", icon: "none" });
      } finally {
        loading.value = false;
        common_vendor.index.stopPullDownRefresh();
      }
    }
    function changeStatus(value) {
      status.value = value;
    }
    async function openTask(task) {
      selected.value = { ...task };
      detailVisible.value = true;
      linesConfirmed.value = task.status === "COMPLETED";
      detailLoading.value = true;
      try {
        lines.value = await utils_merchantApi.merchantApi.replenishmentTaskLines(task.taskId);
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "明细加载失败", icon: "none" });
      } finally {
        detailLoading.value = false;
      }
    }
    function closeDetail() {
      if (!submitting.value)
        detailVisible.value = false;
    }
    async function checkIn() {
      if (!selected.value || submitting.value)
        return;
      submitting.value = true;
      let body = {};
      try {
        const location = await new Promise((resolve, reject) => common_vendor.index.getLocation({ type: "gcj02", success: resolve, fail: reject }));
        body = { latitude: location.latitude, longitude: location.longitude };
      } catch {
      }
      try {
        selected.value = await utils_merchantApi.merchantApi.checkInReplenishmentTask(selected.value.taskId, body);
        common_vendor.index.showToast({ title: "签到成功", icon: "success" });
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "签到失败", icon: "none" });
      } finally {
        submitting.value = false;
      }
    }
    async function confirmLines() {
      if (!selected.value || submitting.value)
        return;
      submitting.value = true;
      try {
        lines.value = await utils_merchantApi.merchantApi.confirmReplenishmentLines(selected.value.taskId, lines.value.map(({ lineId, ...line }) => line));
        linesConfirmed.value = true;
        common_vendor.index.showToast({ title: "清单已确认", icon: "success" });
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "确认失败", icon: "none" });
      } finally {
        submitting.value = false;
      }
    }
    async function completeTask() {
      if (!selected.value || submitting.value)
        return;
      const ok = await new Promise((resolve) => common_vendor.index.showModal({ title: "确认全部上架", content: "完成后将更新柜机库存并签收在途商品，请确认商品、批次和货道无误。", confirmText: "确认完成", success: (r) => resolve(r.confirm), fail: () => resolve(false) }));
      if (!ok)
        return;
      submitting.value = true;
      try {
        selected.value = await utils_merchantApi.merchantApi.completeReplenishmentTask(selected.value.taskId);
        lines.value = lines.value.map((line) => ({ ...line, applied: true }));
        common_vendor.index.showToast({ title: "补货完成", icon: "success" });
        await load();
      } catch (error) {
        common_vendor.index.showToast({ title: error instanceof Error ? error.message : "完成失败", icon: "none" });
      } finally {
        submitting.value = false;
      }
    }
    common_vendor.onShow(load);
    common_vendor.onPullDownRefresh(load);
    return (_ctx, _cache) => {
      var _a, _b, _c, _d, _e, _f, _g, _h, _i, _j, _k, _l, _m, _n;
      return common_vendor.e({
        a: common_vendor.t(pendingCount.value),
        b: common_vendor.t(completedCount.value),
        c: common_vendor.f(statusOptions.value, (item, k0, i0) => {
          return {
            a: common_vendor.t(item.label),
            b: item.value,
            c: status.value === item.value ? 1 : "",
            d: common_vendor.o(($event) => changeStatus(item.value), item.value)
          };
        }),
        d: loading.value
      }, loading.value ? {} : !tasks.value.length ? {} : {}, {
        e: !tasks.value.length,
        f: common_vendor.f(tasks.value, (task, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(deviceName(task.deviceId)),
            b: common_vendor.t(task.deviceId),
            c: common_vendor.t(common_vendor.unref(common_vendor.dictLabel)("replenishment_task_status", task.status)),
            d: common_vendor.n(task.status.toLowerCase()),
            e: common_vendor.t(task.taskId),
            f: common_vendor.t(formatTime(task.createdAt)),
            g: task.notes
          }, task.notes ? {
            h: common_vendor.t(task.notes)
          } : {}, {
            i: common_vendor.t(task.status === "COMPLETED" ? "查看完成明细" : "开始补货"),
            j: common_vendor.o(($event) => openTask(task), task.taskId),
            k: task.taskId
          });
        }),
        g: detailVisible.value
      }, detailVisible.value ? common_vendor.e({
        h: common_vendor.t(deviceName((_a = selected.value) == null ? void 0 : _a.deviceId)),
        i: common_vendor.t((_b = selected.value) == null ? void 0 : _b.taskId),
        j: common_vendor.o(closeDetail),
        k: !!((_c = selected.value) == null ? void 0 : _c.checkInAt) ? 1 : "",
        l: linesConfirmed.value ? 1 : "",
        m: ((_d = selected.value) == null ? void 0 : _d.status) === "COMPLETED" ? 1 : "",
        n: ((_e = selected.value) == null ? void 0 : _e.status) !== "COMPLETED" && !((_f = selected.value) == null ? void 0 : _f.checkInAt)
      }, ((_g = selected.value) == null ? void 0 : _g.status) !== "COMPLETED" && !((_h = selected.value) == null ? void 0 : _h.checkInAt) ? {
        o: submitting.value,
        p: common_vendor.o(checkIn)
      } : {}, {
        q: common_vendor.t(lines.value.length),
        r: detailLoading.value
      }, detailLoading.value ? {} : {}, {
        s: common_vendor.f(lines.value, (line, k0, i0) => {
          return {
            a: common_vendor.t(productIcon(line.skuId)),
            b: common_vendor.t(skuName(line.skuId)),
            c: common_vendor.t(line.skuId),
            d: common_vendor.t(line.quantity),
            e: common_vendor.t(line.batchNo || "-"),
            f: common_vendor.t(line.slotId || "待分配"),
            g: common_vendor.t(line.expiryDate || "-"),
            h: common_vendor.t(line.applied ? "已入柜" : "待上架"),
            i: line.lineId || `${line.skuId}-${line.batchNo}-${line.slotId}`
          };
        }),
        t: ((_i = selected.value) == null ? void 0 : _i.status) !== "COMPLETED" && ((_j = selected.value) == null ? void 0 : _j.checkInAt)
      }, ((_k = selected.value) == null ? void 0 : _k.status) !== "COMPLETED" && ((_l = selected.value) == null ? void 0 : _l.checkInAt) ? common_vendor.e({
        v: !linesConfirmed.value
      }, !linesConfirmed.value ? {
        w: submitting.value || !lines.value.length,
        x: common_vendor.o(confirmLines)
      } : {}, {
        y: submitting.value || !lines.value.length || !linesConfirmed.value,
        z: common_vendor.o(completeTask)
      }) : {}, {
        A: ((_m = selected.value) == null ? void 0 : _m.status) === "COMPLETED"
      }, ((_n = selected.value) == null ? void 0 : _n.status) === "COMPLETED" ? {} : {}, {
        B: common_vendor.o(closeDetail)
      }) : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-6f040ac7"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/replenishment/replenishment.vue"]]);
wx.createPage(MiniProgramPage);
