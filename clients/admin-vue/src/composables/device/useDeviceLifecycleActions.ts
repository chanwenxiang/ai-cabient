import { ref, type ComputedRef, type Ref } from 'vue';
import type { Router } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage, isUserDismiss } from '@/utils/error-message';
import {
  canLifecycleAction,
  lifecycleDisabledReason as lifecycleDisabledReasonFor
} from '@/utils/device-lifecycle-guards';
import type { OpenApiAdminDeviceDto } from '@aicabinet/shared-types';

/** 设备详情页资产摘要（reactive 字段子集） */
export type DeviceLifecycleAsset = {
  lifecycleStatus: string;
  merchantId: string;
  lifecycleRemark: string;
};

export type DeviceLifecycleDevicePatch = {
  deviceId: string;
  deviceName?: string;
  onlineStatus?: string;
  merchantId?: string;
  merchantName?: string;
  activeSessionId?: string;
  activeSessionState?: string;
  refundPolicy?: string | null;
  effectiveRefundPolicy?: string | null;
};

export type UseDeviceLifecycleActionsDeps = {
  deviceId: string;
  asset: DeviceLifecycleAsset;
  device: Ref<DeviceLifecycleDevicePatch | null>;
  canEditDevice: ComputedRef<boolean> | Ref<boolean>;
  canRegenerateDeviceId: ComputedRef<boolean> | Ref<boolean>;
  lifecycleActionLabel: (action?: string | null) => string;
  fillAsset: (row: OpenApiAdminDeviceDto) => void;
  loadDetail: () => Promise<void>;
  loadLifecycleEvents: () => Promise<void>;
  router: Router;
};

/**
 * 设备详情生命周期写路径：绑定商户 / 投放态机 / 解绑硬件 / 重生编号。
 * 从 DeviceDetailView 抽出，降低上帝页 script 体积（debt-tracker D6）。
 */
