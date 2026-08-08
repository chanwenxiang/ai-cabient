<template>
  <view class="login-wrap">
    <view class="login-bg-scene" aria-hidden="true">
      <image class="login-illustration login-illustration-anim" :src="loginBgUrl" mode="aspectFill" />
      <view class="anim-orb anim-orb-a" />
      <view class="anim-orb anim-orb-b" />
      <view class="anim-shimmer" />
    </view>
    <view class="login-overlay" />
    <view class="login-content">
      <view class="hero">
        <text class="brand">AI开门柜</text>
        <text class="tagline">补货与运营</text>
        <view class="badge">
          <text class="badge-icon">◆</text>
          <text class="badge-text">扫码到柜 · 补货上架 · 经营对账</text>
        </view>
      </view>

      <view class="login-spacer" />

      <view class="form-card">
        <text class="title">登录</text>
        <text class="subtitle">补货员与商户运营共用入口</text>

        <view class="field">
          <text class="field-label">手机号</text>
          <input
            v-model="phone"
            class="input"
            type="number"
            maxlength="11"
            aria-label="手机号"
            placeholder="请输入11位手机号…"
            placeholder-class="ph"
            confirm-type="next"
            data-testid="login-phone"
          />
        </view>
        <view class="field">
          <text class="field-label">密码</text>
          <input
            v-model="password"
            class="input"
            password
            aria-label="密码"
            placeholder="请输入登录密码…"
            placeholder-class="ph"
            confirm-type="go"
            data-testid="login-password"
            @confirm="onLogin"
          />
        </view>

        <view
          class="btn-primary"
          :class="{ disabled: loading }"
          role="button"
          data-testid="login-submit"
          @click="onLogin"
          >{{ loading ? '登录中…' : '登录' }}</view
        >
        <text v-if="err" class="err" data-testid="login-error">{{ err }}</text>
        <text v-if="isDev" class="hint">开发演示：13800138001 / 123456</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { merchantLogin, merchantApi } from '@/utils/merchant-api';
import { showDevTools } from '@/utils/runtime-flags';
import loginBgUrl from '@/static/bg-vending-night.jpg';

const isDev = showDevTools();
const phone = ref(isDev ? '13800138001' : '');
const password = ref(isDev ? '123456' : '');
const loading = ref(false);
const err = ref('');

