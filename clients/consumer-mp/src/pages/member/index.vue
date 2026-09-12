<template>
  <view class="page">
    <app-nav-bar title="会员中心" />
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
      <view role="button" class="points-chip" @click="goPoints">
        <view class="points-left">
          <text class="points-label">可用积分</text>
          <text class="points-value">{{ profile?.availablePoints ?? 0 }}</text>
        </view>
        <text class="points-action app-link-chevron">积分明细</text>
      </view>
      <view class="hero-meta">
        <text v-if="currentLevelRate" class="hero-meta-item"
          >积分倍率 ¥1 = {{ currentLevelRate }} 分</text
        >
        <text v-if="profile?.orderCount != null" class="hero-meta-item"
          >累计订单 {{ profile.orderCount }} 笔</text
        >
        <text v-if="expireSoonCoupons > 0" class="hero-meta-item warn"
          >{{ expireSoonCoupons }} 张券即将过期</text
        >
      </view>
    </view>

    <view class="quick-grid">
      <view role="button" class="quick" @click="goRedeem">
        <image class="quick-mark" :src="menuIcon('coupons')" mode="aspectFit" />
        <text class="quick-title">积分兑换</text>
        <text class="quick-desc">积分换券</text>
      </view>
      <view role="button" class="quick" @click="goMessages">
        <image class="quick-mark" :src="menuIcon('notice')" mode="aspectFit" />
        <text class="quick-title">消息中心</text>
        <text class="quick-desc">订单·售后</text>
      </view>
      <view role="button" class="quick" @click="goCoupons">
        <image class="quick-mark" :src="menuIcon('coupons')" mode="aspectFit" />
        <text class="quick-title">我的券</text>
        <text class="quick-desc">{{ couponCount }} 张可用</text>
      </view>
      <view role="button" class="quick" @click="goMarketing">
        <image class="quick-mark" :src="menuIcon('hot')" mode="aspectFit" />
        <text class="quick-title">热门活动</text>
        <text class="quick-desc">本周上新</text>
      </view>
      <view role="button" class="quick" @click="goOrders">
        <image class="quick-mark" :src="menuIcon('orders')" mode="aspectFit" />
        <text class="quick-title">我的订单</text>
        <text class="quick-desc">消费记录</text>
      </view>
      <view role="button" class="quick" @click="goShop">
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
          <text class="level-rate">积分倍率 ¥1 = {{ lv.pointsRate }} 分</text>
          <text v-if="Number(lv.priceDiscountPct || 0) > 0" class="level-rate"
            >会员折扣 {{ lv.priceDiscountPct }}%</text
          >
        </view>
        <text v-if="lv.levelCode === profile?.levelCode" class="level-badge">当前</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  showError
} from '@/utils/notify';
import { onShow } from '@dcloudio/uni-app';
import { consumerApi, ensureConsumerAuth, type MemberProfileDto } from '@/utils/consumer-api';
import { menuIcon } from '@/utils/menu-icon';

const profile = ref<MemberProfileDto | null>(null);
const couponCount = ref(0);
const expireSoonCoupons = ref(0);

const progressWidth = computed(
  () => `${Math.min(100, Math.max(0, profile.value?.progressPercent || 0))}%`
);
const currentLevel = computed(() => {
  const code = profile.value?.levelCode;
  return (profile.value?.levels || []).find((x) => x.levelCode === code) || null;
});
const currentLevelRate = computed(() => currentLevel.value?.pointsRate ?? null);

/** 权益文案由当前档位规则生成（积分倍率 / 会员折扣 / 升级进度），不再写死营销话术。 */
const benefits = computed(() => {
  const rows: Array<{ mark: string; title: string; desc: string }> = [];
  const lv = currentLevel.value;
  const rate = Number(lv?.pointsRate ?? 1);
  rows.push({
    mark: 'member',
    title: '积分加速',
    desc: `当前档位消费 ¥1 = ${Number.isFinite(rate) ? rate : 1} 积分`
  });
  const discount = Number(lv?.priceDiscountPct ?? 0);
  if (Number.isFinite(discount) && discount > 0) {
    rows.push({
      mark: 'coupons',
      title: '会员价折扣',
      desc: `结算享 ${discount}% 会员折扣（以商品与活动规则为准）`
    });
  }
  if (profile.value?.nextLevelName) {
    rows.push({
      mark: 'hot',
      title: '消费升级',
      desc: `距${profile.value.nextLevelName}还差约 ${formatYuan(Number(profile.value.spentToNextLevel || 0))} 累计消费`
    });
  } else {
    rows.push({
      mark: 'hot',
      title: '最高档位',
      desc: '已达当前最高会员等级，继续消费可累计更多积分'
    });
  }
  return rows;
});

