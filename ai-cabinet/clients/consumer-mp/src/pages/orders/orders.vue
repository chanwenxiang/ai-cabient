<template>
  <view class="page">
    <view v-if="loading" class="state-wrap"><text class="meta">加载中…</text></view>
    <view v-else-if="error" class="state-wrap">
      <text class="empty-title">加载失败</text>
      <text class="empty-desc">{{ error }}</text>
      <button class="action-btn" hover-class="btn-hover" @click="load">重试</button>
      <button class="ghost-btn" hover-class="btn-hover" @click="goShop">去扫码购物</button>
    </view>
    <view v-else-if="!authed" class="state-wrap">
      <text class="empty-icon">🔐</text>
      <text class="empty-title">授权后查看订单</text>
      <text class="empty-desc">扫码购物会自动完成微信授权</text>
      <button class="action-btn" hover-class="btn-hover" @click="onAuth">微信授权</button>
      <button class="ghost-btn" hover-class="btn-hover" @click="goShop">先去扫码购物</button>
    </view>
    <view v-else-if="!orders.length" class="state-wrap">
      <text class="empty-icon">📋</text>
      <text class="empty-title">暂无订单</text>
      <text class="empty-desc">扫码开门购物后，订单将显示在这里</text>
      <button class="action-btn" hover-class="btn-hover" @click="goShop">去扫码购物</button>
    </view>
    <view v-else class="orders-main">
      <view class="order-filters">
        <text v-for="f in filters" :key="f.value" class="filter-chip" :class="{ active: filter === f.value }" @click="filter = f.value">{{ f.label }} {{ countBy(f.value) }}</text>
      </view>
    <scroll-view scroll-y class="list" :show-scrollbar="false">
      <view v-for="o in visibleOrders" :key="o.orderId" class="card order-card" @click="goDetail(o)">
        <view class="order-top">
          <text class="order-id">订单 {{ shortId(o.orderId) }}</text>
          <text class="chip" :class="chipClass(o.status)">{{ statusLabel(o.status) }}</text>
        </view>
        <text class="meta">{{ o.deviceId }}</text>
        <view class="order-bottom">
          <text class="order-time">{{ o.createdAt || '' }}</text>
          <text class="amt">¥{{ ((o.totalAmountCents || 0) / 100).toFixed(2) }}</text>
        </view>
        <view v-if="o.status === 'DISPUTED'" class="order-actions" @click.stop="goDetail(o)">
          <text class="order-action-text">账单审核中 · 点击查看</text>
        </view>
      </view>
      <view v-if="!visibleOrders.length" class="state-wrap compact"><text class="empty-title">当前分类暂无订单</text></view>
      <view class="list-foot">
        <text class="foot-link" @click="goReport">柜机有问题？故障报修</text>
      </view>
    </scroll-view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { consumerApi, ensureConsumerAuth, getConsumerToken } from '@/utils/consumer-api';
import { orderStatusLabel } from '@aicabinet/shared-uni/format';
import type { OrderSummary } from '@aicabinet/shared-types';

const loading = ref(true);
const error = ref('');
const authed = ref(false);
const orders = ref<OrderSummary[]>([]);
const filter = ref<'all' | 'paid' | 'pending' | 'issue'>('all');
const filters = [{ label: '全部', value: 'all' as const }, { label: '已完成', value: 'paid' as const }, { label: '处理中', value: 'pending' as const }, { label: '有疑问', value: 'issue' as const }];
const visibleOrders = computed(() => orders.value.filter((o) => {
  return matchesFilter(o, filter.value);
}));
function matchesFilter(order: OrderSummary, value: 'all' | 'paid' | 'pending' | 'issue') {
  if (value === 'paid') return order.status === 'PAID' || order.status === 'COMPLETED';
  if (value === 'pending') return order.status === 'PENDING' || order.status === 'PROCESSING';
  if (value === 'issue') return order.status === 'DISPUTED' || order.status === 'FAILED';
  return true;
}
function countBy(value: 'all' | 'paid' | 'pending' | 'issue') {
  return orders.value.filter((order) => matchesFilter(order, value)).length;
}
function shortId(id: string) { return id.length > 12 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id; }

