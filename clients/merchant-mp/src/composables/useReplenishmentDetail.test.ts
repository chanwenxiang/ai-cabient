import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';

const { safeChooseImageWithReason, showError, uploadEvidence } = vi.hoisted(() => ({
  safeChooseImageWithReason: vi.fn(),
  showError: vi.fn(),
  uploadEvidence: vi.fn()
}));

vi.mock('@aicabinet/shared-uni/safe-uni-call', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@aicabinet/shared-uni/safe-uni-call')>()),
  safeChooseImageWithReason
}));
vi.mock('@aicabinet/shared-uni/upload-limits', () => ({
  assertLocalImageSize: vi.fn(async () => {})
}));
vi.mock('@/utils/notify', () => ({ showError }));
vi.mock('@/utils/merchant-api', () => ({
  merchantApi: { uploadReplenishmentEvidence: uploadEvidence },
  softFallback: vi.fn()
}));

import { useReplenishmentDetail } from './useReplenishmentDetail';

type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;

function make() {
  const selected = ref<Task>({ taskId: 9, checkInAt: '2026-11-27 10:00:00' } as Task);
  const evidenceItems = ref<{ localPath: string; fileId?: number }[]>([]);
  const service = useReplenishmentDetail({
    allTasks: ref([]),
    evidenceCountMap: ref({}),
    selected,
    lines: ref([]),
    linesConfirmed: ref(false),
    evidenceItems,
    detailVisible: ref(true),
    sheetCloseArmed: ref(true),
    detailLoading: ref(false),
    submitting: ref(false),
    inventoryCaps: ref({}),
    headroomBySku: ref({}),
    focusTaskId: ref(null),
    filterDeviceId: ref(''),
    status: ref(''),
    canRequest: computed(() => true),
    restoreDoorState: vi.fn(),
    syncDoorStateFromServer: vi.fn(async () => {})
  });
  return { service, evidenceItems };
}

describe('useReplenishmentDetail 平台选图终态', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('拒权时给出权限修复提示且不上传', async () => {
    safeChooseImageWithReason.mockResolvedValue({ paths: [], denied: true, status: 'denied' });
    const { service } = make();
    await service.addEvidence();
    expect(showError).toHaveBeenCalledWith('拍照失败，请允许相机或相册权限后重试');
    expect(uploadEvidence).not.toHaveBeenCalled();
  });

  it('非拒权失败也给出可见终态，不再静默 return', async () => {
    safeChooseImageWithReason.mockResolvedValue({ paths: [], denied: false, status: 'failed' });
    const { service } = make();
    await service.addEvidence();
    expect(showError).toHaveBeenCalledWith('拍照失败，请检查相机或相册后重试');
    expect(uploadEvidence).not.toHaveBeenCalled();
  });

  it('用户取消不误报失败', async () => {
    safeChooseImageWithReason.mockResolvedValue({ paths: [], denied: false, status: 'cancelled' });
    const { service } = make();
    await service.addEvidence();
    expect(showError).not.toHaveBeenCalled();
  });

  it('busy 覆盖选图 Promise 完整周期，在途重复点击只调用一次', async () => {
    let resolveChoose!: (value: { paths: string[]; denied: boolean; status: string }) => void;
    safeChooseImageWithReason.mockReturnValue(
      new Promise((resolve) => {
        resolveChoose = resolve;
      })
    );
    const { service } = make();
    const first = service.addEvidence();
    const second = service.addEvidence();
    expect(safeChooseImageWithReason).toHaveBeenCalledTimes(1);
    resolveChoose({ paths: [], denied: false, status: 'cancelled' });
    await Promise.all([first, second]);
  });
});
