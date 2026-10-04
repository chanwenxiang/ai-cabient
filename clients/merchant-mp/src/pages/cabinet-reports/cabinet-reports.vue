<template>
  <view class="page">
    <app-nav-bar title="销售报表" />
    <app-underline-tabs :items="periodTabs" :value="String(days)" @change="onPeriodTab" />
    <view class="page-body">
      <view class="intro">
        <text class="intro-desc"
          >按货柜看近一段日子卖了哪些商品、毛利多少。对账或报税需要时可以导出。</text
        >
      </view>
      <view class="period-card">
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
        <text class="period-hint">{{ periodRangeLabel }}</text>
      </view>
      <view v-if="loading" class="empty">正在加载销售报表…</view>
      <view v-else class="card">
        <view class="section-head">
          <text class="section-title">销售明细</text>
        </view>
        <view class="report-dims">
          <text
            v-for="d in reportDims"
            role="button"
            :key="d.value"
            class="aux-link"
            :class="{ active: reportDim === d.value }"
            @click="changeReportDim(d.value)"
            >{{ d.label }}</text
          >
        </view>
        <view v-if="chartsEnabled && salesRows.length" class="chart-block">
          <text class="chart-title">{{ chartTitle }}</text>
          <view class="chart-metrics">
            <text
              v-for="m in chartMetricOptions"
              :key="'metric-' + m.value"
              class="aux-link"
              :class="{ active: chartMetric === m.value }"
              @click="chartMetric = m.value"
              >{{ m.label }}</text
            >
          </view>
          <uni-echarts custom-style="width: 100%; height: 420rpx" :option="chartOption" />
        </view>
        <view v-if="reportLoading" class="empty">{{ loadingLabel('报表') }}</view>
        <view v-else-if="!listedSalesRows.length" class="empty">该区间暂无销售明细</view>
        <view v-for="r in listedSalesRows" :key="r.dimKey" class="sku-row">
          <view class="sku-main">
            <text class="sku-name">{{ skuTitleWithQty(r.dimLabel || r.dimKey, r.qty) }}</text>
            <text class="sku-rec"
              >{{ r.orderCount || 0 }}单 · 客单 {{ money(rowAov(r)) }} · 毛利
              {{ money(r.marginCents) }}</text
            >
          </view>
          <view class="sku-data">
            <text class="sku-money">{{ money(r.revenueCents) }}</text>
          </view>
        </view>
      </view>
      <view v-if="canExport" class="actions">
        <app-button variant="primary" block label="导出报表" @click="onExport" />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import UniEcharts from 'uni-echarts';
import { provideEcharts } from 'uni-echarts/shared';
import { echarts } from '@/utils/echarts-setup';
import { loadMerchantFlags, merchantChartsEnabled } from '@/utils/merchant-config';
import {
  SALES_CHART_METRICS,
  buildSalesChartOption,
  type SalesChartMetric
} from '@/utils/sales-chart';
import { showError, showSuccess } from '@/utils/notify';
import {
  formatPeriodRangeLabel,
  reportDateRange,
  rowAov,
  skuTitleWithQty
} from '@/utils/business-display';
import {
  downloadAuthedFile,
  hasPerm,
  isMerchantLoggedIn,
  merchantApi,
  openExportedFile,
  softFallback
} from '@/utils/merchant-api';
import { seedMerchantMeDisplayCache, useMerchantMe } from '@/composables/useMerchantMe';
import type {
  OpenApiMerchantDeviceReportDto,
  OpenApiSalesReportRowDto
} from '@aicabinet/shared-types';

provideEcharts(echarts);

const { me, refresh: refreshMe } = useMerchantMe();
const periods = [7, 30, 90];
const periodTabs = periods.map((d) => ({ key: String(d), label: `近${d}天` }));
const days = ref(30);
const loading = ref(true);
const reportLoading = ref(false);
const deviceReports = ref<OpenApiMerchantDeviceReportDto[]>([]);
const selectedDeviceId = ref('');
const reportDims = [
  { value: 'PRODUCT', label: '商品' },
  { value: 'MARGIN', label: '毛利' }
];
const reportDim = ref('PRODUCT');
const salesRows = ref<OpenApiSalesReportRowDto[]>([]);
const chartsEnabled = ref(false);
const chartMetric = ref<SalesChartMetric>('revenue');
const chartMetricOptions = SALES_CHART_METRICS;
const canExport = computed(() => hasPerm(me.value, 'merchant:reports:export'));
const periodRangeLabel = computed(() => formatPeriodRangeLabel(days.value));
const listedSalesRows = computed(() => (salesRows.value || []).slice(0, 12));
const chartTitle = computed(
  () => `构成（${chartMetricOptions.find((m) => m.value === chartMetric.value)?.label ?? ''}）`
);
const chartOption = computed(() => buildSalesChartOption(salesRows.value || [], chartMetric.value));
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

async function ensureLogin() {
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
  return true;
}

async function loadSalesReports() {
  reportLoading.value = true;
  try {
    const { fromDate, toDate } = reportDateRange(days.value);
    const rows = await merchantApi.salesReports(
      reportDim.value,
      fromDate,
      toDate,
      selectedDeviceId.value || undefined
    );
    salesRows.value = rows || [];
  } catch {
    salesRows.value = [];
  } finally {
    reportLoading.value = false;
  }
}

async function load() {
  if (!(await ensureLogin())) return;
  loading.value = true;
  try {
    const reports = await softFallback(
      merchantApi.deviceReports(),
      [] as OpenApiMerchantDeviceReportDto[],
      '货柜列表'
    );
    deviceReports.value = reports || [];
    pickDefaultCabinet();
    await loadSalesReports();
  } catch (e) {
    showError(e instanceof Error ? e.message : '加载失败');
    salesRows.value = [];
  } finally {
    loading.value = false;
  }
}

function onCabinetPick(e: { detail?: { value?: string | number } }) {
  const next = cabinetOptions.value[Number(e.detail?.value)]?.id || '';
  if (!next || next === selectedDeviceId.value) return;
  selectedDeviceId.value = next;
  void loadSalesReports();
}

function changeReportDim(value: string) {
  if (reportDim.value === value) return;
  reportDim.value = value;
  void loadSalesReports();
}

function changeDays(value: number) {
  if (days.value === value) return;
  days.value = value;
  void loadSalesReports();
}

function onPeriodTab(key: string) {
  changeDays(Number(key));
}

function onExport() {
  if (!canExport.value) {
    showError('无导出权限');
    return;
  }
  const { fromDate, toDate } = reportDateRange(days.value);
  downloadAuthedFile(merchantApi.exportSalesReportsUrl(reportDim.value, fromDate, toDate))
    .then(async (tempFilePath) => {
      await openExportedFile(tempFilePath, `sales-reports-${days.value}d.csv`);
      showSuccess('导出成功');
    })
    .catch((e) => {
      showError(e instanceof Error ? e.message : '导出失败');
    });
}

onShow(() => {
  void load();
  void loadMerchantFlags().then(() => {
    chartsEnabled.value = merchantChartsEnabled();
  });
});
onPullDownRefresh(() => {
  void load().finally(() => uni.stopPullDownRefresh());
});
</script>

<style scoped src="./cabinet-reports.page.css"></style>
