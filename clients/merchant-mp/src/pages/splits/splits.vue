<template>
  <view class="page">
    <app-nav-bar title="分账明细" />
    <view class="page-body">
      <view class="tabs">
        <text role="button" class="tab" :class="{ active: tab === 'FAILED' }" @click="switchTab('FAILED')"
          >失败</text
        >
        <text role="button" class="tab" :class="{ active: tab === 'ALL' }" @click="switchTab('ALL')">全部</text>
      </view>

      <view v-if="loading && !list.length" class="card state">{{ UI_COPY.loading }}</view>
      <view v-else-if="error && !list.length" class="card state">
        <text class="err">{{ error }}</text>
        <app-button label="重试" @click="load" />
      </view>
      <empty-state
        v-else-if="!list.length"
        icon="/static/menu/splits.png"
        :title="tab === 'FAILED' ? '暂无分账异常' : '暂无分账记录'"
        hint="订单分账后会出现在这里；失败单请核对微信收款账户"
      />
      <view v-else>
        <view v-for="s in list" :key="s.splitId" class="card item">
          <view class="head">
            <text class="tag" :class="statusClass(s.status)">{{ statusLabel(s.status) }}</text>
            <text class="time">{{ formatTime(s.createdAt) }}</text>
          </view>
          <text class="title">订单 {{ displayBizNo(s.orderId) }}</text>
          <text class="meta"
            >柜机 {{ emptyDisplay(s.deviceName || s.deviceId, 'device') }} · 商户所得 ¥{{
              money(s.merchantCents)
            }}</text
          >
          <text class="meta"
            >毛额 ¥{{ money(s.grossCents) }} · 平台 ¥{{ money(s.platformCents) }}</text
          >
          <text v-if="s.wechatOutOrderNo" class="meta">外部单 {{ s.wechatOutOrderNo }}</text>
          <text v-if="s.failureReason" class="fail">失败原因：{{ s.failureReason }}</text>
        </view>
      </view>
    </view></view
  >
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { displayLabel } from '@aicabinet/shared-dict';
import { emptyDisplay, displayBizNo, formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import type { MerchantMe, RevenueSplit } from '@aicabinet/shared-types';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

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
  return formatDateTimeMinute(t, '暂无');
}

function statusLabel(status?: string) {
  return displayLabel('split_status', status, '未知状态');
}

function statusClass(status?: string) {
  const s = String(status || '').toUpperCase();
  if (s === 'WECHAT_FAILED' || s === 'FAILED') return 'fail';
  if (s === 'SUCCESS' || s === 'SETTLED') return 'ok';
  if (s === 'LEDGER_ONLY') return 'warn';
  return 'warn';
}

async function load() {
  if (!list.value.length) loading.value = true;
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
  } catch (e) {
    if (!uni.getStorageSync('merchant_token')) return;
    seedMerchantMeDisplayCache(me);
    list.value = [];
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.page {
  padding: 0;
  min-height: 100vh;
  box-sizing: border-box;
}
.tabs {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.tab {
  padding: 12rpx 28rpx;
  border-radius: var(--radius-pill);
  background: var(--card-bg, #fff);
  color: var(--text-muted);
  font-size: var(--font-size-body);
  border: 1rpx solid var(--color-border);
}
.tab.active {
  background: var(--brand);
  color: #fff;
  border-color: var(--brand);
  font-weight: 650;
}
.card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  padding: 28rpx;
  margin-bottom: 16rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
}
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  color: var(--text-muted);
}
.err {
  color: var(--color-danger);
}
.retry {
  background: var(--brand);
  color: #fff;
  border: none;
}
.head {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 10rpx;
}
.tag {
  font-size: var(--font-size-sm);
  line-height: 1;
  padding: 8rpx 12rpx;
  border-radius: var(--radius-pill);
  font-weight: 600;
}
.tag.fail {
  color: var(--color-danger);
  background: color-mix(in srgb, var(--danger, #b91c1c) 12%, #fff);
}
.tag.ok {
  color: var(--brand);
  background: var(--brand-soft, #d1fae5);
}
.tag.warn {
  color: var(--warning, #b45309);
  background: color-mix(in srgb, var(--warning, #b45309) 14%, #fff);
}
.time {
  margin-left: auto;
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
}
.title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 650;
  color: var(--brand-deep);
}
.meta {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
}
.fail {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-caption);
  color: var(--color-danger);
  line-height: 1.5;
}
.page-body {
  padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
