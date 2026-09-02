<template>
  <view class="page-root devices-page">
    <app-nav-bar title="柜机" />
    <view class="toolbar">
      <button class="scan-btn" :loading="scanning" @click="onScan">扫码到柜</button>
      <button v-if="canReplenishment" class="replenish-btn" @click="goReplenishment">
        补货任务
      </button>
    </view>
    <view class="filters">
      <input
        v-model="keyword"
        class="search"
        aria-label="搜索柜机名称或编号"
        placeholder="搜索柜机名称或编号…"
      />
      <view class="chips">
        <text
          v-for="f in filters"
          :key="f.value"
          class="chip"
          :class="{ active: filter === f.value }"
          @click="filter = f.value"
          >{{ f.label }} {{ countFor(f.value) }}</text
        >
        <text class="chip" :class="{ active: onlyPreferred }" @click="toggleOnlyPreferred"
          >常驻柜 {{ preferredId ? '1' : '0' }}</text
        >
      </view>
      <view v-if="preferredId" class="pref-hint">
        <text>常驻：{{ preferredLabel }}</text>
        <text class="pref-clear" @click="clearPreferred">清除</text>
      </view>
    </view>
    <view v-if="loading && !devices.length" class="card">加载中…</view>
    <view v-else-if="error && !devices.length" class="card">
      <text class="err">{{ error }}</text>
      <button class="retry" @click="load">重试</button>
    </view>
    <view v-else>
      <view
        v-for="d in visibleDevices"
        :key="d.deviceId"
        class="card device-card"
        hover-class="device-card-hover"
        role="button"
        @click="goDetail(d.deviceId)"
      >
        <view class="device-left">
          <image
            class="device-thumb"
            src="/static/device-default.png"
            mode="aspectFill"
            aria-hidden="true"
          />
          <view class="online-dot" :class="d.online ? 'on' : 'off'" />
          <view>
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
            <text v-if="d.firmwareVersion" class="meta">固件 {{ d.firmwareVersion }}</text>
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
          <button
            v-if="d.latitude != null && d.longitude != null"
            class="nav-btn"
            @click.stop="openNav(d)"
          >
            导航
          </button>
          <text v-if="d.salesLocked" class="status-locked">停售</text>
          <text v-if="d.salesLocked && d.salesLockReason" class="status-lock-reason">{{
            d.salesLockReason
          }}</text>
          <text v-if="d.replenishmentInProgress" class="status-replenish">补货中</text>
          <text :class="d.online ? 'status-on' : 'status-off'">
            {{ d.online ? '在线' : '离线' }}
          </text>
        </view>
      </view>
      <empty-state
        v-if="!visibleDevices.length"
        icon="/static/menu/cabinet.png"
        :title="emptyHint"
        hint="可切换筛选或扫码绑定常驻柜"
      />
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import EmptyState from '@/components/empty-state.vue';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import {
  clearPreferredDeviceId,
  getPreferredDeviceId,
  setPreferredDeviceId
} from '@/utils/preferred-device';
import { dictLabel } from '@aicabinet/shared-dict';
import { confirmOpenDeviceNavigation } from '@/utils/open-device-navigation';
import type { DeviceInfo, MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canListDevices = computed(() => hasPerm(me.value, 'merchant:devices:list'));
const canReplenishment = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));

const loading = ref(true);
const scanning = ref(false);
const error = ref('');
let loadSeq = 0;
const devices = ref<(DeviceInfo & { online?: boolean })[]>([]);
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

function toggleOnlyPreferred() {
  if (!preferredId.value) {
    uni.showToast({ title: '先点 ★ 设常驻柜', icon: 'none' });
    return;
  }
  onlyPreferred.value = !onlyPreferred.value;
}

function togglePreferred(id: string) {
  if (preferredId.value === id) {
    clearPreferredDeviceId();
    preferredId.value = '';
    onlyPreferred.value = false;
    uni.showToast({ title: '已取消常驻', icon: 'none' });
    return;
  }
  setPreferredDeviceId(id);
  preferredId.value = id;
  uni.showToast({ title: '已设为常驻柜', icon: 'success' });
}

