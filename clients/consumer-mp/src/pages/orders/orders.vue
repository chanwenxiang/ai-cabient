<template>
  <view class="page page-fill">
    <app-nav-bar title="我的订单" home-url="/pages/index/index" />
    <view v-if="booting" class="state-wrap"><text class="meta">加载中…</text></view>
    <empty-state
      v-else-if="error && !orders.length"
      class="state-wrap"
      title="加载失败"
      :hint="error"
    >
      <button class="empty-btn primary" hover-class="btn-hover" @click="load">重试</button>
      <button class="empty-btn ghost" hover-class="btn-hover" @click="goShop">扫码购物</button>
    </empty-state>
    <empty-state
      v-else-if="!authed"
      class="state-wrap"
      title="登录后查看订单"
      hint="登录后可查看购物账单与审核进度"
    >
      <button class="empty-btn primary" hover-class="btn-hover" @click="onAuth">去登录</button>
      <button class="empty-btn ghost" hover-class="btn-hover" @click="goShop">扫码购物</button>
    </empty-state>
    <view v-else class="orders-main">
      <!-- 关注区 + 筛选 + 列表同一滚动，避免上半区固定挤占购买记录 -->
      <scroll-view
        scroll-y
        class="main-scroll"
        :show-scrollbar="false"
        lower-threshold="120"
        @scrolltolower="loadMore"
      >
        <view v-if="reviewingDisputes.length" class="review-section">
          <text class="section-label"
            >需要关注{{ reviewingDisputes.length > 3 ? `（${reviewingDisputes.length}）` : '' }}</text
          >
          <view
            v-for="d in reviewingDisputesPreview"
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
          <view
            v-if="reviewingDisputesMore > 0"
            class="review-more"
            @click="filter = 'issue'"
          >
            <text>还有 {{ reviewingDisputesMore }} 条待确认，可在「有疑问」筛选查看 ›</text>
          </view>
        </view>

        <view class="filter-block">
          <scroll-view scroll-x class="filter-scroll" :show-scrollbar="false" enable-flex>
            <view class="order-filters">
              <text
                v-for="f in filters"
                :key="f.value"
                class="filter-chip"
                :class="{ active: filter === f.value }"
                @click="filter = f.value"
                >{{ f.label }}{{ filterCountSuffix(f.value) }}</text
              >
            </view>
          </scroll-view>
          <view class="order-filters time-row">
            <text
              v-for="t in timeFilters"
              :key="t.value"
              class="filter-chip time"
              :class="{ active: timeRange === t.value }"
              @click="timeRange = t.value"
              >{{ t.label }}</text
            >
            <text
              class="filter-chip time zero-toggle"
              :class="{ active: hideZeroOrders }"
              @click="toggleHideZeroOrders"
              >隐藏零元单</text
            >
          </view>
        </view>

        <view
          v-if="loading && !orders.length && !reviewingDisputes.length"
          class="state-wrap inline"
          ><text class="meta">刷新中…</text></view
        >
        <empty-state
          v-else-if="!orders.length && !reviewingDisputes.length"
          class="state-wrap"
          title="暂无订单"
          hint="扫码开门购物后，账单会显示在这里"
        >
          <button class="empty-btn primary" hover-class="btn-hover" @click="goShop">
            扫码购物
          </button>
        </empty-state>
        <view v-else class="list-inner">
          <view v-for="o in visibleOrders" :key="o.orderId" class="order-card" @click="goDetail(o)">
            <view class="order-top">
              <view class="order-meta">
                <text class="order-device-name">{{ deviceDisplay(o) }}</text>
                <text class="order-id">{{ shortId(o.orderId) }}</text>
              </view>
              <text class="chip" :class="chipClass(o.status)">{{ statusLabel(o.status) }}</text>
            </view>
            <view class="order-mid">
              <image
                class="order-thumb"
                :src="orderThumb(o)"
                mode="aspectFill"
                aria-hidden="true"
              />
              <view class="order-copy">
                <view class="order-copy-main">
                  <text class="order-summary">{{ orderSummaryText(o) }}</text>
                  <view class="order-tags">
                    <text v-if="Number(o.lineCount || 0) > 0" class="order-tag"
                      >{{ o.lineCount }} 件</text
                    >
                    <text class="order-tag">{{ payChannelText(o.payChannel) }}</text>
                    <text v-for="slot in slotTags(o)" :key="slot" class="order-tag slot">{{
                      slot
                    }}</text>
                    <text v-if="Number(o.couponDiscountCents || 0) > 0" class="order-tag soft"
                      >券减¥{{ (Number(o.couponDiscountCents) / 100).toFixed(2) }}</text
                    >
                    <text v-if="Number(o.memberDiscountCents || 0) > 0" class="order-tag soft"
                      >会员减¥{{ (Number(o.memberDiscountCents) / 100).toFixed(2) }}</text
                    >
                    <text v-if="payTradeShort(o)" class="order-tag mono">{{
                      payTradeShort(o)
                    }}</text>
                    <text v-if="canInvoiceHint(o)" class="order-tag soft">可开票</text>
                  </view>
                </view>
                <view class="order-amt-block">
                  <text v-if="showOriginal(o)" class="amt-origin">{{
                    fmtMoney(Number(o.originalAmountCents))
                  }}</text>
                  <text class="amt">{{ fmtMoney(o.totalAmountCents || 0) }}</text>
                  <text v-if="discountCents(o) > 0" class="discount"
                    >优惠减¥{{ (discountCents(o) / 100).toFixed(2) }}</text
                  >
                </view>
              </view>
            </view>
            <view class="order-bottom">
              <view class="order-bottom-left">
                <text class="order-time">{{ formatTime(o.createdAt) }}</text>
                <text v-if="refundCents(o) > 0" class="order-refund-amt"
                  >已退 {{ fmtMoney(refundCents(o)) }}</text
                >
              </view>
              <text
                v-if="o.status === 'REFUNDED' || o.status === 'PARTIAL_REFUNDED' || o.refundedAt"
                class="order-hint refund"
                >{{ o.status === 'PARTIAL_REFUNDED' ? '部分退款' : '已退款'
                }}{{ o.refundedAt ? ` · ${formatTime(o.refundedAt)}` : '' }} ›</text
              >
              <text v-else-if="o.status === 'DISPUTED'" class="order-hint">审核中 ›</text>
              <text v-else class="order-hint">查看详情 ›</text>
            </view>
          </view>
          <empty-state
            v-if="!visibleOrders.length"
            compact
            title="当前筛选暂无订单"
            :hint="
              hideZeroOrders
                ? '可关闭「隐藏零元单」或切换时间/状态再试'
                : '可切换时间或状态再试'
            "
          />
          <view v-if="loadingMore" class="load-more">加载中…</view>
          <view v-else-if="hasMore && orders.length" class="load-more hint" @click="loadMore"
            >上拉加载更多</view
          >
          <view v-else-if="orders.length && !hasMore" class="load-more hint">没有更多了</view>
          <view class="list-foot">
            <view class="foot-actions">
              <text class="foot-btn" @click="goReport">故障报修</text>
              <text class="foot-btn primary" @click="goHelp">帮助与客服</text>
            </view>
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
import {
  shortBizNo,
  formatDateTimeShort,
  startOfTodayShanghaiMs,
  orderStatusLabel,
  fmtMoney
} from '@aicabinet/shared-uni/format';
import { cleanLineSummary, skuImageFor } from '@aicabinet/shared-uni/product-image';
import { displayLabel } from '@aicabinet/shared-dict';
import { showDisputeResolvedToast } from '@/utils/notify';
import { consumerDisputeReviewCopy } from '@/utils/dispute-copy';
import type { DisputeTicketDto, OrderSummary } from '@aicabinet/shared-types';

