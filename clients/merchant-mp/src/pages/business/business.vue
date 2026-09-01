<template>
  <view class="page">
    <app-nav-bar title="经营分析" />
    <view class="page-body">
      <view class="periods">
        <text
          v-for="d in periods"
          :key="d"
          class="period"
          :class="{ active: days === d }"
          @click="changeDays(d)"
          >近{{ d }}天</text
        >
      </view>
      <view v-if="loading && !analytics.topSkus?.length" class="state">正在汇总经营数据…</view>
      <view v-else-if="error && !analytics.topSkus?.length" class="state"
        ><text class="error">{{ error }}</text
        ><button class="retry" @click="() => load()">重试</button></view
      >
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
              :key="d.value"
              class="report-dim"
              :class="{ active: reportDim === d.value }"
              @click="changeReportDim(d.value)"
              >{{ d.label }}</text
            >
          </view>
          <view v-if="reportLoading" class="empty">加载报表…</view>
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
            ><text class="section-sub">{{ formatTime(aiInsight.generatedAt) }}</text></view
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
              ><text class="expiry-n"
                >¥{{ (expirySummary.writeOffCostCents30d / 100).toFixed(2) }}</text
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
              <text class="meta"
                >{{ r.deviceId }} · {{ r.onlineStatus === 'ONLINE' ? '在线' : '离线'
                }}{{ r.routeCode ? ` · 线路 ${r.routeCode}` : ''
                }}{{ r.salesLocked ? ' · 停售' : '' }}</text
              >
              <text v-if="r.address" class="meta">{{ r.address }}</text>
              <text
                v-if="r.currentTempC != null || r.firmwareVersion || r.salesLockReason"
                class="meta"
              >
                <template v-if="r.currentTempC != null">温度 {{ r.currentTempC }}°C</template>
                <template v-if="r.currentTempC != null && r.firmwareVersion"> · </template>
                <template v-if="r.firmwareVersion">固件 {{ r.firmwareVersion }}</template>
                <template
                  v-if="(r.currentTempC != null || r.firmwareVersion) && r.salesLockReason"
                >
                  ·
                </template>
                <template v-if="r.salesLockReason">{{ r.salesLockReason }}</template>
              </text>
            </view>
            <view class="report-data">
              <text
                >今日 {{ r.orderToday }} 单 · ¥{{ (r.revenueTodayCents / 100).toFixed(2)
                }}{{
                  r.orderToday > 0
                    ? ` · 客单 ¥${((r.avgOrderValueTodayCents || r.revenueTodayCents / r.orderToday) / 100).toFixed(2)}`
                    : ''
                }}</text
              >
              <text
                >累计 {{ r.orderTotal }} 单 · ¥{{ (r.revenueTotalCents / 100).toFixed(2)
                }}{{
                  r.orderTotal > 0
                    ? ` · 客单 ¥${((r.avgOrderValueTotalCents || r.revenueTotalCents / r.orderTotal) / 100).toFixed(2)}`
                    : ''
                }}</text
              >
              <text>会话 {{ r.sessionTotal }}（活跃 {{ r.sessionActive }}）</text>
            </view>
          </view>
        </view>
        <view v-if="settlement.failedSplitCount" class="risk-card" @click="goFailedSplits">
          <text class="risk-title">有 {{ settlement.failedSplitCount }} 笔分账异常</text>
          <text class="risk-desc">点此查看失败原因与订单明细 ›</text>
        </view>
        <view v-if="canExport" class="actions">
          <button class="btn-outline" @click="onExport">导出柜机报表</button>
        </view>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import {
  getToken,
  hasPerm,
  merchantApi,
  downloadAuthedFile,
  openExportedFile,
  type MerchantDeviceReport
} from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type {
  MerchantAnalyticsOverview,
  MerchantMe,
  MerchantSettlementOverview,
  MerchantSkuSales,
  MerchantAiInsight,
  MerchantExpirySummary
} from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canViewBusiness = computed(
  () => hasPerm(me.value, 'merchant:reports:view') || hasPerm(me.value, 'merchant:analytics:view')
);
const canExport = computed(() => hasPerm(me.value, 'merchant:reports:export'));
const canEditProfile = computed(() => hasPerm(me.value, 'merchant:profile:edit'));
const taxMerchantId = computed(() => me.value?.merchants?.[0]?.merchantId || '');
const taxSaving = ref(false);
const taxForm = ref({
  companyName: '',
  taxNo: '',
  address: '',
  phone: ''
});

