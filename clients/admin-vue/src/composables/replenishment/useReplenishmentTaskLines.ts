import { computed, ref, watch, type ComputedRef, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api, authFetch } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import type { createLoadSeq } from '@/composables/createLoadSeq';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

export type ReplenishmentTaskLineRow = AdminDynamicRow;

export type TaskEvidenceFile = {
  fileId: number;
  fileName?: string;
  fileSize?: number;
  contentType?: string;
  previewUrl?: string;
};

export type UseReplenishmentTaskLinesDeps = {
  canEdit: ComputedRef<boolean> | Ref<boolean>;
  loadSeq: ReturnType<typeof createLoadSeq>;
};

/**
 * 理货明细抽屉：明细加载 / 货道分配 / 现场证预览 / 待分配 hint。
 * 从 ReplenishmentView 抽出（debt-tracker D13）。
 */
export function useReplenishmentTaskLines(deps: UseReplenishmentTaskLinesDeps) {
  const linesDrawer = ref(false);
  const linesLoading = ref(false);
  const slotSaving = ref(false);
  const linesTask = ref<ReplenishmentTaskLineRow | null>(null);
  const taskLines = ref<ReplenishmentTaskLineRow[]>([]);
  const deviceSlots = ref<ReplenishmentTaskLineRow[]>([]);
  const taskUnassignedHint = ref<Record<number, boolean>>({});
  const taskEvidence = ref<TaskEvidenceFile[]>([]);
  const evidenceObjectUrls = ref<string[]>([]);

  const linesDrawerTitle = computed(() =>
    linesTask.value?.taskId ? `理货明细 · 任务 ${linesTask.value.taskId}` : '理货明细'
  );
  const restockQtyTotal = computed(() =>
    taskLines.value
      .filter((l) => String(l.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK')
      .reduce((sum, l) => sum + (Number(l.quantity) || 0), 0)
  );
  const editableRestockLines = computed(() =>
    taskLines.value.filter(
      (l) =>
        !l.applied &&
        String(l.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK' &&
        String(linesTask.value?.status || '') !== 'COMPLETED'
    )
  );
  const editablePendingLines = computed(() =>
    taskLines.value.filter(
      (l) => !l.applied && String(linesTask.value?.status || '') !== 'COMPLETED'
    )
  );
  const unassignedRestockCount = computed(
    () => editableRestockLines.value.filter((l) => !String(l.slotId || '').trim()).length
  );

  function isRestockLine(row: ReplenishmentTaskLineRow) {
    return String(row.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK';
  }

  function canAssignSlot(row: ReplenishmentTaskLineRow) {
    return (
      deps.canEdit.value &&
      !!linesTask.value &&
      String(linesTask.value.status || '') !== 'COMPLETED' &&
      !row.applied &&
      isRestockLine(row)
    );
  }

  function slotRoom(slot: ReplenishmentTaskLineRow) {
    const maxLevel = Number(slot.maxLevel) || 0;
    const bookQty = Number(slot.bookQty) || 0;
    if (maxLevel <= 0) return 99;
    return Math.max(0, maxLevel - bookQty);
  }

  function slotOptionsForLine(row: ReplenishmentTaskLineRow) {
    const skuId = String(row.skuId || '');
    return deviceSlots.value
      .filter((s) => s.enabled !== false)
      .filter((s) => !s.assignedSkuId || String(s.assignedSkuId) === skuId)
      .map((s) => ({
        slotCode: String(s.slotCode || '').toUpperCase(),
        room: slotRoom(s)
      }))
      .filter((s) => !!s.slotCode)
      .sort((a, b) => b.room - a.room || a.slotCode.localeCompare(b.slotCode));
  }

  function onSlotAssign(row: ReplenishmentTaskLineRow, slotCode: string | null | undefined) {
    const code = String(slotCode || '')
      .trim()
      .toUpperCase();
    row.slotId = code || undefined;
    if (!code) return;
    const opt = slotOptionsForLine(row).find((o) => o.slotCode === code);
    if (opt && Number(row.quantity) > opt.room) {
      row.quantity = opt.room;
      ElMessage.info(`已按货道余量调至 ${opt.room}`);
    }
  }

  function formatFileSize(size?: number) {
    if (size == null || size <= 0) return '无';
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  }

  function revokeEvidencePreviews() {
    for (const url of evidenceObjectUrls.value) {
      URL.revokeObjectURL(url);
    }
    evidenceObjectUrls.value = [];
  }

  async function loadEvidencePreviews(taskId: number, files: TaskEvidenceFile[]) {
    const seq = deps.loadSeq.begin('loadTaskEvidence');
    const base = globalThis.location.origin;
    const next: TaskEvidenceFile[] = [];
    const urls: string[] = [];
    for (const f of files) {
      const item = { ...f };
      const looksImage =
        String(f.contentType || '').startsWith('image/') ||
        /\.(png|jpe?g|gif|webp|bmp)$/i.test(String(f.fileName || ''));
      if (looksImage) {
        try {
          const res = await authFetch(
            `${base}${AdminEndpoints.replenishmentTaskEvidenceFile(taskId, f.fileId)}`
          );
          if (res.ok) {
            const blob = await res.blob();
            if (!deps.loadSeq.isCurrent(seq, 'loadTaskEvidence')) return;
            const url = URL.createObjectURL(blob);
            urls.push(url);
            item.previewUrl = url;
          }
        } catch {
          if (!deps.loadSeq.isCurrent(seq, 'loadTaskEvidence')) return;
          /* list-only fallback */
        }
      }
      next.push(item);
    }
    evidenceObjectUrls.value = urls;
    taskEvidence.value = next;
  }

  function openEvidencePreview(f: { previewUrl?: string; fileName?: string }) {
    if (!f.previewUrl) return;
    globalThis.open(f.previewUrl, '_blank');
  }

  async function openTaskLines(task: ReplenishmentTaskLineRow) {
    if (!task?.taskId) return;
    linesTask.value = task;
    taskLines.value = [];
    deviceSlots.value = [];
    revokeEvidencePreviews();
    taskEvidence.value = [];
    linesDrawer.value = true;
    linesLoading.value = true;
    try {
      const [lines, evidence, slots] = await Promise.all([
        api.request<ReplenishmentTaskLineRow[]>(
          AdminEndpoints.replenishmentTaskLines(task.taskId),
          'GET'
        ),
        api
          .request<TaskEvidenceFile[]>(AdminEndpoints.replenishmentTaskEvidence(task.taskId), 'GET')
          .catch(() => []),
        task.deviceId
          ? api
              .request<ReplenishmentTaskLineRow[]>(
                AdminEndpoints.deviceSlots(String(task.deviceId)),
                'GET'
              )
              .catch(() => [])
          : Promise.resolve([])
      ]);
      taskLines.value = (lines || []).map((l) => ({ ...l }));
      deviceSlots.value = slots || [];
      taskEvidence.value = evidence || [];
      const unassigned = (lines || []).some(
        (l) =>
          !l.applied &&
          String(l.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK' &&
          !String(l.slotId || '').trim()
      );
      taskUnassignedHint.value = {
        ...taskUnassignedHint.value,
        [Number(task.taskId)]: unassigned && String(task.status) !== 'COMPLETED'
      };
      await loadEvidencePreviews(Number(task.taskId), taskEvidence.value);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '理货明细加载失败');
    } finally {
      linesLoading.value = false;
    }
  }

  async function saveTaskSlots() {
    if (!linesTask.value?.taskId || slotSaving.value) return;
    if (unassignedRestockCount.value) {
      ElMessage.warning('请先为待分配行选择货道');
      return;
    }
    const pending = editablePendingLines.value;
    if (!pending.length) {
      ElMessage.info('没有可保存的明细行');
      return;
    }
    slotSaving.value = true;
    try {
      const saved = await api.request<ReplenishmentTaskLineRow[]>(
        AdminEndpoints.replenishmentTaskLines(linesTask.value.taskId),
        'POST',
        {
          lines: pending.map((l) => ({
            lineType: l.lineType || 'RESTOCK',
            skuId: l.skuId,
            batchNo: l.batchNo || null,
            productionDate: l.productionDate || null,
            expiryDate: l.expiryDate || null,
            quantity: Number(l.quantity) || 0,
            slotId:
              String(l.slotId || '')
                .trim()
                .toUpperCase() || null,
            applied: false
          }))
        }
      );
      taskLines.value = saved || [];
      taskUnassignedHint.value = {
        ...taskUnassignedHint.value,
        [Number(linesTask.value.taskId)]: false
      };
      ElMessage.success('货道已保存');
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存货道失败');
    } finally {
      slotSaving.value = false;
    }
  }

  /** 履约列表打开后预取待分配 hint（最多 24 条开放任务） */
  async function prefetchUnassignedHints(openTasks: ReplenishmentTaskLineRow[]) {
    const open = openTasks
      .filter((t) => {
        const st = String(t.status || '');
        return t.taskId && st !== 'COMPLETED' && st !== 'CANCELLED';
      })
      .slice(0, 24);
    if (!open.length) return;
    const next: Record<number, boolean> = { ...taskUnassignedHint.value };
    const chunkSize = 6;
    for (let i = 0; i < open.length; i += chunkSize) {
      const chunk = open.slice(i, i + chunkSize);
      await Promise.all(
        chunk.map(async (task) => {
          const taskId = Number(task.taskId);
          try {
            const lines = await api.request<ReplenishmentTaskLineRow[]>(
              AdminEndpoints.replenishmentTaskLines(taskId),
              'GET'
            );
            next[taskId] = (lines || []).some(
              (l) =>
                !l.applied &&
                String(l.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK' &&
                !String(l.slotId || '').trim()
            );
          } catch {
            /* keep previous hint */
          }
        })
      );
    }
    taskUnassignedHint.value = next;
  }

  watch(linesDrawer, (open) => {
    if (!open) revokeEvidencePreviews();
  });

  return {
    linesDrawer,
    linesLoading,
    slotSaving,
    linesTask,
    taskLines,
    deviceSlots,
    taskUnassignedHint,
    taskEvidence,
    linesDrawerTitle,
    restockQtyTotal,
    editablePendingLines,
    unassignedRestockCount,
    isRestockLine,
    canAssignSlot,
    slotOptionsForLine,
    onSlotAssign,
    formatFileSize,
    openEvidencePreview,
    openTaskLines,
    saveTaskSlots,
    prefetchUnassignedHints
  };
}
