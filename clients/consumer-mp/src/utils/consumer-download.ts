/**
 * 消费者鉴权下载辅助（debt-tracker C12c）。
 * 纯函数供 downloadAuthedFile 使用；禁止页内自拼 Authorization。
 */

export function buildBearerDownloadHeader(token?: string | null): Record<string, string> {
  const header: Record<string, string> = { 'X-Requested-With': 'XMLHttpRequest' };
  if (token) header.Authorization = `Bearer ${token}`;
  return header;
}

/** 2xx 且有临时路径视为成功；401 单独标记。 */
export function classifyDownloadResult(
  statusCode: number,
  hasTempPath: boolean
): {
  ok: boolean;
  unauthorized: boolean;
  message?: string;
} {
  if (statusCode === 401) {
    return { ok: false, unauthorized: true, message: '登录已失效' };
  }
  if (statusCode >= 200 && statusCode < 300 && hasTempPath) {
    return { ok: true, unauthorized: false };
  }
  return { ok: false, unauthorized: false, message: `下载失败 (${statusCode})` };
}
