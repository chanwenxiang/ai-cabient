/** 图表数值格式化工具（渲染交给 ECharts，见 utils/echarts.ts）。 */

import { yuanText } from '@/utils/display';

export type ChartKind = 'line' | 'area' | 'bar';

/** 图表轴标签用；展示路径与 yuanText 一致，不参与资金计算 */
export function formatYuan(cents: number): string {
  return yuanText(cents, '¥0.00');
}

export function formatPct(rate: number): string {
  return (Number(rate) * 100).toFixed(1) + '%';
}

export function shortDate(iso: string): string {
  if (!iso) return '';
  const parts = iso.split('-');
  return parts.length >= 3 ? `${parts[1]}-${parts[2]}` : iso;
}
