<template>
  <view class="page">
    <app-nav-bar title="经营分析" />
    <view class="page-body">
      <view class="periods">
        <text
          v-for="d in periods"
          role="button"
          :key="d"
          class="period"
          :class="{ active: days === d }"
          @click="changeDays(d)"
          >近{{ d }}天</text
        >
      </view>
      <view v-if="loading && !analytics.topSkus?.length" class="state">正在汇总经营数据…</view>
      <error-state
        v-else-if="error && !analytics.topSkus?.length"
        :title="error"
        @retry="() => load()"
      />
      <template v-else>
        <view class="hero">
          <text class="hero-label">经营毛利</text
          ><text class="hero-value">{{ money(analytics.grossMarginCents) }}</text>
          <view class="hero-row"
            ><text>营收 {{ money(analytics.revenueCents) }}</text
            ><text>毛利率 {{ marginRate }}</text></view
          >
          <view class="hero-row"
            ><text>客单 {{ money(analytics.avgOrderValueCents) }}</text
            ><text>件均 {{ money(analytics.avgUnitPriceCents) }}</text></view
          >
          <view class="hero-row muted"
            ><text>{{ analytics.orderCount || 0 }} 单 · {{ analytics.itemQtySold || 0 }} 件</text
            ><text :class="changeClass(analytics.revenueChangePct)"
              >营收环比 {{ formatChange(analytics.revenueChangePct) }}</text
            ></view
          >
          <view class="hero-row muted"
            ><text :class="changeClass(analytics.marginChangePct)"
              >毛利环比 {{ formatChange(analytics.marginChangePct) }}</text
            ><text v-if="(analytics.stockoutSkuCount || 0) > 0" class="loss"
              >缺货估损 {{ money(analytics.stockoutLossEstimateCents) }}</text
            ></view
          >
        </view>
        <view class="metric-grid">
          <view class="metric"
            ><text class="metric-value">{{ money(analytics.avgOrderValueCents) }}</text
            ><text class="metric-label">客单价</text></view
          >
          <view class="metric"
            ><text class="metric-value">{{ money(analytics.grossMarginCents) }}</text
            ><text class="metric-label">毛利</text></view
          >
          <view class="metric"
            ><text class="metric-value">{{ money(settlement.settledMonthCents) }}</text
            ><text class="metric-label">本月已结算</text></view
          >
          <view class="metric"
            ><text class="metric-value warn">{{ money(settlement.pendingAmountCents) }}</text
            ><text class="metric-label">待结算</text></view
          >
          <view class="metric"
            ><text class="metric-value">{{ analytics.topSkus?.length || 0 }}</text
            ><text class="metric-label">重点商品</text></view
          >
          <view class="metric"
            ><text class="metric-value danger">{{ settlement.failedSplitCount || 0 }}</text
            ><text class="metric-label">分账异常</text></view
          >
          <view class="metric"
            ><text class="metric-value danger">{{ analytics.stockoutSkuCount || 0 }}</text
            ><text class="metric-label">缺货商品数</text></view
          >
        </view>
        <view class="card">
          <view class="section-head"
            ><text class="section-title">商品经营表现</text
            ><text class="section-sub">按销售额排序</text></view
          >
          <view v-for="sku in analytics.topSkus || []" :key="sku.skuId" class="sku-row">
            <view class="sku-main"
              ><text class="sku-name">{{ sku.skuName }}</text
              ><text class="sku-rec"
                >毛利 {{ money(sku.grossMarginCents) }} · 毛利率 {{ skuMarginRate(sku) }} · 件均
                {{ money(skuUnitPrice(sku)) }}</text
              ></view
            >
            <view class="sku-data"
              ><text>{{ sku.qtySold }} 件</text
              ><text class="sku-money">{{ money(sku.revenueCents) }}</text></view
            >
          </view>
          <view v-if="!analytics.topSkus?.length" class="empty">暂无可分析的销售数据</view>
        </view>
        <view class="card">
          <view class="section-head"
            ><text class="section-title">销售四表</text
            ><text class="section-sub">商品 / 货柜 / 毛利 · 含客单</text></view
          >
          <view class="report-dims">
            <text
              v-for="d in reportDims"
              role="button"
              :key="d.value"
              class="report-dim"
              :class="{ active: reportDim === d.value }"
              @click="changeReportDim(d.value)"
              >{{ d.label }}</text
            >
          </view>
          <!-- 扩展功能：构成图（merchant.charts.enabled，关时不渲染）。O5：ECharts 渲染，可切换营收/毛利/销量/订单 -->
          <view v-if="chartsEnabled && salesRows.length" class="chart-block">
            <text class="chart-title">{{ chartTitle }}</text>
            <view class="chart-metrics">
              <text
                v-for="m in chartMetricOptions"
                :key="'metric-' + m.value"
                class="chart-metric"
                :class="{ active: chartMetric === m.value }"
                @click="chartMetric = m.value"
                >{{ m.label }}</text
              >
            </view>
            <!--
              O5：画布尺寸必须走 `custom-style`（内联），不能用 custom-class + 本页 scoped 样式。
              🔴 实测（微信开发者工具模拟器）：scoped 规则会被编译成 `.chart.data-v-<本页>`，
              而组件根节点带的是**组件自己**的 scope（`data-v-<组件>`）⇒ 规则永不匹配
              ⇒ 根节点高度 auto ⇒ canvas 实测 345×0，图上什么都看不到（H5 无此问题）。
            -->
            <uni-echarts custom-style="width: 100%; height: 420rpx" :option="chartOption" />
          </view>
          <view v-if="reportLoading" class="empty">{{ loadingLabel('报表') }}</view>
          <view v-else-if="!salesRows.length" class="empty">该区间暂无销售明细</view>
          <view v-for="r in salesRows.slice(0, 8)" :key="r.dimKey" class="sku-row">
            <view class="sku-main"
              ><text class="sku-name">{{ r.dimLabel || r.dimKey }}</text
              ><text class="sku-rec"
                >{{ r.orderCount }} 单 · {{ r.qty }} 件 · 客单 {{ money(rowAov(r)) }} · 毛利
                {{ money(r.marginCents) }}</text
              ></view
            >
            <view class="sku-data"
              ><text class="sku-money">{{ money(r.revenueCents) }}</text></view
            >
          </view>
        </view>
        <view v-if="aiInsight?.insight" class="card">
          <view class="section-head"
            ><text class="section-title">AI 经营洞察</text
            ><text class="section-sub">{{ formatInsightTime(aiInsight.generatedAt) }}</text></view
          >
          <text class="insight-text">{{ aiInsight.insight }}</text>
          <view v-for="p in aiInsight.skuPerformance || []" :key="p.skuId" class="insight-sku">
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
        <view class="card">
          <view class="section-head"
            ><text class="section-title">开票税号资料</text
            ><text class="section-sub">月结对账开票用</text></view
          >
          <view v-if="!taxMerchantId" class="empty">暂无绑定商户</view>
          <view v-else class="tax-form">
            <input v-model="taxForm.companyName" class="tax-input" placeholder="公司名称" />
            <input v-model="taxForm.taxNo" class="tax-input" placeholder="纳税人识别号" />
            <input v-model="taxForm.address" class="tax-input" placeholder="地址（选填）" />
            <input v-model="taxForm.phone" class="tax-input" placeholder="电话（选填）" />
            <button class="tax-save" :loading="taxSaving" @click="saveTax">保存税号资料</button>
          </view>
        </view>
        <view v-if="deviceReports.length" class="card">
          <view class="section-head"
            ><text class="section-title">柜机报表</text
            ><text class="section-sub">在线 · 线路 · 温度 · 固件 · 客单</text></view
          >
          <view v-for="r in deviceReports" :key="r.deviceId" class="report-row">
            <view class="report-main">
              <text class="sku-name">{{ r.deviceName }}</text>
              <text class="meta meta-status-row">
                <text>{{ r.deviceId }} · </text>
                <text
                  class="app-status"
                  :class="r.onlineStatus === 'ONLINE' ? 'is-online' : 'is-offline'"
                >
                  <text class="app-status-dot" aria-hidden="true" />
                  {{ onlineLabel(r.onlineStatus === 'ONLINE') }}
                </text>
                <text v-if="r.routeCode"> · 线路 {{ r.routeCode }}</text>
                <text v-if="r.salesLocked"> · {{ UI_COPY.salesLocked }}</text>
              </text>
              <text v-if="r.address" class="meta">{{ r.address }}</text>
              <text
                v-if="
                  r.currentTempC != null ||
                  r.firmwareVersion ||
                  (r.salesLocked && r.salesLockReason)
                "
                class="meta"
              >
                <template v-if="r.currentTempC != null">温度 {{ r.currentTempC }}°C</template>
                <template v-if="r.currentTempC != null && r.firmwareVersion"> · </template>
                <template v-if="r.firmwareVersion">固件 {{ r.firmwareVersion }}</template>
                <template
                  v-if="
                    (r.currentTempC != null || r.firmwareVersion) &&
                    r.salesLocked &&
                    r.salesLockReason
                  "
                >
                  ·
                </template>
                <!-- 停售原因只在「确实处于停售」时展示：未锁机却挂着原因会自相矛盾 -->
                <template v-if="r.salesLocked && r.salesLockReason">{{
                  r.salesLockReason
                }}</template>
              </text>
            </view>
            <view class="report-data">
              <text
                >今日 {{ num(r.orderToday) }} 单 · {{ fmtMoney(num(r.revenueTodayCents))
                }}{{
                  avgOrderText(r.revenueTodayCents, r.avgOrderValueTodayCents, r.orderToday)
                }}</text
              >
              <text
                >累计 {{ num(r.orderTotal) }} 单 · {{ fmtMoney(num(r.revenueTotalCents))
                }}{{
                  avgOrderText(r.revenueTotalCents, r.avgOrderValueTotalCents, r.orderTotal)
                }}</text
              >
              <text>会话 {{ r.sessionTotal }}（活跃 {{ r.sessionActive }}）</text>
            </view>
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
        <view v-if="canExport" class="actions">
          <app-button variant="outline" label="导出柜机报表" @click="onExport" />
        </view>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { UI_COPY, onlineLabel, loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import { showError, showSuccess } from '@/utils/notify';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import UniEcharts from 'uni-echarts';
import { provideEcharts } from 'uni-echarts/shared';
import { loadMerchantFlags, merchantChartsEnabled } from '@/utils/merchant-config';
import { echarts } from '@/utils/echarts-setup';
import {
  SALES_CHART_METRICS,
  buildSalesChartOption,
  type SalesChartMetric
} from '@/utils/sales-chart';
import {
  avgOrderText,
  businessNum,
  changeClass,
  formatChange,
  formatInsightTime,
  marginRatePercent,
  performanceLabel,
  reportDateRange,
  rowAov,
  skuMarginRate,
  skuUnitPrice
} from '@/utils/business-display';
import {
  buildSaveTaxProfileBody,
  emptyTaxProfileForm,
  mapTaxProfileToForm,
  taxProfileFormError
} from '@/utils/business-tax';
import {
  BUSINESS_BUNDLE_HARD_FAIL_MESSAGE,
  businessLoadErrorMessage,
  coalesceBusinessBundle,
  isStaleBusinessLoad,
  shouldShowBusinessFullLoading
} from '@/utils/business-load';

// O5：把**按需注册**的 echarts 实例注入 uni-echarts 组件。文档允许由 Vite 插件代劳，
// 这里显式调用是为了不依赖插件的隐式行为（插件失效时图表会静默不渲染，极难排查）。
provideEcharts(echarts);
import {
  isMerchantLoggedIn,
  hasPerm,
  merchantApi,
  softFallback,
  downloadAuthedFile,
  openExportedFile
} from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import type {
  MerchantAnalyticsOverview,
  MerchantMe,
  MerchantSettlementOverview,
  MerchantAiInsight,
  MerchantExpirySummary,
  OpenApiMerchantDeviceReportDto
} from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canViewBusiness = computed(
  () => hasPerm(me.value, 'merchant:reports:view') || hasPerm(me.value, 'merchant:analytics:view')
);
const canExport = computed(() => hasPerm(me.value, 'merchant:reports:export'));
const canEditProfile = computed(() => hasPerm(me.value, 'merchant:profile:edit'));
const taxMerchantId = computed(() => me.value?.merchants?.[0]?.merchantId || '');
const taxSaving = ref(false);
const taxForm = ref(emptyTaxProfileForm());

async function loadTaxProfile() {
  const mid = taxMerchantId.value;
  if (!mid) return;
  try {
    const p = await merchantApi.getTaxProfile(mid);
    taxForm.value = mapTaxProfileToForm(p);
  } catch {
    /* ignore */
  }
}

async function saveTax() {
  const mid = taxMerchantId.value;
  if (!mid) return;
  if (!canEditProfile.value) {
    showError('无资料编辑权限');
    return;
  }
  const formErr = taxProfileFormError(taxForm.value);
  if (formErr) {
    showError(formErr);
    return;
  }
  taxSaving.value = true;
  try {
    await merchantApi.saveTaxProfile(buildSaveTaxProfileBody(mid, taxForm.value));
    showSuccess('已保存');
  } catch (e) {
    showError(e instanceof Error ? e.message : '保存失败');
  } finally {
    taxSaving.value = false;
  }
}

const periods = [7, 30, 90];
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

/** 报表数值兜底已迁 business-display（M6b）。 */
const num = businessNum;

const reportDims = [
  { value: 'PRODUCT', label: '商品' },
  { value: 'CABINET', label: '货柜' },
  { value: 'MARGIN', label: '毛利' }
];
const reportDim = ref('PRODUCT');
const reportLoading = ref(false);
const salesRows = ref<import('@aicabinet/shared-types').OpenApiSalesReportRowDto[]>([]);
/**
 * 扩展功能：经营分析图表（`merchant.charts.enabled`）。
 * 默认关 ⇒ 图表块不渲染，页面与接入前完全一致。
 */
const chartsEnabled = ref(false);
/** O5：构成图当前指标（默认营收 ⇒ 开关开启后默认展示与接入前一致）。 */
const chartMetric = ref<SalesChartMetric>('revenue');
const chartMetricOptions = SALES_CHART_METRICS;
const chartTitle = computed(
  () =>
    `构成（按当前维度 · ${
      chartMetricOptions.find((m) => m.value === chartMetric.value)?.label ?? ''
    }）`
);
/** O5：构成图 option（ECharts）。取值/格式化全在 `buildSalesChartOption`，组件只负责挂载。 */
const chartOption = computed(() => buildSalesChartOption(salesRows.value || [], chartMetric.value));
const marginRate = computed(() =>
  marginRatePercent(analytics.value.revenueCents, analytics.value.grossMarginCents)
);
const money = (cents = 0) => fmtMoney(cents);

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
    const [a, s, ai, ex, reports] = await Promise.all([
      softFallback(merchantApi.analytics(days.value), null, '经营分析'),
      softFallback(merchantApi.settlements(), null, '结算'),
      softFallback(merchantApi.aiInsight(days.value), null, 'AI洞察'),
      softFallback(merchantApi.expirySummary(), null, '效期汇总'),
      softFallback(merchantApi.deviceReports(), [] as OpenApiMerchantDeviceReportDto[], '柜机报表')
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
    deviceReports.value = reports || [];
    await Promise.all([loadTaxProfile(), loadSalesReports()]);
  } catch (e) {
    if (isStaleBusinessLoad(seq, loadSeq)) return;
    error.value = businessLoadErrorMessage(e);
  } finally {
    if (!isStaleBusinessLoad(seq, loadSeq)) loading.value = false;
  }
}

function reportDateRangeForDays() {
  return reportDateRange(days.value);
}

async function loadSalesReports() {
  reportLoading.value = true;
  try {
    const { fromDate, toDate } = reportDateRangeForDays();
    salesRows.value = await merchantApi.salesReports(reportDim.value, fromDate, toDate);
  } catch {
    salesRows.value = [];
  } finally {
    reportLoading.value = false;
  }
}

function changeReportDim(value: string) {
  if (reportDim.value === value) return;
  reportDim.value = value;
  void loadSalesReports();
}

function changeDays(value: number) {
  if (days.value === value) return;
  days.value = value;
  void load(true);
}

function goFailedSplits() {
  if (!hasPerm(me.value, 'merchant:splits:list')) {
    showError('无分账明细权限');
    return;
  }
  uni.navigateTo({ url: '/pages/splits/splits?status=FAILED' });
}

function onExport() {
  if (!canExport.value) {
    showError('无导出权限');
    return;
  }
  const url = merchantApi.exportDeviceReportsUrl();
  downloadAuthedFile(url)
    .then(async (tempFilePath) => {
      await openExportedFile(tempFilePath, `device-reports-${days.value}d.xlsx`);
      showSuccess('导出成功');
    })
    .catch((e) => {
      showError(e instanceof Error ? e.message : '导出失败');
    });
}

onLoad(() => void load(false));
onShow(() => {
  // 返回本页时静默刷新（含首次为空的场景）
  if (!loading.value) void load(true);
  // 扩展功能开关（fail-closed：配置取不到就保持关闭，页面与接入前一致）
  void loadMerchantFlags().then(() => {
    chartsEnabled.value = merchantChartsEnabled();
  });
});
onPullDownRefresh(() => load(false).finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped src="./business.page.css"></style>
