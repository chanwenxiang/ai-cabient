import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';

vi.mock('@/utils/notify', () => ({
  showError: vi.fn(),
  showSuccess: vi.fn(),
  showLongError: vi.fn()
}));

const { confirmReplenishmentLines } = vi.hoisted(() => ({
  confirmReplenishmentLines: vi.fn()
}));
vi.mock('@/utils/merchant-api', () => ({
  merchantApi: {
    confirmReplenishmentLines: (...args: unknown[]) => confirmReplenishmentLines(...args)
  }
}));

import { useReplenishmentFulfillment } from './useReplenishmentFulfillment';

type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;
type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;

function make(
  doorCabinet: boolean,
  lines: Line[],
  inventoryCaps: Record<string, { quantity: number; capacity: number }> = {}
) {
  const linesRef = ref<Line[]>(lines);
  const askConfirm = vi.fn().mockResolvedValue(true);
  const svc = useReplenishmentFulfillment({
    selected: ref<Task>({ taskId: 9, deviceId: 'CAB-1', status: 'IN_PROGRESS' } as unknown as Task),
    lines: linesRef,
    linesConfirmed: ref(false),
    evidenceItems: ref([]),
    submitting: ref(false),
    doorOpened: ref(false),
    openSessionId: ref(''),
    skipLocationCheck: ref(false),
    lineSummaryMap: ref({}),
    allTasks: ref<Task[]>([]),
    canRequest: computed(() => true),
    canSkipLocation: false,
    requireReplenishmentEvidence: computed(() => false),
    requireReplenishmentDoor: computed(() => false),
    requireReplenishmentCheckInLocation: computed(() => false),
    checkInMaxDistanceM: computed(() => 0),
    detailIsPullOff: computed(() => false),
    askConfirm,
    getSkipCheckInLocation: () => false,
    persistDoorState: () => {},
    clearDoorState: () => {},
    syncTaskInList: () => {},
    reloadList: async () => {},
    addEvidence: () => {},
    isPullOffType: (t?: string) => String(t || '').toUpperCase() === 'PULL_OFF',
    isDoorCabinet: () => doorCabinet,
    inventoryCaps: ref(inventoryCaps),
    formatLineSummary: () => '',
    slotHeadroom: () => 0
  });
  return { svc, linesRef, askConfirm };
}

const line = (over: Partial<Line> = {}): Line =>
  ({
    lineId: 1,
    taskId: 9,
    lineType: 'RESTOCK',
    skuId: 'SKU-COLA',
    quantity: 2,
    slotId: null,
    ...over
  }) as unknown as Line;

describe('useReplenishmentFulfillment 开门柜口径', () => {
  beforeEach(() => {
    confirmReplenishmentLines.mockReset();
    confirmReplenishmentLines.mockImplementation(async (_taskId: number, rows: Line[]) => rows);
  });

  it('开门柜台账未配置（缺行/容量0）时不拦——fail-open，判官在后端', () => {
    const { svc, linesRef } = make(true, [line()]);
    svc.adjustQty(linesRef.value[0], 3);
    expect(linesRef.value[0].quantity).toBe(5);
  });

  it('开门柜按台账封顶：capacity 4 − 在架 2 = 最多再补 2', () => {
    const { svc, linesRef } = make(true, [line()], {
      'SKU-COLA': { quantity: 2, capacity: 4 }
    });
    svc.adjustQty(linesRef.value[0], 3);
    expect(linesRef.value[0].quantity).toBe(2);
    // 已到顶再加：保持 2 不涨
    svc.adjustQty(linesRef.value[0], 1);
    expect(linesRef.value[0].quantity).toBe(2);
  });

  it('开门柜台账已满（room=0）时数量不动', () => {
    const { svc, linesRef } = make(true, [line()], {
      'SKU-COLA': { quantity: 4, capacity: 4 }
    });
    svc.adjustQty(linesRef.value[0], 1);
    expect(linesRef.value[0].quantity).toBe(2);
  });

  it('下架行不受台账封顶（以实取数为准）', () => {
    const { svc, linesRef } = make(true, [line({ lineType: 'PULL_OFF' })], {
      'SKU-COLA': { quantity: 4, capacity: 4 }
    });
    svc.adjustQty(linesRef.value[0], 3);
    expect(linesRef.value[0].quantity).toBe(5);
  });

  it('有货道机型仍按余量拦截（锁：豁免不外溢）', () => {
    const { svc, linesRef } = make(false, [line()]);
    svc.adjustQty(linesRef.value[0], 3);
    expect(linesRef.value[0].quantity).toBe(2);
  });

  it('开门柜确认清单不因缺货道被拦', async () => {
    const { svc, linesRef, askConfirm } = make(true, [line()]);
    await svc.confirmLines();
    expect(askConfirm).not.toHaveBeenCalled();
    expect(confirmReplenishmentLines).toHaveBeenCalledTimes(1);
    expect(confirmReplenishmentLines.mock.calls[0][1][0]).toMatchObject({
      skuId: 'SKU-COLA',
      quantity: 2
    });
    expect(linesRef.value[0].quantity).toBe(2);
  });

  it('有货道机型缺货道仍拒绝提交', async () => {
    const { svc } = make(false, [line()]);
    await svc.confirmLines();
    expect(confirmReplenishmentLines).not.toHaveBeenCalled();
  });
});
