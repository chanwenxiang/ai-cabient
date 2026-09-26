/**
 * 经营分析展示纯函数（debt-tracker M6b）。
 * 禁止依赖 uni / merchantApi；写路径（税档保存）留在 business.vue。
 */
import { fmtMoney } from '@aicabinet/shared-uni/format';

export function businessNum(v?: number | null): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

/** 「今日 / 累计」右侧的客单文案；单量为 0 时返回空串。 */
export function avgOrderText(
  revenueCents?: number | null,
  avgCents?: number | null,
  orders?: number | null
): string {
  const o = businessNum(orders);
  if (o <= 0) return '';
  const avg = businessNum(avgCents) || businessNum(revenueCents) / o;
  return ` · 客单 ${fmtMoney(avg)}`;
}

export function formatChange(pct?: number | null): string {
  if (pct == null || Number.isNaN(pct)) return '暂无';
  const sign = pct > 0 ? '+' : '';
  return `${sign}${pct.toFixed(1)}%`;
}

export function changeClass(pct?: number | null): string {
  if (pct == null || Number.isNaN(pct) || pct === 0) return '';
  return pct > 0 ? 'up' : 'down';
}

export function marginRatePercent(
  revenueCents?: number | null,
  grossMarginCents?: number | null
): string {
  const revenue = businessNum(revenueCents);
  if (!revenue) return '暂无';
  return `${((businessNum(grossMarginCents) / revenue) * 100).toFixed(1)}%`;
}

export function skuUnitPrice(sku: {
  qtySold?: number | null;
  revenueCents?: number | null;
}): number {
  const qty = businessNum(sku.qtySold);
  return qty > 0 ? Math.round(businessNum(sku.revenueCents) / qty) : 0;
}

export function skuMarginRate(sku: {
  revenueCents?: number | null;
  grossMarginCents?: number | null;
}): string {
  return marginRatePercent(sku.revenueCents, sku.grossMarginCents);
}

export function rowAov(r: { orderCount?: number | null; revenueCents?: number | null }): number {
  const orders = businessNum(r.orderCount);
  return orders > 0 ? Math.round(businessNum(r.revenueCents) / orders) : 0;
}

export function formatInsightTime(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

const PERFORMANCE_LABELS: Record<string, string> = {
  NORMAL: '正常',
  SLOW_MOVER: '滞销',
  NO_SALES: '无销量',
  HOT: '热销',
  TOP: '爆款'
};

export function performanceLabel(level?: string | null): string {
  return (level && PERFORMANCE_LABELS[level]) || '暂无';
}

/** 销售报表日期区间（含今天共 days 天）。 */
export function reportDateRange(
  days: number,
  now: Date = new Date()
): { fromDate: string; toDate: string } {
  const to = new Date(now.getTime());
  const from = new Date(now.getTime());
  from.setDate(to.getDate() - (Math.max(1, days) - 1));
  const pad = (n: number) => String(n).padStart(2, '0');
  const fmt = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  return { fromDate: fmt(from), toDate: fmt(to) };
}
