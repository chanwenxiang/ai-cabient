<template>
  <view class="page-root">
    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="error" class="empty">
      <text class="err">{{ error }}</text>
      <button class="retry" @click="load">重试</button>
    </view>
    <empty-state
      v-else-if="!list.length"
      icon="/static/menu/orders.png"
      title="暂无柜机订单"
      hint="有成交后会显示在这里"
    />
    <view v-else>
      <view class="filter-panel">
        <input
          v-model="keyword"
          class="search-input"
          placeholder="订单号 / 柜机 / 会话"
          confirm-type="search"
          aria-label="搜索订单"
          @confirm="applySearch"
        />
        <button v-if="canExport" class="export-btn" :disabled="exporting" @click="exportOrders">
          {{ exporting ? '导出中…' : '导出' }}
        </button>
        <scroll-view scroll-x class="filter-scroll" :show-scrollbar="false">
          <view class="filter-row">
            <text
              v-for="s in statusOptions"
              :key="s.value"
              class="filter-chip"
              :class="{ active: status === s.value }"
              @click="setStatus(s.value)"
              >{{ s.label }}</text
            >
          </view>
        </scroll-view>
        <view class="filter-row">
          <picker
            :range="deviceOptions"
            range-key="label"
            :value="deviceIndex"
            @change="onDeviceChange"
          >
            <view class="filter-picker">{{ deviceLabel }}</view>
          </picker>
          <text
            v-for="t in timeOptions"
            :key="t.value"
            class="filter-chip"
            :class="{ active: timeRange === t.value }"
            @click="setTime(t.value)"
            >{{ t.label }}</text
          >
          <text class="filter-reset" @click="resetFilters">重置</text>
        </view>
      </view>
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
        <view class="card-main">
          <image
            class="card-thumb"
            :src="skuImageFor('', '', item.lineSummary)"
            mode="aspectFill"
            aria-hidden="true"
          />
          <view class="card-copy">
            <text class="card-goods">{{ lineSummaryText(item) }}</text>
            <text class="card-meta">
              {{ emptyDisplay(item.deviceId, 'device') }} · {{ item.lineCount || 0 }} 件 ·
              {{ channelText(item.payChannel) }}
            </text>
            <text v-if="Number(item.couponDiscountCents || 0) > 0" class="card-discount"
              >券 -¥{{ ((item.couponDiscountCents || 0) / 100).toFixed(2) }}</text
            >
            <text class="card-time">{{ formatTime(item.createdAt) }}</text>
          </view>
          <text class="card-amount">{{ money(item.totalAmountCents) }}</text>
        </view>
      </view>
      <view
        v-if="hasMore"
        class="load-more"
        role="button"
        aria-label="加载更多订单"
        @click="loadMore"
      >
        {{ loadingMore ? '加载中…' : `加载更多（已显示 ${list.length}/${listTotal}）` }}
      </view>
      <text v-else-if="listTruncated" class="trunc-hint">共 {{ listTotal }} 条，已全部加载</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import {
  emptyDisplay,
  formatDateTimeShort,
  orderStatusLabel,
  fmtMoney
} from '@aicabinet/shared-uni/format';
import EmptyState from '@/components/empty-state.vue';
import {
  hasPerm,
  merchantApi,
  downloadAuthedFile,
  openExportedFile,
  type MerchantOrderSummary
} from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantMe } from '@aicabinet/shared-types';
import { skuImageFor } from '@aicabinet/shared-uni/product-image';

const { me, refresh: refreshMe } = useMerchantMe();
const canList = computed(() => hasPerm(me.value, 'merchant:orders:list'));
const canExport = computed(() => hasPerm(me.value, 'merchant:reports:export'));
const exporting = ref(false);

async function exportOrders() {
  if (exporting.value) return;
  exporting.value = true;
  try {
    const url = `${merchantApi.exportOrdersUrl()}`;
    const file = await downloadAuthedFile(url);
    const d = new Date();
    const p = (n: number) => String(n).padStart(2, '0');
    await openExportedFile(
      file,
      `merchant-orders-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}.csv`
    );
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '导出失败', icon: 'none' });
  } finally {
    exporting.value = false;
  }
}

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

const keyword = ref('');
const status = ref('');
const timeRange = ref('all');
const filterDeviceId = ref('');
const deviceOptions = ref<{ label: string; value: string }[]>([]);
const statusOptions = [
  { value: '', label: '全部' },
  { value: 'PAID', label: '已支付' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'DISPUTED', label: '争议中' },
  { value: 'CANCELLED', label: '已取消' }
];
const timeOptions = [
  { value: 'all', label: '全部时间' },
  { value: 'today', label: '今天' },
  { value: '7d', label: '近7天' },
  { value: '30d', label: '近30天' }
];
const deviceIndex = computed(() => {
  const i = deviceOptions.value.findIndex((d) => d.value === filterDeviceId.value);
  return i < 0 ? 0 : i;
});
const deviceLabel = computed(() => {
  const hit = deviceOptions.value.find((d) => d.value === filterDeviceId.value);
  return hit ? hit.label : '全部柜机';
});

