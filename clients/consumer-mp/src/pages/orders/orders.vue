<template>
  <view class="page">
    <view class="page-nav">
      <view class="nav-back" hover-class="nav-back-hover" @click="goBack">
        <image class="nav-back-svg" src="/static/nav-back.svg" mode="aspectFit" />
      </view>
      <text class="nav-title">我的订单</text>
      <view class="nav-side" />
    </view>
    <view v-if="loading" class="state-wrap"><text class="meta">加载中…</text></view>
    <view v-else-if="error" class="state-wrap">
      <text class="empty-title">加载失败</text>
      <text class="empty-desc">{{ error }}</text>
      <button class="action-btn" hover-class="btn-hover" @click="load">重试</button>
      <button class="ghost-btn" hover-class="btn-hover" @click="goShop">去扫码购物</button>
    </view>
    <view v-else-if="!authed" class="state-wrap">
      <text class="empty-title">登录后查看订单</text>
      <text class="empty-desc">登录后可查看购物账单与审核进度</text>
      <button class="action-btn" hover-class="btn-hover" @click="onAuth">去登录</button>
      <button class="ghost-btn" hover-class="btn-hover" @click="goShop">先去扫码购物</button>
    </view>
    <view v-else-if="!orders.length && !reviewingDisputes.length" class="state-wrap">
      <text class="empty-title">暂无订单</text>
      <text class="empty-desc">扫码开门购物后，账单会显示在这里</text>
      <button class="action-btn" hover-class="btn-hover" @click="goShop">去扫码购物</button>
    </view>
    <view v-else class="orders-main">
      <view v-if="reviewingDisputes.length" class="review-section">
        <text class="section-label">需要关注</text>
        <view
          v-for="d in reviewingDisputes"
          :key="d.ticketId"
          class="review-card"
          :class="'tone-' + reviewCopy(d).tone"
          @click="goDisputeDetail(d)"
        >
          <view class="review-icon">{{ reviewCopy(d).icon }}</view>
          <view class="review-body">
            <view class="review-top">
              <text class="review-title">{{ reviewCopy(d).title }}</text>
              <text class="chip pending">审核中</text>
            </view>
            <text class="review-detail">{{ reviewCopy(d).detail }}</text>
            <view class="review-foot">
              <text class="review-time">{{ formatTime(d.createdAt) }}</text>
              <text class="review-link">查看详情 ›</text>
            </view>
          </view>
        </view>
      </view>

      <view class="filter-block">
        <scroll-view scroll-x class="filter-scroll" :show-scrollbar="false">
          <view class="order-filters">
            <text
              v-for="f in filters"
              :key="f.value"
              class="filter-chip"
              :class="{ active: filter === f.value }"
              @click="filter = f.value"
            >{{ f.label }} {{ countBy(f.value) }}</text>
          </view>
        </scroll-view>
        <scroll-view scroll-x class="filter-scroll" :show-scrollbar="false">
          <view class="order-filters time-filters">
            <text
              v-for="t in timeFilters"
              :key="t.value"
              class="filter-chip time"
              :class="{ active: timeRange === t.value }"
              @click="timeRange = t.value"
            >{{ t.label }}</text>
          </view>
        </scroll-view>
      </view>

      <scroll-view
        scroll-y
        class="list"
        :show-scrollbar="false"
        lower-threshold="120"
        @scrolltolower="loadMore"
      >
        <view v-for="o in visibleOrders" :key="o.orderId" class="order-card" @click="goDetail(o)">
          <view class="order-top">
            <view class="order-meta">
              <text class="order-device-name">{{ deviceDisplay(o.deviceId) }}</text>
              <text class="order-id">{{ shortId(o.orderId) }}</text>
            </view>
            <text class="chip" :class="chipClass(o.status)">{{ statusLabel(o.status) }}</text>
          </view>
          <view class="order-mid">
            <text class="order-summary">{{ orderSummaryText(o) }}</text>
            <text class="amt">¥{{ ((o.totalAmountCents || 0) / 100).toFixed(2) }}</text>
          </view>
          <view class="order-bottom">
            <view class="order-bottom-left">
              <text class="order-channel">{{ payChannelText(o.payChannel) }}</text>
              <text class="order-time">{{ formatTime(o.createdAt) }}</text>
            </view>
            <text v-if="o.status === 'DISPUTED'" class="order-hint">审核中 ›</text>
            <text v-else class="order-hint">查看详情 ›</text>
          </view>
        </view>
        <view v-if="!visibleOrders.length" class="state-wrap compact">
          <text class="empty-title">当前筛选暂无订单</text>
          <text class="empty-desc">可切换时间或状态再试</text>
        </view>
        <view v-if="loadingMore" class="load-more">加载中…</view>
        <view v-else-if="hasMore && orders.length" class="load-more hint" @click="loadMore">上拉加载更多</view>
        <view v-else-if="orders.length && !hasMore" class="load-more hint">没有更多了</view>
        <view class="list-foot">
          <view class="foot-actions">
            <text class="foot-btn" @click="goReport">故障报修</text>
            <text class="foot-btn primary" @click="goHelp">帮助与客服</text>
          </view>
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
import { dictLabel } from '@aicabinet/shared-dict';
import { showDisputeResolvedToast } from '@/utils/notify';
import { consumerDisputeReviewCopy } from '@/utils/dispute-copy';
import type { DisputeTicketDto, OrderSummary } from '@aicabinet/shared-types';

