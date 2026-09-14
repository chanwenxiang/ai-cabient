<template>
  <view class="page">
    <app-nav-bar title="分账明细" />
    <view class="page-body">
      <view class="tabs">
        <text
          role="button"
          class="tab"
          :class="{ active: tab === 'FAILED' }"
          @click="switchTab('FAILED')"
          >失败</text
        >
        <text role="button" class="tab" :class="{ active: tab === 'ALL' }" @click="switchTab('ALL')"
          >全部</text
        >
      </view>

      <view v-if="focusOrderId" class="focus-banner">
        <text class="focus-text">已定位订单 {{ displayBizNo(focusOrderId) }}</text>
        <text role="button" class="focus-clear" @click="clearFocusOrder">清除</text>
      </view>

      <view v-if="loading && !list.length" class="card state">{{ UI_COPY.loading }}</view>
      <error-state v-else-if="error && !list.length" :title="error" @retry="retryLoad" />
      <empty-state
        v-else-if="!list.length"
        icon="/static/menu/splits.png"
        :title="emptyTitle"
        :hint="emptyHint"
      />
      <view v-else>
        <view
          v-for="s in list"
          :key="s.splitId"
          class="card item"
          :class="{ focus: focusOrderId && String(s.orderId) === focusOrderId }"
        >
          <view class="head">
            <text class="tag" :class="statusClass(s.status)">{{ statusLabel(s.status) }}</text>
            <text class="time">{{ formatTime(s.createdAt) }}</text>
          </view>
          <text class="title">订单 {{ displayBizNo(s.orderId) }}</text>
          <text class="meta"
            >柜机 {{ emptyDisplay(s.deviceName || s.deviceId, 'device') }} · 商户所得 ¥{{
              money(s.merchantCents)
            }}</text
          >
          <text class="meta"
            >毛额 ¥{{ money(s.grossCents) }} · 平台 ¥{{ money(s.platformCents) }}</text
          >
          <text v-if="s.wechatOutOrderNo" class="meta">外部单 {{ s.wechatOutOrderNo }}</text>
          <text v-if="s.failureReason" class="fail">失败原因：{{ s.failureReason }}</text>
        </view>
        <view
          v-if="hasMore && !focusOrderId"
          class="load-more"
          role="button"
          @click="loadMore"
          >{{ loadingMore ? UI_COPY.loading : loadMoreLabel }}</view
        >
        <text v-else-if="list.length && !focusOrderId" class="trunc-hint">{{ doneHint }}</text>
      </view>
    </view></view
  >
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { showError } from '@/utils/notify';
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app';
import { hasPerm, merchantApi, isMerchantLoggedIn } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { displayLabel } from '@aicabinet/shared-dict';
import { emptyDisplay, displayBizNo, formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import type { RevenueSplit } from '@aicabinet/shared-types';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

/** M-P2-6：分页尺寸；与订单列表同量级，禁止一次拉 100。 */
const PAGE_SIZE = 20;
const FOCUS_SCAN_MAX_PAGES = 10;

const { me, refresh: refreshMe } = useMerchantMe();
const loading = ref(true);
const loadingMore = ref(false);
const error = ref('');
const tab = ref<'FAILED' | 'ALL'>('FAILED');
const list = ref<RevenueSplit[]>([]);
const pageIndex = ref(0);
const hasMore = ref(false);
const listTotal = ref(0);
/** M-P2-2：消息深链携带的订单号，用于置顶高亮。 */
const focusOrderId = ref('');

let loadSeq = 0;

const emptyTitle = computed(() => {
  if (focusOrderId.value) return '未找到该订单的分账';
  return tab.value === 'FAILED' ? '暂无分账异常' : '暂无分账记录';
});
const emptyHint = computed(() => {
  if (focusOrderId.value) return '可清除定位后查看全部，或核对订单是否已分账';
  return '订单分账后会出现在这里；失败单请核对微信收款账户';
});
const loadMoreLabel = computed(() => {
  if (listTotal.value > 0) {
    return `加载更多（已显示 ${list.value.length}/${listTotal.value}）`;
  }
  return '加载更多';
});
const doneHint = computed(() => {
  if (listTotal.value > 0) return `共 ${listTotal.value} 条，已全部加载`;
  return `已显示 ${list.value.length} 条`;
});

onLoad((query) => {
  const status = String(query?.status || '').toUpperCase();
  if (status === 'ALL') tab.value = 'ALL';
  else tab.value = 'FAILED';
  focusOrderId.value = String(query?.orderId || query?.id || '').trim();
});

onShow(() => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  void load(true);
});