async function loadTaxProfile() {
  const mid = taxMerchantId.value;
  if (!mid) return;
  try {
    const p = await merchantApi.getTaxProfile(mid);
    taxForm.value = {
      companyName: p.companyName || '',
      taxNo: p.taxNo || '',
      address: p.address || '',
      phone: p.phone || ''
    };
  } catch {
    /* ignore */
  }
}

async function saveTax() {
  const mid = taxMerchantId.value;
  if (!mid) return;
  if (!canEditProfile.value) {
    uni.showToast({ title: '无资料编辑权限', icon: 'none' });
    return;
  }
  if (!taxForm.value.companyName.trim() || !taxForm.value.taxNo.trim()) {
    uni.showToast({ title: '请填写公司名与税号', icon: 'none' });
    return;
  }
  taxSaving.value = true;
  try {
    await merchantApi.saveTaxProfile({
      merchantId: mid,
      companyName: taxForm.value.companyName.trim(),
      taxNo: taxForm.value.taxNo.trim(),
      address: taxForm.value.address.trim() || undefined,
      phone: taxForm.value.phone.trim() || undefined
    });
    uni.showToast({ title: '已保存', icon: 'success' });
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '保存失败', icon: 'none' });
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
const deviceReports = ref<MerchantDeviceReport[]>([]);
const reportDims = [
  { value: 'PRODUCT', label: '商品' },
  { value: 'CABINET', label: '货柜' },
  { value: 'MARGIN', label: '毛利' }
];
const reportDim = ref('PRODUCT');
const reportLoading = ref(false);
const salesRows = ref<
  Array<{
    dimKey: string;
    dimLabel: string;
    orderCount: number;
    qty: number;
    revenueCents: number;
    cogsCents: number;
    marginCents: number;
  }>
>([]);
const marginRate = computed(() =>
  analytics.value.revenueCents
    ? `${((analytics.value.grossMarginCents / analytics.value.revenueCents) * 100).toFixed(1)}%`
    : '暂无'
);
const money = (cents = 0) => `¥${((Number(cents) || 0) / 100).toFixed(2)}`;
function formatChange(pct?: number | null) {
  if (pct == null || Number.isNaN(pct)) return '暂无';
  const sign = pct > 0 ? '+' : '';
  return `${sign}${pct.toFixed(1)}%`;
}
function changeClass(pct?: number | null) {
  if (pct == null || Number.isNaN(pct) || pct === 0) return '';
  return pct > 0 ? 'up' : 'down';
}
function skuUnitPrice(sku: MerchantSkuSales) {
  return sku.qtySold > 0 ? Math.round(sku.revenueCents / sku.qtySold) : 0;
}
function rowAov(r: { orderCount: number; revenueCents: number }) {
  return r.orderCount > 0 ? Math.round(r.revenueCents / r.orderCount) : 0;
}
function formatTime(iso?: string) {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function performanceLabel(level?: string) {
  const m: Record<string, string> = {
    NORMAL: '正常',
    SLOW_MOVER: '滞销',
    NO_SALES: '无销量',
    HOT: '热销',
    TOP: '爆款'
  };
  return (level && m[level]) || '暂无';
}
function skuMarginRate(sku: MerchantSkuSales) {
  return sku.revenueCents
    ? `${((sku.grossMarginCents / sku.revenueCents) * 100).toFixed(1)}%`
    : '暂无';
}

async function ensureAccess() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return false;
  }
  try {
    await refreshMe();
  } catch {
    if (!getToken()) return false;
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canViewBusiness.value) {
    loading.value = false;
    uni.showToast({ title: '无经营分析权限', icon: 'none' });
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return false;
  }
  return true;
}

