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
          indicator-active-color="var(--brand)"
        >
          <swiper-item v-for="b in banners" :key="b.id">
            <view
              role="button"
              class="banner-card"
              :class="'tone-' + b.tone"
              @click="openPath(b.ctaPath)"
            >
              <view class="banner-copy">
                <text class="banner-title">{{ b.title }}</text>
                <text class="banner-sub">{{ b.subtitle }}</text>
                <text class="banner-cta app-link-chevron">立即查看</text>
              </view>
              <image class="banner-mark" :src="menuIcon('gift')" mode="aspectFit" />
            </view>
          </swiper-item>
        </swiper>

        <view role="button" class="entry" @click="goCoupons">
          <view>
            <text class="entry-title">我的优惠券</text>
            <text class="entry-sub">{{ couponEntrySub }}</text>
          </view>
          <view class="entry-arrow app-icon app-icon--chevron" aria-hidden="true" />
        </view>

        <view class="section-title">进行中</view>
        <view v-if="loading && !campaigns.length" class="empty">{{ UI_COPY.loading }}</view>
        <view v-else-if="!campaigns.length" class="market-empty">
          <empty-state
            icon="/static/menu/hot.png"
            title="暂无进行中活动"
            hint="可先领券，或扫码开门购物"
          />
          <!-- 按钮放在页面层，与 banner/entry 同一包含块，避免自定义组件内 width:100% 撑出 page-body -->
          <view class="market-actions">
            <app-button label="扫码购物" @click="goShop" />
            <app-button variant="ghost" label="去领券" @click="goCoupons" />
          </view>
        </view>
        <view v-else>
          <view
            v-for="c in campaigns"
            role="button"
            :key="c.id"
            class="campaign"
            @click="onCampaignClick(c)"
          >
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
                class="campaign-cta app-link-chevron"
                :class="{ muted: c.claimed || !c.claimable || claimingId === c.id }"
              >
                {{ claimingId === c.id ? '领取中…' : displayCta(c) }}
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
import { showError, showSuccess } from '@/utils/notify';
import { onShow } from '@dcloudio/uni-app';
import {
  consumerApi,
  getConsumerToken,
  requireConsumerAuth,
  type MarketingBannerDto,
  type MarketingCampaignDto
} from '@/utils/consumer-api';
import { menuIcon } from '@/utils/menu-icon';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

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
    showError(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function openPath(path?: string) {
  if (!path) return;
  const raw = String(path).trim();
  // 仅允许本小程序页面路径，禁止外链 / 协议跳转
  if (!raw.startsWith('/pages/') || /[\s\\]/.test(raw) || raw.includes('://')) {
    showError('活动链接无效');
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
    showError('该活动类型已下线');
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
    showSuccess(`已领取 ${name}`);
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
    showError(msg);
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
  background: var(--card-bg, #ffffff);
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
  /* 唯一水平边距：banner / 入口卡 / 底部操作同宽（勿用 CSS 变量，旧基础库可能整段 padding 失效） */
  padding: 20rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  width: 100%;
}
.banner {
  height: 280rpx;
  margin: 0 0 20rpx;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}
.banner-card {
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 36rpx 28rpx;
  border-radius: var(--radius-card);
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--white);
  background: linear-gradient(135deg, var(--brand-deep), var(--brand));
  box-sizing: border-box;
}
.banner-card.tone-amber {
  background: linear-gradient(135deg, var(--warning, #92400e), var(--warning, #f59e0b));
}
.banner-card.tone-sky {
  background: linear-gradient(135deg, #0c4a6e, var(--info));
}
.banner-card.tone-rose {
  background: linear-gradient(135deg, var(--accent-rose), var(--accent-rose));
}
.banner-card.tone-mint {
  background: linear-gradient(135deg, var(--brand-deep), var(--success));
}
.banner-title {
  display: block;
  font-size: var(--font-size-h2);
  font-weight: 800;
}
.banner-sub {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-caption);
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
  border-radius: var(--radius-pill);
  background: rgba(255, 255, 255, 0.2);
  font-size: var(--font-size-sm);
}
.banner-mark {
  width: 96rpx;
  height: 96rpx;
  border-radius: var(--radius-card);
  background: rgba(6, 78, 59, 0.55);
  border: 2rpx solid rgba(255, 255, 255, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-h2);
  font-weight: 800;
  color: var(--white);
}

.entry {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 28rpx 24rpx;
  margin: 0 0 16rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
}
.entry.mint {
  background: linear-gradient(90deg, var(--white), var(--brand-soft));
  border: 1rpx solid var(--brand-soft, #d1fae5);
}
.entry-title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #1b3027);
}
.entry-sub {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
}
.entry-arrow {
  color: var(--text-subtle, #cbd5e1);
  flex-shrink: 0;
  width: 0.55em;
  height: 0.55em;
  font-size: var(--font-size-lg);
}

.section-title {
  margin: 18rpx 0 14rpx;
  font-size: var(--font-size-lg);
  font-weight: 750;
  color: var(--text-primary, #1b3027);
}
.campaign {
  padding: 26rpx 24rpx;
  margin-bottom: 16rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.campaign-badge {
  display: inline-flex;
  align-items: center;
  gap: 6rpx;
  padding: 6rpx 14rpx;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
  color: var(--brand-deep, #065f46);
  background: var(--brand-soft, #d1fae5);
}
.campaign-badge.tone-amber {
  color: var(--warning, #92400e);
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
}
.campaign-badge.tone-sky {
  color: #075985;
  background: var(--info-soft);
}
.campaign-badge.tone-rose {
  color: var(--accent-rose);
  background: var(--danger-soft);
}
.campaign-title {
  display: block;
  margin-top: 14rpx;
  font-size: var(--font-size-xl);
  font-weight: 750;
  color: var(--text-primary, #1b3027);
}
.campaign-desc {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #849087);
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
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.campaign-remain {
  font-size: var(--font-size-xs);
  color: var(--warning, #b45309);
  font-weight: 600;
}
.campaign-cta {
  font-size: var(--font-size-caption);
  color: var(--brand);
  font-weight: 700;
}
.campaign-cta.muted {
  color: var(--text-subtle);
}
.empty {
  text-align: center;
  padding: 60rpx 0;
  color: var(--text-subtle, #999);
}
.market-empty {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}
.market-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  margin-top: 8rpx;
}
.market-actions .app-btn + .app-btn {
  margin-top: 24rpx !important;
}
/* 高度/通栏由 App.vue / AppButton 统一（微信 88rpx 触控） */
.market-actions .app-btn {
  width: 100% !important;
  max-width: 100% !important;
  min-width: 0 !important;
  margin-left: 0 !important;
  margin-right: 0 !important;
  align-self: stretch !important;
  box-sizing: border-box !important;
}
</style>
