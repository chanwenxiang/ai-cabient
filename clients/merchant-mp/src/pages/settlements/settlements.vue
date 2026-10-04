<template>
  <view class="page-root">
    <app-nav-bar title="结算对账" />
    <app-underline-tabs :items="rangeTabs" :value="rangePreset" @change="onRangeTab" />
    <view class="page-body">
      <view class="period-card">
        <view v-if="hasZeroRows" class="filter-aux">
          <view
            v-if="hasZeroRows"
            role="button"
            class="aux-link"
            :class="{ active: showZeroOrders }"
            :aria-label="showZeroOrders ? '隐藏零元单' : '显示零元单'"
            @click="showZeroOrders = !showZeroOrders"
          >
            <text>{{ showZeroOrders ? '隐藏零元单' : '零元单' }}</text>
          </view>
        </view>
        <view class="date-bar">
          <picker
            mode="date"
            :value="startDate"
            start="2020-01-01"
            :end="endDate"
            @change="onStartDate"
          >
            <view class="date-chip">
              <text class="date-text">{{ startDate }}</text>
            </view>
          </picker>
          <text class="date-sep">至</text>
          <picker
            mode="date"
            :value="endDate"
            :start="startDate"
            end="2035-12-31"
            @change="onEndDate"
          >
            <view class="date-chip">
              <text class="date-text">{{ endDate }}</text>
            </view>
          </picker>
        </view>
      </view>

      <view v-if="loadError" class="banner-err">
        <text>{{ loadError }}</text>
        <text role="button" aria-label="重试" class="banner-retry" @click="load">重试</text>
      </view>

      <view class="summary-card">
        <view class="hero">
          <text class="hero-kicker">所选区间 · 商户所得</text>
          <text class="hero-amount">¥{{ summary.merchantIncome }}</text>
          <view class="hero-eq">
            <text>区间营收 ¥{{ summary.gross }}</text>
            <text class="hero-eq-op">−</text>
            <text>平台抽成 ¥{{ summary.platformFee }}</text>
          </view>
        </view>
        <view class="kpi-grid">
          <view class="kpi-cell">
            <text class="kpi-label">待分账</text>
            <text class="kpi-value">¥{{ summary.pending }}</text>
          </view>
          <view class="kpi-cell">
            <text class="kpi-label">已结算</text>
            <text class="kpi-value">¥{{ summary.settled }}</text>
          </view>
          <view class="kpi-cell">
            <text class="kpi-label">客单价</text>
            <text class="kpi-value">¥{{ summary.avgOrder }}</text>
          </view>
        </view>
        <view v-if="Number(summary.failedOrders) > 0" class="fail-strip">
          <text>分账失败 {{ summary.failedOrders }} 笔，请到分账明细核对</text>
        </view>
      </view>

      <view class="section">
        <text class="section-title">按日汇总</text>
        <view v-if="loading && !daily.length" class="loading-inline">{{
          loadingLabel('结算数据')
        }}</view>
        <template v-else>
          <view v-for="d in visibleDaily" :key="d.date" class="ledger-row">
            <view class="ledger-head">
              <view class="ledger-title-wrap">
                <text class="ledger-title">{{ dayTitle(d.date) }}</text>
                <text class="ledger-sub">{{ d.orderCount }} 笔 · 客单 {{ dayAvgOrder(d) }}</text>
              </view>
              <text class="ledger-amount">{{ money(d.merchantCents) }}</text>
            </view>
            <view class="meta-row">
              <text class="meta-pill">抽成 {{ money(d.platformCents) }}</text>
              <text class="meta-pill">待分 {{ money(d.pendingCents) }}</text>
              <text class="meta-pill">已结 {{ money(d.settledCents) }}</text>
            </view>
            <text v-if="d.failedCount" class="device-fail">失败 {{ d.failedCount }} 笔</text>
          </view>
          <empty-state
            v-if="!visibleDaily.length"
            compact
            icon="/static/menu/settlements.png"
            title="所选日期暂无结算数据"
            hint="本月从当月 1 号算到今天。有金额的单若在上月底，请点「近7天」或改日期；要看 0 元订单点「零元单」"
          />
        </template>
      </view>

      <view class="section">
        <text class="section-title">结算批次</text>
        <view v-if="batchWarn" class="section-warn">{{ batchWarn }}</view>
        <view v-if="loading && !visibleBatches.length && !visibleDaily.length" class="loading-inline">{{
          loadingLabel('批次')
        }}</view>
        <template v-else>
          <view v-for="b in visibleBatches" :key="b.batchNo" class="ledger-row">
            <view class="ledger-head">
              <view class="ledger-title-wrap">
                <text class="ledger-title">{{ b.batchNo }}</text>
                <text class="ledger-sub"
                  >{{ batchStatusLabel(b.batchStatus) }} · {{ b.orderCount }} 笔</text
                >
              </view>
              <text class="ledger-amount">{{ money(b.merchantCents) }}</text>
            </view>
            <view class="meta-row">
              <text class="meta-pill">已结 {{ money(b.settledCents) }}</text>
              <text class="meta-pill">待分 {{ money(b.pendingCents) }}</text>
            </view>
            <text v-if="b.failedCount" class="device-fail">失败 {{ b.failedCount }} 笔</text>
            <text v-if="b.settleAfter || b.settledAt" class="ledger-time">{{
              b.settledAt
                ? `入账 ${formatBatchTime(b.settledAt)}`
                : `计划 ${formatBatchTime(b.settleAfter)}`
            }}</text>
          </view>
          <empty-state
            v-if="!visibleBatches.length"
            compact
            icon="/static/menu/orders.png"
            title="暂无结算批次"
            hint="平台定期提交分账后，批次会显示在这里"
          />
        </template>
      </view>

      <view v-if="canExport" class="actions">
        <app-button variant="outline" label="导出对账单" @click="onExport" />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { computed, ref } from 'vue';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import { displayLabel } from '@aicabinet/shared-dict';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import {
  hasPerm,
  merchantApi,
  downloadAuthedFile,
  openExportedFile,
  isMerchantLoggedIn
} from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { isSettlementBatchTerminal, useAutoRefresh } from '@/composables/use-auto-refresh';
import type { MerchantDailySettlement, MerchantSettlementBatch } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canViewSettlements = computed(() => hasPerm(me.value, 'merchant:settlements:view'));
const canExport = computed(() => hasPerm(me.value, 'merchant:settlements:export'));