const loading = ref(true);
const booting = ref(true);
const loadingMore = ref(false);
const error = ref('');
const authed = ref(false);
const orders = ref<OrderSummary[]>([]);
const disputes = ref<DisputeTicketDto[]>([]);
const pageIndex = ref(0);
const hasMore = ref(false);
const ordersTotal = ref(0);
const PAGE_SIZE = 20;
type OrderStatusFilter = 'all' | 'paid' | 'pending' | 'issue' | 'refunded' | 'cancelled';
const filter = ref<OrderStatusFilter>('all');
type TimeRange = 'all' | 'today' | '7d' | '30d';
const timeRange = ref<TimeRange>('all');
const HIDE_ZERO_STORAGE_KEY = 'consumer_orders_hide_zero';
const hideZeroOrders = ref(readHideZeroPreference());
const reviewingDisputes = computed(() =>
  disputes.value.filter(
    (d) => d.status === 'OPEN' && !orders.value.some((o) => o.sessionId === d.sessionId)
  )
);
/** 关注区最多展示 3 条，避免联调残留工单挤占购买记录 */
const REVIEW_PREVIEW_LIMIT = 3;
const reviewingDisputesPreview = computed(() => reviewingDisputes.value.slice(0, REVIEW_PREVIEW_LIMIT));
const reviewingDisputesMore = computed(() =>
  Math.max(0, reviewingDisputes.value.length - REVIEW_PREVIEW_LIMIT)
);
const filters = [
  { label: '全部', value: 'all' as const },
  { label: '已完成', value: 'paid' as const },
  { label: '待支付', value: 'pending' as const },
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
  orders.value.filter(
    (o) =>
      matchesFilter(o, filter.value) &&
      matchesTimeRange(o.createdAt, timeRange.value) &&
      matchesZeroFilter(o)
  )
);

