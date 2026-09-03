<template>
  <view class="page page-root page-fill">
    <app-nav-bar title="热门活动" />
    <view class="page-scroll">
      <view class="page-body">
        <swiper
          class="banner"
          circular
          autoplay
          :interval="4200"
          indicator-dots
          indicator-active-color="#059669"
        >
          <swiper-item v-for="b in banners" :key="b.id">
            <view class="banner-card" :class="'tone-' + b.tone" @click="openPath(b.ctaPath)">
              <view class="banner-copy">
                <text class="banner-title">{{ b.title }}</text>
                <text class="banner-sub">{{ b.subtitle }}</text>
                <text class="banner-cta">立即查看 ›</text>
              </view>
              <image class="banner-mark" :src="menuIcon('gift')" mode="aspectFit" />
            </view>
          </swiper-item>
        </swiper>

        <view class="entry" @click="goCoupons">
          <view>
            <text class="entry-title">我的优惠券</text>
            <text class="entry-sub">{{ couponEntrySub }}</text>
          </view>
          <text class="entry-arrow">›</text>
        </view>

        <view class="section-title">进行中</view>
        <view v-if="loading && !campaigns.length" class="empty">加载中…</view>
        <empty-state
          v-else-if="!campaigns.length"
          icon="/static/menu/hot.png"
          title="暂无进行中活动"
          hint="可先领券，或扫码开门购物"
        >
          <button class="empty-btn primary" @click="goShop">扫码购物</button>
          <button class="empty-btn ghost" @click="goCoupons">去领券</button>
        </empty-state>
        <view v-else>
          <view v-for="c in campaigns" :key="c.id" class="campaign" @click="onCampaignClick(c)">
            <view class="campaign-badge" :class="'tone-' + c.coverColor">{{ c.typeLabel }}</view>
            <text class="campaign-title">{{ c.title }}</text>
            <text class="campaign-desc">{{ c.description }}</text>
            <view class="campaign-foot">
              <view class="campaign-time-wrap">
                <text class="campaign-time">{{ formatRange(c.startTime, c.endTime) }}</text>
                <text v-if="remainText(c.endTime)" class="campaign-remain">{{
                  remainText(c.endTime)
                }}</text>
              </view>
              <text
                class="campaign-cta"
                :class="{ muted: c.claimed || !c.claimable || claimingId === c.id }"
              >
                {{ claimingId === c.id ? '领取中…' : displayCta(c) }} ›
              </text>
            </view>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import {
  consumerApi,
  getConsumerToken,
  requireConsumerAuth,
  type MarketingBannerDto,
  type MarketingCampaignDto
} from '@/utils/consumer-api';
import { menuIcon } from '@/utils/menu-icon';

const banners = ref<MarketingBannerDto[]>([]);
const campaigns = ref<MarketingCampaignDto[]>([]);
const couponCount = ref(0);
const authed = ref(false);
const loading = ref(false);
const claimingId = ref<number | null>(null);

const couponEntrySub = computed(() =>
  authed.value
    ? `${couponCount.value} 张可用 · 结算时自动选用最优券抵扣`
    : '登录后查看可用优惠券 · 结算时自动选用最优券'
);

onShow(() => load());

