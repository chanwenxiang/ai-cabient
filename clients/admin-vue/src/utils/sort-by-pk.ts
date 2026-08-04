export type SortDirection = 'asc' | 'desc';

export function comparePrimaryKey(a: unknown, b: unknown): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  const sa = String(a).trim();
  const sb = String(b).trim();
  const na = Number(sa);
  const nb = Number(sb);
  if (sa !== '' && sb !== '' && Number.isFinite(na) && Number.isFinite(nb) && /^-?\d+(\.\d+)?$/.test(sa) && /^-?\d+(\.\d+)?$/.test(sb)) {
    return na === nb ? 0 : na < nb ? -1 : 1;
  }
  return sa.localeCompare(sb, 'zh-CN', { numeric: true, sensitivity: 'base' });
}

export function sortByPrimaryKey<T>(
  rows: T[],
  key: keyof T | ((row: T) => unknown),
  direction: SortDirection = 'asc'
): T[] {
  const get = typeof key === 'function' ? key : (row: T) => row[key];
  const sign = direction === 'desc' ? -1 : 1;
  return [...rows].sort((x, y) => sign * comparePrimaryKey(get(x), get(y)));
}
