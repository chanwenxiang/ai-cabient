import { reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import type { OpenApiAdminDeviceDto } from '@aicabinet/shared-types';
import type { DeviceLifecycleAsset } from '@/composables/device/useDeviceLifecycleActions';

/** 设备详情页资产表单（含生命周期摘要字段，供投放态机复用） */
export type DeviceAssetForm = DeviceLifecycleAsset & {
  imei: string;
  assetOwner: string;
  coopMode: string;
  depositCents: number | undefined;
  dataFeeCents: number | undefined;
  opsTags: string;
  routeCode: string;
  latitude: number | undefined;
  longitude: number | undefined;
  address: string;
  deployedAt: string | undefined;
};

export type UseDeviceAssetDeps = {
  deviceId: string;
  canEditDevice: ComputedRef<boolean> | Ref<boolean>;
  /** loadAsset 成功后同步概览 device 行（商户名/在线态等） */
  onDeviceSynced?: (row: OpenApiAdminDeviceDto) => void;
};

/**
 * 设备详情资产读写：表单状态 / GET·PATCH device / 高德配置探测。
 * 从 DeviceDetailView 抽出（debt-tracker D21）。
 */
export function useDeviceAsset(deps: UseDeviceAssetDeps) {
  const asset = reactive<DeviceAssetForm>({
    lifecycleStatus: '',
    imei: '',
    assetOwner: '',
    coopMode: '',
    depositCents: undefined,
    dataFeeCents: undefined,
    opsTags: '',
    routeCode: '',
    latitude: undefined,
    longitude: undefined,
    address: '',
    deployedAt: undefined,
    lifecycleRemark: '',
    merchantId: ''
  });
  const assetSaving = ref(false);
  const geoConfigured = ref(false);

  function fillAsset(row: OpenApiAdminDeviceDto) {
    asset.lifecycleStatus = row.lifecycleStatus || '';
    asset.imei = row.imei || '';
    asset.assetOwner = row.assetOwner || '';
    asset.coopMode = row.coopMode || '';
    asset.depositCents = row.depositCents == null ? undefined : Number(row.depositCents);
    asset.dataFeeCents = row.dataFeeCents == null ? undefined : Number(row.dataFeeCents);
    asset.opsTags = row.opsTags || '';
    asset.routeCode = row.routeCode || '';
    asset.latitude = row.latitude == null ? undefined : Number(row.latitude);
    asset.longitude = row.longitude == null ? undefined : Number(row.longitude);
    asset.address = row.address || '';
    asset.deployedAt = row.deployedAt;
    asset.lifecycleRemark = row.lifecycleRemark || '';
    asset.merchantId = row.merchantId || '';
  }

  async function loadAsset() {
    const row = await api.request<OpenApiAdminDeviceDto>(
      AdminEndpoints.device(deps.deviceId),
      'GET'
    );
    fillAsset(row);
    deps.onDeviceSynced?.(row);
  }

  async function saveAsset() {
    if (!deps.canEditDevice.value) return;
    assetSaving.value = true;
    try {
      const row = await api.request<OpenApiAdminDeviceDto>(
        AdminEndpoints.device(deps.deviceId),
        'PATCH',
        {
          assetOwner: asset.assetOwner || null,
          coopMode: asset.coopMode || null,
          depositCents: asset.depositCents ?? null,
          dataFeeCents: asset.dataFeeCents ?? null,
          opsTags: asset.opsTags || null,
          routeCode: asset.routeCode || null,
          latitude: asset.latitude ?? null,
          longitude: asset.longitude ?? null,
          address: asset.address || null,
          lifecycleRemark: asset.lifecycleRemark || null
        }
      );
      fillAsset(row);
      ElMessage.success('资产信息已保存');
    } catch (e) {
      ElMessage.error(errorMessage(e, '保存失败'));
    } finally {
      assetSaving.value = false;
    }
  }

  async function loadGeoStatus() {
    if (!deps.canEditDevice.value) {
      geoConfigured.value = false;
      return;
    }
    try {
      const data = await api.request<{ configured: boolean }>(AdminEndpoints.geoStatus, 'GET');
      geoConfigured.value = !!data.configured;
    } catch {
      geoConfigured.value = false;
    }
  }

  return {
    asset,
    assetSaving,
    geoConfigured,
    fillAsset,
    loadAsset,
    saveAsset,
    loadGeoStatus
  };
}
