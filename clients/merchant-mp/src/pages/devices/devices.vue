<template>
  <view>
    <view class="toolbar">
      <button class="scan-btn" :loading="scanning" @click="onScan">扫码到柜</button>
      <button v-if="canReplenishment" class="replenish-btn" @click="goReplenishment">补货任务</button>
    </view>
    <view v-if="loading" class="card">加载中…</view>
    <view v-else-if="error" class="card"><text class="err">{{ error }}</text></view>
    <view v-else>
      <view class="filters">
        <input v-model="keyword" class="search" placeholder="搜索柜机名称或编号" />
        <view class="chips"><text v-for="f in filters" :key="f.value" class="chip" :class="{ active: filter === f.value }" @click="filter = f.value">{{ f.label }} {{ countFor(f.value) }}</text></view>
      </view>
      <view v-for="d in visibleDevices" :key="d.deviceId" class="card device-card" @click="goDetail(d.deviceId)">
        <view class="device-left">
          <view class="online-dot" :class="d.online ? 'on' : 'off'" />
          <view>
            <text class="name">{{ d.deviceName || d.deviceId }}</text>
            <text class="meta">{{ d.deviceId }}</text>
          </view>
        </view>
        <text :class="d.online ? 'status-on' : 'status-off'">{{ d.online ? '在线' : '离线' }}</text>
      </view>
      <view v-if="!visibleDevices.length" class="card empty">{{ devices.length ? '没有符合条件的柜机' : '暂无柜机' }}</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import type { DeviceInfo, MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canListDevices = computed(() => hasPerm(me.value, 'merchant:devices:list'));
const canReplenishment = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));

const loading = ref(true);
const scanning = ref(false);
const error = ref('');
const devices = ref<(DeviceInfo & { online?: boolean })[]>([]);
const keyword = ref('');
const filter = ref<'all' | 'online' | 'offline'>('all');
const filters = [
  { label: '全部', value: 'all' as const },
  { label: '在线', value: 'online' as const },
  { label: '离线', value: 'offline' as const }
];
const visibleDevices = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  return devices.value.filter((d) => {
    const statusMatch = filter.value === 'all' || (filter.value === 'online' ? d.online : !d.online);
    const keywordMatch = !q || `${d.deviceName || ''} ${d.deviceId}`.toLowerCase().includes(q);
    return statusMatch && keywordMatch;
  });
});

function countFor(value: 'all' | 'online' | 'offline') {
  if (value === 'all') return devices.value.length;
  return devices.value.filter((d) => value === 'online' ? d.online : !d.online).length;
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  try {
    await refreshMe();
  } catch {
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canListDevices.value) {
    uni.showToast({ title: '无柜机权限', icon: 'none' });
    uni.switchTab({ url: '/pages/home/home' });
    return;
  }
  loading.value = true;
  try {
    const list = await merchantApi.devices();
    devices.value = list.map((d) => ({ ...d, online: (d.onlineStatus || '').toUpperCase() === 'ONLINE' }));
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
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
    const hit = devices.value.find((d) => d.deviceId === id);
    if (!hit) {
      uni.showToast({ title: '未找到该柜机或无权限', icon: 'none' });
      return;
    }
    goDetail(id);
  } finally {
    scanning.value = false;
  }
}

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 12rpx;
  padding: 16rpx 24rpx 0;
  background: #f0fdfa;
}
.scan-btn, .replenish-btn {
  flex: 1;
  margin: 0;
  height: 72rpx;
  line-height: 72rpx;
  border-radius: 36rpx;
  font-size: 26rpx;
  font-weight: 600;
}
.scan-btn { background: #0f766e; color: #fff; }
.replenish-btn { background: #fff; color: #0f766e; border: 1rpx solid #99f6e4; }
.scan-btn::after, .replenish-btn::after { border: none; }
.device-card { display: flex; justify-content: space-between; align-items: center; }
.filters { position: sticky; top: 0; z-index: 2; background: #f0fdfa; padding: 16rpx 24rpx 12rpx; }
.search { height: 72rpx; box-sizing: border-box; background: #fff; border: 1rpx solid #ccfbf1; border-radius: 36rpx; padding: 0 28rpx; font-size: 26rpx; }
.chips { display: flex; gap: 12rpx; margin-top: 14rpx; }
.chip { padding: 10rpx 24rpx; border-radius: 28rpx; color: #64748b; background: #fff; font-size: 23rpx; }
.chip.active { color: #fff; background: #0f766e; }
.empty { text-align: center; color: #64748b; }
.device-left { display: flex; align-items: center; gap: 16rpx; }
.online-dot { width: 16rpx; height: 16rpx; border-radius: 50%; }
.online-dot.on { background: #16a34a; box-shadow: 0 0 8rpx rgba(22,163,74,0.5); }
.online-dot.off { background: #cbd5e1; }
.name { font-weight: 600; display: block; font-size: 28rpx; }
.status-on { color: #16a34a; font-weight: 600; font-size: 26rpx; }
.status-off { color: #94a3b8; font-size: 26rpx; }
.err { color: #ef4444; }
</style>
