import { computed, reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import type { createLoadSeq } from '@/composables/createLoadSeq';
import type { ReplenishmentDeviceRef } from '@/composables/replenishment/useReplenishmentTaskActions';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import type { PageResult } from '@aicabinet/shared-types';

export type ReplenishmentAssigneeOption = {
  userId: number;
  name?: string;
  phoneNumber?: string;
  status?: string;
};

export type UseReplenishmentRoutePlanningDeps = {
  loadSeq: ReturnType<typeof createLoadSeq>;
  canEdit: ComputedRef<boolean> | Ref<boolean>;
  focusDeviceId: Ref<string>;
  shortageDeviceIds: Ref<string[]>;
  devices: Ref<ReplenishmentDeviceRef[]>;
  currentUserId: () => number;
  currentUserName: () => string;
  loadDeviceRefs: () => Promise<void>;
  deviceName: (deviceId?: string, snapshot?: string | null) => string;
  /** 创建成功后：切 routes tab、同步 query、刷新列表 */
  onRouteCreated: () => Promise<void>;
  /** 读深链 ?plan=1&deviceIds= */
  readPlanQuery: () => { plan: string; deviceIds: string };
  /** 打开/放弃规划后清掉 plan 相关 query */
  clearPlanQuery: () => void;
};

/**
 * 补货「规划路线」：负责人下拉 / 对话框开闭 / POST 创建路线。
 * 从 ReplenishmentView 抽出（debt-tracker D24）。
 */
export function useReplenishmentRoutePlanning(deps: UseReplenishmentRoutePlanningDeps) {
  const planDialog = ref(false);
  const planSaving = ref(false);
  const assigneeOptions = ref<ReplenishmentAssigneeOption[]>([]);
  const assigneeLoading = ref(false);
  const planForm = reactive({
    routeName: '',
    plannedDate: '',
    assigneeUserId: undefined as number | undefined,
    deviceIds: [] as string[]
  });

  const shortageDevices = computed(() => deps.shortageDeviceIds.value);
  const selectedDevicesWithoutShortage = computed(() =>
    planForm.deviceIds.filter((id) => !shortageDevices.value.includes(id))
  );

  function localDate() {
    const now = new Date();
    return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  }

  function assigneeOptionLabel(op: ReplenishmentAssigneeOption) {
    const name = (op.name || '').trim() || '未命名';
    const phone = (op.phoneNumber || '').trim();
    return phone ? `${name}（${phone}）` : `${name}（${op.userId}）`;
  }

  function assigneeLabel(userId?: number | string | null, empty = '未分配') {
    if (userId == null || userId === '') return empty;
    const id = Number(userId);
    if (!Number.isFinite(id) || id <= 0) return empty;
    const op = assigneeOptions.value.find((item) => item.userId === id);
    if (op) return assigneeOptionLabel(op);
    return String(id);
  }

  function ensureAssigneeOption(userId: number, name?: string) {
    if (!userId || assigneeOptions.value.some((item) => item.userId === userId)) return;
    assigneeOptions.value = [{ userId, name: name || '当前账号' }, ...assigneeOptions.value];
  }

  async function loadAssignees() {
    const seq = deps.loadSeq.begin('loadAssignees');
    if (assigneeLoading.value) return;
    assigneeLoading.value = true;
    try {
      const data = await api.request<PageResult<ReplenishmentAssigneeOption>>(
        AdminEndpoints.rbacOperatorsPage(0, 100),
        'GET'
      );
      const items = (data.items || []).filter((item) => !item.status || item.status === 'ACTIVE');
      assigneeOptions.value = items;
      ensureAssigneeOption(deps.currentUserId(), deps.currentUserName());
    } catch {
      if (!deps.loadSeq.isCurrent(seq, 'loadAssignees')) return;
      ensureAssigneeOption(deps.currentUserId(), deps.currentUserName());
      if (!assigneeOptions.value.length) {
        assigneeOptions.value = [
          { userId: deps.currentUserId(), name: deps.currentUserName() || '当前账号' }
        ];
      }
    } finally {
      if (!deps.loadSeq.isCurrent(seq, 'loadAssignees')) return;
      assigneeLoading.value = false;
    }
  }

  function planDeviceLabel(device: ReplenishmentDeviceRef | AdminDynamicRow) {
    return `${deps.deviceName(String(device.deviceId || ''), device.deviceName as string | undefined)}（${device.deviceId}）`;
  }

  function fillPlanForm(partial: {
    routeName: string;
    deviceIds: string[];
  }) {
    Object.assign(planForm, {
      routeName: partial.routeName,
      plannedDate: localDate(),
      assigneeUserId: deps.currentUserId(),
      deviceIds: partial.deviceIds
    });
  }

  function openPlan() {
    void deps.loadDeviceRefs();
    const focus = deps.focusDeviceId.value.trim();
    fillPlanForm({
      routeName: `${new Date().toLocaleDateString('zh-CN')} 补货路线`,
      deviceIds: focus ? [focus] : []
    });
    void loadAssignees();
    planDialog.value = true;
  }

  function closePlan() {
    planDialog.value = false;
  }

  async function maybeAutoPlanFromQuery() {
    const q = deps.readPlanQuery();
    if (q.plan !== '1') return;
    if (!deps.canEdit.value) return;
    const ids = q.deviceIds
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    const focus = deps.focusDeviceId.value.trim();
    let target: string[];
    if (ids.length) {
      target = ids;
    } else if (focus) {
      target = [focus];
    } else {
      target = shortageDevices.value;
    }
    if (!target.length) {
      ElMessage.warning('暂无缺货柜机可规划，请先刷新缺货建议');
      deps.clearPlanQuery();
      return;
    }
    fillPlanForm({
      routeName: `${new Date().toLocaleDateString('zh-CN')} 缺货补货`,
      deviceIds: target
    });
    void loadAssignees();
    planDialog.value = true;
    deps.clearPlanQuery();
  }

  async function planFromShortage() {
    const ids = shortageDevices.value;
    if (!ids.length) return ElMessage.warning('当前无缺货设备');
    await deps.loadDeviceRefs();
    const focus = deps.focusDeviceId.value;
    fillPlanForm({
      routeName: `${new Date().toLocaleDateString('zh-CN')} 缺货补货`,
      deviceIds: focus && ids.includes(focus) ? [focus] : ids
    });
    void loadAssignees();
    planDialog.value = true;
  }

  function planSingleDevice(deviceId: string) {
    if (!deviceId) return;
    void deps.loadDeviceRefs();
    fillPlanForm({
      routeName: `${new Date().toLocaleDateString('zh-CN')} ${deviceId} 补货`,
      deviceIds: [deviceId]
    });
    void loadAssignees();
    planDialog.value = true;
  }

  async function createPlan() {
    if (!planForm.routeName.trim()) return ElMessage.warning('请填写路线名称');
    if (!planForm.assigneeUserId) return ElMessage.warning('请选择负责人');
    if (!planForm.deviceIds.length) return ElMessage.warning('请至少选择一台设备');
    planSaving.value = true;
    try {
      const created = await api.request<AdminDynamicRow>(AdminEndpoints.replenishmentPlan, 'POST', {
        ...planForm,
        startLatitude: null,
        startLongitude: null
      });
      planDialog.value = false;
      await deps.onRouteCreated();
      const outbounds =
        (
          await api
            .request<{ items: AdminDynamicRow[] }>(AdminEndpoints.warehouseOutboundsAll, 'GET')
            .catch(() => ({ items: [] as AdminDynamicRow[] }))
        ).items || [];
      const linked = (outbounds || []).filter((o) => o.routeId === created?.routeId);
      if (linked.length) {
        ElMessage.success({
          message: `路线已创建，出库单 ${linked[0].outboundId} 待拣货发运（仓库页）`,
          duration: 5000
        });
      } else {
        ElMessage.warning({
          message: '路线已创建，但未生成出库明细（仓库可用库存不足），可现场补录上架',
          duration: 5000
        });
      }
    } catch (error) {
      ElMessage.error({
        message: error instanceof Error ? error.message : '路线创建失败',
        duration: 5000
      });
    } finally {
      planSaving.value = false;
    }
  }

  return {
    planDialog,
    planSaving,
    planForm,
    assigneeOptions,
    assigneeLoading,
    selectedDevicesWithoutShortage,
    shortageDevices,
    assigneeLabel,
    assigneeOptionLabel,
    planDeviceLabel,
    loadAssignees,
    openPlan,
    closePlan,
    maybeAutoPlanFromQuery,
    planFromShortage,
    planSingleDevice,
    createPlan
  };
}