onPullDownRefresh(async () => {
  try {
    await load(true);
  } finally {
    uni.stopPullDownRefresh();
  }
});

onReachBottom(() => {
  if (!focusOrderId.value) void loadMore();
});

function switchTab(next: 'FAILED' | 'ALL') {
  if (tab.value === next) return;
  tab.value = next;
  void load(true);
}

function clearFocusOrder() {
  focusOrderId.value = '';
  void load(true);
}

function retryLoad() {
  void load(true);
}

function money(cents = 0) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '暂无');
}

function statusLabel(status?: string) {
  return displayLabel('split_status', status, '未知状态');
}

function statusClass(status?: string) {
  const s = String(status || '').toUpperCase();
  if (s === 'WECHAT_FAILED' || s === 'FAILED') return 'fail';
  if (s === 'SUCCESS' || s === 'SETTLED') return 'ok';
  if (s === 'LEDGER_ONLY') return 'warn';
  return 'warn';
}

function applyFocusOrder(rows: RevenueSplit[]): RevenueSplit[] {
  const oid = focusOrderId.value;
  if (!oid) return rows;
  const matched = rows.filter((x) => String(x.orderId || '') === oid);
  if (matched.length) {
    const rest = rows.filter((x) => String(x.orderId || '') !== oid);
    return [...matched, ...rest];
  }
  return [];
}

function mergeFailedPages(
  aItems: RevenueSplit[],
  bItems: RevenueSplit[],
  existingIds?: Set<string>
): RevenueSplit[] {
  const seen = existingIds ? new Set(existingIds) : new Set<string>();
  const out: RevenueSplit[] = [];
  for (const x of [...aItems, ...bItems]) {
    if (!x?.splitId || seen.has(x.splitId)) continue;
    seen.add(x.splitId);
    out.push(x);
  }
  out.sort((x, y) => String(y.createdAt || '').localeCompare(String(x.createdAt || '')));
  return out;
}

async function fetchPage(page: number): Promise<{
  rows: RevenueSplit[];
  total: number;
  pageFull: boolean;
}> {
  if (tab.value === 'ALL') {
    const res = await merchantApi.revenueSplits(page, PAGE_SIZE);
    const rows = res?.items || [];
    const total = Number(res?.total ?? rows.length);
    return { rows, total, pageFull: rows.length >= PAGE_SIZE };
  }
  const [a, b] = await Promise.all([
    merchantApi.revenueSplits(page, PAGE_SIZE, 'WECHAT_FAILED'),
    merchantApi.revenueSplits(page, PAGE_SIZE, 'FAILED')
  ]);
  const aItems = a?.items || [];
  const bItems = b?.items || [];
  const rows = mergeFailedPages(aItems, bItems);
  const total = Number(a?.total || 0) + Number(b?.total || 0);
  const pageFull = aItems.length >= PAGE_SIZE || bItems.length >= PAGE_SIZE;
  return { rows, total, pageFull };
}

/** 深链：在多页中扫描目标订单（失败 Tab 未命中则切全部）。 */
async function loadFocused(seq: number): Promise<void> {
  const oid = focusOrderId.value;
  let collected: RevenueSplit[] = [];
  let mode: 'FAILED' | 'ALL' = tab.value;

  const scan = async (which: 'FAILED' | 'ALL') => {
    tab.value = which;
    collected = [];
    for (let p = 0; p < FOCUS_SCAN_MAX_PAGES; p++) {
      const { rows, pageFull } = await fetchPage(p);
      if (seq !== loadSeq) return false;
      const seen = new Set(collected.map((x) => x.splitId!).filter(Boolean));
      const extra = rows.filter((x) => x.splitId && !seen.has(x.splitId));
      collected = collected.concat(extra);
      if (collected.some((x) => String(x.orderId || '') === oid)) return true;
      if (!pageFull) break;
    }
    return collected.some((x) => String(x.orderId || '') === oid);
  };

  let hit = await scan(mode);
  if (!hit && mode === 'FAILED') {
    hit = await scan('ALL');
  }
  if (seq !== loadSeq) return;
  list.value = applyFocusOrder(collected);
  pageIndex.value = 0;
  hasMore.value = false;
  listTotal.value = list.value.length;
  if (!hit) {
    list.value = [];
  }
}

