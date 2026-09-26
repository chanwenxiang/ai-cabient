import { describe, expect, it } from 'vitest';
import {
  avgOrderText,
  businessNum,
  changeClass,
  formatChange,
  formatInsightTime,
  marginRatePercent,
  performanceLabel,
  reportDateRange,
  rowAov,
  skuMarginRate,
  skuUnitPrice
} from './business-display';

describe('business-display · M6b', () => {
  it('数值与环比', () => {
    expect(businessNum(undefined)).toBe(0);
    expect(businessNum(NaN)).toBe(0);
    expect(formatChange(null)).toBe('暂无');
    expect(formatChange(12.34)).toBe('+12.3%');
    expect(changeClass(-1)).toBe('down');
    expect(changeClass(0)).toBe('');
  });

  it('客单 / 毛利 / 件均', () => {
    expect(avgOrderText(10000, null, 2)).toContain('客单');
    expect(avgOrderText(10000, null, 0)).toBe('');
    expect(marginRatePercent(1000, 250)).toBe('25.0%');
    expect(skuUnitPrice({ qtySold: 4, revenueCents: 400 })).toBe(100);
    expect(skuMarginRate({ revenueCents: 200, grossMarginCents: 50 })).toBe('25.0%');
    expect(rowAov({ orderCount: 2, revenueCents: 200 })).toBe(100);
  });

  it('洞察时间与档位', () => {
    expect(performanceLabel('HOT')).toBe('热销');
    expect(performanceLabel('UNKNOWN')).toBe('暂无');
    expect(formatInsightTime('2026-09-26T10:05:00')).toMatch(/9-26 10:05|9-26/);
  });

  it('报表日期区间含今天共 N 天', () => {
    const r = reportDateRange(7, new Date('2026-09-26T12:00:00'));
    expect(r.toDate).toBe('2026-09-26');
    expect(r.fromDate).toBe('2026-09-20');
  });
});
