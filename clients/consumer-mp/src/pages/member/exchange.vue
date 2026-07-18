<template>
  <view class="page">
    <view class="balance-bar">
      <text class="balance-label">可用积分</text>
      <text class="balance-num">{{ available }}</text>
      <text class="balance-link" @click="goHistory">明细 ›</text>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <empty-state
      v-else-if="!items.length"
      icon="🎁"
      title="暂无可兑好礼"
      hint="购物结算会自动返积分，攒够后再来兑券"
    >
      <button class="empty-btn" @click="goShop">去扫码购物</button>
      <button class="empty-btn ghost" @click="goHistory">查看积分明细</button>
    </empty-state>
    <view v-else class="grid">
      <view v-for="item in items" :key="item.itemId" class="card">
        <view class="card-top">
          <text class="emoji">{{ item.coverEmoji }}</text>
          <text class="stock">余 {{ item.stockLeft }}</text>
        </view>
        <text class="title">{{ item.title }}</text>
        <text class="sub">{{ item.subtitle || item.couponName }}</text>
        <text class="worth">面值 ¥{{ (item.denominationCents / 100).toFixed(0) }} · 满¥{{ (item.minSpendCents / 100).toFixed(0) }}可用</text>
        <view class="card-foot">
          <text class="cost">{{ item.pointsCost }} 积分</text>
          <button
            class="btn"
            :class="{ disabled: !item.canRedeem || redeemingId === item.itemId }"
            :disabled="!item.canRedeem || redeemingId === item.itemId"
            @click="onRedeem(item)"
          >
            {{ redeemingId === item.itemId ? '兑换中' : item.canRedeem ? '立即兑换' : '积分不足' }}
          </button>
        </view>
      </view>
    </view>

    <view class="tips">
      <text class="tips-title">兑换说明</text>
      <text class="tips-line">· 兑换成功后优惠券自动放入「我的优惠券」</text>
      <text class="tips-line">· 积分一经兑换不退回，请确认后操作</text>
      <text class="tips-line">· 当前主推「兑券」：兑换后立即可用于开门结算抵扣</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import {
  consumerApi,
  ensureConsumerAuth,
  type PointsRedeemItemDto
} from '@/utils/consumer-api';

const items = ref<PointsRedeemItemDto[]>([]);
const available = ref(0);
const loading = ref(false);
const redeemingId = ref<number | null>(null);

onShow(async () => {
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/member/exchange') });
    return;
  }
  await load();
});

async function load() {
  loading.value = true;
  try {
    const [list, summary] = await Promise.all([
      consumerApi.redeemItems(),
      consumerApi.memberPointsSummary()
    ]);
    items.value = list || [];
    available.value = summary?.availablePoints || 0;
  } catch (e: any) {
    uni.showToast({ title: e?.message || '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

async function onRedeem(item: PointsRedeemItemDto) {
  if (!item.canRedeem || redeemingId.value) return;
  const ok = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '确认兑换',
      content: `使用 ${item.pointsCost} 积分兑换「${item.title}」？`,
      success: (r) => resolve(!!r.confirm)
    });
  });
  if (!ok) return;
  redeemingId.value = item.itemId;
  try {
    await consumerApi.redeemPoints(item.itemId);
    uni.showToast({ title: '兑换成功', icon: 'success' });
    await load();
    setTimeout(() => {
      uni.navigateTo({ url: '/pages/coupons/coupons' });
    }, 600);
  } catch (e: any) {
    uni.showToast({ title: e?.message || '兑换失败', icon: 'none' });
  } finally {
    redeemingId.value = null;
  }
}

function goHistory() {
  uni.navigateTo({ url: '/pages/member/points-history' });
}
function goShop() {
  uni.switchTab({ url: '/pages/index/index' });
}
</script>

<style scoped>
.page { min-height: 100vh; padding: 24rpx; background: #f5f7f8; }
.balance-bar {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
  padding: 28rpx 24rpx;
  border-radius: 22rpx;
  background: linear-gradient(135deg, #064e3b, #059669);
  color: #fff;
  margin-bottom: 20rpx;
}
.balance-label { font-size: 24rpx; opacity: 0.85; }
.balance-num { font-size: 48rpx; font-weight: 800; }
.balance-link { margin-left: auto; font-size: 24rpx; opacity: 0.9; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16rpx; }
.card {
  padding: 22rpx;
  border-radius: 22rpx;
  background: #fff;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.05);
}
.card-top { display: flex; justify-content: space-between; align-items: center; }
.emoji { font-size: 44rpx; }
.stock { font-size: 20rpx; color: #94a3b8; }
.title { display: block; margin-top: 12rpx; font-size: 28rpx; font-weight: 700; color: #1b3027; }
.sub { display: block; margin-top: 6rpx; font-size: 22rpx; color: #849087; }
.worth { display: block; margin-top: 10rpx; font-size: 22rpx; color: #d97706; }
.card-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 18rpx; gap: 8rpx; }
.cost { font-size: 26rpx; font-weight: 800; color: #059669; }
.btn {
  margin: 0;
  padding: 0 18rpx;
  height: 56rpx;
  line-height: 56rpx;
  border-radius: 28rpx;
  background: #059669;
  color: #fff;
  font-size: 22rpx;
  font-weight: 650;
}
.btn.disabled, .btn[disabled] { opacity: 0.45; background: #94a3b8; }
.btn::after { border: none; }
.empty { text-align: center; padding: 80rpx 0; color: #999; }
.empty-btn {
  margin: 0;
  width: 320rpx;
  height: 72rpx;
  line-height: 72rpx;
  background: #059669;
  color: #fff;
  border-radius: 36rpx;
  font-size: 28rpx;
}
.empty-btn.ghost { background: #fff; color: #059669; border: 2rpx solid #059669; }
.empty-btn::after { border: none; }
.tips { margin-top: 28rpx; padding: 24rpx; border-radius: 18rpx; background: #fff; }
.tips-title { display: block; font-size: 26rpx; font-weight: 700; color: #334155; margin-bottom: 10rpx; }
.tips-line { display: block; font-size: 22rpx; color: #849087; line-height: 1.7; }
</style>
