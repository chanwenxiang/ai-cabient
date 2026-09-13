import { reactive, ref, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { errorMessage } from '@/utils/error-message';

/** 仓储多 Tab 共用行（字段随业务表变化） */
export type WarehouseOutboundRow = Record<string, any>;

export type OutboundConfirmAction = 'pick' | 'ship' | 'cancel-unreceived';

export type UseWarehouseOutboundsDeps = {
  loadedTabs: Ref<Set<string>>;
  loadTab: (name: string, force?: boolean) => Promise<void>;
};

/**
 * 出库写路径：拣货/发运/作废确认弹窗、清理空草稿。
 * 列表加载与筛选项仍留在 WarehouseView。
 */
export function useWarehouseOutbounds(deps: UseWarehouseOutboundsDeps) {
  const cleanupStaleLoading = ref(false);
  const outboundConfirm = reactive({
    visible: false,
    saving: false,
    title: '',
    message: '',
    action: 'pick' as OutboundConfirmAction,
    outboundId: null as number | string | null
  });

  function changeOutbound(row: WarehouseOutboundRow, action: OutboundConfirmAction) {
    outboundConfirm.action = action;
    outboundConfirm.outboundId = row.outboundId;
    if (action === 'pick') {
      outboundConfirm.title = '确认拣货';
      outboundConfirm.message = `确认出库单 ${row.outboundId} 已完成拣货？`;
    } else if (action === 'ship') {
      outboundConfirm.title = '确认发运';
      outboundConfirm.message = `确认发运出库单 ${row.outboundId}？发运后库存将转为在途。`;
    } else {
      outboundConfirm.title = row.status === 'SHIPPED' ? '作废回仓' : '作废出库';
      outboundConfirm.message =
        row.status === 'SHIPPED'
          ? `确认作废出库单 ${row.outboundId}？将回仓并取消在途（仅未签收）。`
          : `确认作废出库单 ${row.outboundId}？未发运单据将直接取消。`;
    }
    outboundConfirm.saving = false;
    outboundConfirm.visible = true;
  }

  function cancelOutboundConfirm() {
    outboundConfirm.visible = false;
  }

  function onOutboundConfirmClosed() {
    if (!outboundConfirm.saving) {
      outboundConfirm.outboundId = null;
    }
  }

  async function submitOutboundConfirm() {
    const action = outboundConfirm.action;
    const outboundId = outboundConfirm.outboundId;
    if (outboundId == null) {
      outboundConfirm.visible = false;
      return;
    }
    outboundConfirm.saving = true;
    try {
      await api.request(`/api/v2/ops/admin/warehouse/outbounds/${outboundId}/${action}`, 'POST');
      outboundConfirm.visible = false;
      let okMsg: string;
      if (action === 'pick') okMsg = '拣货完成';
      else if (action === 'ship') okMsg = '已发运';
      else okMsg = '出库单已作废';
      ElMessage.success(okMsg);
      deps.loadedTabs.value.delete('outbounds');
      deps.loadedTabs.value.delete('transit');
      deps.loadedTabs.value.delete('inventory');
      await deps.loadTab('outbounds', true);
    } catch (e: unknown) {
      ElMessage.error(errorMessage(e, '操作失败'));
    } finally {
      outboundConfirm.saving = false;
      outboundConfirm.outboundId = null;
    }
  }

  async function cleanupStaleOutbounds() {
    try {
      await ElMessageBox.confirm(
        '将安全作废：空草稿/已拣货、终态路线上的未发运草稿、终态路线上未签收且无已完成任务的发运单（回仓并取消在途）。不硬删业务行；已签收或任务已完成的单据跳过。',
        '清理空草稿/脏在途',
        { type: 'warning', confirmButtonText: '确认清理', appendTo: document.body }
      );
    } catch {
      return;
    }
    cleanupStaleLoading.value = true;
    try {
      const result = await api.request<{
        cancelledEmptyDrafts?: number;
        cancelledTerminalDrafts?: number;
        cancelledOrphanShipped?: number;
        skipped?: number;
        cancelledOutboundIds?: number[];
      }>('/api/v2/ops/admin/warehouse/outbounds/cleanup-stale', 'POST');
      const total =
        (result?.cancelledEmptyDrafts || 0) +
        (result?.cancelledTerminalDrafts || 0) +
        (result?.cancelledOrphanShipped || 0);
      ElMessage.success({
        message: total
          ? `已清理 ${total} 单（空草稿 ${result?.cancelledEmptyDrafts || 0} / 终态草稿 ${result?.cancelledTerminalDrafts || 0} / 孤儿发运 ${result?.cancelledOrphanShipped || 0}），跳过 ${result?.skipped || 0}`
          : `无可清理单据（跳过 ${result?.skipped || 0}）`,
        duration: 5000
      });
      deps.loadedTabs.value.delete('outbounds');
      deps.loadedTabs.value.delete('transit');
      deps.loadedTabs.value.delete('inventory');
      await deps.loadTab('outbounds', true);
    } catch (e: unknown) {
      ElMessage.error(errorMessage(e, '清理失败'));
    } finally {
      cleanupStaleLoading.value = false;
    }
  }

  return {
    cleanupStaleLoading,
    outboundConfirm,
    changeOutbound,
    cancelOutboundConfirm,
    onOutboundConfirmClosed,
    submitOutboundConfirm,
    cleanupStaleOutbounds
  };
}