function statusLabel(status?: string) {
  return orderStatusLabel(status);
}

function chipClass(status?: string) {
  if (status === 'PAID' || status === 'COMPLETED') return 'paid';
  if (status === 'PENDING' || status === 'PROCESSING') return 'pending';
  if (status === 'DISPUTED' || status === 'FAILED') return 'disputed';
  return 'default';
}

function goShop() {
  uni.removeStorageSync('active_session_id');
  uni.switchTab({ url: '/pages/index/index' });
}

async function onAuth() {
  const ok = await ensureConsumerAuth();
  if (!ok) {
    uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/orders/orders') });
    return;
  }
  await load();
}

async function load() {
  loading.value = true;
  error.value = '';
  await ensureConsumerAuth();
  authed.value = !!getConsumerToken();
  if (!authed.value) {
    loading.value = false;
    return;
  }
  try {
    const page = await consumerApi.listOrders(0, 30);
    orders.value = page.items || [];
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function goDetail(o: OrderSummary) {
  uni.navigateTo({
    url: `/pages/result/result?orderId=${encodeURIComponent(o.orderId)}&sessionId=${encodeURIComponent(o.sessionId || '')}`
  });
}

function goReport() {
  uni.navigateTo({ url: '/pages/report/report' });
}

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped>
.page { height: 100vh; overflow: hidden; background: #f7f7f7; }
.state-wrap {
  padding: 120rpx 48rpx 80rpx;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.empty-icon { font-size: 80rpx; margin-bottom: 16rpx; }
.empty-title { font-size: 32rpx; font-weight: 600; color: #191919; }
.empty-desc { font-size: 26rpx; color: #888; margin-top: 12rpx; line-height: 1.5; }
.action-btn {
  margin: 40rpx 0 0;
  width: 360rpx;
  height: 88rpx;
  line-height: 88rpx;
  background: #07c160;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
  font-weight: 500;
}
.action-btn::after { border: none; }
.ghost-btn {
  margin: 20rpx 0 0;
  width: 360rpx;
  height: 88rpx;
  line-height: 88rpx;
  background: #fff;
  color: #576b95;
  border-radius: 44rpx;
  font-size: 28rpx;
}
.ghost-btn::after { border: none; }
.btn-hover { opacity: 0.85; }
.orders-main { height: 100%; display:flex; flex-direction:column; }.order-filters{display:flex;gap:12rpx;padding:20rpx 24rpx;background:#f7f7f7;flex-shrink:0;overflow-x:auto}.filter-chip{white-space:nowrap;padding:12rpx 22rpx;border-radius:30rpx;background:#fff;color:#666;font-size:23rpx}.filter-chip.active{background:#07c160;color:#fff}.list { flex:1; min-height:0; }.state-wrap.compact{padding:80rpx 24rpx}
.order-card { margin-bottom: 0; }
.order-top { display: flex; justify-content: space-between; align-items: center; }
.order-id { font-weight: 600; font-size: 28rpx; color: #191919; }
.chip { font-size: 22rpx; padding: 4rpx 12rpx; border-radius: 20rpx; }
.chip.paid { background: #e8f8ef; color: #07c160; }
.chip.pending { background: #fff8e6; color: #fa9d3b; }
.chip.disputed { background: #ffecec; color: #fa5151; }
.chip.default { background: #f0f0f0; color: #888; }
.order-bottom { display: flex; justify-content: space-between; align-items: center; margin-top: 12rpx; }
.order-time { font-size: 22rpx; color: #888; }
.amt { color: #07c160; font-weight: 700; font-size: 32rpx; }
.order-actions { margin-top: 12rpx; padding-top: 12rpx; border-top: 1rpx solid #f0f0f0; }
.order-action-text { font-size: 24rpx; color: #d48806; }
.list-foot { padding: 32rpx; text-align: center; }
.foot-link { font-size: 26rpx; color: #576b95; }
</style>
