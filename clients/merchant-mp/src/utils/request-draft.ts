/**
 * 要货申请草稿行合并（debt-tracker M10b）。
 * 提交/证据门闩见 `request-submit`（M10c）。
 * 纯函数：货道 + 补货建议 → DraftLine；禁止依赖 uni / merchantApi。
 */
import type { DeviceSlot, OpenApiReplenishmentSuggestDto } from '@aicabinet/shared-types';

export type RequestDraftLine = {
  skuId: string;
  skuName: string;
  currentQty: number;
  capacity: number;
  suggestQty: number;
  soldQty7d: number;
  suggestReason: string;
  qty: number;
  selected: boolean;
};

/** 补货建议理由：PAR=目标库存，ROP=销量再订货点 */
export function suggestReasonLabel(code?: string): string {
  const c = String(code || '').toUpperCase();
  if (!c || c === 'PAR') return '';
  if (c === 'ROP') return '按销量补货';
  if (c === 'PAR+ROP') return '目标库存+销量';
  return code || '';
}

export function buildSuggestMap(
  items: OpenApiReplenishmentSuggestDto[]
): Map<string, OpenApiReplenishmentSuggestDto> {
  const suggestMap = new Map<string, OpenApiReplenishmentSuggestDto>();
  for (const s of items || []) {
    if (!s?.skuId) continue;
    const prev = suggestMap.get(s.skuId);
    if (!prev || (s.suggestQty || 0) > (prev.suggestQty || 0)) suggestMap.set(s.skuId, s);
  }
  return suggestMap;
}

export function mergeSlotDraftLine(
  bySku: Map<string, RequestDraftLine>,
  slot: DeviceSlot,
  sug: OpenApiReplenishmentSuggestDto | undefined
): string | undefined {
  const skuId = String(slot.assignedSkuId || '').trim();
  if (!skuId) return;
  const book = Number(slot.bookQty) || 0;
  const capacity = Number(slot.maxLevel ?? slot.parLevel) || 0;
  const suggestQty = Number(sug?.suggestQty) || 0;
  const soldQty7d = Number(sug?.soldQty7d) || 0;
  const suggestReason = String(sug?.suggestReason || '');
  const existing = bySku.get(skuId);
  if (existing) {
    existing.currentQty += book;
    existing.capacity += capacity;
    existing.suggestQty = Math.max(existing.suggestQty, suggestQty);
    existing.soldQty7d = Math.max(existing.soldQty7d, soldQty7d);
    if (suggestReason) existing.suggestReason = suggestReason;
    return skuId;
  }
  const defaultQty = suggestQty > 0 ? suggestQty : Math.max(0, (Number(slot.parLevel) || 0) - book);
  bySku.set(skuId, {
    skuId,
    skuName: String(slot.assignedSkuName || skuId),
    currentQty: book,
    capacity,
    suggestQty,
    soldQty7d,
    suggestReason,
    qty: defaultQty,
    selected: defaultQty > 0
  });
  return skuId;
}

export function appendOrphanSuggestions(
  bySku: Map<string, RequestDraftLine>,
  suggestMap: Map<string, OpenApiReplenishmentSuggestDto>
): void {
  for (const [skuId, sug] of suggestMap) {
    if (bySku.has(skuId)) continue;
    const suggestQty = Number(sug.suggestQty) || 0;
    const qty = suggestQty > 0 ? suggestQty : 0;
    bySku.set(skuId, {
      skuId,
      skuName: skuId,
      currentQty: Number(sug.currentQty) || 0,
      capacity: Number(sug.capacity) || 0,
      suggestQty,
      soldQty7d: Number(sug.soldQty7d) || 0,
      suggestReason: String(sug.suggestReason || ''),
      qty,
      selected: suggestQty > 0
    });
  }
}

/** 选中优先，再按建议量降序 */
export function sortDraftLines(lines: RequestDraftLine[]): RequestDraftLine[] {
  return [...lines].sort((a, b) => {
    if (a.selected !== b.selected) return a.selected ? -1 : 1;
    return b.suggestQty - a.suggestQty;
  });
}
