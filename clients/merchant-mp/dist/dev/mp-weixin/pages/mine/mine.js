"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const utils_notify = require("../../utils/notify.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
const config_merchantNav = require("../../config/merchant-nav.js");
const utils_merchantDisplay = require("../../utils/merchant-display.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "mine",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const meName = common_vendor.ref("");
    const merchantNames = common_vendor.ref("");
    const phone = common_vendor.ref("");
    const avatarText = common_vendor.computed(() => (meName.value || "商").slice(0, 1));
    const notifyBusy = common_vendor.ref(false);
    const wxBound = common_vendor.ref(false);
    const enabledTypes = common_vendor.ref([]);
    const alertTypeOptions = utils_notify.MERCHANT_ALERT_TYPES;
    const subscribeReady = utils_notify.hasSubscribeTemplates();
    const notifyDesc = common_vendor.computed(() => {
      if (!subscribeReady) return "未配置订阅模板，偏好可保存但无法申请微信推送授权";
      if (wxBound.value) return "已绑定微信，可接收待办推送";
      return "绑定微信后可接收待办推送";
    });
    const fieldNav = common_vendor.computed(() => config_merchantNav.MERCHANT_FIELD_NAV.filter((i) => composables_useMerchantMe.canAccessNav(me.value, i)));
    const bizNav = common_vendor.computed(() => config_merchantNav.MERCHANT_BIZ_NAV.filter((i) => composables_useMerchantMe.canAccessNav(me.value, i)));
    const teamNav = common_vendor.computed(() => config_merchantNav.MERCHANT_TEAM_NAV.filter((i) => composables_useMerchantMe.canAccessNav(me.value, i)));
    const canAlerts = common_vendor.computed(() => fieldNav.value.some((i) => i.key === "alerts"));
    function goNav(item) {
      if (item.tab) {
        common_vendor.index.switchTab({ url: item.url });
        return;
      }
      common_vendor.index.navigateTo({ url: item.url });
    }
    function goAnnouncements() {
      common_vendor.index.navigateTo({ url: "/pages/announcements/announcements" });
    }
    async function loadNotifyPrefs() {
      if (!canAlerts.value) return;
      try {
        const prefs = await utils_merchantApi.merchantApi.notifyPrefs();
        wxBound.value = !!prefs.wxBound;
        enabledTypes.value = [...prefs.enabledAlertTypes || []];
      } catch {
      }
    }
    common_vendor.onShow(async () => {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      try {
        await refreshMe();
      } catch {
        if (!common_vendor.index.getStorageSync("merchant_token")) return;
        me.value = common_vendor.index.getStorageSync("merchant_me") || null;
      }
      const profile = me.value || (common_vendor.index.getStorageSync("merchant_me") || {});
      meName.value = profile.displayName || profile.phoneNumber || "商户";
      merchantNames.value = utils_merchantDisplay.formatMerchantNames(profile.merchants, "未绑定");
      phone.value = profile.phoneNumber || "";
      await loadNotifyPrefs();
    });
    function onToggleType(type, on) {
      const set = new Set(enabledTypes.value);
      if (on) set.add(type);
      else set.delete(type);
      enabledTypes.value = [...set];
    }
    async function onBindWx() {
      if (!subscribeReady) {
        common_vendor.index.showToast({ title: "未配置订阅模板，无法开启推送", icon: "none" });
        return;
      }
      notifyBusy.value = true;
      try {
        const sub = await utils_notify.requestMerchantSubscribe();
        if (sub === "failed") {
          common_vendor.index.showToast({ title: "微信授权未完成，仍可继续绑定账号", icon: "none" });
        }
        const code = await utils_notify.wxLoginCode();
        const prefs = await utils_merchantApi.merchantApi.notifyWxBind(code);
        wxBound.value = !!prefs.wxBound;
        enabledTypes.value = [...prefs.enabledAlertTypes || []];
        common_vendor.index.showToast({ title: "已绑定微信提醒", icon: "success" });
      } catch (e) {
        common_vendor.index.showToast({
          title: e instanceof Error ? e.message : "绑定失败",
          icon: "none"
        });
      } finally {
        notifyBusy.value = false;
      }
    }
    async function onSaveSubscribe() {
      notifyBusy.value = true;
      try {
        if (subscribeReady) {
          const sub = await utils_notify.requestMerchantSubscribe();
          if (sub === "failed") {
            common_vendor.index.showToast({ title: "微信授权未完成，偏好仍会保存", icon: "none" });
          }
        }
        const prefs = await utils_merchantApi.merchantApi.notifySubscribe(enabledTypes.value);
        enabledTypes.value = [...prefs.enabledAlertTypes || []];
        common_vendor.index.showToast({
          title: subscribeReady ? "提醒偏好已保存" : "偏好已保存（未配置推送模板）",
          icon: "success"
        });
      } catch (e) {
        common_vendor.index.showToast({
          title: e instanceof Error ? e.message : "保存失败",
          icon: "none"
        });
      } finally {
        notifyBusy.value = false;
      }
    }
    function onLogout() {
      common_vendor.index.showModal({
        title: "退出登录",
        content: "确定退出当前账户吗？",
        confirmText: "退出",
        success(res) {
          if (!res.confirm) return;
          utils_merchantApi.clearSession();
          common_vendor.index.reLaunch({ url: "/pages/login/login" });
        }
      });
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: common_vendor.t(avatarText.value),
        b: common_vendor.t(meName.value),
        c: common_vendor.t(merchantNames.value),
        d: phone.value
      }, phone.value ? {
        e: common_vendor.t(phone.value)
      } : {}, {
        f: fieldNav.value.length
      }, fieldNav.value.length ? {} : {}, {
        g: fieldNav.value.length
      }, fieldNav.value.length ? {
        h: common_vendor.f(fieldNav.value, (item, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(item.icon),
            b: common_vendor.t(item.title),
            c: item.desc
          }, item.desc ? {
            d: common_vendor.t(item.desc)
          } : {}, {
            e: item.key,
            f: item.key === "replenishment" ? 1 : "",
            g: common_vendor.o(($event) => goNav(item), item.key)
          });
        })
      } : {}, {
        i: teamNav.value.length
      }, teamNav.value.length ? {} : {}, {
        j: teamNav.value.length
      }, teamNav.value.length ? {
        k: common_vendor.f(teamNav.value, (item, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(item.icon),
            b: common_vendor.t(item.title),
            c: item.desc
          }, item.desc ? {
            d: common_vendor.t(item.desc)
          } : {}, {
            e: item.key,
            f: common_vendor.o(($event) => goNav(item), item.key)
          });
        })
      } : {}, {
        l: common_vendor.o(goAnnouncements),
        m: canAlerts.value
      }, canAlerts.value ? {} : {}, {
        n: canAlerts.value
      }, canAlerts.value ? common_vendor.e({
        o: common_vendor.t(notifyDesc.value),
        p: common_vendor.t(wxBound.value ? "重新绑定" : "开启提醒"),
        q: notifyBusy.value,
        r: !common_vendor.unref(subscribeReady),
        s: common_vendor.o(onBindWx),
        t: !common_vendor.unref(subscribeReady)
      }, !common_vendor.unref(subscribeReady) ? {} : {}, {
        v: common_vendor.f(common_vendor.unref(alertTypeOptions), (t, k0, i0) => {
          return {
            a: enabledTypes.value.includes(t.value),
            b: common_vendor.o((e) => onToggleType(t.value, !!e.detail.value), t.value),
            c: common_vendor.t(t.label),
            d: t.value
          };
        }),
        w: notifyBusy.value,
        x: common_vendor.o(onSaveSubscribe)
      }) : {}, {
        y: bizNav.value.length
      }, bizNav.value.length ? {} : {}, {
        z: bizNav.value.length
      }, bizNav.value.length ? {
        A: common_vendor.f(bizNav.value, (item, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t(item.icon),
            b: common_vendor.t(item.title),
            c: item.desc
          }, item.desc ? {
            d: common_vendor.t(item.desc)
          } : {}, {
            e: item.key,
            f: common_vendor.o(($event) => goNav(item), item.key)
          });
        })
      } : {}, {
        B: common_vendor.o(onLogout)
      });
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-d41d38da"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/mine/mine.vue"]]);
wx.createPage(MiniProgramPage);
