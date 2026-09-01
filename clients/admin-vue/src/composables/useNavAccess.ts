import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { findNavByPath, NAV_ITEMS } from '@/config/menu';
import { useAuthStore } from '@/stores/auth';

type AuthLike = {
  canAccessNav: (item: { perm?: string } | null | undefined) => boolean;
};

/** 按侧栏菜单顺序找首个可访问页（无权限时落到个人中心）。 */
export function resolveHomePath(auth: AuthLike): string {
  const hit = NAV_ITEMS.find((item) => !item.perm || auth.canAccessNav(item));
  return hit?.path || '/profile';
}

/** Shared route-permission helpers for clickable cards / cross-module links. */
export function useNavAccess() {
  const router = useRouter();
  const auth = useAuthStore();

  function canAccessPath(path: string) {
    const nav = findNavByPath(path);
    return !nav?.perm || auth.canAccessNav(nav);
  }

  function firstAccessiblePath(candidates?: string[]) {
    if (candidates?.length) {
      return candidates.find((p) => canAccessPath(p)) || resolveHomePath(auth);
    }
    return resolveHomePath(auth);
  }

  function goPath(path: string, query?: Record<string, string>) {
    if (!canAccessPath(path)) {
      ElMessage.warning('无访问权限');
      return;
    }
    if (query && Object.keys(query).length) {
      router.push({ path, query });
      return;
    }
    router.push(path);
  }

  function goHome() {
    goPath(firstAccessiblePath());
  }

  return { auth, router, canAccessPath, firstAccessiblePath, goPath, goHome };
}
