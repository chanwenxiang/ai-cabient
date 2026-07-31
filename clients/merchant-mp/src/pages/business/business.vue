<template>
  <view class="page">
    <view class="periods">
      <text v-for="d in periods" :key="d" class="period" :class="{ active: days === d }" @click="changeDays(d)">近{{ d }}天</text>
    </view>
    <view v-if="loading" class="state">正在汇总经营数据…</view>
    <view v-else-if="error" class="state"><text class="error">{{ error }}</text><button class="retry" @click="load">重试</button></view>
    <template v-else>
      <view class="hero">
        <text class="hero-label">经营毛利</text><text class="hero-value">{{ money(analytics.grossMarginCents) }}</text>
        <view class="hero-row"><text>营收 {{ money(analytics.revenueCents) }}</text><text>毛利率 {{ marginRate }}</text></view>
      </view>
      <view class="metric-grid">
        <view class="metric"><text class="metric-value">{{ money(settlement.settledMonthCents) }}</text><text class="metric-label">本月已结算</text></view>
        <view class="metric"><text class="metric-value warn">{{ money(settlement.pendingAmountCents) }}</text><text class="metric-label">待结算</text></view>
        <view class="metric"><text class="metric-value">{{ analytics.topSkus?.length || 0 }}</text><text class="metric-label">重点商品</text></view>
        <view class="metric"><text class="metric-value danger">{{ settlement.failedSplitCount || 0 }}</text><text class="metric-label">分账异常</text></view>
      </view>
      <view class="card">
        <view class="section-head"><text class="section-title">商品经营表现</text><text class="section-sub">按销售额排序</text></view>
        <view v-for="sku in analytics.topSkus || []" :key="sku.skuId" class="sku-row">
          <view class="sku-main"><text class="sku-name">{{ sku.skuName }}</text><text class="sku-rec">毛利 {{ money(sku.grossMarginCents) }} · 毛利率 {{ skuMarginRate(sku) }}</text></view>
          <view class="sku-data"><text>{{ sku.qtySold }} 件</text><text class="sku-money">{{ money(sku.revenueCents) }}</text></view>
        </view>
        <view v-if="!analytics.topSkus?.length" class="empty">暂无可分析的销售数据</view>
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
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { getToken, hasPerm, merchantApi, downloadAuthedFile, openExportedFile } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantAnalyticsOverview, MerchantMe, MerchantSettlementOverview, MerchantSkuSales } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canViewBusiness = computed(
  () => hasPerm(me.value, 'merchant:reports:view') || hasPerm(me.value, 'merchant:analytics:view')
);
const canExport = computed(() => hasPerm(me.value, 'merchant:reports:export'));

const periods = [7, 30, 90];
const days = ref(30);
const loading = ref(true);
const error = ref('');
let loadSeq = 0;
const analytics = ref<MerchantAnalyticsOverview>({ days: 30, revenueCents: 0, cogsCents: 0, grossMarginCents: 0, writeOffCostCents: 0, topSkus: [] });
const settlement = ref<MerchantSettlementOverview>({ pendingAmountCents: 0, pendingSplitCount: 0, settledMonthCents: 0, failedSplitCount: 0 });
const marginRate = computed(() => analytics.value.revenueCents ? `${(analytics.value.grossMarginCents / analytics.value.revenueCents * 100).toFixed(1)}%` : '—');
const money = (cents = 0) => `¥${(cents / 100).toFixed(2)}`;
function skuMarginRate(sku: MerchantSkuSales) { return sku.revenueCents ? `${(sku.grossMarginCents / sku.revenueCents * 100).toFixed(1)}%` : '—'; }

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
    const [a, s] = await Promise.all([
      merchantApi.analytics(days.value).catch(() => null),
      merchantApi.settlements().catch(() => null)
    ]);
    if (seq !== loadSeq) return;
    if (!a && !s) {
      error.value = '经营数据加载失败';
      return;
    }
    analytics.value = a || analytics.value;
    settlement.value = s || settlement.value;
  } catch (e) {
    if (seq !== loadSeq) return;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
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
  void downloadAuthedFile(url)
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
.page { padding-bottom: 24rpx; }
.periods { display:flex; gap:12rpx; padding:20rpx 24rpx 8rpx; }
.period { padding:12rpx 24rpx; border-radius:28rpx; background:#fff; color:#64748b; font-size:24rpx; }
.period.active { background:#0f766e; color:#fff; }
.state { margin:24rpx; padding:80rpx 24rpx; text-align:center; background:#fff; border-radius:20rpx; color:#64748b; }
.error{display:block;color:#dc2626}
.retry{
  margin-top:24rpx;width:220rpx;height:72rpx;line-height:72rpx;
  background:linear-gradient(135deg,#134e4a,#0f766e);color:#fff;border-radius:44rpx;
  font-weight:600;box-shadow:0 8rpx 20rpx rgba(15,118,110,.2);border:none;
}
.retry::after{border:none}
.hero { margin:12rpx 24rpx; padding:32rpx; border-radius:24rpx; color:#fff; background:linear-gradient(135deg,#134e4a,#0f766e 65%,#14b8a6); }
.hero-label{display:block;font-size:24rpx;opacity:.8}
.hero-value{display:block;font-size:56rpx;font-weight:800;margin-top:8rpx}
.hero-row{display:flex;justify-content:space-between;margin-top:22rpx;font-size:23rpx;opacity:.9}
.metric-grid{display:grid;grid-template-columns:1fr 1fr;gap:12rpx;margin:12rpx 24rpx}
.metric{background:#fff;border-radius:18rpx;padding:24rpx}
.metric-value{display:block;font-size:32rpx;font-weight:700;color:#0f172a}
.metric-value.warn{color:#d97706}
.metric-value.danger{color:#dc2626}
.metric-label{display:block;font-size:22rpx;color:#64748b;margin-top:6rpx}
.section-head{display:flex;justify-content:space-between;align-items:flex-end;margin-bottom:10rpx}
.section-title{font-size:30rpx;font-weight:700}
.section-sub{font-size:21rpx;color:#94a3b8}
.sku-row{display:flex;justify-content:space-between;gap:20rpx;padding:22rpx 0;border-top:1rpx solid #f1f5f9}
.sku-main{min-width:0;flex:1}
.sku-name{display:block;font-size:27rpx;font-weight:600}
.sku-rec{display:block;font-size:22rpx;color:#0f766e;margin-top:5rpx}
.sku-data{text-align:right;font-size:22rpx;color:#64748b}
.sku-money{display:block;color:#0f172a;font-size:26rpx;font-weight:600;margin-top:5rpx}
.empty{text-align:center;padding:40rpx;color:#94a3b8}
.risk-card{margin:12rpx 24rpx;padding:24rpx;border-radius:18rpx;background:#fff7ed;border:1rpx solid #fed7aa}
.risk-title{display:block;color:#c2410c;font-weight:700}
.risk-desc{display:block;color:#9a3412;font-size:23rpx;margin-top:6rpx}
.actions { padding: 12rpx 24rpx 24rpx; }
.btn-outline {
  width: 100%;
  height: 80rpx;
  line-height: 80rpx;
  border: 2rpx solid #0f766e;
  color: #0f766e;
  border-radius: 44rpx;
  background: #fff;
  font-size: 28rpx;
  font-weight: 600;
  text-align: center;
}
.btn-outline::after { border: none; }
.card { margin: 12rpx 24rpx; padding: 24rpx; background: #fff; border-radius: 18rpx; }
</style>
