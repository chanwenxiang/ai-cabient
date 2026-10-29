import type { OpenApiReplenishmentHeadroomDto } from '@aicabinet/shared-types';

/**
 * 开门柜「可补量」的唯一算法（D28-A5 / CB-075）。
 *
 * 此前两处各自算一层（录入 `+` 与详情收敛各写各的 `capacity − quantity`），
 * 而提交校验还多扣一层「他单已领未上柜」⇒ 加得进去、点提交才 400。
 * 现在两个站点都走这里，且优先用后端 `/headroom` 下发的 `room`（与提交封顶同一个数）。
 */

/** 后端下发的可补量（`ReplenishmentHeadroomDto`）。 */
export type ServerHeadroom = {
  capacity: number;
  onShelfQty: number;
  otherInTransitQty: number;
  room: number;
};

/** 本地台账行（`deviceDetail.skuInventory`，只有上限与在架两列）。 */
export type LedgerRow = { quantity: number; capacity: number };

export type DoorHeadroom = ServerHeadroom & { source: 'server' | 'ledger' };

function toInt(value: unknown): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function normalizeServer(
  rows: Record<string, OpenApiReplenishmentHeadroomDto> | undefined | null,
  skuId: string
): ServerHeadroom | null {
  const row = rows?.[skuId];
  if (!row) return null;
  const capacity = toInt(row.capacity);
  // capacity≤0 = 台账未配容量：与后端同判据，不设限（下发里本就不该带这行，兜脏数据）
  if (capacity <= 0) return null;
  const onShelfQty = toInt(row.onShelfQty);
  const otherInTransitQty = toInt(row.otherInTransitQty);
  // 后端已钳到 ≥0；这里再钳一次防脏响应给出负 room 把数量钳成负数
  const room = Math.max(0, toInt(row.room));
  return { capacity, onShelfQty, otherInTransitQty, room };
}

/**
 * 取某个 SKU 的可补量。返回 null = 不设限（台账缺行/未配容量/后端未下发）。
 * 后端下发优先；只有后端没给（老服务、请求失败、空数组）才退回台账 `capacity − 在架`，
 * 该降级只会偏松（少扣一层他单在途），判官仍在提交侧。
 */
export function doorHeadroomForSku(
  skuId: string | undefined | null,
  headroomBySku: Record<string, OpenApiReplenishmentHeadroomDto> | undefined | null,
  inventoryCaps: Record<string, LedgerRow> | undefined | null
): DoorHeadroom | null {
  const key = String(skuId || '').trim();
  if (!key) return null;
  const server = normalizeServer(headroomBySku, key);
  if (server) return { ...server, source: 'server' };
  const inv = inventoryCaps?.[key];
  if (!inv || toInt(inv.capacity) <= 0) return null;
  const capacity = toInt(inv.capacity);
  const onShelfQty = toInt(inv.quantity);
  return {
    capacity,
    onShelfQty,
    otherInTransitQty: 0,
    room: Math.max(0, capacity - onShelfQty),
    source: 'ledger'
  };
}

/**
 * 与后端 400 文案同字面（`ReplenishmentService.assertDoorCabinetHeadroom`）：
 * 「上限 X，在架 Y，他单已领未上柜 Z，最多再补 N」——前端提示不能和后端拒因各说一套。
 */
export function describeDoorHeadroom(h: DoorHeadroom): string {
  if (h.source !== 'server') {
    // 降级值缺「他单在途」这层，不能冒充后端口径说四项齐全
    return `最多再补 ${h.room}`;
  }
  return `上限 ${h.capacity}，在架 ${h.onShelfQty}，他单已领未上柜 ${h.otherInTransitQty}，最多再补 ${h.room}`;
}
