import { computed, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage, isUserDismiss } from '@/utils/error-message';
import { softFallback } from '@/utils/soft-fallback';
import { displayLabel } from '@aicabinet/shared-dict';
import type { OpenApiAdminDeviceDto } from '@aicabinet/shared-types';

export type DeviceRemoteOpsPolicy = {
  deviceId: string;
  salesLocked: boolean;
  priceLocked: boolean;
  skuEditForbidden: boolean;
  saleForbidden: boolean;
};

export type DeviceRemoteOpsDevice = {
  deviceId: string;
  refundPolicy?: string | null;
  effectiveRefundPolicy?: string | null;
};

export type DeviceRepairTicketRow = {
  ticketId: number;
  title: string;
  status: string;
  priority?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type UseDeviceRemoteOpsDeps = {
  deviceId: string;
  device: Ref<DeviceRemoteOpsDevice | null>;
  canEditDevice: ComputedRef<boolean> | Ref<boolean>;
  /** 与温控 SET_TEMP 共用 loading key */
  cmdLoading: Ref<string>;
  loadDetail: () => Promise<void>;
};

/**
 * 设备详情远程运维：指令下发 / 退款规则 / 策略锁 / 维修工单。
 * 从 DeviceDetailView 抽出（debt-tracker D23）。
 */
export function useDeviceRemoteOps(deps: UseDeviceRemoteOpsDeps) {
  const policy = ref<DeviceRemoteOpsPolicy | null>(null);
  const refundPolicyDraft = ref('INHERIT');
  const refundPolicySaving = ref(false);
  const globalRefundPolicy = ref('AUTO_REFUND');
  const repairTickets = ref<DeviceRepairTicketRow[]>([]);
  const repairHydrated = ref(false);

  const effectiveRefundPolicy = computed(
    () =>
      deps.device.value?.effectiveRefundPolicy ||
      deps.device.value?.refundPolicy ||
      globalRefundPolicy.value ||
      'AUTO_REFUND'
  );

  function policyLabel(policyCode?: string | null) {
    if (policyCode === 'DISPUTE_ONLY') return '仅申诉审核';
    return '自助退款';
  }

  const refundDraftHint = computed(() => {
    switch (refundPolicyDraft.value) {
      case 'AUTO_REFUND':
        return '本柜覆盖全局：消费者可在订单页一键退款，资金即时原路退回。';
      case 'DISPUTE_ONLY':
        return '本柜覆盖全局：消费者只能提交申诉，需运营核对录像后再退款。';
      default:
        return `不单独设置本柜，沿用参数配置「refund.default_policy」：${policyLabel(globalRefundPolicy.value)}。`;
    }
  });

  const refundPriorityHint = computed(
    () =>
      `全局默认「${policyLabel(globalRefundPolicy.value)}」。若本柜选择自助退款或仅申诉，则以本柜为准；选「跟随全局」则继承参数配置。`
  );

  function syncRefundDraftFromDevice(refundPolicy?: string | null) {
    refundPolicyDraft.value = refundPolicy || 'INHERIT';
  }

  async function loadGlobalRefundPolicy() {
    try {
      const rows = await api.request<Array<{ configKey: string; configValue?: string }>>(
        AdminEndpoints.systemConfigs,
        'GET'
      );
      const hit = rows.find((r) => r.configKey === 'refund.default_policy');
      const v = String(hit?.configValue || '')
        .trim()
        .toUpperCase();
      if (v === 'DISPUTE_ONLY' || v === 'AUTO_REFUND') {
        globalRefundPolicy.value = v;
      }
    } catch {
      /* 无权限或失败时沿用默认 AUTO_REFUND */
    }
  }

  async function loadPolicy(fallbackSalesLocked: boolean) {
    try {
      policy.value = await api.request<DeviceRemoteOpsPolicy>(
        AdminEndpoints.devicePolicy(deps.deviceId),
        'GET'
      );
    } catch {
      policy.value = {
        deviceId: deps.deviceId,
        salesLocked: fallbackSalesLocked,
        priceLocked: false,
        skuEditForbidden: false,
        saleForbidden: false
      };
    }
  }

  async function loadRepairTickets() {
    try {
      repairTickets.value = await softFallback(
        api.request<DeviceRepairTicketRow[]>(
          AdminEndpoints.repairTicketsByDevice(deps.deviceId),
          'GET'
        ),
        [],
        '维修工单'
      );
    } finally {
      repairHydrated.value = true;
    }
  }

  function markRepairHydrated() {
    repairHydrated.value = true;
  }

  function repairStatusLabel(status?: string) {
    return displayLabel('repair_ticket_status', status, '未知状态');
  }

  function priorityLabel(priority?: string) {
    return displayLabel('dispute_priority', priority, '暂无');
  }

  async function createRepair() {
    try {
      const { value: title } = await ElMessageBox.prompt('请输入工单标题', '新建维修工单', {
        inputValidator: (v) => !!String(v || '').trim() || '标题必填',
        confirmButtonText: '创建'
      });
      await api.request(AdminEndpoints.repairTickets, 'POST', {
        deviceId: deps.deviceId,
        title: String(title).trim(),
        priority: 'NORMAL'
      });
      ElMessage.success('工单已创建');
      await loadRepairTickets();
    } catch (e: unknown) {
      if (!isUserDismiss(e)) {
        ElMessage.error(errorMessage(e, '创建失败'));
      }
    }
  }

  async function saveRefundPolicy() {
    if (!deps.canEditDevice.value) return;
    refundPolicySaving.value = true;
    try {
      const updated = await api.request<OpenApiAdminDeviceDto>(
        AdminEndpoints.device(deps.deviceId),
        'PATCH',
        { refundPolicy: refundPolicyDraft.value }
      );
      deps.device.value = {
        ...(deps.device.value || { deviceId: deps.deviceId }),
        refundPolicy: updated.refundPolicy ?? null,
        effectiveRefundPolicy: updated.effectiveRefundPolicy
      };
      refundPolicyDraft.value = updated.refundPolicy || 'INHERIT';
      ElMessage.success(
        `已保存：${policyLabel(updated.effectiveRefundPolicy || updated.refundPolicy)}${
          updated.refundPolicy ? '（本柜覆盖）' : '（跟随全局）'
        }`
      );
    } catch (e) {
      ElMessage.error(errorMessage(e, '退款规则保存失败'));
    } finally {
      refundPolicySaving.value = false;
    }
  }

  async function savePolicy() {
    if (!policy.value || !deps.canEditDevice.value) return;
    try {
      policy.value = await api.request(AdminEndpoints.devicePolicy(deps.deviceId), 'PUT', policy.value);
      ElMessage.success('策略已更新');
      await deps.loadDetail();
    } catch (e) {
      ElMessage.error(errorMessage(e, '策略保存失败'));
    }
  }

  async function sendCommand(command: string) {
    const labels: Record<string, string> = {
      OPEN_DOOR: '运维远程开门',
      LOCK: '锁机停售',
      UNLOCK: '解锁营业',
      REBOOT: '重启设备'
    };
    try {
      const hint =
        command === 'OPEN_DOOR'
          ? '确认执行「运维远程开门」？将创建运维会话并占柜（关门后不结算）。补货请用补货调度页的「补货开门」。请填写原因。'
          : `确认执行「${labels[command]}」？请填写原因。`;
      const { value: reason } = await ElMessageBox.prompt(hint, '运维指令', {
        inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
        confirmButtonText: '确认下发',
        type:
          command === 'REBOOT' || command === 'LOCK' || command === 'OPEN_DOOR'
            ? 'warning'
            : undefined
      });
      deps.cmdLoading.value = command;
      const result = await api.request<{ message?: string; salesLocked?: boolean }>(
        AdminEndpoints.deviceCommands(deps.deviceId),
        'POST',
        { command, reason: reason }
      );
      ElMessage.success(result.message || '指令已下发');
      await deps.loadDetail();
    } catch (e: unknown) {
      if (!isUserDismiss(e)) {
        ElMessage.error(errorMessage(e, '指令失败'));
      }
    } finally {
      deps.cmdLoading.value = '';
    }
  }

  return {
    policy,
    refundPolicyDraft,
    refundPolicySaving,
    globalRefundPolicy,
    effectiveRefundPolicy,
    refundDraftHint,
    refundPriorityHint,
    policyLabel,
    repairTickets,
    repairHydrated,
    syncRefundDraftFromDevice,
    loadGlobalRefundPolicy,
    loadPolicy,
    loadRepairTickets,
    markRepairHydrated,
    repairStatusLabel,
    priorityLabel,
    createRepair,
    saveRefundPolicy,
    savePolicy,
    sendCommand
  };
}
