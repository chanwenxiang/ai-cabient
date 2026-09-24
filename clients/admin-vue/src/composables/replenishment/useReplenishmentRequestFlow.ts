import { computed, ref, watch, type ComputedRef, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, authFetch } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import type { createLoadSeq } from '@/composables/createLoadSeq';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { errorMessage, isUserDismiss } from '@/utils/error-message';
import { displayLabel } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import type { TableAction } from '@/components/TableActions.vue';
import { Check, Close, View } from '@element-plus/icons-vue';

export type ReplenishmentRequestRow = AdminDynamicRow;

export type UseReplenishmentRequestFlowDeps = {
  canEdit: ComputedRef<boolean> | Ref<boolean>;
  allRequests: Ref<ReplenishmentRequestRow[]>;
  deviceName: (deviceId?: string, snapshot?: string | null) => string;
  reloadCurrentTab: () => Promise<void>;
  loadSeq: ReturnType<typeof createLoadSeq>;
  /** 跳转并打开关联补货任务理货抽屉（View 持有路线/明细状态） */
  openLinkedTask: (taskId: number | string) => Promise<void>;
};

/**
 * 要货审批流：抽屉状态 / 接单驳回 / 附图预览。
 * 从 ReplenishmentView 抽出（debt-tracker D13）。
 */
