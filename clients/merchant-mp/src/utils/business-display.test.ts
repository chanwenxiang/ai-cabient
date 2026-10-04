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
  formatPeriodRangeLabel,
  mergeCabinetSalesWithDevices,
  rowAov,
  skuMarginRate,
  skuTitleWithQty,
  skuUnitPrice,
  insightHelpText
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
    expect(performanceLabel('BEST_SELLER')).toBe('畅销');
    expect(performanceLabel('UNKNOWN')).toBe('暂无');
    expect(skuTitleWithQty('纯牛奶 250ml', 3)).toBe('纯牛奶 250ml · 3件');
    expect(insightHelpText(30, '本期共分析 6 个商品')).toContain('依据');
    expect(insightHelpText(30, '本期共分析 6 个商品')).toContain('本期共分析 6 个商品');
    expect(formatInsightTime('2026-09-26T10:05:00')).toMatch(/9-26 10:05|9-26/);
  });

  it('报表日期区间含今天共 N 天', () => {
    const r = reportDateRange(7, new Date('2026-09-26T12:00:00'));
    expect(r.toDate).toBe('2026-09-26');
    expect(r.fromDate).toBe('2026-09-20');
    expect(formatPeriodRangeLabel(7, new Date('2026-09-26T12:00:00'))).toBe('9月20日–9月26日');
    expect(formatPeriodRangeLabel(30, new Date('2026-10-04T12:00:00'))).toBe('9月5日–10月4日');
  });

  it('货柜分析补全无成交的柜', () => {
    const rows = mergeCabinetSalesWithDevices(
      [{ dimKey: 'a', dimLabel: '演示智能柜', orderCount: 4, qty: 4, revenueCents: 1750 }],
      [
        { deviceId: 'a', deviceName: '演示智能柜' },
        { deviceId: 'b', deviceName: '二号柜' }
      ]
    );
    expect(rows.map((r) => r.dimKey)).toEqual(['a', 'b']);
    expect(rows[1]).toMatchObject({ dimLabel: '二号柜', orderCount: 0, revenueCents: 0 });
  });
});
