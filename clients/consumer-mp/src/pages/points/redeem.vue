<template>
  <view class="page-root">
    <app-nav-bar title="积分兑换" />
    <view class="page-body">
      <view class="balance-bar" @click="goPoints">
        <text class="balance-label">我的积分</text>
        <text class="balance-value">{{ summary?.availablePoints ?? 0 }}</text>
        <text class="balance-action">明细 ›</text>
      </view>

      <view v-if="loading && !items.length" class="loading"><text>加载中…</text></view>
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
                >券面 {{ (item.denominationCents / 100).toFixed(2) }} 元 ·
                {{
                  item.minSpendCents && item.minSpendCents > 0
                    ? `满 ${(item.minSpendCents / 100).toFixed(2)} 可用`
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
import { onShow } from '@dcloudio/uni-app';
import {
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
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
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
    uni.showToast({ title: '已兑完', icon: 'none' });
    return;
  }
  if ((summary.value?.availablePoints ?? 0) < item.pointsCost) {
    uni.showToast({ title: '积分不足', icon: 'none' });
    return;
  }
  const confirmed = await new Promise<boolean>((resolve) =>
    uni.showModal({
      title: '确认兑换',
      content: `将消耗 ${item.pointsCost} 积分兑换「${item.title}」，兑换后发放至我的优惠券。`,
      confirmText: '确认兑换',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  );
  if (!confirmed) return;

  redeeming.value = item.itemId;
  try {
    await consumerApi.redeemPoints(item.itemId);
    uni.showToast({ title: '兑换成功，已放入我的券', icon: 'success' });
    await load();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '兑换失败', icon: 'none' });
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
  background: #ffffff;
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
  border-radius: 24rpx;
  color: #14201b;
  background: linear-gradient(135deg, #ecfdf5, #fff);
  border: 1rpx solid #d1fae5;
  text-align: center;
  position: relative;
}
.balance-label {
  font-size: 24rpx;
  color: #64748b;
  text-align: center;
}
.balance-value {
  font-size: 40rpx;
  font-weight: 800;
  color: #047857;
  text-align: center;
}
.balance-action {
  margin-left: 0;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #059669;
}
.loading {
  padding: 120rpx 0;
  text-align: center;
  color: #8a968e;
}
.empty {
  padding: 120rpx 0;
  text-align: center;
}
.empty-title {
  display: block;
  font-size: 28rpx;
  color: #4b5563;
}
.empty-hint {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #9aa4a0;
}
.item-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16rpx;
  margin-top: 20rpx;
  padding: 24rpx;
  border-radius: 24rpx;
  background: #fff;
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
  border-radius: 20rpx;
  background: #f0fdf4;
  font-size: 44rpx;
}
.item-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #1f2a24;
}
.item-subtitle {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #8a968e;
}
.item-coupon {
  display: block;
  margin-top: 4rpx;
  font-size: 20rpx;
  color: #64748b;
}
.item-stock {
  display: block;
  margin-top: 4rpx;
  font-size: 20rpx;
  color: #059669;
}
.item-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12rpx;
}
.item-cost {
  font-size: 26rpx;
  font-weight: 700;
  color: #d97706;
}
.redeem-btn {
  margin: 0;
  padding: 0 28rpx;
  height: 60rpx;
  line-height: 60rpx;
  border-radius: 999rpx;
  font-size: 24rpx;
  color: #fff;
  background: #047857;
}
.redeem-btn.disabled {
  background: #c7d1cb;
}
</style>
