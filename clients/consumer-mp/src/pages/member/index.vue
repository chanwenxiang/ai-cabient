<template>
  <view class="page">
    <view class="hero" :class="'lv-' + (profile?.levelCode || 'NORMAL').toLowerCase()">
      <view class="hero-top">
        <view>
          <text class="hero-kicker">会员俱乐部</text>
          <text class="hero-level">{{ profile?.levelName || '普通会员' }}</text>
        </view>
        <view class="spent-chip">
          <text class="spent-num">{{ spentText }}</text>
          <text class="spent-unit">累计消费</text>
        </view>
      </view>
      <view class="progress-block">
        <view class="progress-track">
          <view class="progress-fill" :style="{ width: progressWidth }" />
        </view>
        <text class="progress-text">
          <text v-if="profile?.nextLevelName"
            >距{{ profile.nextLevelName }}还差约
            {{ formatYuan(profile.spentToNextLevel) }} 消费</text
          >
          <text v-else>已达最高等级</text>
        </text>
      </view>
      <view class="points-chip" @click="goPoints">
        <view class="points-left">
          <text class="points-label">可用积分</text>
          <text class="points-value">{{ profile?.availablePoints ?? 0 }}</text>
        </view>
        <text class="points-action">积分明细 ›</text>
      </view>
    </view>

    <view class="quick-grid">
      <view class="quick" @click="goRedeem">
        <image class="quick-mark" :src="menuIcon('coupons')" mode="aspectFit" />
        <text class="quick-title">积分兑换</text>
        <text class="quick-desc">积分换券</text>
      </view>
      <view class="quick" @click="goMessages">
        <image class="quick-mark" :src="menuIcon('notice')" mode="aspectFit" />
        <text class="quick-title">消息中心</text>
        <text class="quick-desc">订单·售后</text>
      </view>
      <view class="quick" @click="goCoupons">
        <image class="quick-mark" :src="menuIcon('coupons')" mode="aspectFit" />
        <text class="quick-title">我的券</text>
        <text class="quick-desc">{{ couponCount }} 张可用</text>
      </view>
      <view class="quick" @click="goMarketing">
        <image class="quick-mark" :src="menuIcon('hot')" mode="aspectFit" />
        <text class="quick-title">热门活动</text>
        <text class="quick-desc">本周上新</text>
      </view>
      <view class="quick" @click="goOrders">
        <image class="quick-mark" :src="menuIcon('orders')" mode="aspectFit" />
        <text class="quick-title">我的订单</text>
        <text class="quick-desc">消费记录</text>
      </view>
      <view class="quick" @click="goShop">
        <image class="quick-mark" :src="menuIcon('shopping')" mode="aspectFit" />
        <text class="quick-title">去购物</text>
        <text class="quick-desc">扫码开门</text>
      </view>
    </view>

    <view class="section">
      <text class="section-title">会员权益</text>
      <view class="benefit-list">
        <view v-for="b in benefits" :key="b.title" class="benefit-row">
          <image class="benefit-mark" :src="menuIcon(b.mark)" mode="aspectFit" />
          <view class="benefit-copy">
            <text class="benefit-title">{{ b.title }}</text>
            <text class="benefit-desc">{{ b.desc }}</text>
          </view>
        </view>
      </view>
    </view>

    <view class="section">
      <text class="section-title">等级说明</text>
      <view
        v-for="lv in profile?.levels || []"
        :key="lv.levelCode"
        class="level-row"
        :class="{ on: lv.levelCode === profile?.levelCode }"
      >
        <view>
          <text class="level-name">{{ lv.levelName }}</text>
          <text class="level-range"
            >累计消费 {{ formatYuan(Number(lv.minSpent || 0))
            }}{{ lv.maxSpent != null ? ' - ' + formatYuan(Number(lv.maxSpent)) : '+' }}</text
          >
        </view>
        <text v-if="lv.levelCode === profile?.levelCode" class="level-badge">当前</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { consumerApi, ensureConsumerAuth, type MemberProfileDto } from '@/utils/consumer-api';
import { menuIcon } from '@/utils/menu-icon';

const profile = ref<MemberProfileDto | null>(null);
const couponCount = ref(0);

const progressWidth = computed(
  () => `${Math.min(100, Math.max(0, profile.value?.progressPercent || 0))}%`
);
const yuanFmt = new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  maximumFractionDigits: 0
});
function formatYuan(n: number) {
  return yuanFmt.format(Number.isFinite(n) ? n : 0);
}
const spentText = computed(() => formatYuan(Number(profile.value?.totalSpent || 0)));

