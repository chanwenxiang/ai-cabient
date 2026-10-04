<template>
  <view class="page-root devices-page">
    <view class="nav-place" :style="navPlaceStyle">
      <app-nav-bar title="柜机" home-url="/pages/home/home" />
    </view>
    <view class="toolbar">
      <view
        class="tool-item is-primary"
        role="button"
        aria-label="扫码到柜"
        hover-class="tool-item-hover"
        :class="{ 'is-busy': scanning }"
        @click="onScan"
      >
        <text class="tool-label">{{ scanning ? '扫码中…' : '扫码到柜' }}</text>
      </view>
      <view
        v-if="canReplenishment"
        class="tool-item"
        role="button"
        aria-label="补货任务"
        hover-class="tool-item-hover"
        @click="goReplenishment"
      >
        <text class="tool-label">补货任务</text>
      </view>
    </view>
    <app-underline-tabs :items="deviceStatusTabs" :value="filter" @change="setDeviceFilter" />
    <view class="filters">
      <input
        v-model="keyword"
        class="search"
        aria-label="搜索柜机名称或编号"
        placeholder="搜索柜机名称或编号…"
      />
      <view class="filter-aux">
        <text
          role="button"
          class="aux-link"
          :class="{ active: onlyPreferred }"
          @click="toggleOnlyPreferred"
          >常驻柜</text
        >
      </view>
      <view v-if="preferredId" class="pref-hint">
        <text>常驻：{{ preferredLabel }}</text>
        <text role="button" aria-label="清除常驻柜" class="pref-clear" @click="clearPreferred"
          >清除</text
        >
      </view>
    </view>
    <view v-if="loading && !devices.length" class="card">{{ UI_COPY.loading }}</view>
    <error-state
      v-else-if="error && !devices.length"
      :title="error"
      hint="请检查网络后重试"
      @retry="load"
    />
    <view v-else>
      <view
        v-for="d in visibleDevices"
        :key="d.deviceId"
        class="card device-card"
        hover-class="device-card-hover"
        role="button"
        @click="goDetail(d.deviceId)"
      >
        <view class="device-main">
          <view class="device-left">
            <view class="thumb-wrap">
              <image
                class="device-thumb"
                src="/static/device-default.png"
                mode="aspectFill"
                aria-hidden="true"
              />
              <view class="online-dot" :class="d.online ? 'on' : 'off'" />
            </view>
            <view class="device-info">
              <text class="name">{{ d.deviceName || d.deviceId }}</text>
              <text class="meta">{{ d.deviceId }}</text>
              <text v-if="d.address" class="meta addr">{{ d.address }}</text>
              <text
                v-if="d.routeCode || lifecycleText(d.lifecycleStatus) || d.currentTempC != null"
                class="meta"
              >
                <template v-if="d.routeCode">线路 {{ d.routeCode }}</template>
                <template v-if="d.routeCode && lifecycleText(d.lifecycleStatus)"> · </template>
                <template v-if="lifecycleText(d.lifecycleStatus)">{{
                  lifecycleText(d.lifecycleStatus)
                }}</template>
                <template
                  v-if="(d.routeCode || lifecycleText(d.lifecycleStatus)) && d.currentTempC != null"
                >
                  ·
                </template>
                <template v-if="d.currentTempC != null">{{ d.currentTempC }}°C</template>
              </text>
              <text v-if="stockSummary(d)" class="meta stock-warn">{{ stockSummary(d) }}</text>
              <text v-if="canSeeRevenue" class="meta revenue"
                >今日收入 ¥{{ ((revenueByDevice[d.deviceId ?? ''] || 0) / 100).toFixed(2) }}</text
              >
              <text v-else-if="d.firmwareVersion" class="meta">固件 {{ d.firmwareVersion }}</text>
            </view>
          </view>
          <view class="device-right">
            <text
              class="star"
              role="button"
              :aria-label="preferredId === d.deviceId ? '取消常驻柜' : '设为常驻柜'"
              :class="{ on: preferredId === d.deviceId }"
              @click.stop="togglePreferred(d.deviceId)"
              >★</text
            >
            <text v-if="d.salesLocked" class="tag tag-lock">{{ UI_COPY.salesLocked }}</text>
            <text v-else-if="d.replenishmentInProgress" class="tag tag-warn">{{
              UI_COPY.replenishing
            }}</text>
            <text v-else class="tag" :class="d.online ? 'tag-on' : 'tag-off'">{{
              onlineLabel(!!d.online)
            }}</text>
          </view>
        </view>
        <view
          v-if="(d.salesLocked && d.salesLockReason) || (d.latitude != null && d.longitude != null)"
          class="device-foot"
        >
          <text v-if="d.salesLocked && d.salesLockReason" class="device-note">{{
            d.salesLockReason
          }}</text>
          <view v-else class="device-note-spacer" />
          <view
            v-if="d.latitude != null && d.longitude != null"
            class="nav-link app-link-chevron"
            role="button"
            aria-label="导航"
            hover-class="nav-link-hover"
            @click.stop="openNav(d)"
            >导航</view
          >
        </view>
      </view>
      <empty-state
        v-if="!visibleDevices.length"
        kind="devices"
        icon="/static/menu/cabinet.png"
        :title="emptyHint"
        hint="可切换筛选或扫码绑定常驻柜"
      />
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { computed, ref } from 'vue';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import { hasPerm, merchantApi, isMerchantLoggedIn } from '@/utils/merchant-api';
import { softFallback } from '@/utils/soft-fallback';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import {
  clearPreferredDeviceId,
  getPreferredDeviceId,
  setPreferredDeviceId
} from '@/utils/preferred-device';
import { dictLabel } from '@aicabinet/shared-dict';
import { confirmOpenDeviceNavigation } from '@/utils/open-device-navigation';
import type { MerchantDeviceInfo, MerchantMe } from '@aicabinet/shared-types';
import { UI_COPY, onlineLabel } from '@aicabinet/shared-uni/ui-copy';
import { getCustomNavPlaceStyle } from '@aicabinet/shared-uni/status-bar';

