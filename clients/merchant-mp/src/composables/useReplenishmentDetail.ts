import { nextTick, type ComputedRef, type Ref } from 'vue';
import { showError } from '@/utils/notify';
import { merchantApi, softFallback } from '@/utils/merchant-api';
import { assertLocalImageSize } from '@aicabinet/shared-uni/upload-limits';
import type { DeviceSlot } from '@aicabinet/shared-types';

type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;
type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

export function buildSlotCapsFromSlots(slots: DeviceSlot[]) {
  const map: Record<string, { maxLevel: number; bookQty: number }> = {};
  for (const s of slots) {
    const code = String(s.slotCode || '').toUpperCase();
    if (!code) continue;
    map[code] = {
      maxLevel: Number(s.maxLevel) || 0,
      bookQty: Number(s.bookQty) || 0
    };
  }
  return map;
}

/**
 * 补货详情 sheet：深链打开、拉明细/货道/凭证、上传预览、关闭。
 */
export function useReplenishmentDetail(opts: {
  allTasks: Ref<Task[]>;
  evidenceCountMap: Ref<Record<number, number>>;
  selected: Ref<Task | null>;
  lines: Ref<Line[]>;
  linesConfirmed: Ref<boolean>;
  evidenceItems: Ref<{ localPath: string; fileId?: number }[]>;
  detailVisible: Ref<boolean>;
  sheetCloseArmed: Ref<boolean>;
  detailLoading: Ref<boolean>;
  submitting: Ref<boolean>;
  slotCaps: Ref<Record<string, { maxLevel: number; bookQty: number }>>;
  deviceSlotsList: Ref<DeviceSlot[]>;
  focusTaskId: Ref<number | null>;
  filterDeviceId: Ref<string>;
  status: Ref<string>;
  canRequest: ComputedRef<boolean>;
  restoreDoorState: (taskId: number) => void;
  syncDoorStateFromServer: (taskId: number) => Promise<void>;
  /** 打开详情时再拉 SKU 图/条码目录（勿在列表 onShow 全量 pricing）。 */
  ensureSkuCatalog?: () => Promise<void>;
}) {
  /** Deep-link query applied once; cleared so onShow/load won't reopen the same task. */
  let pendingDeepLink = false;

  function readHashQuery(key: string): string | undefined {
    if (typeof location === 'undefined') return undefined;
    const m = location.hash.match(new RegExp(`[?&]${key}=([^&]+)`));
    return m ? decodeURIComponent(m[1]) : undefined;
  }

  function applyRouteQuery(routeOpts?: Record<string, string | undefined>) {
    const deviceId = routeOpts?.deviceId || readHashQuery('deviceId');
    const taskIdRaw = routeOpts?.taskId || readHashQuery('taskId');
    let changed = false;
    if (deviceId) {
      opts.filterDeviceId.value = String(deviceId).trim().toUpperCase();
      changed = true;
    }
    if (taskIdRaw) {
      const id = Number(taskIdRaw);
      if (Number.isFinite(id) && id > 0) {
        opts.focusTaskId.value = id;
        changed = true;
      }
    }
    if (deviceId || taskIdRaw) {
      opts.status.value = '';
    }
    if (changed) pendingDeepLink = true;
  }

  /** Strip deviceId/taskId from H5 hash so back/onShow won't re-apply the deep link. */
  function clearDeepLinkQuery() {
    pendingDeepLink = false;
    opts.focusTaskId.value = null;
    if (typeof location === 'undefined' || typeof history === 'undefined') return;
    const hash = location.hash || '';
    const qIndex = hash.indexOf('?');
    if (qIndex < 0) return;
    const path = hash.slice(0, qIndex);
    history.replaceState(null, '', `${location.pathname}${location.search}${path}`);
  }

  function findDeepLinkTaskById(): Task | undefined {
    if (!opts.focusTaskId.value) return undefined;
    const open = opts.allTasks.value.find(
      (t) => t.taskId === opts.focusTaskId.value && t.status !== 'CANCELLED'
    );
    opts.focusTaskId.value = null;
    return open;
  }

  function findDeepLinkTaskByDevice(): Task | undefined {
    if (opts.detailVisible.value || !opts.filterDeviceId.value) return undefined;
    const key = opts.filterDeviceId.value.trim().toUpperCase();
    return opts.allTasks.value.find(
      (t) =>
        String(t.deviceId || '')
          .trim()
          .toUpperCase() === key &&
        t.status !== 'COMPLETED' &&
        t.status !== 'CANCELLED'
    );
  }

  function resolveDeepLinkOpenTask(): Task | undefined {
    if (!pendingDeepLink) return undefined;
    return findDeepLinkTaskById() || findDeepLinkTaskByDevice();
  }

  function prepareTaskDetailSheet(task: Task) {
    const fromList = opts.allTasks.value.find((t) => t.taskId === task.taskId);
    opts.selected.value = { ...(fromList || task) };
    opts.sheetCloseArmed.value = false;
    opts.detailVisible.value = true;
    opts.linesConfirmed.value = opts.selected.value.status === 'COMPLETED';
    opts.evidenceItems.value = [];
    const taskId = opts.selected.value.taskId;
    if (typeof taskId === 'number') opts.restoreDoorState(taskId);
    opts.detailLoading.value = true;
    opts.slotCaps.value = {};
    opts.deviceSlotsList.value = [];
  }

  async function refreshSelectedTask(task: Task) {
    try {
      const latest = (await merchantApi.replenishmentTasks()) as Task[];
      opts.allTasks.value = latest;
      const fresh = latest.find((t) => t.taskId === task.taskId);
      if (fresh) opts.selected.value = { ...fresh };
    } catch {
      /* keep selected */
    }
  }

  async function mapEvidenceFiles(task: Task, evidence: { fileId?: number; url?: string }[]) {
    return Promise.all(
      (evidence || []).map(async (f) => {
        const fileId = f.fileId;
        if (!fileId) return { localPath: f.url || '', fileId };
        try {
          const localPath = await merchantApi.downloadReplenishmentEvidence(task.taskId!, fileId);
          return { localPath, fileId };
        } catch {
          return { localPath: f.url || '', fileId };
        }
      })
    );
  }

  async function loadTaskDetailResources(task: Task) {
    const taskId = task.taskId;
    if (typeof taskId !== 'number') return;
    const [taskLines, slots, evidence] = await Promise.all([
      merchantApi.replenishmentTaskLines(taskId) as Promise<Line[]>,
      softFallback(merchantApi.deviceSlots(task.deviceId!), [] as DeviceSlot[]),
      softFallback(merchantApi.listReplenishmentEvidence(taskId), [])
    ]);
    opts.lines.value = taskLines;
    opts.deviceSlotsList.value = (slots || []) as DeviceSlot[];
    const mapped = await mapEvidenceFiles(task, evidence || []);
    opts.evidenceItems.value = mapped;
    opts.evidenceCountMap.value = {
      ...opts.evidenceCountMap.value,
      [taskId]: mapped.length
    };
    opts.slotCaps.value = buildSlotCapsFromSlots(opts.deviceSlotsList.value);
    await opts.syncDoorStateFromServer(taskId);
  }

  async function openTask(task: Task) {
    prepareTaskDetailSheet(task);
    await nextTick();
    setTimeout(() => {
      opts.sheetCloseArmed.value = true;
    }, 280);
    try {
      await Promise.all([
        opts.ensureSkuCatalog?.() ?? Promise.resolve(),
        (async () => {
          await refreshSelectedTask(task);
          await loadTaskDetailResources(task);
        })()
      ]);
    } catch (error) {
      showError(error instanceof Error ? error.message : '明细加载失败');
    } finally {
      opts.detailLoading.value = false;
    }
  }

  async function handleDeepLinkAfterLoad(open: Task | undefined, wantedTaskId: number | null) {
    if (pendingDeepLink) {
      clearDeepLinkQuery();
    }
    if (open) {
      await openTask(open);
    } else if (wantedTaskId) {
      showError(`任务 #${wantedTaskId} 不可用或已取消`);
    }
  }

  async function addEvidence() {
    const task = opts.selected.value;
    const taskId = task?.taskId;
    if (!task || typeof taskId !== 'number' || !opts.canRequest.value) return;
    if (!task.checkInAt) {
      showError('请先签到再拍照');
      return;
    }
    if (opts.evidenceItems.value.length >= 5) {
      showError('最多 5 张');
      return;
    }
    const paths = await new Promise<string[]>((resolve) => {
      uni.chooseImage({
        count: 5 - opts.evidenceItems.value.length,
        sizeType: ['compressed'],
        sourceType: ['album', 'camera'],
        success: (res) => {
          const raw = res.tempFilePaths || [];
          resolve(Array.isArray(raw) ? raw : [raw]);
        },
        fail: () => resolve([])
      });
    });
    for (const path of paths) {
      try {
        await assertLocalImageSize(path);
        const uploaded = await merchantApi.uploadReplenishmentEvidence(taskId, path);
        opts.evidenceItems.value.push({ localPath: path, fileId: uploaded.fileId });
        opts.evidenceCountMap.value = {
          ...opts.evidenceCountMap.value,
          [taskId]: opts.evidenceItems.value.length
        };
      } catch (e) {
        showError(e instanceof Error ? e.message : '上传失败');
        break;
      }
    }
  }

  function previewEvidence(index: number) {
    const urls = opts.evidenceItems.value.map((i) => i.localPath).filter(Boolean);
    if (!urls.length) return;
    uni.previewImage({ urls, current: urls[index] || urls[0] });
  }

  function closeDetail() {
    if (!opts.sheetCloseArmed.value) return;
    if (!opts.submitting.value) {
      opts.detailVisible.value = false;
      opts.sheetCloseArmed.value = false;
      clearDeepLinkQuery();
    }
  }

  return {
    applyRouteQuery,
    clearDeepLinkQuery,
    resolveDeepLinkOpenTask,
    handleDeepLinkAfterLoad,
    openTask,
    addEvidence,
    previewEvidence,
    closeDetail
  };
}
