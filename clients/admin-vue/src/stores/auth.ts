import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { matchPermission } from '@aicabinet/shared-rbac';
import {
  api,
  applyLoginSession,
  isLoggedIn,
  isSessionSoftExpired,
  logoutSession
} from '@/api/client';
import { loadRuntimeDict, resetRuntimeDict } from '@/stores/dict-runtime';

const PERM_KEY = 'admin_permissions';
const NAV_KEY = 'admin_active_nav';
const TWO_FACTOR_KEY = 'admin_2fa_challenge';

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
  email?: string;
  avatarUrl?: string;
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
  /** 首屏 /me 未完成前勿用「未分配角色 / 运营账号」等默认文案占位 */
  const profileHydrated = ref(false);

  const displayName = computed(() => {
    if (profile.value?.name) return profile.value.name;
    return profileHydrated.value ? '运营账号' : '…';
  });
  const email = computed(() => profile.value?.email || '');
  const avatarUrl = computed(() => profile.value?.avatarUrl || '');
  const roleText = computed(() => {
    if (!profile.value) return profileHydrated.value ? '未分配角色' : '…';
    const names = (profile.value.roleNames || []).filter(Boolean);
    return names.length ? names.join('、') : '未分配角色';
  });
  const dataScopeText = computed(() => {
    const p = profile.value;
    if (!p) return profileHydrated.value ? '数据范围未知' : '…';
    if (p.globalDataScope !== false) return '全局数据范围';
    const names = (p.merchantNames || []).filter(Boolean);
    if (names.length) return `商户范围：${names.join('、')}`;
    const ids = p.merchantIds || [];
    return ids.length ? `商户范围：${ids.join('、')}` : '已限定（无商户）';
  });

  async function login(
    phoneNumber: string,
    password: string,
    captcha?: { captchaId: string; captchaCode: string }
  ) {
    const data = await api.loginByPassword(phoneNumber, password, captcha);
    if (data.twoFactorRequired) {
      // 密码已通过：保存短时 challenge，待动态码验证后完成登录
      localStorage.setItem(TWO_FACTOR_KEY, data.token);
      phone.value = phoneNumber;
      return { twoFactorRequired: true };
    }
    applyLoginSession(data);
    userId.value = data.userId;
    phone.value = phoneNumber;
    await Promise.all([loadPermissions(), loadActiveNav(), loadProfile(), loadRuntimeDict()]);
    return { twoFactorRequired: false };
  }

  async function completeTwoFactor(code: string, recovery: boolean) {
    const challenge = localStorage.getItem(TWO_FACTOR_KEY);
    if (!challenge) {
      throw new Error('登录状态已失效，请重新登录');
    }
    const data = recovery
      ? await api.recoveryTwoFactor(challenge, code)
      : await api.verifyTwoFactor(challenge, code);
    localStorage.removeItem(TWO_FACTOR_KEY);
    applyLoginSession(data);
    userId.value = data.userId;
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
        email?: string;
        avatarUrl?: string;
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
        email: me.email || '',
        avatarUrl: me.avatarUrl || '',
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
      // soft fail: keep last good profile to avoid header/Profile flash to defaults
    } finally {
      profileHydrated.value = true;
    }
  }

  async function updateProfile(payload: {
    name: string;
    phoneNumber: string;
    email?: string | null;
    avatarUrl?: string | null;
  }) {
    const me = await api.request<{
      userId: number | string;
      phoneNumber?: string;
      name?: string;
      email?: string;
      avatarUrl?: string;
      roleNames?: string[];
      permissionCount?: number;
      globalDataScope?: boolean;
      merchantIds?: string[];
      merchantNames?: string[];
    }>('/api/v2/ops/admin/rbac/me', 'PUT', {
      name: payload.name,
      phoneNumber: payload.phoneNumber,
      email: payload.email || null,
      avatarUrl: payload.avatarUrl || null
    });
    profile.value = {
      userId: String(me.userId),
      phoneNumber: me.phoneNumber,
      name: me.name,
      email: me.email || '',
      avatarUrl: me.avatarUrl || '',
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
  }

  async function logout() {
    await logoutSession();
    resetRuntimeDict();
    userId.value = '';
    permissions.value = [];
    activeNavPerms.value = [];
    activeNavLoaded.value = false;
    phone.value = '';
    profile.value = null;
    profileHydrated.value = false;
    localStorage.removeItem(NAV_KEY);
  }

  function hasPerm(code?: string | null) {
    return matchPermission(permissions.value, code);
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
    email,
    avatarUrl,
    profile,
    profileHydrated,
    displayName,
    roleText,
    dataScopeText,
    login,
    completeTwoFactor,
    logout,
    loadPermissions,
    loadActiveNav,
    loadProfile,
    updateProfile,
    refreshPermissions,
    restore,
    hasPerm,
    isNavMenuActive,
    canAccessNav
  };
});
