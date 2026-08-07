"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const utils_preferredDevice = require("../../utils/preferred-device.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "request",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const preferredId = common_vendor.ref(utils_preferredDevice.getPreferredDeviceId());
    const canView = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:replenishment:view"));
    const canRequest = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:replenishment:request"));
    const mode = common_vendor.ref("create");
    const devices = common_vendor.ref([]);
    const deviceIndex = common_vendor.ref(0);
    const selectedDeviceId = common_vendor.computed(() => {
      var _a;
      return ((_a = devices.value[deviceIndex.value]) == null ? void 0 : _a.deviceId) || "";
    });
    const deviceLabels = common_vendor.computed(
      () => devices.value.map((d) => {
        const name = d.deviceName || d.deviceId;
        return preferredId.value && d.deviceId === preferredId.value ? `${name}（常驻）` : name;
      })
    );
    const draftLoading = common_vendor.ref(false);
    const listLoading = common_vendor.ref(false);
    let draftSeq = 0;
    let listSeq = 0;
    const draftLines = common_vendor.ref([]);
    const notes = common_vendor.ref("");
    const submitting = common_vendor.ref(false);
    const statusTabs = [
      { value: "", label: "全部" },
      { value: "SUBMITTED", label: "待审核" },
      { value: "ACCEPTED", label: "已接单" },
      { value: "COMPLETED", label: "已完成" },
      { value: "REJECTED", label: "已驳回" }
    ];
    const listStatus = common_vendor.ref("");
    const listError = common_vendor.ref("");
    const requests = common_vendor.ref([]);
    const selectedCount = common_vendor.computed(() => draftLines.value.filter((l) => l.selected && l.qty > 0).length);
    const canSubmit = common_vendor.computed(() => canRequest.value && !!selectedDeviceId.value && selectedCount.value > 0);
    common_vendor.onLoad((opts) => {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      const deviceId = typeof (opts == null ? void 0 : opts.deviceId) === "string" ? decodeURIComponent(opts.deviceId) : "";
      const tab = typeof (opts == null ? void 0 : opts.tab) === "string" ? opts.tab : "";
      if (tab === "list") mode.value = "list";
      void bootstrap(deviceId);
    });
    common_vendor.onShow(() => {
      preferredId.value = utils_preferredDevice.getPreferredDeviceId();
      if (mode.value === "list") void loadRequests();
    });
    common_vendor.onPullDownRefresh(() => {
      void bootstrap().finally(() => common_vendor.index.stopPullDownRefresh());
    });
    common_vendor.watch(selectedDeviceId, (id, prev) => {
      if (id && id !== prev && mode.value === "create") void loadDraft();
    });
    async function bootstrap(preferDeviceId) {
      try {
        await refreshMe();
      } catch {
        if (!common_vendor.index.getStorageSync("merchant_token")) return;
        me.value = common_vendor.index.getStorageSync("merchant_me") || null;
      }
      if (!canView.value) {
        common_vendor.index.showToast({ title: "无补货查看权限", icon: "none" });
        common_vendor.index.navigateBack({ fail: () => common_vendor.index.switchTab({ url: "/pages/home/home" }) });
        return;
      }
      try {
        devices.value = await utils_merchantApi.merchantApi.devices() || [];
      } catch (e) {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "柜机加载失败", icon: "none" });
        devices.value = [];
      }
      const prefer = preferDeviceId || preferredId.value;
      const preferKey = String(prefer || "").trim().toUpperCase();
      const idx = preferKey ? devices.value.findIndex((d) => String(d.deviceId || "").trim().toUpperCase() === preferKey) : -1;
      deviceIndex.value = idx >= 0 ? idx : 0;
      if (mode.value === "create") await loadDraft();
      else await loadRequests();
    }
    function onDevicePick(e) {
      deviceIndex.value = Number(e.detail.value) || 0;
    }
    function switchToList() {
      mode.value = "list";
      void loadRequests();
    }
    function changeListStatus(status) {
      listStatus.value = status;
      void loadRequests();
    }
    async function loadDraft() {
      const deviceId = selectedDeviceId.value;
      if (!deviceId) {
        draftLines.value = [];
        return;
      }
      const seq = ++draftSeq;
      draftLoading.value = true;
      try {
        const [suggest, slots] = await Promise.all([
          utils_merchantApi.merchantApi.replenishmentSuggestions(deviceId).catch(() => []),
          utils_merchantApi.merchantApi.deviceSlots(deviceId).catch(() => [])
        ]);
        if (seq !== draftSeq) return;
        const suggestMap = /* @__PURE__ */ new Map();
        for (const s of suggest || []) {
          if (!(s == null ? void 0 : s.skuId)) continue;
          const prev = suggestMap.get(s.skuId);
          if (!prev || (s.suggestQty || 0) > (prev.suggestQty || 0)) suggestMap.set(s.skuId, s);
        }
        const bySku = /* @__PURE__ */ new Map();
        for (const slot of slots || []) {
          const skuId = String(slot.assignedSkuId || "").trim();
          if (!skuId) continue;
          const sug = suggestMap.get(skuId);
          const book = Number(slot.bookQty) || 0;
          const capacity = Number(slot.maxLevel ?? slot.parLevel) || 0;
          const suggestQty = Number(sug == null ? void 0 : sug.suggestQty) || 0;
          const existing = bySku.get(skuId);
          if (existing) {
            existing.currentQty += book;
            existing.capacity += capacity;
            existing.suggestQty = Math.max(existing.suggestQty, suggestQty);
            continue;
          }
          const defaultQty = suggestQty > 0 ? suggestQty : Math.max(0, (Number(slot.parLevel) || 0) - book);
          bySku.set(skuId, {
            skuId,
            skuName: String(slot.assignedSkuName || skuId),
            currentQty: book,
            capacity,
            suggestQty,
            qty: defaultQty > 0 ? defaultQty : 1,
            selected: suggestQty > 0 || defaultQty > 0
          });
        }
        for (const [skuId, sug] of suggestMap) {
          if (bySku.has(skuId)) continue;
          const qty = Math.max(1, Number(sug.suggestQty) || 1);
          bySku.set(skuId, {
            skuId,
            skuName: skuId,
            currentQty: Number(sug.currentQty) || 0,
            capacity: Number(sug.capacity) || 0,
            suggestQty: Number(sug.suggestQty) || 0,
            qty,
            selected: (sug.suggestQty || 0) > 0
          });
        }
        if (seq !== draftSeq) return;
        draftLines.value = [...bySku.values()].sort((a, b) => {
          if (a.selected !== b.selected) return a.selected ? -1 : 1;
          return b.suggestQty - a.suggestQty;
        });
      } catch (e) {
        if (seq !== draftSeq) return;
        draftLines.value = [];
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "建议加载失败", icon: "none" });
      } finally {
        if (seq === draftSeq) draftLoading.value = false;
      }
    }
    function toggleLine(line) {
      line.selected = !line.selected;
      if (line.selected && line.qty <= 0) line.qty = Math.max(1, line.suggestQty || 1);
    }
    function adjustQty(line, delta) {
      const next = Math.max(0, (line.qty || 0) + delta);
      line.qty = next;
      if (next > 0) line.selected = true;
      else line.selected = false;
    }
    async function submit() {
      if (!canSubmit.value || submitting.value) return;
      const deviceId = selectedDeviceId.value;
      const lines = draftLines.value.filter((l) => l.selected && l.qty > 0).map((l) => ({ skuId: l.skuId, requestedQty: l.qty }));
      if (!lines.length) {
        common_vendor.index.showToast({ title: "请选择要货商品", icon: "none" });
        return;
      }
      submitting.value = true;
      try {
        const created = await utils_merchantApi.merchantApi.submitReplenishmentRequest({
          deviceId,
          notes: notes.value.trim() || void 0,
          lines
        });
        common_vendor.index.showToast({ title: `已提交 #${created.requestId}`, icon: "success" });
        notes.value = "";
        mode.value = "list";
        listStatus.value = "SUBMITTED";
        await loadRequests();
      } catch (e) {
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "提交失败", icon: "none" });
      } finally {
        submitting.value = false;
      }
    }
    async function loadRequests() {
      if (!canView.value) return;
      const seq = ++listSeq;
      listLoading.value = true;
      listError.value = "";
      try {
        const rows = await utils_merchantApi.merchantApi.replenishmentRequests(listStatus.value || void 0) || [];
        if (seq !== listSeq) return;
        requests.value = rows;
      } catch (e) {
        if (seq !== listSeq) return;
        listError.value = e instanceof Error ? e.message : "加载失败";
        requests.value = [];
      } finally {
        if (seq === listSeq) listLoading.value = false;
      }
    }
    function formatTime(value) {
      return common_vendor.formatDateTimeShort(value, "暂无");
    }
    function canGoReplenish(req) {
      return req.status === "ACCEPTED" && !!req.replenishmentTaskId;
    }
    function onRequestCard(req) {
      if (!canGoReplenish(req)) return;
      goReplenish(req);
    }
    function goReplenish(req) {
      if (!req.replenishmentTaskId) return;
      common_vendor.index.navigateTo({
        url: `/pages/replenishment/replenishment?taskId=${req.replenishmentTaskId}`
      });
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: mode.value === "create" ? 1 : "",
        b: common_vendor.o(($event) => mode.value = "create"),
        c: mode.value === "list" ? 1 : "",
        d: common_vendor.o(switchToList),
        e: mode.value === "create"
      }, mode.value === "create" ? common_vendor.e({
        f: common_vendor.t(deviceLabels.value[deviceIndex.value] || "请选择柜机"),
        g: deviceLabels.value,
        h: deviceIndex.value,
        i: common_vendor.o(onDevicePick),
        j: preferredId.value && selectedDeviceId.value === preferredId.value
      }, preferredId.value && selectedDeviceId.value === preferredId.value ? {} : {}, {
        k: common_vendor.o(loadDraft),
        l: draftLoading.value
      }, draftLoading.value ? {} : !draftLines.value.length ? {} : {}, {
        m: !draftLines.value.length,
        n: common_vendor.f(draftLines.value, (line, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(line.selected ? "✓" : ""),
            b: line.selected ? 1 : "",
            c: common_vendor.t(line.skuName),
            d: common_vendor.t(line.skuId),
            e: common_vendor.t(line.currentQty),
            f: common_vendor.t(line.capacity),
            g: line.suggestQty > 0
          }, line.suggestQty > 0 ? {
            h: common_vendor.t(line.suggestQty)
          } : {}, {
            i: common_vendor.o(($event) => adjustQty(line, -1), line.skuId),
            j: common_vendor.t(line.qty),
            k: common_vendor.o(($event) => adjustQty(line, 1), line.skuId),
            l: common_vendor.o(() => {
            }, line.skuId),
            m: line.skuId,
            n: common_vendor.o(($event) => toggleLine(line), line.skuId)
          });
        }),
        o: notes.value,
        p: common_vendor.o(($event) => notes.value = $event.detail.value),
        q: common_vendor.t(submitting.value ? "提交中…" : `提交要货（${selectedCount.value} 种）`),
        r: submitting.value || !canSubmit.value ? 1 : "",
        s: common_vendor.o(submit),
        t: !canRequest.value
      }, !canRequest.value ? {} : {}) : common_vendor.e({
        v: common_vendor.f(statusTabs, (t, k0, i0) => {
          return {
            a: common_vendor.t(t.label),
            b: t.value,
            c: listStatus.value === t.value ? 1 : "",
            d: common_vendor.o(($event) => changeListStatus(t.value), t.value)
          };
        }),
        w: listLoading.value
      }, listLoading.value ? {} : listError.value ? {
        y: common_vendor.t(listError.value)
      } : !requests.value.length ? {} : {}, {
        x: listError.value,
        z: !requests.value.length,
        A: common_vendor.f(requests.value, (req, k0, i0) => {
          var _a, _b;
          return common_vendor.e({
            a: common_vendor.t(req.requestId),
            b: common_vendor.t(common_vendor.unref(common_vendor.displayLabel)("replenishment_request_status", req.status)),
            c: common_vendor.n((req.status || "").toLowerCase()),
            d: common_vendor.t(req.deviceName || req.deviceId),
            e: common_vendor.t(req.deviceId),
            f: common_vendor.t(formatTime(req.submittedAt)),
            g: (_a = req.lines) == null ? void 0 : _a.length
          }, ((_b = req.lines) == null ? void 0 : _b.length) ? {
            h: common_vendor.f(req.lines, (l, k1, i1) => {
              return {
                a: common_vendor.t(l.skuName || l.skuId),
                b: common_vendor.t(l.requestedQty),
                c: l.lineId || l.skuId
              };
            })
          } : {}, {
            i: req.rejectReason
          }, req.rejectReason ? {
            j: common_vendor.t(req.rejectReason)
          } : {}, {
            k: req.notes
          }, req.notes ? {
            l: common_vendor.t(req.notes)
          } : {}, {
            m: req.status === "ACCEPTED" && req.replenishmentTaskId
          }, req.status === "ACCEPTED" && req.replenishmentTaskId ? {} : {}, {
            n: req.requestId,
            o: canGoReplenish(req) ? 1 : "",
            p: canGoReplenish(req) ? "req-card-hover" : "",
            q: common_vendor.o(($event) => onRequestCard(req), req.requestId)
          });
        }),
        B: requests.value.length >= 100
      }, requests.value.length >= 100 ? {
        C: common_vendor.t(requests.value.length)
      } : {}));
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-a2f8b0fb"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/request/request.vue"]]);
wx.createPage(MiniProgramPage);
