<template>
  <view class="page-root">
    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="error" class="empty">
      <text class="err">{{ error }}</text>
      <button class="retry" @click="load">重试</button>
    </view>
    <empty-state
      v-else-if="!list.length"
      icon="单"
      title="暂无柜机订单"
      hint="有成交后会显示在这里"
    />
    <view v-else>
      <view
        v-for="item in list"
        :key="item.orderId"
        class="card"
        hover-class="card-hover"
        role="button"
        :aria-label="`订单 ${shortId(item.orderId)} ${statusText(item.status)} ${money(item.totalAmountCents)}`"
        @click="onDetail(item)"
      >
        <view class="card-header">
          <text class="card-id">#{{ shortId(item.orderId) }}</text>
          <text class="card-status" :class="item.status">{{ statusText(item.status) }}</text>
        </view>
        <view class="card-amount">{{ money(item.totalAmountCents) }}</view>
        <view class="card-meta">
          <text>{{ emptyDisplay(item.deviceId, 'device') }} · {{ item.lineCount || 0 }} 件</text>
          <text>{{ formatTime(item.createdAt) }}</text>
        </view>
      </view>
      <view v-if="hasMore" class="load-more" role="button" aria-label="加载更多订单" @click="loadMore">
        {{ loadingMore ? '加载中…' : `加载更多（已显示 ${list.length}/${listTotal}）` }}
      </view>
      <text v-else-if="listTruncated" class="trunc-hint">共 {{ listTotal }} 条，已全部加载</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { emptyDisplay, formatDateTimeShort, orderStatusLabel, fmtMoney } from '@aicabinet/shared-uni/format';
import EmptyState from '@/components/empty-state.vue';
import { hasPerm, merchantApi, type MerchantOrderSummary } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canList = computed(() => hasPerm(me.value, 'merchant:orders:list'));

const loading = ref(false);
const loadingMore = ref(false);
const error = ref('');
const list = ref<MerchantOrderSummary[]>([]);
let loadSeq = 0;
const listTotal = ref(0);
const pageIndex = ref(0);
const hasMore = ref(false);
const PAGE_SIZE = 50;

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
    const res = await merchantApi.orders(undefined, 0, PAGE_SIZE);
    if (seq !== loadSeq) return;
    if (Array.isArray(res)) {
      list.value = res;
      listTotal.value = res.length;
    } else {
      list.value = res?.items || [];
      listTotal.value = res?.total ?? list.value.length;
    }
    pageIndex.value = 0;
    hasMore.value = list.value.length < listTotal.value;
  } catch (e) {
    if (seq !== loadSeq) return;
    list.value = [];
    listTotal.value = 0;
    hasMore.value = false;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value || loading.value) return;
  loadingMore.value = true;
  try {
    const next = pageIndex.value + 1;
    const res = await merchantApi.orders(undefined, next, PAGE_SIZE);
    const items = Array.isArray(res) ? res : res?.items || [];
    if (!items.length) {
      hasMore.value = false;
      return;
    }
    const seen = new Set(list.value.map((o) => o.orderId));
    const appended = items.filter((o) => o.orderId && !seen.has(o.orderId));
    list.value = list.value.concat(appended);
    pageIndex.value = next;
    const total = Array.isArray(res) ? list.value.length : Number(res?.total ?? list.value.length);
    listTotal.value = total;
    hasMore.value = list.value.length < total && items.length >= PAGE_SIZE;
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  } finally {
    loadingMore.value = false;
  }
}

function statusText(s?: string) {
  return orderStatusLabel(s);
}

function money(cents?: number) {
  return fmtMoney(cents);
}

function shortId(id?: string) {
  if (!id) return emptyDisplay(id, 'order');
  return id.length > 14 ? id.substring(0, 14) : id;
}

function formatTime(t?: string) {
  return formatDateTimeShort(t, '暂无');
}

function onDetail(item: MerchantOrderSummary) {
  if (!item.orderId) return;
  uni.navigateTo({
    url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(item.orderId)}`
  });
}
</script>

<style scoped>
.page-root { /* globals in App.vue */ }
.loading, .empty { text-align: center; padding: 80rpx 24rpx; color: var(--text-muted, #64748b); font-size: 28rpx; }
.err { color: var(--danger, #b91c1c); display: block; margin-bottom: 20rpx; }
.card {
  background: #fff;
  border-radius: var(--card-radius, 22rpx);
  padding: 24rpx;
  margin: 0 0 16rpx;
  border: 1rpx solid var(--card-border, #e2e8f0);
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
.load-more {
  display: block;
  text-align: center;
  color: var(--brand, #0f766e);
  font-size: 24rpx;
  font-weight: 600;
  padding: 20rpx 0 8rpx;
}
</style>
