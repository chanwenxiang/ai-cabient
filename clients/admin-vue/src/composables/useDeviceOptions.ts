import { ref } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { api } from '@/api/client';

export interface DeviceOption {
  deviceId: string;
  deviceName?: string;
  onlineStatus?: string;
}

/**
 * 设备下拉数据源：统一按 ops:device:list / ops:device:ref 权限守卫（ref 为履约角色只读引用），
 * 无权限时不请求并降级为空列表（避免控制台 403 刷屏）。
 */
export function useDeviceOptions() {
  const auth = useAuthStore();
  const deviceOptions = ref<DeviceOption[]>([]);
  const deviceOptionsLoading = ref(false);

  async function loadDeviceOptions() {
    if (!auth.hasPerm('ops:device:list') && !auth.hasPerm('ops:device:ref')) {
      deviceOptions.value = [];
      return;
    }
    deviceOptionsLoading.value = true;
    try {
      deviceOptions.value = await api.request<DeviceOption[]>(
        '/api/v2/ops/admin/devices/ref',
        'GET'
      );
    } catch {
      deviceOptions.value = [];
    } finally {
      deviceOptionsLoading.value = false;
    }
  }

  return { deviceOptions, deviceOptionsLoading, loadDeviceOptions };
}
