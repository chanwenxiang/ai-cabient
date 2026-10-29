import { reactive, ref, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

/** 仓储动态行（D15：禁止散落 Record<string, any>） */
export type WarehouseOutboundRow = AdminDynamicRow;

export type OutboundConfirmAction = 'pick' | 'ship' | 'cancel-unreceived';

export type UseWarehouseOutboundsDeps = {
  loadedTabs: Ref<Set<string>>;
  loadTab: (name: string, force?: boolean) => Promise<void>;
};

/**
 * 出库写路径：拣货/领货/作废确认弹窗、清理空草稿。
 * 🔴 铁律 80：仓→柜是补货员自提自送，动词用「领货 / 上柜」，不写发运、签收、在途。
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
      outboundConfirm.title = '确认领货出库';
      outboundConfirm.message = `确认出库单 ${row.outboundId} 已由补货员领出？领出后这批货记为「已领未上柜」，补货员在柜机确认后才进柜机库存。`;
    } else {
      outboundConfirm.title = row.status === 'SHIPPED' ? '作废回仓' : '作废出库';
      outboundConfirm.message =
        row.status === 'SHIPPED'
          ? `确认作废出库单 ${row.outboundId}？货物回仓并结清未上柜行（仅还没全部上柜的单据可作废）。`
          : `确认作废出库单 ${row.outboundId}？还没领货的单据直接取消。`;
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
      await api.request(AdminEndpoints.warehouseOutboundAction(outboundId, action), 'POST');
      outboundConfirm.visible = false;
      let okMsg: string;
      if (action === 'pick') okMsg = '拣货完成';
      else if (action === 'ship') okMsg = '已领出';
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
        '将安全作废：空草稿/已拣货、终态路线上未领货的草稿、终态路线上未全部上柜且无已完成任务的领货单（回仓并结清未上柜行）。不硬删业务行；已上柜结清或任务已完成的单据跳过。',
        '清理空草稿/未结领货',
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
        cancelledPlannedNoTask?: number;
        skipped?: number;
        cancelledOutboundIds?: number[];
      }>(AdminEndpoints.warehouseOutboundsCleanupStale, 'POST');
      const total =
        (result?.cancelledEmptyDrafts || 0) +
        (result?.cancelledTerminalDrafts || 0) +
        (result?.cancelledOrphanShipped || 0) +
        (result?.cancelledPlannedNoTask || 0);
      ElMessage.success({
        message: total
          ? `已清理 ${total} 单（空草稿 ${result?.cancelledEmptyDrafts || 0} / 终态草稿 ${result?.cancelledTerminalDrafts || 0} / 未结领货 ${result?.cancelledOrphanShipped || 0} / 计划无任务 ${result?.cancelledPlannedNoTask || 0}），跳过 ${result?.skipped || 0}`
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
