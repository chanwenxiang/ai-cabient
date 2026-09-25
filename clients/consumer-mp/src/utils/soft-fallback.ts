import { showError } from '@/utils/notify';
import { isMpAuthFailure } from '@aicabinet/shared-uni/request';

/**
 * 可选依赖软失败：回退值 + 可见 toast（debt-tracker C1）。
 * 禁止 `.catch(() => [])` 静默空列表（用户会当成「暂无数据」）。
 * 会话失效类错误不 toast。
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
