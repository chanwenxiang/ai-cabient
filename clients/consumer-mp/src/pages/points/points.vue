<template>
  <view class="page-root">
    <app-nav-bar title="积分明细" />
    <view class="page-body">
      <view v-if="loading && !summary" class="loading"
        ><text>{{ UI_COPY.loading }}</text></view
      >
      <template v-else>
        <view class="summary">
          <view class="summary-main">
            <text class="summary-label">可用积分</text>
            <text class="summary-value">{{ summary?.availablePoints ?? 0 }}</text>
            <text class="summary-sub"
              >累计 {{ summary?.totalPoints ?? 0 }} · 已用 {{ summary?.usedPoints ?? 0
              }}{{
                summary && summary.expiredPoints > 0 ? ` · 已过期 ${summary.expiredPoints}` : ''
              }}</text
            >
          </view>
          <view class="summary-meta">
            <view class="meta-row">
              <text class="meta-label">当前等级</text>
              <text class="meta-value">{{
                summary?.levelName ||
                (summary?.levelCode && !/^[A-Z0-9_]+$/.test(String(summary.levelCode))
                  ? summary.levelCode
                  : '') ||
                '普通会员'
              }}</text>
            </view>
            <view class="meta-row">
              <text class="meta-label">积分倍率</text>
              <text class="meta-value">¥1 = {{ summary?.pointsRate ?? 1 }} 积分</text>
            </view>
            <view v-if="summary && summary.nextLevelPointsGap > 0" class="meta-row">
              <text class="meta-label">升级还差</text>
              <text class="meta-value warn">{{ summary.nextLevelPointsGap }} 积分</text>
            </view>
            <view class="meta-row tip">
              <text class="meta-label">说明</text>
              <text class="meta-value tip">购物获积分，兑换券有门槛与有效期</text>
            </view>
          </view>
        </view>

        <view class="card">
          <view class="card-head">
            <text class="card-title">积分明细</text>
            <text role="button" class="card-link app-link-chevron" @click="goRedeem">去兑换</text>
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
                <text v-if="l.expireAt && l.points > 0" class="log-expire"
                  >有效至 {{ formatTime(l.expireAt) }}</text
                >
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
import { showError } from '@/utils/notify';
import { onShow } from '@dcloudio/uni-app';
import {
  consumerApi,
  ensureConsumerAuth,
  type MemberPointsLogDto,
  type MemberPointsSummaryDto
} from '@/utils/consumer-api';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

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
  if (!summary.value) loading.value = true;
  try {
    const [s, list] = await Promise.all([
      consumerApi.memberPoints(),
      consumerApi.memberPointsLog(100)
    ]);
    summary.value = s;
    logs.value = list;
  } catch (e) {
    showError(e instanceof Error ? e.message : '加载失败');
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
  return formatDateTimeMinute(t, '暂无');
}

function goRedeem() {
  uni.navigateTo({ url: '/pages/points/redeem' });
}
</script>

<style scoped>
.page-root {
  min-height: 100%;
  padding: 0;
  background: var(--card-bg, #ffffff);
  box-sizing: border-box;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.loading {
  padding: 120rpx 0;
  text-align: center;
  color: var(--text-muted, #8a968e);
}
.summary {
  display: flex;
  justify-content: space-between;
  gap: 20rpx;
  padding: 32rpx;
  border-radius: var(--radius-card);
  color: var(--text-primary, #14201b);
  background: linear-gradient(135deg, var(--brand-soft), var(--white));
  border: 1rpx solid var(--brand-soft, #d1fae5);
  box-shadow: none;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  margin: 0;
}
.summary-main {
  flex: 1;
  min-width: 0;
  text-align: center;
}
.summary-label {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  text-align: center;
}
.summary-value {
  display: block;
  margin-top: 6rpx;
  font-size: 64rpx;
  font-weight: 800;
  line-height: 1;
  color: var(--brand);
  text-align: center;
}
.summary-sub {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
  text-align: center;
}
.summary-meta {
  min-width: 210rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: var(--brand-soft, #f0fdf4);
}
.meta-row {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
  padding: 6rpx 0;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #334155);
}
.meta-label {
  color: var(--text-muted, #849087);
}
.meta-value.warn {
  color: var(--warning, #b45309);
}
.meta-row.tip .meta-value.tip {
  color: var(--text-muted, #849087);
  font-size: var(--font-size-xs);
  text-align: right;
  max-width: 140rpx;
  line-height: 1.35;
}
.card {
  margin: 24rpx 0 0;
  padding: 28rpx 24rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16rpx;
}
.card-title {
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #1b3027);
}
.card-link {
  font-size: var(--font-size-caption);
  color: var(--brand);
}
.empty {
  padding: 48rpx 0 32rpx;
  text-align: center;
}
.empty-title {
  display: block;
  font-size: var(--font-size-body);
  color: var(--text-muted, #4b5563);
}
.empty-hint {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #9aa4a0);
}
.log-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f0f2f1);
}
.log-row:last-child {
  border-bottom: none;
}
.log-title {
  display: block;
  font-size: var(--font-size-body);
  color: var(--text-primary, #1f2a24);
}
.log-time {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #9aa4a0);
}
.log-expire {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-xs);
  color: var(--warning, #b45309);
}
.log-points {
  font-size: var(--font-size-lg);
  font-weight: 700;
}
.log-points.income {
  color: var(--brand);
}
.log-points.outcome {
  color: var(--color-danger);
}
</style>
