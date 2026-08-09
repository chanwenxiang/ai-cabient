<template>
  <view class="page-root devices-page">
    <view class="toolbar">
      <button class="scan-btn" :loading="scanning" @click="onScan">扫码到柜</button>
      <button v-if="canReplenishment" class="replenish-btn" @click="goReplenishment">
        补货任务
      </button>
    </view>
    <view v-if="loading" class="card">加载中…</view>
    <view v-else-if="error" class="card">
      <text class="err">{{ error }}</text>
      <button class="retry" size="mini" @click="load">重试</button>
    </view>
    <view v-else>
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
          <text v-if="d.salesLocked" class="status-locked">停售</text>
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
  loading.value = true;
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
</script>

<style scoped>
.devices-page {
  padding: 0;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
}
.toolbar {
  display: flex;
  gap: 12rpx;
  padding: 16rpx 24rpx 0;
  background: #f0fdfa;
}
.scan-btn,
.replenish-btn {
  flex: 1;
  margin: 0;
  height: 72rpx;
  line-height: 72rpx;
  border-radius: 36rpx;
  font-size: 26rpx;
  font-weight: 600;
}
.scan-btn {
  background: #0f766e;
  color: #fff;
}
.replenish-btn {
  background: #fff;
  color: #0f766e;
  border: 1rpx solid #99f6e4;
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
  align-items: center;
  gap: 16rpx;
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
  z-index: 2;
  background: #f0fdfa;
  padding: 16rpx 24rpx 12rpx;
}
.search {
  height: 72rpx;
  box-sizing: border-box;
  background: #fff;
  border: 1rpx solid #ccfbf1;
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
  background: #0f766e;
}
.pref-hint {
  margin-top: 12rpx;
  display: flex;
  justify-content: space-between;
  font-size: 22rpx;
  color: #0f766e;
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
.err {
  color: #ef4444;
  display: block;
}
.retry {
  margin-top: 16rpx;
  background: linear-gradient(135deg, #134e4a, #0f766e);
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
