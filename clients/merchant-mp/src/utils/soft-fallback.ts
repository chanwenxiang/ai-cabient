import { showError } from '@/utils/notify';
import { isMpAuthFailure } from '@aicabinet/shared-uni/request';

/**
 * 可选依赖软失败：回退值 + 对用户可见 toast（debt-tracker M1）。
 * 禁止无 label 的静默 `.catch(() => [])` —— 商户会当成「暂无数据」。
 * 会话失效类错误不 toast（401 已由 request 层清会话）。
 */
export function softFallback<T>(promise: Promise<T>, fallback: T, label: string): Promise<T> {
  return promise.catch((e) => {
    if (!isMpAuthFailure(e)) {
      const tip = e instanceof Error && e.message.trim() ? e.message.trim() : `${label}加载失败`;
      showError(tip);
    }
    return fallback;
  });
}
