<template>
  <view class="page page-fill">
    <app-nav-bar title="我的订单" home-url="/pages/index/index" />
    <view v-if="booting" class="state-wrap"
      ><text class="meta">{{ UI_COPY.loading }}</text></view
    >
    <empty-state
      v-else-if="error && !orders.length"
      class="state-wrap"
      :title="UI_COPY.loadFailed"
      :hint="error"
    >
      <view class="btn-slot">
        <app-button :label="UI_COPY.retry" @click="load" />
      </view>
      <view class="btn-slot">
        <app-button variant="ghost" label="扫码购物" @click="goShop" />
      </view>
    </empty-state>
    <empty-state
      v-else-if="!authed"
      class="state-wrap"
      title="登录后查看订单"
      hint="登录后可查看购物账单与审核进度"
    >
      <view class="btn-slot">
        <app-button label="去登录" @click="onAuth" />
      </view>
      <view class="btn-slot">
        <app-button variant="ghost" label="扫码购物" @click="goShop" />
      </view>
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
            >需要关注{{
              reviewingDisputes.length > 3 ? `（${reviewingDisputes.length}）` : ''
            }}</text
          >
          <view
            v-for="d in reviewingDisputesPreview"
            role="button"
            :key="d.ticketId"
            class="review-card"
            :class="'tone-' + reviewCopy(d).tone"
            @click="goDisputeDetail(d)"
          >
            <view class="review-icon">{{ reviewCopy(d).icon }}</view>
            <view class="review-body">
              <view class="review-top">
                <text class="review-title">{{ reviewCopy(d).title }}</text>
                <text class="chip pending">{{ displayLabel('dispute_status', d.status) }}</text>
              </view>
              <text class="review-detail">{{ reviewCopy(d).detail }}</text>
              <view class="review-foot">
                <text class="review-time">{{ formatTime(d.createdAt) }}</text>
                <text class="review-link app-link-chevron">查看详情</text>
              </view>
            </view>
          </view>
          <view v-if="reviewingDisputesMore > 0" class="review-more" @click="filter = 'issue'">
            <text class="app-link-chevron"
              >还有 {{ reviewingDisputesMore }} 条待确认，可在「有疑问」筛选查看</text
            >
          </view>
        </view>

        <view class="filter-block">
          <!-- 扩展功能：订单关键字搜索（consumer.order_search.enabled，关时不渲染） -->
          <view v-if="searchEnabled" class="search-row">
            <input
              v-model="searchKeyword"
              class="search-input"
              type="text"
              placeholder="搜索订单号 / 柜机 / 商品"
              placeholder-class="search-placeholder"
              confirm-type="search"
            />
            <text
              v-if="searchKeyword"
              role="button"
              class="search-clear"
              @click="searchKeyword = ''"
              >清空</text
            >
          </view>
          <scroll-view scroll-x class="filter-scroll" :show-scrollbar="false" enable-flex>
            <view class="order-filters">
              <text
                v-for="f in filters"
                role="button"
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
              role="button"
              :key="t.value"
              class="filter-chip time"
              :class="{ active: timeRange === t.value }"
              @click="timeRange = t.value"
              >{{ t.label }}</text
            >
            <text
              role="button"
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
          <app-button label="扫码购物" @click="goShop" />
        </empty-state>
        <view v-else class="list-inner">
          <view
            v-for="o in visibleOrders"
            role="button"
            :key="o.orderId"
            class="order-card"
            @click="goDetail(o)"
          >
            <view class="order-top">
              <view class="order-meta">
                <text class="order-device-name">{{ deviceDisplay(o) }}</text>
                <text class="order-id">{{ orderIdDisplay(o.orderId) }}</text>
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
                    <text v-if="Number(o.lineCount ?? 0) > 0" class="order-tag"
                      >{{ o.lineCount }} 件</text
                    >
                    <text class="order-tag">{{ payChannelText(o.payChannel) }}</text>
                    <text v-for="slot in slotTags(o)" :key="slot" class="order-tag slot">{{
                      slot
                    }}</text>
                    <!-- 后端 couponDiscountCents 为 primitive int（无券恒 0），必须 > 0 才算有券 -->
                    <text v-if="Number(o.couponDiscountCents ?? 0) > 0" class="order-tag soft"
                      >券减{{ fmtMoney(o.couponDiscountCents) }}</text
                    >
                    <text v-if="Number(o.memberDiscountCents ?? 0) > 0" class="order-tag soft"
                      >会员减{{ fmtMoney(o.memberDiscountCents) }}</text
                    >
                    <text v-if="payTradeDisplay(o)" class="order-tag mono">{{
                      payTradeDisplay(o)
                    }}</text>
                    <text v-if="canInvoiceHint(o)" class="order-tag soft">可开票</text>
                  </view>
                </view>
                <view class="order-amt-block">
                  <text v-if="showOriginal(o)" class="amt-origin">{{
                    fmtMoney(Number(o.originalAmountCents))
                  }}</text>
                  <text class="amt">{{ fmtMoney(o.totalAmountCents ?? 0) }}</text>
                  <text v-if="discountCents(o) > 0" class="discount"
                    >优惠减{{ fmtMoney(discountCents(o)) }}</text
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
                class="order-hint refund app-link-chevron"
                >{{
                  displayLabel(
                    'order_status',
                    o.status === 'PARTIAL_REFUNDED' ? 'PARTIAL_REFUNDED' : 'REFUNDED'
                  )
                }}{{ o.refundedAt ? ` · ${formatTime(o.refundedAt)}` : '' }}</text
              >
              <text v-else-if="o.status === 'DISPUTED'" class="order-hint app-link-chevron">{{
                displayLabel('order_status', 'DISPUTED')
              }}</text>
              <text v-else class="order-hint app-link-chevron">查看详情</text>
            </view>
          </view>
          <empty-state
            v-if="!visibleOrders.length"
            compact
            title="当前筛选暂无订单"
            :hint="
              hideZeroOrders ? '可关闭「隐藏零元单」或切换时间/状态再试' : '可切换时间或状态再试'
            "
          />
          <view v-if="loadingMore" class="load-more">{{ UI_COPY.loading }}</view>
          <view
            v-else-if="hasMore && orders.length"
            role="button"
            class="load-more hint"
            @click="loadMore"
            >上拉加载更多</view
          >
          <view v-else-if="orders.length && !hasMore" class="load-more hint">没有更多了</view>
          <view class="list-foot">
            <view class="foot-actions">
              <text role="button" class="foot-btn" @click="goReport">故障报修</text>
              <text role="button" class="foot-btn primary" @click="goHelp">帮助与客服</text>
            </view>
          </view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { isOrderTerminal, useAutoRefresh } from '@/composables/use-auto-refresh';
