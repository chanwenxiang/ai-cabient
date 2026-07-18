<template>
  <view class="page">
    <view class="hero" :class="'lv-' + (profile?.levelCode || 'NORMAL').toLowerCase()">
      <view class="hero-top">
        <view>
          <text class="hero-kicker">会员俱乐部</text>
          <text class="hero-level">{{ profile?.levelName || '普通会员' }}</text>
        </view>
        <view class="points-chip" @click="goHistory">
          <text class="points-num">{{ profile?.availablePoints ?? 0 }}</text>
          <text class="points-unit">积分</text>
        </view>
      </view>
      <view class="progress-block">
        <view class="progress-track">
          <view class="progress-fill" :style="{ width: progressWidth }" />
        </view>
        <text class="progress-text">
          <text v-if="profile?.nextLevelName">距 {{ profile.nextLevelName }} 还差约 ¥{{ profile.pointsToNextLevel }} 消费 · 积分倍率 {{ profile.pointsRate }}x</text>
          <text v-else>已达最高等级 · 积分倍率 {{ profile?.pointsRate || 1 }}x</text>
        </text>
      </view>
    </view>

    <view class="quick-grid">
      <view class="quick" @click="goExchange">
        <text class="quick-emoji">🎁</text>
        <text class="quick-title">积分兑换</text>
        <text class="quick-desc">兑优惠券</text>
      </view>
      <view class="quick" @click="goHistory">
        <text class="quick-emoji">📒</text>
        <text class="quick-title">积分明细</text>
        <text class="quick-desc">收支一览</text>
      </view>
      <view class="quick" @click="goCoupons">
        <text class="quick-emoji">🎫</text>
        <text class="quick-title">我的券</text>
        <text class="quick-desc">{{ couponCount }} 张可用</text>
      </view>
      <view class="quick" @click="goMarketing">
        <text class="quick-emoji">🔥</text>
        <text class="quick-title">热门活动</text>
        <text class="quick-desc">本周上新</text>
      </view>
    </view>

    <view class="section">
      <view class="section-head">
        <text class="section-title">积分兑好礼</text>
        <text class="section-more" @click="goExchange">全部 ›</text>
      </view>
      <scroll-view scroll-x class="redeem-scroll" :show-scrollbar="false">
        <view v-for="item in redeemPreview" :key="item.itemId" class="redeem-card" @click="goExchange">
          <text class="redeem-emoji">{{ item.coverEmoji }}</text>
          <text class="redeem-title">{{ item.title }}</text>
          <text class="redeem-cost">{{ item.pointsCost }} 积分</text>
        </view>
        <view v-if="redeemLoading" class="redeem-empty">加载中…</view>
        <view v-else-if="!redeemPreview.length" class="redeem-empty clickable" @click="goExchange">
          <text class="redeem-empty-title">暂无可兑好礼</text>
          <text class="redeem-empty-hint">去积分兑换看看 ›</text>
        </view>
      </scroll-view>
    </view>

    <view class="section">
      <text class="section-title">会员权益</text>
      <view class="benefit-list">
        <view v-for="b in benefits" :key="b.title" class="benefit-row">
          <text class="benefit-emoji">{{ b.emoji }}</text>
          <view class="benefit-copy">
            <text class="benefit-title">{{ b.title }}</text>
            <text class="benefit-desc">{{ b.desc }}</text>
          </view>
        </view>
      </view>
    </view>

    <view class="section">
      <text class="section-title">等级说明</text>
      <view v-for="lv in profile?.levels || []" :key="lv.levelCode" class="level-row" :class="{ on: lv.levelCode === profile?.levelCode }">
        <view>
          <text class="level-name">{{ lv.levelName }}</text>
          <text class="level-range">累计消费 ¥{{ Number(lv.minSpent || 0).toFixed(0) }}{{ lv.maxSpent != null ? ' - ¥' + Number(lv.maxSpent).toFixed(0) : '+' }}</text>
        </view>
        <text class="level-rate">{{ lv.pointsRate }}x 积分</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import {
  consumerApi,
  ensureConsumerAuth,
  type MemberProfileDto,
  type PointsRedeemItemDto
} from '@/utils/consumer-api';

const profile = ref<MemberProfileDto | null>(null);
const redeemPreview = ref<PointsRedeemItemDto[]>([]);
const couponCount = ref(0);
const redeemLoading = ref(false);

const progressWidth = computed(() => `${Math.min(100, Math.max(0, profile.value?.progressPercent || 0))}%`);

const benefits = computed(() => {
  const rate = profile.value?.pointsRate || 1;
  return [
    { emoji: '⭐', title: '购物返积分', desc: `当前 ${rate}x，关门结算自动到账` },
    { emoji: '🎁', title: '积分兑券', desc: '100 积分起兑立减券，开门立减' },
    { emoji: '🧊', title: '活动优先', desc: '会员专享满减与新客礼，活动页直达' }
  ];
});

onShow(async () => {
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/member/index') });
    return;
  }
  await load();
});

async function load() {
  redeemLoading.value = true;
  try {
    const [p, items, count] = await Promise.all([
      consumerApi.memberProfile(),
      consumerApi.redeemItems(),
      consumerApi.couponCount()
    ]);
    profile.value = p;
    redeemPreview.value = (items || []).slice(0, 6);
    couponCount.value = Number(count) || 0;
  } catch (e: any) {
    uni.showToast({ title: e?.message || '加载失败', icon: 'none' });
  } finally {
    redeemLoading.value = false;
  }
}

