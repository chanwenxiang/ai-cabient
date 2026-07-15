<template>
  <view class="page-root">
    <!-- 收入概览卡片 -->
    <view class="overview-card">
      <view class="overview-header">
        <text class="overview-title">分账收入</text>
        <text class="overview-period">本月</text>
      </view>
      <view class="overview-amount">
        <text class="currency">¥</text>
        <text class="amount">{{ formatAmount(summary.monthIncome) }}</text>
      </view>
      <view class="overview-details">
        <view class="detail-item">
          <text class="detail-label">今日收入</text>
          <text class="detail-value">¥{{ formatAmount(summary.todayIncome) }}</text>
        </view>
        <view class="detail-item">
          <text class="detail-label">待结算</text>
          <text class="detail-value">¥{{ formatAmount(summary.pendingSettlement) }}</text>
        </view>
        <view class="detail-item">
          <text class="detail-label">已提现</text>
          <text class="detail-value">¥{{ formatAmount(summary.withdrawn) }}</text>
        </view>
      </view>
    </view>

    <!-- 收入来源 -->
    <view class="section">
      <text class="section-title">收入来源</text>
      <view class="source-list">
        <view v-for="item in incomeSources" :key="item.type" class="source-item">
          <view class="source-left">
            <text class="source-type">{{ item.typeLabel }}</text>
            <text class="source-count">{{ item.count }}笔</text>
          </view>
          <text class="source-amount">+¥{{ formatAmount(item.amount) }}</text>
        </view>
      </view>
    </view>

    <!-- 结算记录 -->
    <view class="section">
      <view class="section-header">
        <text class="section-title">结算记录</text>
        <text class="section-more" @click="goToSettlements">查看全部</text>
      </view>
      <view class="settlement-list">
        <view v-for="item in settlements" :key="item.id" class="settlement-item">
          <view class="settlement-left">
            <text class="settlement-date">{{ item.settlementDate }}</text>
            <text class="settlement-status" :class="'status-' + item.status">
              {{ getStatusLabel(item.status) }}
            </text>
          </view>
          <text class="settlement-amount">¥{{ formatAmount(item.amount) }}</text>
        </view>
      </view>
    </view>

    <!-- 提现按钮 -->
    <view class="actions">
      <button class="btn-primary" @click="goToWithdraw">提现</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { merchantApi } from '@/utils/merchant-api';

const summary = ref({
  monthIncome: 0,
  todayIncome: 0,
  pendingSettlement: 0,
  withdrawn: 0
});

const incomeSources = ref<any[]>([]);
const settlements = ref<any[]>([]);

onMounted(() => load());

async function load() {
  try {
    const res = await merchantApi.get('/api/v2/revenue-share/my-income');
    const data = res.data?.data ?? {};
    summary.value = {
      monthIncome: data.monthIncome || 0,
      todayIncome: data.todayIncome || 0,
      pendingSettlement: data.pendingSettlement || 0,
      withdrawn: data.withdrawn || 0
    };
    incomeSources.value = data.sources || [];
    settlements.value = data.recentSettlements || [];
  } catch (error) {
    uni.showToast({ title: '加载失败', icon: 'none' });
  }
}

function formatAmount(cents: number): string {
  return (cents / 100).toFixed(2);
}

function getStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    'PENDING': '处理中',
    'SUCCESS': '已完成',
    'FAILED': '失败'
  };
  return labels[status] || status;
}

function goToSettlements() {
  uni.navigateTo({ url: '/pages/settlements/settlements' });
}

function goToWithdraw() {
  uni.navigateTo({ url: '/pages/revenue-share/withdraw' });
}
</script>

<style scoped>
.page-root {
  min-height: 100vh;
  background: #f5f5f5;
  padding: 24rpx;
}

.overview-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 24rpx;
  padding: 32rpx;
  color: #fff;
  margin-bottom: 24rpx;
}

.overview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24rpx;
}

.overview-title {
  font-size: 32rpx;
  font-weight: bold;
}

.overview-period {
  font-size: 24rpx;
  opacity: 0.8;
}

.overview-amount {
  display: flex;
  align-items: baseline;
  margin-bottom: 32rpx;
}

.currency {
  font-size: 36rpx;
  margin-right: 8rpx;
}

.amount {
  font-size: 64rpx;
  font-weight: bold;
}

.overview-details {
  display: flex;
  justify-content: space-between;
  padding-top: 24rpx;
  border-top: 1rpx solid rgba(255, 255, 255, 0.2);
}

.detail-item {
  text-align: center;
}

.detail-label {
  font-size: 24rpx;
  opacity: 0.8;
  display: block;
  margin-bottom: 8rpx;
}

.detail-value {
  font-size: 28rpx;
  font-weight: bold;
}

.section {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16rpx;
}

.section-title {
  font-size: 32rpx;
  font-weight: bold;
  color: #333;
  margin-bottom: 16rpx;
}

.section-more {
  font-size: 24rpx;
  color: #666;
}

.source-list, .settlement-list {
  padding: 0;
}

.source-item, .settlement-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}

.source-item:last-child, .settlement-item:last-child {
  border-bottom: none;
}

.source-left, .settlement-left {
  display: flex;
  flex-direction: column;
}

.source-type, .settlement-date {
  font-size: 28rpx;
  color: #333;
  margin-bottom: 8rpx;
}

.source-count, .settlement-status {
  font-size: 24rpx;
  color: #999;
}

.source-amount, .settlement-amount {
  font-size: 32rpx;
  font-weight: bold;
  color: #333;
}

.source-amount {
  color: #52c41a;
}

.status-SUCCESS {
  color: #52c41a;
}

.status-PENDING {
  color: #faad14;
}

.status-FAILED {
  color: #ff4d4f;
}

.actions {
  padding: 32rpx 0;
}

.btn-primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #fff;
  border: none;
  border-radius: 48rpx;
  font-size: 32rpx;
  padding: 24rpx;
}
</style>