import { computed, ref } from 'vue';
import { consumerApi, ensureConsumerAuth, isConsumerLoggedIn } from '@/utils/consumer-api';
import { loadConsumerFlags, orderSearchEnabled } from '@/utils/feature-flags';
import {
  displayBizNo,
  formatDateTimeShort,
  startOfTodayShanghaiMs,
  orderStatusLabel,
  fmtMoney
} from '@aicabinet/shared-uni/format';
import { cleanLineSummary, skuImageFor } from '@aicabinet/shared-uni/product-image';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import { displayLabel } from '@aicabinet/shared-dict';
import { showDisputeResolvedToast, showError } from '@/utils/notify';
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
/**
 * 扩展功能：订单关键字搜索（`consumer.order_search.enabled`）。
 * 默认关 ⇒ 搜索框不渲染、过滤条件不参与，与接入前完全一致。
 */
const searchEnabled = ref(false);
const searchKeyword = ref('');
/** 消息中心「待支付」深链经此一次性 storage 传过滤（tabBar 页无法带参跳转） */
const ORDERS_PENDING_FILTER_KEY = 'orders_pending_filter';
const reviewingDisputes = computed(() =>
  disputes.value.filter(
    (d) => d.status === 'OPEN' && !orders.value.some((o) => o.sessionId === d.sessionId)
  )
);
/** 关注区最多展示 3 条，避免联调残留工单挤占购买记录 */
const REVIEW_PREVIEW_LIMIT = 3;
const reviewingDisputesPreview = computed(() =>
  reviewingDisputes.value.slice(0, REVIEW_PREVIEW_LIMIT)
);
const reviewingDisputesMore = computed(() =>
  Math.max(0, reviewingDisputes.value.length - REVIEW_PREVIEW_LIMIT)
);
const filters = [
  { label: '全部', value: 'all' as const },
  { label: displayLabel('order_status', 'COMPLETED'), value: 'paid' as const },
  { label: displayLabel('order_status', 'PENDING'), value: 'pending' as const },
  { label: '有疑问', value: 'issue' as const },
  { label: displayLabel('order_status', 'REFUNDED'), value: 'refunded' as const },
  { label: displayLabel('order_status', 'CANCELLED'), value: 'cancelled' as const }
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
      matchesZeroFilter(o) &&
      matchesKeyword(o)
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
  return Number(order.totalAmountCents ?? 0) <= 0;
}

