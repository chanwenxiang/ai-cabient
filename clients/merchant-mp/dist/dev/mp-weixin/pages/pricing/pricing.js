"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
if (!Math) {
  EmptyState();
}
const EmptyState = () => "../../components/empty-state.js";
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "pricing",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const loading = common_vendor.ref(true);
    const error = common_vendor.ref("");
    const rows = common_vendor.ref([]);
    const draft = common_vendor.ref({});
    const devices = common_vendor.ref([]);
    const selectedDeviceId = common_vendor.ref("");
    const gated = common_vendor.ref(false);
    const savingKey = common_vendor.ref("");
    let loadSeq = 0;
    let loadingInFlight = false;
    let pendingReload = null;
    const canView = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:pricing:view"));
    const canEdit = common_vendor.computed(() => composables_useMerchantMe.canEditPricingWithPerm(me.value));
    const deviceOptions = common_vendor.computed(
      () => [
        { deviceId: "", label: "全部柜机" },
        ...devices.value.map((d) => ({ deviceId: d.deviceId, label: d.deviceName || d.deviceId }))
      ]
    );
    const selectedLabel = common_vendor.computed(() => {
      const hit = deviceOptions.value.find((d) => d.deviceId === selectedDeviceId.value);
      return (hit == null ? void 0 : hit.label) || "全部柜机";
    });
    function draftKey(p) {
      return `${p.deviceId}::${p.skuId}`;
    }
    function money(cents) {
      if (cents == null || Number.isNaN(Number(cents))) return "暂无";
      return `¥${(Number(cents) / 100).toFixed(2)}`;
    }
    function draftValueFor(p) {
      return p.overridePriceCents != null ? (p.overridePriceCents / 100).toFixed(2) : "";
    }
    common_vendor.onShow(() => {
      void load(true);
    });
    async function load(soft = false) {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      if (loadingInFlight) {
        pendingReload = soft ? pendingReload || "soft" : "hard";
        return;
      }
      loadingInFlight = true;
      const seq = ++loadSeq;
      if (!soft || !rows.value.length) loading.value = true;
      error.value = "";
      try {
        try {
          await refreshMe();
        } catch {
          if (!common_vendor.index.getStorageSync("merchant_token")) return;
          me.value = me.value || common_vendor.index.getStorageSync("merchant_me") || null;
        }
        if (seq !== loadSeq) return;
        if (!canView.value) {
          loading.value = false;
          if (!gated.value) {
            gated.value = true;
            common_vendor.index.showToast({ title: "无定价查看权限", icon: "none" });
            common_vendor.index.switchTab({ url: "/pages/home/home" });
          }
          return;
        }
        if (!devices.value.length) {
          devices.value = await utils_merchantApi.merchantApi.devices();
        }
        if (seq !== loadSeq) return;
        const list = await utils_merchantApi.merchantApi.pricing(selectedDeviceId.value || void 0);
        if (seq !== loadSeq) return;
        rows.value = list;
        const next = {};
        for (const p of list) {
          next[draftKey(p)] = draftValueFor(p);
        }
        draft.value = next;
      } catch (e) {
        if (seq === loadSeq) {
          error.value = e instanceof Error ? e.message : "加载失败";
        }
      } finally {
        if (seq === loadSeq) {
          loading.value = false;
          loadingInFlight = false;
          const again = pendingReload;
          pendingReload = null;
          if (again) void load(again === "soft");
        }
      }
    }
    function onDevicePick(e) {
      var _a;
      const idx = Number(e.detail.value);
      selectedDeviceId.value = ((_a = deviceOptions.value[idx]) == null ? void 0 : _a.deviceId) || "";
      void load(false);
    }
    async function savePrice(p) {
      if (!canEdit.value || !p.deviceId) return;
      const key = draftKey(p);
      if (savingKey.value === key) return;
      const raw = (draft.value[key] || "").trim();
      const priceCents = raw === "" ? null : Math.round(parseFloat(raw) * 100);
      if (raw !== "" && (Number.isNaN(priceCents) || priceCents < 0)) {
        common_vendor.index.showToast({ title: "价格无效", icon: "none" });
        return;
      }
      if (p.minPriceCents != null && priceCents != null && priceCents < p.minPriceCents) {
        common_vendor.index.showToast({
          title: `不低于 ¥${(p.minPriceCents / 100).toFixed(2)}`,
          icon: "none"
        });
        return;
      }
      if (p.maxPriceCents != null && priceCents != null && priceCents > p.maxPriceCents) {
        common_vendor.index.showToast({
          title: `不高于 ¥${(p.maxPriceCents / 100).toFixed(2)}`,
          icon: "none"
        });
        return;
      }
      const prev = draftValueFor(p);
      if (raw === prev) return;
      savingKey.value = key;
      try {
        const updated = await utils_merchantApi.merchantApi.updatePricing(p.skuId, {
          deviceId: p.deviceId,
          priceCents
        });
        const idx = rows.value.findIndex((r) => draftKey(r) === key);
        if (idx >= 0) {
          rows.value[idx] = { ...rows.value[idx], ...updated };
          draft.value[key] = draftValueFor(rows.value[idx]);
        }
        common_vendor.index.showToast({ title: "已更新", icon: "success" });
      } catch (e) {
        draft.value[key] = prev;
        common_vendor.index.showToast({ title: e instanceof Error ? e.message : "保存失败", icon: "none" });
      } finally {
        savingKey.value = "";
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: !canView.value
      }, !canView.value ? {} : common_vendor.e({
        b: common_vendor.t(selectedLabel.value),
        c: deviceOptions.value,
        d: common_vendor.o(onDevicePick),
        e: !canEdit.value
      }, !canEdit.value ? {} : {}, {
        f: loading.value && !rows.value.length
      }, loading.value && !rows.value.length ? {} : error.value && !rows.value.length ? {
        h: common_vendor.t(error.value)
      } : common_vendor.e({
        i: error.value
      }, error.value ? {
        j: common_vendor.t(error.value),
        k: common_vendor.o(($event) => load(false))
      } : {}, {
        l: common_vendor.f(rows.value, (p, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(p.skuName),
            b: common_vendor.t(p.deviceName || p.deviceId),
            c: common_vendor.t(money(p.basePriceCents)),
            d: p.minPriceCents != null || p.maxPriceCents != null
          }, p.minPriceCents != null || p.maxPriceCents != null ? {
            e: common_vendor.t(p.minPriceCents != null ? money(p.minPriceCents) : "未设"),
            f: common_vendor.t(p.maxPriceCents != null ? money(p.maxPriceCents) : "未设")
          } : {}, {
            g: common_vendor.t(money(p.effectivePriceCents))
          }, canEdit.value ? {
            h: savingKey.value === draftKey(p),
            i: common_vendor.o(($event) => savePrice(p), draftKey(p)),
            j: draft.value[draftKey(p)],
            k: common_vendor.o(($event) => draft.value[draftKey(p)] = $event.detail.value, draftKey(p))
          } : {}, {
            l: canEdit.value && savingKey.value === draftKey(p)
          }, canEdit.value && savingKey.value === draftKey(p) ? {} : {}, {
            m: draftKey(p)
          });
        }),
        m: canEdit.value,
        n: !rows.value.length
      }, !rows.value.length ? {
        o: common_vendor.p({
          icon: "价",
          title: "暂无定价数据",
          hint: "选择柜机后可查看 SKU 基准价与覆盖价"
        })
      } : {}), {
        g: error.value && !rows.value.length
      }));
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-1dd96812"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/pricing/pricing.vue"]]);
wx.createPage(MiniProgramPage);