function formatYuan(n: number) {
  const v = Number.isFinite(n) ? n : 0;
  return (
    '¥' +
    new Intl.NumberFormat('zh-CN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(v)
  );
}
const spentText = computed(() => formatYuan(Number(profile.value?.totalSpent || 0)));

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
    const [p, count, coupons] = await Promise.all([
      consumerApi.memberProfile(),
      consumerApi.couponCount(),
      consumerApi.myCoupons().catch(() => [])
    ]);
    profile.value = p;
    couponCount.value = Number(count) || 0;
    const now = Date.now();
    const soon = 7 * 24 * 60 * 60 * 1000;
    expireSoonCoupons.value = (coupons || []).filter((c) => {
      if (String(c.status || '').toUpperCase() !== 'UNUSED') return false;
      if (!c.expireAt) return false;
      const t = new Date(c.expireAt).getTime();
      return Number.isFinite(t) && t > now && t - now <= soon;
    }).length;
  } catch (e) {
    showError(e instanceof Error ? e.message : '加载失败');
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
  min-height: 100%;
  padding: 0 0 48rpx;
  background: var(--card-bg, #ffffff);
}
.hero {
  position: relative;
  overflow: hidden;
  margin: 24rpx 24rpx 0;
  padding: 36rpx 32rpx 28rpx;
  border-radius: var(--radius-card);
  color: var(--text-primary, #14201b);
  background: linear-gradient(135deg, var(--brand-soft), var(--white));
  border: 1rpx solid var(--brand-soft, #d1fae5);
  box-shadow: none;
}
.hero.lv-silver {
  background: linear-gradient(135deg, var(--color-border-subtle, #f1f5f9), var(--white));
  border-color: var(--color-border);
}
.hero.lv-gold {
  background: linear-gradient(135deg, color-mix(in srgb, var(--warning, #b45309) 8%, var(--white)), var(--white));
  border-color: var(--warning-soft);
}
.hero.lv-platinum {
  background: linear-gradient(135deg, #eef2ff, var(--white));
  border-color: #c7d2fe;
}
.hero-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20rpx;
}
.hero-kicker {
  display: block;
  font-size: var(--font-size-xs);
  letter-spacing: 2rpx;
  color: var(--text-muted, #849087);
}
.hero-level {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-h2);
  font-weight: 800;
  color: var(--brand);
}
.hero.lv-silver .hero-level {
  color: var(--text-muted, #475569);
}
.hero.lv-gold .hero-level {
  color: var(--warning, #b45309);
}
.hero.lv-platinum .hero-level {
  color: #4338ca;
}
.spent-chip {
  min-width: 140rpx;
  padding: 16rpx 22rpx;
  border-radius: var(--radius-card);
  background: var(--brand-soft, #f0fdf4);
  text-align: center;
}
.hero.lv-silver .spent-chip {
  background: var(--color-border-subtle, #f1f5f9);
}
.hero.lv-gold .spent-chip {
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
}
.hero.lv-platinum .spent-chip {
  background: #e0e7ff;
}
.spent-num {
  display: block;
  font-size: var(--font-size-display-sm);
  font-weight: 800;
  line-height: 1;
  color: var(--text-primary, #14201b);
}
.spent-unit {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
}
.progress-block {
  margin-top: 28rpx;
}
.progress-track {
  height: 10rpx;
  border-radius: var(--radius-pill);
  background: var(--brand-soft, #d1fae5);
  overflow: hidden;
}
.hero.lv-silver .progress-track {
  background: var(--color-border);
}
.hero.lv-gold .progress-track {
  background: var(--warning-soft);
}
.hero.lv-platinum .progress-track {
  background: #c7d2fe;
}
.progress-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--brand);
}
.hero.lv-silver .progress-fill {
  background: var(--text-muted);
}
.hero.lv-gold .progress-fill {
  background: var(--warning, #d97706);
}
.hero.lv-platinum .progress-fill {
  background: #4f46e5;
}
.progress-text {
  display: block;
  margin-top: 14rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  line-height: 1.4;
}
.points-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20rpx;
  padding: 16rpx 22rpx;
  border-radius: 18rpx;
  background: var(--brand-soft, #f0fdf4);
}
.hero.lv-silver .points-chip {
  background: var(--color-border-subtle, #f1f5f9);
}
.hero.lv-gold .points-chip {
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
}
.hero.lv-platinum .points-chip {
  background: #e0e7ff;
}
.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx 20rpx;
  margin-top: 16rpx;
}
.hero-meta-item {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.hero-meta-item.warn {
  color: var(--warning, #b45309);
  font-weight: 600;
}
.points-left {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
}
.points-label {
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
}
.points-value {
  font-size: var(--font-size-display-sm);
  font-weight: 800;
  color: var(--brand);
}
.points-action {
  font-size: var(--font-size-sm);
  color: var(--brand);
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12rpx;
  margin-top: 22rpx;
}
.quick {
  padding: 22rpx 10rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
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
  border-radius: var(--radius-panel);
  background: var(--brand-soft, #f0fdf4);
  font-size: var(--font-size-body);
  font-weight: 700;
  color: var(--brand);
}
.quick-title {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-caption);
  font-weight: 650;
  color: var(--text-primary, #1f2a24);
}
.quick-desc {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-xs);
  color: var(--text-muted, #8a968e);
}

.section {
  margin-top: 24rpx;
  padding: 28rpx 24rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.section-title {
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #1b3027);
}

.benefit-row {
  display: flex;
  gap: 18rpx;
  padding: 18rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f0f2f1);
}
.benefit-row:last-child {
  border-bottom: 0;
}
.benefit-mark {
  width: 56rpx;
  height: 56rpx;
  flex-shrink: 0;
  border-radius: var(--radius-panel);
  background: var(--brand-soft, #f0fdf4);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-body);
  font-weight: 700;
  color: var(--brand);
}
.benefit-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 650;
  color: var(--text-primary, #223029);
}
.benefit-desc {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
}

.level-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20rpx 18rpx;
  margin-top: 12rpx;
  border-radius: var(--radius-panel);
  background: var(--page-bg, #f8faf9);
}
.level-row.on {
  background: var(--brand-soft);
  border: 1rpx solid var(--brand-mist, #a7f3d0);
}
.level-name {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 650;
  color: var(--text-primary, #1b3027);
}
.level-range {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
}
.level-rate {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-xs);
  color: var(--brand);
}
.level-badge {
  font-size: var(--font-size-caption);
  color: var(--brand);
  font-weight: 700;
}
</style>
