import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { findNavByPath } from '@/config/menu';
import { useAuthStore } from '@/stores/auth';

/** Shared route-permission helpers for clickable cards / cross-module links. */
export function useNavAccess() {
  const router = useRouter();
  const auth = useAuthStore();

  function canAccessPath(path: string) {
    const nav = findNavByPath(path);
    return !nav?.perm || auth.canAccessNav(nav);
  }

  function firstAccessiblePath(
    candidates: string[] = ['/dashboard', '/devices', '/orders', '/profile']
  ) {
    return candidates.find((p) => canAccessPath(p)) || '/profile';
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
