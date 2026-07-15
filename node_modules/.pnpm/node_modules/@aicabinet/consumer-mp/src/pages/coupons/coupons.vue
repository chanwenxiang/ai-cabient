<template>
  <view class="page-root">
    <view class="tabs">
      <view v-for="tab in tabs" :key="tab.key" class="tab" :class="{ active: activeTab === tab.key }" @click="activeTab = tab.key">
        <text>{{ tab.label }}</text>
      </view>
    </view>

    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="!list.length" class="empty-card">
      <text class="empty-icon">🎫</text>
      <text class="empty-text">暂无优惠券</text>
      <text class="empty-hint">购物后可获得优惠券哦</text>
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
import { ref, watch } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { get } from '@/utils/consumer-api';

const tabs = [
  { key: '', label: '全部' },
  { key: 'UNUSED', label: '未使用' },
  { key: 'USED', label: '已使用' },
  { key: 'EXPIRED', label: '已过期' },
];

const activeTab = ref('');
const loading = ref(false);
const list = ref<any[]>([]);

onShow(() => load());
watch(activeTab, () => load());

async function load() {
  loading.value = true;
  try {
    const params = activeTab.value ? `?status=${activeTab.value}` : '';
    const res = await get('/api/v2/coupons' + params);
    list.value = res.data ?? [];
  } catch { list.value = []; }
  finally { loading.value = false; }
}

function typeText(t: string) {
  const map: Record<string, string> = { AMOUNT_OFF: '满减券', PERCENT_OFF: '折扣券', FREE_SHIPPING: '免运费', EXCHANGE: '兑换券' };
  return map[t] || t;
}

function formatTime(t: string) {
  if (!t) return ''; return t.substring(0, 10);
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f7f7f7; min-height: 100vh; }
.tabs { display: flex; background: #fff; border-radius: 16rpx; margin-bottom: 20rpx; overflow: hidden; }
.tab { flex: 1; text-align: center; padding: 20rpx 0; font-size: 26rpx; color: #666; }
.tab.active { color: #07c160; font-weight: 600; border-bottom: 4rpx solid #07c160; }
.loading, .empty-card { text-align: center; padding: 80rpx 0; }
.empty-icon { font-size: 80rpx; display: block; margin-bottom: 16rpx; }
.empty-text { font-size: 28rpx; color: #999; }
.empty-hint { font-size: 24rpx; color: #ccc; margin-top: 8rpx; }
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
