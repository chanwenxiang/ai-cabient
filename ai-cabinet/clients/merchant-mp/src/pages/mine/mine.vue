<template>
  <view>
    <view class="profile-header">
      <view class="avatar">🏪</view>
      <view class="profile-info">
        <text class="hello">{{ meName }}</text>
        <text class="sub">{{ merchantNames }}</text>
        <text class="phone">{{ phone }}</text>
      </view>
    </view>

    <view class="menu-list">
      <view class="menu-cell" @click="goBusiness">
        <text class="menu-icon">📈</text>
        <view class="menu-text"><text class="menu-title">经营分析</text><text class="menu-desc">营收、毛利、商品表现和结算</text></view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goPricing">
        <text class="menu-icon">💰</text>
        <view class="menu-text">
          <text class="menu-title">点位定价</text>
          <text class="menu-desc">按柜机覆盖 SKU 价格</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goReplenishment">
        <text class="menu-icon">📦</text>
        <view class="menu-text"><text class="menu-title">补货任务</text><text class="menu-desc">签到、核对商品并确认上架</text></view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell danger-cell" @click="onLogout">
        <text class="menu-icon">🚪</text>
        <view class="menu-text">
          <text class="menu-title danger">退出登录</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { clearSession } from '@/utils/merchant-api';
import type { MerchantMe } from '@aicabinet/shared-types';

const meName = ref('');
const merchantNames = ref('');
const phone = ref('');

onShow(() => {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const me = (uni.getStorageSync('merchant_me') || {}) as MerchantMe;
  meName.value = me.displayName || me.phoneNumber || '商户';
  merchantNames.value = (me.merchants || []).map((m) => m.merchantName).join('、') || '未绑定';
  phone.value = me.phoneNumber || '';
});

function goPricing() {
  uni.navigateTo({ url: '/pages/pricing/pricing' });
}
function goBusiness() { uni.navigateTo({ url: '/pages/business/business' }); }
function goReplenishment() { uni.navigateTo({ url: '/pages/replenishment/replenishment' }); }

function onLogout() {
  clearSession();
  uni.reLaunch({ url: '/pages/login/login' });
}
</script>

<style scoped>
.profile-header { background: linear-gradient(135deg, #134e4a, #0f766e); padding: 48rpx 32rpx; display: flex; align-items: center; gap: 24rpx; }
.avatar { width: 100rpx; height: 100rpx; border-radius: 50%; background: rgba(255,255,255,0.25); display: flex; align-items: center; justify-content: center; font-size: 48rpx; }
.profile-info { color: #fff; }
.hello { font-size: 36rpx; font-weight: 700; display: block; }
.sub { font-size: 26rpx; opacity: 0.9; display: block; margin-top: 4rpx; }
.phone { font-size: 24rpx; opacity: 0.75; display: block; margin-top: 4rpx; }
.menu-list {
  margin: 12px;
  padding-bottom: calc(120rpx + env(safe-area-inset-bottom));
}
.menu-cell { background: #fff; border-radius: 16px; padding: 28rpx 24rpx; margin-bottom: 12rpx; display: flex; align-items: center; gap: 20rpx; box-shadow: 0 2px 12px rgba(15,118,110,0.06); }
.menu-icon { font-size: 40rpx; }
.menu-text { flex: 1; }
.menu-title { font-size: 30rpx; font-weight: 500; display: block; color: #1e293b; }
.menu-desc { font-size: 24rpx; color: #94a3b8; display: block; margin-top: 4rpx; }
.menu-arrow { color: #cbd5e1; font-size: 36rpx; }
.danger { color: #ef4444; }
</style>