async function load(soft = false) {
  const seq = ++loadSeq;
  if (!(await ensureAccess())) {
    if (seq === loadSeq) loading.value = false;
    return;
  }
  if (seq !== loadSeq) return;
  if (!soft || !analytics.value.topSkus?.length) loading.value = true;
  error.value = '';
  try {
    const [a, s, ai, ex, reports] = await Promise.all([
      merchantApi.analytics(days.value).catch(() => null),
      merchantApi.settlements().catch(() => null),
      merchantApi.aiInsight(days.value).catch(() => null),
      merchantApi.expirySummary().catch(() => null),
      merchantApi.deviceReports().catch(() => [] as MerchantDeviceReport[])
    ]);
    if (seq !== loadSeq) return;
    if (!a && !s) {
      error.value = '经营数据加载失败';
      return;
    }
    analytics.value = a || analytics.value;
    settlement.value = s || settlement.value;
    aiInsight.value = ai;
    expirySummary.value = ex;
    deviceReports.value = reports || [];
    await Promise.all([loadTaxProfile(), loadSalesReports()]);
  } catch (e) {
    if (seq !== loadSeq) return;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function reportDateRange() {
  const to = new Date();
  const from = new Date();
  from.setDate(to.getDate() - (days.value - 1));
  const pad = (n: number) => String(n).padStart(2, '0');
  const fmt = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  return { fromDate: fmt(from), toDate: fmt(to) };
}

async function loadSalesReports() {
  reportLoading.value = true;
  try {
    const { fromDate, toDate } = reportDateRange();
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
    uni.showToast({ title: '无分账明细权限', icon: 'none' });
    return;
  }
  uni.navigateTo({ url: '/pages/splits/splits?status=FAILED' });
}

function onExport() {
  if (!canExport.value) {
    uni.showToast({ title: '无导出权限', icon: 'none' });
    return;
  }
  const url = merchantApi.exportDeviceReportsUrl();
  downloadAuthedFile(url)
    .then(async (tempFilePath) => {
      await openExportedFile(tempFilePath, `device-reports-${days.value}d.xlsx`);
      uni.showToast({ title: '导出成功', icon: 'success' });
    })
    .catch((e) => {
      uni.showToast({ title: e instanceof Error ? e.message : '导出失败', icon: 'none' });
    });
}

onLoad(() => void load(false));
onShow(() => {
  // 返回本页时静默刷新（含首次为空的场景）
  if (!loading.value) void load(true);
});
onPullDownRefresh(() => load(false).finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped>
.insight-text {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  line-height: 1.6;
  color: #334155;
}
.insight-sku {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
  margin-top: 12rpx;
  padding: 12rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}
.insight-sku:last-child {
  border-bottom: none;
}
.expiry-grid {
  display: flex;
  gap: 12rpx;
  margin-top: 12rpx;
}
.expiry-cell {
  flex: 1;
  padding: 14rpx;
  border-radius: 14rpx;
  background: #fffbeb;
}
.expiry-n,
.expiry-l {
  display: block;
}
.expiry-n {
  font-size: 30rpx;
  font-weight: 800;
  color: #b45309;
}
.expiry-l {
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #92400e;
}
.report-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}
.report-row:last-child {
  border-bottom: none;
}
.report-main {
  flex: 1;
  min-width: 0;
}
.report-data {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4rpx;
  font-size: 22rpx;
  color: #64748b;
}

.page {
  padding: 0;
  padding-bottom: 24rpx;
}
.periods {
  display: flex;
  gap: 12rpx;
  padding: 20rpx 24rpx 8rpx;
}
.period {
  padding: 12rpx 24rpx;
  border-radius: 28rpx;
  background: #fff;
  color: #64748b;
  font-size: 24rpx;
}
.period.active {
  background: #0f766e;
  color: #fff;
}
.report-dims {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.report-dim {
  padding: 8rpx 20rpx;
  border-radius: 24rpx;
  background: #f1f5f9;
  color: #475569;
  font-size: 22rpx;
}
.report-dim.active {
  background: #0f766e;
  color: #fff;
}
.state {
  margin: 24rpx;
  padding: 80rpx 24rpx;
  text-align: center;
  background: #fff;
  border-radius: 20rpx;
  color: #64748b;
}
.error {
  display: block;
  color: #dc2626;
}
.retry {
  margin-top: 24rpx;
  width: 220rpx;
  height: 72rpx;
  line-height: 72rpx;
  background: linear-gradient(135deg, #134e4a, #0f766e);
  color: #fff;
  border-radius: 44rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 20rpx rgba(15, 118, 110, 0.2);
  border: none;
}
.retry::after {
  border: none;
}
.hero {
  margin: 12rpx 24rpx;
  padding: 32rpx;
  border-radius: 24rpx;
  color: #0f172a;
  background: linear-gradient(135deg, #ecfdf5, #fff);
  border: 1rpx solid #d1fae5;
}
.hero-label {
  display: block;
  font-size: 24rpx;
  color: #64748b;
}
.hero-value {
  display: block;
  font-size: 56rpx;
  font-weight: 800;
  margin-top: 8rpx;
  color: #0f766e;
}
.hero-row {
  display: flex;
  justify-content: space-between;
  margin-top: 22rpx;
  font-size: 23rpx;
  color: #475569;
}
.hero-row.muted {
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #64748b;
}
.hero-row .up {
  color: #059669;
  font-weight: 600;
}
.hero-row .down {
  color: #dc2626;
  font-weight: 600;
}
.hero-row .loss {
  color: #b45309;
  font-weight: 600;
}
.metric-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12rpx;
  margin: 12rpx 24rpx;
}
.metric {
  background: #fff;
  border-radius: 18rpx;
  padding: 24rpx;
}
.metric-value {
  display: block;
  font-size: 32rpx;
  font-weight: 700;
  color: #0f172a;
}
.metric-value.warn {
  color: #d97706;
}
.metric-value.danger {
  color: #dc2626;
}
.metric-label {
  display: block;
  font-size: 22rpx;
  color: #64748b;
  margin-top: 6rpx;
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 10rpx;
}
.section-title {
  font-size: 30rpx;
  font-weight: 700;
}
.section-sub {
  font-size: 21rpx;
  color: #94a3b8;
}
.tax-form {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.tax-input {
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 12rpx;
  padding: 16rpx 20rpx;
  font-size: 26rpx;
}
.tax-save {
  margin-top: 8rpx;
  background: #0f766e;
  color: #fff;
  border-radius: 12rpx;
  font-size: 26rpx;
}
.tax-save::after {
  border: none;
}
.sku-row {
  display: flex;
  justify-content: space-between;
  gap: 20rpx;
  padding: 22rpx 0;
  border-top: 1rpx solid #f1f5f9;
}
.sku-main {
  min-width: 0;
  flex: 1;
}
.sku-name {
  display: block;
  font-size: 27rpx;
  font-weight: 600;
}
.sku-rec {
  display: block;
  font-size: 22rpx;
  color: #0f766e;
  margin-top: 5rpx;
}
.sku-data {
  text-align: right;
  font-size: 22rpx;
  color: #64748b;
}
.sku-money {
  display: block;
  color: #0f172a;
  font-size: 26rpx;
  font-weight: 600;
  margin-top: 5rpx;
}
.empty {
  text-align: center;
  padding: 40rpx;
  color: #94a3b8;
}
.risk-card {
  margin: 12rpx 24rpx;
  padding: 24rpx;
  border-radius: 18rpx;
  background: #fff7ed;
  border: 1rpx solid #fed7aa;
}
.risk-title {
  display: block;
  color: #c2410c;
  font-weight: 700;
}
.risk-desc {
  display: block;
  color: #9a3412;
  font-size: 23rpx;
  margin-top: 6rpx;
}
.actions {
  padding: 12rpx 24rpx 24rpx;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}
.btn-outline {
  width: 100%;
  min-height: 80rpx;
  height: 80rpx;
  line-height: 1.2;
  border: 2rpx solid #0f766e;
  color: #0f766e;
  border-radius: 44rpx;
  background: #fff;
  font-size: 28rpx;
  font-weight: 600;
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  margin: 0;
}
.btn-outline::after {
  border: none;
}
.card {
  margin: 12rpx 24rpx;
  padding: 24rpx;
  background: #fff;
  border-radius: 18rpx;
}
.page-body {
  padding: 0 0 calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
