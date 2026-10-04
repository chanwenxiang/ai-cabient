<template>
  <view class="page">
    <app-nav-bar title="经营分析" />
    <app-underline-tabs :items="periodTabs" :value="String(days)" @change="onPeriodTab" />
    <view class="page-body">
      <view v-if="cabinetPickerLabels.length" class="period-card">
        <picker
          v-if="cabinetPickerLabels.length"
          mode="selector"
          :range="cabinetPickerLabels"
          :value="cabinetPickerIndex"
          @change="onCabinetPick"
        >
          <view class="cabinet-pick">
            <text class="cabinet-pick-label">当前货柜</text>
            <text class="cabinet-pick-name">{{ selectedCabinetName }}</text>
          </view>
        </picker>
      </view>
      <view v-if="loading && !analytics.topSkus?.length" class="state">正在汇总经营数据…</view>
      <error-state
        v-else-if="error && !analytics.topSkus?.length"
        :title="error"
        @retry="() => load()"
      />
      <template v-else>
        <view class="summary-card">
          <view class="hero">
            <text class="hero-kicker">近{{ days }}天</text>
            <text class="hero-range">{{ periodRangeLabel }}</text>
            <text class="hero-amount">{{ money(analytics.grossMarginCents) }}</text>
            <view class="hero-eq">
              <text>营收 {{ money(analytics.revenueCents) }}</text>
              <text class="hero-eq-op">−</text>
              <text>成本 {{ money(analytics.cogsCents) }}</text>
            </view>
            <view class="hero-meta">
              <text>毛利率 {{ marginRate }}</text>
              <text>成交 {{ analytics.orderCount || 0 }} 单 · {{ analytics.itemQtySold || 0 }} 件</text>
            </view>
            <view class="hero-meta">
              <text>客单 {{ money(analytics.avgOrderValueCents) }}</text>
              <text>件均 {{ money(analytics.avgUnitPriceCents) }}</text>
            </view>
            <view v-if="hasRevenueChange || hasMarginChange" class="hero-meta">
              <text v-if="hasRevenueChange" :class="changeClass(analytics.revenueChangePct)"
                >营收环比 {{ formatChange(analytics.revenueChangePct) }}</text
              >
              <text v-if="hasMarginChange" :class="changeClass(analytics.marginChangePct)"
                >毛利环比 {{ formatChange(analytics.marginChangePct) }}</text
              >
            </view>
          </view>
          <view class="kpi-grid">
            <view class="kpi-cell">
              <view class="kpi-label-row">
                <text class="kpi-label">全店待结算</text>
                <text class="help-q" role="button" aria-label="待结算说明" @click="explainSettlement"
                  >?</text
                >
              </view>
              <text class="kpi-value warn">{{ money(settlement.pendingAmountCents) }}</text>
            </view>
            <view class="kpi-cell">
              <view class="kpi-label-row">
                <text class="kpi-label">全店本月已结</text>
                <text class="help-q" role="button" aria-label="已结说明" @click="explainSettlement"
                  >?</text
                >
              </view>
              <text class="kpi-value">{{ money(settlement.settledMonthCents) }}</text>
            </view>
          </view>
          <view v-if="(analytics.stockoutSkuCount || 0) > 0" class="warn-strip">
            <text
              >缺货 {{ analytics.stockoutSkuCount }} 种 · 估损
              {{ money(analytics.stockoutLossEstimateCents) }}</text
            >
          </view>
        </view>
        <view class="card">
          <view class="section-head"
            ><text class="section-title">商品经营表现</text
            ><text class="section-sub">本柜在售 · 按销售额</text></view
          >
          <view v-for="sku in analytics.topSkus || []" :key="sku.skuId" class="sku-row">
            <view class="sku-main">
              <text class="sku-name">{{ skuTitleWithQty(sku.skuName, sku.qtySold) }}</text>
              <text class="sku-rec"
                >毛利 {{ money(sku.grossMarginCents) }} · 毛利率 {{ skuMarginRate(sku) }} · 件均
                {{ money(skuUnitPrice(sku)) }}</text
              >
            </view>
            <view class="sku-data">
              <text class="sku-money">{{ money(sku.revenueCents) }}</text>
            </view>
          </view>
          <view v-if="!analytics.topSkus?.length" class="empty">本柜在售商品该区间暂无成交</view>
        </view>
        <view v-if="insightRows.length" class="card">
          <view class="section-head">
            <view class="title-with-help">
              <text class="section-title">AI 经营洞察</text>
              <text class="help-q" role="button" aria-label="洞察说明" @click="explainInsight"
                >?</text
              >
            </view>
            <text class="section-sub">{{ formatInsightTime(aiInsight?.generatedAt) }}</text>
          </view>
          <view v-for="p in insightRows" :key="p.skuId" class="insight-sku">
            <text class="sku-name">{{ p.skuName }}</text>
            <text class="meta"
              >{{ performanceLabel(p.performanceLevel) }} · {{ p.recommendation || '' }}</text
            >
          </view>
        </view>
        <view
          v-if="
            expirySummary &&
            (expirySummary.openPullOffTasks > 0 || expirySummary.writeOffQty30d > 0)
          "
          class="card"
        >
          <view class="section-head"
            ><text class="section-title">临期摘要</text
            ><text class="section-sub">近 30 天</text></view
          >
          <view class="expiry-grid">
            <view class="expiry-cell"
              ><text class="expiry-n">{{ expirySummary.openPullOffTasks }}</text
              ><text class="expiry-l">待下架任务</text></view
            >
            <view class="expiry-cell"
              ><text class="expiry-n">{{ expirySummary.writeOffQty30d }}</text
              ><text class="expiry-l">报损件数</text></view
            >
            <view class="expiry-cell"
              ><text class="expiry-n">{{ fmtMoney(expirySummary.writeOffCostCents30d) }}</text
              ><text class="expiry-l">报损成本</text></view
            >
          </view>
        </view>
        <view
          v-if="settlement.failedSplitCount"
          role="button"
          class="risk-card"
          @click="goFailedSplits"
        >
          <text class="risk-title">有 {{ settlement.failedSplitCount }} 笔分账异常</text>
          <text class="risk-desc app-link-chevron">点此查看失败原因与订单明细</text>
        </view>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import { showError } from '@/utils/notify';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import {
  changeClass,
  formatChange,
  formatInsightTime,
  insightHelpText,
  marginRatePercent,
  performanceLabel,
  formatPeriodRangeLabel,
  settlementHelpText,
  skuMarginRate,
  skuTitleWithQty,
  skuUnitPrice
} from '@/utils/business-display';
import {
  BUSINESS_BUNDLE_HARD_FAIL_MESSAGE,
  businessLoadErrorMessage,
  coalesceBusinessBundle,
  isStaleBusinessLoad,
  shouldShowBusinessFullLoading
} from '@/utils/business-load';
import {
  isMerchantLoggedIn,
  hasPerm,
  merchantApi,
  softFallback
} from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import type {
  MerchantAnalyticsOverview,
  MerchantSettlementOverview,
  MerchantAiInsight,
  MerchantExpirySummary,
  OpenApiMerchantDeviceReportDto
} from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canViewBusiness = computed(
  () => hasPerm(me.value, 'merchant:reports:view') || hasPerm(me.value, 'merchant:analytics:view')
);

