<template>
  <view class="page-root">
    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="error" class="empty">
      <text class="err">{{ error }}</text>
      <button class="retry" @click="load">重试</button>
    </view>
    <view v-else-if="!list.length" class="empty">
      <text>暂无柜机订单</text>
    </view>
    <view v-else>
      <view
        v-for="item in list"
        :key="item.orderId"
        class="card"
        hover-class="card-hover"
        role="button"
        @click="onDetail(item)"
      >
        <view class="card-header">
          <text class="card-id">#{{ shortId(item.orderId) }}</text>
          <text class="card-status" :class="item.status">{{ statusText(item.status) }}</text>
        </view>
        <view class="card-amount">¥{{ money(item.totalAmountCents) }}</view>
        <view class="card-meta">
          <text>{{ item.deviceId || '-' }} · {{ item.lineCount || 0 }} 件</text>
          <text>{{ formatTime(item.createdAt) }}</text>
        </view>
      </view>
      <text v-if="listTruncated" class="trunc-hint">仅显示前 {{ list.length }} 条，共 {{ listTotal }} 条</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { formatDateTimeShort, orderStatusLabel } from '@aicabinet/shared-uni/format';
import { hasPerm, merchantApi, type MerchantOrderSummary } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canList = computed(() => hasPerm(me.value, 'merchant:orders:list'));

const loading = ref(false);
const error = ref('');
const list = ref<MerchantOrderSummary[]>([]);
let loadSeq = 0;
const listTotal = ref(0);

const listTruncated = computed(
  () => listTotal.value > 0 && list.value.length > 0 && listTotal.value > list.value.length
);

onShow(() => load());
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const seq = ++loadSeq;
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
  if (!canList.value) {
    uni.showToast({ title: '无订单权限', icon: 'none' });
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const res = await merchantApi.orders(undefined, 0, 50);
    if (seq !== loadSeq) return;
    if (Array.isArray(res)) {
      list.value = res;
      listTotal.value = res.length;
    } else {
      list.value = res?.items || [];
      listTotal.value = res?.total ?? list.value.length;
    }
  } catch (e) {
    if (seq !== loadSeq) return;
    list.value = [];
    listTotal.value = 0;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function statusText(s?: string) {
  return orderStatusLabel(s) || s || '-';
}

function money(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function shortId(id?: string) {
  if (!id) return '-';
  return id.length > 14 ? id.substring(0, 14) : id;
}

function formatTime(t?: string) {
  return formatDateTimeShort(t) || '';
}

function onDetail(item: MerchantOrderSummary) {
  if (!item.orderId) return;
  uni.navigateTo({
    url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(item.orderId)}`
  });
}
</script>

<style scoped>
.page-root { min-height: 100vh; background: #f1f5f9; padding: 24rpx; box-sizing: border-box; }
.loading, .empty { text-align: center; padding: 80rpx 24rpx; color: #64748b; font-size: 28rpx; }
.err { color: #b91c1c; display: block; margin-bottom: 20rpx; }
.retry {
  display: inline-block;
  margin-top: 12rpx;
  padding: 12rpx 32rpx;
  border-radius: 999rpx;
  background: #0f766e;
  color: #fff;
  font-size: 26rpx;
}
.retry::after { border: none; }
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
  border: 1rpx solid #e2e8f0;
}
.card-hover { background: #f8fafc !important; }
.card-header { display: flex; justify-content: space-between; margin-bottom: 10rpx; }
.card-id { font-size: 22rpx; color: #94a3b8; }
.card-status { font-size: 22rpx; color: #92400e; background: #fef3c7; padding: 4rpx 12rpx; border-radius: 999rpx; }
.card-status.PAID, .card-status.COMPLETED { color: #166534; background: #dcfce7; }
.card-status.REFUNDED, .card-status.PARTIAL_REFUNDED { color: #1e40af; background: #dbeafe; }
.card-status.DISPUTED { color: #9a3412; background: #ffedd5; }
.card-status.CANCELLED, .card-status.FAILED { color: #475569; background: #e2e8f0; }
.card-amount { font-size: 36rpx; font-weight: 700; color: #0f172a; margin: 8rpx 0; }
.card-meta { display: flex; justify-content: space-between; margin-top: 12rpx; font-size: 22rpx; color: #94a3b8; }
.trunc-hint {
  display: block;
  text-align: center;
  color: #94a3b8;
  font-size: 22rpx;
  margin-top: 8rpx;
}
</style>
