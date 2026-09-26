import { API_BASE_URL } from '@/config/api';
import { showError } from '@/utils/notify';
import { clearDictOverrides, displayLabel } from '@aicabinet/shared-dict';
import { matchPermission } from '@aicabinet/shared-rbac';
import { loadRuntimeDict as sharedLoadRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
import { localizeApiMessage } from '@aicabinet/shared-uni/format';
import { withQuery } from '@aicabinet/shared-uni/query';
import {
  mpRequest,
  createMpApiError,
  type MpApiSession,
  type MpRefreshData
} from '@aicabinet/shared-uni/request';
import type {
  LoginResponse,
  OpenApiDeviceInventoryDto,
  OpenApiNotificationDto,
  OpenApiReplenishmentSuggestDto,
  OpenApiMerchantReplenishmentEfficiencyDto,
  OpenApiSlotDiscrepancyAlertDto,
  OpenApiUpdateMerchantProfileRequest,
  OpenApiDisputeTicketDto,
  OpenApiMerchantDisputeSummaryDto
} from '@aicabinet/shared-types';
import {
  exceptionPageCount,
  mergeExceptionRowsById,
  OPEN_EXCEPTIONS_DEFAULT_MAX_PAGES
} from '@/utils/exception-pages';
import { merchantOrderVideoUrl } from '@/utils/order-video-url';
import { AuthEndpoints, MerchantEndpoints } from '@/api/endpoints';

export { merchantOrderVideoUrl } from '@/utils/order-video-url';

/** 相对 MerchantEndpoints 路径 → 绝对 URL（上传/下载/导出）。 */
function merchantAbsUrl(path: string): string {
  return `${API_BASE_URL.replace(/\/$/, '')}${path}`;
}

const TOKEN_KEY = 'merchant_token';
const USER_KEY = 'merchant_user_id';
const COOKIE_AUTH_KEY = 'merchant_cookie_auth';

function isMerchantH5Runtime() {
  return (
    typeof window !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    !/miniProgram|miniprogram/i.test(navigator.userAgent)
  );
}

export function isMerchantCookieAuth() {
  return isMerchantH5Runtime() && uni.getStorageSync(COOKIE_AUTH_KEY) === '1';
}

/** 登录态：本地 JWT 或 H5 Cookie 会话标记（商户走 admin_session Cookie） */
export function isMerchantLoggedIn() {
  return Boolean(getToken()) || isMerchantCookieAuth();
}

export function getToken() {
  return uni.getStorageSync(TOKEN_KEY) || '';
}

/** 登录页路径（去 query / 前后斜杠后比对）。 */
export function isMerchantLoginPath(url: string): boolean {
  const path = String(url || '')
    .split('?')[0]
    .replace(/^\/+/, '')
    .replace(/\/+$/, '');
  return path === 'pages/login/login' || path.endsWith('/pages/login/login');
}

function applyLoginSession(data: Partial<LoginResponse> & MpRefreshData) {
  if (isMerchantH5Runtime() && (data.cookieEnabled || isMerchantCookieAuth())) {
    uni.removeStorageSync(TOKEN_KEY);
    uni.setStorageSync(COOKIE_AUTH_KEY, '1');
  } else if (data.token) {
    uni.setStorageSync(TOKEN_KEY, data.token);
    uni.removeStorageSync(COOKIE_AUTH_KEY);
  }
  if (data.userId != null) {
    uni.setStorageSync(USER_KEY, String(data.userId));
  }
}

const mpApiSession: MpApiSession = {
  baseUrl: API_BASE_URL,
  timeoutMs: 20_000,
  getToken,
  hasSession: isMerchantLoggedIn,
  useCookieAuth: isMerchantCookieAuth,
  clearSession,
  applyRefreshedToken: (data) => applyLoginSession(data),
  handleUnauthorized
};

export function clearSession() {
  uni.removeStorageSync(TOKEN_KEY);
  uni.removeStorageSync(USER_KEY);
  uni.removeStorageSync(COOKIE_AUTH_KEY);
  uni.removeStorageSync('merchant_me');
  clearDictOverrides();
  // M-P2-5：运行时挂钩清 useMerchantMe 内存（避免与 composable 循环依赖）
  sessionClearHooks.forEach((fn) => {
    try {
      fn();
    } catch {
      /* ignore hook errors */
    }
  });
}

const sessionClearHooks = new Set<() => void>();

/** 注册登出/401 时的额外清理（如模块单例 me）。 */
export function registerMerchantSessionClearHook(fn: () => void): void {
  sessionClearHooks.add(fn);
}

let unauthorizedHandling = false;
let navGuardInstalled = false;

/**
 * 统一导航守卫：无登录态时拦截业务跳转并 reLaunch 登录。
 * 冷启动深链仍依赖 App.onLaunch；本拦截覆盖运行期 navigate/redirect/reLaunch/switchTab。
 */
export function installMerchantNavGuard() {
  if (navGuardInstalled) return;
  navGuardInstalled = true;
  const guard = {
    invoke(args: { url?: string }) {
      const url = String(args?.url || '');
      if (isMerchantLoginPath(url)) return true;
      if (isMerchantLoggedIn()) return true;
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
    if (!isMerchantLoggedIn()) {
      reject(new Error('请先登录'));
      return;
    }
    const header: Record<string, string> = { 'X-Requested-With': 'XMLHttpRequest' };
    const token = getToken();
    if (token) header.Authorization = 'Bearer ' + token;
    uni.downloadFile({
      url,
      header,
      // #ifdef H5
      withCredentials: true,
      // #endif
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
        showError('文件已下载，请从文件管理打开');
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
  return request<LoginResponse>(
    AuthEndpoints.merchantPasswordLogin,
    'POST',
    { phoneNumber: phone, password },
    false
  ).then(async (data) => {
    applyLoginSession(data);
    await sharedLoadRuntimeDict({
      getToken: getToken,
      fetchRuntime: () => request(MerchantEndpoints.dictsRuntime, 'GET')
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

/**
 * @deprecated 请从 `@/utils/soft-fallback` 导入；此处 re-export 兼容旧引用。
 */
export { softFallback } from '@/utils/soft-fallback';

/** 鉴权 multipart 上传；两处凭证上传共用，避免重复 try/catch 解析。 */
function uploadMerchantAuthedFile(
  url: string,
  filePath: string
): Promise<import('@aicabinet/shared-types').FileAttachmentDto> {
  return new Promise((resolve, reject) => {
    if (!isMerchantLoggedIn()) {
      reject(new Error('请先登录'));
      return;
    }
    const header: Record<string, string> = {
      'X-Requested-With': 'XMLHttpRequest'
    };
    const token = getToken();
    if (token) header.Authorization = 'Bearer ' + token;
    uni.uploadFile({
      url,
      filePath,
      name: 'file',
      header,
      // #ifdef H5
      withCredentials: true,
      // #endif
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

export function uploadReplenishmentEvidenceFile(
  taskId: number,
  filePath: string
): Promise<import('@aicabinet/shared-types').FileAttachmentDto> {
  return uploadMerchantAuthedFile(
    merchantAbsUrl(MerchantEndpoints.replenishmentTaskEvidence(taskId)),
    filePath
  );
}

export function uploadReplenishmentRequestEvidenceFile(
  filePath: string
): Promise<import('@aicabinet/shared-types').FileAttachmentDto> {
  return uploadMerchantAuthedFile(
    merchantAbsUrl(MerchantEndpoints.replenishmentRequestEvidence),
    filePath
  );
}

/** Auth-aware download for evidence stream URLs (image tags cannot send Bearer). */
export function downloadReplenishmentEvidenceFile(taskId: number, fileId: number): Promise<string> {
  return downloadAuthedFile(
    merchantAbsUrl(MerchantEndpoints.replenishmentTaskEvidenceFile(taskId, fileId))
  );
}

export function downloadReplenishmentRequestEvidenceFile(
  requestId: number,
  fileId: number
): Promise<string> {
  return downloadAuthedFile(
    merchantAbsUrl(MerchantEndpoints.replenishmentRequestEvidenceFile(requestId, fileId))
  );
}

export const merchantApi = {
  me: () => request<import('@aicabinet/shared-types').MerchantMe>(MerchantEndpoints.me),
  stats: () =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDashboardStatsDto>(
      MerchantEndpoints.stats
    ),
  trend: (days = 7) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantTrendDto>(
      MerchantEndpoints.trend(days)
    ),
  devices: () =>
    request<import('@aicabinet/shared-types').MerchantDeviceInfo[]>(MerchantEndpoints.devices),
  deviceSettings: (id: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDeviceSettingsDto>(
      MerchantEndpoints.deviceSettings(id)
    ),
  updateDeviceSettings: (
    id: string,
    body: import('@aicabinet/shared-types').OpenApiUpdateMerchantDeviceSettingsRequest
  ) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDeviceSettingsDto>(
      MerchantEndpoints.deviceSettings(id),
      'PATCH',
      body
    ),
  deviceSlots: (id: string) =>
    request<import('@aicabinet/shared-types').DeviceSlot[]>(MerchantEndpoints.deviceSlots(id)),
  upsertSlots: (id: string, body: import('@aicabinet/shared-types').UpsertDeviceSlotRequest[]) =>
    request(MerchantEndpoints.deviceSlots(id), 'PUT', body),
  pricing: (deviceId?: string) => {
    const path = deviceId
      ? MerchantEndpoints.pricingSkusByDevice(deviceId)
      : MerchantEndpoints.pricingSkus;
    return request<import('@aicabinet/shared-types').MerchantSkuPricing[]>(path);
  },
  updatePricing: (
    skuId: string,
    body: { deviceId: string; priceCents: number | null; expectedVersion?: number | null }
  ) =>
    request<import('@aicabinet/shared-types').MerchantSkuPricing>(
      MerchantEndpoints.pricingSku(skuId),
      'PATCH',
      body
    ),
  workbench: () =>
    request<import('@aicabinet/shared-types').MerchantWorkbench>(MerchantEndpoints.workbench),
  listAnnouncements: () =>
    request<import('@aicabinet/shared-types').AnnouncementDto[]>(MerchantEndpoints.announcements),
  getAnnouncement: (id: number) =>
    request<import('@aicabinet/shared-types').AnnouncementDto>(MerchantEndpoints.announcement(id)),
  teamUsers: () =>
    request<import('@aicabinet/shared-types').MerchantUserDto[]>(MerchantEndpoints.teamUsers),
  teamRoles: () =>
    request<import('@aicabinet/shared-types').MerchantTeamRoleDto[]>(MerchantEndpoints.teamRoles),
  createTeamUser: (body: {
    phoneNumber: string;
    password: string;
    displayName?: string;
    roleKey?: string;
  }) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      MerchantEndpoints.teamUsers,
      'POST',
      body
    ),
  updateTeamUser: (userId: number, body: { displayName?: string; roleKey?: string }) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      MerchantEndpoints.teamUser(userId),
      'PATCH',
      body
    ),
  disableTeamUser: (userId: number) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      MerchantEndpoints.teamUserDisable(userId),
      'POST'
    ),
  enableTeamUser: (userId: number) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      MerchantEndpoints.teamUserEnable(userId),
      'POST'
    ),
  resetTeamUserPassword: (userId: number, password: string) =>
    request<import('@aicabinet/shared-types').MerchantUserDto>(
      MerchantEndpoints.teamUserResetPassword(userId),
      'POST',
      { password }
    ),
  notifyPrefs: () =>
    request<import('@aicabinet/shared-types').OpenApiMerchantNotifyPrefDto>(
      MerchantEndpoints.notifyPrefs
    ),
  notifyWxBind: (code: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantNotifyPrefDto>(
      MerchantEndpoints.notifyWxBind,
      'POST',
      {
        code
      }
    ),
  notifySubscribe: (alertTypes: string[]) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantNotifyPrefDto>(
      MerchantEndpoints.notifySubscribe,
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
    >(MerchantEndpoints.exceptions(status, page, size)),
  /** OPEN + PROCESSING；默认最多各拉 3 页；首页可传 maxPages=1 降扇出（M4） */
  openExceptions: async (pageSize = 100, options?: { maxPages?: number }) => {
    type ExRow = import('@aicabinet/shared-types').OpenApiOpsExceptionDto;
    const size = Math.min(Math.max(pageSize, 1), 100);
    const maxPages = options?.maxPages ?? OPEN_EXCEPTIONS_DEFAULT_MAX_PAGES;
    const mergePages = async (status: string) => {
      const first = await merchantApi.exceptions(status, 0, size);
      const items: ExRow[] = [...(first.items || [])];
      const total = first.total ?? items.length;
      const pages = exceptionPageCount({ total, pageSize: size, maxPages });
      if (pages > 1) {
        const rest = await Promise.all(
          Array.from({ length: pages - 1 }, (_, i) => merchantApi.exceptions(status, i + 1, size))
        );
        for (const next of rest) {
          items.push(...(next.items || []));
        }
      }
      return { items, total };
    };
    // M-P2-11：允许单侧失败保留另一侧；双侧失败向上抛，由页面 softFallback/展示错误
    const settled = await Promise.allSettled([mergePages('OPEN'), mergePages('PROCESSING')]);
    const open =
      settled[0].status === 'fulfilled' ? settled[0].value : { items: [] as ExRow[], total: 0 };
    const processing =
      settled[1].status === 'fulfilled' ? settled[1].value : { items: [] as ExRow[], total: 0 };
    if (settled[0].status === 'rejected' && settled[1].status === 'rejected') {
      const reason = settled[0].reason;
      throw reason instanceof Error ? reason : new Error('异常列表加载失败');
    }
    return {
      items: mergeExceptionRowsById(open.items, processing.items),
      total: (open.total || 0) + (processing.total || 0)
    };
  },
  resolveInventoryException: (id: string, resolution: string) =>
    request(MerchantEndpoints.exceptionResolve(id), 'POST', {
      resolution
    }),
  analytics: (days = 30) =>
    request<import('@aicabinet/shared-types').MerchantAnalyticsOverview>(
      MerchantEndpoints.analyticsOverview(days)
    ),
  salesReports: (dim = 'PRODUCT', fromDate?: string, toDate?: string) =>
    request<import('@aicabinet/shared-types').OpenApiSalesReportRowDto[]>(
      withQuery(MerchantEndpoints.analyticsSalesReports, { dim, fromDate, toDate })
    ),
  /**
   * 商户端公开配置（只含非敏感 UI 开关，如经营分析图表）。
   * 走匿名端点，故 `auth=false`。
   */
  merchantPublicConfig: () =>
    request<Record<string, string>>(MerchantEndpoints.publicMerchantConfig, 'GET', null, false),
  skuSales: (days = 30, deviceId?: string) =>
    request<import('@aicabinet/shared-types').MerchantSkuSales[]>(
      withQuery(MerchantEndpoints.analyticsSkuSales, { days, deviceId })
    ),
  skuVelocity: (deviceId: string) =>
    request<import('@aicabinet/shared-types').MerchantSkuVelocity[]>(
      MerchantEndpoints.analyticsVelocity(deviceId)
    ),
  aiInsight: (days = 30) =>
    request<import('@aicabinet/shared-types').MerchantAiInsight>(
      MerchantEndpoints.analyticsAiInsight(days)
    ),
  expirySummary: () =>
    request<import('@aicabinet/shared-types').MerchantExpirySummary>(
      MerchantEndpoints.analyticsExpirySummary
    ),
  deviceTemperatureHistory: (deviceId: string, hours = 24) =>
    request<import('@aicabinet/shared-types').DeviceTemperatureReading[]>(
      MerchantEndpoints.deviceTemperatureHistory(deviceId, hours)
    ),
  pricingHistory: (deviceId?: string, skuId?: string) =>
    request<import('@aicabinet/shared-types').MerchantSkuPriceChange[]>(
      withQuery(MerchantEndpoints.pricingHistory, { deviceId, skuId })
    ),
  settlements: () =>
    request<import('@aicabinet/shared-types').MerchantSettlementOverview>(
      MerchantEndpoints.settlementsOverview
    ),
  lineWallet: () =>
    request<import('@aicabinet/shared-types').OpenApiLineWalletOverviewDto>(
      MerchantEndpoints.lineWallet
    ),
  lineWalletWithdraw: (body: { amountCents: number; requestNo?: string }) =>
    request(MerchantEndpoints.lineWalletWithdraw, 'POST', body),
  /** 多商户绑定时可指定 merchantId（后端 merchantOverview 校验归属） */
  wallet: (merchantId?: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantWalletOverviewDto>(
      withQuery(MerchantEndpoints.wallet, { merchantId })
    ),
  /** 多商户绑定时必须显式带 merchantId，否则后端 400「请指定提现商户」（H53） */
  walletWithdraw: (body: { amountCents: number; requestNo?: string; merchantId?: string }) =>
    request(MerchantEndpoints.walletWithdraw, 'POST', body),
  dailySettlements: (from: string, to: string) =>
    request<import('@aicabinet/shared-types').MerchantDailySettlement[]>(
      MerchantEndpoints.settlementsDaily(from, to)
    ),
  settlementBatches: (from: string, to: string) =>
    request<import('@aicabinet/shared-types').MerchantSettlementBatch[]>(
      MerchantEndpoints.settlementsBatches(from, to)
    ),
  revenueSplits: (page = 0, size = 50, status?: string, from?: string, to?: string) =>
    request<
      import('@aicabinet/shared-types').PageResult<import('@aicabinet/shared-types').RevenueSplit>
    >(withQuery(MerchantEndpoints.revenueSplits, { page, size, status, from, to })),
  exportSettlementsUrl: (from: string, to: string) =>
    merchantAbsUrl(MerchantEndpoints.settlementsExport(from, to)),
  exportOrdersUrl: (deviceId?: string) => merchantAbsUrl(MerchantEndpoints.ordersExport(deviceId)),
  exportDeviceReportsUrl: () => merchantAbsUrl(MerchantEndpoints.deviceReportsExport),
  /** 订单购物视频绝对 URL（页内禁止再拼 API_BASE + path） */
  orderVideoUrl: merchantOrderVideoUrl,
  replenishmentSuggestions: (deviceId: string) =>
    request<OpenApiReplenishmentSuggestDto[]>(MerchantEndpoints.replenishmentSuggestions(deviceId)),
  getTaxProfile: (merchantId: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantTaxProfileDto>(
      MerchantEndpoints.taxProfileByMerchant(merchantId)
    ),
  saveTaxProfile: (body: import('@aicabinet/shared-types').OpenApiMerchantTaxProfileDto) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantTaxProfileDto>(
      MerchantEndpoints.taxProfile,
      'PUT',
      body
    ),
  myReplenishmentEfficiency: () =>
    request<OpenApiMerchantReplenishmentEfficiencyDto>(MerchantEndpoints.replenishmentEfficiency),
  /** 缺货巡柜：全部低库存 SKU 明细（按柜聚合由页面完成） */
  lowStockDevices: () => request<OpenApiDeviceInventoryDto[]>(MerchantEndpoints.inventoryLowStock),
  replenishmentRequests: (status?: string, deviceId?: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantReplenishmentRequestDto[]>(
      withQuery(MerchantEndpoints.replenishmentRequests, { status, deviceId })
    ),
  submitReplenishmentRequest: (
    body: import('@aicabinet/shared-types').OpenApiCreateMerchantReplenishmentRequest
  ) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantReplenishmentRequestDto>(
      MerchantEndpoints.replenishmentRequests,
      'POST',
      body
    ),
  listReplenishmentRequestEvidence: (requestId: number) =>
    request<import('@aicabinet/shared-types').FileAttachmentDto[]>(
      MerchantEndpoints.replenishmentRequestEvidenceList(requestId)
    ),
  uploadReplenishmentRequestEvidence: (filePath: string) =>
    uploadReplenishmentRequestEvidenceFile(filePath),
  downloadReplenishmentRequestEvidence: (requestId: number, fileId: number) =>
    downloadReplenishmentRequestEvidenceFile(requestId, fileId),
  replenishmentTasks: (status?: string) => {
    const path = status
      ? MerchantEndpoints.replenishmentTasksByStatus(status)
      : MerchantEndpoints.replenishmentTasks;
    return request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto[]>(path);
  },
  /** 扫码柜机归属校验：须在当前账号 FIELD 管辖范围 */
  assertReplenishmentDeviceAccess: (deviceId: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantReplenishmentDeviceAccessDto>(
      MerchantEndpoints.replenishmentDeviceAccess(deviceId)
    ),
  /** 补货开门状态：以服务端会话为准 */
  replenishmentDoorSession: (taskId: number) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantReplenishmentDoorSessionDto>(
      MerchantEndpoints.replenishmentDoorSession(taskId)
    ),
  replenishmentTaskLines: (taskId: number) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto[]>(
      MerchantEndpoints.replenishmentTaskLines(taskId)
    ),
  checkInReplenishmentTask: (
    taskId: number,
    body?: import('@aicabinet/shared-types').OpenApiReplenishmentCheckInRequest
  ) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto>(
      MerchantEndpoints.replenishmentTaskCheckIn(taskId),
      'POST',
      body || {}
    ),
  /** 补货员开门：签到后调用，绑定补货任务，不产生消费者账单 */
  openReplenishmentDoor: (taskId: number) =>
    request<import('@aicabinet/shared-types').OpenApiSessionDto>(
      MerchantEndpoints.replenishmentTaskOpenDoor(taskId),
      'POST'
    ),
  confirmReplenishmentLines: (
    taskId: number,
    lines: import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto[]
  ) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto[]>(
      MerchantEndpoints.replenishmentTaskLines(taskId),
      'POST',
      { lines } satisfies import('@aicabinet/shared-types').OpenApiSubmitReplenishmentLinesRequest
    ),
  completeReplenishmentTask: (taskId: number) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto>(
      MerchantEndpoints.replenishmentTaskComplete(taskId),
      'POST'
    ),
  listReplenishmentEvidence: (taskId: number) =>
    request<import('@aicabinet/shared-types').FileAttachmentDto[]>(
      MerchantEndpoints.replenishmentTaskEvidence(taskId)
    ),
  uploadReplenishmentEvidence: (taskId: number, filePath: string) =>
    uploadReplenishmentEvidenceFile(taskId, filePath),
  downloadReplenishmentEvidence: (taskId: number, fileId: number) =>
    downloadReplenishmentEvidenceFile(taskId, fileId),
  expiryAlerts: () =>
    request<import('@aicabinet/shared-types').OpenApiPullOffTaskDto[]>(
      MerchantEndpoints.expiryAlerts
    ),
  slotDiscrepancies: (deviceId?: string) => {
    return request<OpenApiSlotDiscrepancyAlertDto[]>(
      deviceId
        ? MerchantEndpoints.slotDiscrepanciesByDevice(deviceId)
        : MerchantEndpoints.slotDiscrepancies
    );
  },
  deviceReports: () =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDeviceReportDto[]>(
      MerchantEndpoints.deviceReports
    ),
  updateMerchantProfile: (body: OpenApiUpdateMerchantProfileRequest) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDto[]>(
      MerchantEndpoints.profile,
      'PATCH',
      body
    ),
  disputes: (status?: string, page = 0, size = 50) =>
    request<
      import('@aicabinet/shared-types').PageResult<
        import('@aicabinet/shared-types').OpenApiMerchantDisputeSummaryDto
      >
    >(withQuery(MerchantEndpoints.disputes, { page, size, status })),
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
      withQuery(MerchantEndpoints.orders, {
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
      MerchantEndpoints.orderDetail(orderId)
    ),
  disputeDetail: (ticketId: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDisputeDetailDto>(
      MerchantEndpoints.disputeDetail(ticketId)
    ),
  disputeReply: (ticketId: string, body: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDisputeDetailDto>(
      MerchantEndpoints.disputeReply(ticketId),
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
      MerchantEndpoints.disputeResolve(ticketId),
      'POST',
      body
    ),
  disputeClaim: (ticketId: string) =>
    request<import('@aicabinet/shared-types').OpenApiDisputeTicketDto>(
      MerchantEndpoints.disputeClaim(ticketId),
      'POST'
    ),
  notifications: (limit = 50) =>
    request<OpenApiNotificationDto[]>(MerchantEndpoints.notifications(limit)),
  notificationUnreadCount: () =>
    request<{ count: number }>(MerchantEndpoints.notificationsUnreadCount),
  markNotificationRead: (id: number) =>
    request<void>(MerchantEndpoints.notificationRead(id), 'POST')
};

/**
 * 争议列表行视图：摘要契约 + 前端可选投影（lastMessage / canReply 非 OpenAPI 字段）。
 */
export type MerchantDisputeTicket = OpenApiMerchantDisputeSummaryDto & {
  lastMessage?: string;
  canReply?: boolean;
};

/**
 * 争议详情弹层视图：契约工单 + 前端从 messages 拼出的最近一条文案。
 */
export type MerchantDisputeDetailView = OpenApiDisputeTicketDto & {
  lastMessage?: string;
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
