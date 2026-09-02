<template>
  <view class="page-root">
    <app-nav-bar title="我的优惠券" />
    <view class="page-body">
      <view class="tabs-pill">
        <text
          v-for="tab in tabs"
          :key="tab.key"
          class="filter-chip"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
          >{{ tab.label }}</text
        >
      </view>

      <view v-if="loading && !list.length" class="loading"><text>加载中…</text></view>
      <empty-state
        v-else-if="loadError && !list.length"
        icon="/static/menu/warning.png"
        title="优惠券加载失败"
        :hint="loadError"
      >
        <button class="empty-btn primary" @click="load">重试</button>
      </empty-state>
      <empty-state
        v-else-if="!list.length"
        icon="/static/menu/coupons.png"
        :title="emptyTitle"
        :hint="emptyHint"
      >
        <button class="empty-btn primary" @click="goShop">去扫码购物</button>
        <button class="empty-btn ghost" @click="goMarketing">看热门活动</button>
      </empty-state>
      <view v-else>
        <view
          v-for="c in list"
          :key="c.couponId"
          class="coupon-card"
          :class="{ expired: c.status === 'EXPIRED', used: c.status === 'USED' }"
        >
          <view class="coupon-left">
            <text class="coupon-amount">{{ fmtMoney(c.denominationCents) }}</text>
            <text class="coupon-type">{{ typeText(c.couponType) }}</text>
          </view>
          <view class="coupon-right">
            <view v-if="c.status === 'USED' || c.status === 'EXPIRED'" class="coupon-status-row">
              <text v-if="c.status === 'USED'" class="coupon-status-badge used">已使用</text>
              <text v-else class="coupon-status-badge expired">已过期</text>
            </view>
            <text class="coupon-name">{{ c.couponName }}</text>
            <text v-if="c.minSpendCents > 0" class="coupon-limit"
              >满{{ fmtMoney(c.minSpendCents) }}可用</text
            >
            <text v-else class="coupon-limit">无门槛</text>
            <text class="coupon-expire"
              >有效期至 {{ formatTime(c.expireAt) }}{{ expireSoon(c) ? ' · 即将过期' : '' }}</text
            >
            <text class="coupon-scope">{{ deviceScopeText(c.deviceScope) }}</text>
            <text v-if="c.description" class="coupon-desc">{{ c.description }}</text>
            <text
              v-if="c.status === 'UNUSED'"
              class="coupon-pick"
              :class="{ on: preferredId === c.couponId }"
              @click.stop="pickForNextOpen(c)"
              >{{ preferredId === c.couponId ? '已选·下次开门优先用' : '选作下次开门用' }}</text
            >
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { displayLabel } from '@aicabinet/shared-dict';
import { consumerApi, ensureConsumerAuth, type CouponDto } from '@/utils/consumer-api';
import { formatDateTimeMinute, fmtMoney } from '@aicabinet/shared-uni/format';

const tabs = [
  { key: '', label: '全部' },
  { key: 'UNUSED', label: '未使用' },
  { key: 'USED', label: '已使用' },
  { key: 'EXPIRED', label: '已过期' }
];

const activeTab = ref('');
const loading = ref(false);
const loadError = ref('');
const list = ref<CouponDto[]>([]);
const preferredId = ref<number | null>(null);

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
  const raw = uni.getStorageSync('preferred_coupon_id');
  const n = Number(raw);
  preferredId.value = Number.isFinite(n) && n > 0 ? n : null;
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/coupons/coupons')
    });
    return;
  }
  await load();
});
watch(activeTab, () => load());

async function load() {
  if (!list.value.length) loading.value = true;
  loadError.value = '';
  try {
    list.value = await consumerApi.myCoupons(activeTab.value || undefined);
  } catch (e) {
    if (!list.value.length) {
      list.value = [];
      loadError.value = e instanceof Error ? e.message : '加载失败';
      uni.showToast({ title: loadError.value, icon: 'none' });
    }
  } finally {
    loading.value = false;
  }
}