export function useDeviceLifecycleActions(deps: UseDeviceLifecycleActionsDeps) {
  const lifeLoading = ref('');
  const bindDialogVisible = ref(false);
  const bindMerchantId = ref('');
  const bindMerchantsLoading = ref(false);
  const bindMerchantOptions = ref<Array<{ merchantId: string; merchantName?: string }>>([]);
  const hardwareResetLoading = ref(false);
  const regenerateIdLoading = ref(false);

  function canLifecycle(action: string) {
    return canLifecycleAction(action, deps.asset.lifecycleStatus, deps.asset.merchantId);
  }

  function lifecycleDisabledReason(action: string) {
    return lifecycleDisabledReasonFor(action, deps.asset.lifecycleStatus, deps.asset.merchantId);
  }

  async function openBindDialog() {
    if (!canLifecycle('BIND')) return;
    bindMerchantId.value = '';
    bindDialogVisible.value = true;
    bindMerchantsLoading.value = true;
    try {
      const data = await api.request<{
        items?: Array<{ merchantId: string; merchantName?: string }>;
      }>(AdminEndpoints.merchantsCatalog, 'GET');
      bindMerchantOptions.value = data.items || [];
    } catch (e) {
      bindMerchantOptions.value = [];
      ElMessage.error(errorMessage(e, '加载商户失败'));
    } finally {
      bindMerchantsLoading.value = false;
    }
  }

  async function confirmBindMerchant() {
    const merchantId = String(bindMerchantId.value || '').trim();
    if (!merchantId) {
      ElMessage.warning('请选择商户');
      return;
    }
    bindDialogVisible.value = false;
    await runLifecycle('BIND', false, merchantId);
  }

  async function runLifecycle(action: string, requireRemark = false, merchantId?: string) {
    try {
      let remark = deps.asset.lifecycleRemark || '';
      if (requireRemark || action === 'RETIRE' || action === 'RETURN') {
        const { value } = await ElMessageBox.prompt(
          `确认执行「${deps.lifecycleActionLabel(action)}」？请填写备注。`,
          deps.lifecycleActionLabel(action),
          {
            inputValue: remark,
            inputValidator: (v) => !!String(v || '').trim() || '必须填写备注',
            confirmButtonText: '确认',
            type: action === 'RETIRE' ? 'warning' : undefined
          }
        );
        remark = String(value).trim();
      } else {
        await ElMessageBox.confirm(
          `确认执行「${deps.lifecycleActionLabel(action)}」？`,
          deps.lifecycleActionLabel(action),
          {
            type: 'warning',
            confirmButtonText: '确认',
            cancelButtonText: '取消'
          }
        );
      }
      lifeLoading.value = action;
      const row = await api.request<OpenApiAdminDeviceDto>(
        AdminEndpoints.deviceLifecycle(deps.deviceId),
        'POST',
        { action, merchantId, remark: remark || undefined }
      );
      deps.fillAsset(row);
      ElMessage.success(`${deps.lifecycleActionLabel(action)}成功`);
      await Promise.all([deps.loadDetail(), deps.loadLifecycleEvents()]);
    } catch (e: unknown) {
      if (!isUserDismiss(e)) {
        ElMessage.error(errorMessage(e, '操作失败'));
      }
    } finally {
      lifeLoading.value = '';
    }
  }

  async function resetHardwareBinding() {
    if (!deps.canEditDevice.value) return;
    try {
      await ElMessageBox.confirm(
        '解绑后 IMEI 将清空，柜机下次联网心跳将重新绑定硬件。是否继续？',
        '解绑硬件',
        { type: 'warning', confirmButtonText: '解绑', cancelButtonText: '取消' }
      );
    } catch {
      return;
    }
    hardwareResetLoading.value = true;
    try {
      const row = await api.request<OpenApiAdminDeviceDto>(
        AdminEndpoints.deviceResetHardwareBinding(deps.deviceId),
        'POST'
      );
      deps.fillAsset(row);
      deps.device.value = {
        deviceId: row.deviceId ?? deps.deviceId,
        deviceName: row.deviceName,
        onlineStatus: row.onlineStatus,
        merchantId: row.merchantId,
        merchantName: row.merchantName,
        activeSessionId: row.activeSessionId,
        activeSessionState: row.activeSessionState,
        refundPolicy: row.refundPolicy,
        effectiveRefundPolicy: row.effectiveRefundPolicy
      };
      ElMessage.success('硬件绑定已解除，请让柜机重新联网');
    } catch (e) {
      ElMessage.error(errorMessage(e, '解绑失败'));
    } finally {
      hardwareResetLoading.value = false;
    }
  }

  async function regenerateDeviceId() {
    if (!deps.canRegenerateDeviceId.value) return;
    try {
      await ElMessageBox.confirm(
        '将废弃当前编号并分配新的 12 位数字编号，仅适用于入库且无业务记录的设备。是否继续？',
        '重新生成编号',
        { type: 'warning', confirmButtonText: '重新生成', cancelButtonText: '取消' }
      );
    } catch {
      return;
    }
    regenerateIdLoading.value = true;
    try {
      const row = await api.request<OpenApiAdminDeviceDto>(
        AdminEndpoints.deviceRegenerateId(deps.deviceId),
        'POST'
      );
      const newId = row.deviceId ?? '';
      ElMessage.success(`新编号 ${newId}`);
      await deps.router.replace(`/devices/${encodeURIComponent(newId)}`);
    } catch (e) {
      ElMessage.error(errorMessage(e, '重新生成失败'));
    } finally {
      regenerateIdLoading.value = false;
    }
  }

  return {
    lifeLoading,
    bindDialogVisible,
    bindMerchantId,
    bindMerchantsLoading,
    bindMerchantOptions,
    hardwareResetLoading,
    regenerateIdLoading,
    canLifecycle,
    lifecycleDisabledReason,
    openBindDialog,
    confirmBindMerchant,
    runLifecycle,
    resetHardwareBinding,
    regenerateDeviceId
  };
}
