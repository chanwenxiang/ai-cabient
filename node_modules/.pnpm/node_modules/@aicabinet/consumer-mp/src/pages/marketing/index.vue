<template>
  <view class="page-root">
    <!-- 活动轮播 -->
    <swiper class="banner-swiper" autoplay circular :interval="5000">
      <swiper-item v-for="banner in banners" :key="banner.id">
        <image class="banner-image" :src="banner.image" mode="aspectFill" @click="goToCampaign(banner.campaignId)" />
      </swiper-item>
    </swiper>

    <!-- 活动列表 -->
    <view class="section">
      <text class="section-title">热门活动</text>
      <view class="campaign-list">
        <view 
          v-for="campaign in campaigns" 
          :key="campaign.id" 
          class="campaign-card"
          @click="goToCampaignDetail(campaign.id)"
        >
          <image class="campaign-image" :src="campaign.image" mode="aspectFill" />
          <view class="campaign-content">
            <text class="campaign-title">{{ campaign.title }}</text>
            <text class="campaign-desc">{{ campaign.description }}</text>
            <view class="campaign-footer">
              <view class="campaign-time">
                <text class="time-label">活动时间：</text>
                <text class="time-value">{{ campaign.startTime }} - {{ campaign.endTime }}</text>
              </view>
              <view class="campaign-tag" :class="'type-' + campaign.type">
                {{ getTypeLabel(campaign.type) }}
              </view>
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- 我的优惠券入口 -->
    <view class="coupon-entry" @click="goToMyCoupons">
      <view class="entry-left">
        <image class="entry-icon" src="/static/icons/coupon.png" mode="aspectFit" />
        <text class="entry-text">我的优惠券</text>
      </view>
      <view class="entry-right">
        <text class="coupon-count">{{ availableCouponCount }}张可用</text>
        <text class="entry-arrow">›</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { consumerApi } from '@/utils/consumer-api';

const banners = ref<any[]>([]);
const campaigns = ref<any[]>([]);
const availableCouponCount = ref(0);

onMounted(() => {
  loadBanners();
  loadCampaigns();
  loadCouponCount();
});

async function loadBanners() {
  try {
    const res = await consumerApi.get('/api/v2/marketing/banners');
    banners.value = res.data?.data ?? [];
  } catch (error) {
    console.error('加载轮播图失败', error);
  }
}

async function loadCampaigns() {
  try {
    const res = await consumerApi.get('/api/v2/marketing/campaigns/active');
    campaigns.value = res.data?.data ?? [];
  } catch (error) {
    console.error('加载活动列表失败', error);
  }
}

async function loadCouponCount() {
  try {
    const res = await consumerApi.get('/api/v2/coupons/my', {
      params: { status: 'available' }
    });
    availableCouponCount.value = res.data?.data?.total || 0;
  } catch (error) {
    console.error('加载优惠券数量失败', error);
  }
}

function getTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    'discount': '折扣',
    'full_reduce': '满减',
    'gift': '赠品',
    'new_user': '新人'
  };
  return labels[type] || '活动';
}

function goToCampaign(campaignId: number) {
  uni.navigateTo({ url: /pages/marketing/detail?id= });
}

function goToCampaignDetail(id: number) {
  uni.navigateTo({ url: /pages/marketing/detail?id= });
}

function goToMyCoupons() {
  uni.navigateTo({ url: '/pages/coupons/coupons' });
}
</script>

<style scoped>
.page-root {
  min-height: 100vh;
  background: #f5f5f5;
}

.banner-swiper {
  width: 100%;
  height: 360rpx;
}

.banner-image {
  width: 100%;
  height: 100%;
}

.section {
  background: #fff;
  margin: 24rpx;
  padding: 32rpx;
  border-radius: 16rpx;
}

.section-title {
  font-size: 32rpx;
  font-weight: bold;
  color: #333;
  display: block;
  margin-bottom: 24rpx;
}

.campaign-list {
  padding: 0;
}

.campaign-card {
  display: flex;
  background: #fff;
  border-radius: 12rpx;
  overflow: hidden;
  margin-bottom: 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.05);
}

.campaign-image {
  width: 240rpx;
  height: 180rpx;
}

.campaign-content {
  flex: 1;
  padding: 16rpx;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.campaign-title {
  font-size: 28rpx;
  font-weight: bold;
  color: #333;
  display: block;
  margin-bottom: 8rpx;
}

.campaign-desc {
  font-size: 24rpx;
  color: #666;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.campaign-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8rpx;
}

.campaign-time {
  display: flex;
  flex-direction: column;
}

.time-label {
  font-size: 20rpx;
  color: #999;
}

.time-value {
  font-size: 22rpx;
  color: #666;
}

.campaign-tag {
  font-size: 22rpx;
  padding: 4rpx 12rpx;
  border-radius: 4rpx;
  background: #fff7e6;
  color: #fa8c16;
}

.campaign-tag.type-discount {
  background: #f6ffed;
  color: #52c41a;
}

.campaign-tag.type-new_user {
  background: #fff0f6;
  color: #eb2f96;
}

.coupon-entry {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
  margin: 24rpx;
  padding: 32rpx;
  border-radius: 16rpx;
}

.entry-left {
  display: flex;
  align-items: center;
}

.entry-icon {
  width: 48rpx;
  height: 48rpx;
  margin-right: 16rpx;
}

.entry-text {
  font-size: 28rpx;
  color: #333;
}

.entry-right {
  display: flex;
  align-items: center;
}

.coupon-count {
  font-size: 24rpx;
  color: #ff4d4f;
  margin-right: 16rpx;
}

.entry-arrow {
  font-size: 32rpx;
  color: #ccc;
}
</style>