function localDateISO(d: Date) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function batchStatusLabel(status?: string) {
  return displayLabel('settlement_batch_status', status, '未知状态');
}

function isZeroMoneyRow(row: { grossCents?: number | null; merchantCents?: number | null }) {
  return Number(row.grossCents || 0) === 0 && Number(row.merchantCents || 0) === 0;
}

/** 分金额展示；缺省/非法显示 ¥0.00，避免模板 NaN */
function money(cents?: number | null) {
  const n = Number(cents);
  return fmtMoney(Number.isFinite(n) ? n : 0);
}

/** YYYY-MM-DD → 本地日起点时间戳，避免字符串字典序比较（M-26） */
function settlementDayMs(isoDate: string): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(isoDate || '').trim());
  if (!m) return Number.NaN;
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getTime();
}

function isSettlementRangeInvalid(start: string, end: string): boolean {
  const a = settlementDayMs(start);
  const b = settlementDayMs(end);
  if (!Number.isFinite(a) || !Number.isFinite(b)) return true;
  return a > b;
}

function formatBatchTime(iso?: string) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 10);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

const WEEKDAY = ['日', '一', '二', '三', '四', '五', '六'] as const;

function dayTitle(iso?: string) {
  const ms = settlementDayMs(String(iso || ''));
  if (!Number.isFinite(ms)) return iso || '';
  const d = new Date(ms);
  const week = WEEKDAY[d.getDay()] ?? '';
  const thisYear = new Date().getFullYear();
  const prefix =
    d.getFullYear() === thisYear
      ? `${d.getMonth() + 1}月${d.getDate()}日`
      : `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
  return week ? `${prefix} 周${week}` : prefix;
}

function dayAvgOrder(d: MerchantDailySettlement) {
  const count = Number(d.orderCount || 0);
  if (count <= 0) return money(0);
  return money(Math.round(Number(d.grossCents ?? 0) / count));
}

function monthStartISO(d = new Date()) {
  return localDateISO(new Date(d.getFullYear(), d.getMonth(), 1));
}

const today = localDateISO(new Date());
const sevenDaysAgo = localDateISO(new Date(Date.now() - 7 * 86400000));

const startDate = ref(sevenDaysAgo);
const endDate = ref(today);
const daily = ref<MerchantDailySettlement[]>([]);
const batches = ref<MerchantSettlementBatch[]>([]);
const showZeroOrders = ref(false);
const hasZeroRows = computed(
  () => daily.value.some(isZeroMoneyRow) || batches.value.some(isZeroMoneyRow)
);
const visibleDaily = computed(() =>
  showZeroOrders.value ? daily.value : daily.value.filter((d) => !isZeroMoneyRow(d))
);
const visibleBatches = computed(() =>
  showZeroOrders.value ? batches.value : batches.value.filter((b) => !isZeroMoneyRow(b))
);

function summarizeDays(days: MerchantDailySettlement[]) {
  const gross = days.reduce((s, d) => s + (d.grossCents || 0), 0);
  const platform = days.reduce((s, d) => s + (d.platformCents || 0), 0);
  const merchant = days.reduce((s, d) => s + (d.merchantCents || 0), 0);
  const pending = days.reduce((s, d) => s + (d.pendingCents || 0), 0);
  const settled = days.reduce((s, d) => s + (d.settledCents || 0), 0);
  const orders = days.reduce((s, d) => s + (d.orderCount || 0), 0);
  const failed = days.reduce((s, d) => s + (d.failedCount || 0), 0);
  return {
    gross: money(gross).replace(/^¥/, ''),
    platformFee: money(platform).replace(/^¥/, ''),
    merchantIncome: money(merchant).replace(/^¥/, ''),
    pending: money(pending).replace(/^¥/, ''),
    settled: money(settled).replace(/^¥/, ''),
    avgOrder: orders > 0 ? money(Math.round(gross / orders)).replace(/^¥/, '') : '0.00',
    failedOrders: String(failed)
  };
}

const summary = computed(() => summarizeDays(visibleDaily.value));
const loading = ref(false);
const loadError = ref('');
const batchWarn = ref('');
let loadSeq = 0;

function onStartDate(e: unknown) {
  const ev = e as { detail?: { value?: string }; target?: { value?: string } };
  const v = String(ev?.detail?.value ?? ev?.target?.value ?? '').trim();
  if (v) startDate.value = v;
  void load();
}

function onEndDate(e: unknown) {
  const ev = e as { detail?: { value?: string }; target?: { value?: string } };
  const v = String(ev?.detail?.value ?? ev?.target?.value ?? '').trim();
  if (v) endDate.value = v;
  void load();
}

function applyRangePreset(kind: '7d' | 'month') {
  endDate.value = localDateISO(new Date());
  startDate.value =
    kind === 'month' ? monthStartISO() : localDateISO(new Date(Date.now() - 7 * 86400000));
  void load();
}

const rangeTabs = [
  { key: '7d', label: '近7天' },
  { key: 'month', label: '本月' }
];

function onRangeTab(key: string) {
  if (key === '7d' || key === 'month') applyRangePreset(key);
}

const rangePreset = computed(() => {
  const todayIso = localDateISO(new Date());
  if (endDate.value !== todayIso) return '';
  if (startDate.value === localDateISO(new Date(Date.now() - 7 * 86400000))) return '7d';
  if (startDate.value === monthStartISO()) return 'month';
  return '';
});

onShow(() => load());
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

/**
 * 批次未到终态时每 15 秒静默跟进一次（结算按天聚合，比订单慢，不必 3 秒）。
 * 终态集合放在共享原语里，并由单测断言「与 shared-dict 的 settlement_batch_status 双向全覆盖」——
 * 字典新增状态时测试会红，逼人显式判断它算不算终态，而不是静默漏轮询。
 * 日期非法时 load() 会短路，这里也一并挡住，避免空转。
 */
useAutoRefresh({
  intervalMs: 15_000,
  load,
  shouldContinue: () => batches.value.some((b) => !isSettlementBatchTerminal(b.batchStatus)),
  maxDurationMs: 300_000,
  canRefresh: () => !loading.value && !isSettlementRangeInvalid(startDate.value, endDate.value)
});

function applyInvalidSettlementRange() {
  loadError.value = '开始日期不能晚于结束日期';
  daily.value = [];
  batches.value = [];
  loading.value = false;
}

function applySettlementResponses(
  daysRes: PromiseSettledResult<MerchantDailySettlement[]>,
  batchRes: PromiseSettledResult<MerchantSettlementBatch[]>
) {
  if (daysRes.status === 'rejected') {
    throw daysRes.reason instanceof Error ? daysRes.reason : new Error('结算数据加载失败');
  }

  const days = daysRes.value || [];
  daily.value = days;

  if (batchRes.status === 'fulfilled') {
    batches.value = batchRes.value || [];
  } else {
    batches.value = [];
    batchWarn.value =
      batchRes.reason instanceof Error ? batchRes.reason.message : '结算批次加载失败';
  }
}

async function load() {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  // 日期非法时先短路，避免无意义的 refreshMe / 接口等待
  if (isSettlementRangeInvalid(startDate.value, endDate.value)) {
    applyInvalidSettlementRange();
    return;
  }
  const seq = ++loadSeq;
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return;
    seedMerchantMeDisplayCache(me);
  }
  if (seq !== loadSeq) return;
  if (!canViewSettlements.value) {
    showError('无结算权限');
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  // 已有日汇总时静默刷新，避免日期区/摘要先空再撑开
  if (!daily.value.length) loading.value = true;
  loadError.value = '';
  batchWarn.value = '';
  try {
    const [daysRes, batchRes] = await Promise.allSettled([
      merchantApi.dailySettlements(startDate.value, endDate.value),
      merchantApi.settlementBatches(startDate.value, endDate.value)
    ]);
    if (seq !== loadSeq) return;
    applySettlementResponses(daysRes, batchRes);
  } catch (e: unknown) {
    if (seq !== loadSeq) return;
    loadError.value = e instanceof Error ? e.message : '加载失败';
    showError(loadError.value);
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function onExport() {
  if (!canExport.value) {
    showError('无导出权限');
    return;
  }
  if (isSettlementRangeInvalid(startDate.value, endDate.value)) {
    showError('开始日期不能晚于结束日期');
    return;
  }
  const url = merchantApi.exportSettlementsUrl(startDate.value, endDate.value);
  downloadAuthedFile(url)
    .then(async (tempFilePath) => {
      await openExportedFile(tempFilePath, `settlements-${startDate.value}-${endDate.value}.xlsx`);
      showSuccess('导出成功');
    })
    .catch((e) => {
      showError(e instanceof Error ? e.message : '导出失败');
    });
}
</script>

<style scoped>
.page-root {
  padding: 0;
  background: var(--page-bg, #ededed);
  min-height: 100vh;
}
.period-card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 16rpx 20rpx 18rpx;
  margin-bottom: 20rpx;
}
.date-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
  overflow: hidden;
  max-height: 96rpx;
  position: relative;
  z-index: 2;
}
.date-chip {
  min-width: 220rpx;
  height: 56rpx;
  padding: 0 16rpx;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--color-border);
  border-radius: var(--radius-control);
}
.date-text {
  font-size: var(--font-size-md);
  color: var(--brand);
  font-weight: 500;
}
.date-sep {
  color: var(--text-subtle);
  flex-shrink: 0;
}
.filter-aux {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12rpx;
}
.aux-link {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.aux-link.active {
  color: var(--brand);
  font-weight: 600;
}
.summary-card {
  background: var(--card-bg, #fff);
  border: none;
  border-radius: var(--radius-panel);
  padding: 28rpx 30rpx 24rpx;
  margin-bottom: 20rpx;
}
.hero {
  text-align: center;
  padding-bottom: 24rpx;
  margin-bottom: 20rpx;
  border-bottom: 1rpx solid var(--color-border);
}
.hero-kicker {
  display: block;
  color: var(--text-muted);
  font-size: var(--font-size-caption);
}
.hero-amount {
  display: block;
  color: var(--brand-deep);
  font-size: 56rpx;
  font-weight: 700;
  line-height: 1.2;
  margin: 6rpx 0 10rpx;
  font-variant-numeric: tabular-nums;
}
.hero-eq {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.hero-eq-op {
  color: var(--text-subtle);
}
.kpi-grid {
  display: flex;
  gap: 12rpx;
}
.kpi-cell {
  flex: 1;
  min-width: 0;
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--color-border);
  border-radius: var(--radius-tag);
  padding: 14rpx 12rpx;
  box-sizing: border-box;
}
.kpi-label {
  display: block;
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}
.kpi-value {
  display: block;
  color: var(--text-primary);
  font-size: var(--font-size-md);
  font-weight: 650;
  margin-top: 4rpx;
  font-variant-numeric: tabular-nums;
}
.fail-strip {
  margin-top: 16rpx;
  padding: 12rpx 14rpx;
  border-radius: var(--radius-tag);
  background: var(--danger-soft);
  color: var(--color-danger);
  font-size: var(--font-size-sm);
}
.banner-err {
  margin-bottom: 16rpx;
  padding: 16rpx 20rpx;
  border-radius: var(--radius-control);
  background: var(--danger-soft);
  color: var(--color-danger);
  font-size: var(--font-size-caption);
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.banner-retry {
  color: var(--brand);
  font-weight: 600;
}
.section-warn {
  margin-bottom: 12rpx;
  padding: 12rpx 16rpx;
  border-radius: var(--radius-tag);
  background: var(--warning-soft);
  color: var(--accent-orange);
  font-size: var(--font-size-sm);
}
.section {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 24rpx;
  margin-bottom: 20rpx;
}
.section-title {
  font-size: var(--font-size-md);
  font-weight: 600;
  margin-bottom: 8rpx;
  display: block;
}
.ledger-row {
  padding: 18rpx 0;
  border-bottom: 1rpx solid var(--color-border);
}
.ledger-row:last-child {
  border-bottom: none;
  padding-bottom: 4rpx;
}
.ledger-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16rpx;
}
.ledger-title-wrap {
  min-width: 0;
  flex: 1;
}
.ledger-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary);
}
.ledger-sub {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.ledger-amount {
  flex-shrink: 0;
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  margin-left: auto;
}
.ledger-time {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-top: 12rpx;
}
.meta-pill {
  font-size: var(--font-size-xs);
  color: var(--text-muted);
  background: #f7f7f7;
  border-radius: var(--radius-pill);
  padding: 4rpx 12rpx;
}
.device-fail {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--color-danger);
  font-weight: 600;
}
.loading-inline {
  font-size: var(--font-size-caption);
  color: var(--text-subtle);
  padding: 24rpx 0;
  text-align: center;
}
.actions {
  padding: 20rpx 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
