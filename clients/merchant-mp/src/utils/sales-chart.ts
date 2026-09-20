/**
 * 商户经营分析「构成图」的指标计算（纯函数，便于单测）。
 *
 * 背景（O5 增强）：构成图此前只用 `revenueCents` 一根柱子，而后端
 * `SalesReportRowDto` 已经同时下发 `orderCount` / `qty` / `marginCents` /
 * `revenueCents` —— 这里把「取哪个字段、怎么归一化、怎么格式化」收敛成一组
 * 纯函数，组件只负责渲染。
 *
 * ⚠️ 缺字段一律按 0（生成类型里所有字段可选），避免 NaN 进入 `width`/展示。
 */

export type SalesChartMetric = 'revenue' | 'margin' | 'qty' | 'orders';

export interface SalesChartMetricOption {
  value: SalesChartMetric;
  label: string;
}

/** 指标切换项；数组顺序即 UI 顺序。 */
export const SALES_CHART_METRICS: SalesChartMetricOption[] = [
  { value: 'revenue', label: '营收' },
  { value: 'margin', label: '毛利' },
  { value: 'qty', label: '销量' },
  { value: 'orders', label: '订单' }
];

/** 只声明本模块真正读取的字段（结构子类型，兼容 OpenApiSalesReportRowDto）。 */
export interface SalesChartRow {
  dimKey?: string;
  dimLabel?: string;
  orderCount?: number | null;
  qty?: number | null;
  revenueCents?: number | null;
  marginCents?: number | null;
}

function num(v?: number | null): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

/** 取某行在某指标下的原始值（缺字段/非法值 ⇒ 0）。 */
export function salesMetricValue(row: SalesChartRow, metric: SalesChartMetric): number {
  switch (metric) {
    case 'margin':
      return num(row.marginCents);
    case 'qty':
      return num(row.qty);
    case 'orders':
      return num(row.orderCount);
    case 'revenue':
    default:
      return num(row.revenueCents);
  }
}

/** 当前指标下各行的最大值（至少 1 ⇒ 不会除零）。空集合恒为 1。 */
export function salesMetricMax(rows: SalesChartRow[], metric: SalesChartMetric): number {
  let max = 0;
  for (const r of rows || []) {
    const v = salesMetricValue(r, metric);
    if (v > max) max = v;
  }
  return Math.max(1, max);
}

/** 条形宽度百分比：最小 2%（保证「有值但极小」可见），最大 100%。 */
export function salesBarWidth(value: number, max: number): string {
  const pct = (num(value) / Math.max(1, num(max))) * 100;
  return `${Math.max(2, Math.round(pct))}%`;
}

/** 条形右侧的数值文案：金额类走 ¥，计数类带单位。 */
export function formatSalesMetric(value: number, metric: SalesChartMetric): string {
  const v = num(value);
  if (metric === 'qty') return `${v} 件`;
  if (metric === 'orders') return `${v} 单`;
  return `¥${(v / 100).toFixed(2)}`;
}
