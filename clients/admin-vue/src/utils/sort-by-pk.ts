export type SortDirection = 'asc' | 'desc';

const INT_RE = /^-?\d+$/;
const DECIMAL_RE = /^-?\d+(\.\d+)?$/;

/** Compare digit IDs safely (snowflake / bigint beyond Number.MAX_SAFE_INTEGER). */
function compareDigitIds(sa: string, sb: string): number {
  try {
    const ba = BigInt(sa);
    const bb = BigInt(sb);
    return ba === bb ? 0 : ba < bb ? -1 : 1;
  } catch {
    // same length lexicographic fallback for pure digits
    if (sa.length !== sb.length) return sa.length < sb.length ? -1 : 1;
    return sa < sb ? -1 : sa > sb ? 1 : 0;
  }
}

export function comparePrimaryKey(a: unknown, b: unknown): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  const sa = String(a).trim();
  const sb = String(b).trim();

  if (sa !== '' && sb !== '' && INT_RE.test(sa) && INT_RE.test(sb)) {
    return compareDigitIds(sa, sb);
  }

  const na = Number(sa);
  const nb = Number(sb);
  if (
    sa !== '' &&
    sb !== '' &&
    Number.isFinite(na) &&
    Number.isFinite(nb) &&
    DECIMAL_RE.test(sa) &&
    DECIMAL_RE.test(sb)
  ) {
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
