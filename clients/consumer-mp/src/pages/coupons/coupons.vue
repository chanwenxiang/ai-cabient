<template>
  <view class="page-root">
    <view class="tabs">
      <view v-for="tab in tabs" :key="tab.key" class="tab" :class="{ active: activeTab === tab.key }" @click="activeTab = tab.key">
        <text>{{ tab.label }}</text>
      </view>
    </view>

    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="loadError" class="empty-card">
      <text class="empty-icon">!</text>
      <text class="empty-text">优惠券加载失败</text>
      <text class="empty-hint">{{ loadError }}</text>
      <view class="empty-actions">
        <button class="empty-btn" @click="load">重试</button>
      </view>
    </view>
    <view v-else-if="!list.length" class="empty-card">
      <text class="empty-icon">券</text>
      <text class="empty-text">{{ emptyTitle }}</text>
      <text class="empty-hint">{{ emptyHint }}</text>
      <view class="empty-actions">
        <button class="empty-btn" @click="goShop">去扫码购物</button>
        <button class="empty-btn ghost" @click="goMarketing">看热门活动</button>
      </view>
    </view>
    <view v-else>
      <view v-for="c in list" :key="c.couponId" class="coupon-card" :class="{ expired: c.status === 'EXPIRED', used: c.status === 'USED' }">
        <view class="coupon-left">
          <text class="coupon-amount">¥{{ (c.denominationCents / 100).toFixed(0) }}</text>
          <text class="coupon-type">{{ typeText(c.couponType) }}</text>
        </view>
        <view class="coupon-right">
          <text class="coupon-name">{{ c.couponName }}</text>
          <text v-if="c.minSpendCents > 0" class="coupon-limit">满¥{{ (c.minSpendCents / 100).toFixed(0) }}可用</text>
          <text class="coupon-expire">有效期至 {{ formatTime(c.expireAt) }}</text>
          <text v-if="c.status === 'USED'" class="coupon-status-badge used">已使用</text>
          <text v-else-if="c.status === 'EXPIRED'" class="coupon-status-badge expired">已过期</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { displayLabel } from '@aicabinet/shared-dict';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';

const tabs = [
  { key: '', label: '全部' },
  { key: 'UNUSED', label: '未使用' },
  { key: 'USED', label: '已使用' },
  { key: 'EXPIRED', label: '已过期' },
];

const activeTab = ref('');
const loading = ref(false);
const loadError = ref('');
const list = ref<any[]>([]);

const emptyTitle = computed(() => {
  if (activeTab.value === 'UNUSED') return '暂无未使用优惠券';
  if (activeTab.value === 'USED') return '暂无已使用优惠券';
  if (activeTab.value === 'EXPIRED') return '暂无已过期优惠券';
  return '暂无优惠券';
});
const emptyHint = computed(() =>
  activeTab.value
    ? '可切换状态再试，或去热门活动领券'
    : '可先去逛逛热门活动，或扫码购物后领取优惠券'
);

onShow(async () => {
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/coupons/coupons') });
    return;
  }
  await load();
});
watch(activeTab, () => load());

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    list.value = await consumerApi.myCoupons(activeTab.value || undefined);
  } catch (e: any) {
    list.value = [];
    loadError.value = e?.message || '加载失败';
    uni.showToast({ title: loadError.value, icon: 'none' });
  } finally {
    loading.value = false;
  }
}

function typeText(t: string) {
  return displayLabel('coupon_type', t);
}

function formatTime(t: string) {
  return formatDateTimeMinute(t, '').slice(0, 10);
}

function goShop() {
  uni.switchTab({ url: '/pages/index/index' });
}

function goMarketing() {
  uni.navigateTo({ url: '/pages/marketing/index' });
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f7f7f7; min-height: 100vh; }
.tabs { display: flex; background: #fff; border-radius: 16rpx; margin-bottom: 20rpx; overflow: hidden; }
.tab { flex: 1; text-align: center; padding: 20rpx 0; font-size: 26rpx; color: #666; }
.tab.active { color: #07c160; font-weight: 600; border-bottom: 4rpx solid #07c160; }
.loading, .empty-card { text-align: center; padding: 80rpx 0; }
.empty-icon {
  width: 88rpx;
  height: 88rpx;
  margin: 0 auto 16rpx;
  border-radius: 24rpx;
  background: #f0fdf4;
  color: #059669;
  font-size: 36rpx;
  font-weight: 700;
  line-height: 88rpx;
  display: block;
}
.empty-text { font-size: 30rpx; font-weight: 700; color: #223029; display: block; }
.empty-hint { font-size: 24rpx; color: #849087; margin-top: 8rpx; display: block; padding: 0 40rpx; line-height: 1.5; }
.empty-actions { display: flex; flex-direction: column; align-items: center; gap: 16rpx; margin-top: 32rpx; }
.empty-btn {
  margin: 0;
  width: 320rpx;
  height: 72rpx;
  line-height: 72rpx;
  background: #07c160;
  color: #fff;
  border-radius: 36rpx;
  font-size: 28rpx;
  border: none;
}
.empty-btn.ghost {
  background: #fff;
  color: #059669;
  border: 2rpx solid #86efac;
}
.empty-btn::after { border: none; }
.coupon-card { display: flex; background: #fff; border-radius: 16rpx; margin-bottom: 16rpx; overflow: hidden; box-shadow: 0 2rpx 8rpx rgba(0,0,0,.04); }
.coupon-card.expired, .coupon-card.used { opacity: .6; }
.coupon-left { width: 200rpx; display: flex; flex-direction: column; align-items: center; justify-content: center; background: linear-gradient(135deg, #ff6b35, #ff8f00); padding: 24rpx; }
.coupon-card.expired .coupon-left { background: #ccc; }
.coupon-amount { color: #fff; font-size: 48rpx; font-weight: 700; }
.coupon-type { color: rgba(255,255,255,.9); font-size: 22rpx; margin-top: 4rpx; }
.coupon-right { flex: 1; padding: 20rpx; position: relative; }
.coupon-name { font-size: 28rpx; font-weight: 600; display: block; }
.coupon-limit { font-size: 24rpx; color: #999; margin-top: 4rpx; display: block; }
.coupon-expire { font-size: 22rpx; color: #ccc; margin-top: 8rpx; display: block; }
.coupon-status-badge { position: absolute; top: 16rpx; right: 16rpx; font-size: 20rpx; padding: 4rpx 12rpx; border-radius: 8rpx; }
.coupon-status-badge.used { background: #e8f5e9; color: #07c160; }
.coupon-status-badge.expired { background: #f5f5f5; color: #999; }
</style>
