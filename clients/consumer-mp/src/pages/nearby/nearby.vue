<template>
  <view class="page page-fill">
    <view class="nav" :style="{ paddingTop: statusBarPad + 'px' }">
      <text class="nav-back" role="button" aria-label="返回" @click="goBack">‹</text>
      <text class="nav-title">附近柜机</text>
      <text class="nav-action" @click="reload">刷新</text>
    </view>

    <view class="toolbar">
      <text class="loc-hint">{{ locHint }}</text>
      <view class="radius-row">
        <text
          v-for="r in radiusOptions"
          :key="r"
          class="radius-chip"
          :class="{ on: radiusKm === r }"
          @click="setRadius(r)"
          >{{ r }}km</text
        >
      </view>
    </view>

    <view v-if="loading" class="state">定位并加载附近柜机…</view>
    <view v-else-if="error" class="state error">
      <text>{{ error }}</text>
      <button class="retry" @click="reload">重试</button>
    </view>
    <view v-else-if="!list.length" class="state">
      <text>附近 {{ radiusKm }}km 暂无柜机</text>
      <text class="sub">可扩大范围，或扫柜门二维码开门</text>
    </view>
    <scroll-view v-else class="list" scroll-y>
      <view v-for="d in list" :key="d.deviceId" class="card" @click="openDevice(d)">
        <view class="card-top">
          <view class="card-title-wrap">
            <text class="card-name">{{ d.deviceName || d.deviceId }}</text>
            <text class="card-id">{{ d.deviceId }}</text>
          </view>
          <text class="dist">{{ formatDist(d.distanceMeters) }}</text>
        </view>
        <text class="addr">{{ d.address || '地址待完善' }}</text>
        <view class="meta">
          <text class="chip" :class="d.available ? 'ok' : 'busy'">{{
            d.available ? '可开门' : '忙碌/停售'
          }}</text>
          <text
            class="chip"
            :class="String(d.onlineStatus || '').toUpperCase() === 'ONLINE' ? 'ok' : 'muted'"
            >{{ String(d.onlineStatus || '').toUpperCase() === 'ONLINE' ? '在线' : '离线' }}</text
          >
          <text class="chip muted"
            >在售 {{ d.sellableSkuCount }} 种 · {{ d.sellableItemCount }} 件</text
          >
        </view>
        <view v-if="d.previewSkus?.length" class="preview">
          <text v-for="s in d.previewSkus" :key="s.skuId" class="preview-item"
            >{{ s.skuName }}×{{ s.quantity
            }}{{ s.unitPriceCents != null ? ` ¥${(s.unitPriceCents / 100).toFixed(2)}` : '' }}</text
          >
        </view>
        <view class="card-actions">
          <!-- 不用 size=mini：微信原生 mini 热区过小，自定义 72rpx 行内双按钮 -->
          <button class="btn ghost" @click.stop="openNav(d)">导航</button>
          <button class="btn primary" :disabled="!d.available" @click.stop="openDevice(d)">
            去开门
          </button>
        </view>
      </view>
      <view class="list-pad" />
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { consumerApi } from '@/utils/consumer-api';
import { getBelowCapsulePadPx } from '@aicabinet/shared-uni/status-bar';

type NearbyDevice = Awaited<ReturnType<typeof consumerApi.nearbyDevices>>[number];

const statusBarPad = getBelowCapsulePadPx(8);
const radiusOptions = [2, 5, 10, 20];
const radiusKm = ref(5);
const loading = ref(true);
const error = ref('');
const list = ref<NearbyDevice[]>([]);
const lat = ref(31.2304);
const lng = ref(121.4737);
const locHint = computed(() =>
  usingFallbackLoc.value
    ? '未获取定位，已用演示坐标（上海黄浦）'
    : `已定位 · 半径 ${radiusKm.value}km`
);
const usingFallbackLoc = ref(false);

function formatDist(m: number) {
  if (m < 1000) return `${Math.round(m)}m`;
  return `${(m / 1000).toFixed(1)}km`;
}

function setRadius(r: number) {
  radiusKm.value = r;
  loadList();
}

function goBack() {
  uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/index/index' }) });
}

function openDevice(d: NearbyDevice) {
  uni.setStorageSync('reopen_device_id', d.deviceId);
  if (d.deviceName) uni.setStorageSync('reopen_device_name', d.deviceName);
  uni.switchTab({ url: '/pages/index/index' });
}

