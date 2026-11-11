import { reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import type { OpenApiAdminDeviceDto } from '@aicabinet/shared-types';
import type { DeviceLifecycleAsset } from '@/composables/device/useDeviceLifecycleActions';

/** 设备详情页资产表单（含生命周期摘要字段，供投放态机复用） */
export type DeviceAssetForm = DeviceLifecycleAsset & {
  deviceName: string;
  /** 投放场景（CB-049）：OFFICE/CAMPUS/...；空=未分类 */
  sceneType: string;
  imei: string;
  assetOwner: string;
  coopMode: string;
  depositCents: number | undefined;
  dataFeeCents: number | undefined;
  opsTags: string;
  routeCode: string;
  homeWarehouseId: string;
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
    deviceName: '',
    sceneType: '',
    imei: '',
    assetOwner: '',
    coopMode: '',
    depositCents: undefined,
    dataFeeCents: undefined,
    opsTags: '',
    routeCode: '',
    homeWarehouseId: '',
    latitude: undefined,
    longitude: undefined,
    address: '',
    deployedAt: undefined,
    lifecycleRemark: '',
    merchantId: ''
  });
  const assetSaving = ref(false);
  /** 资产是否已成功加载过（R2-A8：未加载成功时禁止保存，防空表单清字段） */
  const assetLoaded = ref(false);
  const assetLoadFailed = ref(false);
  const geoConfigured = ref(false);

  const warehouseOptions = ref<{ warehouseId: string; warehouseName?: string }[]>([]);

  function fillAsset(row: OpenApiAdminDeviceDto) {
    asset.lifecycleStatus = row.lifecycleStatus || '';
    asset.deviceName = row.deviceName || '';
    asset.sceneType = row.sceneType || '';
    asset.imei = row.imei || '';
    asset.assetOwner = row.assetOwner || '';
    asset.coopMode = row.coopMode || '';
    asset.depositCents = row.depositCents == null ? undefined : Number(row.depositCents);
    asset.dataFeeCents = row.dataFeeCents == null ? undefined : Number(row.dataFeeCents);
    asset.opsTags = row.opsTags || '';
    asset.routeCode = row.routeCode || '';
    asset.homeWarehouseId = row.homeWarehouseId || '';
    asset.latitude = row.latitude == null ? undefined : Number(row.latitude);
    asset.longitude = row.longitude == null ? undefined : Number(row.longitude);
    asset.address = row.address || '';
    asset.deployedAt = row.deployedAt;
    asset.lifecycleRemark = row.lifecycleRemark || '';
    asset.merchantId = row.merchantId || '';
  }

  async function loadWarehouses() {
    try {
      const data = await api.request<{ items?: { warehouseId: string; warehouseName?: string }[] }>(
        AdminEndpoints.warehouseListAll,
        'GET'
      );
      warehouseOptions.value = data.items || [];
    } catch {
      warehouseOptions.value = [];
    }
  }

  async function loadAsset() {
    // R2-A8：加载失败要留下失败态 —— 原实现让空表单可保存，PATCH 恒回传
    // homeWarehouseId:''（空串=清除）⇒ 资产接口单点失败时一点保存就把真实所属仓清掉。
    try {
      const row = await api.request<OpenApiAdminDeviceDto>(
        AdminEndpoints.device(deps.deviceId),
        'GET'
      );
      fillAsset(row);
      deps.onDeviceSynced?.(row);
      assetLoaded.value = true;
      assetLoadFailed.value = false;
      void loadWarehouses();
    } catch (e) {
      assetLoaded.value = false;
      assetLoadFailed.value = true;
      throw e;
    }
  }

  async function saveAsset() {
    if (!deps.canEditDevice.value) return;
    if (!assetLoaded.value || assetLoadFailed.value) {
      ElMessage.error(
        '资产信息未加载成功，已阻止保存（避免用空表单清掉所属仓库等字段），请刷新后重试'
      );
      return;
    }
    // 投放场景必填（CB-049 扩展，2026-10-16 用户拍板）：没有场景就无法按商品×场景限额校验上架
    if (!asset.sceneType) {
      ElMessage.warning('请选择投放场景（必选）：商品上架限额按柜子所属场景校验');
      return;
    }
    assetSaving.value = true;
    try {
      const row = await api.request<OpenApiAdminDeviceDto>(
        AdminEndpoints.device(deps.deviceId),
        'PATCH',
        {
          deviceName: asset.deviceName || null,
          // CB-049：空串=清除场景，null=不改；表单恒回传当前值
          sceneType: asset.sceneType || '',
          // R12-MA-172：`?? null` 只转 null/undefined，保留空串 `''`——`|| null` 会把
          // 空串也吞成 null，后端 updateById 跳 null ⇒ 可清空字段永不可清（M01 家族）。
          assetOwner: asset.assetOwner ?? null,
          coopMode: asset.coopMode ?? null,
          depositCents: asset.depositCents ?? null,
          dataFeeCents: asset.dataFeeCents ?? null,
          opsTags: asset.opsTags ?? null,
          routeCode: asset.routeCode ?? null,
          homeWarehouseId: asset.homeWarehouseId || '',
          latitude: asset.latitude ?? null,
          longitude: asset.longitude ?? null,
          address: asset.address ?? null,
          lifecycleRemark: asset.lifecycleRemark ?? null
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
    assetLoaded,
    assetLoadFailed,
    geoConfigured,
    fillAsset,
    loadAsset,
    loadWarehouses,
    warehouseOptions,
    saveAsset,
    loadGeoStatus
  };
}