const periods = [7, 30, 90];
const periodTabs = periods.map((d) => ({ key: String(d), label: `近${d}天` }));
const days = ref(30);
const loading = ref(true);
const error = ref('');
let loadSeq = 0;
const analytics = ref<MerchantAnalyticsOverview>({
  days: 30,
  revenueCents: 0,
  cogsCents: 0,
  grossMarginCents: 0,
  writeOffCostCents: 0,
  topSkus: [],
  orderCount: 0,
  avgOrderValueCents: 0,
  itemQtySold: 0,
  avgUnitPriceCents: 0,
  prevRevenueCents: 0,
  prevGrossMarginCents: 0,
  revenueChangePct: null,
  marginChangePct: null,
  stockoutSkuCount: 0,
  stockoutLossEstimateCents: 0
});
const settlement = ref<MerchantSettlementOverview>({
  pendingAmountCents: 0,
  pendingSplitCount: 0,
  settledMonthCents: 0,
  failedSplitCount: 0
});
const aiInsight = ref<MerchantAiInsight | null>(null);
const expirySummary = ref<MerchantExpirySummary | null>(null);
const deviceReports = ref<OpenApiMerchantDeviceReportDto[]>([]);
const selectedDeviceId = ref('');
const marginRate = computed(() =>
  marginRatePercent(analytics.value.revenueCents, analytics.value.grossMarginCents)
);
const hasRevenueChange = computed(() => formatChange(analytics.value.revenueChangePct) !== '暂无');
const hasMarginChange = computed(() => formatChange(analytics.value.marginChangePct) !== '暂无');
const periodRangeLabel = computed(() => formatPeriodRangeLabel(days.value));
const insightRows = computed(() => aiInsight.value?.skuPerformance || []);
const cabinetOptions = computed(() =>
  (deviceReports.value || [])
    .filter((d) => d.deviceId)
    .map((d) => ({ id: String(d.deviceId), name: d.deviceName || d.deviceId || '' }))
);
const cabinetPickerLabels = computed(() => cabinetOptions.value.map((c) => c.name));
const cabinetPickerIndex = computed(() => {
  const i = cabinetOptions.value.findIndex((c) => c.id === selectedDeviceId.value);
  return i < 0 ? 0 : i;
});
const selectedCabinetName = computed(() => {
  const hit = cabinetOptions.value.find((c) => c.id === selectedDeviceId.value);
  return hit?.name || '请选择货柜';
});
const money = (cents = 0) => fmtMoney(cents);