function openNav(d: NearbyDevice) {
  if (d.latitude == null || d.longitude == null) {
    uni.showToast({ title: '暂无坐标', icon: 'none' });
    return;
  }
  // #ifdef H5
  const q = encodeURIComponent(d.address || d.deviceName || d.deviceId);
  window.open(
    `https://uri.amap.com/marker?position=${d.longitude},${d.latitude}&name=${q}`,
    '_blank'
  );
  // #endif
  // #ifndef H5
  uni.openLocation({
    latitude: Number(d.latitude),
    longitude: Number(d.longitude),
    name: d.deviceName || d.deviceId,
    address: d.address || ''
  });
  // #endif
}

function locate(): Promise<void> {
  return new Promise((resolve) => {
    uni.getLocation({
      type: 'gcj02',
      success: (res) => {
        lat.value = res.latitude;
        lng.value = res.longitude;
        usingFallbackLoc.value = false;
        resolve();
      },
      fail: () => {
        usingFallbackLoc.value = true;
        lat.value = 31.2304;
        lng.value = 121.4737;
        resolve();
      }
    });
  });
}

async function loadList() {
  loading.value = true;
  error.value = '';
  try {
    list.value = await consumerApi.nearbyDevices({
      lat: lat.value,
      lng: lng.value,
      radiusKm: radiusKm.value,
      limit: 30
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
    list.value = [];
  } finally {
    loading.value = false;
  }
}

async function reload() {
  await locate();
  await loadList();
}

onMounted(() => {
  reload();
});
</script>

<style scoped>
.page {
  min-height: 100%;
  background: #f5f7f6;
  display: flex;
  flex-direction: column;
}
.nav {
  display: flex;
  align-items: center;
  padding: 8px 12px 12px;
  background: #064e3b;
  color: #fff;
}
.nav-back {
  width: 36px;
  font-size: 28px;
  line-height: 1;
}
.nav-title {
  flex: 1;
  text-align: center;
  font-size: 17px;
  font-weight: 600;
}
.nav-action {
  width: 48px;
  text-align: right;
  font-size: 13px;
  opacity: 0.9;
}
.toolbar {
  padding: 12px 16px 4px;
  background: #fff;
  border-bottom: 1px solid #e8eeeb;
}
.loc-hint {
  font-size: 12px;
  color: #64748b;
}
.radius-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.radius-chip {
  padding: 4px 12px;
  border-radius: 999px;
  background: #f1f5f4;
  color: #334155;
  font-size: 12px;
}
.radius-chip.on {
  background: #064e3b;
  color: #fff;
}
.state {
  padding: 48px 24px;
  text-align: center;
  color: #64748b;
  font-size: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
}
.state.error {
  color: #b91c1c;
}
.state .sub {
  font-size: 12px;
  color: #94a3b8;
}
.retry {
  margin-top: 8px;
  background: #064e3b;
  color: #fff;
  font-size: 13px;
  border-radius: 20px;
  padding: 0 20px;
}
.list {
  flex: 1;
  height: 0;
  padding: 12px 12px 0;
  box-sizing: border-box;
}
.card {
  background: #fff;
  border-radius: 14px;
  padding: 14px;
  margin-bottom: 10px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.card-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.card-name {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}
.card-id {
  display: block;
  font-size: 11px;
  color: #94a3b8;
  margin-top: 2px;
}
.dist {
  font-size: 14px;
  font-weight: 600;
  color: #064e3b;
  white-space: nowrap;
}
.addr {
  display: block;
  margin-top: 8px;
  font-size: 12px;
  color: #64748b;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.chip {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #e2e8f0;
  color: #334155;
}
.chip.ok {
  background: #d1fae5;
  color: #065f46;
}
.chip.busy {
  background: #fee2e2;
  color: #991b1b;
}
.chip.muted {
  background: #f1f5f9;
}
.preview {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.preview-item {
  font-size: 11px;
  color: #475569;
  background: #f8fafc;
  padding: 2px 8px;
  border-radius: 6px;
}
.card-actions {
  display: flex;
  flex-direction: row;
  align-items: stretch;
  margin-top: 20rpx;
}
.card-actions .btn + .btn {
  margin-left: 16rpx;
}
.btn {
  flex: 1 1 0;
  margin: 0;
  padding: 0 20rpx;
  min-width: 0;
  min-height: 72rpx;
  height: 72rpx;
  line-height: 72rpx;
  border-radius: 36rpx;
  font-size: 26rpx;
  font-weight: 600;
  text-align: center;
  box-sizing: border-box;
}
.btn::after {
  border: none;
}
.btn.ghost {
  background: #ecfdf5;
  color: #0f766e;
  border: 1rpx solid #99f6e4;
}
.btn.primary {
  background: #0f766e;
  color: #fff;
  border: none;
}
.btn[disabled] {
  opacity: 0.45;
}
.list-pad {
  height: 48rpx;
}
</style>