function clearPreferred() {
  clearPreferredDeviceId();
  preferredId.value = '';
  onlyPreferred.value = false;
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const seq = ++loadSeq;
  preferredId.value = getPreferredDeviceId();
  try {
    await refreshMe();
  } catch {
    if (!uni.getStorageSync('merchant_token')) return;
    me.value = me.value || (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (seq !== loadSeq) return;
  if (!me.value) {
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canListDevices.value) {
    uni.showToast({ title: '无柜机权限', icon: 'none' });
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
  } catch (e) {
    if (seq !== loadSeq) return;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function goDetail(id: string) {
  uni.navigateTo({ url: `/pages/device-detail/device-detail?id=${encodeURIComponent(id)}` });
}

function openNav(d: DeviceInfo) {
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
    uni.showToast({ title: '无补货权限', icon: 'none' });
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
    if (!hit) {
      uni.showToast({ title: '未找到该柜机或无权限', icon: 'none' });
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
.toolbar {
  display: flex;
  gap: 12rpx;
  padding: 16rpx 24rpx 0;
  background: var(--page-tint, #f0fdfa);
}
.scan-btn,
.replenish-btn {
  flex: 1 1 0;
  width: 0;
  min-width: 0;
  max-width: none;
  margin: 0;
  min-height: 72rpx;
  height: 72rpx;
  border-radius: 36rpx;
  font-size: 26rpx;
  font-weight: 600;
}
.scan-btn {
  background: var(--brand, #0f766e);
  color: #fff;
}
.replenish-btn {
  background: #fff;
  color: var(--brand, #0f766e);
  border: 1rpx solid var(--brand-soft, #99f6e4);
}
.scan-btn::after,
.replenish-btn::after {
  border: none;
}
.device-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.device-card-hover {
  background: #f8fafc !important;
  opacity: 0.96;
}
.device-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10rpx;
}
.nav-btn {
  margin: 0;
  padding: 0 20rpx;
  min-height: 64rpx;
  height: 64rpx;
  font-size: 24rpx;
  color: #0f766e;
  background: #ecfdf5;
  border: 1rpx solid #99f6e4;
  border-radius: 999rpx;
}
.nav-btn::after {
  border: none;
}
.star {
  color: #cbd5e1;
  font-size: 36rpx;
  padding: 8rpx;
  position: relative;
  z-index: 1;
}
.star.on {
  color: #f59e0b;
}
.name,
.meta,
.status-on,
.status-off,
.online-dot {
  pointer-events: none;
}
.filters {
  position: sticky;
  top: 0;
  z-index: 5;
  isolation: isolate;
  background: var(--page-tint, #f0fdfa);
  padding: 16rpx 24rpx 12rpx;
}
.search {
  height: 72rpx;
  box-sizing: border-box;
  background: #fff;
  border: 1rpx solid var(--brand-tint, #ccfbf1);
  border-radius: 36rpx;
  padding: 0 28rpx;
  font-size: 26rpx;
}
.chips {
  display: flex;
  gap: 12rpx;
  margin-top: 14rpx;
  flex-wrap: wrap;
}
.chip {
  padding: 10rpx 24rpx;
  border-radius: 28rpx;
  color: #64748b;
  background: #fff;
  font-size: 23rpx;
}
.chip.active {
  color: #fff;
  background: var(--brand, #0f766e);
}
.pref-hint {
  margin-top: 12rpx;
  display: flex;
  justify-content: space-between;
  font-size: 22rpx;
  color: var(--brand, #0f766e);
}
.pref-clear {
  color: #64748b;
}
.empty {
  text-align: center;
  color: #64748b;
}
.device-left {
  display: flex;
  align-items: center;
  gap: 16rpx;
  flex: 1;
}
.device-thumb {
  width: 88rpx;
  height: 88rpx;
  border-radius: 14rpx;
  background: #ecfdf5;
  flex-shrink: 0;
}
.online-dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
}
.online-dot.on {
  background: #16a34a;
  box-shadow: 0 0 8rpx rgba(22, 163, 74, 0.5);
}
.online-dot.off {
  background: #cbd5e1;
}
.name {
  font-weight: 600;
  display: block;
  font-size: 28rpx;
  max-width: 360rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status-on {
  color: #16a34a;
  font-weight: 600;
  font-size: 26rpx;
}
.status-off {
  color: #94a3b8;
  font-size: 26rpx;
}
.status-locked {
  color: #b45309;
  font-weight: 700;
  font-size: 24rpx;
  background: #fef3c7;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
}
.status-lock-reason {
  display: block;
  margin-top: 6rpx;
  font-size: 20rpx;
  color: #b45309;
  max-width: 200rpx;
  text-align: right;
  line-height: 1.3;
}
.status-replenish {
  color: #0f766e;
  font-weight: 600;
  font-size: 24rpx;
}
.meta.stock-warn {
  color: #b45309;
}
.err {
  color: #ef4444;
  display: block;
}
.retry {
  margin-top: 16rpx;
  background: linear-gradient(135deg, var(--brand-deep, #134e4a), var(--brand, #0f766e));
  color: #fff;
  border-radius: 44rpx;
  font-weight: 600;
  border: none;
  box-shadow: 0 8rpx 20rpx rgba(15, 118, 110, 0.2);
}
.retry::after {
  border: none;
}
</style>