async function load(reset: boolean) {
  const seq = ++loadSeq;
  if (reset) {
    pageIndex.value = 0;
    hasMore.value = false;
    listTotal.value = 0;
    if (!list.value.length) loading.value = true;
  }
  error.value = '';
  try {
    await refreshMe();
    if (!hasPerm(me.value, 'merchant:splits:list')) {
      if (seq !== loadSeq) return;
      error.value = '无分账明细权限';
      list.value = [];
      hasMore.value = false;
      return;
    }
    if (focusOrderId.value) {
      await loadFocused(seq);
      return;
    }
    const { rows, total, pageFull } = await fetchPage(0);
    if (seq !== loadSeq) return;
    list.value = rows;
    pageIndex.value = 0;
    listTotal.value = total;
    hasMore.value = pageFull && (total <= 0 || rows.length < total);
  } catch (e) {
    if (seq !== loadSeq) return;
    if (!isMerchantLoggedIn()) return;
    seedMerchantMeDisplayCache(me);
    list.value = [];
    hasMore.value = false;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value || loading.value || focusOrderId.value) return;
  const seq = ++loadSeq;
  loadingMore.value = true;
  try {
    const next = pageIndex.value + 1;
    const { rows, total, pageFull } = await fetchPage(next);
    if (seq !== loadSeq) return;
    if (!rows.length) {
      hasMore.value = false;
      return;
    }
    const seen = new Set(list.value.map((x) => x.splitId).filter(Boolean) as string[]);
    const appended =
      tab.value === 'FAILED'
        ? mergeFailedPages(rows, [], seen)
        : rows.filter((x) => x.splitId && !seen.has(x.splitId));
    list.value = list.value.concat(appended);
    pageIndex.value = next;
    if (total > 0) listTotal.value = total;
    hasMore.value =
      pageFull && appended.length > 0 && (listTotal.value <= 0 || list.value.length < listTotal.value);
  } catch (e) {
    if (seq !== loadSeq) return;
    showError(e instanceof Error ? e.message : '加载失败');
  } finally {
    if (seq === loadSeq) loadingMore.value = false;
  }
}
</script>

<style scoped>
.page {
  padding: 0;
  min-height: 100vh;
  box-sizing: border-box;
}
.tabs {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.tab {
  padding: 12rpx 28rpx;
  border-radius: var(--radius-pill);
  background: var(--card-bg, #fff);
  color: var(--text-muted);
  font-size: var(--font-size-body);
  border: 1rpx solid var(--color-border);
}
.tab.active {
  background: var(--brand);
  color: var(--white);
  border-color: var(--brand);
  font-weight: 650;
}
.focus-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-bottom: 16rpx;
  padding: 16rpx 20rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--brand);
}
.focus-text {
  flex: 1;
  font-size: var(--font-size-caption);
  color: var(--brand);
  font-weight: 600;
}
.focus-clear {
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  padding: 4rpx 8rpx;
}
.card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  padding: 28rpx;
  margin-bottom: 16rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
  border: 2rpx solid transparent;
}
.card.item.focus {
  border-color: var(--brand);
}
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  color: var(--text-muted);
}
.head {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 10rpx;
}
.tag {
  font-size: var(--font-size-sm);
  line-height: 1;
  padding: 8rpx 12rpx;
  border-radius: var(--radius-pill);
  font-weight: 600;
}
.tag.fail {
  color: var(--color-danger);
  background: rgba(185, 28, 28, 0.12);
}
.tag.ok {
  color: var(--brand);
  background: var(--brand-soft, #d1fae5);
}
.tag.warn {
  color: var(--warning, #b45309);
  background: rgba(180, 83, 9, 0.14);
}
.time {
  margin-left: auto;
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
}
.title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 650;
  color: var(--brand-deep);
}
.meta {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
}
.fail {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-caption);
  color: var(--color-danger);
  line-height: 1.5;
}
.load-more {
  text-align: center;
  padding: 24rpx 16rpx;
  color: var(--brand);
  font-size: var(--font-size-caption);
  font-weight: 600;
}
.trunc-hint {
  display: block;
  text-align: center;
  padding: 16rpx;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.page-body {
  padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
