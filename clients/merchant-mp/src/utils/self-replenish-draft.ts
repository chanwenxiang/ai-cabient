/**
 * 自主补货草稿行合并。
 * 提交门闩见 `self-replenish-submit`。
 * 纯函数：在架台账 + 货道 + 补货建议 → DraftLine；禁止依赖 uni / merchantApi。
 */
import type {
  DeviceSlot,
  OpenApiDeviceDetailDto,
  OpenApiDeviceInventoryDto,
  OpenApiReplenishmentSuggestDto
} from '@aicabinet/shared-types';

export type ReplenishDraftLine = {
  skuId: string;
  skuName: string;
  /** 规格（如 250ml）；后端已拼进 skuName 时可为空 */
  spec?: string;
  currentQty: number;
  capacity: number;
  suggestQty: number;
  soldQty7d: number;
  suggestReason: string;
  qty: number;
  selected: boolean;
};

/** 补货建议理由：PAR=目标库存，ROP=销量再订货点，LOW_STOCK=库存偏低 */
export function suggestReasonLabel(code?: string): string {
  const c = String(code || '').toUpperCase();
  if (!c || c === 'PAR') return '';
  if (c === 'LOW_STOCK') return '库存偏低';
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

/**
 * 在架台账 = 补货明细的唯一骨架真源（CB-069）。
 * 后端 `deviceDetail.skuInventory` 已是 `device_sku_inventory` ∩ `sku_catalog.status='ACTIVE'`，
 * 与设备详情「已上架商品」、消费者目录同口径 ⇒ 已下架/演示 SKU 不会进来。
 *
 * 🔴 `null`（详情读取失败）与 `[]`（柜里确实没有在架商品）必须分开：
 * 前者退回货道骨架（旧行为），后者是真空态，不能当成「没读到」而放行全部货道 SKU。
 */
export function onShelfInventoryRows(
  detail: OpenApiDeviceDetailDto | null | undefined
): OpenApiDeviceInventoryDto[] | null {
  const rows = detail?.skuInventory;
  return Array.isArray(rows) ? rows : null;
}

export function mergeSlotDraftLine(
  bySku: Map<string, ReplenishDraftLine>,
  slot: DeviceSlot,
  sug: OpenApiReplenishmentSuggestDto | undefined,
  onShelf?: Set<string> | null
): string | undefined {
  const skuId = String(slot.assignedSkuId || '').trim();
  if (!skuId) return;
  // 在架集合存在时，货道只作名称/容量注解：绑了已下架 SKU 的货道不进明细（2026-10-27 用户「补货应该只补柜里当前存在的 sku」）
  if (onShelf && !onShelf.has(skuId)) return;
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

/**
 * 孤儿建议落行：只用于「在架台账读不到」的退回路径（buildDraftLines 内部）。
 * 在架态下不要直接调用——建议接口会带已下架 SKU 回来，会把演示商品重新灌进明细。
 */
export function appendOrphanSuggestions(
  bySku: Map<string, ReplenishDraftLine>,
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

/** 台账在册但没绑到货道的 SKU（货道漏配/仅按商品建档）也必须能补，否则柜里真有货的商品永远补不到。 */
export function mergeInventoryDraftLine(
  bySku: Map<string, ReplenishDraftLine>,
  row: OpenApiDeviceInventoryDto,
  sug: OpenApiReplenishmentSuggestDto | undefined
): string | undefined {
  const skuId = String(row.skuId || '').trim();
  if (!skuId || bySku.has(skuId)) return skuId || undefined;
  const currentQty = Number(row.quantity) || 0;
  const capacity = Number(row.capacity) || 0;
  const suggestQty = Number(sug?.suggestQty) || 0;
  const defaultQty = suggestQty > 0 ? suggestQty : Math.max(0, capacity - currentQty);
  bySku.set(skuId, {
    skuId,
    // 设备详情 skuInventory 现带 skuName（2026-10-27 后端补的读面注解）；
    // 旧后端/空名时退回编号，不阻塞补货
    skuName: String(row.skuName || skuId),
    currentQty,
    capacity,
    suggestQty,
    soldQty7d: Number(sug?.soldQty7d) || 0,
    suggestReason: String(sug?.suggestReason || ''),
    qty: defaultQty,
    selected: defaultQty > 0
  });
  return skuId;
}

/**
 * 草稿组装：在架台账优先，货道只补名称/容量；台账读不到（rows=null）才退回「货道 + 孤儿建议」旧口径。
 * 在架态下**不再由孤儿建议新增行**——建议可能带着已下架 SKU 回来（CB-069）。
 */
export function buildDraftLines(
  slots: DeviceSlot[],
  suggestMap: Map<string, OpenApiReplenishmentSuggestDto>,
  rows: OpenApiDeviceInventoryDto[] | null
): ReplenishDraftLine[] {
  const bySku = new Map<string, ReplenishDraftLine>();
  const rest = new Map(suggestMap);
  const onShelf = rows ? new Set(rows.map((r) => String(r.skuId || '').trim())) : null;
  for (const slot of slots || []) {
    const skuId = mergeSlotDraftLine(
      bySku,
      slot,
      rest.get(String(slot.assignedSkuId || '').trim()),
      onShelf
    );
    if (skuId) rest.delete(skuId);
  }
  if (rows) {
    for (const row of rows) {
      const skuId = mergeInventoryDraftLine(bySku, row, rest.get(String(row.skuId || '').trim()));
      if (skuId) rest.delete(skuId);
    }
  } else {
    appendOrphanSuggestions(bySku, rest);
  }
  return sortDraftLines([...bySku.values()]);
}

/** 选中优先，再按建议量降序 */
export function sortDraftLines(lines: ReplenishDraftLine[]): ReplenishDraftLine[] {
  return [...lines].sort((a, b) => {
    if (a.selected !== b.selected) return a.selected ? -1 : 1;
    return b.suggestQty - a.suggestQty;
  });
}

type DevicePick = {
  deviceId?: string;
  oosSlotCount?: number | null;
  lowStockSlotCount?: number | null;
};

/** 收藏/常驻柜优先，否则缺货货道最多（缺货+低库存）。 */
export function pickDefaultDeviceIndex(list: DevicePick[], prefer?: string): number {
  if (!list.length) return 0;
  const preferKey = String(prefer || '')
    .trim()
    .toUpperCase();
  if (preferKey) {
    const idx = list.findIndex(
      (d) =>
        String(d.deviceId || '')
          .trim()
          .toUpperCase() === preferKey
    );
    if (idx >= 0) return idx;
  }
  let best = 0;
  let bestScore = -1;
  list.forEach((d, i) => {
    const score = (Number(d.oosSlotCount) || 0) + (Number(d.lowStockSlotCount) || 0);
    if (score > bestScore) {
      bestScore = score;
      best = i;
    }
  });
  return best;
}