function readHideZeroPreference(): boolean {
  try {
    const raw = uni.getStorageSync(HIDE_ZERO_STORAGE_KEY);
    if (raw === false || raw === '0' || raw === 'false') return false;
  } catch {
    /* ignore */
  }
  return true;
}

function toggleHideZeroOrders() {
  hideZeroOrders.value = !hideZeroOrders.value;
  try {
    uni.setStorageSync(HIDE_ZERO_STORAGE_KEY, hideZeroOrders.value);
  } catch {
    /* ignore */
  }
}

function isZeroAmountOrder(order: OrderSummary) {
  return Number(order.totalAmountCents || 0) <= 0;
}

function matchesZeroFilter(order: OrderSummary) {
  return !hideZeroOrders.value || !isZeroAmountOrder(order);
}

function startOfTodayShanghai(): number {
  return startOfTodayShanghaiMs();
}

function matchesTimeRange(createdAt: string | undefined, range: TimeRange) {
  if (range === 'all' || !createdAt) return range === 'all';
  const ts = new Date(createdAt).getTime();
  if (Number.isNaN(ts)) return false;
  const now = Date.now();
  if (range === 'today') return ts >= startOfTodayShanghai();
  if (range === '7d') return ts >= now - 7 * 24 * 60 * 60 * 1000;
  if (range === '30d') return ts >= now - 30 * 24 * 60 * 60 * 1000;
  return true;
}

function matchesFilter(order: OrderSummary, value: OrderStatusFilter) {
  if (value === 'paid') return order.status === 'PAID' || order.status === 'COMPLETED';
  if (value === 'pending') return order.status === 'PENDING' || order.status === 'PROCESSING';
  if (value === 'issue') return order.status === 'DISPUTED' || order.status === 'FAILED';
  if (value === 'refunded')
    return order.status === 'REFUNDED' || order.status === 'PARTIAL_REFUNDED';
  if (value === 'cancelled') return order.status === 'CANCELLED';
  return true;
}
function countBy(value: OrderStatusFilter) {
  return orders.value.filter(
    (order) =>
      matchesFilter(order, value) &&
      matchesTimeRange(order.createdAt, timeRange.value) &&
      matchesZeroFilter(order)
  ).length;
}

