"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_merchantApi = require("../../utils/merchant-api.js");
const composables_useMerchantMe = require("../../composables/useMerchantMe.js");
if (!Array) {
  const _easycom_empty_state2 = common_vendor.resolveComponent("empty-state");
  _easycom_empty_state2();
}
const _easycom_empty_state = () => "../../components/empty-state.js";
if (!Math) {
  _easycom_empty_state();
}
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "team",
  setup(__props) {
    const { me, refresh: refreshMe } = composables_useMerchantMe.useMerchantMe();
    const canInvite = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:users:invite"));
    const canEdit = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:users:edit"));
    const canDisable = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:users:disable"));
    const canReset = common_vendor.computed(() => utils_merchantApi.hasPerm(me.value, "merchant:users:reset-password"));
    const canManage = common_vendor.computed(() => canEdit.value || canDisable.value || canReset.value);
    const loading = common_vendor.ref(true);
    const saving = common_vendor.ref(false);
    const error = common_vendor.ref("");
    const list = common_vendor.ref([]);
    const roles = common_vendor.ref([
      { roleKey: "merchant", roleName: "商户管理员" },
      { roleKey: "merchant_store_manager", roleName: "店长" },
      { roleKey: "merchant_finance", roleName: "财务" },
      { roleKey: "merchant_replenisher", roleName: "补货员" },
      { roleKey: "merchant_staff", roleName: "店员" }
    ]);
    const inviteVisible = common_vendor.ref(false);
    const manageVisible = common_vendor.ref(false);
    const manageUser = common_vendor.ref(null);
    const manageRoleKey = common_vendor.ref("merchant_staff");
    const resetPassword = common_vendor.ref("");
    const form = common_vendor.reactive({
      phoneNumber: "",
      password: "",
      displayName: "",
      roleKey: "merchant_staff"
    });
    common_vendor.onShow(() => {
      if (!common_vendor.index.getStorageSync("merchant_token")) {
        common_vendor.index.reLaunch({ url: "/pages/login/login" });
        return;
      }
      void load();
    });
    common_vendor.onPullDownRefresh(async () => {
      try {
        await load();
      } finally {
        common_vendor.index.stopPullDownRefresh();
      }
    });
    function eventInput(e) {
      var _a;
      return String(((_a = e == null ? void 0 : e.detail) == null ? void 0 : _a.value) ?? "");
    }
    function roleLabel(roleKey) {
      const hit = roles.value.find((r) => r.roleKey === roleKey);
      if (hit) return hit.roleName;
      if (roleKey === "merchant_admin" || roleKey === "merchant") return "商户管理员";
      if (roleKey === "merchant_staff") return "店员";
      return roleKey || "成员";
    }
    function openInvite() {
      form.phoneNumber = "";
      form.password = "";
      form.displayName = "";
      form.roleKey = "merchant_staff";
      inviteVisible.value = true;
    }
    function openManage(u) {
      if (u.self || !canManage.value) return;
      manageUser.value = u;
      manageRoleKey.value = u.roleKey || "merchant_staff";
      resetPassword.value = "";
      manageVisible.value = true;
    }
    async function load() {
      loading.value = true;
      error.value = "";
      try {
        await refreshMe();
        if (!utils_merchantApi.hasPerm(me.value, "merchant:users:list")) {
          error.value = "无团队成员权限";
          list.value = [];
          return;
        }
        list.value = await utils_merchantApi.merchantApi.teamUsers() || [];
        if (canInvite.value || canEdit.value) {
          try {
            const rs = await utils_merchantApi.merchantApi.teamRoles();
            if (rs == null ? void 0 : rs.length) roles.value = rs;
          } catch {
          }
        }
      } catch (e) {
        if (!common_vendor.index.getStorageSync("merchant_token")) return;
        me.value = common_vendor.index.getStorageSync("merchant_me") || null;
        list.value = [];
        error.value = (e == null ? void 0 : e.message) || "加载失败";
      } finally {
        loading.value = false;
      }
    }
    async function onInvite() {
      const phone = form.phoneNumber.trim();
      const password = form.password.trim();
      if (!/^1\d{10}$/.test(phone)) {
        common_vendor.index.showToast({ title: "请输入正确手机号", icon: "none" });
        return;
      }
      if (password.length < 6) {
        common_vendor.index.showToast({ title: "密码至少 6 位", icon: "none" });
        return;
      }
      saving.value = true;
      try {
        await utils_merchantApi.merchantApi.createTeamUser({
          phoneNumber: phone,
          password,
          displayName: form.displayName.trim() || void 0,
          roleKey: form.roleKey
        });
        common_vendor.index.showToast({ title: "已邀请", icon: "success" });
        inviteVisible.value = false;
        await load();
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "邀请失败", icon: "none" });
      } finally {
        saving.value = false;
      }
    }
    async function onSaveRole() {
      if (!manageUser.value) return;
      saving.value = true;
      try {
        await utils_merchantApi.merchantApi.updateTeamUser(manageUser.value.userId, { roleKey: manageRoleKey.value });
        common_vendor.index.showToast({ title: "已更新角色", icon: "success" });
        manageVisible.value = false;
        await load();
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "更新失败", icon: "none" });
      } finally {
        saving.value = false;
      }
    }
    async function onResetPassword() {
      if (!manageUser.value) return;
      const pwd = resetPassword.value.trim();
      if (pwd.length < 6) {
        common_vendor.index.showToast({ title: "密码至少 6 位", icon: "none" });
        return;
      }
      saving.value = true;
      try {
        await utils_merchantApi.merchantApi.resetTeamUserPassword(manageUser.value.userId, pwd);
        common_vendor.index.showToast({ title: "密码已重置", icon: "success" });
        resetPassword.value = "";
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "重置失败", icon: "none" });
      } finally {
        saving.value = false;
      }
    }
    async function onDisable() {
      if (!manageUser.value) return;
      saving.value = true;
      try {
        await utils_merchantApi.merchantApi.disableTeamUser(manageUser.value.userId);
        common_vendor.index.showToast({ title: "已停用", icon: "success" });
        manageVisible.value = false;
        await load();
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "停用失败", icon: "none" });
      } finally {
        saving.value = false;
      }
    }
    async function onEnable() {
      if (!manageUser.value) return;
      saving.value = true;
      try {
        await utils_merchantApi.merchantApi.enableTeamUser(manageUser.value.userId);
        common_vendor.index.showToast({ title: "已启用", icon: "success" });
        manageVisible.value = false;
        await load();
      } catch (e) {
        common_vendor.index.showToast({ title: (e == null ? void 0 : e.message) || "启用失败", icon: "none" });
      } finally {
        saving.value = false;
      }
    }
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: canInvite.value
      }, canInvite.value ? {
        b: saving.value,
        c: common_vendor.o(openInvite)
      } : {}, {
        d: loading.value
      }, loading.value ? {} : error.value ? {
        f: common_vendor.t(error.value),
        g: common_vendor.o(load)
      } : !list.value.length ? common_vendor.e({
        i: canInvite.value
      }, canInvite.value ? {
        j: common_vendor.o(openInvite)
      } : {}, {
        k: common_vendor.p({
          icon: "👥",
          title: "暂无团队成员",
          hint: "可邀请同事登录商户端协同补货与经营"
        })
      }) : {
        l: common_vendor.f(list.value, (u, k0, i0) => {
          return common_vendor.e({
            a: common_vendor.t((u.displayName || u.phoneNumber || "员").slice(0, 1)),
            b: common_vendor.t(u.displayName || u.phoneNumber || "用户 " + u.userId),
            c: common_vendor.t(u.phoneNumber || "无手机号"),
            d: common_vendor.t(u.roleName || roleLabel(u.roleKey)),
            e: u.status === "INACTIVE"
          }, u.status === "INACTIVE" ? {} : {}, {
            f: u.self
          }, u.self ? {} : canManage.value ? {} : {}, {
            g: u.userId,
            h: common_vendor.o(($event) => openManage(u), u.userId)
          });
        }),
        m: canManage.value
      }, {
        e: error.value,
        h: !list.value.length,
        n: inviteVisible.value
      }, inviteVisible.value ? {
        o: form.phoneNumber,
        p: common_vendor.o(($event) => form.phoneNumber = eventInput($event)),
        q: form.password,
        r: common_vendor.o(($event) => form.password = eventInput($event)),
        s: form.displayName,
        t: common_vendor.o(($event) => form.displayName = eventInput($event)),
        v: common_vendor.f(roles.value, (r, k0, i0) => {
          return {
            a: common_vendor.t(r.roleName),
            b: r.roleKey,
            c: form.roleKey === r.roleKey ? 1 : "",
            d: common_vendor.o(($event) => form.roleKey = r.roleKey, r.roleKey)
          };
        }),
        w: common_vendor.o(($event) => inviteVisible.value = false),
        x: saving.value,
        y: common_vendor.o(onInvite),
        z: common_vendor.o(() => {
        }),
        A: common_vendor.o(($event) => inviteVisible.value = false)
      } : {}, {
        B: manageVisible.value && manageUser.value
      }, manageVisible.value && manageUser.value ? common_vendor.e({
        C: common_vendor.t(manageUser.value.displayName || manageUser.value.phoneNumber),
        D: common_vendor.t(manageUser.value.phoneNumber),
        E: common_vendor.t(manageUser.value.roleName || roleLabel(manageUser.value.roleKey)),
        F: canEdit.value
      }, canEdit.value ? {
        G: common_vendor.f(roles.value, (r, k0, i0) => {
          return {
            a: common_vendor.t(r.roleName),
            b: "m-" + r.roleKey,
            c: manageRoleKey.value === r.roleKey ? 1 : "",
            d: common_vendor.o(($event) => manageRoleKey.value = r.roleKey, "m-" + r.roleKey)
          };
        }),
        H: saving.value,
        I: common_vendor.o(onSaveRole)
      } : {}, {
        J: canReset.value
      }, canReset.value ? {
        K: resetPassword.value,
        L: common_vendor.o(($event) => resetPassword.value = eventInput($event)),
        M: saving.value,
        N: common_vendor.o(onResetPassword)
      } : {}, {
        O: canDisable.value && !manageUser.value.self
      }, canDisable.value && !manageUser.value.self ? common_vendor.e({
        P: manageUser.value.status !== "INACTIVE"
      }, manageUser.value.status !== "INACTIVE" ? {
        Q: saving.value,
        R: common_vendor.o(onDisable)
      } : {
        S: saving.value,
        T: common_vendor.o(onEnable)
      }) : {}, {
        U: common_vendor.o(($event) => manageVisible.value = false),
        V: common_vendor.o(() => {
        }),
        W: common_vendor.o(($event) => manageVisible.value = false)
      }) : {});
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-4cba8a8b"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/pages/team/team.vue"]]);
wx.createPage(MiniProgramPage);
