<template>
  <view class="page">
    <view class="profile-header">
      <view class="avatar">{{ avatarText }}</view>
      <view class="profile-info">
        <text class="hello">{{ meName }}</text>
        <text class="sub">{{ merchantNames }}</text>
        <text v-if="phone" class="phone">{{ phone }}</text>
      </view>
    </view>

    <view class="section-label">现场作业</view>
    <view class="menu-list">
      <view class="menu-cell highlight" @click="goReplenishment">
        <text class="menu-icon">📦</text>
        <view class="menu-text">
          <text class="menu-title">补货任务</text>
          <text class="menu-desc">扫码到柜 · 签到 · 核对上架</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goDevices">
        <text class="menu-icon">🗄️</text>
        <view class="menu-text">
          <text class="menu-title">柜机管理</text>
          <text class="menu-desc">在线状态 · 货道库存</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goAlerts">
        <text class="menu-icon">🔔</text>
        <view class="menu-text">
          <text class="menu-title">待办事项</text>
          <text class="menu-desc">缺货 · 临期 · 离线 · 争议</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view class="section-label">经营工具</view>
    <view class="menu-list">
      <view class="menu-cell" @click="goPricing">
        <text class="menu-icon">¥</text>
        <view class="menu-text">
          <text class="menu-title">点位定价</text>
          <text class="menu-desc">按柜机调整 SKU 售价</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goSettlements">
        <text class="menu-icon">📑</text>
        <view class="menu-text">
          <text class="menu-title">结算对账</text>
          <text class="menu-desc">日结与对账单导出</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goDisputes">
        <text class="menu-icon">⚖️</text>
        <view class="menu-text">
          <text class="menu-title">争议处理</text>
          <text class="menu-desc">消费者账单申诉</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goBusiness">
        <text class="menu-icon">📈</text>
        <view class="menu-text">
          <text class="menu-title">经营分析</text>
          <text class="menu-desc">营收、毛利与商品表现</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view class="menu-list">
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
import { computed, ref } from 'vue';
import { clearSession } from '@/utils/merchant-api';
import type { MerchantMe } from '@aicabinet/shared-types';

const meName = ref('');
const merchantNames = ref('');
const phone = ref('');
const avatarText = computed(() => (meName.value || '商').slice(0, 1));

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
function goBusiness() {
  uni.navigateTo({ url: '/pages/business/business' });
}
function goReplenishment() {
  uni.navigateTo({ url: '/pages/replenishment/replenishment' });
}
function goSettlements() {
  uni.navigateTo({ url: '/pages/settlements/settlements' });
}
function goDisputes() {
  uni.navigateTo({ url: '/pages/disputes/disputes' });
}
function goDevices() {
  uni.switchTab({ url: '/pages/devices/devices' });
}
function goAlerts() {
  uni.switchTab({ url: '/pages/alerts/alerts' });
}

function onLogout() {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前账户吗？',
    confirmText: '退出',
    success(res) {
      if (!res.confirm) return;
      clearSession();
      uni.reLaunch({ url: '/pages/login/login' });
    }
  });
}
</script>

<style scoped>
.page {
  min-height: 100%;
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
  background: linear-gradient(180deg, #ecfdf5 0, #f0fdfa 280rpx, #f0fdfa 100%);
}
.profile-header {
  margin: 20rpx 24rpx 0;
  padding: 40rpx 32rpx;
  border-radius: 28rpx;
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: linear-gradient(145deg, #134e4a, #0f766e 60%, #14b8a6);
  box-shadow: 0 16rpx 40rpx rgba(15, 118, 110, 0.2);
  color: #fff;
}
.avatar {
  width: 100rpx;
  height: 100rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  border: 2rpx solid rgba(255, 255, 255, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 44rpx;
  font-weight: 700;
}
.profile-info { flex: 1; min-width: 0; }
.hello { font-size: 36rpx; font-weight: 700; display: block; }
.sub { font-size: 26rpx; opacity: 0.9; display: block; margin-top: 4rpx; }
.phone { font-size: 24rpx; opacity: 0.75; display: block; margin-top: 4rpx; }
.section-label {
  margin: 28rpx 32rpx 10rpx;
  font-size: 22rpx;
  color: #94a3b8;
  letter-spacing: 1rpx;
}
.menu-list { margin: 0 24rpx; }
.menu-cell {
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx 24rpx;
  margin-bottom: 12rpx;
  display: flex;
  align-items: center;
  gap: 20rpx;
  border: 1rpx solid #e2e8f0;
  box-shadow: 0 6rpx 18rpx rgba(15, 118, 110, 0.05);
}
.menu-cell.highlight {
  border-color: #99f6e4;
  background: linear-gradient(90deg, #fff, #f0fdfa);
}
.menu-icon {
  width: 72rpx;
  height: 72rpx;
  border-radius: 18rpx;
  background: #f0fdfa;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34rpx;
  color: #0f766e;
  font-weight: 700;
}
.menu-text { flex: 1; min-width: 0; }
.menu-title { font-size: 30rpx; font-weight: 600; display: block; color: #1e293b; }
.menu-desc { font-size: 24rpx; color: #94a3b8; display: block; margin-top: 4rpx; }
.menu-arrow { color: #cbd5e1; font-size: 36rpx; }
.danger { color: #ef4444; }
.danger-cell { background: #fffafa; }
.danger-cell .menu-icon { background: #fff1f0; }
</style>
