/** 管理后台空值展示：不用「- / —」，给出可读中文。 */

export const EMPTY_TEXT = '暂无';
export const EMPTY_STAT = '未统计';
export const EMPTY_LOADING = '…';

/** 可选文案：空则「暂无」 */
export function textOrNone(v: unknown, fallback = EMPTY_TEXT): string {
  if (v == null) return fallback;
  let s = '';
  if (typeof v === 'string') s = v;
  else if (typeof v === 'number' || typeof v === 'boolean' || typeof v === 'bigint') s = String(v);
  else if (typeof v === 'object') {
    try {
      s = JSON.stringify(v);
    } catch {
      return fallback;
    }
  } else {
    return fallback;
  }
  s = s.trim();
  return s ? s : fallback;
}

/** 计数类：null/undefined → 0 */
export function numOrZero(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

/** 展示计数（含 0） */
export function countText(v: unknown): string {
  return String(numOrZero(v));
}

/**
 * 比率（默认按 0~1 转百分数）。
 * null →「未统计」；可用 zeroLabel 覆盖 0。
 */
export function rateText(
  v: unknown,
  opts?: { asPercent?: boolean; digits?: number; empty?: string; zeroLabel?: string }
): string {
  if (v == null || v === '') return opts?.empty ?? EMPTY_STAT;
  const n = Number(v);
  if (!Number.isFinite(n)) return opts?.empty ?? EMPTY_STAT;
  if (n === 0 && opts?.zeroLabel) return opts.zeroLabel;
  let pct: number;
  if (opts?.asPercent === false) {
    pct = n;
  } else {
    pct = n <= 1 ? n * 100 : n;
  }
  const d = opts?.digits ?? 1;
  return `${pct.toFixed(d)}%`;
}

/** 时长（小时） */
export function hoursText(v: unknown, empty = EMPTY_STAT): string {
  if (v == null || v === '') return empty;
  const n = Number(v);
  if (!Number.isFinite(n)) return empty;
  return `${n.toFixed(2)} 小时`;
}

/** 毫秒 */
export function msText(v: unknown, empty = EMPTY_STAT): string {
  if (v == null || v === '') return empty;
  const n = Number(v);
  if (!Number.isFinite(n)) return empty;
  if (n < 1000) return `${Math.round(n)} ms`;
  return `${(n / 1000).toFixed(2)} s`;
}

/** 金额分 → 元 */
export function yuanText(cents: unknown, empty = EMPTY_STAT): string {
  if (cents == null || cents === '') return empty;
  const n = Number(cents);
  if (!Number.isFinite(n)) return empty;
  return `¥${(n / 100).toFixed(2)}`;
}

/** 首屏未 hydrate 时占位 */
export function waitOr(ready: boolean, value: string | number, loading = EMPTY_LOADING): string {
  return ready ? String(value) : loading;
}
