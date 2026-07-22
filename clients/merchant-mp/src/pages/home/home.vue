<template>
  <view class="page">
    <view v-if="loading && !meName" class="card"><text>加载中…</text></view>
    <view v-else-if="error && !meName" class="card"><text class="err">{{ error }}</text></view>
    <view v-else>
      <view v-if="error" class="banner-err"><text>{{ error }}</text><text class="banner-retry" @click="load">重试</text></view>
      <view class="dash-header">
        <text class="hello">你好，{{ meName }}</text>
        <text class="sub">{{ merchantNames }}</text>
        <view class="header-stats">
          <view class="h-stat">
            <text class="h-val">{{ pendingTaskCount }}</text>
            <text class="h-label">待补货</text>
          </view>
          <view class="h-stat">
            <text class="h-val" :class="{ urgent: pendingCount > 0 }">{{ pendingCount }}</text>
            <text class="h-label">待办</text>
          </view>
          <view class="h-stat">
            <text class="h-val">{{ offlineCount }}</text>
            <text class="h-label">离线柜</text>
          </view>
        </view>
      </view>

      <!-- 竞品式主路径：扫码到柜 → 补货/查看 -->
      <view class="scan-card">
        <view class="scan-copy">
          <text class="scan-title">扫码到柜</text>
          <text class="scan-desc">扫描柜门二维码，查看库存或开始补货</text>
        </view>
        <button class="scan-btn" hover-class="btn-hover" :loading="scanning" @click="onScan">扫码</button>
      </view>

      <view v-if="canReplenishment || canDevices || canAlerts" class="quick-row">
        <view v-if="canReplenishment" class="quick-item primary" @click="goReplenishment()">
          <text class="quick-icon">📦</text>
          <text class="quick-label">补货任务</text>
          <text v-if="pendingTaskCount" class="quick-badge">{{ pendingTaskCount }}</text>
        </view>
        <view v-if="canDevices" class="quick-item" @click="goTab('/pages/devices/devices')">
          <text class="quick-icon">🗄️</text>
          <text class="quick-label">柜机列表</text>
        </view>
        <view v-if="canAlerts" class="quick-item" @click="goTab('/pages/alerts/alerts')">
          <text class="quick-icon">🔔</text>
          <text class="quick-label">待办事项</text>
          <text v-if="pendingCount" class="quick-badge">{{ pendingCount }}</text>
        </view>
      </view>

      <view v-if="canReplenishment" class="card section-card">
        <view class="section-head">
          <text class="section">今日补货</text>
          <text class="section-more" @click="goReplenishment()">全部 ›</text>
        </view>
        <text v-if="preferredId" class="pref-tip">常驻柜 {{ preferredId }} 优先置顶</text>
        <view v-if="taskPreviewLoading" class="empty-inline">任务加载中…</view>
        <view v-else-if="!taskPreview.length" class="empty-inline empty-actions">
          <text class="empty-title">暂无待处理补货任务</text>
          <text class="empty-hint">可扫码巡柜看缺货，或从柜机列表进详情</text>
          <view class="empty-btns">
            <button class="empty-btn primary" :loading="scanning" @click="onScan">扫码到柜</button>
            <button v-if="canDevices" class="empty-btn" @click="goTab('/pages/devices/devices')">柜机列表</button>
            <button class="empty-btn" @click="goReplenishment()">查看记录</button>
          </view>
        </view>
        <view
          v-for="task in taskPreview"
          :key="task.taskId"
          class="task-row"
          @click="goReplenishment(task.deviceId, task.taskId)"
        >
          <view class="task-copy">
            <text class="task-name">
              {{ deviceLabel(task.deviceId) }}
              <text v-if="preferredId && task.deviceId === preferredId" class="pref-mark">常驻</text>
            </text>
            <text class="task-meta">{{ task.deviceId }} · {{ statusLabel(task.status) }}</text>
          </view>
          <text class="task-go">去补货 ›</text>
        </view>
      </view>

      <view v-if="canAlerts && actionItems.length" class="card section-card" @click="goTab('/pages/alerts/alerts')">
        <view class="section-head">
          <text class="section">优先待办</text>
          <text class="section-more">查看全部 ›</text>
        </view>
        <view v-for="item in actionItems" :key="item.type + item.title" class="todo-row">
          <text class="todo-dot" />
          <view class="todo-copy">
            <text class="todo-title">{{ item.title }}</text>
            <text v-if="item.detail" class="todo-detail">{{ item.detail }}</text>
          </view>
        </view>
      </view>

      <view v-if="canPricing || canSettlements || canDisputes || canBusiness || canReplenishment" class="ops-block">
        <text class="ops-title">经营工具</text>
        <view class="ops-grid">
          <view v-if="canReplenishment" class="ops-card" @click="goRequest">
            <text class="ops-label">要货申请</text>
          </view>
          <view v-if="canPricing" class="ops-card" @click="goPricing">
            <text class="ops-label">点位定价</text>
          </view>
          <view v-if="canSettlements" class="ops-card" @click="goSettlements">
            <text class="ops-label">结算对账</text>
          </view>
          <view v-if="canDisputes" class="ops-card" @click="goDisputes">
            <text class="ops-label">争议处理</text>
          </view>
          <view v-if="canBusiness" class="ops-card" @click="goBusiness">
            <text class="ops-label">经营分析</text>
          </view>
        </view>
      </view>

      <view v-if="canFinanceKpi || canDevices" class="card section-card">
        <text class="section">{{ canFinanceKpi ? '近7日营收' : '柜机概况' }}</text>
        <view class="kpi-mini">
          <view v-if="canFinanceKpi">
            <text class="kpi-label">今日营收</text>
            <text class="kpi-value">{{ revenueToday }}</text>
          </view>
          <view v-if="canFinanceKpi">
            <text class="kpi-label">商户收入</text>
            <text class="kpi-value">{{ incomeToday }}</text>
          </view>
          <view>
            <text class="kpi-label">在线柜机</text>
            <text class="kpi-value">{{ onlineText }}</text>
          </view>
        </view>
        <view v-if="canFinanceKpi && trendBars.length" class="bars">
          <view v-for="b in trendBars" :key="b.date" class="bar-wrap">
            <view class="bar" :style="{ height: b.height + 'rpx' }" />
            <text class="bar-label">{{ b.label }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import { dictLabel } from '@aicabinet/shared-dict';
import type { MerchantMe } from '@aicabinet/shared-types';

type TaskRow = { taskId: number; deviceId: string; status: string };

const { me, refresh: refreshMe } = useMerchantMe();
const preferredId = ref(getPreferredDeviceId());
const canReplenishment = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));
const canDevices = computed(() => hasPerm(me.value, 'merchant:devices:list'));
const canAlerts = computed(() => hasPerm(me.value, 'merchant:alerts:view'));
const canPricing = computed(() => hasPerm(me.value, 'merchant:pricing:view'));
const canSettlements = computed(() => hasPerm(me.value, 'merchant:settlements:view'));
const canDisputes = computed(() => hasPerm(me.value, 'merchant:disputes:list'));
const canBusiness = computed(
  () => hasPerm(me.value, 'merchant:reports:view') || hasPerm(me.value, 'merchant:analytics:view')
);
const canTrend = computed(() => hasPerm(me.value, 'merchant:trend:view'));
const canFinanceKpi = computed(
  () =>
    canBusiness.value ||
    canSettlements.value ||
    canTrend.value
);

