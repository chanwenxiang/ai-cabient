import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import type { DeviceSlot, MerchantSkuPricing } from '@aicabinet/shared-types';
import { useReplenishmentDisplay } from './useReplenishmentDisplay';

type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

function make(doorCabinet: boolean) {
  return useReplenishmentDisplay({
    skus: ref<MerchantSkuPricing[]>([]),
    slotCaps: ref({}),
    deviceSlotsList: ref<DeviceSlot[]>([]),
    isDoorCabinet: () => doorCabinet
  });
}

const restockLine = {
  lineId: 1,
  taskId: 9,
  lineType: 'RESTOCK',
  skuId: 'SKU-COLA',
  quantity: 2,
  slotId: null
} as unknown as Line;

describe('useReplenishmentDisplay 开门柜口径', () => {
  it('开门柜行摘要不再提示「待选货道」', () => {
    const summary = make(true).formatLineSummary([restockLine]);
    expect(summary).not.toContain('待选货道');
    expect(summary).toContain('共 2 件');
  });

  it('有货道机型维持原提示（锁：豁免不外溢）', () => {
    const summary = make(false).formatLineSummary([restockLine]);
    expect(summary).toContain('1 行待选货道');
  });

  it('开门柜不显示货道余量提示', () => {
    const svc = useReplenishmentDisplay({
      skus: ref<MerchantSkuPricing[]>([]),
      slotCaps: ref({ A1: { maxLevel: 5, bookQty: 5 } }),
      deviceSlotsList: ref<DeviceSlot[]>([]),
      isDoorCabinet: () => true
    });
    expect(svc.slotHint({ ...restockLine, slotId: 'A1' } as unknown as Line)).toBe('');
  });
});
