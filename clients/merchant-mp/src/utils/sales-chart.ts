/**
 * 商户经营分析「构成图」的指标计算与 ECharts option 构建（纯函数，便于单测）。
 *
 * 演进（O5）：构成图最早是组件里手写的 CSS 百分比条（`salesBarWidth` + 宽度内联样式），
 * 2026-09-21 起改用 **ECharts** 渲染（`uni-echarts` 组件）。为免把「取哪个字段、怎么
 * 格式化、怎么组织 option」散进组件，这里统一收敛成纯函数，组件只负责挂载图表。
 *
 * ⚠️ 缺字段一律按 0（生成类型里所有字段可选），避免 NaN 进入坐标轴与展示。
 * ⚠️ 金额字段单位是**分**（`revenueCents` / `marginCents`），展示前由 `formatSalesMetric` 换算。
 */
import type { EChartsOption } from 'echarts';
import { fmtMoney } from '@aicabinet/shared-uni/format';

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

/** 构成图最多展示的维度数（与前手写版 `slice(0, 8)` 一致）。 */
export const SALES_CHART_MAX_ROWS = 8;

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

/** 展示文案：金额类走 ¥（分→元），计数类带单位。 */
export function formatSalesMetric(value: number, metric: SalesChartMetric): string {
  const v = num(value);
  if (metric === 'qty') return `${v} 件`;
  if (metric === 'orders') return `${v} 单`;
  return fmtMoney(v);
}

/**
 * 构建构成图的 ECharts option（横向条形，按当前指标归一化）。
 *
 * 取值/格式化全部复用上面的纯函数，因此单测能覆盖「渲染成什么样」而不必挂载组件。
 * 视觉与接入前保持一致：维度名在左、值在条末、条色与页面主色一致。
 */
export function buildSalesChartOption(
  rows: SalesChartRow[],
  metric: SalesChartMetric,
  limit: number = SALES_CHART_MAX_ROWS
): EChartsOption {
  const picked = (rows || []).slice(0, Math.max(1, limit));
  const labels = picked.map((r) => r.dimLabel || r.dimKey || '');
  const values = picked.map((r) => salesMetricValue(r, metric));
  const formatValue = (value: unknown) => formatSalesMetric(Number(value), metric);

  return {
    animation: false,
    grid: { left: 0, right: 0, top: 8, bottom: 0, containLabel: true },
    tooltip: {
      trigger: 'item',
      formatter: (p: unknown) => {
        const params = p as { name?: string; value?: unknown };
        return `${params.name ?? ''}<br/>${formatValue(params.value)}`;
      },
      textStyle: {
        // 微信小程序上 tooltip 文字有阴影，官方建议置 1 规避。
        textShadowBlur: 1
      }
    },
    xAxis: { type: 'value', show: false },
    yAxis: {
      type: 'category',
      inverse: true,
      data: labels,
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: {
        fontSize: 11,
        color: '#6b7280',
        width: 70,
        overflow: 'truncate'
      }
    },
    series: [
      {
        type: 'bar',
        data: values,
        barMaxWidth: 14,
        itemStyle: { color: '#2563eb', borderRadius: [0, 4, 4, 0] },
        label: {
          show: true,
          position: 'right',
          fontSize: 11,
          color: '#6b7280',
          formatter: (p: unknown) => formatValue((p as { value?: unknown }).value)
        },
        // 0 值维度不留空轨，避免看起来像「漏了数据」。
        silent: false
      }
    ]
  };
}
