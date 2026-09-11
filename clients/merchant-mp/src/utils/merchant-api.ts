import { API_BASE_URL } from '@/config/api';
import { clearDictOverrides, displayLabel } from '@aicabinet/shared-dict';
import { matchPermission } from '@aicabinet/shared-rbac';
import { loadRuntimeDict as sharedLoadRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
import { localizeApiMessage } from '@aicabinet/shared-uni/format';
import { withQuery } from '@aicabinet/shared-uni/query';
import { mpRequest, createMpApiError, type MpApiSession } from '@aicabinet/shared-uni/request';

export type MerchantReplenishmentSuggest =
  import('@aicabinet/shared-types').OpenApiReplenishmentSuggestDto;

export type MerchantReplenishmentEfficiency =
  import('@aicabinet/shared-types').OpenApiMerchantReplenishmentEfficiencyDto;

/** @deprecated 使用 OpenApiDeviceInventoryDto；低库存列表即库存行 */
export type DeviceLowStockItem = import('@aicabinet/shared-types').OpenApiDeviceInventoryDto;

/** @deprecated 使用 OpenApiSlotDiscrepancyAlertDto */
export type MerchantSlotDiscrepancy =
  import('@aicabinet/shared-types').OpenApiSlotDiscrepancyAlertDto;

/** @deprecated 使用 OpenApiMerchantDeviceReportDto */
export type MerchantDeviceReport = import('@aicabinet/shared-types').OpenApiMerchantDeviceReportDto;

/** @deprecated 使用 OpenApiUpdateMerchantProfileRequest */
export type MerchantProfileUpdate =
  import('@aicabinet/shared-types').OpenApiUpdateMerchantProfileRequest;

/** @deprecated 使用 OpenApiMerchantReplenishmentRequestLineDto */
export type MerchantReplenishmentRequestLine =
  import('@aicabinet/shared-types').OpenApiMerchantReplenishmentRequestLineDto;

/** @deprecated 使用 OpenApiMerchantReplenishmentRequestDto */
export type MerchantReplenishmentRequest =
  import('@aicabinet/shared-types').OpenApiMerchantReplenishmentRequestDto;

/** @deprecated 使用 OpenApiMerchantWalletLedgerDto */
export type WalletLedger = import('@aicabinet/shared-types').OpenApiMerchantWalletLedgerDto;

/** @deprecated 使用 OpenApiMerchantWithdrawRequestDto */
export type WithdrawRecord = import('@aicabinet/shared-types').OpenApiMerchantWithdrawRequestDto;

/** @deprecated 使用 OpenApiMerchantWalletOverviewDto */
export type WalletOverview = import('@aicabinet/shared-types').OpenApiMerchantWalletOverviewDto;

/** @deprecated 使用 OpenApiLineWalletOverviewDto */
export type LineWalletOverview = import('@aicabinet/shared-types').OpenApiLineWalletOverviewDto;

export function getToken() {
  return uni.getStorageSync('merchant_token') || '';
}

/** 登录页路径（去 query / 前后斜杠后比对）。 */
export function isMerchantLoginPath(url: string): boolean {
  const path = String(url || '')
    .split('?')[0]
    .replace(/^\/+/, '')
    .replace(/\/+$/, '');
  return path === 'pages/login/login' || path.endsWith('/pages/login/login');
}

const mpApiSession: MpApiSession = {
  baseUrl: API_BASE_URL,
  timeoutMs: 20_000,
  getToken,
  clearSession,
  applyRefreshedToken: (data) => {
    uni.setStorageSync('merchant_token', data.token);
    if (data.userId) uni.setStorageSync('merchant_user_id', data.userId);
  },
  handleUnauthorized
};

export function clearSession() {
  uni.removeStorageSync('merchant_token');
  uni.removeStorageSync('merchant_user_id');
  uni.removeStorageSync('merchant_me');
  clearDictOverrides();
}

let unauthorizedHandling = false;
let navGuardInstalled = false;

/**
 * 统一导航守卫：无 token 时拦截业务跳转并 reLaunch 登录。
 * 冷启动深链仍依赖 App.onLaunch；本拦截覆盖运行期 navigate/redirect/reLaunch/switchTab。
 */
export function installMerchantNavGuard() {
  if (navGuardInstalled) return;
  navGuardInstalled = true;
  const guard = {
    invoke(args: { url?: string }) {
      const url = String(args?.url || '');
      if (isMerchantLoginPath(url)) return true;
      if (getToken()) return true;
      if (!unauthorizedHandling) {
        unauthorizedHandling = true;
        uni.reLaunch({
          url: '/pages/login/login',
          complete: () => {
            setTimeout(() => {
              unauthorizedHandling = false;
            }, 800);
          }
        });
      }
      return false;
    }
  };
  for (const api of ['navigateTo', 'redirectTo', 'reLaunch', 'switchTab'] as const) {
    uni.addInterceptor(api, guard);
  }
}

/** 401 时清会话并跳转登录（M-10：防抖 + 精确匹配登录页） */
export function handleUnauthorized(message?: string) {
  clearSession();
  const pages = getCurrentPages();
  const route = String(pages[pages.length - 1]?.route || '');
  const onLogin = isMerchantLoginPath(route);
  if (!onLogin && !unauthorizedHandling) {
    unauthorizedHandling = true;
    uni.reLaunch({
      url: '/pages/login/login',
      complete: () => {
        setTimeout(() => {
          unauthorizedHandling = false;
        }, 800);
      }
    });
  }
  return createMpApiError(
    localizeApiMessage(message, '登录已失效，请重新登录'),
    401,
    'UNAUTHORIZED'
  );
}

/**
 * 带鉴权的文件下载（导出/证据等）。
 * 成功返回 tempFilePath；失败抛错。
 */
export function downloadAuthedFile(url: string, timeoutMs = 60_000): Promise<string> {
  return new Promise((resolve, reject) => {
    const token = getToken();
    if (!token) {
      reject(new Error('请先登录'));
      return;
    }
    uni.downloadFile({
      url,
      header: { Authorization: 'Bearer ' + token },
      timeout: timeoutMs,
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
  auth = true,
  retried = false
): Promise<T> {
  return mpRequest<T>(
    mpApiSession,
    path,
    method as UniApp.RequestOptions['method'],
    data,
    auth,
    retried
  );
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
    await sharedLoadRuntimeDict({
      getToken: getToken,
      fetchRuntime: () => request('/api/v2/dicts/runtime', 'GET')
    });
    return data;
  });
}

/** 上传响应 data 须为有效附件（M-19） */
function parseFileAttachmentDto(
  data: unknown
): import('@aicabinet/shared-types').FileAttachmentDto | null {
  if (!data || typeof data !== 'object') return null;
  const row = data as Record<string, unknown>;
  const fileId = Number(row.fileId);
  if (!Number.isFinite(fileId) || fileId <= 0) return null;
  const out: import('@aicabinet/shared-types').FileAttachmentDto = { fileId };
  if (row.fileName != null) out.fileName = String(row.fileName);
  if (row.contentType != null) out.contentType = String(row.contentType);
  if (row.fileSize != null && Number.isFinite(Number(row.fileSize))) {
    out.fileSize = Number(row.fileSize);
  }
  if (row.url != null) out.url = String(row.url);
  return out;
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
      header: {
        Authorization: 'Bearer ' + getToken(),
        'X-Requested-With': 'XMLHttpRequest'
      },
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
            data?: unknown;
          };
          const attachment = parseFileAttachmentDto(body?.data);
          if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0 && attachment) {
            resolve(attachment);
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

export function uploadReplenishmentRequestEvidenceFile(
  filePath: string
): Promise<import('@aicabinet/shared-types').FileAttachmentDto> {
  return new Promise((resolve, reject) => {
    if (!getToken()) {
      reject(new Error('请先登录'));
      return;
    }
    uni.uploadFile({
      url: `${API_BASE_URL}/api/v2/merchant/replenishment/requests/evidence`,
      filePath,
      name: 'file',
      header: {
        Authorization: 'Bearer ' + getToken(),
        'X-Requested-With': 'XMLHttpRequest'
      },
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
            data?: unknown;
          };
          const attachment = parseFileAttachmentDto(body?.data);
          if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0 && attachment) {
            resolve(attachment);
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
export function downloadReplenishmentEvidenceFile(taskId: number, fileId: number): Promise<string> {
  const url = `${API_BASE_URL}/api/v2/merchant/replenishment/tasks/${taskId}/evidence/${fileId}`;
  return downloadAuthedFile(url);
}

export function downloadReplenishmentRequestEvidenceFile(
  requestId: number,
  fileId: number
): Promise<string> {
  const url = `${API_BASE_URL}/api/v2/merchant/replenishment/requests/${requestId}/evidence/${fileId}`;
  return downloadAuthedFile(url);
}

export const merchantApi = {
  me: () => request<import('@aicabinet/shared-types').MerchantMe>('/api/v2/merchant/me'),
  stats: () =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDashboardStatsDto>(
      '/api/v2/merchant/stats'
    ),
  trend: (days = 7) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantTrendDto>(
      `/api/v2/merchant/trend?days=${days}`
    ),
  devices: () =>
    request<import('@aicabinet/shared-types').DeviceInfo[]>('/api/v2/merchant/devices'),
  deviceSettings: (id: string) =>
    request<Record<string, unknown>>(`/api/v2/merchant/devices/${encodeURIComponent(id)}/settings`),
  updateDeviceSettings: (id: string, body: Record<string, unknown>) =>
    request(`/api/v2/merchant/devices/${encodeURIComponent(id)}/settings`, 'PATCH', body),
  deviceSlots: (id: string) =>
    request<import('@aicabinet/shared-types').DeviceSlot[]>(
      `/api/v2/merchant/devices/${encodeURIComponent(id)}/slots`
    ),
  upsertSlots: (id: string, body: import('@aicabinet/shared-types').UpsertDeviceSlotRequest[]) =>
    request(`/api/v2/merchant/devices/${encodeURIComponent(id)}/slots`, 'PUT', body),
  pricing: (deviceId?: string) => {
    const path = deviceId
      ? `/api/v2/merchant/pricing/skus?deviceId=${encodeURIComponent(deviceId)}`
      : '/api/v2/merchant/pricing/skus';
    return request<import('@aicabinet/shared-types').MerchantSkuPricing[]>(path);
  },
  updatePricing: (skuId: string, body: { deviceId: string; priceCents: number | null }) =>
    request<import('@aicabinet/shared-types').MerchantSkuPricing>(
      `/api/v2/merchant/pricing/skus/${encodeURIComponent(skuId)}`,
      'PATCH',
      body
    ),
  workbench: () =>
    request<import('@aicabinet/shared-types').MerchantWorkbench>('/api/v2/merchant/workbench'),
  listAnnouncements: () =>
    request<import('@aicabinet/shared-types').AnnouncementDto[]>('/api/v2/merchant/announcements'),
  getAnnouncement: (id: number) =>
    request<import('@aicabinet/shared-types').AnnouncementDto>(
      `/api/v2/merchant/announcements/${id}`
    ),
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
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      '/api/v2/merchant/team/users',
      'POST',
      body
    ),
  updateTeamUser: (userId: number, body: { displayName?: string; roleKey?: string }) =>
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
    request<import('@aicabinet/shared-types').OpenApiMerchantNotifyPrefDto>(
      '/api/v2/merchant/notify/prefs'
    ),
  notifyWxBind: (code: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantNotifyPrefDto>(
      '/api/v2/merchant/notify/wx-bind',
      'POST',
      {
        code
      }
    ),
  notifySubscribe: (alertTypes: string[]) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantNotifyPrefDto>(
      '/api/v2/merchant/notify/subscribe',
      'POST',
      {
        alertTypes
      }
    ),
  exceptions: (status = 'OPEN', page = 0, size = 100) =>
    request<
      import('@aicabinet/shared-types').PageResult<
        import('@aicabinet/shared-types').OpenApiOpsExceptionDto
      >
    >(`/api/v2/merchant/exceptions?status=${encodeURIComponent(status)}&page=${page}&size=${size}`),
  /** OPEN + PROCESSING；最多拉 3 页（300 条），返回去重后的 items 与合计 total */
  openExceptions: async (pageSize = 100) => {
    type ExRow = import('@aicabinet/shared-types').OpenApiOpsExceptionDto;
    const size = Math.min(Math.max(pageSize, 1), 100);
    const MAX_PAGES = 3;
    const mergePages = async (status: string) => {
      const first = await merchantApi.exceptions(status, 0, size);
      const items: ExRow[] = [...(first.items || [])];
      const total = first.total ?? items.length;
      // 限制页数，避免异常量大时首页/待办请求风暴；超出部分以后端聚合接口为准
      const pages = Math.min(Math.ceil(total / size), MAX_PAGES);
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
    request(`/api/v2/merchant/exceptions/${encodeURIComponent(id)}/resolve`, 'POST', {
      resolution
    }),
  analytics: (days = 30) =>
    request<import('@aicabinet/shared-types').MerchantAnalyticsOverview>(
      `/api/v2/merchant/analytics/overview?days=${days}`
    ),
  salesReports: (dim = 'PRODUCT', fromDate?: string, toDate?: string) =>
    request<import('@aicabinet/shared-types').OpenApiSalesReportRowDto[]>(
      withQuery('/api/v2/merchant/analytics/sales-reports', { dim, fromDate, toDate })
    ),
  skuSales: (days = 30, deviceId?: string) =>
    request<import('@aicabinet/shared-types').MerchantSkuSales[]>(
      withQuery('/api/v2/merchant/analytics/sku-sales', { days, deviceId })
    ),
  skuVelocity: (deviceId: string) =>
    request<import('@aicabinet/shared-types').MerchantSkuVelocity[]>(
      `/api/v2/merchant/analytics/velocity?deviceId=${encodeURIComponent(deviceId)}`
    ),
  aiInsight: (days = 30) =>
    request<import('@aicabinet/shared-types').MerchantAiInsight>(
      `/api/v2/merchant/analytics/ai-insight?days=${days}`
    ),
  expirySummary: () =>
    request<import('@aicabinet/shared-types').MerchantExpirySummary>(
      '/api/v2/merchant/analytics/expiry-summary'
    ),
  deviceTemperatureHistory: (deviceId: string, hours = 24) =>
    request<import('@aicabinet/shared-types').DeviceTemperatureReading[]>(
      `/api/v2/merchant/devices/${encodeURIComponent(deviceId)}/temperature-history?hours=${hours}`
    ),
  pricingHistory: (deviceId?: string, skuId?: string) =>
    request<import('@aicabinet/shared-types').MerchantSkuPriceChange[]>(
      withQuery('/api/v2/merchant/pricing/history', { deviceId, skuId })
    ),
  settlements: () =>
    request<import('@aicabinet/shared-types').MerchantSettlementOverview>(
      '/api/v2/merchant/settlements/overview'
    ),
  lineWallet: () =>
    request<import('@aicabinet/shared-types').OpenApiLineWalletOverviewDto>(
      '/api/v2/merchant/line-wallet'
    ),
  lineWalletWithdraw: (body: { amountCents: number; requestNo?: string }) =>
    request('/api/v2/merchant/line-wallet/withdraw', 'POST', body),
  wallet: () =>
    request<import('@aicabinet/shared-types').OpenApiMerchantWalletOverviewDto>(
      '/api/v2/merchant/wallet'
    ),
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
  revenueSplits: (page = 0, size = 50, status?: string, from?: string, to?: string) =>
    request<
      import('@aicabinet/shared-types').PageResult<import('@aicabinet/shared-types').RevenueSplit>
    >(withQuery('/api/v2/merchant/revenue-splits', { page, size, status, from, to })),
  exportSettlementsUrl: (from: string, to: string) =>
    `${API_BASE_URL}/api/v2/merchant/settlements/export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  exportOrdersUrl: (deviceId?: string) => {
    const q = deviceId ? `?deviceId=${encodeURIComponent(deviceId)}` : '';
    return `${API_BASE_URL}/api/v2/merchant/orders/export${q}`;
  },
  exportDeviceReportsUrl: () => `${API_BASE_URL}/api/v2/merchant/device-reports/export`,
  replenishmentSuggestions: (deviceId: string) =>
    request<MerchantReplenishmentSuggest[]>(
      `/api/v2/merchant/replenishment/suggestions?deviceId=${encodeURIComponent(deviceId)}`
    ),
  getTaxProfile: (merchantId: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantTaxProfileDto>(
      `/api/v2/merchant/tax-profile?merchantId=${encodeURIComponent(merchantId)}`
    ),
  saveTaxProfile: (body: import('@aicabinet/shared-types').OpenApiMerchantTaxProfileDto) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantTaxProfileDto>(
      '/api/v2/merchant/tax-profile',
      'PUT',
      body
    ),
  myReplenishmentEfficiency: () =>
    request<MerchantReplenishmentEfficiency>('/api/v2/merchant/replenishment/my-efficiency'),
  /** 缺货巡柜：全部低库存 SKU 明细（按柜聚合由页面完成） */
  lowStockDevices: () =>
    request<DeviceLowStockItem[]>('/api/v2/merchant/inventory?lowStockOnly=true'),
  replenishmentRequests: (status?: string, deviceId?: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantReplenishmentRequestDto[]>(
      withQuery('/api/v2/merchant/replenishment/requests', { status, deviceId })
    ),
  submitReplenishmentRequest: (
    body: import('@aicabinet/shared-types').OpenApiCreateMerchantReplenishmentRequest
  ) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantReplenishmentRequestDto>(
      '/api/v2/merchant/replenishment/requests',
      'POST',
      body
    ),
  listReplenishmentRequestEvidence: (requestId: number) =>
    request<import('@aicabinet/shared-types').FileAttachmentDto[]>(
      `/api/v2/merchant/replenishment/requests/${requestId}/evidence`
    ),
  uploadReplenishmentRequestEvidence: (filePath: string) =>
    uploadReplenishmentRequestEvidenceFile(filePath),
  downloadReplenishmentRequestEvidence: (requestId: number, fileId: number) =>
    downloadReplenishmentRequestEvidenceFile(requestId, fileId),
  replenishmentTasks: (status?: string) => {
    const path = status
      ? `/api/v2/merchant/replenishment/tasks?status=${encodeURIComponent(status)}`
      : '/api/v2/merchant/replenishment/tasks';
    return request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto[]>(path);
  },
  /** 扫码柜机归属校验：须在当前账号 FIELD 管辖范围 */
  assertReplenishmentDeviceAccess: (deviceId: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantReplenishmentDeviceAccessDto>(
      `/api/v2/merchant/replenishment/devices/${encodeURIComponent(deviceId)}/access`
    ),
  /** 补货开门状态：以服务端会话为准 */
  replenishmentDoorSession: (taskId: number) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantReplenishmentDoorSessionDto>(
      `/api/v2/merchant/replenishment/tasks/${taskId}/door-session`
    ),
  replenishmentTaskLines: (taskId: number) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto[]>(
      `/api/v2/merchant/replenishment/tasks/${taskId}/lines`
    ),
  checkInReplenishmentTask: (
    taskId: number,
    body?: import('@aicabinet/shared-types').OpenApiReplenishmentCheckInRequest
  ) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto>(
      `/api/v2/merchant/replenishment/tasks/${taskId}/check-in`,
      'POST',
      body || {}
    ),
  /** 补货员开门：签到后调用，绑定补货任务，不产生消费者账单 */
  openReplenishmentDoor: (taskId: number) =>
    request<import('@aicabinet/shared-types').OpenApiSessionDto>(
      `/api/v2/merchant/replenishment/tasks/${taskId}/open-door`,
      'POST'
    ),
  confirmReplenishmentLines: (
    taskId: number,
    lines: import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto[]
  ) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto[]>(
      `/api/v2/merchant/replenishment/tasks/${taskId}/lines`,
      'POST',
      { lines } satisfies import('@aicabinet/shared-types').OpenApiSubmitReplenishmentLinesRequest
    ),
  completeReplenishmentTask: (taskId: number) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto>(
      `/api/v2/merchant/replenishment/tasks/${taskId}/complete`,
      'POST'
    ),
  listReplenishmentEvidence: (taskId: number) =>
    request<import('@aicabinet/shared-types').FileAttachmentDto[]>(
      `/api/v2/merchant/replenishment/tasks/${taskId}/evidence`
    ),
  uploadReplenishmentEvidence: (taskId: number, filePath: string) =>
    uploadReplenishmentEvidenceFile(taskId, filePath),
  downloadReplenishmentEvidence: (taskId: number, fileId: number) =>
    downloadReplenishmentEvidenceFile(taskId, fileId),
  expiryAlerts: () =>
    request<import('@aicabinet/shared-types').OpenApiPullOffTaskDto[]>(
      '/api/v2/merchant/expiry-alerts'
    ),
  slotDiscrepancies: (deviceId?: string) => {
    const q = deviceId ? `?deviceId=${encodeURIComponent(deviceId)}` : '';
    return request<MerchantSlotDiscrepancy[]>(`/api/v2/merchant/slot-discrepancies${q}`);
  },
  deviceReports: () =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDeviceReportDto[]>(
      '/api/v2/merchant/device-reports'
    ),
  updateMerchantProfile: (body: MerchantProfileUpdate) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDto[]>(
      '/api/v2/merchant/profile',
      'PATCH',
      body
    ),
  disputes: (status?: string, page = 0, size = 100) =>
    request<
      import('@aicabinet/shared-types').PageResult<
        import('@aicabinet/shared-types').OpenApiMerchantDisputeSummaryDto
      >
    >(withQuery('/api/v2/merchant/disputes', { page, size, status })),
  orders: (
    opts: {
      deviceId?: string;
      status?: string;
      from?: string;
      to?: string;
      keyword?: string;
      page?: number;
      size?: number;
    } = {}
  ) => {
    const { deviceId, status, from, to, keyword, page = 0, size = 50 } = opts;
    return request<
      import('@aicabinet/shared-types').PageResult<
        import('@aicabinet/shared-types').OpenApiOrderReadModelMerchant
      >
    >(
      withQuery('/api/v2/merchant/orders', {
        page,
        size,
        deviceId,
        status,
        from,
        to,
        keyword
      })
    );
  },
  orderDetail: (orderId: string) =>
    request<import('@aicabinet/shared-types').OpenApiOrderReadModelMerchant>(
      `/api/v2/merchant/orders/${encodeURIComponent(orderId)}`
    ),
  disputeDetail: (ticketId: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDisputeDetailDto>(
      `/api/v2/merchant/disputes/${encodeURIComponent(ticketId)}`
    ),
  disputeReply: (ticketId: string, body: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDisputeDetailDto>(
      `/api/v2/merchant/disputes/${encodeURIComponent(ticketId)}/reply`,
      'POST',
      { body }
    ),
  disputeResolve: (
    ticketId: string,
    body: {
      resolutionType: 'KEEP' | 'WAIVE' | 'CONFIRM';
      restoreInventory?: boolean;
      items?: { skuId: string; quantity: number }[];
    }
  ) =>
    request<{ message?: string; resolutionType?: string }>(
      `/api/v2/merchant/disputes/${encodeURIComponent(ticketId)}/resolve`,
      'POST',
      body
    ),
  disputeClaim: (ticketId: string) =>
    request<import('@aicabinet/shared-types').OpenApiDisputeTicketDto>(
      `/api/v2/merchant/disputes/${encodeURIComponent(ticketId)}/claim`,
      'POST'
    ),
  notifications: (limit = 50) =>
    request<MerchantNotificationDto[]>(`/api/v2/merchant/notifications?limit=${limit}`),
  notificationUnreadCount: () =>
    request<{ count: number }>('/api/v2/merchant/notifications/unread-count'),
  markNotificationRead: (id: number) =>
    request<void>(`/api/v2/merchant/notifications/${id}/read`, 'POST')
};

/** @deprecated 使用 OpenApiOrderReadModelMerchant；保留别名避免旧 import 立刻炸掉 */
export type MerchantOrderSummary = import('@aicabinet/shared-types').OpenApiOrderReadModelMerchant;

/**
 * @deprecated 列表契约为 OpenApiMerchantDisputeSummaryDto。
 * lastMessage / canReply 为前端可选投影（摘要接口通常不下发）。
 */
export type MerchantDisputeTicket =
  import('@aicabinet/shared-types').OpenApiMerchantDisputeSummaryDto & {
    lastMessage?: string;
    canReply?: boolean;
  };

/** @deprecated 使用 OpenApiMerchantDisputeDetailDto */
export type MerchantDisputeDetail =
  import('@aicabinet/shared-types').OpenApiMerchantDisputeDetailDto;

/**
 * 详情弹层视图：契约工单 + 前端从 messages 拼出的最近一条文案。
 * lastMessage 非 OpenAPI 字段。
 */
export type MerchantDisputeDetailView =
  import('@aicabinet/shared-types').OpenApiDisputeTicketDto & {
    lastMessage?: string;
  };

/** @deprecated 使用 OpenApiNotificationDto（与消费者通知同契约） */
export type MerchantNotificationDto = import('@aicabinet/shared-types').OpenApiNotificationDto;

export function canEditPlanogram(me: import('@aicabinet/shared-types').MerchantMe | null) {
  if (!me?.merchants?.length) return false;
  return me.merchants.some((m) => m.allowMerchantPlanogramEdit);
}

export function canEditPricing(me: import('@aicabinet/shared-types').MerchantMe | null) {
  if (!me?.merchants?.length) return false;
  return me.merchants.some((m) => m.allowMerchantPricingEdit);
}

/** 若依风格：精确码或分段通配 merchant:replenishment:*（@aicabinet/shared-rbac） */
export function hasPerm(
  me: import('@aicabinet/shared-types').MerchantMe | null | undefined,
  code: string
) {
  return matchPermission(me?.permissions, code);
}

export function alertTypeLabel(type: string) {
  return displayLabel('merchant_alert_type', type, '告警');
}

export function merchantAlertTitle(_type: string, title: string) {
  return String(title || '').replaceAll('设备', '柜机');
}