const navPlaceStyle = getCustomNavPlaceStyle();
const { me, refresh: refreshMe } = useMerchantMe();
/** 今日营业额仅报表可见角色展示（店长/财务；店员/补货员不展示，权限口径 2026-09-30 与运营确认） */
const canSeeRevenue = computed(() =>
  (me.value?.permissions || []).includes('merchant:reports:view')
);
const revenueByDevice = ref<Record<string, number>>({});
const canListDevices = computed(() => hasPerm(me.value, 'merchant:devices:list'));
const canReplenishment = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));

const loading = ref(true);
const scanning = ref(false);
const error = ref('');
let loadSeq = 0;
// /api/v2/merchant/devices 实际返回 MerchantDeviceDto（含 oosSlotCount/lifecycleStatus 等），
// 此前错标为 DeviceInfo(=AdminDeviceDto)，导致 stockSummary() 等读取被 vue-tsc 判为「无公共属性」
const devices = ref<(MerchantDeviceInfo & { online?: boolean })[]>([]);
const keyword = ref('');
const filter = ref<'all' | 'online' | 'offline' | 'locked'>('all');
const preferredId = ref('');
const onlyPreferred = ref(false);
const filters = [
  { label: '全部', value: 'all' as const },
  { label: '在线', value: 'online' as const },
  { label: '离线', value: 'offline' as const },
  { label: '停售', value: 'locked' as const }
];

const preferredLabel = computed(() => {
  const hit = devices.value.find((d) => d.deviceId === preferredId.value);
  return hit?.deviceName || preferredId.value || '未设置';
});

const visibleDevices = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const list = devices.value.filter((d) => {
    let statusMatch = true;
    if (filter.value === 'online') statusMatch = !!d.online;
    else if (filter.value === 'offline') statusMatch = !d.online;
    else if (filter.value === 'locked') statusMatch = !!d.salesLocked;
    const keywordMatch = !q || `${d.deviceName || ''} ${d.deviceId}`.toLowerCase().includes(q);
    const preferredMatch = !onlyPreferred.value || d.deviceId === preferredId.value;
    return statusMatch && keywordMatch && preferredMatch;
  });
  if (!preferredId.value) return list;
  return [...list].sort((a, b) => {
    if (a.deviceId === preferredId.value) return -1;
    if (b.deviceId === preferredId.value) return 1;
    return 0;
  });
});

const emptyHint = computed(() => {
  if (onlyPreferred.value && preferredId.value) return '常驻柜不在当前筛选结果中';
  return devices.value.length ? '没有符合条件的柜机' : '暂无柜机';
});

function countFor(value: 'all' | 'online' | 'offline' | 'locked') {
  if (value === 'all') return devices.value.length;
  if (value === 'locked') return devices.value.filter((d) => !!d.salesLocked).length;
  return devices.value.filter((d) => (value === 'online' ? d.online : !d.online)).length;
}

const deviceStatusTabs = computed(() =>
  filters.map((f) => ({
    key: f.value,
    label: f.label,
    badge: countFor(f.value) || undefined
  }))
);

function setDeviceFilter(key: string) {
  filter.value = key as typeof filter.value;
}

function toggleOnlyPreferred() {
  if (!preferredId.value) {
    showError('先点 ★ 设常驻柜');
    return;
  }
  onlyPreferred.value = !onlyPreferred.value;
}

