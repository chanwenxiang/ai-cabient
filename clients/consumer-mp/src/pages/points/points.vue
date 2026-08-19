<template>
  <view class="page-root">
    <app-nav-bar title="积分明细" />
    <view class="page-body">
      <view v-if="loading" class="loading"><text>加载中…</text></view>
      <template v-else>
        <view class="summary">
          <view class="summary-main">
            <text class="summary-label">可用积分</text>
            <text class="summary-value">{{ summary?.availablePoints ?? 0 }}</text>
            <text class="summary-sub"
              >累计 {{ summary?.totalPoints ?? 0 }} · 已用 {{ summary?.usedPoints ?? 0 }}</text
            >
          </view>
          <view class="summary-meta">
            <view class="meta-row">
              <text class="meta-label">当前等级</text>
              <text class="meta-value">{{ summary?.levelName || summary?.levelCode || '—' }}</text>
            </view>
            <view class="meta-row">
              <text class="meta-label">积分倍率</text>
              <text class="meta-value">¥1 = {{ summary?.pointsRate ?? 1 }} 积分</text>
            </view>
            <view v-if="summary && summary.nextLevelPointsGap > 0" class="meta-row">
              <text class="meta-label">升级还差</text>
              <text class="meta-value warn">{{ summary.nextLevelPointsGap }} 积分</text>
            </view>
          </view>
        </view>

        <view class="card">
          <view class="card-head">
            <text class="card-title">积分明细</text>
            <text class="card-link" @click="goRedeem">去兑换 ›</text>
          </view>
          <view v-if="!logs.length" class="empty">
            <text class="empty-title">暂无积分记录</text>
            <text class="empty-hint">购物支付后自动返积分，可在结算后查看</text>
          </view>
          <view v-else class="log-list">
            <view v-for="l in logs" :key="l.id" class="log-row">
              <view class="log-main">
                <text class="log-title">{{ l.description || logTypeText(l.pointsType) }}</text>
                <text class="log-time">{{ formatTime(l.createdAt) }}</text>
              </view>
              <text class="log-points" :class="l.points >= 0 ? 'income' : 'outcome'">{{
                l.points >= 0 ? `+${l.points}` : l.points
              }}</text>
            </view>
          </view>
        </view>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import {
  consumerApi,
  ensureConsumerAuth,
  type MemberPointsLogDto,
  type MemberPointsSummaryDto
} from '@/utils/consumer-api';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';

const loading = ref(false);
const summary = ref<MemberPointsSummaryDto | null>(null);
const logs = ref<MemberPointsLogDto[]>([]);

onShow(async () => {
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/points/points')
    });
    return;
  }
  await load();
});

async function load() {
  loading.value = true;
  try {
    const [s, list] = await Promise.all([
      consumerApi.memberPoints(),
      consumerApi.memberPointsLog(100)
    ]);
    summary.value = s;
    logs.value = list;
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

function logTypeText(t: string) {
  if (t === 'EARN') return '积分获得';
  if (t === 'USE') return '积分使用';
  if (t === 'EXPIRE') return '积分过期';
  return '积分记录';
}

function formatTime(t: string) {
  return formatDateTimeMinute(t, '—');
}

function goRedeem() {
  uni.navigateTo({ url: '/pages/points/redeem' });
}
</script>

<style scoped>
.page-root {
  min-height: 100%;
  padding: 0;
  background: #ffffff;
  box-sizing: border-box;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.loading {
  padding: 120rpx 0;
  text-align: center;
  color: #8a968e;
}
.summary {
  display: flex;
  justify-content: space-between;
  gap: 20rpx;
  padding: 32rpx;
  border-radius: 28rpx;
  color: #fff;
  background: linear-gradient(135deg, #064e3b 0%, #059669 55%, #059669 100%);
  box-shadow: 0 16rpx 40rpx rgba(5, 150, 105, 0.24);
}
.summary-label {
  display: block;
  font-size: 24rpx;
  opacity: 0.85;
}
.summary-value {
  display: block;
  margin-top: 6rpx;
  font-size: 64rpx;
  font-weight: 800;
  line-height: 1;
}
.summary-sub {
  display: block;
  margin-top: 12rpx;
  font-size: 22rpx;
  opacity: 0.8;
}
.summary-meta {
  min-width: 210rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.14);
}
.meta-row {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
  padding: 6rpx 0;
  font-size: 22rpx;
}
.meta-label {
  opacity: 0.8;
}
.meta-value.warn {
  color: #fde68a;
}
.card {
  margin-top: 24rpx;
  padding: 28rpx 24rpx;
  border-radius: 24rpx;
  background: #fff;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16rpx;
}
.card-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #1b3027;
}
.card-link {
  font-size: 24rpx;
  color: #059669;
}
.empty {
  padding: 48rpx 0 32rpx;
  text-align: center;
}
.empty-title {
  display: block;
  font-size: 26rpx;
  color: #4b5563;
}
.empty-hint {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #9aa4a0;
}
.log-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f0f2f1;
}
.log-row:last-child {
  border-bottom: none;
}
.log-title {
  display: block;
  font-size: 26rpx;
  color: #1f2a24;
}
.log-time {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #9aa4a0;
}
.log-points {
  font-size: 30rpx;
  font-weight: 700;
}
.log-points.income {
  color: #059669;
}
.log-points.outcome {
  color: #dc2626;
}
</style>