export function useReplenishmentRequestFlow(deps: UseReplenishmentRequestFlowDeps) {
  const requestFlowDrawer = ref(false);
  const requestFlowRow = ref<ReplenishmentRequestRow | null>(null);
  const requestEvidence = ref<
    {
      fileId: number;
      fileName?: string;
      fileSize?: number;
      contentType?: string;
      url?: string;
      previewUrl?: string;
    }[]
  >([]);
  const requestEvidenceObjectUrls = ref<string[]>([]);

  const requestActions: TableAction[] = [
    { key: 'accept', label: '接单', icon: Check, type: 'primary' },
    { key: 'reject', label: '驳回', icon: Close, type: 'danger' }
  ];

  function formatRequestLines(row: ReplenishmentRequestRow) {
    const lines = (row.lines || []) as {
      skuName?: string;
      skuId?: string;
      requestedQty?: number;
    }[];
    if (!lines.length) return '无明细';
    return lines.map((l) => `${l.skuName || l.skuId || '无'}×${l.requestedQty ?? 0}`).join('、');
  }

  function requestActionsFor(row: ReplenishmentRequestRow): TableAction[] {
    const acts: TableAction[] = [{ key: 'flow', label: '审批流', icon: View, type: 'info' }];
    if (deps.canEdit.value && row.status === 'SUBMITTED') {
      acts.push(...requestActions);
    }
    if (row.replenishmentTaskId && (row.status === 'ACCEPTED' || row.status === 'COMPLETED')) {
      acts.push({ key: 'view-task', label: '查看任务', icon: View, type: 'primary' });
    }
    return acts;
  }

  const showRequestActionColumn = computed(() =>
    deps.allRequests.value.some((row) => requestActionsFor(row).length > 0)
  );

  const requestFlowTitle = computed(() =>
    requestFlowRow.value?.requestId
      ? `审批流 · 要货 ${requestFlowRow.value.requestId}`
      : '审批流'
  );

  const requestFlowActiveStep = computed(() => {
    const status = String(requestFlowRow.value?.status || '');
    if (status === 'SUBMITTED') return 0;
    if (status === 'REJECTED') return 1;
    if (status === 'ACCEPTED') return 2;
    if (status === 'COMPLETED') return 3;
    return 0;
  });

  const requestFlowProcessStatus = computed(() =>
    String(requestFlowRow.value?.status || '') === 'REJECTED' ? 'error' : 'process'
  );

  const requestFlowSubmitDesc = computed(() => {
    const row = requestFlowRow.value;
    if (!row) return '';
    const who = row.createdByName || row.createdBy || '商户';
    const when = row.submittedAt || row.createdAt;
    return when ? `${who}\n${formatDateTime(String(when))}` : String(who);
  });

  const requestFlowReviewDesc = computed(() => {
    const row = requestFlowRow.value;
    if (!row) return '';
    const status = String(row.status || '');
    if (status === 'SUBMITTED') return '等待运营接单/驳回';
    const who = row.reviewerName || row.reviewerId || '审核人';
    const result =
      status === 'REJECTED'
        ? displayLabel('replenishment_request_status', 'REJECTED')
        : displayLabel('replenishment_request_status', 'ACCEPTED');
    const when = row.reviewedAt ? formatDateTime(String(row.reviewedAt)) : '';
    return when ? `${who} · ${result}\n${when}` : `${who} · ${result}`;
  });

  const requestFlowFulfillDesc = computed(() => {
    const row = requestFlowRow.value;
    if (!row) return '';
    const status = String(row.status || '');
    if (status === 'REJECTED') return '已终止';
    if (status === 'SUBMITTED') return '审核通过后生成补货任务';
    if (row.replenishmentTaskId) {
      return status === 'COMPLETED'
        ? `任务 ${row.replenishmentTaskId} · 已完成`
        : `任务 ${row.replenishmentTaskId} · 履约中`;
    }
    return '待生成补货任务';
  });

  function revokeRequestEvidenceUrls() {
    for (const url of requestEvidenceObjectUrls.value) {
      URL.revokeObjectURL(url);
    }
    requestEvidenceObjectUrls.value = [];
    requestEvidence.value = [];
  }

  async function loadRequestEvidence(row: ReplenishmentRequestRow) {
    const seq = deps.loadSeq.begin('loadRequestEvidence');
    revokeRequestEvidenceUrls();
    const requestId = Number(row.requestId);
    if (!requestId) return;
    try {
      const files = await api.request<
        {
          fileId: number;
          fileName?: string;
          fileSize?: number;
          contentType?: string;
          url?: string;
        }[]
      >(AdminEndpoints.replenishmentRequestEvidence(requestId), 'GET');
      if (!deps.loadSeq.isCurrent(seq, 'loadRequestEvidence')) return;
      if (!files?.length) return;
      const base = globalThis.location.origin;
      const urls: string[] = [];
      const mapped = await Promise.all(
        files.map(async (f) => {
          const fileId = Number(f.fileId);
          const item = { ...f, previewUrl: f.url };
          const looksImage =
            String(f.contentType || '').startsWith('image/') ||
            /\.(png|jpe?g|gif|webp|bmp)$/i.test(String(f.fileName || ''));
          if (looksImage) {
            try {
              const res = await authFetch(
                `${base}${AdminEndpoints.replenishmentRequestEvidenceFile(requestId, fileId)}`
              );
              if (res.ok) {
                const blob = await res.blob();
                if (!deps.loadSeq.isCurrent(seq, 'loadRequestEvidence')) return item;
                const objectUrl = URL.createObjectURL(blob);
                urls.push(objectUrl);
                item.previewUrl = objectUrl;
              }
            } catch {
              /* list-only fallback */
            }
          }
          return item;
        })
      );
      if (!deps.loadSeq.isCurrent(seq, 'loadRequestEvidence')) return;
      requestEvidenceObjectUrls.value = urls;
      requestEvidence.value = mapped;
    } catch {
      if (!deps.loadSeq.isCurrent(seq, 'loadRequestEvidence')) return;
      requestEvidence.value = [];
    }
  }

  function openRequestFlow(row: ReplenishmentRequestRow) {
    requestFlowRow.value = row;
    requestFlowDrawer.value = true;
    void loadRequestEvidence(row);
  }

  function refreshRequestFlowDrawer(row: ReplenishmentRequestRow) {
    if (!requestFlowDrawer.value || requestFlowRow.value?.requestId !== row.requestId) return;
    const updated = deps.allRequests.value.find((r) => r.requestId === row.requestId);
    if (updated) requestFlowRow.value = updated;
    else requestFlowDrawer.value = false;
  }

  async function acceptReplenishmentRequest(row: ReplenishmentRequestRow) {
    const linesPreview = formatRequestLines(row);
    await ElMessageBox.confirm(
      `确认接单要货 ${row.requestId}？\n设备：${deps.deviceName(row.deviceId as string | undefined, row.deviceName as string | null | undefined)}（${row.deviceId}）\n明细：${linesPreview}`,
      '接单',
      { type: 'warning', confirmButtonText: '确认接单' }
    );
    const accepted = await api.request<{
      requestId?: number;
      outboundId?: number | null;
      replenishmentTaskId?: number | null;
      reviewerId?: number;
      reviewerName?: string;
      reviewedAt?: string;
      status?: string;
    }>(AdminEndpoints.replenishmentRequestAccept(row.requestId as string | number), 'POST');
    if (accepted?.outboundId) {
      ElMessage.success(
        `已接单，出库 ${accepted.outboundId}，补货任务 ${accepted.replenishmentTaskId ?? '无'}`
      );
    } else {
      ElMessage.success(
        `已接单，无仓配库存，已建现场补货任务 ${accepted?.replenishmentTaskId ?? '无'}`
      );
    }
  }

  async function rejectReplenishmentRequest(row: ReplenishmentRequestRow) {
    const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回要货', {
      inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
      confirmButtonText: '确认驳回',
      type: 'warning'
    });
    await api.request(
      AdminEndpoints.replenishmentRequestReject(row.requestId as string | number),
      'POST',
      { reason: value }
    );
    ElMessage.success(displayLabel('replenishment_request_status', 'REJECTED'));
  }

  async function onRequestAction(row: ReplenishmentRequestRow, key: string) {
    try {
      if (key === 'flow') {
        openRequestFlow(row);
        return;
      }
      if (key === 'view-task') {
        const taskId = row.replenishmentTaskId;
        if (!taskId) {
          ElMessage.info('该要货尚未关联补货任务');
          return;
        }
        await deps.openLinkedTask(taskId as string | number);
        return;
      }
      if (key === 'accept') {
        await acceptReplenishmentRequest(row);
      } else if (key === 'reject') {
        await rejectReplenishmentRequest(row);
      }
      await deps.reloadCurrentTab();
      refreshRequestFlowDrawer(row);
    } catch (e: unknown) {
      if (!isUserDismiss(e)) ElMessage.error(errorMessage(e, '操作失败'));
    }
  }

  function onRequestRowAction({ key, row }: { key: string; row: ReplenishmentRequestRow }) {
    void onRequestAction(row, key);
  }

  watch(requestFlowDrawer, (open) => {
    if (!open) revokeRequestEvidenceUrls();
  });

  return {
    requestFlowDrawer,
    requestFlowRow,
    requestEvidence,
    requestActionsFor,
    showRequestActionColumn,
    requestFlowTitle,
    requestFlowActiveStep,
    requestFlowProcessStatus,
    requestFlowSubmitDesc,
    requestFlowReviewDesc,
    requestFlowFulfillDesc,
    formatRequestLines,
    openRequestFlow,
    onRequestAction,
    onRequestRowAction
  };
}
