import { showError } from '@/utils/notify';
import { isMpAuthFailure } from '@aicabinet/shared-uni/request';

/**
 * 可选依赖软失败：回退值 + 对用户可见 toast（debt-tracker M1）。
 * 禁止无 label 的静默 `.catch(() => [])` —— 商户会当成「暂无数据」。
 * 会话失效类错误不 toast（401 已由 request 层清会话）。
 * 接口层审计（2026-10-20）H4：轮询路径传 opts.silent——auto-refresh 契约要求静默重入。
 */
export function softFallback<T>(
  promise: Promise<T>,
  fallback: T,
  label: string,
  opts?: { silent?: boolean }
): Promise<T> {
  return promise.catch((e) => {
    if (!isMpAuthFailure(e) && !opts?.silent) {
      const tip = e instanceof Error && e.message.trim() ? e.message.trim() : `${label}加载失败`;
      showError(tip);
    }
    return fallback;
  });
}
