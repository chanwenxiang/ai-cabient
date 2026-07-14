<template>
  <view class="page-root">
    <view class="date-bar">
      <picker mode="date" :value="startDate" @change="e => { startDate = e.detail.value; load(); }">
        <text class="date-text">{{ startDate }}</text>
      </picker>
      <text class="date-sep">至</text>
      <picker mode="date" :value="endDate" @change="e => { endDate = e.detail.value; load(); }">
        <text class="date-text">{{ endDate }}</text>
      </picker>
    </view>

    <view class="summary-card">
      <view class="summary-row">
        <text class="summary-label">营收总额</text>
        <text class="summary-value">¥{{ summary.gross || '0.00' }}</text>
      </view>
      <view class="summary-row">
        <text class="summary-label">平台抽成</text>
        <text class="summary-value minus">-¥{{ summary.platformFee || '0.00' }}</text>
      </view>
      <view class="summary-row total">
        <text class="summary-label">商户所得</text>
        <text class="summary-value">¥{{ summary.merchantIncome || '0.00' }}</text>
      </view>
    </view>

    <view class="section">
      <text class="section-title">设备明细</text>
      <view v-for="d in deviceDetails" :key="d.deviceId" class="device-row">
        <view class="device-info">
          <text class="device-name">{{ d.deviceName || d.deviceId }}</text>
          <text class="device-orders">{{ d.orderCount }}笔订单</text>
        </view>
        <text class="device-amount">¥{{ (d.grossCents / 100).toFixed(2) }}</text>
      </view>
    </view>

    <view class="actions">
      <button class="btn-outline" @click="onExport">导出对账单</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { merchantApi } from '@/utils/merchant-api';

const today = new Date().toISOString().substring(0, 10);
const sevenDaysAgo = new Date(Date.now() - 7 * 86400000).toISOString().substring(0, 10);

const startDate = ref(sevenDaysAgo);
const endDate = ref(today);
const summary = ref({ gross: '0.00', platformFee: '0.00', merchantIncome: '0.00' });
const deviceDetails = ref<any[]>([]);

onShow(() => load());

async function load() {
  try {
    const res = await merchantApi.get('/api/v2/merchant/settlements', {
      params: { startDate: startDate.value, endDate: endDate.value }
    });
    const data = res.data ?? {};
    summary.value = {
      gross: ((data.grossCents || 0) / 100).toFixed(2),
      platformFee: ((data.platformFeeCents || 0) / 100).toFixed(2),
      merchantIncome: ((data.merchantIncomeCents || 0) / 100).toFixed(2),
    };
    deviceDetails.value = data.devices ?? [];
  } catch {
    uni.showToast({ title: '加载失败', icon: 'none' });
  }
}

async function onExport() {
  try {
    const blob = await merchantApi.get('/api/v2/merchant/settlements/export', {
      params: { startDate: startDate.value, endDate: endDate.value },
      responseType: 'blob'
    });
    uni.showToast({ title: '导出成功', icon: 'success' });
  } catch {
    uni.showToast({ title: '导出失败', icon: 'error' });
  }
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
.section { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; }
.section-title { font-size: 28rpx; font-weight: 600; margin-bottom: 16rpx; display: block; }
.device-row { display: flex; justify-content: space-between; align-items: center; padding: 14rpx 0; border-bottom: 1rpx solid #f0fdfa; }
.device-name { font-size: 28rpx; display: block; }
.device-orders { font-size: 22rpx; color: #999; }
.device-amount { font-size: 28rpx; font-weight: 600; }
.actions { padding: 20rpx 0; }
.btn-outline { width: 100%; height: 72rpx; line-height: 72rpx; border: 2rpx solid #0f766e; color: #0f766e; border-radius: 36rpx; background: #fff; font-size: 28rpx; text-align: center; }
</style>
