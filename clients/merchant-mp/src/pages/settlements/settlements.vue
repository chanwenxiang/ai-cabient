<template>
  <view class="page-root">
    <view class="date-bar">
      <picker mode="date" :value="startDate" @change="(e: any) => { startDate = e.detail.value; load(); }">
        <text class="date-text">{{ startDate }}</text>
      </picker>
      <text class="date-sep">至</text>
      <picker mode="date" :value="endDate" @change="(e: any) => { endDate = e.detail.value; load(); }">
        <text class="date-text">{{ endDate }}</text>
      </picker>
    </view>

    <view class="summary-card">
      <view class="summary-row">
        <text class="summary-label">区间营收</text>
        <text class="summary-value">¥{{ summary.gross }}</text>
      </view>
      <view class="summary-row">
        <text class="summary-label">平台抽成</text>
        <text class="summary-value minus">-¥{{ summary.platformFee }}</text>
      </view>
      <view class="summary-row total">
        <text class="summary-label">商户所得</text>
        <text class="summary-value">¥{{ summary.merchantIncome }}</text>
      </view>
      <view class="summary-row">
        <text class="summary-label">待分账</text>
        <text class="summary-value">¥{{ summary.pending }}</text>
      </view>
      <view class="summary-row">
        <text class="summary-label">本月已结算</text>
        <text class="summary-value">¥{{ summary.settledMonth }}</text>
      </view>
    </view>

    <view class="tip-card">
      <text class="tip-text">分账由平台定期提交至微信收款账户，商户端不支持自主提现。</text>
      <text v-if="profitNote" class="tip-meta">{{ profitNote }}</text>
    </view>

    <view class="section">
      <text class="section-title">按日汇总</text>
      <view v-for="d in daily" :key="d.date" class="device-row">
        <view class="device-info">
          <text class="device-name">{{ d.date }}</text>
          <text class="device-orders">{{ d.orderCount }} 笔 · 待分 ¥{{ (d.pendingCents / 100).toFixed(2) }}</text>
        </view>
        <text class="device-amount">¥{{ (d.merchantCents / 100).toFixed(2) }}</text>
      </view>
      <view v-if="!daily.length" class="empty">所选日期暂无结算数据</view>
    </view>

    <view class="section">
      <text class="section-title">结算批次</text>
      <view v-for="b in batches" :key="b.batchNo" class="device-row">
        <view class="device-info">
          <text class="device-name">{{ b.batchNo }}</text>
          <text class="device-orders">{{ b.batchStatus }} · {{ b.orderCount }} 笔</text>
        </view>
        <text class="device-amount">¥{{ (b.merchantCents / 100).toFixed(2) }}</text>
      </view>
      <view v-if="!batches.length" class="empty">暂无批次</view>
    </view>

    <view class="actions">
      <button class="btn-outline" @click="onExport">导出对账单</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { getToken, merchantApi } from '@/utils/merchant-api';
import type { MerchantDailySettlement, MerchantSettlementBatch } from '@aicabinet/shared-types';

const today = new Date().toISOString().substring(0, 10);
const sevenDaysAgo = new Date(Date.now() - 7 * 86400000).toISOString().substring(0, 10);

const startDate = ref(sevenDaysAgo);
const endDate = ref(today);
const summary = ref({
  gross: '0.00',
  platformFee: '0.00',
  merchantIncome: '0.00',
  pending: '0.00',
  settledMonth: '0.00'
});
const daily = ref<MerchantDailySettlement[]>([]);
const batches = ref<MerchantSettlementBatch[]>([]);
const profitNote = ref('');

onShow(() => load());

async function load() {
  try {
    const [overview, days, batchList] = await Promise.all([
      merchantApi.settlements(),
      merchantApi.dailySettlements(startDate.value, endDate.value),
      merchantApi.settlementBatches(startDate.value, endDate.value)
    ]);
    daily.value = days || [];
    batches.value = batchList || [];
    const gross = days.reduce((s, d) => s + (d.grossCents || 0), 0);
    const platform = days.reduce((s, d) => s + (d.platformCents || 0), 0);
    const merchant = days.reduce((s, d) => s + (d.merchantCents || 0), 0);
    summary.value = {
      gross: (gross / 100).toFixed(2),
      platformFee: (platform / 100).toFixed(2),
      merchantIncome: (merchant / 100).toFixed(2),
      pending: ((overview.pendingAmountCents || 0) / 100).toFixed(2),
      settledMonth: ((overview.settledMonthCents || 0) / 100).toFixed(2)
    };
    profitNote.value = overview.profitSharing?.note || '';
  } catch {
    uni.showToast({ title: '加载失败', icon: 'none' });
  }
}

function onExport() {
  const url = merchantApi.exportSettlementsUrl(startDate.value, endDate.value);
  const token = getToken();
  uni.downloadFile({
    url,
    header: token ? { Authorization: `Bearer ${token}` } : {},
    success(res) {
      if (res.statusCode >= 200 && res.statusCode < 300) {
        uni.showToast({ title: '导出成功', icon: 'success' });
        if (res.tempFilePath) {
          uni.openDocument({ filePath: res.tempFilePath, showMenu: true }).catch(() => undefined);
        }
      } else {
        uni.showToast({ title: '导出失败', icon: 'none' });
      }
    },
    fail() {
      uni.showToast({ title: '导出失败', icon: 'none' });
    }
  });
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f0fdfa; min-height: 100vh; }
.date-bar { display: flex; align-items: center; justify-content: center; gap: 16rpx; background: #fff; border-radius: 16rpx; padding: 20rpx; margin-bottom: 20rpx; }
.date-text { font-size: 28rpx; color: #0f766e; font-weight: 500; }
.date-sep { color: #999; }
.summary-card { background: linear-gradient(135deg, #0f766e, #134e4a); border-radius: 16rpx; padding: 30rpx; margin-bottom: 20rpx; }
.summary-row { display: flex; justify-content: space-between; padding: 12rpx 0; }
.summary-row.total { border-top: 1rpx solid rgba(255,255,255,.2); margin-top: 10rpx; padding-top: 20rpx; }
.summary-label { color: rgba(255,255,255,.8); font-size: 26rpx; }
.summary-value { color: #fff; font-size: 30rpx; font-weight: 600; }
.summary-value.minus { color: rgba(255,255,255,.7); }
.tip-card { background: #ecfdf5; border-radius: 12rpx; padding: 20rpx; margin-bottom: 20rpx; }
.tip-text { font-size: 24rpx; color: #0f766e; display: block; line-height: 1.5; }
.tip-meta { font-size: 22rpx; color: #64748b; margin-top: 8rpx; display: block; }
.section { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; }
.section-title { font-size: 28rpx; font-weight: 600; margin-bottom: 16rpx; display: block; }
.device-row { display: flex; justify-content: space-between; align-items: center; padding: 14rpx 0; border-bottom: 1rpx solid #f0fdfa; }
.device-name { font-size: 28rpx; display: block; }
.device-orders { font-size: 22rpx; color: #999; }
.device-amount { font-size: 28rpx; font-weight: 600; }
.empty { font-size: 24rpx; color: #94a3b8; padding: 12rpx 0; }
.actions { padding: 20rpx 0; }
.btn-outline { width: 100%; height: 72rpx; line-height: 72rpx; border: 2rpx solid #0f766e; color: #0f766e; border-radius: 36rpx; background: #fff; font-size: 28rpx; text-align: center; }
</style>
