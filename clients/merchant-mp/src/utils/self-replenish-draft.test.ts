import { describe, expect, it } from 'vitest';
import type {
  DeviceSlot,
  OpenApiDeviceDetailDto,
  OpenApiReplenishmentSuggestDto
} from '@aicabinet/shared-types';
import {
  appendOrphanSuggestions,
  buildDraftLines,
  buildSuggestMap,
  mergeSlotDraftLine,
  onShelfInventoryRows,
  pickDefaultDeviceIndex,
  sortDraftLines,
  suggestReasonLabel,
  type ReplenishDraftLine
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

describe('mergeSlotDraftLine + orphans', () => {
  it('builds draft from slots and orphan suggestions', () => {
    const bySku = new Map<string, ReplenishDraftLine>();
    const slot = {
      assignedSkuId: 'SKU1',
      assignedSkuName: '可乐',
      bookQty: 2,
      maxLevel: 10,
      parLevel: 8
    } as DeviceSlot;
    const sug = {
      skuId: 'SKU1',
      suggestQty: 5,
      soldQty7d: 12,
      suggestReason: 'ROP'
    } as OpenApiReplenishmentSuggestDto;
    mergeSlotDraftLine(bySku, slot, sug);
    const orphanMap = new Map<string, OpenApiReplenishmentSuggestDto>([
      [
        'SKU2',
        {
          skuId: 'SKU2',
          suggestQty: 4,
          currentQty: 0,
          capacity: 6
        } as OpenApiReplenishmentSuggestDto
      ]
    ]);
    appendOrphanSuggestions(bySku, orphanMap);
    const sorted = sortDraftLines([...bySku.values()]);
    expect(sorted[0]?.skuId).toBe('SKU1');
    expect(sorted[0]?.qty).toBe(5);
    expect(sorted[0]?.selected).toBe(true);
    expect(sorted[1]?.skuId).toBe('SKU2');
    expect(sorted[1]?.selected).toBe(true);
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
  const slots = [
    {
      assignedSkuId: 'SKU-DEMO-001',
      assignedSkuName: '可口可乐 330ml',
      bookQty: 0,
      maxLevel: 20,
      parLevel: 8
    },
    {
      assignedSkuId: '100014',
      assignedSkuName: '康师傅冰红茶',
      bookQty: 1,
      maxLevel: 4,
      parLevel: 3
    },
    {
      assignedSkuId: '100013',
      assignedSkuName: '雪碧柠檬330',
      bookQty: 1,
      maxLevel: 2,
      parLevel: 2
    },
    {
      assignedSkuId: '100013',
      assignedSkuName: '雪碧柠檬330',
      bookQty: 1,
      maxLevel: 2,
      parLevel: 2
    }
  ] as unknown as DeviceSlot[];
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

  it('只出在架 SKU，台账在册无货道的也补上', () => {
    const lines = buildDraftLines(slots, buildSuggestMap(suggest), rows || []);
    expect(lines.map((l) => l.skuId).sort()).toEqual(['100013', '100014', '100015']);
    expect(lines.find((l) => l.skuId === 'SKU-DEMO-001')).toBeUndefined();
    const sprite = lines.find((l) => l.skuId === '100013');
    expect(sprite?.skuName).toBe('雪碧柠檬330');
    expect(sprite?.currentQty).toBe(2);
    const tea = lines.find((l) => l.skuId === '100014');
    expect(tea?.qty).toBe(2);
    // 无建议的台账行按「补到容量」预填
    expect(lines.find((l) => l.skuId === '100015')?.qty).toBe(3);
  });

  it('台账为空数组 = 柜内无在架商品；读失败(null) = 退回货道旧口径', () => {
    expect(buildDraftLines(slots, buildSuggestMap(suggest), [])).toEqual([]);
    const fallback = buildDraftLines(slots, buildSuggestMap(suggest), null);
    expect(fallback.map((l) => l.skuId).sort()).toEqual(['100013', '100014', 'SKU-DEMO-001']);
  });

  it('零货道柜：标题用 skuInventory.skuName，缺名退回编号（2026-10-27 用户反馈）', () => {
    const noSlots: DeviceSlot[] = [];
    const rowsWithName = [
      { skuId: '100013', skuName: '雪碧柠檬味汽水330ml', quantity: 2, capacity: 4 },
      { skuId: '100016', quantity: 4, capacity: 4 }
    ] as OpenApiDeviceDetailDto['skuInventory'];
    const lines = buildDraftLines(noSlots, buildSuggestMap([]), rowsWithName || []);
    expect(lines.map((l) => l.skuId).sort()).toEqual(['100013', '100016']);
    expect(lines.find((l) => l.skuId === '100013')?.skuName).toBe('雪碧柠檬味汽水330ml');
    // 旧后端不带 skuName 时退回编号，不阻塞补货
    expect(lines.find((l) => l.skuId === '100016')?.skuName).toBe('100016');
  });

  it('有货道但台账行存在：库存/容量/名称以台账为准，不吃货道的 0/8（2026-10-27 用户实测）', () => {
    const slotRows = [
      {
        assignedSkuId: '100013',
        assignedSkuName: '雪碧',
        bookQty: 0,
        maxLevel: 8,
        parLevel: 8
      }
    ] as unknown as DeviceSlot[];
    const ledgerRows = [
      { skuId: '100013', skuName: '雪碧柠檬味汽水330ml', quantity: 2, capacity: 4 }
    ] as OpenApiDeviceDetailDto['skuInventory'];
    const lines = buildDraftLines(slotRows, buildSuggestMap([]), ledgerRows || []);
    const sprite = lines.find((l) => l.skuId === '100013');
    expect(sprite?.currentQty).toBe(2);
    expect(sprite?.capacity).toBe(4);
    expect(sprite?.skuName).toBe('雪碧柠檬味汽水330ml');
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
