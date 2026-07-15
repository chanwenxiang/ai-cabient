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
    <view v-else-if="!orders.length && !reviewingDisputes.length" class="state-wrap">
      <text class="empty-icon">📋</text>
      <text class="empty-title">暂无订单</text>
      <text class="empty-desc">扫码开门购物后，订单将显示在这里</text>
      <button class="action-btn" hover-class="btn-hover" @click="goShop">去扫码购物</button>
    </view>
    <view v-else class="orders-main">
      <view v-if="reviewingDisputes.length" class="review-section">
        <text class="review-section-title">审核中的购物</text>
        <view v-for="d in reviewingDisputes" :key="d.ticketId" class="card review-card" @click="goDisputeDetail(d)">
          <view class="order-top">
            <text class="order-id">会话 {{ shortId(d.sessionId) }}</text>
            <text class="chip pending">审核中</text>
          </view>
          <text class="review-detail">{{ consumerDisputeReviewCopy(d).detail }}</text>
          <text class="order-time">{{ formatTime(d.createdAt) }}</text>
        </view>
      </view>
      <view class="order-filters">
        <text v-for="f in filters" :key="f.value" class="filter-chip" :class="{ active: filter === f.value }" @click="filter = f.value">{{ f.label }} {{ countBy(f.value) }}</text>
      </view>
    <scroll-view scroll-y class="list" :show-scrollbar="false">
      <view v-for="o in visibleOrders" :key="o.orderId" class="card order-card" @click="goDetail(o)">
        <view class="order-top">
          <text class="order-id">订单 {{ shortId(o.orderId) }}</text>
          <text class="chip" :class="chipClass(o.status)">{{ statusLabel(o.status) }}</text>
        </view>
        <view class="order-device"><text class="device-icon">▣</text><text>{{ deviceDisplay(o.deviceId) }}</text><text class="device-code">{{ o.deviceId }}</text></view>
        <view class="order-bottom">
          <text class="order-time">{{ formatTime(o.createdAt) }}</text>
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
import { orderStatusLabel, formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { showDisputeResolvedToast } from '@/utils/notify';
import { consumerDisputeReviewCopy } from '@/utils/dispute-copy';
import type { DisputeTicketDto, OrderSummary } from '@aicabinet/shared-types';

const loading = ref(true);
const error = ref('');
const authed = ref(false);
const orders = ref<OrderSummary[]>([]);
const disputes = ref<DisputeTicketDto[]>([]);
const filter = ref<'all' | 'paid' | 'pending' | 'issue'>('all');
const reviewingDisputes = computed(() =>
  disputes.value.filter((d) => d.status === 'OPEN' && !orders.value.some((o) => o.sessionId === d.sessionId))
);
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
function deviceDisplay(deviceId?: string) {
  if (!deviceId) return '智能柜';
  const lastId = uni.getStorageSync('last_device_id');
  const lastName = uni.getStorageSync('last_device_name');
  if (lastId === deviceId && lastName) return lastName;
  return deviceId === 'CAB-001' ? '测试柜-001' : '智能零售柜';
}
function formatTime(value?: string) {
  return formatDateTimeShort(value);
}

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
    const [page, mine] = await Promise.all([
      consumerApi.listOrders(0, 30),
      consumerApi.listMyDisputes()
    ]);
    orders.value = page.items || [];
    disputes.value = mine || [];
    const lastSid = String(uni.getStorageSync('last_disputed_session_id') || '');
    if (lastSid) {
      const ticket = disputes.value.find((d) => d.sessionId === lastSid);
      if (!ticket || ticket.status !== 'OPEN') {
        uni.removeStorageSync('last_disputed_session_id');
        if (ticket?.status === 'RESOLVED') {
          showDisputeResolvedToast(ticket);
        }
      }
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function goDisputeDetail(d: DisputeTicketDto) {
  if (d.orderId) {
    uni.navigateTo({
      url: `/pages/result/result?orderId=${encodeURIComponent(d.orderId)}&sessionId=${encodeURIComponent(d.sessionId)}`
    });
    return;
  }
  uni.setStorageSync('last_disputed_session_id', d.sessionId);
  uni.switchTab({ url: '/pages/index/index' });
}

function goDetail(o: OrderSummary) {
  uni.navigateTo({
    url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(o.orderId)}`
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
.orders-main { height: 100%; display:flex; flex-direction:column; }
.review-section { padding: 20rpx 24rpx 0; flex-shrink: 0; }
.review-section-title { display: block; font-size: 26rpx; color: #68766e; margin-bottom: 12rpx; font-weight: 600; }
.review-card { margin-bottom: 16rpx; }
.review-detail { display: block; margin-top: 12rpx; font-size: 24rpx; color: #d48806; line-height: 1.5; }
.order-filters{display:flex;gap:12rpx;padding:20rpx 24rpx;background:#f7f7f7;flex-shrink:0;overflow-x:auto}.filter-chip{white-space:nowrap;padding:12rpx 22rpx;border-radius:30rpx;background:#fff;color:#666;font-size:23rpx}.filter-chip.active{background:#07c160;color:#fff}.list { flex:1; min-height:0; }.state-wrap.compact{padding:80rpx 24rpx}
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
<style scoped>
.page{background:linear-gradient(180deg,#eefbf5,#f5f7f8 260rpx)}.orders-main{padding-top:8rpx}.order-filters{padding:22rpx 24rpx 18rpx;background:transparent}.filter-chip{padding:13rpx 23rpx;border:1rpx solid #e7eeea;border-radius:999rpx;color:#68766e;background:rgba(255,255,255,.85);box-shadow:0 5rpx 16rpx rgba(15,23,42,.04)}.filter-chip.active{border-color:#059669;background:linear-gradient(135deg,#059669,#0d9488);box-shadow:0 8rpx 22rpx rgba(5,150,105,.2)}.order-card{position:relative;overflow:hidden;margin:0 24rpx 16rpx;padding:26rpx;border-radius:23rpx;box-shadow:0 10rpx 30rpx rgba(15,23,42,.06)}.order-card::before{content:'';position:absolute;left:0;top:0;bottom:0;width:6rpx;background:linear-gradient(#10b981,#0d9488)}.order-id{color:#26342d}.chip{padding:6rpx 14rpx;border-radius:999rpx;font-weight:650}.order-device{display:flex;align-items:center;gap:9rpx;margin-top:18rpx;color:#44534b;font-size:25rpx}.device-icon{display:flex;width:37rpx;height:37rpx;align-items:center;justify-content:center;border-radius:10rpx;color:#047857;background:#ecfdf5}.device-code{margin-left:auto;color:#a1aaa5;font-size:20rpx}.order-bottom{margin-top:20rpx;padding-top:16rpx;border-top:1rpx dashed #e3e9e6}.order-time{font-size:23rpx}.amt{color:#047857;font-size:36rpx}.list-foot{padding-bottom:50rpx}
</style>
