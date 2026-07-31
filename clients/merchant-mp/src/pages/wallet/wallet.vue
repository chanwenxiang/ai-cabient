<template>
  <view class="page-root">
    <view v-if="loadError" class="banner-err">
      <text>{{ loadError }}</text>
      <text class="banner-retry" @click="load">重试</text>
    </view>

    <view v-if="!loading && overview && !overview.bound" class="empty-card">
      <text class="empty-title">暂无商户钱包</text>
      <text class="empty-desc">当前账号未绑定开通经营工具的商户，无法查看可提现余额。</text>
    </view>

    <template v-else-if="overview && overview.bound">
      <view class="summary-card">
        <text class="role-tag">商户 · 可自主提现</text>
        <text class="name">{{ overview.merchantName }} · {{ overview.merchantId }}</text>
        <view class="bal-row">
          <text class="bal-label">可用余额</text>
          <text class="bal-value">¥{{ yuan(overview.availableCents) }}</text>
        </view>
        <view class="bal-sub">
          <text>账面 ¥{{ yuan(overview.balanceCents) }}</text>
          <text>冻结 ¥{{ yuan(overview.frozenCents) }}</text>
        </view>
      </view>

      <view class="action-card">
        <input class="amount-input" type="digit" v-model="amountYuan" placeholder="提现金额（元）" />
        <button class="btn-primary" :disabled="submitting" @click="submitWithdraw">申请提现</button>
        <text class="tip">分账入账后可提现；演示环境默认 Mock 打款。大额需运营审核。</text>
      </view>

      <view class="section">
        <text class="section-title">最近提现</text>
        <view v-for="w in overview.recentWithdraws || []" :key="w.requestId" class="row-item">
          <view class="row-main">
            <text>¥{{ yuan(w.amountCents) }}</text>
            <text class="status">{{ w.status }}</text>
          </view>
          <text class="row-sub">{{ w.requestNo }}</text>
        </view>
        <view v-if="!(overview.recentWithdraws || []).length" class="muted">暂无提现记录</view>
      </view>

      <view class="section">
        <text class="section-title">最近流水</text>
        <view v-for="l in overview.recentLedgers || []" :key="l.ledgerId" class="row-item">
          <view class="row-main">
            <text>{{ l.entryType }}</text>
            <text>{{ yuan(l.amountCents) }}</text>
          </view>
          <text class="row-sub">{{ l.remark || '' }}</text>
        </view>
      </view>
    </template>

    <view v-if="loading" class="loading-inline">加载中…</view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { merchantApi, getToken, handleUnauthorized } from '@/utils/merchant-api';

const loading = ref(false);
const submitting = ref(false);
const loadError = ref('');
const amountYuan = ref('');
const overview = ref<any>(null);

function yuan(cents?: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

async function load() {
  if (!getToken()) {
    handleUnauthorized();
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    overview.value = await merchantApi.wallet();
  } catch (e: any) {
    loadError.value = e?.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

async function submitWithdraw() {
  const yuanNum = Number(amountYuan.value);
  if (!yuanNum || yuanNum <= 0) {
    uni.showToast({ title: '请输入金额', icon: 'none' });
    return;
  }
  submitting.value = true;
  try {
    await merchantApi.walletWithdraw({
      amountCents: Math.round(yuanNum * 100),
      requestNo: 'MW-' + Date.now()
    });
    uni.showToast({ title: '已提交', icon: 'success' });
    amountYuan.value = '';
    await load();
  } catch (e: any) {
    uni.showToast({ title: e?.message || '提交失败', icon: 'none' });
  } finally {
    submitting.value = false;
  }
}

onShow(load);
</script>

<style scoped>
.page-root { padding: 24rpx; }
.summary-card, .action-card, .empty-card, .section {
  background: #fff;
  border-radius: 16rpx;
  padding: 28rpx;
  margin-bottom: 20rpx;
}
.role-tag { font-size: 22rpx; color: #0d9488; }
.name { display: block; margin-top: 8rpx; color: #64748b; font-size: 24rpx; }
.bal-row { display: flex; justify-content: space-between; align-items: baseline; margin-top: 24rpx; }
.bal-label { color: #64748b; }
.bal-value { font-size: 48rpx; font-weight: 700; color: #0f172a; }
.bal-sub { display: flex; gap: 24rpx; margin-top: 12rpx; color: #94a3b8; font-size: 22rpx; }
.amount-input {
  background: #f8fafc;
  border-radius: 12rpx;
  padding: 20rpx;
  margin-bottom: 16rpx;
}
.btn-primary {
  background: #0d9488;
  color: #fff;
  border-radius: 12rpx;
}
.tip { display: block; margin-top: 12rpx; font-size: 22rpx; color: #94a3b8; }
.section-title { font-weight: 600; margin-bottom: 12rpx; display: block; }
.row-item { padding: 16rpx 0; border-bottom: 1px solid #f1f5f9; }
.row-main { display: flex; justify-content: space-between; }
.row-sub { font-size: 22rpx; color: #94a3b8; }
.status { color: #0d9488; }
.empty-title { font-weight: 600; display: block; margin-bottom: 8rpx; }
.empty-desc { color: #64748b; font-size: 24rpx; line-height: 1.5; }
.banner-err { background: #fef2f2; color: #b91c1c; padding: 16rpx; border-radius: 12rpx; margin-bottom: 16rpx; }
.banner-retry { margin-left: 16rpx; text-decoration: underline; }
.loading-inline { text-align: center; color: #94a3b8; padding: 40rpx; }
.muted { color: #94a3b8; font-size: 24rpx; }
</style>
