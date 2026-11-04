import { describe, expect, it } from 'vitest';
import type {
  OpenApiDeviceDetailDto,
  OpenApiReplenishmentSuggestDto
} from '@aicabinet/shared-types';
import {
  buildDraftLines,
  buildSuggestMap,
  onShelfInventoryRows,
  pickDefaultDeviceIndex,
  suggestReasonLabel
} from './self-replenish-draft';

describe('suggestReasonLabel', () => {
  it('hides PAR and maps ROP', () => {
    expect(suggestReasonLabel('PAR')).toBe('');
    expect(suggestReasonLabel('ROP')).toBe('按销量补货');
    expect(suggestReasonLabel('PAR+ROP')).toBe('目标库存+销量');
  });
  it('maps LOW_STOCK to Chinese, never leaks the raw enum (2026-10-27 用户反馈)', () => {
    expect(suggestReasonLabel('LOW_STOCK')).toBe('库存偏低');
    expect(suggestReasonLabel('low_stock')).toBe('库存偏低');
  });
});

describe('buildSuggestMap', () => {
  it('keeps higher suggestQty per sku', () => {
    const map = buildSuggestMap([
      { skuId: 'A', suggestQty: 1 },
      { skuId: 'A', suggestQty: 3 },
      { skuId: 'B', suggestQty: 2 }
    ] as OpenApiReplenishmentSuggestDto[]);
    expect(map.get('A')?.suggestQty).toBe(3);
    expect(map.get('B')?.suggestQty).toBe(2);
  });
});

describe('onShelfInventoryRows', () => {
  it('把「读失败」与「柜内真空」分开', () => {
    expect(onShelfInventoryRows(null)).toBeNull();
    expect(onShelfInventoryRows(undefined)).toBeNull();
    expect(onShelfInventoryRows({} as OpenApiDeviceDetailDto)).toBeNull();
    expect(onShelfInventoryRows({ skuInventory: [] } as OpenApiDeviceDetailDto)).toEqual([]);
  });
});

describe('buildDraftLines（在架台账为骨架，CB-069）', () => {
  // 设备详情已按 sku_catalog.status=ACTIVE 过滤：下架的 SKU-DEMO-001 不在其中，100015 无货道但在台账
  const rows = [
    { skuId: '100013', quantity: 2, capacity: 4 },
    { skuId: '100014', quantity: 1, capacity: 4 },
    { skuId: '100015', quantity: 1, capacity: 4 }
  ] as OpenApiDeviceDetailDto['skuInventory'];
  const suggest = [
    { skuId: '100014', suggestQty: 2, suggestReason: 'PAR' },
    // 建议接口仍会带回已下架 SKU，不能因此进明细
    { skuId: 'SKU-DEMO-001', suggestQty: 5, suggestReason: 'PAR+ROP' }
  ] as OpenApiReplenishmentSuggestDto[];

  it('只出在架 SKU，台账在册的都补上', () => {
    const lines = buildDraftLines(buildSuggestMap(suggest), rows || []);
    expect(lines.map((l) => l.skuId).sort()).toEqual(['100013', '100014', '100015']);
    expect(lines.find((l) => l.skuId === 'SKU-DEMO-001')).toBeUndefined();
    const sprite = lines.find((l) => l.skuId === '100013');
    expect(sprite?.skuName).toBe('100013');
    expect(sprite?.currentQty).toBe(2);
    const tea = lines.find((l) => l.skuId === '100014');
    expect(tea?.qty).toBe(2);
    // 无建议的台账行按「补到容量」预填
    expect(lines.find((l) => l.skuId === '100015')?.qty).toBe(3);
  });

  it('台账为空数组 = 柜内无在架商品；读失败(null) = 退回孤儿建议口径', () => {
    expect(buildDraftLines(buildSuggestMap(suggest), [])).toEqual([]);
    const fallback = buildDraftLines(buildSuggestMap(suggest), null);
    expect(fallback.map((l) => l.skuId).sort()).toEqual(['100014', 'SKU-DEMO-001']);
  });

  it('台账行优先用 skuInventory.skuName，缺名退回编号（2026-10-27 用户反馈）', () => {
    const rowsWithName = [
      { skuId: '100013', skuName: '雪碧柠檬味汽水330ml', quantity: 2, capacity: 4 },
      { skuId: '100016', quantity: 4, capacity: 4 }
    ] as OpenApiDeviceDetailDto['skuInventory'];
    const lines = buildDraftLines(buildSuggestMap([]), rowsWithName || []);
    expect(lines.map((l) => l.skuId).sort()).toEqual(['100013', '100016']);
    expect(lines.find((l) => l.skuId === '100013')?.skuName).toBe('雪碧柠檬味汽水330ml');
    // 旧后端不带 skuName 时退回编号，不阻塞补货
    expect(lines.find((l) => l.skuId === '100016')?.skuName).toBe('100016');
  });
});

describe('pickDefaultDeviceIndex', () => {
  it('prefers starred cabinet, else highest shortage', () => {
    const list = [
      { deviceId: 'A', oosSlotCount: 1, lowStockSlotCount: 0 },
      { deviceId: 'B', oosSlotCount: 4, lowStockSlotCount: 2 },
      { deviceId: 'C', oosSlotCount: 0, lowStockSlotCount: 1 }
    ];
    expect(pickDefaultDeviceIndex(list, 'C')).toBe(2);
    expect(pickDefaultDeviceIndex(list)).toBe(1);
  });
});
