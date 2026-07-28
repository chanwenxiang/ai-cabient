import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { api, applyLoginSession, isLoggedIn, isSessionSoftExpired, clearSession } from '@/api/client';
import { loadRuntimeDict, resetRuntimeDict } from '@/stores/dict-runtime';

const PERM_KEY = 'admin_permissions';
const NAV_KEY = 'admin_active_nav';

function readCachedPermissions(): string[] {
  try {
    const raw = localStorage.getItem(PERM_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((p): p is string => typeof p === 'string') : [];
  } catch {
    localStorage.removeItem(PERM_KEY);
    return [];
  }
}

function readCachedActiveNav(): string[] {
  try {
    const raw = localStorage.getItem(NAV_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((p): p is string => typeof p === 'string') : [];
  } catch {
    localStorage.removeItem(NAV_KEY);
    return [];
  }
}

export interface OpsProfile {
  userId: string;
  phoneNumber?: string;
  name?: string;
  roleNames?: string[];
  permissionCount?: number;
  globalDataScope?: boolean;
  merchantIds?: string[];
  merchantNames?: string[];
}

export const useAuthStore = defineStore('auth', () => {
  const userId = ref(localStorage.getItem('admin_userId') || '');
  const permissions = ref<string[]>(readCachedPermissions());
  /** ACTIVE 菜单/目录权限码（系统级，停用后对所有人含超管隐藏导航） */
  const activeNavPerms = ref<string[]>(readCachedActiveNav());
  const activeNavLoaded = ref(readCachedActiveNav().length > 0);
  const phone = ref(localStorage.getItem('admin_phone') || '');
  const profile = ref<OpsProfile | null>(null);

  const displayName = computed(() => profile.value?.name || '运营账号');
  const roleText = computed(() => (profile.value?.roleNames || []).join('、') || '未分配角色');
  const dataScopeText = computed(() => {
    const p = profile.value;
    if (!p) return '数据范围未知';
    if (p.globalDataScope !== false) return '全局数据范围';
    const names = (p.merchantNames || []).filter(Boolean);
    if (names.length) return `商户范围：${names.join('、')}`;
    const ids = p.merchantIds || [];
    return ids.length ? `商户范围：${ids.join('、')}` : '已限定（无商户）';
  });

  async function login(phoneNumber: string, password: string) {
    const data = await api.loginByPassword(phoneNumber, password);
    applyLoginSession(data);
    userId.value = data.userId;
    phone.value = phoneNumber;
    localStorage.setItem('admin_phone', phoneNumber);
    await Promise.all([loadPermissions(), loadActiveNav(), loadProfile(), loadRuntimeDict()]);
  }

  async function loadPermissions() {
    try {
      const perms = await api.request<string[]>('/api/v2/ops/admin/rbac/me/permissions', 'GET');
      permissions.value = perms || [];
      localStorage.setItem(PERM_KEY, JSON.stringify(permissions.value));
    } catch (e) {
      // 401 already clears session via ApiClient; keep token only for soft failures
      const msg = e instanceof Error ? e.message : '';
      if (/401|登录|未授权|失效/i.test(msg) || !isLoggedIn()) {
        permissions.value = [];
        localStorage.setItem(PERM_KEY, '[]');
        return;
      }
      // Keep last-known perms from localStorage if soft-fail (network blip)
      permissions.value = readCachedPermissions();
    }
  }

  async function loadActiveNav() {
    try {
      const codes = await api.request<string[]>('/api/v2/ops/admin/rbac/me/nav', 'GET');
      activeNavPerms.value = codes || [];
      activeNavLoaded.value = true;
      localStorage.setItem(NAV_KEY, JSON.stringify(activeNavPerms.value));
    } catch (e) {
      const msg = e instanceof Error ? e.message : '';
      if (/401|登录|未授权|失效/i.test(msg) || !isLoggedIn()) {
        activeNavPerms.value = [];
        activeNavLoaded.value = false;
        localStorage.setItem(NAV_KEY, '[]');
        return;
      }
      // Soft-fail: keep cache; if never loaded, don't block all nav
      const cached = readCachedActiveNav();
      if (cached.length) {
        activeNavPerms.value = cached;
        activeNavLoaded.value = true;
      }
    }
  }

  /** Re-fetch permissions (+ profile + active menus) after role/menu edits. */
  async function refreshPermissions() {
    if (!isLoggedIn()) return false;
    await Promise.all([loadPermissions(), loadActiveNav(), loadProfile()]);
    return true;
  }

  async function loadProfile() {
    try {
      const me = await api.request<{
        userId: number | string;
        phoneNumber?: string;
        name?: string;
        roleNames?: string[];
        permissionCount?: number;
        globalDataScope?: boolean;
        merchantIds?: string[];
        merchantNames?: string[];
      }>('/api/v2/ops/admin/rbac/me', 'GET');
      profile.value = {
        userId: String(me.userId),
        phoneNumber: me.phoneNumber,
        name: me.name,
        roleNames: me.roleNames,
        permissionCount: me.permissionCount,
        globalDataScope: me.globalDataScope !== false,
        merchantIds: me.merchantIds || [],
        merchantNames: me.merchantNames || []
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
    activeNavPerms.value = [];
    activeNavLoaded.value = false;
    phone.value = '';
    profile.value = null;
    localStorage.removeItem(NAV_KEY);
  }

  function hasPerm(code?: string | null) {
    if (!code) return true;
    const perms = permissions.value || [];
    if (perms.includes('ops:admin')) return true;
    if (perms.includes(code)) return true;
    // 若依风格通配：system:user:* 覆盖 system:user:list
    const segments = code.split(':');
    for (let i = segments.length - 1; i >= 1; i--) {
      const wildcard = `${segments.slice(0, i).join(':')}:*`;
      if (perms.includes(wildcard)) return true;
    }
    return false;
  }

  /** 菜单是否在系统中启用（ACTIVE）。超管也不能绕过停用菜单。 */
  function isNavMenuActive(perm?: string | null) {
    if (!perm) return true;
    if (!activeNavLoaded.value) return true;
    return activeNavPerms.value.includes(perm);
  }

  function canAccessNav(item: { perm?: string } | null | undefined) {
    if (!item) return false;
    if (!hasPerm(item.perm)) return false;
    return isNavMenuActive(item.perm);
  }

  async function restore() {
    if (!isLoggedIn()) return false;
    if (isSessionSoftExpired()) {
      const ok = await api.refreshSilently();
      if (!ok) {
        logout();
        return false;
      }
    }
    await Promise.all([loadPermissions(), loadActiveNav(), loadProfile(), loadRuntimeDict()]);
    return true;
  }

  return {
    userId,
    permissions,
    activeNavPerms,
    activeNavLoaded,
    phone,
    profile,
    displayName,
    roleText,
    dataScopeText,
    login,
    logout,
    loadPermissions,
    loadActiveNav,
    loadProfile,
    refreshPermissions,
    hasPerm,
    isNavMenuActive,
    canAccessNav,
    restore
  };
});
