import { ElMessage } from 'element-plus';
import { isLoggingOut } from '@/api/client';
import { isSessionAuthError } from '@/utils/error-message';

type MessageArgs = Parameters<typeof ElMessage.error>;

function shouldSuppressAuthToast(args: MessageArgs): boolean {
  if (!isLoggingOut()) return false;
  const first = args[0];
  if (typeof first === 'string') return isSessionAuthError(first);
  if (first && typeof first === 'object' && 'message' in first) {
    return isSessionAuthError((first as { message?: unknown }).message);
  }
  return false;
}

/**
 * 退出登录瞬间在途请求会 401（「请先登录」）；主动退出时吞掉这类 Toast，避免误报。
 */
export function installLogoutMessageGuard() {
  const rawError = ElMessage.error.bind(ElMessage);
  ElMessage.error = ((...args: MessageArgs) => {
    if (shouldSuppressAuthToast(args))
      return { close: () => undefined } as ReturnType<typeof ElMessage.error>;
    return rawError(...args);
  }) as typeof ElMessage.error;
}
