<template>
  <view class="page">
    <view class="tabs">
      <text class="tab" :class="{ active: tab === 'FAILED' }" @click="switchTab('FAILED')">失败</text>
      <text class="tab" :class="{ active: tab === 'ALL' }" @click="switchTab('ALL')">全部</text>
    </view>

    <view v-if="loading" class="card state">加载中…</view>
    <view v-else-if="error" class="card state">
      <text class="err">{{ error }}</text>
      <button class="retry" size="mini" @click="load">重试</button>
    </view>
    <empty-state
      v-else-if="!list.length"
      icon="💱"
      :title="tab === 'FAILED' ? '暂无分账异常' : '暂无分账记录'"
      hint="订单分账后会出现在这里；失败单请核对微信收款账户"
    />
    <view v-else>
      <view v-for="s in list" :key="s.splitId" class="card item">
        <view class="head">
          <text class="tag" :class="statusClass(s.status)">{{ statusLabel(s.status) }}</text>
          <text class="time">{{ formatTime(s.createdAt) }}</text>
        </view>
        <text class="title">订单 {{ s.orderId }}</text>
        <text class="meta">柜机 {{ s.deviceId || '—' }} · 商户所得 ¥{{ money(s.merchantCents) }}</text>
        <text v-if="s.failureReason" class="fail">失败原因：{{ s.failureReason }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import type { MerchantMe, RevenueSplit } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const loading = ref(true);
const error = ref('');
const tab = ref<'FAILED' | 'ALL'>('FAILED');
const list = ref<RevenueSplit[]>([]);

onLoad((query) => {
  const status = String(query?.status || '').toUpperCase();
  if (status === 'ALL') tab.value = 'ALL';
  else tab.value = 'FAILED';
});

onShow(() => {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  void load();
});

onPullDownRefresh(async () => {
  try {
    await load();
  } finally {
    uni.stopPullDownRefresh();
  }
});

function switchTab(next: 'FAILED' | 'ALL') {
  if (tab.value === next) return;
  tab.value = next;
  void load();
}

function money(cents = 0) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '');
}

function statusLabel(status?: string) {
  const s = String(status || '').toUpperCase();
  if (s === 'WECHAT_FAILED' || s === 'FAILED') return '失败';
  if (s === 'SUCCESS' || s === 'SETTLED') return '成功';
  if (s === 'PENDING' || s === 'ACCRUED') return '待分账';
  if (s === 'WECHAT_SUBMITTED' || s === 'SUBMITTED') return '已提交';
  return status || '—';
}

function statusClass(status?: string) {
  const s = String(status || '').toUpperCase();
  if (s === 'WECHAT_FAILED' || s === 'FAILED') return 'fail';
  if (s === 'SUCCESS' || s === 'SETTLED') return 'ok';
  return 'warn';
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    await refreshMe();
    if (!hasPerm(me.value, 'merchant:splits:list')) {
      error.value = '无分账明细权限';
      list.value = [];
      return;
    }
    if (tab.value === 'ALL') {
      const res = await merchantApi.revenueSplits(0, 100);
      list.value = res?.items || [];
    } else {
      const [a, b] = await Promise.all([
        merchantApi.revenueSplits(0, 50, 'WECHAT_FAILED'),
        merchantApi.revenueSplits(0, 50, 'FAILED')
      ]);
      const merged = [...(a?.items || []), ...(b?.items || [])];
      const seen = new Set<string>();
      list.value = merged
        .filter((x) => {
          if (!x?.splitId || seen.has(x.splitId)) return false;
          seen.add(x.splitId);
          return true;
        })
        .sort((x, y) => String(y.createdAt || '').localeCompare(String(x.createdAt || '')));
    }
  } catch (e: any) {
    if (!uni.getStorageSync('merchant_token')) return;
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
    list.value = [];
    error.value = e?.message || '加载失败';
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.page { padding: 24rpx; min-height: 100vh; box-sizing: border-box; }
.tabs {
  display: flex; gap: 12rpx; margin-bottom: 16rpx;
}
.tab {
  padding: 12rpx 28rpx; border-radius: 999rpx; background: #fff; color: #64748b; font-size: 26rpx;
  border: 1rpx solid #e2e8f0;
}
.tab.active { background: #0f766e; color: #fff; border-color: #0f766e; font-weight: 650; }
.card {
  background: #fff; border-radius: 20rpx; padding: 28rpx; margin-bottom: 16rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
}
.state { display: flex; flex-direction: column; align-items: center; gap: 16rpx; color: #64748b; }
.err { color: #b91c1c; }
.retry { background: #0f766e; color: #fff; border: none; }
.head { display: flex; align-items: center; gap: 12rpx; margin-bottom: 10rpx; }
.tag {
  font-size: 22rpx; line-height: 1; padding: 8rpx 12rpx; border-radius: 999rpx; font-weight: 600;
}
.tag.fail { color: #b91c1c; background: #fee2e2; }
.tag.ok { color: #047857; background: #d1fae5; }
.tag.warn { color: #b45309; background: #fef3c7; }
.time { margin-left: auto; color: #94a3b8; font-size: 22rpx; }
.title { display: block; font-size: 30rpx; font-weight: 650; color: #134e4a; }
.meta { display: block; margin-top: 8rpx; font-size: 24rpx; color: #64748b; }
.fail { display: block; margin-top: 12rpx; font-size: 24rpx; color: #b91c1c; line-height: 1.5; }
</style>