const loading = ref(true);
const loadingMore = ref(false);
const error = ref('');
const authed = ref(false);
const orders = ref<OrderSummary[]>([]);
const disputes = ref<DisputeTicketDto[]>([]);
const pageIndex = ref(0);
const hasMore = ref(false);
const PAGE_SIZE = 20;
const filter = ref<'all' | 'paid' | 'pending' | 'issue' | 'refunded' | 'cancelled'>('all');
type TimeRange = 'all' | 'today' | '7d' | '30d';
const timeRange = ref<TimeRange>('all');
const reviewingDisputes = computed(() =>
  disputes.value.filter((d) => d.status === 'OPEN' && !orders.value.some((o) => o.sessionId === d.sessionId))
);
const filters = [
  { label: '全部', value: 'all' as const },
  { label: '已完成', value: 'paid' as const },
  { label: '处理中', value: 'pending' as const },
  { label: '有疑问', value: 'issue' as const },
  { label: '已退款', value: 'refunded' as const },
  { label: '已取消', value: 'cancelled' as const }
];
const timeFilters = [
  { label: '全部时间', value: 'all' as const },
  { label: '今天', value: 'today' as const },
  { label: '近7天', value: '7d' as const },
  { label: '近30天', value: '30d' as const }
];
const visibleOrders = computed(() =>
  orders.value.filter((o) => matchesFilter(o, filter.value) && matchesTimeRange(o.createdAt, timeRange.value))
);

function startOfTodayShanghai(): number {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(new Date());
  const y = parts.find((p) => p.type === 'year')?.value;
  const m = parts.find((p) => p.type === 'month')?.value;
  const d = parts.find((p) => p.type === 'day')?.value;
  // Asia/Shanghai 当天 00:00 对应的瞬时时间
  return new Date(`${y}-${m}-${d}T00:00:00+08:00`).getTime();
}

function matchesTimeRange(createdAt: string | undefined, range: TimeRange) {
  if (range === 'all' || !createdAt) return range === 'all' ? true : false;
  const ts = new Date(createdAt).getTime();
  if (Number.isNaN(ts)) return false;
  const now = Date.now();
  if (range === 'today') return ts >= startOfTodayShanghai();
  if (range === '7d') return ts >= now - 7 * 24 * 60 * 60 * 1000;
  if (range === '30d') return ts >= now - 30 * 24 * 60 * 60 * 1000;
  return true;
}