function togglePreferred(id?: string) {
  if (!id) return;
  if (preferredId.value === id) {
    clearPreferredDeviceId();
    preferredId.value = '';
    onlyPreferred.value = false;
    showSuccess('已取消常驻');
    return;
  }
  setPreferredDeviceId(id);
  preferredId.value = id;
  showSuccess('已设为常驻柜');
}

function clearPreferred() {
  clearPreferredDeviceId();
  preferredId.value = '';
  onlyPreferred.value = false;
}

async function load() {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const seq = ++loadSeq;
  preferredId.value = getPreferredDeviceId();
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return;
    seedMerchantMeDisplayCache(me);
  }
  if (seq !== loadSeq) return;
  if (!me.value) {
    seedMerchantMeDisplayCache(me);
  }
  if (!canListDevices.value) {
    showError('无柜机权限');
    uni.switchTab({ url: '/pages/home/home' });
    return;
  }
  // 已有柜机列表时静默刷新，避免 Tab 切换时列表先消失再撑开
  if (!devices.value.length) loading.value = true;
  error.value = '';
  try {
    const list = await merchantApi.devices();
    if (seq !== loadSeq) return;
    devices.value = list.map((d) => ({
      ...d,
      online: (d.onlineStatus || '').toUpperCase() === 'ONLINE'
    }));
    if (canSeeRevenue.value) {
      softFallback(merchantApi.deviceReports(), [], '报表')
        .then((rows) => {
          if (seq !== loadSeq) return;
          const map: Record<string, number> = {};
          for (const r of rows as { deviceId?: string; revenueTodayCents?: number }[]) {
            if (r.deviceId) map[r.deviceId] = Number(r.revenueTodayCents || 0);
          }
          revenueByDevice.value = map;
        })
        .catch(() => {});
    }
  } catch (e) {
    if (seq !== loadSeq) return;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function goDetail(id?: string) {
  // 生成类型里 deviceId 可选；缺 id 时原先会跳到 ?id=undefined
  if (!id) return;
  uni.navigateTo({ url: `/pages/device-detail/device-detail?id=${encodeURIComponent(id)}` });
}

function openNav(d: MerchantDeviceInfo) {
  void confirmOpenDeviceNavigation({
    latitude: d.latitude,
    longitude: d.longitude,
    name: d.deviceName,
    address: d.address,
    deviceId: d.deviceId
  });
}

function goReplenishment() {
  if (!canReplenishment.value) {
    showError('无补货权限');
    return;
  }
  uni.navigateTo({ url: '/pages/replenishment/replenishment' });
}

async function onScan() {
  if (scanning.value) return;
  scanning.value = true;
  try {
    const id = await scanCabinetDeviceId();
    if (!id) return;
    const key = id.trim().toUpperCase();
    const hit = devices.value.find(
      (d) =>
        String(d.deviceId || '')
          .trim()
          .toUpperCase() === key
    );
    if (!hit?.deviceId) {
      showError('未找到该柜机或无权限');
      return;
    }
    setPreferredDeviceId(hit.deviceId);
    preferredId.value = hit.deviceId;
    goDetail(hit.deviceId);
  } finally {
    scanning.value = false;
  }
}

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

function lifecycleText(status?: string) {
  if (!status) return '';
  return dictLabel('device_lifecycle', status) || '';
}

function stockSummary(d: { oosSlotCount?: number | null; lowStockSlotCount?: number | null }) {
  const oos = Number(d.oosSlotCount || 0);
  const low = Number(d.lowStockSlotCount || 0);
  if (oos > 0 && low > 0) return `缺货 ${oos} · 低库存 ${low}`;
  if (oos > 0) return `缺货货道 ${oos}`;
  if (low > 0) return `低库存货道 ${low}`;
  return '';
}
</script>

<style scoped>
.meta.addr {
  max-width: 380rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.devices-page {
  padding: 0;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  min-height: 100%;
  background: var(--page-tint, #f0fdfa);
  box-sizing: border-box;
}
.nav-place {
  width: 100%;
}
/* 昨晚终态：白底文字操作条（主操作品牌色+底线，次操作灰字），不是渐变胶囊 */
.toolbar {
  display: flex;
  align-items: stretch;
  background: var(--card-bg, #fff);
  padding: 0 8rpx;
  border-bottom: 1rpx solid var(--color-border-subtle, #e2e8f0);
}
.tool-item {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 22rpx 12rpx 18rpx;
  position: relative;
}
.tool-label {
  font-size: 30rpx;
  font-weight: 500;
  color: var(--text-muted, #64748b);
  line-height: 1.2;
}
.tool-item.is-primary .tool-label {
  color: var(--brand, #0f766e);
  font-weight: 700;
}
.tool-item.is-primary::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 64rpx;
  height: 6rpx;
  margin-left: -32rpx;
  border-radius: 6rpx;
  background: var(--brand, #0f766e);
}
.tool-item.is-busy {
  opacity: 0.55;
  pointer-events: none;
}
.tool-item-hover {
  opacity: 0.72;
}
/* 无 page-body：卡片水平 gutter 由页面承担（对齐 M02） */
.devices-page > .card,
.devices-page .device-card {
  margin-left: var(--page-gutter, 24rpx);
  margin-right: var(--page-gutter, 24rpx);
  width: auto;
  max-width: none;
  box-sizing: border-box;
}
.device-card {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 0;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.device-card-hover {
  background: var(--page-bg, #f8fafc) !important;
  opacity: 0.96;
}
.device-main {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16rpx;
}
.device-right {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  flex: 0 0 128rpx;
  width: 128rpx;
  max-width: 128rpx;
  box-sizing: border-box;
  padding-top: 2rpx;
}
.tag {
  display: inline-block;
  min-width: 96rpx;
  padding: 12rpx 20rpx;
  border-radius: 12rpx;
  font-size: 26rpx;
  font-weight: 700;
  line-height: 1.2;
  text-align: center;
  box-sizing: border-box;
}
.tag-lock {
  min-width: 112rpx;
  padding: 16rpx 22rpx;
  font-size: 30rpx;
  letter-spacing: 2rpx;
  color: var(--warning, #b45309);
  background: var(--warning-soft, #fff7ed);
}
.tag-warn {
  color: var(--warning, #b45309);
  background: var(--warning-soft, #fff7ed);
}
.tag-on {
  color: var(--brand, #0f766e);
  background: var(--brand-soft, #ecfdf5);
}
.tag-off {
  color: var(--text-muted, #64748b);
  background: var(--page-tint, #f0fdfa);
}
.star {
  color: var(--text-subtle, #cbd5e1);
  font-size: 36rpx;
  line-height: 1;
  padding: 0;
  position: relative;
  z-index: 1;
}
.star.on {
  color: var(--warning, #f59e0b);
}
.device-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-top: 16rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid var(--color-border-subtle, #e2e8f0);
}
.device-note {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-xs, 20rpx);
  color: var(--warning, #b45309);
  line-height: 1.4;
}
.device-note-spacer {
  flex: 1;
  min-width: 0;
}
.nav-link {
  flex-shrink: 0;
  margin: 0;
  padding: 0;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--brand, #0f766e);
  line-height: 1.2;
}
.nav-link-hover {
  opacity: 0.65;
}
.name,
.meta,
.online-dot {
  pointer-events: none;
}
.filters {
  position: sticky;
  top: 0;
  z-index: 5;
  isolation: isolate;
  background: var(--page-bg, #ededed);
  padding: 16rpx 24rpx 12rpx;
}
.search {
  height: 72rpx;
  box-sizing: border-box;
  background: #fff;
  border: none;
  border-radius: 8rpx;
  padding: 0 28rpx;
  font-size: var(--font-size-body);
}
.chips {
  display: none;
}
.filter-aux {
  display: flex;
  margin-top: 12rpx;
}
.aux-link {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.aux-link.active {
  color: var(--brand);
  font-weight: 600;
}
.pref-hint {
  margin-top: 12rpx;
  display: flex;
  justify-content: space-between;
  font-size: var(--font-size-sm);
  color: var(--brand, #0f766e);
}
.pref-clear {
  color: var(--text-muted);
}
.empty {
  text-align: center;
  color: var(--text-muted);
}
.device-left {
  display: flex;
  align-items: center;
  gap: 16rpx;
  flex: 1;
  min-width: 0;
}
.device-info {
  flex: 1;
  min-width: 0;
}
.device-thumb {
  width: 88rpx;
  height: 88rpx;
  border-radius: var(--radius-control);
  background: var(--brand-soft);
  flex-shrink: 0;
}
.thumb-wrap {
  position: relative;
  width: 88rpx;
  height: 88rpx;
  flex-shrink: 0;
}
.online-dot {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  border: 2rpx solid var(--card-bg, #fff);
}
.online-dot.on {
  background: var(--success, #16a34a);
  box-shadow: 0 0 8rpx rgba(22, 163, 74, 0.5);
}
.online-dot.off {
  background: var(--text-subtle, #cbd5e1);
}
/* 每个 meta 独占一行：小程序 <text> 默认 inline，缺 display 会与相邻 text 连排
   （真机实测「…入库 · 8°C固件 1.0.0」连成一句；.meta.addr 的 max-width 对 inline 亦无效） */
.meta {
  display: block;
}
.name {
  font-weight: 600;
  display: block;
  font-size: var(--font-size-md);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta.revenue {
  color: var(--warning, #d97706);
  font-weight: 600;
}
.meta.stock-warn {
  color: var(--warning, #b45309);
}
</style>
