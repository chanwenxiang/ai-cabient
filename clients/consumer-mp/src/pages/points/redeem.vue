<template>
  <view class="page-root">
    <app-nav-bar title="积分兑换" />
    <view class="page-body">
      <view role="button" class="balance-bar" @click="goPoints">
        <text class="balance-label">我的积分</text>
        <text class="balance-value">{{ summary?.availablePoints ?? 0 }}</text>
        <text class="balance-action app-link-chevron">明细</text>
      </view>

      <view v-if="loading && !items.length" class="loading"><text>{{ UI_COPY.loading }}</text></view>
      <view v-else-if="!items.length" class="empty">
        <text class="empty-title">暂无兑换商品</text>
        <text class="empty-hint">运营上架积分兑换后即可兑换优惠券</text>
      </view>
      <view v-else class="item-list">
        <view v-for="item in items" :key="item.itemId" class="item-card">
          <view class="item-main">
            <view class="item-emoji">{{ item.coverEmoji }}</view>
            <view class="item-copy">
              <text class="item-title">{{ item.title }}</text>
              <text class="item-subtitle">{{ item.subtitle || '兑换优惠券，结算自动使用' }}</text>
              <text v-if="item.denominationCents != null" class="item-coupon"
                >券面 {{ fmtMoney(item.denominationCents).replace('¥', '') }} 元 ·
                {{
                  item.minSpendCents && item.minSpendCents > 0
                    ? `满 ${fmtMoney(item.minSpendCents).replace('¥', '')} 可用`
                    : '无门槛'
                }}</text
              >
              <text v-if="item.validityDays" class="item-coupon"
                >领后 {{ item.validityDays }} 天有效 · {{ deviceScopeText(item.deviceScope) }}</text
              >
              <text class="item-stock">{{
                item.availableStock > 0 ? `剩余 ${item.availableStock} 份` : '已兑完'
              }}</text>
            </view>
          </view>
          <view class="item-side">
            <text class="item-cost">{{ item.pointsCost }} 积分</text>
            <button
              class="redeem-btn"
              :class="{
                disabled:
                  !!redeeming ||
                  item.availableStock <= 0 ||
                  (summary?.availablePoints ?? 0) < item.pointsCost
              }"
              :disabled="!!redeeming || item.availableStock <= 0"
              @click="redeem(item)"
            >
              {{ redeeming === item.itemId ? '兑换中…' : '立即兑换' }}
            </button>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import {
  showError,
  showSuccess,
  showConfirm
} from '@/utils/notify';
import { onShow } from '@dcloudio/uni-app';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import {
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
  consumerApi,
  ensureConsumerAuth,
  type MemberPointsSummaryDto,
  type PointsRedeemItemDto
} from '@/utils/consumer-api';

const loading = ref(false);
const redeeming = ref<number | null>(null);
const summary = ref<MemberPointsSummaryDto | null>(null);
const items = ref<PointsRedeemItemDto[]>([]);

onShow(async () => {
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/points/redeem')
    });
    return;
  }
  await load();
});

async function load() {
  if (!items.value.length) loading.value = true;
  try {
    const [s, list] = await Promise.all([consumerApi.memberPoints(), consumerApi.redeemItems()]);
    summary.value = s;
    items.value = list;
  } catch (e) {
    showError(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function deviceScopeText(scope?: string) {
  const s = String(scope || 'ALL').toUpperCase();
  if (s === 'ALL' || !s) return '全柜可用';
  if (s === 'SELECTED' || s === 'DEVICE' || s === 'DEVICES') return '指定柜可用';
  return scope || '全柜可用';
}

async function redeem(item: PointsRedeemItemDto) {
  if (redeeming.value) return;
  if (item.availableStock <= 0) {
    showError('已兑完');
    return;
  }
  if ((summary.value?.availablePoints ?? 0) < item.pointsCost) {
    showError('积分不足');
    return;
  }
  const confirmed = await showConfirm({
    title: '确认兑换',
    content: `将消耗 ${item.pointsCost} 积分兑换「${item.title}」，兑换后发放至我的优惠券。`,
    confirmText: '确认兑换'
  });
  if (!confirmed) return;

  redeeming.value = item.itemId;
  try {
    await consumerApi.redeemPoints(item.itemId);
    showSuccess('兑换成功，已放入我的券');
    await load();
  } catch (e) {
    showError(e instanceof Error ? e.message : '兑换失败');
  } finally {
    redeeming.value = null;
  }
}

function goPoints() {
  uni.navigateTo({ url: '/pages/points/points' });
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
.balance-bar {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  padding: 26rpx 28rpx;
  border-radius: var(--radius-card);
  color: var(--text-primary, #14201b);
  background: linear-gradient(135deg, var(--brand-soft), var(--white));
  border: 1rpx solid var(--brand-soft, #d1fae5);
  text-align: center;
  position: relative;
}
.balance-label {
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  text-align: center;
}
.balance-value {
  font-size: var(--font-size-h2);
  font-weight: 800;
  color: var(--brand);
  text-align: center;
}
.balance-action {
  margin-left: 0;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--brand);
}
.loading {
  padding: 120rpx 0;
  text-align: center;
  color: var(--text-muted, #8a968e);
}
.empty {
  padding: 120rpx 0;
  text-align: center;
}
.empty-title {
  display: block;
  font-size: var(--font-size-md);
  color: var(--text-muted, #4b5563);
}
.empty-hint {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #9aa4a0);
}
.item-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16rpx;
  margin-top: 20rpx;
  padding: 24rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
}
.item-main {
  display: flex;
  align-items: center;
  gap: 20rpx;
  flex: 1;
  min-width: 0;
}
.item-emoji {
  width: 88rpx;
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-card);
  background: var(--brand-soft, #f0fdf4);
  font-size: var(--font-size-h1);
}
.item-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--text-primary, #1f2a24);
}
.item-subtitle {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #8a968e);
}
.item-coupon {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}
.item-stock {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-xs);
  color: var(--brand);
}
.item-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12rpx;
}
.item-cost {
  font-size: var(--font-size-body);
  font-weight: 700;
  color: var(--warning, #d97706);
}
.redeem-btn {
  margin: 0;
  padding: 0 28rpx;
  height: 60rpx;
  line-height: 60rpx;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-caption);
  color: var(--white);
  background: var(--brand);
}
.redeem-btn.disabled {
  background: #c7d1cb;
}
</style>
