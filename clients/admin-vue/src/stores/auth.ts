import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { api, applyLoginSession, isLoggedIn, clearSession } from '@/api/client';
import { loadRuntimeDict, resetRuntimeDict } from '@/stores/dict-runtime';

const PERM_KEY = 'admin_permissions';

export interface OpsProfile {
  userId: string;
  phoneNumber?: string;
  name?: string;
  roleNames?: string[];
  permissionCount?: number;
}

export const useAuthStore = defineStore('auth', () => {
  const userId = ref(localStorage.getItem('admin_userId') || '');
  const permissions = ref<string[]>(JSON.parse(localStorage.getItem(PERM_KEY) || '[]'));
  const phone = ref(localStorage.getItem('admin_phone') || '');
  const profile = ref<OpsProfile | null>(null);

  const displayName = computed(() => profile.value?.name || '运营账号');
  const roleText = computed(() => (profile.value?.roleNames || []).join('、') || '未分配角色');

  async function login(phoneNumber: string, password: string) {
    const data = await api.loginByPassword(phoneNumber, password);
    applyLoginSession(data);
    userId.value = data.userId;
    phone.value = phoneNumber;
    localStorage.setItem('admin_phone', phoneNumber);
    await Promise.all([loadPermissions(), loadProfile(), loadRuntimeDict()]);
  }

  async function loadPermissions() {
    try {
      const perms = await api.request<string[]>('/api/v2/ops/admin/rbac/me/permissions', 'GET');
      permissions.value = perms || [];
      localStorage.setItem(PERM_KEY, JSON.stringify(permissions.value));
    } catch {
      permissions.value = [];
    }
  }

  async function loadProfile() {
    try {
      const me = await api.request<{
        userId: number | string;
        phoneNumber?: string;
        name?: string;
        roleNames?: string[];
        permissionCount?: number;
      }>('/api/v2/ops/admin/rbac/me', 'GET');
      profile.value = {
        userId: String(me.userId),
        phoneNumber: me.phoneNumber,
        name: me.name,
        roleNames: me.roleNames,
        permissionCount: me.permissionCount
      };
      userId.value = String(me.userId);
      if (me.phoneNumber) {
        phone.value = me.phoneNumber;
        localStorage.setItem('admin_phone', me.phoneNumber);
      }
      localStorage.setItem('admin_userId', String(me.userId));
    } catch {
      profile.value = null;
    }
  }

  function logout() {
    clearSession();
    resetRuntimeDict();
    userId.value = '';
    permissions.value = [];
    phone.value = '';
    profile.value = null;
  }

  function hasPerm(code: string) {
    if (!permissions.value.length) return true;
    return permissions.value.includes(code);
  }

  async function restore() {
    if (!isLoggedIn()) return false;
    await Promise.all([loadPermissions(), loadProfile(), loadRuntimeDict()]);
    return true;
  }

  return {
    userId,
    permissions,
    phone,
    profile,
    displayName,
    roleText,
    login,
    logout,
    loadPermissions,
    loadProfile,
    hasPerm,
    restore
  };
});