const benefits = [
  { mark: 'coupons', title: '优惠券立减', desc: '结算时自动选用可用优惠券' },
  { mark: 'member', title: '消费升级', desc: '累计消费提升会员等级' },
  { mark: 'hot', title: '活动优先', desc: '会员专享满减与新客礼，活动页直达' }
];

onShow(async () => {
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/member/index')
    });
    return;
  }
  await load();
});

async function load() {
  try {
    const [p, count] = await Promise.all([consumerApi.memberProfile(), consumerApi.couponCount()]);
    profile.value = p;
    couponCount.value = Number(count) || 0;
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  }
}

function goCoupons() {
  uni.navigateTo({ url: '/pages/coupons/coupons' });
}
function goMarketing() {
  uni.navigateTo({ url: '/pages/marketing/index' });
}
function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}
function goShop() {
  uni.switchTab({ url: '/pages/index/index' });
}
function goPoints() {
  uni.navigateTo({ url: '/pages/points/points' });
}
function goRedeem() {
  uni.navigateTo({ url: '/pages/points/redeem' });
}
function goMessages() {
  uni.navigateTo({ url: '/pages/messages/messages' });
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
.hero.lv-silver {
  background: linear-gradient(135deg, #334155, #64748b 60%, #94a3b8);
}
.hero.lv-gold {
  background: linear-gradient(135deg, #92400e, #d97706 55%, #f59e0b);
}
.hero.lv-platinum {
  background: linear-gradient(135deg, #1e1b4b, #4338ca 55%, #6366f1);
}
.hero-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20rpx;
}
.hero-kicker {
  display: block;
  font-size: 20rpx;
  letter-spacing: 2rpx;
  opacity: 0.7;
}
.hero-level {
  display: block;
  margin-top: 8rpx;
  font-size: 40rpx;
  font-weight: 800;
}
.spent-chip {
  min-width: 140rpx;
  padding: 16rpx 22rpx;
  border-radius: 20rpx;
  background: rgba(255, 255, 255, 0.16);
  text-align: right;
}
.spent-num {
  display: block;
  font-size: 36rpx;
  font-weight: 800;
  line-height: 1;
}
.spent-unit {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  opacity: 0.85;
}
.progress-block {
  margin-top: 28rpx;
}
.progress-track {
  height: 10rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.22);
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  border-radius: 999rpx;
  background: #fff;
}
.progress-text {
  display: block;
  margin-top: 14rpx;
  font-size: 22rpx;
  opacity: 0.88;
  line-height: 1.4;
}
.points-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20rpx;
  padding: 16rpx 22rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.14);
}
.points-left {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
}
.points-label {
  font-size: 22rpx;
  opacity: 0.85;
}
.points-value {
  font-size: 34rpx;
  font-weight: 800;
}
.points-action {
  font-size: 22rpx;
  opacity: 0.9;
}

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
.quick-mark {
  display: inline-flex;
  width: 56rpx;
  height: 56rpx;
  margin: 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 16rpx;
  background: #f0fdf4;
  font-size: 26rpx;
  font-weight: 700;
  color: #059669;
}
.quick-title {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  font-weight: 650;
  color: #1f2a24;
}
.quick-desc {
  display: block;
  margin-top: 4rpx;
  font-size: 20rpx;
  color: #8a968e;
}

.section {
  margin-top: 24rpx;
  padding: 28rpx 24rpx;
  border-radius: 24rpx;
  background: #fff;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.section-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #1b3027;
}

.benefit-row {
  display: flex;
  gap: 18rpx;
  padding: 18rpx 0;
  border-bottom: 1rpx solid #f0f2f1;
}
.benefit-row:last-child {
  border-bottom: 0;
}
.benefit-mark {
  width: 56rpx;
  height: 56rpx;
  flex-shrink: 0;
  border-radius: 16rpx;
  background: #f0fdf4;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26rpx;
  font-weight: 700;
  color: #059669;
}
.benefit-title {
  display: block;
  font-size: 28rpx;
  font-weight: 650;
  color: #223029;
}
.benefit-desc {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #849087;
}

.level-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20rpx 18rpx;
  margin-top: 12rpx;
  border-radius: 16rpx;
  background: #f8faf9;
}
.level-row.on {
  background: #ecfdf5;
  border: 1rpx solid #a7f3d0;
}
.level-name {
  display: block;
  font-size: 28rpx;
  font-weight: 650;
  color: #1b3027;
}
.level-range {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #849087;
}
.level-badge {
  font-size: 24rpx;
  color: #059669;
  font-weight: 700;
}
</style>
