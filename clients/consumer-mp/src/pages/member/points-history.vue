<template>
  <view class="page">
    <view class="summary">
      <view class="sum-item">
        <text class="sum-num">{{ summary?.availablePoints ?? 0 }}</text>
        <text class="sum-label">可用积分</text>
      </view>
      <view class="sum-item">
        <text class="sum-num">{{ summary?.earnedThisMonth ?? 0 }}</text>
        <text class="sum-label">本月获得</text>
      </view>
      <view class="sum-item">
        <text class="sum-num">{{ summary?.usedThisMonth ?? 0 }}</text>
        <text class="sum-label">本月使用</text>
      </view>
    </view>

    <view class="tabs">
      <view v-for="t in tabs" :key="t.key" class="tab" :class="{ on: tab === t.key }" @click="tab = t.key">{{ t.label }}</view>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!logs.length" class="empty">暂无积分记录</view>
    <view v-else class="list">
      <view v-for="row in logs" :key="row.id" class="row">
        <view>
          <text class="row-title">{{ row.description || typeLabel(row.pointsType) }}</text>
          <text class="row-time">{{ formatTime(row.createdAt) }}</text>
        </view>
        <text class="row-points" :class="{ plus: row.points > 0 }">{{ row.points > 0 ? '+' : '' }}{{ row.points }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import {
  consumerApi,
  ensureConsumerAuth,
  type MemberPointsLogDto,
  type MemberPointsSummaryDto
} from '@/utils/consumer-api';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';

const tabs = [
  { key: '', label: '全部' },
  { key: 'EARN', label: '获得' },
  { key: 'USE', label: '使用' },
  { key: 'EXPIRE', label: '过期' }
];

const tab = ref('');
const loading = ref(false);
const summary = ref<MemberPointsSummaryDto | null>(null);
const logs = ref<MemberPointsLogDto[]>([]);

onShow(async () => {
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/member/points-history') });
    return;
  }
  await loadSummary();
  await loadLogs();
});

watch(tab, () => loadLogs());

async function loadSummary() {
  try {
    summary.value = await consumerApi.memberPointsSummary();
  } catch {
    summary.value = null;
  }
}

async function loadLogs() {
  loading.value = true;
  try {
    logs.value = await consumerApi.memberPointsHistory(tab.value || undefined);
  } catch {
    logs.value = [];
  } finally {
    loading.value = false;
  }
}

function typeLabel(t: string) {
  return ({ EARN: '获得积分', USE: '使用积分', EXPIRE: '积分过期' } as Record<string, string>)[t] || t;
}

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '');
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f7f8; padding: 24rpx; }
.summary {
  display: flex;
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx 12rpx;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.sum-item { flex: 1; text-align: center; }
.sum-num { display: block; font-size: 36rpx; font-weight: 800; color: #059669; }
.sum-label { display: block; margin-top: 8rpx; font-size: 22rpx; color: #849087; }
.tabs {
  display: flex;
  margin: 20rpx 0;
  padding: 6rpx;
  border-radius: 16rpx;
  background: #fff;
}
.tab { flex: 1; text-align: center; padding: 16rpx 0; font-size: 26rpx; color: #666; border-radius: 12rpx; }
.tab.on { background: #ecfdf5; color: #059669; font-weight: 700; }
.list { background: #fff; border-radius: 20rpx; padding: 0 24rpx; }
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 26rpx 0;
  border-bottom: 1rpx solid #f0f2f1;
}
.row:last-child { border-bottom: 0; }
.row-title { display: block; font-size: 28rpx; color: #223029; font-weight: 600; }
.row-time { display: block; margin-top: 6rpx; font-size: 22rpx; color: #99a39c; }
.row-points { font-size: 32rpx; font-weight: 800; color: #334155; }
.row-points.plus { color: #059669; }
.empty { text-align: center; padding: 80rpx 0; color: #999; font-size: 26rpx; }
</style>