/** Avoid showing partial page counts as if they were globals while more pages remain. */
function filterCountSuffix(value: OrderStatusFilter) {
  if (hasMore.value) {
    if (value === 'all' && timeRange.value === 'all' && ordersTotal.value > 0) {
      return ` ${ordersTotal.value}`;
    }
    // 未加载完时展示「至少 N+」，避免把部分计数当成全局
    const loaded = countBy(value);
    return loaded > 0 ? ` ${loaded}+` : '';
  }
  return ` ${countBy(value)}`;
}
function shortId(id?: string) {
  return shortBizNo(id, 12, '暂无单号');
}
function deviceDisplay(o: { deviceId?: string; deviceName?: string } | string | undefined) {
  if (!o) return '无柜机';
  if (typeof o === 'string') {
    const lastId = uni.getStorageSync('last_device_id');
    const lastName = uni.getStorageSync('last_device_name');
    if (lastId === o && lastName) return String(lastName);
    return o;
  }
  if (o.deviceName) return o.deviceName;
  if (!o.deviceId) return '无柜机';
  const lastId = uni.getStorageSync('last_device_id');
  const lastName = uni.getStorageSync('last_device_name');
  if (lastId === o.deviceId && lastName) return String(lastName);
  return o.deviceId;
}
function orderSummaryText(o: OrderSummary) {
  const summary = cleanLineSummary(o.lineSummary);
  if (summary) return summary;
  const n = o.lineCount || 0;
  if (n > 0) return `共 ${n} 件商品`;
  return '购物账单';
}
function orderThumb(o: OrderSummary) {
  return skuImageFor('', '', o.lineSummary);
}
function discountCents(o: OrderSummary) {
  return Math.max(0, Number(o.couponDiscountCents || 0) + Number(o.memberDiscountCents || 0));
}
function showOriginal(o: OrderSummary) {
  const origin = Number(o.originalAmountCents || 0);
  const total = Number(o.totalAmountCents || 0);
  return origin > total && origin > 0;
}
function refundCents(o: OrderSummary) {
  const n = Number(o.refundedCents || 0);
  if (n > 0) return n;
  if (o.status === 'REFUNDED') return Number(o.totalAmountCents || 0);
  return 0;
}
function slotTags(o: OrderSummary): string[] {
  const raw = String(o.lineSummary || '');
  const found = raw.match(/货道\s*([A-Za-z0-9_-]+)/g) || [];
  const slots = found
    .map((s) => s.replace(/^货道\s*/, '').trim())
    .filter(Boolean)
    .map((s) => `货道${s}`);
  return [...new Set(slots)].slice(0, 3);
}
function canInvoiceHint(o: OrderSummary) {
  const s = String(o.status || '');
  return s === 'PAID' || s === 'COMPLETED' || s === 'PARTIAL_REFUNDED';
}
function payTradeShort(o: OrderSummary) {
  const id = o.payTradeNo || o.paymentOperationId;
  if (!id) return '';
  return shortBizNo(id, 10);
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
  return displayLabel('pay_channel', channel, '未知渠道');
}
function chipClass(status?: string) {
  if (status === 'PAID' || status === 'COMPLETED') return 'paid';
  if (status === 'PENDING' || status === 'PROCESSING') return 'pending';
  if (status === 'DISPUTED' || status === 'FAILED') return 'disputed';
  if (status === 'REFUNDED' || status === 'PARTIAL_REFUNDED') return 'refunded';
  if (status === 'CANCELLED') return 'cancelled';
  return 'default';
}

function goShop() {
  uni.removeStorageSync('active_session_id');
  uni.switchTab({ url: '/pages/index/index' });
}

async function onAuth() {
  const ok = await ensureConsumerAuth();
  if (!ok) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/orders/orders')
    });
    return;
  }
  await load();
}

