import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}));

vi.mock('@/api/client', () => ({ api: { request: vi.fn() } }));
vi.mock('@/api/endpoints', () => ({
  AdminEndpoints: { suppliersPayablePay: (id: string | number) => `/payables/${id}/pay` }
}));

import { api } from '@/api/client';
import { useWarehouseEntityDialogs } from './useWarehouseEntityDialogs';

function makeDialogs() {
  return useWarehouseEntityDialogs({
    saving: ref(false),
    dialogBootLoading: ref(false),
    tab: ref('payables'),
    loadedTabs: ref(new Set<string>()),
    loadTab: vi.fn().mockResolvedValue(undefined),
    filterWarehouseId: ref(''),
    activeWarehouses: computed(() => []),
    skus: ref([]),
    loadWarehousesSoft: vi.fn().mockResolvedValue(undefined),
    ensureMeta: vi.fn().mockResolvedValue(undefined)
  });
}

describe('供应商付款幂等键', () => {
  beforeEach(() => vi.clearAllMocks());

  it('首次响应丢失后重试复用同一稳定键', async () => {
    vi.mocked(api.request).mockRejectedValueOnce(new Error('response lost')).mockResolvedValueOnce({});
    const dialogs = makeDialogs();
    dialogs.openPay({ payableId: 7, balanceCents: 10000 });

    await dialogs.savePayment();
    await dialogs.savePayment();

    const calls = vi.mocked(api.request).mock.calls;
    expect(calls).toHaveLength(2);
    const first = calls[0]?.[2] as { idempotencyKey: string };
    const second = calls[1]?.[2] as { idempotencyKey: string };
    expect(first.idempotencyKey).toMatch(/^supplier-pay-/);
    expect(second.idempotencyKey).toBe(first.idempotencyKey);
  });
});
