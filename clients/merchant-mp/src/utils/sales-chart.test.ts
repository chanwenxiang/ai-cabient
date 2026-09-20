import { describe, expect, it } from 'vitest';
import {
  SALES_CHART_METRICS,
  formatSalesMetric,
  salesBarWidth,
  salesMetricMax,
  salesMetricValue,
  type SalesChartMetric
} from './sales-chart';

const row = {
  dimKey: 'SKU-1',
  dimLabel: '可乐',
  revenueCents: 12_300,
  marginCents: 4_500,
  qty: 7,
  orderCount: 3
};

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

describe('salesMetricMax', () => {
  it('空集合恒为 1（不会除零）', () => {
    expect(salesMetricMax([], 'revenue')).toBe(1);
  });

  it('全 0 亦为 1', () => {
    expect(salesMetricMax([{ revenueCents: 0 }, {}], 'revenue')).toBe(1);
  });

  it('取当前指标下的最大值（不同指标互不干扰）', () => {
    const rows = [
      { revenueCents: 100, qty: 9 },
      { revenueCents: 5_000, qty: 2 }
    ];
    expect(salesMetricMax(rows, 'revenue')).toBe(5_000);
    expect(salesMetricMax(rows, 'qty')).toBe(9);
  });
});

describe('salesBarWidth', () => {
  it('按最大值归一化为百分比', () => {
    expect(salesBarWidth(5_000, 5_000)).toBe('100%');
    expect(salesBarWidth(2_500, 5_000)).toBe('50%');
  });

  it('有值但极小 ⇒ 保底 2% 可见', () => {
    expect(salesBarWidth(1, 1_000_000)).toBe('2%');
  });

  it('0 值同样保底 2%（与既有实现一致，不产生 0 宽不可见条）', () => {
    expect(salesBarWidth(0, 5_000)).toBe('2%');
  });

  it('max 为 0 / 非法时按 1 处理，不产生 Infinity/NaN', () => {
    expect(salesBarWidth(0, 0)).toBe('2%');
    expect(salesBarWidth(NaN, NaN)).toBe('2%');
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