function dateStr(offsetDays: number) {
  const d = new Date();
  d.setDate(d.getDate() - offsetDays);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function orderParams(page = 0, size = PAGE_SIZE) {
  const params: Record<string, string | number> = { page, size };
  if (filterDeviceId.value) params.deviceId = filterDeviceId.value;
  if (status.value) params.status = status.value;
  if (timeRange.value === 'today') params.from = dateStr(0);
  else if (timeRange.value === '7d') params.from = dateStr(6);
  else if (timeRange.value === '30d') params.from = dateStr(29);
  const kw = keyword.value.trim();
  if (kw) params.keyword = kw;
  return params;
}

async function loadDevices() {
  try {
    const devices = await merchantApi.devices();
    deviceOptions.value = [
      { label: '全部柜机', value: '' },
      ...devices.map((d) => ({
        label: d.deviceName || d.deviceId,
        value: d.deviceId
      }))
    ];
  } catch {
    deviceOptions.value = [{ label: '全部柜机', value: '' }];
  }
}

function applySearch() {
  load();
}

function setStatus(value: string) {
  status.value = value;
  load();
}

function setTime(value: string) {
  timeRange.value = value;
  load();
}

function onDeviceChange(e: { detail: { value: number } }) {
  const opt = deviceOptions.value[e.detail.value];
  filterDeviceId.value = opt ? opt.value : '';
  load();
}

function resetFilters() {
  keyword.value = '';
  status.value = '';
  timeRange.value = 'all';
  filterDeviceId.value = '';
  load();
}

function lineSummaryText(item: MerchantOrderSummary) {
  const summary = String(item.lineSummary || '').trim();
  if (summary) return summary;
  return `${item.lineCount || 0} 件商品`;
}

onShow(() => {
  void loadDevices();
  load();
});
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
    const res = await merchantApi.orders(orderParams(0, PAGE_SIZE));
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
    const res = await merchantApi.orders(orderParams(next, PAGE_SIZE));
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

function channelText(channel?: string) {
  return (
    (
      {
        WECHAT: '微信',
        ALIPAY: '支付宝',
        BALANCE: '余额',
        PAYSCORE: '微信支付分',
        UNKNOWN: '未知'
      } as Record<string, string>
    )[String(channel || '').toUpperCase()] || '—'
  );
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
.card-discount {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #b91c1c;
  font-weight: 600;
}

.page-root {
  /* globals in App.vue */
}
.loading,
.empty {
  text-align: center;
  padding: 80rpx 24rpx;
  color: var(--text-muted, #64748b);
  font-size: 28rpx;
}
.err {
  color: var(--danger, #b91c1c);
  display: block;
  margin-bottom: 20rpx;
}
.filter-panel {
  background: #fff;
  border-radius: 20rpx;
  padding: 20rpx 20rpx 14rpx;
  margin: 0 0 16rpx;
  border: 1rpx solid var(--card-border, #e2e8f0);
}
.search-input {
  height: 72rpx;
  box-sizing: border-box;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 36rpx;
  padding: 0 26rpx;
  font-size: 26rpx;
}
.filter-scroll {
  white-space: nowrap;
  margin-top: 14rpx;
}
.filter-row {
  display: flex;
  align-items: center;
  gap: 10rpx;
  flex-wrap: wrap;
}
.filter-row + .filter-row {
  margin-top: 12rpx;
}
.filter-chip {
  padding: 8rpx 20rpx;
  border-radius: 999rpx;
  font-size: 23rpx;
  color: #64748b;
  background: #f1f5f9;
  flex-shrink: 0;
}
.filter-chip.active {
  color: #fff;
  background: #0f766e;
  font-weight: 600;
}
.filter-picker {
  padding: 8rpx 20rpx;
  border-radius: 999rpx;
  font-size: 23rpx;
  color: #334155;
  background: #ecfdf5;
  border: 1rpx solid #99f6e4;
  flex-shrink: 0;
}
.filter-reset {
  margin-left: auto;
  padding: 8rpx 12rpx;
  font-size: 22rpx;
  color: #94a3b8;
  flex-shrink: 0;
}
.card {
  background: #fff;
  border-radius: var(--card-radius, 22rpx);
  padding: 24rpx;
  margin: 0 0 16rpx;
  border: 1rpx solid var(--card-border, #e2e8f0);
}
.card-hover {
  background: #f8fafc !important;
}
.card-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10rpx;
}
.card-id {
  font-size: 22rpx;
  color: #94a3b8;
}
.card-status {
  font-size: 22rpx;
  color: #92400e;
  background: #fef3c7;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
}
.card-status.PAID,
.card-status.COMPLETED {
  color: #166534;
  background: #dcfce7;
}
.card-status.REFUNDED,
.card-status.PARTIAL_REFUNDED {
  color: #1e40af;
  background: #dbeafe;
}
.card-status.DISPUTED {
  color: #9a3412;
  background: #ffedd5;
}
.card-status.CANCELLED,
.card-status.FAILED {
  color: #475569;
  background: #e2e8f0;
}
.card-main {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-top: 12rpx;
}
.card-thumb {
  width: 96rpx;
  height: 96rpx;
  border-radius: 18rpx;
  background: #ecfdf5;
  flex-shrink: 0;
}
.card-copy {
  flex: 1;
  min-width: 0;
}
.card-goods {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-amount {
  font-size: 36rpx;
  font-weight: 700;
  color: #0f172a;
  flex-shrink: 0;
}
.card-meta {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
.card-time {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
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
