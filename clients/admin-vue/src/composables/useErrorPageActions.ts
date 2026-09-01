import { useRouter } from 'vue-router';
import { resolveHomePath } from '@/composables/useNavAccess';
import { useAuthStore } from '@/stores/auth';

/** 错误页：回退到可访问首页，或浏览器上一页（仅当同会话确有后退栈）。 */
export function useErrorPageActions() {
  const router = useRouter();
  const auth = useAuthStore();

  function resolveHome() {
    return resolveHomePath(auth);
  }

  function goHome() {
    router.replace(resolveHome());
  }

  function goBack() {
    // Vue Router 4：history.state.back 存在才表示本会话可后退；history.length 几乎恒 > 1，不可靠
    const back = (window.history.state as { back?: unknown } | null)?.back;
    if (back != null) {
      router.back();
      return;
    }
    goHome();
  }

  return { resolveHome, goHome, goBack };
}
