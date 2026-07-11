import { API_BASE_URL } from '@/config/api';
import { dictLabel } from '@aicabinet/shared-dict';

function getToken() {
  return uni.getStorageSync('merchant_token') || '';
}

export function clearSession() {
  uni.removeStorageSync('merchant_token');
  uni.removeStorageSync('merchant_user_id');
  uni.removeStorageSync('merchant_me');
}

export function request<T>(
  path: string,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' = 'GET',
  data?: unknown,
  auth = true
): Promise<T> {
  return new Promise((resolve, reject) => {
    const header: Record<string, string> = { 'Content-Type': 'application/json' };
    if (auth && getToken()) header.Authorization = 'Bearer ' + getToken();
    uni.request({
      url: API_BASE_URL + path,
      method: method as UniApp.RequestOptions['method'],
      data: data as UniApp.RequestOptions['data'],
      header,
      success(res) {
        const body = res.data as { code?: number; message?: string; data?: T };
        if (res.statusCode === 401 || res.statusCode === 403) {
          clearSession();
          reject(new Error(body?.message || '登录已失效'));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0) {
          resolve(body.data as T);
          return;
        }
        reject(new Error(body?.message || `请求失败 (${res.statusCode})`));
      },
      fail(err) {
        reject(new Error(err.errMsg || '网络错误'));
      }
    });
  });
}

export function merchantLogin(phone: string, password: string) {
  return request<{ token: string; userId: string }>(
    '/api/v2/auth/admin-password-login',
    'POST',
    { phoneNumber: phone, password },
    false
  ).then((data) => {
    uni.setStorageSync('merchant_token', data.token);
    uni.setStorageSync('merchant_user_id', data.userId);
    return data;
  });
}

export const merchantApi = {
  me: () => request<import('@aicabinet/shared-types').MerchantMe>('/api/v2/merchant/me'),
  stats: () => request<Record<string, number>>('/api/v2/merchant/stats'),
  trend: (days = 7) =>
    request<{ last7Days?: { date: string; revenueCents: number }[] }>(`/api/v2/merchant/trend?days=${days}`),
  devices: () => request<import('@aicabinet/shared-types').DeviceInfo[]>('/api/v2/merchant/devices'),
  deviceSettings: (id: string) =>
    request<Record<string, unknown>>(`/api/v2/merchant/devices/${encodeURIComponent(id)}/settings`),
  updateDeviceSettings: (id: string, body: Record<string, unknown>) =>
    request(`/api/v2/merchant/devices/${encodeURIComponent(id)}/settings`, 'PATCH', body),
  deviceSlots: (id: string) =>
    request<import('@aicabinet/shared-types').DeviceSlot[]>(`/api/v2/merchant/devices/${encodeURIComponent(id)}/slots`),
  upsertSlots: (id: string, body: import('@aicabinet/shared-types').UpsertDeviceSlotRequest[]) =>
    request(`/api/v2/merchant/devices/${encodeURIComponent(id)}/slots`, 'PUT', body),
  pricing: (deviceId?: string) =>
    request<import('@aicabinet/shared-types').MerchantSkuPricing[]>(
      `/api/v2/merchant/pricing/skus${deviceId ? `?deviceId=${encodeURIComponent(deviceId)}` : ''}`
    ),
  updatePricing: (skuId: string, body: { deviceId: string; priceCents: number | null }) =>
    request(`/api/v2/merchant/pricing/skus/${encodeURIComponent(skuId)}`, 'PATCH', body),
  workbench: () => request<import('@aicabinet/shared-types').MerchantWorkbench>('/api/v2/merchant/workbench'),
  exceptions: (status = 'OPEN') => request<{ items: Array<{ exceptionId:string; exceptionType:string; title:string; detail?:string; deviceId?:string }> }>(`/api/v2/merchant/exceptions?status=${encodeURIComponent(status)}`),
  resolveInventoryException: (id: string, resolution: string) =>
    request(`/api/v2/merchant/exceptions/${encodeURIComponent(id)}/resolve`, 'POST', { resolution }),
  analytics: (days = 30) => request<import('@aicabinet/shared-types').MerchantAnalyticsOverview>(`/api/v2/merchant/analytics/overview?days=${days}`),
  settlements: () => request<import('@aicabinet/shared-types').MerchantSettlementOverview>('/api/v2/merchant/settlements/overview'),
  skuSales: (days = 30) => request<import('@aicabinet/shared-types').MerchantSkuSales[]>(`/api/v2/merchant/analytics/sku-sales?days=${days}`),
  replenishmentSuggestions: (deviceId: string) =>
    request<Record<string, unknown>[]>(`/api/v2/merchant/replenishment/suggestions?deviceId=${encodeURIComponent(deviceId)}`),
  replenishmentTasks: (status?: string) =>
    request<Record<string, unknown>[]>(`/api/v2/merchant/replenishment/tasks${status ? `?status=${encodeURIComponent(status)}` : ''}`),
  replenishmentTaskLines: (taskId: number) =>
    request<Record<string, unknown>[]>(`/api/v2/merchant/replenishment/tasks/${taskId}/lines`),
  checkInReplenishmentTask: (taskId: number, body?: { latitude?: number; longitude?: number }) =>
    request(`/api/v2/merchant/replenishment/tasks/${taskId}/check-in`, 'POST', body || {}),
  confirmReplenishmentLines: (taskId: number, lines: Record<string, unknown>[]) =>
    request(`/api/v2/merchant/replenishment/tasks/${taskId}/lines`, 'POST', { lines }),
  completeReplenishmentTask: (taskId: number) =>
    request(`/api/v2/merchant/replenishment/tasks/${taskId}/complete`, 'POST')
};

export function canEditPlanogram(me: import('@aicabinet/shared-types').MerchantMe | null) {
  if (!me?.merchants?.length) return false;
  return me.merchants.some((m) => m.allowMerchantPlanogramEdit);
}

export function canEditPricing(me: import('@aicabinet/shared-types').MerchantMe | null) {
  if (!me?.merchants?.length) return false;
  return me.merchants.some((m) => m.allowMerchantPricingEdit);
}

export function hasPerm(me: import('@aicabinet/shared-types').MerchantMe | null, code: string) {
  return (me?.permissions || []).includes(code);
}

export function alertTypeLabel(type: string) {
  return dictLabel('exception_type', type);
}
