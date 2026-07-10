<template>
  <view class="login-wrap">
    <view class="hero">
      <text class="brand">AI开门柜</text>
      <text class="tagline">商户运营中心</text>
    </view>
    <view class="card form-card">
      <text class="title">商户登录</text>
      <input v-model="phone" class="input" type="number" maxlength="11" placeholder="手机号" />
      <input v-model="password" class="input" password placeholder="密码" />
      <view class="btn-primary" @click="onLogin">{{ loading ? '登录中…' : '登录' }}</view>
      <text v-if="err" class="err">{{ err }}</text>
      <text class="hint">演示：13800138001 / 123456</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { merchantLogin, merchantApi } from '@/utils/merchant-api';

const phone = ref('13800138001');
const password = ref('123456');
const loading = ref(false);
const err = ref('');

async function onLogin() {
  loading.value = true;
  err.value = '';
  try {
    await merchantLogin(phone.value.trim(), password.value);
    const me = await merchantApi.me();
    uni.setStorageSync('merchant_me', me);
    uni.switchTab({ url: '/pages/home/home' });
  } catch (e) {
    err.value = e instanceof Error ? e.message : '登录失败';
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-wrap { min-height: 100vh; background: linear-gradient(160deg, #134e4a 0%, #0f766e 60%, #14b8a6 100%); }
.hero { padding: 80rpx 40rpx 40rpx; color: #fff; }
.brand { font-size: 52rpx; font-weight: 800; display: block; }
.tagline { font-size: 28rpx; opacity: 0.9; display: block; margin-top: 8rpx; }
.form-card { margin: 0 24rpx 40rpx; border-radius: 24rpx; }
.title { font-size: 40rpx; font-weight: 700; display: block; margin-bottom: 24rpx; color: #1e293b; }
.input { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 24rpx; margin-bottom: 16rpx; font-size: 28rpx; }
.err { color: #ef4444; display: block; margin-top: 16rpx; text-align: center; }
.hint { color: #94a3b8; font-size: 22rpx; display: block; margin-top: 16rpx; text-align: center; }
</style>
