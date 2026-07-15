<template>
  <view class="page-root">
    <view class="balance-card">
      <text class="bal-label">当前余额</text>
      <text class="bal-amount">¥{{ balanceYuan }}</text>
    </view>

    <view class="amount-grid">
      <view v-for="item in amounts" :key="item.value"
        class="amount-card"
        :class="{ selected: selectedAmount === item.value }"
        @click="selectedAmount = item.value">
        <text class="amount-value">¥{{ item.text }}</text>
        <text v-if="item.bonus" class="amount-bonus">赠¥{{ item.bonus }}</text>
      </view>
    </view>

    <button class="btn-primary" :disabled="!selectedAmount || loading" :loading="loading" @click="onRecharge">
      {{ loading ? '充值中…' : '确认充值' }}
    </button>

    <view class="recharge-list">
      <text class="section-title">充值记录</text>
      <view v-if="!records.length" class="empty">暂无充值记录</view>
      <view v-for="r in records" :key="r.orderId" class="record-row">
        <view>
          <text class="record-amount">¥{{ (r.amountCents / 100).toFixed(2) }}</text>
          <text class="record-time">{{ formatTime(r.createdAt) }}</text>
        </view>
        <text class="record-status" :class="r.status">{{ statusText(r.status) }}</text>
      </view>
    </view>

    <view class="note">充值金额将存入账户余额，用于关门自动扣款</view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { get, post } from '@/utils/consumer-api';

const amounts = [
  { value: 1000, text: '10', bonus: '0' },
  { value: 2000, text: '20', bonus: '2' },
  { value: 5000, text: '50', bonus: '5' },
  { value: 10000, text: '100', bonus: '10' },
  { value: 20000, text: '200', bonus: '20' },
];

const balanceYuan = ref('0.00');
const balanceCents = ref(0);
const selectedAmount = ref(0);
const loading = ref(false);
const records = ref<any[]>([]);
const showManual = ref(false);
const manualAmount = ref('');

onShow(() => { loadBalance(); loadRecords(); });

async function loadBalance() {
  try {
    const res = await get('/api/v2/account/balance');
    balanceCents.value = res.data.balanceCents ?? 0;
    balanceYuan.value = (balanceCents.value / 100).toFixed(2);
  } catch {}
}

async function loadRecords() {
  try {
    const res = await get('/api/v2/recharges');
    records.value = res.data ?? [];
  } catch { records.value = []; }
}

function formatTime(t: string) {
  if (!t) return '';
  return t.substring(0, 16).replace('T', ' ');
}

function statusText(s: string) {
  const map: Record<string, string> = { PENDING: '待支付', PAID: '已完成', REFUNDED: '已退款', FAILED: '失败' };
  return map[s] || s;
}

async function onRecharge() {
  if (!selectedAmount.value || loading.value) return;
  loading.value = true;
  try {
    const res = await post('/api/v2/recharges', { amountCents: selectedAmount.value });
    const order = res.data;
    if (order.payUrl) {
      uni.navigateTo({ url: `/pages/webview/webview?url=${encodeURIComponent(order.payUrl)}` });
    } else {
      uni.showToast({ title: '充值成功', icon: 'success' });
      loadBalance();
      loadRecords();
    }
  } catch (e: any) {
    uni.showToast({ title: e?.message || '充值失败', icon: 'error' });
  } finally { loading.value = false; }
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f7f7f7; min-height: 100vh; }
.balance-card { background: linear-gradient(135deg, #07c160, #06ad56); border-radius: 20rpx; padding: 40rpx; text-align: center; margin-bottom: 30rpx; }
.bal-label { color: rgba(255,255,255,.8); font-size: 28rpx; }
.bal-amount { color: #fff; font-size: 72rpx; font-weight: 700; margin-top: 10rpx; }
.amount-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20rpx; margin-bottom: 30rpx; }
.amount-card { background: #fff; border-radius: 16rpx; padding: 30rpx 20rpx; text-align: center; border: 2rpx solid #eee; }
.amount-card.selected { border-color: #07c160; background: #f0fff4; }
.amount-value { font-size: 40rpx; font-weight: 700; color: #333; }
.amount-bonus { font-size: 22rpx; color: #ff6b35; margin-top: 8rpx; display: block; }
.btn-primary { width: 100%; height: 88rpx; line-height: 88rpx; background: #07c160; color: #fff; border-radius: 44rpx; font-size: 32rpx; border: none; margin-bottom: 30rpx; }
.recharge-list { background: #fff; border-radius: 16rpx; padding: 24rpx; }
.section-title { font-size: 28rpx; font-weight: 600; color: #333; margin-bottom: 16rpx; display: block; }
.empty { text-align: center; color: #999; padding: 40rpx; font-size: 26rpx; }
.record-row { display: flex; justify-content: space-between; align-items: center; padding: 20rpx 0; border-bottom: 1rpx solid #f0f0f0; }
.record-amount { font-size: 30rpx; font-weight: 600; display: block; }
.record-time { font-size: 22rpx; color: #999; margin-top: 4rpx; display: block; }
.record-status { font-size: 24rpx; padding: 4rpx 12rpx; border-radius: 8rpx; }
.record-status.PAID { color: #07c160; background: #f0fff4; }
.record-status.PENDING { color: #ff9500; background: #fff8e8; }
.record-status.FAILED, .record-status.REFUNDED { color: #ff3b30; background: #fff0ee; }
.note { text-align: center; font-size: 24rpx; color: #999; margin-top: 24rpx; padding-bottom: 40rpx; }
</style>