function goExchange() {
  uni.navigateTo({ url: '/pages/member/exchange' });
}
function goHistory() {
  uni.navigateTo({ url: '/pages/member/points-history' });
}
function goCoupons() {
  uni.navigateTo({ url: '/pages/coupons/coupons' });
}
function goMarketing() {
  uni.navigateTo({ url: '/pages/marketing/index' });
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  background: linear-gradient(180deg, #e8f8f1 0%, #f5f7f8 220rpx, #f5f7f8 100%);
}
.hero {
  position: relative;
  overflow: hidden;
  padding: 36rpx 32rpx 28rpx;
  border-radius: 28rpx;
  color: #fff;
  background: linear-gradient(135deg, #064e3b 0%, #059669 55%, #0d9488 100%);
  box-shadow: 0 16rpx 40rpx rgba(5, 150, 105, 0.28);
}
.hero.lv-silver { background: linear-gradient(135deg, #334155, #64748b 60%, #94a3b8); }
.hero.lv-gold { background: linear-gradient(135deg, #92400e, #d97706 55%, #f59e0b); }
.hero.lv-platinum { background: linear-gradient(135deg, #1e1b4b, #4338ca 55%, #6366f1); }
.hero-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 20rpx; }
.hero-kicker { display: block; font-size: 20rpx; letter-spacing: 2rpx; opacity: 0.7; }
.hero-level { display: block; margin-top: 8rpx; font-size: 40rpx; font-weight: 800; }
.points-chip {
  min-width: 140rpx;
  padding: 16rpx 22rpx;
  border-radius: 20rpx;
  background: rgba(255, 255, 255, 0.16);
  text-align: right;
}
.points-num { display: block; font-size: 44rpx; font-weight: 800; line-height: 1; }
.points-unit { display: block; margin-top: 6rpx; font-size: 22rpx; opacity: 0.85; }
.progress-block { margin-top: 28rpx; }
.progress-track { height: 10rpx; border-radius: 999rpx; background: rgba(255, 255, 255, 0.22); overflow: hidden; }
.progress-fill { height: 100%; border-radius: 999rpx; background: #fff; }
.progress-text { display: block; margin-top: 14rpx; font-size: 22rpx; opacity: 0.88; line-height: 1.4; }

.quick-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12rpx;
  margin-top: 22rpx;
}
.quick {
  padding: 22rpx 10rpx;
  border-radius: 20rpx;
  background: #fff;
  text-align: center;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.05);
}
.quick-emoji { display: block; font-size: 34rpx; }
.quick-title { display: block; margin-top: 8rpx; font-size: 24rpx; font-weight: 650; color: #1f2a24; }
.quick-desc { display: block; margin-top: 4rpx; font-size: 20rpx; color: #8a968e; }

.section {
  margin-top: 24rpx;
  padding: 28rpx 24rpx;
  border-radius: 24rpx;
  background: #fff;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.section-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18rpx; }
.section-title { font-size: 30rpx; font-weight: 700; color: #1b3027; }
.section-more { font-size: 24rpx; color: #059669; }
.redeem-scroll { white-space: nowrap; }
.redeem-card {
  display: inline-flex;
  flex-direction: column;
  width: 200rpx;
  margin-right: 16rpx;
  padding: 22rpx 18rpx;
  border-radius: 20rpx;
  background: linear-gradient(180deg, #f0fdf4, #fff);
  border: 1rpx solid #d1fae5;
  vertical-align: top;
}
.redeem-emoji { font-size: 40rpx; }
.redeem-title { margin-top: 10rpx; font-size: 24rpx; font-weight: 650; color: #14532d; white-space: normal; }
.redeem-cost { margin-top: 8rpx; font-size: 22rpx; color: #059669; font-weight: 700; }
.redeem-empty {
  display: inline-flex;
  flex-direction: column;
  justify-content: center;
  min-width: 280rpx;
  padding: 24rpx 20rpx;
  color: #849087;
  font-size: 24rpx;
  vertical-align: top;
}
.redeem-empty.clickable { color: #059669; }
.redeem-empty-title { font-size: 26rpx; font-weight: 650; color: #64748b; }
.redeem-empty-hint { margin-top: 8rpx; font-size: 22rpx; color: #059669; }

.benefit-row { display: flex; gap: 18rpx; padding: 18rpx 0; border-bottom: 1rpx solid #f0f2f1; }
.benefit-row:last-child { border-bottom: 0; }
.benefit-emoji { font-size: 34rpx; }
.benefit-title { display: block; font-size: 28rpx; font-weight: 650; color: #223029; }
.benefit-desc { display: block; margin-top: 6rpx; font-size: 22rpx; color: #849087; }

.level-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20rpx 18rpx;
  margin-top: 12rpx;
  border-radius: 16rpx;
  background: #f8faf9;
}
.level-row.on { background: #ecfdf5; border: 1rpx solid #a7f3d0; }
.level-name { display: block; font-size: 28rpx; font-weight: 650; color: #1b3027; }
.level-range { display: block; margin-top: 4rpx; font-size: 22rpx; color: #849087; }
.level-rate { font-size: 24rpx; color: #059669; font-weight: 700; }
</style>
