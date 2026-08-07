import { API_BASE_URL } from '@/config/api';
import { clearDictOverrides, displayLabel } from '@aicabinet/shared-dict';
import { matchPermission } from '@aicabinet/shared-rbac';
import { localizeApiMessage } from '@aicabinet/shared-uni/format';

export type MerchantReplenishmentSuggest = {
  deviceId?: string;
  skuId: string;
  currentQty?: number;
  capacity?: number;
  lowThreshold?: number;
  suggestQty: number;
  inTransitQty?: number;
  suggestReason?: string;
};

export type MerchantReplenishmentRequestLine = {
  lineId?: number;
  skuId: string;
  skuName?: string;
  suggestedQty?: number;
  requestedQty: number;
};

export type MerchantReplenishmentRequest = {
  requestId: number;
  merchantId?: string;
  merchantName?: string;
  deviceId: string;
  deviceName?: string;
  status: string;
  notes?: string;
  submittedAt?: string;
  reviewedAt?: string;
  rejectReason?: string;
  replenishmentTaskId?: number;
  outboundId?: number;
  lines?: MerchantReplenishmentRequestLine[];
};

export function getToken() {
  return uni.getStorageSync('merchant_token') || '';
}

export function clearSession() {
  uni.removeStorageSync('merchant_token');
  uni.removeStorageSync('merchant_user_id');
  uni.removeStorageSync('merchant_me');
  clearDictOverrides();
}

/** 401 时清会话并跳转登录 */
export function handleUnauthorized(message?: string) {
  clearSession();
  const pages = getCurrentPages();
  const route = pages[pages.length - 1]?.route || '';
  if (!route.includes('login')) {
    uni.reLaunch({ url: '/pages/login/login' });
  }
  return new Error(localizeApiMessage(message, '登录已失效，请重新登录'));
}

/**
 * 带鉴权的文件下载（导出/证据等）。
 * 成功返回 tempFilePath；失败抛错。
 */
export function downloadAuthedFile(url: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const token = getToken();
    if (!token) {
      reject(new Error('请先登录'));
      return;
    }
    uni.downloadFile({
      url,
      header: { Authorization: 'Bearer ' + token },
      timeout: 60_000,
      success(res) {
        if (res.statusCode === 401) {
          reject(handleUnauthorized());
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && res.tempFilePath) {
          resolve(res.tempFilePath);
          return;
        }
        reject(new Error(`下载失败 (${res.statusCode})`));
      },
      fail(err) {
        reject(new Error(err.errMsg || '下载失败'));
      }
    });
  });
}

/**
 * 打开已下载的导出文件；H5 上 openDocument 常失败，回退为触发浏览器下载。
 */
export function openExportedFile(tempFilePath: string, fileName = 'export.xlsx'): Promise<void> {
  return new Promise((resolve) => {
    uni.openDocument({
      filePath: tempFilePath,
      showMenu: true,
      success() {
        resolve();
      },
      fail() {
        if (typeof document !== 'undefined') {
          const a = document.createElement('a');
          a.href = tempFilePath;
          a.download = fileName;
          a.rel = 'noopener';
          document.body.appendChild(a);
          a.click();
          a.remove();
          resolve();
          return;
        }
        uni.showToast({ title: '文件已下载，请从文件管理打开', icon: 'none' });
        resolve();
      }
    });
  });
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
      timeout: 20_000,
      success(res) {
        const body = res.data as { code?: number; message?: string; data?: T };
        if (res.statusCode === 401) {
          reject(handleUnauthorized(body?.message));
          return;
        }
        if (res.statusCode === 403) {
          reject(new Error(localizeApiMessage(body?.message, '权限不足')));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0) {
          resolve(body.data as T);
          return;
        }
        reject(new Error(localizeApiMessage(body?.message, `请求失败 (${res.statusCode})`)));
      },
      fail(err) {
        reject(new Error(localizeApiMessage(err.errMsg, '网络错误')));
      }
    });
  });
}

export function merchantLogin(phone: string, password: string) {
  return request<{ token: string; userId: string }>(
    '/api/v2/auth/merchant-password-login',
    'POST',
    { phoneNumber: phone, password },
    false
  ).then(async (data) => {
    uni.setStorageSync('merchant_token', data.token);
    uni.setStorageSync('merchant_user_id', data.userId);
    const { loadRuntimeDict } = await import('@/utils/dict-runtime');
    await loadRuntimeDict();
    return data;
  });
}