const loading = ref(true);
const taskPreviewLoading = ref(false);
const scanning = ref(false);
const error = ref('');
const meName = ref('');
const merchantNames = ref('');
const revenueToday = ref('-');
const incomeToday = ref('-');
const trendBars = ref<{ date: string; label: string; height: number }[]>([]);
const pendingCount = ref(0);
const offlineCount = ref(0);
const pendingTaskCount = ref(0);
const actionItems = ref<{ type: string; title: string; detail?: string }[]>([]);
const taskPreview = ref<TaskRow[]>([]);
const deviceMap = ref<Record<string, string>>({});
const stats = ref<Record<string, unknown>>({});

const onlineText = computed(() => {
  const on = stats.value.deviceOnline;
  const total = stats.value.deviceTotal;
  if (on == null && total == null) return '-';
  return `${on ?? '-'} / ${total ?? '-'}`;
});

function fmtMoney(cents?: number) {
  if (cents == null) return '-';
  return '¥' + (cents / 100).toFixed(2);
}

function deviceLabel(id?: string) {
  if (!id) return '未知柜机';
  return deviceMap.value[id] || id;
}

function statusLabel(status?: string) {
  return dictLabel('replenishment_task_status', status || '') || status || '';
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

async function onScan() {
  if (scanning.value) return;
  scanning.value = true;
  try {
    const deviceId = await scanCabinetDeviceId();
    if (!deviceId) return;
    // 有待补货任务则进补货，否则进柜机详情
    const openTask = taskPreview.value.find(
      (t) => t.deviceId === deviceId && t.status !== 'COMPLETED' && t.status !== 'CANCELLED'
    );
    if (openTask) {
      goReplenishment(deviceId, openTask.taskId);
    } else {
      uni.navigateTo({
        url: `/pages/device-detail/device-detail?id=${encodeURIComponent(deviceId)}`
      });
    }
  } finally {
    scanning.value = false;
  }
}

function hydrateFromCache() {
  const cached = (uni.getStorageSync('merchant_me') || {}) as MerchantMe;
  if (cached && (cached.permissions || cached.displayName || cached.phoneNumber)) {
    me.value = cached;
  }
  if (cached.displayName || cached.phoneNumber) {
    meName.value = cached.displayName || cached.phoneNumber || '同事';
    merchantNames.value = (cached.merchants || []).map((m) => m.merchantName).join('、') || '未绑定商户';
  }
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  hydrateFromCache();
  loading.value = !meName.value;
  error.value = '';
  try {
    let profile: MerchantMe;
    try {
      profile = await refreshMe();
    } catch {
      profile = (uni.getStorageSync('merchant_me') as MerchantMe) || ({} as MerchantMe);
      me.value = profile;
    }
    meName.value = profile.displayName || profile.phoneNumber || '同事';
    merchantNames.value = (profile.merchants || []).map((m) => m.merchantName).join('、') || '未绑定商户';

    const [s, trend, workbench, devices, tasks] = await Promise.all([
      merchantApi.stats() as Promise<Record<string, number>>,
      canTrend.value
        ? (merchantApi.trend(7) as Promise<{ last7Days?: { date: string; revenueCents: number }[] }>)
        : Promise.resolve({ last7Days: [] }),
      canAlerts.value
        ? merchantApi.workbench()
        : Promise.resolve({
            offlineDevices: 0,
            openDisputes: 0,
            lowStockItems: 0,
            expiryAlerts: 0,
            actionItems: []
          }),
      canDevices.value || canReplenishment.value
        ? merchantApi.devices()
        : Promise.resolve([]),
      canReplenishment.value ? merchantApi.replenishmentTasks() : Promise.resolve([])
    ]);

    const days = trend.last7Days || [];
    const maxRev = Math.max(...days.map((d) => d.revenueCents), 1);
    stats.value = s;
    revenueToday.value = canFinanceKpi.value ? fmtMoney(s.revenueTodayCents) : '-';
    incomeToday.value = canFinanceKpi.value ? fmtMoney(s.merchantIncomeTodayCents) : '-';
    offlineCount.value = canAlerts.value
      ? workbench.offlineDevices || 0
      : Number(s.deviceOffline || 0);
    pendingCount.value = canAlerts.value
      ? (workbench.openDisputes || 0) +
        (workbench.offlineDevices || 0) +
        (workbench.lowStockItems || 0) +
        (workbench.expiryAlerts || 0)
      : 0;
    try {
      if (pendingCount.value > 0) {
        uni.setTabBarBadge({
          index: 2,
          text: pendingCount.value > 99 ? '99+' : String(pendingCount.value)
        });
      } else {
        uni.removeTabBarBadge({ index: 2 });
      }
    } catch {
      /* H5 / non-tab context */
    }
    actionItems.value = canAlerts.value ? (workbench.actionItems || []).slice(0, 3) : [];
    trendBars.value = canFinanceKpi.value
      ? days.map((d) => ({
          date: d.date,
          label: d.date.slice(5),
          height: Math.max(16, Math.round((d.revenueCents / maxRev) * 120))
        }))
      : [];

    const map: Record<string, string> = {};
    for (const d of devices as { deviceId: string; deviceName?: string }[]) {
      map[d.deviceId] = d.deviceName || d.deviceId;
    }
    deviceMap.value = map;

    const taskRows = (tasks as TaskRow[]) || [];
    const openTasks = taskRows.filter((t) => t.status !== 'COMPLETED' && t.status !== 'CANCELLED');
    preferredId.value = getPreferredDeviceId();
    const preferred = preferredId.value;
    const sorted = preferred
      ? [
          ...openTasks.filter((t) => t.deviceId === preferred),
          ...openTasks.filter((t) => t.deviceId !== preferred)
        ]
      : openTasks;
    pendingTaskCount.value = canReplenishment.value ? sorted.length : 0;
    taskPreview.value = canReplenishment.value ? sorted.slice(0, 5) : [];
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
    taskPreviewLoading.value = false;
  }
}

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped>
.page {
  min-height: 100%;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  background: linear-gradient(180deg, #ecfdf5 0, #f0fdfa 280rpx, #f0fdfa 100%);
}
.dash-header {
  background: linear-gradient(145deg, #134e4a, #0f766e 60%, #14b8a6);
  padding: 36rpx 32rpx 40rpx;
  color: #fff;
  border-radius: 0 0 32rpx 32rpx;
}
.hello { font-size: 36rpx; font-weight: 700; display: block; }
.sub { font-size: 24rpx; opacity: 0.85; display: block; margin-top: 6rpx; }
.header-stats {
  display: flex;
  margin-top: 28rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid rgba(255, 255, 255, 0.18);
}
.h-stat { flex: 1; text-align: center; }
.h-val { display: block; font-size: 40rpx; font-weight: 800; }
.h-val.urgent { color: #fecaca; }
.h-label { display: block; margin-top: 4rpx; font-size: 22rpx; opacity: 0.8; }

.scan-card {
  margin: -20rpx 24rpx 0;
  position: relative;
  z-index: 2;
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx 24rpx;
  display: flex;
  align-items: center;
  gap: 20rpx;
  box-shadow: 0 12rpx 36rpx rgba(15, 118, 110, 0.12);
  border: 1rpx solid #ccfbf1;
}
.scan-copy { flex: 1; min-width: 0; }
.scan-title { display: block; font-size: 32rpx; font-weight: 700; color: #134e4a; }
.scan-desc { display: block; margin-top: 6rpx; font-size: 24rpx; color: #64748b; line-height: 1.4; }
.scan-btn {
  margin: 0;
  flex-shrink: 0;
  height: 80rpx;
  line-height: 80rpx;
  padding: 0 36rpx;
  border-radius: 40rpx;
  background: linear-gradient(135deg, #0f766e, #14b8a6);
  color: #fff;
  font-size: 30rpx;
  font-weight: 700;
  box-shadow: 0 8rpx 20rpx rgba(15, 118, 110, 0.25);
}
.scan-btn::after { border: none; }
.btn-hover { opacity: 0.88; }

.quick-row {
  display: flex;
  gap: 12rpx;
  margin: 20rpx 24rpx 0;
}
.quick-item {
  position: relative;
  flex: 1;
  background: #fff;
  border-radius: 20rpx;
  padding: 24rpx 12rpx;
  text-align: center;
  border: 1rpx solid #e2e8f0;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.quick-item.primary {
  border-color: #99f6e4;
  background: linear-gradient(180deg, #f0fdfa, #fff);
}
.quick-icon { display: block; font-size: 36rpx; }
.quick-label { display: block; margin-top: 8rpx; font-size: 24rpx; color: #334155; font-weight: 600; }
.quick-badge {
  position: absolute;
  top: 10rpx;
  right: 10rpx;
  min-width: 32rpx;
  height: 32rpx;
  padding: 0 8rpx;
  border-radius: 16rpx;
  background: #ef4444;
  color: #fff;
  font-size: 20rpx;
  line-height: 32rpx;
  text-align: center;
}

.card {
  margin: 20rpx 24rpx 0;
  padding: 28rpx 24rpx;
  background: #fff;
  border-radius: 22rpx;
  border: 1rpx solid #e2e8f0;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.04);
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8rpx;
}
.section { font-weight: 700; font-size: 30rpx; color: #0f172a; }
.section-more { color: #0f766e; font-size: 24rpx; }
.pref-tip {
  display: block;
  margin: 0 0 12rpx;
  font-size: 22rpx;
  color: #0f766e;
}
.empty-inline {
  padding: 28rpx 0 8rpx;
  text-align: center;
  color: #94a3b8;
  font-size: 26rpx;
}
.empty-actions { padding-bottom: 16rpx; }
.empty-title { display: block; color: #64748b; font-size: 28rpx; font-weight: 600; }
.empty-hint { display: block; margin-top: 8rpx; font-size: 22rpx; color: #cbd5e1; }
.empty-btns {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12rpx;
  margin-top: 20rpx;
}
.empty-btn {
  margin: 0;
  padding: 0 22rpx;
  height: 64rpx;
  line-height: 64rpx;
  border-radius: 999rpx;
  font-size: 24rpx;
  color: #0f766e;
  background: #ecfdf5;
  border: none;
}
.empty-btn.primary { color: #fff; background: #0f766e; }
.empty-btn::after { border: none; }
.task-row {
  display: flex;
  align-items: center;
  padding: 20rpx 0;
  border-top: 1rpx solid #f1f5f9;
}
.task-row:first-of-type { border-top: 0; }
.task-copy { flex: 1; min-width: 0; }
.task-name { display: block; font-size: 28rpx; font-weight: 600; color: #0f172a; }
.pref-mark {
  margin-left: 10rpx;
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
  background: #ccfbf1;
  color: #0f766e;
  font-size: 20rpx;
  font-weight: 700;
}
.task-meta { display: block; margin-top: 4rpx; font-size: 22rpx; color: #94a3b8; }
.task-go { color: #0f766e; font-size: 26rpx; font-weight: 600; }

.todo-row { display: flex; align-items: flex-start; padding: 14rpx 0; border-top: 1rpx solid #f1f5f9; }
.todo-dot {
  width: 12rpx;
  height: 12rpx;
  flex: 0 0 auto;
  margin: 13rpx 14rpx 0 0;
  border-radius: 50%;
  background: #f59e0b;
}
.todo-copy { min-width: 0; }
.todo-title { display: block; font-size: 26rpx; color: #0f172a; }
.todo-detail {
  display: block;
  margin-top: 4rpx;
  color: #64748b;
  font-size: 22rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ops-block { margin: 28rpx 24rpx 0; }
.ops-title {
  display: block;
  margin: 0 8rpx 14rpx;
  font-size: 24rpx;
  color: #94a3b8;
  letter-spacing: 1rpx;
}
.ops-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12rpx; }
.ops-card {
  background: #fff;
  border-radius: 18rpx;
  padding: 28rpx 20rpx;
  text-align: center;
  border: 1rpx solid #e2e8f0;
}
.ops-label { font-size: 28rpx; color: #334155; font-weight: 600; }

.kpi-mini {
  display: flex;
  gap: 12rpx;
  margin: 16rpx 0 20rpx;
}
.kpi-mini > view { flex: 1; }
.kpi-label { display: block; font-size: 22rpx; color: #64748b; }
.kpi-value { display: block; margin-top: 6rpx; font-size: 28rpx; font-weight: 700; color: #0f766e; }
.bars { display: flex; align-items: flex-end; gap: 8rpx; height: 140rpx; }
.bar-wrap { flex: 1; display: flex; flex-direction: column; align-items: center; }
.bar {
  width: 100%;
  background: linear-gradient(180deg, #14b8a6, #0f766e);
  border-radius: 6rpx 6rpx 0 0;
  min-height: 8rpx;
}
.bar-label { font-size: 20rpx; color: #64748b; margin-top: 6rpx; }
.err { color: #ef4444; }
.banner-err {
  margin: 16rpx 24rpx 0;
  padding: 18rpx 22rpx;
  border-radius: 16rpx;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 24rpx;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16rpx;
}
.banner-retry { color: #0f766e; font-weight: 600; flex-shrink: 0; }
</style>