async function load() {
  if (!campaigns.value.length && !banners.value.length) loading.value = true;
  authed.value = !!getConsumerToken();
  try {
    const [b, c] = await Promise.all([
      consumerApi.marketingBanners(),
      consumerApi.marketingCampaigns()
    ]);
    banners.value = b?.length
      ? b
      : [
          {
            id: 0,
            title: '领券更优惠',
            subtitle: '满减与新客礼等你领取',
            tone: 'mint',
            emoji: '惠',
            ctaPath: '/pages/coupons/coupons'
          }
        ];
    // POINTS 已下线，兜底过滤（后端也会过滤）
    campaigns.value = (c || []).filter((x) => String(x.type || '').toUpperCase() !== 'POINTS');
    if (authed.value) {
      try {
        couponCount.value = Number(await consumerApi.couponCount()) || 0;
      } catch {
        couponCount.value = 0;
      }
    } else {
      couponCount.value = 0;
    }
  } catch (e) {
    if (!campaigns.value.length) {
      banners.value = [];
      campaigns.value = [];
    }
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

function openPath(path?: string) {
  if (!path) return;
  const raw = String(path).trim();
  // 仅允许本小程序页面路径，禁止外链 / 协议跳转
  if (!raw.startsWith('/pages/') || /[\s\\]/.test(raw) || raw.includes('://')) {
    uni.showToast({ title: '活动链接无效', icon: 'none' });
    return;
  }
  const pathOnly = raw.split('?')[0] || raw;
  if (
    pathOnly === '/pages/index/index' ||
    pathOnly.startsWith('/pages/index/') ||
    pathOnly === '/pages/orders/orders' ||
    pathOnly.startsWith('/pages/orders/') ||
    pathOnly === '/pages/mine/mine' ||
    pathOnly.startsWith('/pages/mine/')
  ) {
    uni.switchTab({ url: pathOnly });
    return;
  }
  uni.navigateTo({ url: raw });
}

function displayCta(c: MarketingCampaignDto) {
  if (c.claimed) return c.ctaLabel || '查看券包';
  if (c.claimable === false) return c.ctaLabel || '暂不可领';
  return c.ctaLabel || '立即领取';
}

async function onCampaignClick(c: MarketingCampaignDto) {
  if (!c?.id) return;
  if (String(c.type || '').toUpperCase() === 'POINTS') {
    uni.showToast({ title: '该活动类型已下线', icon: 'none' });
    return;
  }
  if (c.claimed || c.claimable === false) {
    openPath(c.ctaPath || '/pages/coupons/coupons');
    return;
  }
  if (claimingId.value === c.id) return;
  if (!(await requireConsumerAuth('领取活动需先完成登录', '/pages/marketing/index'))) return;
  claimingId.value = c.id;
  try {
    const coupon = await consumerApi.claimCampaign(c.id);
    const name = coupon?.couponName || '优惠券';
    uni.showToast({ title: `已领取 ${name}`, icon: 'success' });
    c.claimed = true;
    c.claimable = false;
    c.ctaLabel = '查看券包';
    try {
      couponCount.value = await consumerApi.couponCount();
    } catch {
      /* keep previous count */
    }
    setTimeout(() => openPath(c.ctaPath || '/pages/coupons/coupons'), 400);
  } catch (e) {
    const msg = e instanceof Error ? e.message : '领取失败';
    uni.showToast({ title: msg, icon: 'none' });
    if (String(msg).includes('已领取')) {
      c.claimed = true;
      c.claimable = false;
      c.ctaLabel = '查看券包';
      openPath(c.ctaPath || '/pages/coupons/coupons');
    }
  } finally {
    claimingId.value = null;
  }
}

function goCoupons() {
  uni.navigateTo({ url: '/pages/coupons/coupons' });
}
function goShop() {
  uni.switchTab({ url: '/pages/index/index' });
}

function formatRange(start?: string, end?: string) {
  const s = start ? String(start).substring(5, 10).replace('-', '/') : '';
  const e = end ? String(end).substring(5, 10).replace('-', '/') : '';
  if (!s && !e) return '长期有效';
  return `${s} - ${e}`;
}

function remainText(end?: string) {
  if (!end) return '';
  const t = new Date(end).getTime();
  if (!Number.isFinite(t)) return '';
  const diff = t - Date.now();
  if (diff <= 0) return '即将结束';
  const days = Math.ceil(diff / (24 * 60 * 60 * 1000));
  if (days <= 1) return '今日截止';
  if (days <= 7) return `剩 ${days} 天`;
  return '';
}
</script>

<style scoped>
.page {
  height: 100%;
  min-height: 100%;
  padding: 0;
  background: #ffffff;
  box-sizing: border-box;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.page-scroll {
  flex: 1 1 0;
  height: 0;
  min-height: 0;
  width: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
  scrollbar-width: none;
}
.page-scroll::-webkit-scrollbar {
  width: 0;
  height: 0;
  display: none;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.banner {
  height: 280rpx;
  margin-bottom: 20rpx;
}
.banner-card {
  height: 260rpx;
  margin: 0 4rpx;
  padding: 36rpx 32rpx;
  border-radius: 28rpx;
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #fff;
  background: linear-gradient(135deg, #064e3b, #059669);
}
.banner-card.tone-amber {
  background: linear-gradient(135deg, #92400e, #f59e0b);
}
.banner-card.tone-sky {
  background: linear-gradient(135deg, #0c4a6e, #0ea5e9);
}
.banner-card.tone-rose {
  background: linear-gradient(135deg, #9f1239, #fb7185);
}
.banner-card.tone-mint {
  background: linear-gradient(135deg, #064e3b, #10b981);
}
.banner-title {
  display: block;
  font-size: 40rpx;
  font-weight: 800;
}
.banner-sub {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  opacity: 0.9;
  max-width: 420rpx;
}
.banner-cta {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 22rpx;
  padding: 8rpx 18rpx;
  min-height: 48rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.2);
  font-size: 22rpx;
}
.banner-mark {
  width: 96rpx;
  height: 96rpx;
  border-radius: 28rpx;
  background: rgba(6, 78, 59, 0.55);
  border: 2rpx solid rgba(255, 255, 255, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 40rpx;
  font-weight: 800;
  color: #fff;
}

.entry {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 28rpx 24rpx;
  margin-bottom: 16rpx;
  border-radius: 22rpx;
  background: #fff;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.entry.mint {
  background: linear-gradient(90deg, #fff, #ecfdf5);
  border: 1rpx solid #d1fae5;
}
.entry-title {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: #1b3027;
}
.entry-sub {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #849087;
}
.entry-arrow {
  font-size: 36rpx;
  color: #cbd5e1;
}

.section-title {
  margin: 18rpx 0 14rpx;
  font-size: 30rpx;
  font-weight: 750;
  color: #1b3027;
}
.campaign {
  padding: 26rpx 24rpx;
  margin-bottom: 16rpx;
  border-radius: 22rpx;
  background: #fff;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.campaign-badge {
  display: inline-flex;
  align-items: center;
  gap: 6rpx;
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  color: #065f46;
  background: #d1fae5;
}
.campaign-badge.tone-amber {
  color: #92400e;
  background: #fef3c7;
}
.campaign-badge.tone-sky {
  color: #075985;
  background: #e0f2fe;
}
.campaign-badge.tone-rose {
  color: #9f1239;
  background: #ffe4e6;
}
.campaign-title {
  display: block;
  margin-top: 14rpx;
  font-size: 32rpx;
  font-weight: 750;
  color: #1b3027;
}
.campaign-desc {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #849087;
  line-height: 1.5;
}
.campaign-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 18rpx;
  gap: 16rpx;
}
.campaign-time-wrap {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  min-width: 0;
}
.campaign-time {
  font-size: 22rpx;
  color: #94a3b8;
}
.campaign-remain {
  font-size: 20rpx;
  color: #b45309;
  font-weight: 600;
}
.campaign-cta {
  font-size: 24rpx;
  color: #059669;
  font-weight: 700;
}
.campaign-cta.muted {
  color: #94a3b8;
}
.empty {
  text-align: center;
  padding: 60rpx 0;
  color: #999;
}
/* 高度/通栏由 App.vue + empty-actions 统一（微信 88rpx 触控） */
.empty-btn::after {
  border: none;
}
</style>
