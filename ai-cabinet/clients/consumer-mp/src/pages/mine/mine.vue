<template>
  <view>
    <view class="profile-header">
      <view class="avatar">{{ avatarText }}</view>
      <view class="profile-info">
        <text class="hello">{{ authed ? displayName : '游客模式' }}</text>
        <text class="balance">{{ authed ? `余额 ¥${balanceYuan}` : '扫码购物无需注册' }}</text>
        <view v-if="authed" class="tags">
          <text class="tag" :class="verified ? 'ok' : 'warn'">{{ verified ? '已实名' : '待实名' }}</text>
          <text class="tag" :class="payReady ? 'ok' : 'warn'">{{ payReady ? '支付已开通' : '待开通支付' }}</text>
        </view>
      </view>
    </view>

    <view v-if="authed && needsSetup" class="setup-banner" @click="goVerify">
      <view class="setup-text">
        <text class="setup-title">完成开门准备</text>
        <text class="setup-desc">{{ setupHint }}</text>
      </view>
      <text class="setup-arrow">去设置 ›</text>
    </view>

    <view class="menu-list">
      <view v-if="authed && !verified" class="menu-cell highlight" @click="goVerify">
        <text class="menu-icon">🪪</text>
        <view class="menu-text">
          <text class="menu-title">实名认证</text>
          <text class="menu-desc">填写姓名与身份证后四位</text>
        </view>
        <text class="menu-badge">待完成</text>
        <text class="menu-arrow">›</text>
      </view>
      <view v-if="authed && verified && !payReady" class="menu-cell highlight" @click="goVerify">
        <text class="menu-icon">💳</text>
        <view class="menu-text">
          <text class="menu-title">开通微信支付分</text>
          <text class="menu-desc">免押金开门，关门自动扣款</text>
        </view>
        <text class="menu-badge">待开通</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goIndex">
        <text class="menu-icon">🛒</text>
        <view class="menu-text">
          <text class="menu-title">开门购物</text>
          <text class="menu-desc">扫码开门，取货即走</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goOrders">
        <text class="menu-icon">📋</text>
        <view class="menu-text">
          <text class="menu-title">我的订单</text>
          <text class="menu-desc">查看历史购物记录</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goReport">
        <text class="menu-icon">🔧</text>
        <view class="menu-text">
          <text class="menu-title">故障报修</text>
          <text class="menu-desc">柜机打不开、门关不上等问题</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goLogin">
        <text class="menu-icon">📱</text>
        <view class="menu-text">
          <text class="menu-title">手机号验证</text>
          <text class="menu-desc">绑定演示账号或已有账户</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view v-if="authed" class="menu-cell danger-cell" @click="onLogout">
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
import type { AccountDto } from '@aicabinet/shared-types';
import { clearConsumerSession, consumerApi, ensureConsumerAuth, getConsumerToken } from '@/utils/consumer-api';

const balanceYuan = ref('-');
const authed = ref(false);
const account = ref<AccountDto | null>(null);

const verified = computed(() => !!account.value?.verified);
const payReady = computed(
  () => !!account.value?.passwordFreeReady || (account.value?.balanceCents || 0) >= 500
);
const needsSetup = computed(() => !verified.value || !payReady.value);
const displayName = computed(() => (verified.value ? '我的账户' : '我的账户（待实名）'));
const avatarText = computed(() => account.value?.realName?.slice(0, 1) || '我');
const setupHint = computed(() => {
  if (!verified.value) return '需先完成实名认证';
  if (!payReady.value) return '需开通微信支付分或充值余额';
  return '';
});

onShow(async () => {
  await ensureConsumerAuth();
  authed.value = !!getConsumerToken();
  if (!authed.value) {
    balanceYuan.value = '-';
    account.value = null;
    return;
  }
  try {
    account.value = await consumerApi.account();
    balanceYuan.value = ((account.value.balanceCents || 0) / 100).toFixed(2);
  } catch {
    balanceYuan.value = '-';
    account.value = null;
  }
});

function goVerify() {
  uni.navigateTo({ url: '/pages/verify/verify' });
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/mine/mine') });
}

function goIndex() {
  uni.switchTab({ url: '/pages/index/index' });
}

function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}

function goReport() {
  const id = uni.getStorageSync('last_device_id') || '';
  uni.navigateTo({
    url: id
      ? `/pages/report/report?deviceId=${encodeURIComponent(id)}`
      : '/pages/report/report'
  });
}

function onLogout() {
  clearConsumerSession();
  authed.value = false;
  account.value = null;
  balanceYuan.value = '-';
  uni.showToast({ title: '已退出', icon: 'none' });
}
</script>

<style scoped>
.profile-header { background: linear-gradient(135deg, #07c160, #06ae56); padding: 48rpx 32rpx; display: flex; align-items: center; gap: 24rpx; }
.avatar { width: 100rpx; height: 100rpx; border-radius: 50%; background: rgba(255,255,255,0.25); display: flex; align-items: center; justify-content: center; font-size: 48rpx; }
.profile-info { color: #fff; flex: 1; }
.hello { font-size: 36rpx; font-weight: 700; display: block; }
.balance { font-size: 28rpx; opacity: 0.9; display: block; margin-top: 4rpx; }
.tags { display: flex; gap: 12rpx; margin-top: 12rpx; flex-wrap: wrap; }
.tag { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 8rpx; background: rgba(255,255,255,0.2); }
.tag.ok { background: rgba(255,255,255,0.35); }
.tag.warn { background: #fff3cd; color: #856404; }
.setup-banner { margin: 12px; background: #fff7e6; border: 1rpx solid #ffd591; border-radius: 16px; padding: 24rpx; display: flex; align-items: center; justify-content: space-between; }
.setup-title { font-size: 30rpx; font-weight: 600; color: #d48806; display: block; }
.setup-desc { font-size: 24rpx; color: #ad6800; display: block; margin-top: 4rpx; }
.setup-arrow { color: #d48806; font-size: 28rpx; font-weight: 500; white-space: nowrap; margin-left: 16rpx; }
.menu-list { margin: 12px; }
.menu-cell { background: #fff; border-radius: 16px; padding: 28rpx 24rpx; margin-bottom: 12rpx; display: flex; align-items: center; gap: 20rpx; box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.menu-cell.highlight { border: 1rpx solid #07c160; }
.menu-icon { font-size: 40rpx; }
.menu-text { flex: 1; min-width: 0; }
.menu-title { font-size: 30rpx; font-weight: 500; display: block; color: #191919; }
.menu-desc { font-size: 24rpx; color: #888; display: block; margin-top: 4rpx; }
.menu-badge { font-size: 22rpx; color: #fa5151; background: #fff1f0; padding: 4rpx 12rpx; border-radius: 8rpx; }
.menu-arrow { color: #ccc; font-size: 36rpx; }
.danger { color: #fa5151; }
</style>
