import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';

vi.mock('@/utils/notify', () => ({
  showError: vi.fn(),
  showSuccess: vi.fn(),
  showLongError: vi.fn()
}));

const { cancelReplenishmentTask } = vi.hoisted(() => ({
  cancelReplenishmentTask: vi.fn()
}));
vi.mock('@/utils/merchant-api', () => ({
  merchantApi: {
    cancelReplenishmentTask: (...args: unknown[]) => cancelReplenishmentTask(...args)
  }
}));

import { useReplenishmentFulfillment } from './useReplenishmentFulfillment';
import { showError, showLongError, showSuccess } from '@/utils/notify';

type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;

function make(status: string, canRequest = true) {
  const askConfirm = vi.fn().mockResolvedValue(true);
  const reloadList = vi.fn(async () => {});
  const allTasks = ref<Task[]>([]);
  const selected = ref<Task>({ taskId: 9, deviceId: 'CAB-1', status } as unknown as Task);
  const svc = useReplenishmentFulfillment({
    selected,
    lines: ref([]),
    linesConfirmed: ref(false),
    evidenceItems: ref([]),
    submitting: ref(false),
    doorOpened: ref(true),
    openSessionId: ref('S-1'),
    skipLocationCheck: ref(false),
    lineSummaryMap: ref({}),
    allTasks,
    canRequest: computed(() => canRequest),
    canSkipLocation: false,
    requireReplenishmentEvidence: computed(() => false),
    requireReplenishmentDoor: computed(() => false),
    requireReplenishmentCheckInLocation: computed(() => false),
    checkInMaxDistanceM: computed(() => 0),
    detailIsPullOff: computed(() => false),
    askConfirm,
    getSkipCheckInLocation: () => false,
    persistDoorState: () => {},
    clearDoorState: vi.fn(),
    syncTaskInList: () => {},
    reloadList,
    addEvidence: () => {},
    isPullOffType: () => false,
    isDoorCabinet: () => true,
    inventoryCaps: ref({}),
    headroomBySku: ref({}),
    formatLineSummary: () => '',
    slotHeadroom: () => 0
  });
  return { svc, selected, askConfirm, reloadList, allTasks };
}

describe('useReplenishmentFulfillment.cancelTask', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('cancels after confirm, clears door state and reloads the list', async () => {
    cancelReplenishmentTask.mockResolvedValue({ taskId: 9, status: 'CANCELLED' });
    const { svc, selected, askConfirm, reloadList } = make('IN_PROGRESS');

    await svc.cancelTask();

    expect(askConfirm).toHaveBeenCalledTimes(1);
    expect(cancelReplenishmentTask).toHaveBeenCalledWith(9);
    expect(selected.value.status).toBe('CANCELLED');
    expect(reloadList).toHaveBeenCalledTimes(1);
    expect(showSuccess).toHaveBeenCalledWith('任务已取消');
  });

  it('does nothing when the operator declines the confirm', async () => {
    const { svc, askConfirm } = make('PENDING');
    askConfirm.mockResolvedValue(false);

    await svc.cancelTask();

    expect(cancelReplenishmentTask).not.toHaveBeenCalled();
  });

  /** 服务端判据（有已上架明细 ⇒ 409）必须原样透传到文案，前端不自行放宽。 */
  it('surfaces the backend rejection instead of cancelling locally', async () => {
    cancelReplenishmentTask.mockRejectedValue(new Error('任务已有上架记录，不能按空任务取消'));
    const { svc, selected } = make('IN_PROGRESS');

    await svc.cancelTask();

    expect(showLongError).toHaveBeenCalledWith(
      '任务已有上架记录，不能按空任务取消',
      '取消失败',
      3600
    );
    expect(showSuccess).not.toHaveBeenCalled();
    expect(selected.value.status).toBe('IN_PROGRESS');
  });

  it('refuses without the request permission', async () => {
    const { svc } = make('PENDING', false);

    await svc.cancelTask();

    expect(showError).toHaveBeenCalledWith('无补货操作权限');
    expect(cancelReplenishmentTask).not.toHaveBeenCalled();
  });
});