function matchesFilter(order: OrderSummary, value: 'all' | 'paid' | 'pending' | 'issue' | 'refunded' | 'cancelled') {
  if (value === 'paid') return order.status === 'PAID' || order.status === 'COMPLETED';
  if (value === 'pending') return order.status === 'PENDING' || order.status === 'PROCESSING';
  if (value === 'issue') return order.status === 'DISPUTED' || order.status === 'FAILED';
  if (value === 'refunded') return order.status === 'REFUNDED' || order.status === 'PARTIAL_REFUNDED';
  if (value === 'cancelled') return order.status === 'CANCELLED';
  return true;
}
function countBy(value: 'all' | 'paid' | 'pending' | 'issue' | 'refunded' | 'cancelled') {
  return orders.value.filter(
    (order) => matchesFilter(order, value) && matchesTimeRange(order.createdAt, timeRange.value)
  ).length;
}
function shortId(id?: string) {
  if (!id) return '-';
  return id.length > 12 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id;
}
function deviceDisplay(deviceId?: string) {
  if (!deviceId) return '智能柜';
  const lastId = uni.getStorageSync('last_device_id');
  const lastName = uni.getStorageSync('last_device_name');
  if (lastId === deviceId && lastName) return String(lastName);
  // 无点位名时展示真实柜机编号，避免「测试柜」等硬编码演示文案
  return deviceId;
}
function orderSummaryText(o: OrderSummary) {
  const n = o.lineCount || 0;
  if (n > 0) return `共 ${n} 件商品`;
  return '购物账单';
}
function formatTime(value?: string) {
  return formatDateTimeShort(value);
}
function reviewCopy(d: DisputeTicketDto) {
  return consumerDisputeReviewCopy(d);
}
function statusLabel(status?: string) {
  return orderStatusLabel(status);
}
function payChannelText(channel?: string) {
  return dictLabel('pay_channel', channel || '') || channel || '未知';
}
function chipClass(status?: string) {
  if (status === 'PAID' || status === 'COMPLETED') return 'paid';
  if (status === 'PENDING' || status === 'PROCESSING') return 'pending';
  if (status === 'DISPUTED' || status === 'FAILED') return 'disputed';
  if (status === 'REFUNDED' || status === 'PARTIAL_REFUNDED') return 'refunded';
  if (status === 'CANCELLED') return 'cancelled';
  return 'default';
}

