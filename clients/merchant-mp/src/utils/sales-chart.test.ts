import { describe, expect, it } from 'vitest';
import {
  SALES_CHART_MAX_ROWS,
  SALES_CHART_METRICS,
  buildSalesChartOption,
  formatSalesMetric,
  salesMetricValue,
  type SalesChartMetric,
  type SalesChartRow
} from './sales-chart';

const row = {
  dimKey: 'SKU-1',
  dimLabel: '可乐',
  revenueCents: 12_300,
  marginCents: 4_500,
  qty: 7,
  orderCount: 3
};

/** 从 option 里取出实用片段（EChartsOption 是联合类型，测试里按下标取需窄化）。 */
function seriesData(option: ReturnType<typeof buildSalesChartOption>): number[] {
  const series = option.series as Array<{ data: number[] }>;
  return series[0].data;
}
function axisLabels(option: ReturnType<typeof buildSalesChartOption>): string[] {
  const axis = option.yAxis as { data: string[] };
  return axis.data;
}
function seriesLabelFormatter(option: ReturnType<typeof buildSalesChartOption>) {
  const series = option.series as Array<{ label: { formatter: (p: unknown) => string } }>;
  return series[0].label.formatter;
}
function tooltipFormatter(option: ReturnType<typeof buildSalesChartOption>) {
  const tooltip = option.tooltip as { formatter: (p: unknown) => string };
  return tooltip.formatter;
}

describe('SALES_CHART_METRICS', () => {
  it('提供 4 个指标且首个为营收（默认口径保持不变）', () => {
    expect(SALES_CHART_METRICS.map((m) => m.value)).toEqual(['revenue', 'margin', 'qty', 'orders']);
    expect(SALES_CHART_METRICS[0].value).toBe('revenue');
  });
});

describe('salesMetricValue', () => {
  it('按指标取对应字段', () => {
    expect(salesMetricValue(row, 'revenue')).toBe(12_300);
    expect(salesMetricValue(row, 'margin')).toBe(4_500);
    expect(salesMetricValue(row, 'qty')).toBe(7);
    expect(salesMetricValue(row, 'orders')).toBe(3);
  });

  it('缺字段 / null / undefined / NaN / 非数字一律按 0（不进 NaN）', () => {
    expect(salesMetricValue({}, 'revenue')).toBe(0);
    expect(salesMetricValue({ marginCents: null }, 'margin')).toBe(0);
    expect(salesMetricValue({ qty: undefined }, 'qty')).toBe(0);
    expect(salesMetricValue({ orderCount: NaN }, 'orders')).toBe(0);
    expect(salesMetricValue({ revenueCents: 'abc' as unknown as number }, 'revenue')).toBe(0);
  });

  it('未知指标回退营收（运行时防御，类型上不会出现）', () => {
    expect(salesMetricValue(row, 'nope' as SalesChartMetric)).toBe(12_300);
  });
});

describe('formatSalesMetric', () => {
  it('金额类走 ¥ 且保留两位', () => {
    expect(formatSalesMetric(12_300, 'revenue')).toBe('¥123.00');
    expect(formatSalesMetric(4_599, 'margin')).toBe('¥45.99');
  });

  it('计数类带单位', () => {
    expect(formatSalesMetric(7, 'qty')).toBe('7 件');
    expect(formatSalesMetric(3, 'orders')).toBe('3 单');
  });

  it('非法值按 0 格式化', () => {
    expect(formatSalesMetric(NaN, 'revenue')).toBe('¥0.00');
    expect(formatSalesMetric(undefined as unknown as number, 'qty')).toBe('0 件');
  });
});

describe('buildSalesChartOption', () => {
  it('维度名与取值按行序一一对应（inverse 使首个维度显示在最上）', () => {
    const rows: SalesChartRow[] = [
      { dimKey: 'A', dimLabel: '甲', revenueCents: 100 },
      { dimKey: 'B', revenueCents: 200 }
    ];
    const option = buildSalesChartOption(rows, 'revenue');
    expect(axisLabels(option)).toEqual(['甲', 'B']);
    expect(seriesData(option)).toEqual([100, 200]);
    expect((option.yAxis as { inverse?: boolean }).inverse).toBe(true);
  });

  it('缺 dimLabel 时回退 dimKey；两者都缺则空串（不产生 undefined 标签）', () => {
    const option = buildSalesChartOption([{ dimKey: 'K' }, {}], 'revenue');
    expect(axisLabels(option)).toEqual(['K', '']);
  });

  it('切换指标只换取值、不动维度顺序', () => {
    const rows: SalesChartRow[] = [{ dimKey: 'A', revenueCents: 100, qty: 9, orderCount: 2 }];
    expect(seriesData(buildSalesChartOption(rows, 'revenue'))).toEqual([100]);
    expect(seriesData(buildSalesChartOption(rows, 'qty'))).toEqual([9]);
    expect(seriesData(buildSalesChartOption(rows, 'orders'))).toEqual([2]);
    expect(axisLabels(buildSalesChartOption(rows, 'qty'))).toEqual(['A']);
  });

  it(`最多只取前 ${SALES_CHART_MAX_ROWS} 条（与手写版 slice(0, 8) 一致）`, () => {
    const rows: SalesChartRow[] = Array.from({ length: SALES_CHART_MAX_ROWS + 5 }, (_, i) => ({
      dimKey: `S${i}`,
      revenueCents: i
    }));
    expect(seriesData(buildSalesChartOption(rows, 'revenue'))).toHaveLength(SALES_CHART_MAX_ROWS);
    expect(seriesData(buildSalesChartOption(rows, 'revenue', 3))).toEqual([0, 1, 2]);
  });

  it('缺字段的行按 0 出条，不产生 NaN', () => {
    expect(seriesData(buildSalesChartOption([{}, { revenueCents: null }], 'revenue'))).toEqual([
      0, 0
    ]);
  });

  it('空集合/非法集合不抛异常', () => {
    expect(() => buildSalesChartOption([], 'revenue')).not.toThrow();
    expect(seriesData(buildSalesChartOption([], 'revenue'))).toEqual([]);
    expect(axisLabels(buildSalesChartOption([], 'revenue'))).toEqual([]);
    expect(buildSalesChartOption(undefined as unknown as SalesChartRow[], 'qty')).toBeTruthy();
  });

  it('条末标签与 tooltip 文案复用 formatSalesMetric（金额/计数口径一致）', () => {
    const option = buildSalesChartOption([{ dimKey: 'A', revenueCents: 12_300 }], 'revenue');
    expect(seriesLabelFormatter(option)({ value: 12_300 })).toBe('¥123.00');
    expect(tooltipFormatter(option)({ name: 'A', value: 12_300 })).toBe('A<br/>¥123.00');
    const qtyOption = buildSalesChartOption([{ dimKey: 'A', qty: 7 }], 'qty');
    expect(seriesLabelFormatter(qtyOption)({ value: 7 })).toBe('7 件');
  });
});