async function load() {
  if (!orders.value.length && !disputes.value.length) loading.value = true;
  error.value = '';
  pageIndex.value = 0;
  hasMore.value = false;
  ordersTotal.value = 0;
  await ensureConsumerAuth();
  authed.value = !!getConsumerToken();
  if (!authed.value) {
    loading.value = false;
    booting.value = false;
    return;
  }
  try {
    const [page, mine] = await Promise.all([
      consumerApi.listOrders(0, PAGE_SIZE),
      consumerApi.listMyDisputes()
    ]);
    orders.value = page.items || [];
    const total = Number(page.total ?? 0);
    ordersTotal.value = total;
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
    booting.value = false;
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
.discount {
  font-size: 20rpx;
  color: #ea580c;
  font-weight: 600;
}

.page {
  height: 100%;
  width: 100%;
  max-width: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  box-sizing: border-box;
}
.state-wrap {
  flex: 1;
  padding: 48rpx 40rpx 32rpx;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  min-height: 40vh;
}
.state-wrap.inline {
  flex: 0;
  padding: 24rpx 40rpx;
}
.state-wrap.compact {
  padding: 32rpx 24rpx;
}
.meta {
  color: #849087;
}
.btn-hover {
  opacity: 0.88;
}

.orders-main {
  flex: 1;
  min-height: 0;
  width: 100%;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  overflow: hidden;
}
.main-scroll {
  flex: 1;
  height: 0;
  width: 100%;
  box-sizing: border-box;
}
.section-label {
  display: block;
  margin: 4rpx 4rpx 10rpx;
  font-size: 24rpx;
  font-weight: 650;
  color: #68766e;
  letter-spacing: 1rpx;
}
.review-section {
  padding: 8rpx 24rpx 0;
}
.review-card {
  display: flex;
  gap: 14rpx;
  padding: 18rpx 20rpx;
  margin-bottom: 12rpx;
  border-radius: 20rpx;
  background: #fff;
  border: 1rpx solid #edf1ef;
  box-shadow: 0 8rpx 20rpx rgba(15, 23, 42, 0.04);
}
.review-card.tone-wait {
  background: #fff;
  border-color: #edf1ef;
}
.review-card.tone-warn {
  background: #fff;
  border-color: #edf1ef;
}
.review-card.tone-success {
  background: #fff;
  border-color: #edf1ef;
}
.review-icon {
  width: 56rpx;
  height: 56rpx;
  border-radius: 16rpx;
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #047857);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28rpx;
  font-weight: 700;
  flex-shrink: 0;
}
.tone-warn .review-icon {
  background: #fff7ed;
  color: #c2410c;
}
.review-body {
  flex: 1;
  min-width: 0;
}
.review-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}
.review-title {
  font-size: 26rpx;
  font-weight: 700;
  color: #223029;
}
.review-detail {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #68766e;
  line-height: 1.45;
}
.review-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10rpx;
}
.review-time {
  font-size: 22rpx;
  color: #a1aaa5;
}
.review-link {
  font-size: 24rpx;
  color: var(--brand, #047857);
  font-weight: 600;
}
.review-more {
  margin-top: 8rpx;
  padding: 16rpx 20rpx;
  border-radius: 16rpx;
  background: rgba(4, 120, 87, 0.06);
  color: var(--brand, #047857);
  font-size: 24rpx;
  font-weight: 600;
  text-align: center;
}

.filter-block {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  background: #fff;
  padding: 8rpx 0 4rpx;
}
.filter-scroll {
  width: 100%;
  white-space: nowrap;
}
.order-filters {
  display: inline-flex;
  flex-wrap: nowrap;
  padding: 8rpx 24rpx 4rpx;
  align-items: center;
  box-sizing: border-box;
}
.order-filters .filter-chip {
  margin-right: 12rpx;
}
.order-filters .filter-chip:last-child {
  margin-right: 24rpx;
}
.order-filters.time-row {
  display: flex;
  flex-wrap: wrap;
  white-space: normal;
  padding: 12rpx 24rpx 8rpx;
  margin-top: 0;
}
.order-filters.time-row .filter-chip {
  margin-right: 12rpx;
  margin-bottom: 4rpx;
}
.order-filters.time-row .filter-chip:last-child {
  margin-right: 0;
}
.filter-chip {
  flex-shrink: 0;
  white-space: nowrap;
  padding: 10rpx 20rpx;
  border-radius: 999rpx;
  border: 1rpx solid #e7eeea;
  background: #fff;
  color: #68766e;
  font-size: 23rpx;
  box-shadow: 0 5rpx 16rpx rgba(15, 23, 42, 0.04);
}
.filter-chip.time {
  padding: 8rpx 18rpx;
  font-size: 22rpx;
  background: #f7faf8;
  border-color: #dceee6;
}
.filter-chip.active {
  border-color: var(--brand, #047857);
  color: #fff;
  background: linear-gradient(135deg, var(--brand, #047857), var(--brand, #047857));
  box-shadow: 0 8rpx 22rpx rgba(5, 150, 105, 0.2);
}
.filter-chip.time.active {
  background: var(--brand, #047857);
  border-color: var(--brand, #047857);
  box-shadow: 0 6rpx 16rpx rgba(4, 120, 87, 0.18);
}
.filter-chip.zero-toggle {
  margin-left: auto;
}

.list-inner {
  padding: 4rpx 0 12rpx;
  box-sizing: border-box;
}
.order-card {
  margin: 0 24rpx 16rpx;
  padding: 26rpx 28rpx;
  border-radius: 22rpx;
  background: #fff;
  border: 1rpx solid #edf1ef;
  box-shadow: 0 10rpx 28rpx rgba(15, 23, 42, 0.05);
}
.order-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16rpx;
}
.order-meta {
  min-width: 0;
}
.order-device-name {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: #223029;
}
.order-id {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #a1aaa5;
}
.chip {
  flex-shrink: 0;
  font-size: 22rpx;
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  font-weight: 650;
}
.chip.paid {
  background: #e8f8ef;
  color: #065f46;
}
.chip.pending {
  background: #fff8e6;
  color: #b45309;
}
.chip.disputed {
  background: #ffecec;
  color: #991b1b;
}
.chip.refunded {
  background: #fff3e0;
  color: #c2410c;
}
.chip.cancelled {
  background: #f3f4f6;
  color: #4b5563;
}
.chip.default {
  background: #f0f0f0;
  color: #475569;
}
.order-mid {
  display: flex;
  align-items: center;
  margin-top: 22rpx;
  gap: 20rpx;
}
.order-thumb {
  width: 96rpx;
  height: 96rpx;
  border-radius: 18rpx;
  background: var(--brand-soft, #ecfdf5);
  flex-shrink: 0;
}
.order-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16rpx;
}
.order-copy-main {
  flex: 1;
  min-width: 0;
}
.order-summary {
  display: block;
  font-size: 26rpx;
  color: #53645b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.order-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-top: 10rpx;
}
.order-tag {
  font-size: 20rpx;
  color: #576b95;
  background: #f2f4f8;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  max-width: 220rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.order-tag.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: #64748b;
}
.order-tag.slot {
  color: #0f766e;
  background: #ecfdf5;
}
.order-tag.soft {
  color: #b45309;
  background: #fffbeb;
}
.order-amt-block {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4rpx;
}
.amt-origin {
  font-size: 22rpx;
  color: #a1aaa5;
  text-decoration: line-through;
}
.amt {
  color: var(--brand, #047857);
  font-weight: 800;
  font-size: 40rpx;
  letter-spacing: -1rpx;
  line-height: 1.1;
}
.order-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx dashed #e3e9e6;
}
.order-bottom-left {
  display: flex;
  align-items: center;
  gap: 12rpx;
  flex-wrap: wrap;
}
.order-time {
  font-size: 22rpx;
  color: #a1aaa5;
}
.order-refund-amt {
  font-size: 22rpx;
  color: #b45309;
  font-weight: 600;
}
.order-hint {
  font-size: 24rpx;
  color: var(--brand, #047857);
  font-weight: 600;
}
.order-hint.refund {
  color: #b45309;
}
.load-more {
  padding: 20rpx 0 8rpx;
  text-align: center;
  font-size: 24rpx;
  color: #94a3b8;
}
.load-more.hint {
  color: #64748b;
}
.list-foot {
  padding: 28rpx 24rpx calc(160rpx + env(safe-area-inset-bottom));
  text-align: center;
}
.foot-link {
  font-size: 26rpx;
  color: #576b95;
}
.foot-actions {
  display: flex;
  gap: 20rpx;
  justify-content: center;
  flex-wrap: wrap;
}
.foot-btn {
  padding: 14rpx 28rpx;
  border-radius: 999rpx;
  font-size: 26rpx;
  color: #576b95;
  background: #f3f4f6;
}
.foot-btn.primary {
  color: var(--brand, #047857);
  background: var(--brand-soft, #ecfdf5);
  font-weight: 600;
}
</style>

<style>
/* 非 scoped：确保列表滚动条不露出来 */
.page .main-scroll,
.page .filter-scroll {
  scrollbar-width: none !important;
  -ms-overflow-style: none !important;
}
.page .main-scroll::-webkit-scrollbar,
.page .filter-scroll::-webkit-scrollbar {
  width: 0 !important;
  height: 0 !important;
  display: none !important;
}
</style>