function goBack() {
  const pages = getCurrentPages();
  if (pages.length > 1) {
    uni.navigateBack({ delta: 1 });
    return;
  }
  // #ifdef H5
  if (typeof window !== 'undefined' && window.history.length > 1) {
    window.history.back();
    return;
  }
  // #endif
  uni.navigateBack({
    fail: () => uni.switchTab({ url: '/pages/mine/mine' })
  });
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
  pageIndex.value = 0;
  hasMore.value = false;
  await ensureConsumerAuth();
  authed.value = !!getConsumerToken();
  if (!authed.value) {
    loading.value = false;
    return;
  }
  try {
    const [page, mine] = await Promise.all([
      consumerApi.listOrders(0, PAGE_SIZE),
      consumerApi.listMyDisputes()
    ]);
    orders.value = page.items || [];
    const total = Number(page.total ?? 0);
    hasMore.value = orders.value.length < total;
    pageIndex.value = 0;
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

async function loadMore() {
  if (!authed.value || loading.value || loadingMore.value || !hasMore.value) return;
  loadingMore.value = true;
  try {
    const nextPage = pageIndex.value + 1;
    const page = await consumerApi.listOrders(nextPage, PAGE_SIZE);
    const items = page.items || [];
    if (!items.length) {
      hasMore.value = false;
      return;
    }
    const seen = new Set(orders.value.map((o) => o.orderId));
    const appended = items.filter((o) => o.orderId && !seen.has(o.orderId));
    orders.value = orders.value.concat(appended);
    pageIndex.value = nextPage;
    const total = Number(page.total ?? 0);
    hasMore.value = orders.value.length < total && items.length >= PAGE_SIZE;
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  } finally {
    loadingMore.value = false;
  }
}

function goDisputeDetail(d: DisputeTicketDto) {
  const q = [
    d.ticketId ? `ticketId=${encodeURIComponent(d.ticketId)}` : '',
    d.sessionId ? `sessionId=${encodeURIComponent(d.sessionId)}` : ''
  ]
    .filter(Boolean)
    .join('&');
  uni.navigateTo({ url: `/pages/dispute/detail?${q}` });
}

function goDetail(o: OrderSummary) {
  uni.navigateTo({
    url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(o.orderId)}`
  });
}

function goReport() {
  uni.navigateTo({ url: '/pages/report/report' });
}

function goHelp() {
  uni.navigateTo({ url: '/pages/help/help' });
}

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped>
.page {
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #e9fbf3 0, #f5f7f8 280rpx, #f5f7f8 100%);
  box-sizing: border-box;
}
.page-nav {
  flex-shrink: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 44px;
  padding: 0;
  padding-top: env(safe-area-inset-top);
  background: #fff;
  color: #000;
  box-sizing: content-box;
}
.nav-back,
.nav-side {
  width: 48px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.nav-back {
  padding-left: 2px;
}
.nav-back-svg {
  display: block;
  width: 26px;
  height: 26px;
}
.nav-back-hover { opacity: 0.6; }
.nav-title {
  flex: 1;
  text-align: center;
  font-size: 16px;
  font-weight: 500;
  color: #000;
  line-height: 44px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.state-wrap {
  flex: 1;
  padding: 140rpx 48rpx 80rpx;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.state-wrap.compact { padding: 80rpx 24rpx; }
.meta { color: #849087; }
.empty-title { font-size: 34rpx; font-weight: 700; color: #223029; }
.empty-desc { font-size: 26rpx; color: #849087; margin-top: 12rpx; line-height: 1.55; }
.action-btn {
  margin: 40rpx 0 0;
  width: 360rpx;
  height: 88rpx;
  line-height: 88rpx;
  background: linear-gradient(135deg, #059669, #0d9488);
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
  font-weight: 600;
}
.action-btn::after, .ghost-btn::after { border: none; }
.ghost-btn {
  margin: 20rpx 0 0;
  width: 360rpx;
  height: 88rpx;
  line-height: 88rpx;
  background: #fff;
  color: #53645b;
  border-radius: 44rpx;
  font-size: 28rpx;
  border: 1rpx solid #e4ebe7;
}
.btn-hover { opacity: 0.88; }

.orders-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding-top: 12rpx;
}
.section-label {
  display: block;
  margin: 8rpx 28rpx 14rpx;
  font-size: 24rpx;
  font-weight: 650;
  color: #68766e;
  letter-spacing: 1rpx;
}
.review-section { flex-shrink: 0; padding: 0 24rpx; }
.review-card {
  display: flex;
  gap: 18rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
  border-radius: 22rpx;
  background: #fff;
  border: 1rpx solid #edf1ef;
  box-shadow: 0 10rpx 28rpx rgba(15, 23, 42, 0.05);
}
.review-card.tone-wait { background: linear-gradient(135deg, #fff, #f0fdf7); border-color: rgba(5, 150, 105, 0.22); }
.review-card.tone-warn { background: linear-gradient(135deg, #fff, #fff7ed); border-color: rgba(217, 119, 6, 0.25); }
.review-card.tone-success { background: linear-gradient(135deg, #fff, #ecfdf5); border-color: rgba(16, 185, 129, 0.28); }
.review-icon {
  width: 64rpx;
  height: 64rpx;
  border-radius: 18rpx;
  background: #ecfdf5;
  color: #047857;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30rpx;
  font-weight: 700;
  flex-shrink: 0;
}
.tone-warn .review-icon { background: #fff7ed; color: #c2410c; }
.review-body { flex: 1; min-width: 0; }
.review-top { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.review-title { font-size: 28rpx; font-weight: 700; color: #223029; }
.review-detail {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #68766e;
  line-height: 1.55;
}
.review-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 14rpx;
}
.review-time { font-size: 22rpx; color: #a1aaa5; }
.review-link { font-size: 24rpx; color: #059669; font-weight: 600; }

.filter-block { flex-shrink: 0; }
.filter-scroll { white-space: nowrap; }
.order-filters {
  display: inline-flex;
  gap: 12rpx;
  padding: 8rpx 24rpx 10rpx;
}
.time-filters { padding-top: 0; padding-bottom: 16rpx; }
.filter-chip {
  white-space: nowrap;
  padding: 12rpx 22rpx;
  border-radius: 999rpx;
  border: 1rpx solid #e7eeea;
  background: rgba(255, 255, 255, 0.92);
  color: #68766e;
  font-size: 23rpx;
  box-shadow: 0 5rpx 16rpx rgba(15, 23, 42, 0.04);
}
.filter-chip.time {
  padding: 10rpx 20rpx;
  font-size: 22rpx;
  background: #f3faf7;
  border-color: #dceee6;
}
.filter-chip.active {
  border-color: #059669;
  color: #fff;
  background: linear-gradient(135deg, #059669, #0d9488);
  box-shadow: 0 8rpx 22rpx rgba(5, 150, 105, 0.2);
}
.filter-chip.time.active {
  background: #047857;
  border-color: #047857;
  box-shadow: 0 6rpx 16rpx rgba(4, 120, 87, 0.18);
}

.list { flex: 1; min-height: 0; }
.order-card {
  margin: 0 24rpx 16rpx;
  padding: 26rpx 28rpx;
  border-radius: 22rpx;
  background: #fff;
  border: 1rpx solid #edf1ef;
  box-shadow: 0 10rpx 28rpx rgba(15, 23, 42, 0.05);
}
.order-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 16rpx; }
.order-meta { min-width: 0; }
.order-device-name { display: block; font-size: 30rpx; font-weight: 700; color: #223029; }
.order-id { display: block; margin-top: 6rpx; font-size: 22rpx; color: #a1aaa5; }
.chip {
  flex-shrink: 0;
  font-size: 22rpx;
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  font-weight: 650;
}
.chip.paid { background: #e8f8ef; color: #059669; }
.chip.pending { background: #fff8e6; color: #d97706; }
.chip.disputed { background: #ffecec; color: #ef4444; }
.chip.refunded { background: #fff3e0; color: #ea580c; }
.chip.cancelled { background: #f3f4f6; color: #6b7280; }
.chip.default { background: #f0f0f0; color: #888; }
.order-mid {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-top: 22rpx;
  gap: 16rpx;
}
.order-summary { font-size: 26rpx; color: #53645b; }
.amt { color: #047857; font-weight: 800; font-size: 40rpx; letter-spacing: -1rpx; }
.order-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx dashed #e3e9e6;
}
.order-bottom-left { display: flex; align-items: center; gap: 12rpx; flex-wrap: wrap; }
.order-channel {
  font-size: 22rpx;
  color: #576b95;
  background: #f2f4f8;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
}
.order-time { font-size: 22rpx; color: #a1aaa5; }
.order-hint { font-size: 24rpx; color: #059669; font-weight: 600; }
.load-more {
  padding: 20rpx 0 8rpx;
  text-align: center;
  font-size: 24rpx;
  color: #94a3b8;
}
.load-more.hint { color: #64748b; }
.list-foot { padding: 28rpx 24rpx 60rpx; text-align: center; }
.foot-link { font-size: 26rpx; color: #576b95; }
.foot-actions { display: flex; gap: 20rpx; justify-content: center; flex-wrap: wrap; }
.foot-btn {
  padding: 14rpx 28rpx;
  border-radius: 999rpx;
  font-size: 26rpx;
  color: #576b95;
  background: #f3f4f6;
}
.foot-btn.primary {
  color: #047857;
  background: #ecfdf5;
  font-weight: 600;
}
</style>
