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
  return s || fallback;
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
 * 比率展示为百分数。
 * - 默认 `unit:'ratio'`：按 0~1 比例 ×100（后端 KPI 等）
 * - `unit:'percent'`：入参已是百分数，不再乘
 * 勿依赖「n<=1 猜比率」；调用方必须显式约定单位。
 */
export function rateText(
  v: unknown,
  opts?: {
    unit?: 'ratio' | 'percent';
    /** @deprecated 请用 unit:'percent'；false 表示入参已是百分数 */
    asPercent?: boolean;
    digits?: number;
    empty?: string;
    zeroLabel?: string;
  }
): string {
  if (v == null || v === '') return opts?.empty ?? EMPTY_STAT;
  const n = Number(v);
  if (!Number.isFinite(n)) return opts?.empty ?? EMPTY_STAT;
  if (n === 0 && opts?.zeroLabel) return opts.zeroLabel;
  const unit = opts?.unit ?? (opts?.asPercent === false ? 'percent' : 'ratio');
  const pct = unit === 'percent' ? n : n * 100;
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

/** 金额分 → 元（整数拆分，避免 cents/100 浮点除法展示漂移） */
export function yuanText(cents: unknown, empty = EMPTY_STAT): string {
  if (cents == null || cents === '') return empty;
  const n = Number(cents);
  if (!Number.isFinite(n)) return empty;
  const sign = n < 0 ? '-' : '';
  const abs = Math.trunc(Math.abs(n));
  const whole = Math.floor(abs / 100);
  const frac = String(abs % 100).padStart(2, '0');
  return `${sign}¥${whole}.${frac}`;
}

/**
 * 金额分 → 元，空/非法值显示 ¥0.00。
 * 列表列空数据语义=「明确为 0 元」而非「未统计」；A6 整改：收敛 14 处自建 yuan()（审计批次 6）。
 */
export function yuanTextZero(cents: unknown): string {
  return yuanText(cents, '¥0.00');
}

/** 金额分 → 元，空/非法值显示「暂无」（大屏/报表类空态文案）。 */
export function yuanTextNone(cents: unknown): string {
  return yuanText(cents, '暂无');
}

/**
 * 万分比 bps → 百分比文本（60 ⇒ `0.60%`）。
 * 存在理由：bps 是后端契约单位，运营语言只有 %；此前 5 个页面各写
 * `(x / 100).toFixed(1|2)`，小数位不一致，读起来像两个口径。
 */
export function bpsText(v: unknown, digits = 2, empty = EMPTY_STAT): string {
  if (v == null || v === '') return empty;
  const n = Number(v);
  if (!Number.isFinite(n)) return empty;
  return `${(n / 100).toFixed(digits)}%`;
}

/**
 * 元（表单输入）→ 分。经 toFixed(2) 再拆整/小位，避免 `0.29*100` 浮点误差。
 * @returns 非法输入返回 null
 */
export function yuanToCents(yuan: unknown): number | null {
  if (yuan == null || yuan === '') return null;
  const n = Number(yuan);
  if (!Number.isFinite(n)) return null;
  const sign = n < 0 ? -1 : 1;
  const [whole, frac = '00'] = Math.abs(n).toFixed(2).split('.');
  return sign * (Number(whole) * 100 + Number(frac));
}
