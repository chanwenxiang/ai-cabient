import { API_BASE_URL } from '@/config/api';
import { showError } from '@/utils/notify';
import { clearDictOverrides, displayLabel } from '@aicabinet/shared-dict';
import { matchPermission } from '@aicabinet/shared-rbac';
import { loadRuntimeDict as sharedLoadRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
import { localizeApiMessage } from '@aicabinet/shared-uni/format';
import { withQuery } from '@aicabinet/shared-uni/query';
import { clearPreferredDeviceId } from '@/utils/preferred-device';
import {
  mpRequest,
  createMpApiError,
  refreshTokenSilently,
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
import { AuthEndpoints, MerchantEndpoints } from '@/api/endpoints';

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
  // 修复 2026-10-14：换账号/登出清掉上一账号的客户端偏好与本地缓存
  // （补货员共用手机场景：常驻柜筛选、任务门态、争议证据下载缓存）。
  try {
    clearPreferredDeviceId();
  } catch {
    /* ignore */
  }
  try {
    const info = uni.getStorageInfoSync();
    for (const key of info.keys || []) {
      if (key.startsWith('replenish_door_')) uni.removeStorageSync(key);
    }
  } catch {
    /* ignore */
  }
  disputeEvidenceLocalCache.clear();
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
 * 接口层审计（2026-10-20）H3：401 原直接登出踢回登录页——现场导出/下载证据时 token
 * 过期（20 分钟）会被硬中断。对齐 JSON 请求层：静默刷新后重试一次，仍失败才登出。
 */
export function downloadAuthedFile(url: string, timeoutMs = 60_000): Promise<string> {
  return downloadAuthedFileOnce(url, timeoutMs).catch(async (e) => {
    if (e instanceof Error && e.message === 'DOWNLOAD_AUTH_EXPIRED') {
      const ok = await refreshTokenSilently(mpApiSession).catch(() => false);
      if (ok) return downloadAuthedFileOnce(url, timeoutMs);
      throw handleUnauthorized();
    }
    throw e;
  });
}

function downloadAuthedFileOnce(url: string, timeoutMs: number): Promise<string> {
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
          reject(new Error('DOWNLOAD_AUTH_EXPIRED'));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && res.tempFilePath) {
          resolve(res.tempFilePath);
          return;
        }
        reject(new Error(`下载失败 (${res.statusCode})`));
      },
      fail(err) {
        // 不带原始 errMsg（第五轮门禁类 D）：同 consumer-api 口径
        reject(new Error('下载失败'));
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

/** L2-2：图形验证码（连续登录失败后后端强制校验）。 */
export function fetchCaptcha() {
  return request<{ captchaId: string; imageBase64: string }>(
    AuthEndpoints.captcha,
    'GET',
    null,
    false
  );
}

export function merchantLogin(
  phone: string,
  password: string,
  // L2-2：连续失败后后端要求图形验证码
  captcha?: { captchaId: string; captchaCode: string }
) {
  return request<LoginResponse>(
    AuthEndpoints.merchantPasswordLogin,
    'POST',
    { phoneNumber: phone, password, ...(captcha ?? {}) },
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

/** CB-048：商户微信免密登录（openid 须已绑定商户账号；2FA 商户回 challenge）。 */
export function merchantWxLogin(code: string) {
  return request<LoginResponse>(AuthEndpoints.merchantWxLogin, 'POST', { code }, false).then(
    async (data) => {
      applyLoginSession(data);
      await sharedLoadRuntimeDict({
        getToken: getToken,
        fetchRuntime: () => request(MerchantEndpoints.dictsRuntime, 'GET')
      });
      return data;
    }
  );
}

/**
 * CB-048：绑定当前微信到已登录商户账号（幂等；换微信号=密码登录后重绑覆盖）。
 * 只能在密码登录成功后的会话内调用（后端校验商户 realm）。
 */
export function merchantWxBind(code: string) {
  return request<void>(AuthEndpoints.merchantWxBind, 'POST', { code }, true);
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

/** 鉴权 multipart 上传；两处凭证上传共用，避免重复 try/catch 解析。
 *  接口层审计（2026-10-20）H3：401 对齐 JSON 层——静默刷新后重试一次，仍失败才登出。 */
function uploadMerchantAuthedFile(
  url: string,
  filePath: string
): Promise<import('@aicabinet/shared-types').FileAttachmentDto> {
  return uploadMerchantAuthedFileOnce(url, filePath).catch(async (e) => {
    if (e instanceof Error && e.message === 'UPLOAD_AUTH_EXPIRED') {
      const ok = await refreshTokenSilently(mpApiSession).catch(() => false);
      if (ok) return uploadMerchantAuthedFileOnce(url, filePath);
      throw handleUnauthorized();
    }
    throw e;
  });
}

function uploadMerchantAuthedFileOnce(
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
          reject(new Error('UPLOAD_AUTH_EXPIRED'));
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
        // 不带原始 errMsg（第五轮门禁类 D）：uploadFile:fail 类英文平台串不上浮
        reject(new Error('网络错误'));
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

/** 盘点现场照片上传（V349，refType=STOCKTAKE）。 */
export function uploadStocktakeEvidenceFile(
  stocktakeId: number,
  filePath: string
): Promise<import('@aicabinet/shared-types').FileAttachmentDto> {
  return uploadMerchantAuthedFile(
    merchantAbsUrl(MerchantEndpoints.warehouseStocktakeEvidence(stocktakeId)),
    filePath
  );
}

/** Auth-aware download for evidence stream URLs (image tags cannot send Bearer). */
export function downloadReplenishmentEvidenceFile(taskId: number, fileId: number): Promise<string> {
  return downloadAuthedFile(
    merchantAbsUrl(MerchantEndpoints.replenishmentTaskEvidenceFile(taskId, fileId))
  );
}

/** 盘点照片鉴权下载（image 标签无法携带 Bearer）。 */
export function downloadStocktakeEvidenceFile(
  stocktakeId: number,
  fileId: number
): Promise<string> {
  return downloadAuthedFile(
    merchantAbsUrl(MerchantEndpoints.warehouseStocktakeEvidenceFile(stocktakeId, fileId))
  );
}

/**
 * 争议证据本地临时路径缓存：FIFO 上限 50（2026-10-23 第四轮审计，
 * 同 C 端 BoundedFifoCache 口径）。Map 保持插入序，超限逐出最旧——
 * 此前无上限，长会话浏览争议图会无界增长；临时文件被系统回收后
 * 展示层 image error 会回退占位，不影响正确性。
 */
const DISPUTE_EVIDENCE_CACHE_MAX = 50;
const disputeEvidenceLocalCache = new Map<string, string>();

function cacheDisputeEvidence(key: string, local: string) {
  disputeEvidenceLocalCache.set(key, local);
  while (disputeEvidenceLocalCache.size > DISPUTE_EVIDENCE_CACHE_MAX) {
    const oldest = disputeEvidenceLocalCache.keys().next().value;
    if (oldest === undefined) break;
    disputeEvidenceLocalCache.delete(oldest);
  }
}

/**
 * 争议证据图：带鉴权下载到本地临时路径（image 标签无法携带 Bearer）。
 * 失败/无 url 返回 ''，由展示层回退占位——不把 token 拼进 URL（防泄露，对齐 C 端 dispute-evidence）。
 */
export async function downloadDisputeEvidence(img: {
  fileId?: number;
  url?: string;
}): Promise<string> {
  const url = String(img?.url || '').trim();
  if (!url) return '';
  const cacheKey = String(img.fileId ?? url);
  const cached = disputeEvidenceLocalCache.get(cacheKey);
  if (cached) return cached;
  try {
    const local = await downloadAuthedFile(merchantAbsUrl(url));
    cacheDisputeEvidence(cacheKey, local);
    return local;
  } catch {
    return '';
  }
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
  /** CB-050：柜机完整详情（DeviceDetailDto=device+metrics+slots+inventory） */
  deviceDetail: (id: string) =>
    request<import('@aicabinet/shared-types').OpenApiDeviceDetailDto>(
      MerchantEndpoints.deviceDetail(id)
    ),
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
  /** CB-050 扩展：柜机扫码盘点（单货道实盘；凭证 URL 可选，按商户 ops-policy 强制） */
  deviceSlotStocktake: (id: string, body: import('@aicabinet/shared-types').SlotStocktakeRequest) =>
    request<import('@aicabinet/shared-types').DeviceSlot>(
      MerchantEndpoints.deviceSlotStocktake(id),
      'POST',
      body
    ),
  /** CB-050 扩展：柜机盘点凭证上传（返回附件含 url，回填盘点请求 photoEvidenceUrl） */
  uploadDeviceStocktakeEvidenceFile: (deviceId: string, filePath: string) =>
    uploadMerchantAuthedFile(
      merchantAbsUrl(MerchantEndpoints.deviceStocktakeEvidence(deviceId)),
      filePath
    ),
  /** CB-050 扩展：理货记录-配货（补货任务行粒度） */
  deviceTallyRecords: (id: string, page = 0, size = 20, days = 30) =>
    request<import('@aicabinet/shared-types').OpenApiPageResultTallyRecordRowDto>(
      MerchantEndpoints.deviceTallyRecords(id, page, size, days)
    ),
  /** CB-050 扩展：理货记录-盘点（AISLE_AUDIT 事件流） */
  deviceStocktakeEvents: (id: string, page = 0, size = 20) =>
    request<import('@aicabinet/shared-types').OpenApiPageResultDeviceOpsEventDto>(
      MerchantEndpoints.deviceStocktakeEvents(id, page, size)
    ),
  /** CB-050 扩展：可切换组织列表（绑定商户 + 下级树） */
  orgs: () => request<import('@aicabinet/shared-types').MerchantOrgDto[]>(MerchantEndpoints.orgs),
  /** CB-050 扩展：自助改密（携带当前密码） */
  changePassword: (oldPassword: string, newPassword: string) =>
    request(MerchantEndpoints.merchantPasswordChange, 'POST', { oldPassword, newPassword }),
  /** CB-050：柜机详情 KPI 汇总（今日销售额/今日订单量/本月销售额） */
  deviceSalesSummary: (id: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantDeviceSalesSummaryDto>(
      MerchantEndpoints.deviceSalesSummary(id)
    ),
  /** CB-050：设备控制指令（merchant:device:control，白名单 LOCK/UNLOCK/REBOOT） */
  deviceCommand: (id: string, body: { command: string; reason?: string }) =>
    request<import('@aicabinet/shared-types').OpenApiDeviceOpsCommandResultDto>(
      MerchantEndpoints.deviceCommand(id),
      'POST',
      body
    ),
  jiangyiGatherStatus: (id: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantGatherStatusDto>(
      MerchantEndpoints.jiangyiGatherStatus(id)
    ),
  jiangyiGatherStart: (id: string, doorPosition?: string) =>
    request(MerchantEndpoints.jiangyiGatherStart(id), 'POST', doorPosition ? { doorPosition } : {}),
  jiangyiGatherExit: (id: string) => request(MerchantEndpoints.jiangyiGatherExit(id), 'POST', {}),
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
  opsConfig: (merchantId?: string) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantOpsConfigDto>(
      merchantId
        ? `${MerchantEndpoints.opsConfig}?merchantId=${encodeURIComponent(merchantId)}`
        : MerchantEndpoints.opsConfig
    ),
  saveOpsConfig: (
    body: import('@aicabinet/shared-types').OpenApiMerchantOpsConfigDto,
    merchantId?: string
  ) =>
    request<import('@aicabinet/shared-types').OpenApiMerchantOpsConfigDto>(
      merchantId
        ? `${MerchantEndpoints.opsConfig}?merchantId=${encodeURIComponent(merchantId)}`
        : MerchantEndpoints.opsConfig,
      'PUT',
      body
    ),
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
  analytics: (days = 30, deviceId?: string) =>
    request<import('@aicabinet/shared-types').MerchantAnalyticsOverview>(
      withQuery(MerchantEndpoints.analyticsOverview, { days, deviceId })
    ),
  salesReports: (dim = 'PRODUCT', fromDate?: string, toDate?: string, deviceId?: string) =>
    request<import('@aicabinet/shared-types').OpenApiSalesReportRowDto[]>(
      withQuery(MerchantEndpoints.analyticsSalesReports, { dim, fromDate, toDate, deviceId })
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
  aiInsight: (days = 30, deviceId?: string) =>
    request<import('@aicabinet/shared-types').MerchantAiInsight>(
      withQuery(MerchantEndpoints.analyticsAiInsight, { days, deviceId })
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
  /** 商户月度结算单（V330 CB-020）：from/to 为 yyyy-MM，可省略走后端默认近 12 个月 */
  monthlySettlementBills: (from?: string, to?: string) =>
    request<import('@aicabinet/shared-types').MerchantSettlementBillDto[]>(
      withQuery(MerchantEndpoints.settlementsMonthlyBills, { from, to })
    ),
  /** 商户账单（图4 对照，2026-10-15）：销售报表口径，month=yyyy-MM，deviceId 空=全部设备 */
  monthlyBill: (month: string, deviceId?: string) =>
    request<import('@aicabinet/shared-types').MerchantMonthlyBill>(
      MerchantEndpoints.settlementsMonthlyBill(month, deviceId)
    ),
  revenueSplits: (page = 0, size = 50, status?: string, from?: string, to?: string) =>
    request<
      import('@aicabinet/shared-types').PageResult<import('@aicabinet/shared-types').RevenueSplit>
    >(withQuery(MerchantEndpoints.revenueSplits, { page, size, status, from, to })),
  exportSettlementsUrl: (from: string, to: string) =>
    merchantAbsUrl(MerchantEndpoints.settlementsExport(from, to)),
  exportOrdersUrl: (deviceId?: string) => merchantAbsUrl(MerchantEndpoints.ordersExport(deviceId)),
  exportDeviceReportsUrl: () => merchantAbsUrl(MerchantEndpoints.deviceReportsExport),
  exportSalesReportsUrl: (dim = 'PRODUCT', fromDate?: string, toDate?: string) =>
    merchantAbsUrl(
      withQuery(MerchantEndpoints.analyticsSalesReportsExport, { dim, fromDate, toDate })
    ),
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
  /** 缺货巡柜聚合：货道级统一口径（与 admin 缺货看板同源），后端已按缺口排序取前 5 */
  shortageSummary: () =>
    request<import('@aicabinet/shared-types').OpenApiMerchantShortageSummaryDto[]>(
      MerchantEndpoints.replenishmentShortageSummary
    ),
  /** 自主补货：直接生成分仓出库 + 补货任务（无审批） */
  selfInitiateReplenishment: (
    body: import('@aicabinet/shared-types').OpenApiMerchantSelfReplenishRequest
  ) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto>(
      MerchantEndpoints.replenishmentSelfInitiate,
      'POST',
      body
    ),
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
  completeReplenishmentTask: (
    taskId: number,
    /** CB-050 收尾：可选定位（证明到场，与盘点凭证定位同语义；后端写入完成审计） */
    location?: { latitude?: number; longitude?: number }
  ) =>
    request<import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto>(
      MerchantEndpoints.replenishmentTaskComplete(taskId),
      'POST',
      location && (location.latitude != null || location.longitude != null) ? location : undefined
    ),
  listReplenishmentEvidence: (taskId: number) =>
    request<import('@aicabinet/shared-types').FileAttachmentDto[]>(
      MerchantEndpoints.replenishmentTaskEvidence(taskId)
    ),
  uploadReplenishmentEvidence: (taskId: number, filePath: string) =>
    uploadReplenishmentEvidenceFile(taskId, filePath),
  downloadReplenishmentEvidence: (taskId: number, fileId: number) =>
    downloadReplenishmentEvidenceFile(taskId, fileId),
  // ── 仓库盘点（V349：仅本人负责的分仓，服务端二次校验） ──
  warehouseStocktakes: (query?: string) =>
    request<import('@aicabinet/shared-types').OpenApiPageResultStocktakeDto>(
      MerchantEndpoints.warehouseStocktakes(query)
    ),
  warehouseStocktakeCreate: (body: { mode: string; notes?: string }) =>
    request<import('@aicabinet/shared-types').OpenApiStocktakeDto>(
      MerchantEndpoints.warehouseStocktakeCreate,
      'POST',
      body
    ),
  warehouseStocktake: (stocktakeId: number) =>
    request<import('@aicabinet/shared-types').OpenApiStocktakeDto>(
      MerchantEndpoints.warehouseStocktake(stocktakeId)
    ),
  warehouseStocktakeUpdateLine: (
    stocktakeId: number,
    lineId: number,
    body: { countedQty: number; notes?: string; diffReason?: string }
  ) =>
    request<import('@aicabinet/shared-types').OpenApiStocktakeLineDto>(
      MerchantEndpoints.warehouseStocktakeLine(stocktakeId, lineId),
      'PUT',
      body
    ),
  warehouseStocktakeComplete: (stocktakeId: number) =>
    request<import('@aicabinet/shared-types').OpenApiStocktakeDto>(
      MerchantEndpoints.warehouseStocktakeComplete(stocktakeId),
      'POST'
    ),
  warehouseStocktakeCompleteAndAdjust: (stocktakeId: number) =>
    request<import('@aicabinet/shared-types').OpenApiStocktakeDto>(
      MerchantEndpoints.warehouseStocktakeCompleteAndAdjust(stocktakeId),
      'POST'
    ),
  warehouseStocktakeCancel: (stocktakeId: number) =>
    request<import('@aicabinet/shared-types').OpenApiStocktakeDto>(
      MerchantEndpoints.warehouseStocktakeCancel(stocktakeId),
      'POST'
    ),
  warehouseStocktakeEvidenceList: (stocktakeId: number) =>
    request<import('@aicabinet/shared-types').FileAttachmentDto[]>(
      MerchantEndpoints.warehouseStocktakeEvidence(stocktakeId)
    ),
  uploadStocktakeEvidence: (stocktakeId: number, filePath: string) =>
    uploadStocktakeEvidenceFile(stocktakeId, filePath),
  downloadStocktakeEvidence: (stocktakeId: number, fileId: number) =>
    downloadStocktakeEvidenceFile(stocktakeId, fileId),
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
      excludeZero?: boolean | number | string;
      page?: number;
      size?: number;
      /** CB-050 收尾：切换组织上下文——按所选商户收窄订单（后端按其设备集过滤） */
      merchantId?: string;
    } = {}
  ) => {
    const {
      deviceId,
      status,
      from,
      to,
      keyword,
      excludeZero,
      page = 0,
      size = 50,
      merchantId
    } = opts;
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
        keyword,
        excludeZero: excludeZero ? 1 : undefined,
        merchantId: merchantId || undefined
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
    request<void>(MerchantEndpoints.notificationRead(id), 'POST'),
  satelliteWarehouse: () =>
    request<{
      warehouseId: string;
      warehouseName: string;
      address?: string;
      status?: string;
    }>(MerchantEndpoints.satelliteWarehouse),
  satellitePurchaseSuppliers: () =>
    request<{ supplierId: string; supplierName: string; status?: string }[]>(
      MerchantEndpoints.satellitePurchaseSuppliers
    ),
  satellitePurchaseSkus: () =>
    request<{ skuId: string; skuName: string; unitCostCents: number }[]>(
      MerchantEndpoints.satellitePurchaseSkus
    ),
  satellitePurchaseOrders: () =>
    request<
      {
        purchaseOrderId: number;
        supplierId: string;
        warehouseId: string;
        status: string;
        refNo?: string;
        notes?: string;
        lines?: {
          lineId?: number;
          skuId: string;
          orderedQty: number;
          receivedQty?: number;
          batchNo?: string;
          expiryDate?: string;
        }[];
      }[]
    >(MerchantEndpoints.satellitePurchaseOrders),
  createSatellitePurchaseOrder: (body: {
    supplierId: string;
    warehouseId?: string;
    notes?: string;
    lines: { skuId: string; orderedQty: number; unitCostCents: number }[];
  }) =>
    request<{ purchaseOrderId: number; status: string; warehouseId: string }>(
      MerchantEndpoints.satellitePurchaseOrders,
      'POST',
      body
    ),
  receiveSatellitePurchaseOrder: (
    purchaseOrderId: number,
    body: {
      lines: {
        lineId?: number;
        skuId: string;
        batchNo: string;
        expiryDate: string;
        receivedQty: number;
      }[];
    }
  ) =>
    request<{ purchaseOrderId: number; status: string }>(
      MerchantEndpoints.satellitePurchaseReceive(purchaseOrderId),
      'POST',
      body
    )
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