export function uploadReplenishmentEvidenceFile(
  taskId: number,
  filePath: string
): Promise<import('@aicabinet/shared-types').FileAttachmentDto> {
  return new Promise((resolve, reject) => {
    if (!getToken()) {
      reject(new Error('请先登录'));
      return;
    }
    uni.uploadFile({
      url: `${API_BASE_URL}/api/v2/merchant/replenishment/tasks/${taskId}/evidence`,
      filePath,
      name: 'file',
      header: { Authorization: 'Bearer ' + getToken() },
      timeout: 30_000,
      success(res) {
        if (res.statusCode === 401) {
          reject(handleUnauthorized());
          return;
        }
        try {
          const body = JSON.parse(String(res.data || '{}')) as {
            code?: number;
            message?: string;
            data?: import('@aicabinet/shared-types').FileAttachmentDto;
          };
          if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0 && body.data) {
            resolve(body.data);
            return;
          }
          reject(new Error(localizeApiMessage(body?.message, `上传失败 (${res.statusCode})`)));
        } catch {
          reject(new Error('上传响应解析失败'));
        }
      },
      fail(err) {
        reject(new Error(err.errMsg || '网络错误'));
      }
    });
  });
}

/** Auth-aware download for evidence stream URLs (image tags cannot send Bearer). */
export function downloadReplenishmentEvidenceFile(
  taskId: number,
  fileId: number
): Promise<string> {
  const url = `${API_BASE_URL}/api/v2/merchant/replenishment/tasks/${taskId}/evidence/${fileId}`;
  return downloadAuthedFile(url);
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
    request<import('@aicabinet/shared-types').MerchantSkuPricing>(
      `/api/v2/merchant/pricing/skus/${encodeURIComponent(skuId)}`,
      'PATCH',
      body
    ),
  workbench: () => request<import('@aicabinet/shared-types').MerchantWorkbench>('/api/v2/merchant/workbench'),
  listAnnouncements: () =>
    request<import('@aicabinet/shared-types').AnnouncementDto[]>('/api/v2/merchant/announcements'),
  getAnnouncement: (id: number) =>
    request<import('@aicabinet/shared-types').AnnouncementDto>(`/api/v2/merchant/announcements/${id}`),
  teamUsers: () =>
    request<import('@aicabinet/shared-types').MerchantUserDto[]>('/api/v2/merchant/team/users'),
  teamRoles: () =>
    request<import('@aicabinet/shared-types').MerchantTeamRoleDto[]>('/api/v2/merchant/team/roles'),
  createTeamUser: (body: {
    phoneNumber: string;
    password: string;
    displayName?: string;
    roleKey?: string;
  }) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>('/api/v2/merchant/team/users', 'POST', body),
  updateTeamUser: (
    userId: number,
    body: { displayName?: string; roleKey?: string }
  ) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      `/api/v2/merchant/team/users/${userId}`,
      'PATCH',
      body
    ),
  disableTeamUser: (userId: number) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      `/api/v2/merchant/team/users/${userId}/disable`,
      'POST'
    ),
  enableTeamUser: (userId: number) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      `/api/v2/merchant/team/users/${userId}/enable`,
      'POST'
    ),
  resetTeamUserPassword: (userId: number, password: string) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      `/api/v2/merchant/team/users/${userId}/reset-password`,
      'POST',
      { password }
    ),
  notifyPrefs: () =>
    request<{ wxBound: boolean; enabledAlertTypes: string[] }>('/api/v2/merchant/notify/prefs'),
  notifyWxBind: (code: string) =>
    request<{ wxBound: boolean; enabledAlertTypes: string[] }>('/api/v2/merchant/notify/wx-bind', 'POST', {
      code
    }),
  notifySubscribe: (alertTypes: string[]) =>
    request<{ wxBound: boolean; enabledAlertTypes: string[] }>('/api/v2/merchant/notify/subscribe', 'POST', {
      alertTypes
    }),
  exceptions: (status = 'OPEN', page = 0, size = 100) =>
    request<{
      items: Array<{
        exceptionId: string;
        exceptionType: string;
        title: string;
        detail?: string;
        deviceId?: string;
      }>;
      total: number;
    }>(
      `/api/v2/merchant/exceptions?status=${encodeURIComponent(status)}&page=${page}&size=${size}`
    ),
  /** OPEN + PROCESSING；按页拉满，返回去重后的 items 与合计 total */
  openExceptions: async (pageSize = 100) => {
    type ExRow = {
      exceptionId: string;
      exceptionType: string;
      title: string;
      detail?: string;
      deviceId?: string;
    };
    const size = Math.min(Math.max(pageSize, 1), 100);
    const mergePages = async (status: string) => {
      const first = await merchantApi.exceptions(status, 0, size);
      const items: ExRow[] = [...(first.items || [])];
      const total = first.total ?? items.length;
      const pages = Math.ceil(total / size);
      for (let p = 1; p < pages; p++) {
        const next = await merchantApi.exceptions(status, p, size);
        items.push(...(next.items || []));
      }
      return { items, total };
    };
    const [open, processing] = await Promise.all([
      mergePages('OPEN').catch(() => ({ items: [] as ExRow[], total: 0 })),
      mergePages('PROCESSING').catch(() => ({ items: [] as ExRow[], total: 0 }))
    ]);
    const byId = new Map<string, ExRow>();
    for (const row of [...open.items, ...processing.items]) {
      if (row?.exceptionId) byId.set(row.exceptionId, row);
    }
    return {
      items: [...byId.values()],
      total: (open.total || 0) + (processing.total || 0)
    };
  },
  resolveInventoryException: (id: string, resolution: string) =>
    request(`/api/v2/merchant/exceptions/${encodeURIComponent(id)}/resolve`, 'POST', { resolution }),
  analytics: (days = 30) => request<import('@aicabinet/shared-types').MerchantAnalyticsOverview>(`/api/v2/merchant/analytics/overview?days=${days}`),
  settlements: () =>
    request<import('@aicabinet/shared-types').MerchantSettlementOverview>(
      '/api/v2/merchant/settlements/overview'
    ),
  lineWallet: () =>
    request<{
      bound: boolean;
      managerId?: number;
      managerName?: string;
      phone?: string;
      balanceCents?: number;
      frozenCents?: number;
      availableCents?: number;
      recentLedgers?: any[];
      recentWithdraws?: any[];
    }>('/api/v2/merchant/line-wallet'),
  lineWalletWithdraw: (body: { amountCents: number; requestNo?: string }) =>
    request('/api/v2/merchant/line-wallet/withdraw', 'POST', body),
  wallet: () =>
    request<{
      bound: boolean;
      merchantId?: string;
      merchantName?: string;
      balanceCents?: number;
      frozenCents?: number;
      availableCents?: number;
      recentLedgers?: any[];
      recentWithdraws?: any[];
    }>('/api/v2/merchant/wallet'),
  walletWithdraw: (body: { amountCents: number; requestNo?: string }) =>
    request('/api/v2/merchant/wallet/withdraw', 'POST', body),
  dailySettlements: (from: string, to: string) =>
    request<import('@aicabinet/shared-types').MerchantDailySettlement[]>(
      `/api/v2/merchant/settlements/daily?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
    ),
  settlementBatches: (from: string, to: string) =>
    request<import('@aicabinet/shared-types').MerchantSettlementBatch[]>(
      `/api/v2/merchant/settlements/batches?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
    ),
  revenueSplits: (page = 0, size = 50, status?: string, from?: string, to?: string) => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) q.set('status', status);
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    return request<{ items: import('@aicabinet/shared-types').RevenueSplit[]; total: number }>(
      `/api/v2/merchant/revenue-splits?${q}`
    );
  },
  exportSettlementsUrl: (from: string, to: string) =>
    `${API_BASE_URL}/api/v2/merchant/settlements/export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  exportDeviceReportsUrl: () => `${API_BASE_URL}/api/v2/merchant/device-reports/export`,
  replenishmentSuggestions: (deviceId: string) =>
    request<MerchantReplenishmentSuggest[]>(
      `/api/v2/merchant/replenishment/suggestions?deviceId=${encodeURIComponent(deviceId)}`
    ),
  replenishmentRequests: (status?: string, deviceId?: string) => {
    const q = new URLSearchParams();
    if (status) q.set('status', status);
    if (deviceId) q.set('deviceId', deviceId);
    const qs = q.toString();
    return request<MerchantReplenishmentRequest[]>(
      `/api/v2/merchant/replenishment/requests${qs ? `?${qs}` : ''}`
    );
  },
  submitReplenishmentRequest: (body: {
    deviceId: string;
    notes?: string;
    lines: { skuId: string; requestedQty: number }[];
  }) => request<MerchantReplenishmentRequest>('/api/v2/merchant/replenishment/requests', 'POST', body),
  replenishmentTasks: (status?: string) =>
    request<Record<string, unknown>[]>(`/api/v2/merchant/replenishment/tasks${status ? `?status=${encodeURIComponent(status)}` : ''}`),
  replenishmentTaskLines: (taskId: number) =>
    request<Record<string, unknown>[]>(`/api/v2/merchant/replenishment/tasks/${taskId}/lines`),
  checkInReplenishmentTask: (taskId: number, body?: { latitude?: number; longitude?: number }) =>
    request(`/api/v2/merchant/replenishment/tasks/${taskId}/check-in`, 'POST', body || {}),
  /** 补货员开门：签到后调用，绑定补货任务，不产生消费者账单 */
  openReplenishmentDoor: (taskId: number) =>
    request<{ sessionId: string; state?: string }>(
      `/api/v2/merchant/replenishment/tasks/${taskId}/open-door`,
      'POST'
    ),
  confirmReplenishmentLines: (taskId: number, lines: Record<string, unknown>[]) =>
    request(`/api/v2/merchant/replenishment/tasks/${taskId}/lines`, 'POST', { lines }),
  completeReplenishmentTask: (taskId: number) =>
    request(`/api/v2/merchant/replenishment/tasks/${taskId}/complete`, 'POST'),
  listReplenishmentEvidence: (taskId: number) =>
    request<import('@aicabinet/shared-types').FileAttachmentDto[]>(
      `/api/v2/merchant/replenishment/tasks/${taskId}/evidence`
    ),
  uploadReplenishmentEvidence: (taskId: number, filePath: string) =>
    uploadReplenishmentEvidenceFile(taskId, filePath),
  downloadReplenishmentEvidence: (taskId: number, fileId: number) =>
    downloadReplenishmentEvidenceFile(taskId, fileId),
  expiryAlerts: () =>
    request<
      {
        taskId?: number;
        deviceId: string;
        skuId?: string;
        batchNo?: string;
        quantity?: number;
        reason?: string;
        status?: string;
        restockHeadroom?: number;
      }[]
    >('/api/v2/merchant/expiry-alerts'),
  disputes: (status?: string, page = 0, size = 100) => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) q.set('status', status);
    return request<{ items?: MerchantDisputeTicket[]; total?: number } | MerchantDisputeTicket[]>(
      `/api/v2/merchant/disputes?${q}`
    );
  },
  orders: (deviceId?: string, page = 0, size = 50) => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (deviceId) q.set('deviceId', deviceId);
    return request<{ items?: MerchantOrderSummary[]; total?: number } | MerchantOrderSummary[]>(
      `/api/v2/merchant/orders?${q}`
    );
  },
  orderDetail: (orderId: string) =>
    request<Record<string, unknown>>(`/api/v2/merchant/orders/${encodeURIComponent(orderId)}`),
  disputeDetail: (ticketId: string) =>
    request<MerchantDisputeDetail>(`/api/v2/merchant/disputes/${encodeURIComponent(ticketId)}`),
  disputeReply: (ticketId: string, body: string) =>
    request<MerchantDisputeDetail>(
      `/api/v2/merchant/disputes/${encodeURIComponent(ticketId)}/reply`,
      'POST',
      { body }
    )
};

export type MerchantOrderSummary = {
  orderId: string;
  sessionId?: string;
  deviceId?: string;
  status?: string;
  totalAmountCents?: number;
  lineCount?: number;
  createdAt?: string;
};

export type MerchantDisputeTicket = {
  ticketId: string;
  status?: string;
  reason?: string;
  deviceId?: string;
  createdAt?: string;
  lastMessage?: string;
  canReply?: boolean;
  orderId?: string;
  billedAmountCents?: number;
  sessionId?: string;
};

export type MerchantDisputeDetail = {
  ticket?: MerchantDisputeTicket;
  messages?: { body?: string; authorType?: string; createdAt?: string }[];
  canReply?: boolean;
};

export function canEditPlanogram(me: import('@aicabinet/shared-types').MerchantMe | null) {
  if (!me?.merchants?.length) return false;
  return me.merchants.some((m) => m.allowMerchantPlanogramEdit);
}

export function canEditPricing(me: import('@aicabinet/shared-types').MerchantMe | null) {
  if (!me?.merchants?.length) return false;
  return me.merchants.some((m) => m.allowMerchantPricingEdit);
}

/** 若依风格：精确码或分段通配 merchant:replenishment:*（@aicabinet/shared-rbac） */
export function hasPerm(me: import('@aicabinet/shared-types').MerchantMe | null | undefined, code: string) {
  return matchPermission(me?.permissions, code);
}

/** 商户端展示用：运营字典「设备」在商户侧统一为「柜机」 */
const MERCHANT_ALERT_TYPE_LABELS: Record<string, string> = {
  DEVICE_OFFLINE: '柜机离线',
  DEVICE_FAULT: '柜机故障',
  REPLENISHMENT: '补货任务',
  REPLENISHMENT_REQUIRED: '需补货',
  LOW_STOCK: '低库存',
  EXPIRY: '临期',
  DISPUTE: '消费争议'
};

export function alertTypeLabel(type: string) {
  return MERCHANT_ALERT_TYPE_LABELS[type] || displayLabel('exception_type', type, '告警');
}

export function merchantAlertTitle(_type: string, title: string) {
  // 用 replace + 正则而非 replaceAll（ES2021），兼容旧版微信基础库 / WebView
  return String(title || '').replace(/设备/g, '柜机');
}