function matchesZeroFilter(order: OrderSummary) {
  return !hideZeroOrders.value || !isZeroAmountOrder(order);
}

/**
 * 关键字搜索：匹配订单号 / 柜机名 / 商品摘要（大小写不敏感）。
 * 开关关闭或关键字为空时**恒返回 true**（不过滤），保证默认路径零影响。
 */
function matchesKeyword(order: OrderSummary) {
  if (!searchEnabled.value) return true;
  const kw = searchKeyword.value.trim().toLowerCase();
  if (!kw) return true;
  return [order.orderId, order.deviceName, order.lineSummary].some((v) =>
    String(v ?? '')
      .toLowerCase()
      .includes(kw)
  );
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
function orderIdDisplay(id?: string) {
  return displayBizNo(id, '暂无单号');
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
  const n = o.lineCount ?? 0;
  if (n > 0) return `共 ${n} 件商品`;
  return '购物账单';
}
function orderThumb(o: OrderSummary) {
  return skuImageFor('', '', o.lineSummary);
}
function discountCents(o: OrderSummary) {
  return Math.max(0, Number(o.couponDiscountCents ?? 0) + Number(o.memberDiscountCents ?? 0));
}
function showOriginal(o: OrderSummary) {
  const origin = Number(o.originalAmountCents ?? 0);
  const total = Number(o.totalAmountCents ?? 0);
  return origin > total && origin > 0;
}
function refundCents(o: OrderSummary) {
  // M-6：只信服务端 refundedCents
  const n = Number(o.refundedCents ?? 0);
  return Number.isFinite(n) && n > 0 ? n : 0;
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
function payTradeDisplay(o: OrderSummary) {
  const id = o.payTradeNo || o.paymentOperationId;
  if (!id) return '';
  return displayBizNo(id);
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
  authed.value = isConsumerLoggedIn();
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
    showError(e instanceof Error ? e.message : '加载失败');
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

/**
 * 一次性消费消息中心写入的过滤意图（orders_pending_filter）：
 * 存在则把筛选切到对应档位并立即移除 key，仅本次进入生效；值不合法时忽略。
 */
function applyPendingFilterFromStorage() {
  try {
    const raw = uni.getStorageSync(ORDERS_PENDING_FILTER_KEY);
    if (!raw) return;
    uni.removeStorageSync(ORDERS_PENDING_FILTER_KEY);
    if (filters.some((f) => f.value === raw)) {
      filter.value = raw as OrderStatusFilter;
    }
  } catch {
    /* ignore */
  }
}

onShow(() => {
  uni.showTabBar({ animation: false });
  applyPendingFilterFromStorage();
  load();
  // 扩展功能开关（fail-closed：配置取不到就保持关闭，行为与接入前一致）
  // 先同步读一次：若缓存已被首页预置（seedConsumerFlags）则首屏即为正确值，不会再「闪」出搜索框。
  searchEnabled.value = orderSearchEnabled();
  void loadConsumerFlags().then(() => {
    searchEnabled.value = orderSearchEnabled();
  });
});
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

/**
 * 后端结果是异步到达的（落单 → 审核 → 扣款 / 争议结案）：列表里只要还有未到终态的行，
 * 就每 10 秒静默跟进一次，用户不必手动下拉。
 *
 * 只在「还没翻过页」时轮询：load() 会把列表重置回第一页，若用户已经「加载更多」，
 * 轮询就会把他的翻页结果吞掉。翻过页后仍可手动下拉刷新。
 */
useAutoRefresh({
  intervalMs: 10_000,
  load,
  shouldContinue: () =>
    orders.value.some((o) => !isOrderTerminal(o.status)) ||
    disputes.value.some((d) => {
      const s = String(d.status || '').toUpperCase();
      return s !== 'RESOLVED' && s !== 'CLOSED';
    }),
  maxDurationMs: 300_000,
  canRefresh: () => authed.value && pageIndex.value === 0 && !loading.value && !loadingMore.value
});
</script>

<style scoped src="./orders.page.css"></style>

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
