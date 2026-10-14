import { computed, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import type {
  OpenApiReplenishmentRouteDto,
  OpenApiReplenishmentTaskDto
} from '@aicabinet/shared-types';

/** 补货柜机参照（列表/开门在线态） */
export type ReplenishmentDeviceRef = {
  deviceId?: string;
  deviceName?: string;
  onlineStatus?: string;
  salesLocked?: boolean;
};

export type ReplenishmentTaskRow = OpenApiReplenishmentTaskDto;
export type ReplenishmentRouteRow = OpenApiReplenishmentRouteDto;
/** @deprecated 用 Task/Route/DeviceRef；保留别名兼容旧 import */
export type ReplenishmentRow =
  ReplenishmentTaskRow | ReplenishmentRouteRow | ReplenishmentDeviceRef;

export type UseReplenishmentTaskActionsDeps = {
  devices: Ref<ReplenishmentDeviceRef[]>;
  routes: Ref<ReplenishmentRouteRow[]>;
  canEdit: ComputedRef<boolean> | Ref<boolean>;
  deviceName: (deviceId?: string, snapshot?: string | null) => string;
  /** 动作成功后刷新当前 Tab（含 summary） */
  reloadCurrentTab: () => Promise<void>;
};

/**
 * 补货履约写动作：补货开门 / 取消空路线。
 * （2026-10-14，b96a74d4 后端下线 admin 代签到/完成上架/任务明细执行链——补货执行统一走
 * 商户端小程序 /merchant/replenishment/tasks/*；admin 仅保留补货开门（活端点）与路线收口。）
 * 从 ReplenishmentView 抽出，降低上帝页 script 体积与误改柜门逻辑风险（debt-tracker D4 / lessons #117）。
 */
export function useReplenishmentTaskActions(deps: UseReplenishmentTaskActionsDeps) {
  const openDoorLoading = ref<number | null>(null);
  const cancelRouteLoading = ref<number | null>(null);

  function deviceOnline(deviceId?: string) {
    if (!deviceId) return false;
    const d = deps.devices.value.find((item) => item.deviceId === deviceId);
    return String(d?.onlineStatus || '').toUpperCase() === 'ONLINE';
  }

  function deviceSalesLocked(deviceId?: string) {
    if (!deviceId) return false;
    const d = deps.devices.value.find((item) => item.deviceId === deviceId);
    return !!(d as { salesLocked?: boolean } | undefined)?.salesLocked;
  }

  function canOpenRestock(task: ReplenishmentTaskRow) {
    if (!task?.taskId || !task?.deviceId) return false;
    if (['COMPLETED', 'CANCELLED'].includes(String(task.status || ''))) return false;
    return !!task.checkInAt && deviceOnline(task.deviceId);
  }

  function openDoorHint(task: ReplenishmentTaskRow) {
    if (['COMPLETED', 'CANCELLED'].includes(String(task.status || ''))) return '无';
    if (!task.checkInAt) return '需先签到';
    if (!deviceOnline(task.deviceId)) return '设备离线';
    if (deviceSalesLocked(task.deviceId)) return '停售中可补货';
    return '无';
  }

  function canCancelEmptyRoute(row: ReplenishmentRouteRow) {
    if (!row?.routeId) return false;
    if (String(row.status || '') === 'COMPLETED') return false;
    // CANCELLED：仍可幂等收口历史脏出库/在途
    if (String(row.status || '') === 'CANCELLED') return true;
    const tasks: ReplenishmentTaskRow[] = row.tasks || [];
    if (!tasks.length) return true;
    return tasks.every((t) => {
      if (['COMPLETED', 'CANCELLED'].includes(String(t.status || ''))) return true;
      return !t.checkInAt;
    });
  }

  const showRouteCancelColumn = computed(
    () => deps.canEdit.value && deps.routes.value.some((row) => canCancelEmptyRoute(row))
  );

  async function cancelEmptyRoute(row: ReplenishmentRouteRow) {
    if (!row?.routeId) return;
    const orphanCleanup = String(row.status || '') === 'CANCELLED';
    try {
      await ElMessageBox.confirm(
        orphanCleanup
          ? `确认收口路线 ${row.routeId} 的脏出库/在途？\n已发运未签收将回仓并取消在途。`
          : `确认取消空路线 ${row.routeId}（${row.routeName || ''}）？\n仅未签到且未交接的任务可取消；已发运未签收会回仓。`,
        orphanCleanup ? '收口脏出库' : '取消空路线',
        { type: 'warning', confirmButtonText: orphanCleanup ? '确认收口' : '确认取消' }
      );
    } catch {
      return;
    }
    cancelRouteLoading.value = row.routeId ?? null;
    try {
      await api.request(AdminEndpoints.replenishmentRouteCancelEmpty(row.routeId), 'POST');
      ElMessage.success(
        orphanCleanup ? `路线 ${row.routeId} 脏出库已收口` : `路线 ${row.routeId} 已取消`
      );
      await deps.reloadCurrentTab();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '取消失败');
    } finally {
      cancelRouteLoading.value = null;
    }
  }

  async function openRestockDoor(task: ReplenishmentTaskRow) {
    if (!task?.checkInAt) {
      ElMessage.warning('请先到店签到后再补货开门');
      return;
    }
    if (!deviceOnline(task.deviceId)) {
      ElMessage.warning(
        `${deps.deviceName(task.deviceId, task.deviceName)} 当前离线，无法下发补货开门`
      );
      return;
    }
    const locked = deviceSalesLocked(task.deviceId);
    try {
      await ElMessageBox.confirm(
        `确认对 ${deps.deviceName(task.deviceId, task.deviceName)}（${task.deviceId}）下发补货开门？\n将绑定任务 ${task.taskId}，不产生消费者账单。\n（与设备详情「远程开门」不同）` +
          (locked
            ? '\n\n注意：该柜当前锁机停售，消费者无法开门；补货开门仅供上架，完成后请视情况解锁恢复售卖。'
            : ''),
        locked ? '补货开门（停售中）' : '补货开门',
        { type: 'warning', confirmButtonText: '开门' }
      );
    } catch {
      return;
    }
    openDoorLoading.value = task.taskId ?? null;
    try {
      const session = await api.request<{ sessionId?: string }>(
        AdminEndpoints.restockOpenDoor,
        'POST',
        { deviceId: task.deviceId, taskId: task.taskId }
      );
      ElMessage.success({
        message: session?.sessionId ? `开门已下发（${session.sessionId}）` : '开门指令已下发',
        duration: 4000
      });
      await deps.reloadCurrentTab();
    } catch (error) {
      ElMessage.error({
        message: error instanceof Error ? error.message : '开门失败',
        duration: 5000
      });
    } finally {
      openDoorLoading.value = null;
    }
  }

  return {
    openDoorLoading,
    cancelRouteLoading,
    deviceOnline,
    deviceSalesLocked,
    canOpenRestock,
    openDoorHint,
    canCancelEmptyRoute,
    showRouteCancelColumn,
    cancelEmptyRoute,
    openRestockDoor
  };
}