async function onLogin() {
  if (loading.value) return;
  const phoneNumber = phone.value.trim();
  const pwd = password.value;
  if (!/^1\d{10}$/.test(phoneNumber)) {
    err.value = '请输入11位手机号';
    return;
  }
  if (!pwd) {
    err.value = '请输入登录密码';
    return;
  }
  loading.value = true;
  err.value = '';
  try {
    await merchantLogin(phoneNumber, pwd);
    try {
      const me = await merchantApi.me();
      uni.setStorageSync('merchant_me', me);
    } catch {
      /* 工作台 onShow 会再拉 profile */
    }
    uni.switchTab({ url: '/pages/home/home' });
  } catch (e) {
    err.value = e instanceof Error ? e.message : '登录失败';
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-wrap {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background: linear-gradient(180deg, #0b1220 0%, #0e3a46 100%);
}
.login-bg-scene {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
  overflow: hidden;
}
.login-illustration {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
}
.login-illustration-anim {
  animation: illusKenBurns 22s ease-in-out infinite alternate;
  transform-origin: center top;
}
.anim-orb {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
  filter: blur(24rpx);
}
.anim-orb-a {
  top: 10%;
  right: 8%;
  width: 190rpx;
  height: 190rpx;
  background: rgba(45, 212, 191, 0.3);
  animation: orbFloatA 9s ease-in-out infinite;
}
.anim-orb-b {
  top: 36%;
  left: 5%;
  width: 170rpx;
  height: 170rpx;
  background: rgba(56, 189, 248, 0.26);
  animation: orbFloatB 11s ease-in-out infinite;
}
.anim-shimmer {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    115deg,
    transparent 38%,
    rgba(255, 255, 255, 0.16) 50%,
    transparent 62%
  );
  background-size: 220% 220%;
  animation: shimmerSweep 10s ease-in-out infinite;
  pointer-events: none;
}
@keyframes illusKenBurns {
  from {
    transform: scale(1) translateY(0);
  }
  to {
    transform: scale(1.045) translateY(-10rpx);
  }
}
@keyframes orbFloatA {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
    opacity: 0.55;
  }
  50% {
    transform: translate(-14rpx, 18rpx) scale(1.08);
    opacity: 0.85;
  }
}
@keyframes orbFloatB {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
    opacity: 0.45;
  }
  50% {
    transform: translate(18rpx, -12rpx) scale(1.06);
    opacity: 0.75;
  }
}
@keyframes shimmerSweep {
  0%,
  100% {
    background-position: 120% 0;
    opacity: 0.35;
  }
  50% {
    background-position: -20% 0;
    opacity: 0.7;
  }
}
@media (prefers-reduced-motion: reduce) {
  .login-illustration-anim,
  .anim-orb-a,
  .anim-orb-b,
  .anim-shimmer {
    animation: none;
  }
}
.login-overlay {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 1;
  background: linear-gradient(
    180deg,
    rgba(8, 26, 30, 0.46) 0%,
    rgba(8, 26, 30, 0.18) 36%,
    rgba(8, 26, 30, 0.3) 64%,
    rgba(6, 22, 26, 0.82) 100%
  );
}
.login-content {
  position: relative;
  z-index: 2;
  min-height: 100vh;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: calc(48rpx + env(safe-area-inset-top)) 32rpx calc(32rpx + env(safe-area-inset-bottom));
}
.hero {
  flex-shrink: 0;
  padding-top: 0;
  text-align: center;
}
.brand {
  font-size: 56rpx;
  font-weight: 800;
  display: block;
  color: #f0fdfa;
  letter-spacing: 2rpx;
}
.tagline {
  font-size: 30rpx;
  color: #a5b4c8;
  display: block;
  margin-top: 10rpx;
}
.badge {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  margin-top: 18rpx;
  padding: 10rpx 22rpx;
  border-radius: 999rpx;
  background: rgba(13, 148, 136, 0.18);
}
.badge-icon {
  color: #2dd4bf;
  font-size: 22rpx;
}
.badge-text {
  color: #cbd5e1;
  font-size: 22rpx;
}
.login-spacer {
  flex: 0 0 auto;
  min-height: 40rpx;
}
.form-card {
  flex-shrink: 0;
  padding: 36rpx 32rpx 40rpx;
  border-radius: 32rpx 32rpx 24rpx 24rpx;
  background: rgba(8, 24, 30, 0.58);
  border: 2rpx solid rgba(148, 210, 198, 0.22);
  backdrop-filter: blur(52rpx);
  box-shadow:
    0 -8rpx 40rpx rgba(2, 12, 16, 0.18),
    0 16rpx 48rpx rgba(2, 10, 14, 0.42);
}
.title {
  font-size: 36rpx;
  font-weight: 700;
  display: block;
  margin-bottom: 8rpx;
  color: #f0fdfa;
}
.subtitle {
  font-size: 24rpx;
  color: rgba(204, 251, 241, 0.74);
  display: block;
  margin-bottom: 32rpx;
  line-height: 1.5;
}
.field {
  margin-bottom: 20rpx;
}
.field-label {
  display: block;
  font-size: 26rpx;
  color: #ccfbf1;
  font-weight: 500;
  margin-bottom: 10rpx;
}
.input {
  display: block;
  width: 100%;
  height: 88rpx;
  box-sizing: border-box;
  background: rgba(8, 24, 30, 0.42);
  border: 2rpx solid rgba(148, 210, 198, 0.3);
  border-radius: 16rpx;
  padding: 0 28rpx;
  font-size: 28rpx;
  color: #f0fdfa;
  line-height: 88rpx;
  backdrop-filter: blur(16rpx);
}
.input:focus {
  border-color: rgba(94, 234, 212, 0.7);
  background: rgba(8, 24, 30, 0.55);
}
.ph {
  color: rgba(204, 251, 241, 0.4);
}
.btn-primary {
  margin-top: 12rpx;
  background: linear-gradient(135deg, #0f766e, #059669);
  color: #fff;
  border-radius: 44rpx;
  height: 96rpx;
  line-height: 96rpx;
  text-align: center;
  font-size: 30rpx;
  font-weight: 600;
  box-shadow: 0 10rpx 28rpx rgba(15, 118, 110, 0.28);
}
.btn-primary.disabled {
  opacity: 0.55;
  pointer-events: none;
}
.err {
  color: #ef4444;
  display: block;
  margin-top: 16rpx;
  text-align: center;
  font-size: 26rpx;
}
.hint {
  color: rgba(204, 251, 241, 0.62);
  font-size: 22rpx;
  display: block;
  margin-top: 20rpx;
  text-align: center;
  opacity: 0.72;
}
</style>