function typeText(t: string) {
  return displayLabel('coupon_type', t, '优惠券');
}

function deviceScopeText(scope?: string) {
  const s = String(scope || 'ALL').toUpperCase();
  if (s === 'ALL' || !s) return '全柜可用';
  if (s === 'SELECTED' || s === 'DEVICE' || s === 'DEVICES') return '指定柜机可用';
  return `适用范围 ${scope}`;
}

function expireSoon(c: CouponDto) {
  if (c.status !== 'UNUSED' || !c.expireAt) return false;
  const t = new Date(c.expireAt).getTime();
  if (!Number.isFinite(t)) return false;
  const days = (t - Date.now()) / 86400000;
  return days >= 0 && days <= 3;
}

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '暂无').slice(0, 10);
}

function goShop() {
  uni.switchTab({ url: '/pages/index/index' });
}

function goMarketing() {
  uni.navigateTo({ url: '/pages/marketing/index' });
}

function pickForNextOpen(c: CouponDto) {
  if (preferredId.value === c.couponId) {
    preferredId.value = null;
    uni.removeStorageSync('preferred_coupon_id');
    uni.showToast({ title: '已取消指定券', icon: 'none' });
    return;
  }
  preferredId.value = c.couponId;
  uni.setStorageSync('preferred_coupon_id', c.couponId);
  uni.showToast({ title: '下次开门将优先用此券', icon: 'none' });
}
</script>

<style scoped>
.page-root {
  padding: 0;
  background: #ffffff;
  min-height: 100%;
}
.page-body {
  padding: 20rpx 20rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.tabs-pill {
  margin-bottom: 20rpx;
}
.loading {
  text-align: center;
  padding: 80rpx 24rpx;
  color: var(--text-muted, #64748b);
}
.coupon-card {
  display: flex;
  background: #fff;
  border-radius: 16rpx;
  margin-bottom: 16rpx;
  overflow: hidden;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.04);
}
.coupon-card.expired,
.coupon-card.used {
  opacity: 0.6;
}
.coupon-left {
  width: 200rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #ff6b35, #ff8f00);
  padding: 24rpx;
}
.coupon-card.expired .coupon-left {
  background: #ccc;
}
.coupon-amount {
  color: #fff;
  font-size: 48rpx;
  font-weight: 700;
}
.coupon-type {
  color: rgba(255, 255, 255, 0.9);
  font-size: 22rpx;
  margin-top: 4rpx;
}
.coupon-right {
  flex: 1;
  min-width: 0;
  padding: 20rpx;
  overflow: hidden;
}
.coupon-status-row {
  display: flex;
  justify-content: flex-end;
  margin: -4rpx 0 8rpx;
}
.coupon-name {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  line-height: 1.35;
  word-break: break-word;
  overflow-wrap: anywhere;
  white-space: normal;
}
.coupon-limit {
  font-size: 24rpx;
  color: #64748b;
  margin-top: 4rpx;
  display: block;
}
.coupon-expire {
  font-size: 22rpx;
  color: #ccc;
  margin-top: 8rpx;
  display: block;
}
.coupon-scope,
.coupon-desc {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
.coupon-pick {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 12rpx;
  font-size: 22rpx;
  color: #064e3b;
  padding: 8rpx 16rpx;
  min-height: 48rpx;
  border-radius: 999rpx;
  background: #ecfdf5;
  border: 1rpx solid #a7f3d0;
}
.coupon-pick.on {
  background: #064e3b;
  color: #fff;
  border-color: #064e3b;
}
.coupon-status-badge {
  font-size: 20rpx;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
  white-space: nowrap;
  line-height: 1.4;
}
.coupon-status-badge.used {
  background: #e8f5e9;
  color: #065f46;
}
.coupon-status-badge.expired {
  background: #f5f5f5;
  color: #475569;
}
</style>
