/**
 * 统一解析列表接口：兼容后端返回数组或 PageResult({ items, total })。
 * 避免再出现「接口有数据、页面读 items 变空表」的问题。
 */

export type ListPageLike<T> =
  T[] | { items?: T[] | null; total?: number | null } | null | undefined;

export type NormalizedListPage<T> = {
  items: T[];
  total: number;
};

/**
 * @param data API data（已解包 ApiResponse）
 * @returns { items, total }
 */
export function normalizeListPage<T>(data: ListPageLike<T>): NormalizedListPage<T> {
  if (Array.isArray(data)) {
    return { items: data, total: data.length };
  }
  if (data && typeof data === 'object') {
    const items = Array.isArray(data.items) ? data.items : [];
    const total = Number(data.total);
    return {
      items,
      total: Number.isFinite(total) ? total : items.length
    };
  }
  return { items: [], total: 0 };
}
