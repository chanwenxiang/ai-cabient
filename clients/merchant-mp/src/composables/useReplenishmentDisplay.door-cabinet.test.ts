import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import type { MerchantSkuPricing } from '@aicabinet/shared-types';
import { useReplenishmentDisplay } from './useReplenishmentDisplay';

type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

function make() {
  return useReplenishmentDisplay({ skus: ref<MerchantSkuPricing[]>([]) });
}

const restockLine = {
  lineId: 1,
  taskId: 9,
  lineType: 'RESTOCK',
  skuId: 'SKU-COLA',
  quantity: 2,
  slotId: null
} as unknown as Line;

describe('useReplenishmentDisplay 行摘要', () => {
  it('不再提示「待选货道」', () => {
    const summary = make().formatLineSummary([restockLine]);
    expect(summary).not.toContain('待选货道');
    expect(summary).toContain('共 2 件');
  });

  it('空明细给固定文案', () => {
    expect(make().formatLineSummary([])).toBe('暂无明细行');
  });
});
