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
import { showError } from '@/utils/notify';

type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;
type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;
type Headroom = import('@aicabinet/shared-types').OpenApiReplenishmentHeadroomDto;

function make(
  doorCabinet: boolean,
  lines: Line[],
  inventoryCaps: Record<string, { quantity: number; capacity: number }> = {},
  headroomBySku: Record<string, Headroom> = {}
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
    headroomBySku: ref(headroomBySku),
    formatLineSummary: () => ''
  });
  return { svc, linesRef, askConfirm };
}

/** 后端下发口径（D28-A5）：room = 上限 − 在架 − 他单已领未上柜 */
const serverRoom = (over: Partial<Headroom> = {}): Headroom =>
  ({
    skuId: 'SKU-COLA',
    capacity: 20,
    onShelfQty: 5,
    otherInTransitQty: 10,
    room: 5,
    ...over
  }) as unknown as Headroom;

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
    vi.mocked(showError).mockClear();
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

  it('后端下发 room 优先：台账口径 15、后端 5 ⇒ 只让补 5（D28-A5 少扣一层才 400 的根因）', () => {
    const { svc, linesRef } = make(
      true,
      [line()],
      { 'SKU-COLA': { quantity: 5, capacity: 20 } },
      { 'SKU-COLA': serverRoom() }
    );
    svc.adjustQty(linesRef.value[0], 8);
    expect(linesRef.value[0].quantity).toBe(5);
    svc.adjustQty(linesRef.value[0], 1);
    expect(linesRef.value[0].quantity).toBe(5);
    expect(showError).toHaveBeenCalledWith('上限 20，在架 5，他单已领未上柜 10，最多再补 5');
  });

  it('后端下发比台账宽时也用后端（不再各算一层）', () => {
    const { svc, linesRef } = make(
      true,
      [line()],
      { 'SKU-COLA': { quantity: 8, capacity: 10 } },
      { 'SKU-COLA': serverRoom({ capacity: 30, onShelfQty: 8, otherInTransitQty: 0, room: 9 }) }
    );
    svc.adjustQty(linesRef.value[0], 6);
    expect(linesRef.value[0].quantity).toBe(8);
    svc.adjustQty(linesRef.value[0], 6);
    expect(linesRef.value[0].quantity).toBe(9);
  });

  it('后端下发 room=0 ⇒ 不动，提示带「台账已满」', () => {
    const { svc, linesRef } = make(true, [line()], {}, { 'SKU-COLA': serverRoom({ room: 0 }) });
    svc.adjustQty(linesRef.value[0], 2);
    expect(linesRef.value[0].quantity).toBe(2);
    expect(showError).toHaveBeenCalledWith(
      '台账已满，无法再加（上限 20，在架 5，他单已领未上柜 10，最多再补 0）'
    );
  });

  it('后端下发 capacity≤0（脏数据）= 不设限，与后端未配容量同判据', () => {
    const { svc, linesRef } = make(true, [line()], {}, { 'SKU-COLA': serverRoom({ capacity: 0 }) });
    svc.adjustQty(linesRef.value[0], 3);
    expect(linesRef.value[0].quantity).toBe(5);
  });

  it('下架行不受后端 room 影响（以实取数为准）', () => {
    const { svc, linesRef } = make(
      true,
      [line({ lineType: 'PULL_OFF' })],
      {},
      { 'SKU-COLA': serverRoom({ room: 1 }) }
    );
    svc.adjustQty(linesRef.value[0], 6);
    expect(linesRef.value[0].quantity).toBe(8);
  });

  it('下架行不受台账封顶（以实取数为准）', () => {
    const { svc, linesRef } = make(true, [line({ lineType: 'PULL_OFF' })], {
      'SKU-COLA': { quantity: 4, capacity: 4 }
    });
    svc.adjustQty(linesRef.value[0], 3);
    expect(linesRef.value[0].quantity).toBe(5);
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
});
