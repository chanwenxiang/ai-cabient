import { describe, expect, it } from 'vitest';
import type { DeviceSlot, OpenApiReplenishmentSuggestDto } from '@aicabinet/shared-types';
import {
  appendOrphanSuggestions,
  buildSuggestMap,
  mergeSlotDraftLine,
  sortDraftLines,
  suggestReasonLabel,
  type RequestDraftLine
} from './request-draft';

describe('suggestReasonLabel', () => {
  it('hides PAR and maps ROP', () => {
    expect(suggestReasonLabel('PAR')).toBe('');
    expect(suggestReasonLabel('ROP')).toBe('按销量补货');
    expect(suggestReasonLabel('PAR+ROP')).toBe('目标库存+销量');
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
    const bySku = new Map<string, RequestDraftLine>();
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
