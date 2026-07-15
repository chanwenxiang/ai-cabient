<template>
  <view class="page-root">
    <view class="header">
      <text class="title">申请提现</text>
    </view>

    <!-- 可提现金额 -->
    <view class="balance-card">
      <text class="balance-label">可提现金额</text>
      <view class="balance-amount">
        <text class="currency">¥</text>
        <text class="amount">{{ formatAmount(balance) }}</text>
      </view>
    </view>

    <!-- 提现金额输入 -->
    <view class="input-section">
      <text class="input-label">提现金额</text>
      <view class="input-wrapper">
        <text class="input-prefix">¥</text>
        <input 
          type="digit" 
          class="input-field"
          :value="withdrawAmount"
          @input="onAmountInput"
          placeholder="请输入提现金额"
        />
      </view>
      <view class="input-actions">
        <text class="quick-action" @click="setAmount('all')">全部提现</text>
      </view>
    </view>

    <!-- 提现方式 -->
    <view class="withdraw-method">
      <text class="method-title">提现方式</text>
      <radio-group @change="onMethodChange">
        <view class="method-item">
          <radio value="wechat" :checked="method === 'wechat'" color="#667eea" />
          <view class="method-info">
            <text class="method-name">微信零钱</text>
            <text class="method-desc">实时到账</text>
          </view>
        </view>
        <view class="method-item">
          <radio value="bank" :checked="method === 'bank'" color="#667eea" />
          <view class="method-info">
            <text class="method-name">银行卡</text>
            <text class="method-desc">1-3个工作日</text>
          </view>
        </view>
      </radio-group>
    </view>

    <!-- 提示信息 -->
    <view class="tips">
      <text class="tip-item">• 单笔提现最低10元</text>
      <text class="tip-item">• 每日最多提现3次</text>
      <text class="tip-item">• 提现手续费：{{ feeRate }}%</text>
    </view>

    <!-- 实际到账 -->
    <view class="actual-section">
      <text class="actual-label">实际到账</text>
      <text class="actual-amount">¥{{ formatAmount(actualAmount) }}</text>
    </view>

    <!-- 提交按钮 -->
    <view class="actions">
      <button 
        class="btn-primary" 
        :disabled="!canSubmit"
        @click="submitWithdraw"
      >
        确认提现
      </button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { merchantApi } from '@/utils/merchant-api';

const balance = ref(0);
const withdrawAmount = ref('');
const method = ref('wechat');
const feeRate = ref(0.6); // 0.6%手续费
const loading = ref(false);

const actualAmount = computed(() => {
  const amount = parseFloat(withdrawAmount.value) || 0;
  const fee = amount * feeRate.value / 100;
  return Math.max(0, amount - fee) * 100; // 转换为分
});

const canSubmit = computed(() => {
  const amount = parseFloat(withdrawAmount.value) || 0;
  return amount >= 10 && amount <= balance.value && !loading.value;
});

function formatAmount(cents: number): string {
  return (cents / 100).toFixed(2);
}

function onAmountInput(e: any) {
  withdrawAmount.value = e.detail.value;
}

function setAmount(type: string) {
  if (type === 'all') {
    withdrawAmount.value = (balance.value / 100).toFixed(2);
  }
}

function onMethodChange(e: any) {
  method.value = e.detail.value;
}

async function submitWithdraw() {
  if (!canSubmit.value) return;

  loading.value = true;
  try {
    const amountCents = Math.round(parseFloat(withdrawAmount.value) * 100);
    await merchantApi.post('/api/v2/revenue-share/withdraw', {
      amountCents,
      method: method.value,
      idempotencyKey: withdraw-
    });

    uni.showToast({ title: '提现申请已提交', icon: 'success' });
    setTimeout(() => {
      uni.navigateBack();
    }, 1500);
  } catch (error: any) {
    uni.showToast({ 
      title: error.response?.data?.message || '提现失败', 
      icon: 'none' 
    });
  } finally {
    loading.value = false;
  }
}

async function loadBalance() {
  try {
    const res = await merchantApi.get('/api/v2/revenue-share/my-income');
    balance.value = res.data?.data?.pendingSettlement || 0;
  } catch (error) {
    console.error('加载余额失败', error);
  }
}

loadBalance();
</script>

<style scoped>
.page-root {
  min-height: 100vh;
  background: #f5f5f5;
  padding: 24rpx;
}

.header {
  padding: 24rpx 0;
}

.title {
  font-size: 36rpx;
  font-weight: bold;
  color: #333;
}

.balance-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 24rpx;
  padding: 48rpx 32rpx;
  color: #fff;
  text-align: center;
  margin-bottom: 24rpx;
}

.balance-label {
  font-size: 28rpx;
  opacity: 0.8;
  display: block;
  margin-bottom: 16rpx;
}

.balance-amount {
  display: flex;
  justify-content: center;
  align-items: baseline;
}

.currency {
  font-size: 36rpx;
  margin-right: 8rpx;
}

.amount {
  font-size: 72rpx;
  font-weight: bold;
}

.input-section {
  background: #fff;
  border-radius: 16rpx;
  padding: 32rpx;
  margin-bottom: 24rpx;
}

.input-label {
  font-size: 28rpx;
  color: #666;
  display: block;
  margin-bottom: 16rpx;
}

.input-wrapper {
  display: flex;
  align-items: center;
  border-bottom: 2rpx solid #f0f0f0;
  padding-bottom: 16rpx;
}

.input-prefix {
  font-size: 48rpx;
  color: #333;
  margin-right: 16rpx;
}

.input-field {
  flex: 1;
  font-size: 48rpx;
  color: #333;
}

.input-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 16rpx;
}

.quick-action {
  font-size: 24rpx;
  color: #667eea;
}

.withdraw-method {
  background: #fff;
  border-radius: 16rpx;
  padding: 32rpx;
  margin-bottom: 24rpx;
}

.method-title {
  font-size: 28rpx;
  color: #666;
  display: block;
  margin-bottom: 24rpx;
}

.method-item {
  display: flex;
  align-items: center;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}

.method-item:last-child {
  border-bottom: none;
}

.method-info {
  display: flex;
  flex-direction: column;
  margin-left: 16rpx;
}

.method-name {
  font-size: 28rpx;
  color: #333;
}

.method-desc {
  font-size: 24rpx;
  color: #999;
  margin-top: 8rpx;
}

.tips {
  padding: 24rpx;
  margin-bottom: 24rpx;
}

.tip-item {
  font-size: 24rpx;
  color: #999;
  display: block;
  line-height: 1.8;
}

.actual-section {
  background: #fff;
  border-radius: 16rpx;
  padding: 32rpx;
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24rpx;
}

.actual-label {
  font-size: 28rpx;
  color: #666;
}

.actual-amount {
  font-size: 40rpx;
  font-weight: bold;
  color: #52c41a;
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

.btn-primary[disabled] {
  opacity: 0.5;
}
</style>
