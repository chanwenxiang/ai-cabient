/**
 * 经营分析展示纯函数（debt-tracker M6b）。
 * 禁止依赖 uni / merchantApi；税档保存在 pages/tax，销售报表在 pages/cabinet-reports。
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

export function skuTitleWithQty(name?: string | null, qty?: number | null): string {
  const n = (name || '').trim() || '商品';
  return `${n} · ${businessNum(qty)}件`;
}

export function settlementHelpText(): string {
  return '这两个金额是全店所有货柜合计，不随上面所选货柜变化。待结算是尚未打到商户账上的货款，本月已结是本月已结算金额。明细请到结算对账查看。';
}

export function insightHelpText(days: number, insight?: string | null): string {
  const base =
    `依据：所选货柜当前在库或已绑货道的商品，对照近${Math.max(1, days)}天成交。` +
    '畅销=销量不低于中位数的两倍，慢销=销量不足中位数一半，无销量=期间 0 件。';
  const extra = (insight || '').trim();
  return extra ? `${base}\n\n${extra}` : base;
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
  BEST_SELLER: '畅销',
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

/** 经营分析顶栏：把 YYYY-MM-DD 区间显示成「9月28日–10月4日」。 */
export function formatPeriodRangeLabel(
  days: number,
  now: Date = new Date()
): string {
  const { fromDate, toDate } = reportDateRange(days, now);
  const md = (iso: string) => {
    const [, m, d] = iso.split('-');
    return `${Number(m)}月${Number(d)}日`;
  };
  return `${md(fromDate)}–${md(toDate)}`;
}

export type CabinetSalesRow = {
  dimKey?: string;
  dimLabel?: string;
  orderCount?: number;
  qty?: number;
  revenueCents?: number;
  cogsCents?: number;
  marginCents?: number;
  refundedCents?: number;
  refundOrderCount?: number;
};

/** 把「区间内有成交的柜」补全成商户名下全部货柜；没成交的柜营收为 0。 */
export function mergeCabinetSalesWithDevices(
  sales: CabinetSalesRow[] | null | undefined,
  devices: Array<{ deviceId?: string; deviceName?: string }> | null | undefined
): CabinetSalesRow[] {
  const sold = sales || [];
  const byId = new Map<string, CabinetSalesRow>();
  for (const row of sold) {
    if (row.dimKey) byId.set(row.dimKey, row);
  }
  const out: CabinetSalesRow[] = [];
  const seen = new Set<string>();
  for (const device of devices || []) {
    const id = device.deviceId;
    if (!id) continue;
    seen.add(id);
    const hit = byId.get(id);
    out.push(
      hit
        ? { ...hit, dimLabel: hit.dimLabel || device.deviceName || id }
        : {
            dimKey: id,
            dimLabel: device.deviceName || id,
            orderCount: 0,
            qty: 0,
            revenueCents: 0,
            cogsCents: 0,
            marginCents: 0,
            refundedCents: 0,
            refundOrderCount: 0
          }
    );
  }
  for (const row of sold) {
    if (row.dimKey && !seen.has(row.dimKey)) out.push(row);
  }
  return out.sort((a, b) => businessNum(b.revenueCents) - businessNum(a.revenueCents));
}
