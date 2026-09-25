/**
 * 商户异常列表分页预算（debt-tracker M4）。
 * 首页摘要只需首屏；待办页可拉更多页。
 */

export const OPEN_EXCEPTIONS_DEFAULT_MAX_PAGES = 3;
/** 首页待办摘要：每状态只拉第 1 页，避免 OPEN+PROCESSING 各串行最多 3 页 */
export const OPEN_EXCEPTIONS_HOME_MAX_PAGES = 1;

export function exceptionPageCount(input: {
  total: number;
  pageSize: number;
  maxPages: number;
}): number {
  const size = Math.max(1, input.pageSize);
  const maxPages = Math.max(1, input.maxPages);
  if (!Number.isFinite(input.total) || input.total <= 0) return 1;
  return Math.min(Math.ceil(input.total / size), maxPages);
}

export function mergeExceptionRowsById<T extends { exceptionId?: string | null }>(
  ...lists: T[][]
): T[] {
  const byId = new Map<string, T>();
  for (const list of lists) {
    for (const row of list) {
      const id = row?.exceptionId;
      if (id) byId.set(id, row);
    }
  }
  return [...byId.values()];
}
