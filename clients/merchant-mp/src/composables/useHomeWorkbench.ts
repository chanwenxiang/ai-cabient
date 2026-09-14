/**
 * 商户工作台数据与导航（M-P2-13）。
 * 从 home.vue 抽出 KPI/待办/补货预览/扫码与跳转，页面只保留模板与样式。
 */
import { computed, ref, type Ref } from 'vue';
import { showError } from '@/utils/notify';
import { hasPerm, isMerchantLoggedIn, merchantApi, softFallback } from '@/utils/merchant-api';
import {
  canAccessNav,
  hasPack,
  useMerchantMe,
  seedMerchantMeDisplayCache,
  peekMerchantMeCacheForDisplay
} from '@/composables/useMerchantMe';
import { MERCHANT_BIZ_NAV, MERCHANT_FIELD_NAV } from '@/config/merchant-nav';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import { displayLabel } from '@aicabinet/shared-dict';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import { formatMerchantNames } from '@/utils/merchant-display';
import { setAlertsTabBadge } from '@/utils/todo-badge';
import { mergeTodoItems, type TodoSourceException, type TodoSourceExpiry } from '@/utils/todo-list';
import type {
  AnnouncementDto,
  MerchantMe,
  MerchantWorkbench,
  OpenApiMerchantDashboardStatsDto
} from '@aicabinet/shared-types';

type TaskRow = { taskId: number; deviceId: string; status: string };

const EMPTY_WORKBENCH: MerchantWorkbench = {
  offlineDevices: 0,
  openDisputes: 0,
  lowStockItems: 0,
  expiryAlerts: 0,
  slotDiscrepancies: 0,
  actionItems: []
};

function softErr<T>(promise: Promise<T>, fallback: T, label: string): Promise<T> {
  return promise.catch((e) => {
    showError((e instanceof Error ? e.message : label).slice(0, 40));
    return fallback;
  });
}