function pickDefaultCabinet() {
  if (selectedDeviceId.value && cabinetOptions.value.some((c) => c.id === selectedDeviceId.value)) {
    return;
  }
  const ranked = [...(deviceReports.value || [])]
    .filter((d) => d.deviceId)
    .sort((a, b) => {
      const rev = Number(b.revenueTodayCents || 0) - Number(a.revenueTodayCents || 0);
      if (rev) return rev;
      return Number(b.orderToday || 0) - Number(a.orderToday || 0);
    });
  selectedDeviceId.value = ranked[0]?.deviceId || '';
}

function onCabinetPick(e: { detail?: { value?: string | number } }) {
  const i = Number(e.detail?.value);
  const next = cabinetOptions.value[i]?.id || '';
  if (!next || next === selectedDeviceId.value) return;
  selectedDeviceId.value = next;
  void load(true);
}

async function ensureAccess() {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return false;
  }
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return false;
    seedMerchantMeDisplayCache(me);
  }
  if (!canViewBusiness.value) {
    loading.value = false;
    showError('无经营分析权限');
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return false;
  }
  return true;
}

async function load(soft = false) {
  const seq = ++loadSeq;
  if (!(await ensureAccess())) {
    if (!isStaleBusinessLoad(seq, loadSeq)) loading.value = false;
    return;
  }
  if (isStaleBusinessLoad(seq, loadSeq)) return;
  if (shouldShowBusinessFullLoading(soft, analytics.value.topSkus?.length || 0)) {
    loading.value = true;
  }
  error.value = '';
  try {
    const reports = await softFallback(
      merchantApi.deviceReports(),
      [] as OpenApiMerchantDeviceReportDto[],
      '货柜列表'
    );
    if (isStaleBusinessLoad(seq, loadSeq)) return;
    deviceReports.value = reports || [];
    pickDefaultCabinet();
    const deviceId = selectedDeviceId.value || undefined;
    const [a, s, ai, ex] = await Promise.all([
      softFallback(merchantApi.analytics(days.value, deviceId), null, '经营分析'),
      softFallback(merchantApi.settlements(), null, '结算'),
      softFallback(merchantApi.aiInsight(days.value, deviceId), null, 'AI洞察'),
      softFallback(merchantApi.expirySummary(), null, '效期汇总')
    ]);
    if (isStaleBusinessLoad(seq, loadSeq)) return;
    const merged = coalesceBusinessBundle({
      analytics: a,
      settlement: s,
      prevAnalytics: analytics.value,
      prevSettlement: settlement.value
    });
    if (merged.hardFail) {
      error.value = BUSINESS_BUNDLE_HARD_FAIL_MESSAGE;
      return;
    }
    analytics.value = merged.analytics;
    settlement.value = merged.settlement;
    aiInsight.value = ai;
    expirySummary.value = ex;
  } catch (e) {
    if (isStaleBusinessLoad(seq, loadSeq)) return;
    error.value = businessLoadErrorMessage(e);
  } finally {
    if (!isStaleBusinessLoad(seq, loadSeq)) loading.value = false;
  }
}

function changeDays(value: number) {
  if (days.value === value) return;
  days.value = value;
  void load(true);
}

function onPeriodTab(key: string) {
  changeDays(Number(key));
}

function explainSettlement() {
  uni.showModal({
    title: '全店金额',
    content: settlementHelpText(),
    showCancel: false,
    confirmText: '知道了'
  });
}

function explainInsight() {
  uni.showModal({
    title: '洞察依据',
    content: insightHelpText(days.value, aiInsight.value?.insight),
    showCancel: false,
    confirmText: '知道了'
  });
}

function goFailedSplits() {
  if (!hasPerm(me.value, 'merchant:splits:list')) {
    showError('无分账明细权限');
    return;
  }
  uni.navigateTo({ url: '/pages/splits/splits?status=FAILED' });
}

onLoad(() => void load(false));
onShow(() => {
  if (!loading.value) void load(true);
});
onPullDownRefresh(() => load(false).finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped src="./business.page.css"></style>
