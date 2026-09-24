import { ElMessage } from 'element-plus';
import { adminDevWarn } from '@/utils/admin-dev-log';
import { errorMessage, isSessionAuthError } from '@/utils/error-message';

/** 软失败已发生时的可见提示（保留旧数据场景，如筛选项）。 */
export function reportSoftFail(error: unknown, label: string) {
  adminDevWarn(`[softFallback] ${label}`, error);
  if (isSessionAuthError(error)) return;
  ElMessage.warning(`${label}加载失败：${errorMessage(error, '请稍后重试')}`);
}

/**
 * 可选依赖软失败：回退值 + 对用户可见 warning。
 * 禁止 `.catch(() => [])` 静默空列表（运营会当成「暂无数据」），见 lessons #116 / debt-tracker D3。
 * 会话失效类错误不 toast（退出登录在途请求）。
 */
export async function softFallback<T>(
  promise: Promise<T>,
  fallback: T,
  label: string
): Promise<T> {
  try {
    return await promise;
  } catch (e) {
    reportSoftFail(e, label);
    return fallback;
  }
}

/**
 * 多路并行软失败收集器：失败汇总成一条 warning，避免大屏等场景 toast 风暴。
 */
export function createSoftFailCollector() {
  const failedLabels: string[] = [];

  async function soft<T>(promise: Promise<T>, fallback: T, label: string): Promise<T> {
    try {
      return await promise;
    } catch (e) {
      adminDevWarn(`[softFallback] ${label}`, e);
      if (!isSessionAuthError(e)) failedLabels.push(label);
      return fallback;
    }
  }

  function flush(prefix = '部分数据加载失败') {
    if (!failedLabels.length) return;
    const uniq = [...new Set(failedLabels)];
    failedLabels.length = 0;
    ElMessage.warning(`${prefix}：${uniq.join('、')}`);
  }

  return { soft, flush };
}