export function useHomeWorkbench() {
  const { me, refresh: refreshMe } = useMerchantMe();
  const preferredId = ref(getPreferredDeviceId());

  function fieldOk(key: string) {
    const item = MERCHANT_FIELD_NAV.find((i) => i.key === key);
    return !!item && canAccessNav(me.value, item);
  }
  function bizOk(key: string) {
    const item = MERCHANT_BIZ_NAV.find((i) => i.key === key);
    return !!item && canAccessNav(me.value, item);
  }

  const canReplenishment = computed(() => fieldOk('replenishment'));
  const canDevices = computed(() => fieldOk('devices'));
  const canAlerts = computed(() => fieldOk('alerts'));
  const canPricing = computed(() => bizOk('pricing'));
  const canSettlements = computed(() => bizOk('settlements'));
  const canDisputes = computed(() => bizOk('disputes'));
  const canBusiness = computed(() => bizOk('business'));
  const canTrend = computed(
    () => hasPack(me.value, 'biz') && hasPerm(me.value, 'merchant:trend:view')
  );
  const canFinanceKpi = computed(() => canBusiness.value || canSettlements.value || canTrend.value);

  const loading = ref(true);
  const taskPreviewLoading = ref(false);
  /** 今日补货是否已完成过至少一次拉取（之后切 Tab 不再显示「任务加载中」） */
  const taskPreviewBooted = ref(false);
  const scanning = ref(false);
  const error = ref('');
  const meName = ref('');
  const merchantNames = ref('');
  const revenueToday = ref('暂无');
  const incomeToday = ref('暂无');
  const avgOrderToday = ref('暂无');
  const analyticsDays = ref(7);
  const trendBars = ref<{ date: string; label: string; height: number }[]>([]);
  const pendingCount = ref(0);
  const offlineCount = ref(0);
  const pendingTaskCount = ref(0);
  const actionItems = ref<{ type: string; title: string; detail?: string; deviceId?: string }[]>(
    []
  );
  const taskPreview = ref<TaskRow[]>([]);
  const deviceMap = ref<Record<string, string>>({});
  const stats = ref<Record<string, unknown>>({});
  const latestAnnouncement = ref<AnnouncementDto | null>(null);

  const onlineText = computed(() => {
    const on = stats.value.deviceOnline;
    const total = stats.value.deviceTotal;
    if (on == null && total == null) return '暂无';
    return `${on ?? 0} / ${total ?? 0}`;
  });

  const isMerchantUnbound = computed(
    () => !merchantNames.value || merchantNames.value === '未绑定商户'
  );
  const headerSubLine = computed(() =>
    isMerchantUnbound.value ? '尚未绑定商户，请联系运营开通后使用完整功能' : merchantNames.value
  );
  const homeEmptyTitle = computed(() =>
    isMerchantUnbound.value ? '暂无补货任务' : '暂无待处理补货任务'
  );
  const homeEmptyHint = computed(() =>
    isMerchantUnbound.value
      ? '绑定商户并分配柜机后，待补货任务会显示在这里'
      : '可扫码巡柜看缺货，或从柜机列表进详情'
  );

  function deviceLabel(id?: string) {
    if (!id) return '无柜机';
    return deviceMap.value[id] || id;
  }

  function statusLabel(status?: string) {
    return displayLabel('replenishment_task_status', status, '未知状态');
  }

  function goTab(url: string) {
    uni.switchTab({ url });
  }

  function goReplenishment(deviceId?: string, taskId?: number) {
    const params: string[] = [];
    if (deviceId) params.push(`deviceId=${encodeURIComponent(deviceId)}`);
    if (taskId) params.push(`taskId=${taskId}`);
    const q = params.length ? `?${params.join('&')}` : '';
    uni.navigateTo({ url: `/pages/replenishment/replenishment${q}` });
  }

  function goRequest() {
    uni.navigateTo({ url: '/pages/request/request' });
  }

  function goPricing() {
    uni.navigateTo({ url: '/pages/pricing/pricing' });
  }

  function goSettlements() {
    uni.navigateTo({ url: '/pages/settlements/settlements' });
  }

  function goDisputes() {
    uni.navigateTo({ url: '/pages/disputes/disputes' });
  }

  function goBusiness() {
    uni.navigateTo({ url: '/pages/business/business' });
  }

  function goAnnouncementDetail() {
    const id = latestAnnouncement.value?.announceId;
    if (!id) {
      uni.navigateTo({ url: '/pages/announcements/announcements' });
      return;
    }
    uni.navigateTo({ url: `/pages/announcements/detail?id=${id}` });
  }

  async function onScan() {
    if (scanning.value) return;
    scanning.value = true;
    try {
      const deviceId = await scanCabinetDeviceId();
      if (!deviceId) return;
      try {
        await merchantApi.assertReplenishmentDeviceAccess(deviceId);
      } catch (e) {
        showError(e instanceof Error ? e.message : '柜机不在您的管辖范围');
        return;
      }
      uni.navigateTo({
        url: `/pages/device-detail/device-detail?id=${encodeURIComponent(deviceId)}`
      });
    } finally {
      scanning.value = false;
    }
  }

  function hydrateFromCache() {
    const cached = peekMerchantMeCacheForDisplay();
    if (cached && (cached.displayName || cached.phoneNumber)) {
      seedMerchantMeDisplayCache(me);
      meName.value = cached.displayName || cached.phoneNumber || '同事';
      merchantNames.value = formatMerchantNames(cached.merchants);
    }
  }

  let loadSeq = 0;

  async function fetchHomeProfile(seq: number): Promise<MerchantMe | null> {
    hydrateFromCache();
    try {
      const profile = await refreshMe();
      if (seq !== loadSeq) return null;
      return profile;
    } catch {
      if (!isMerchantLoggedIn()) return null;
      seedMerchantMeDisplayCache(me);
      if (seq !== loadSeq) return null;
      return me.value;
    }
  }

  async function fetchHomeTrend() {
    if (!canTrend.value) return { last7Days: [] as { date?: string; revenueCents?: number }[] };
    return softErr(merchantApi.trend(7), { last7Days: [] }, '趋势加载失败');
  }

  async function fetchHomeWorkbench() {
    if (!canAlerts.value) return EMPTY_WORKBENCH;
    return softErr(merchantApi.workbench(), EMPTY_WORKBENCH, '待办加载失败');
  }

  async function fetchHomeExceptions() {
    if (!canAlerts.value) return { items: [], total: 0 };
    return softErr(merchantApi.openExceptions(100), { items: [], total: 0 }, '异常加载失败');
  }

  async function fetchHomeExpiryRows() {
    if (!canAlerts.value) return [];
    return softErr(merchantApi.expiryAlerts(), [], '效期告警加载失败');
  }

  async function fetchHomeDevices() {
    if (!canDevices.value && !canReplenishment.value) return [];
    return softErr(merchantApi.devices(), [], '柜机加载失败');
  }

  async function fetchHomeReplenishmentTasks() {
    if (!canReplenishment.value) return [];
    return softErr(merchantApi.replenishmentTasks(), [], '补货任务加载失败');
  }

  async function fetchHomeAnalytics() {
    if (!canBusiness.value) return null;
    return softErr(merchantApi.analytics(7), null, '经营数据加载失败');
  }

  async function fetchHomeDashboardBundle() {
    return Promise.all([
      softErr(merchantApi.stats(), {} as OpenApiMerchantDashboardStatsDto, '统计加载失败'),
      fetchHomeTrend(),
      fetchHomeWorkbench(),
      fetchHomeExceptions(),
      fetchHomeExpiryRows(),
      fetchHomeDevices(),
      fetchHomeReplenishmentTasks(),
      softFallback(merchantApi.listAnnouncements(), []),
      fetchHomeAnalytics()
    ]);
  }

  function applyHomeFinanceKpis(
    s: OpenApiMerchantDashboardStatsDto,
    analytics: { days?: number; avgOrderValueCents?: number | null } | null,
    days: { date?: string; revenueCents?: number }[]
  ) {
    const maxRev = Math.max(...days.map((d) => Number(d.revenueCents || 0)), 1);
    revenueToday.value = canFinanceKpi.value ? fmtMoney(s.revenueTodayCents) : '暂无';
    incomeToday.value = canFinanceKpi.value ? fmtMoney(s.merchantIncomeTodayCents) : '暂无';
    analyticsDays.value = Number(analytics?.days || 7);
    avgOrderToday.value =
      canBusiness.value && analytics?.avgOrderValueCents != null
        ? fmtMoney(analytics.avgOrderValueCents)
        : '暂无';
    trendBars.value = canFinanceKpi.value
      ? days.map((d) => ({
          date: d.date || '',
          label: (d.date || '').slice(5),
          height: Math.max(16, Math.round((Number(d.revenueCents || 0) / maxRev) * 120))
        }))
      : [];
  }

  function applyHomeAlerts(
    s: OpenApiMerchantDashboardStatsDto,
    workbench: MerchantWorkbench,
    exceptionPage: { items?: TodoSourceException[] },
    expiryRows: TodoSourceExpiry[]
  ) {
    offlineCount.value = canAlerts.value
      ? workbench.offlineDevices || 0
      : Number(s.deviceOffline || 0);
    const mergedTodos = canAlerts.value
      ? mergeTodoItems({
          exceptions: exceptionPage.items || [],
          actionItems: workbench.actionItems || [],
          expiryRows: expiryRows || []
        })
      : [];
    pendingCount.value = mergedTodos.length;
    setAlertsTabBadge(pendingCount.value);
    actionItems.value = canAlerts.value
      ? mergedTodos.slice(0, 3).map((a) => ({
          type: a.type,
          title: a.title,
          detail: a.detail,
          deviceId: a.deviceId
        }))
      : [];
  }

  function applyTaskPreview(tasks: TaskRow[]) {
    const taskRows = tasks || [];
    const openTasks = taskRows.filter((t) => t.status !== 'COMPLETED' && t.status !== 'CANCELLED');
    preferredId.value = getPreferredDeviceId();
    const preferredKey = String(preferredId.value || '')
      .trim()
      .toUpperCase();
    const sorted = preferredKey
      ? [
          ...openTasks.filter(
            (t) =>
              String(t.deviceId || '')
                .trim()
                .toUpperCase() === preferredKey
          ),
          ...openTasks.filter(
            (t) =>
              String(t.deviceId || '')
                .trim()
                .toUpperCase() !== preferredKey
          )
        ]
      : openTasks;
    pendingTaskCount.value = canReplenishment.value ? sorted.length : 0;
    taskPreview.value = canReplenishment.value ? sorted.slice(0, 5) : [];
  }

  function applyHomeDashboardData(
    profile: MerchantMe,
    bundle: Awaited<ReturnType<typeof fetchHomeDashboardBundle>>
  ) {
    const [
      s,
      trend,
      workbench,
      exceptionPage,
      expiryRows,
      devices,
      tasks,
      announcements,
      analytics
    ] = bundle;
    meName.value = profile.displayName || profile.phoneNumber || '同事';
    merchantNames.value = formatMerchantNames(profile.merchants);
    latestAnnouncement.value = announcements?.[0] || null;
    stats.value = s as Record<string, unknown>;
    const days = trend.last7Days || [];
    applyHomeFinanceKpis(s, analytics, days);
    applyHomeAlerts(s, workbench, exceptionPage, expiryRows);

    const map: Record<string, string> = {};
    for (const d of devices as { deviceId: string; deviceName?: string }[]) {
      map[d.deviceId] = d.deviceName || d.deviceId;
    }
    deviceMap.value = map;
    applyTaskPreview(tasks as TaskRow[]);
  }

  async function load() {
    if (!isMerchantLoggedIn()) {
      uni.reLaunch({ url: '/pages/login/login' });
      return;
    }
    const seq = ++loadSeq;
    loading.value = !meName.value;
    taskPreviewLoading.value = !taskPreviewBooted.value && canReplenishment.value;
    error.value = '';
    try {
      const profile = await fetchHomeProfile(seq);
      if (!profile || seq !== loadSeq) return;
      const bundle = await fetchHomeDashboardBundle();
      if (seq !== loadSeq) return;
      applyHomeDashboardData(profile, bundle);
    } catch (e) {
      if (seq !== loadSeq) return;
      error.value = e instanceof Error ? e.message : '加载失败';
    } finally {
      if (seq === loadSeq) {
        loading.value = false;
        taskPreviewLoading.value = false;
        taskPreviewBooted.value = true;
      }
    }
  }

  return {
    me: me as Ref<MerchantMe | null>,
    preferredId,
    loading,
    taskPreviewLoading,
    taskPreviewBooted,
    scanning,
    error,
    meName,
    merchantNames,
    revenueToday,
    incomeToday,
    avgOrderToday,
    analyticsDays,
    trendBars,
    pendingCount,
    offlineCount,
    pendingTaskCount,
    actionItems,
    taskPreview,
    deviceMap,
    stats,
    latestAnnouncement,
    onlineText,
    isMerchantUnbound,
    headerSubLine,
    homeEmptyTitle,
    homeEmptyHint,
    canReplenishment,
    canDevices,
    canAlerts,
    canPricing,
    canSettlements,
    canDisputes,
    canBusiness,
    canTrend,
    canFinanceKpi,
    deviceLabel,
    statusLabel,
    goTab,
    goReplenishment,
    goRequest,
    goPricing,
    goSettlements,
    goDisputes,
    goBusiness,
    goAnnouncementDetail,
    onScan,
    load
  };
}
