<template>
  <view class="login-wrap">
    <view class="hero">
      <text class="brand">AI开门柜</text>
      <text class="tagline">扫码开门 · 取货即走</text>
      <view class="steps">
        <view class="step"><text class="step-num">1</text><text class="step-text">扫码</text></view>
        <text class="step-arrow">→</text>
        <view class="step"><text class="step-num">2</text><text class="step-text">开门</text></view>
        <text class="step-arrow">→</text>
        <view class="step"><text class="step-num">3</text><text class="step-text">结算</text></view>
      </view>
    </view>

    <view class="card form-card">
      <text class="title">手机号验证</text>
      <text class="subtitle">扫码购物无需注册；仅在绑定已有账户或微信授权失败时使用</text>
      <view class="tabs">
        <text :class="mode === 'sms' ? 'tab on' : 'tab'" @click="mode = 'sms'">验证码</text>
        <text :class="mode === 'password' ? 'tab on' : 'tab'" @click="mode = 'password'">密码</text>
      </view>
      <input v-model="phone" class="input" type="number" maxlength="11" placeholder="请输入手机号" />
      <input v-if="mode === 'password'" v-model="password" class="input" password placeholder="请输入密码" />
      <view v-else class="row">
        <input v-model="code" class="input flex" placeholder="请输入验证码" />
        <view class="btn-code" @click="onSendCode">{{ codeCooldown ? codeCooldown + 's' : '获取验证码' }}</view>
      </view>
      <view class="btn-primary" @click="onLogin">{{ loading ? '验证中…' : '验证并继续' }}</view>
      <view class="btn-ghost" @click="goBack">返回购物</view>
      <text v-if="err" class="err">{{ err }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { consumerPasswordLogin, consumerSmsLogin, consumerWxLogin, sendSmsCode } from '@/utils/consumer-api';

const redirect = ref('/pages/index/index');

const mode = ref<'password' | 'sms'>('sms');
const phone = ref('');
const password = ref('');
const code = ref('');
const loading = ref(false);
const err = ref('');
const codeCooldown = ref(0);
let codeTimer: ReturnType<typeof setInterval> | null = null;

onLoad((opts) => {
  if (opts?.redirect) redirect.value = decodeURIComponent(String(opts.redirect));
});

function goBack() {
  if (redirect.value.startsWith('/pages/index') || redirect.value.startsWith('/pages/orders') || redirect.value.startsWith('/pages/mine')) {
    uni.switchTab({ url: redirect.value.split('?')[0] });
  } else {
    uni.redirectTo({ url: redirect.value });
  }
}

async function onSendCode() {
  if (codeCooldown.value || !phone.value.trim()) return;
  try {
    await sendSmsCode(phone.value.trim());
    codeCooldown.value = 60;
    codeTimer = setInterval(() => {
      codeCooldown.value -= 1;
      if (codeCooldown.value <= 0 && codeTimer) clearInterval(codeTimer);
    }, 1000);
    uni.showToast({ title: '验证码已发送', icon: 'none' });
  } catch (e) {
    err.value = e instanceof Error ? e.message : '发送失败';
  }
}

async function onLogin() {
  loading.value = true;
  err.value = '';
  try {
    if (mode.value === 'password') {
      await consumerPasswordLogin(phone.value.trim(), password.value);
    } else {
      await consumerSmsLogin(phone.value.trim(), code.value.trim());
    }
    try {
      const wxCode = await new Promise<string>((resolve, reject) => {
        uni.login({ provider: 'weixin', success: (r) => (r.code ? resolve(r.code) : reject()), fail: reject });
      });
      await consumerWxLogin(wxCode, phone.value.trim());
    } catch {
      /* 非微信环境或绑定失败时仍可用手机号会话 */
    }
    goBack();
  } catch (e) {
    err.value = e instanceof Error ? e.message : '验证失败';
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-wrap { min-height: 100vh; background: linear-gradient(160deg, #059669 0%, #14b8a6 60%, #5eead4 100%); }
.hero { padding: 80rpx 40rpx 40rpx; color: #fff; }
.brand { font-size: 52rpx; font-weight: 800; display: block; }
.tagline { font-size: 28rpx; opacity: 0.9; display: block; margin-top: 8rpx; }
.steps { display: flex; align-items: center; gap: 12rpx; margin-top: 40rpx; }
.step { display: flex; align-items: center; gap: 8rpx; background: rgba(255,255,255,0.2); border-radius: 24rpx; padding: 8rpx 16rpx; }
.step-num { width: 36rpx; height: 36rpx; background: #fff; color: #059669; border-radius: 50%; text-align: center; line-height: 36rpx; font-size: 22rpx; font-weight: 700; }
.step-text { font-size: 24rpx; }
.step-arrow { opacity: 0.6; font-size: 24rpx; }
.form-card { margin: 0 24rpx 40rpx; border-radius: 24rpx; }
.title { font-size: 40rpx; font-weight: 700; display: block; margin-bottom: 12rpx; color: #1e293b; }
.subtitle { font-size: 24rpx; color: #64748b; display: block; margin-bottom: 24rpx; line-height: 1.5; }
.btn-ghost { margin-top: 16rpx; text-align: center; color: #64748b; font-size: 28rpx; padding: 12rpx; }
.tabs { display: flex; gap: 32rpx; margin-bottom: 24rpx; border-bottom: 1px solid #f1f5f9; padding-bottom: 12rpx; }
.tab { padding: 8rpx 0; color: #94a3b8; font-size: 28rpx; }
.tab.on { color: #059669; font-weight: 600; border-bottom: 3px solid #059669; }
.input { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 24rpx; margin-bottom: 16rpx; font-size: 28rpx; }
.row { display: flex; gap: 12rpx; margin-bottom: 16rpx; }
.flex { flex: 1; margin-bottom: 0; }
.btn-code { background: #ecfdf5; color: #059669; padding: 24rpx 20rpx; border-radius: 12px; white-space: nowrap; font-size: 26rpx; font-weight: 600; }
.err { color: #ef4444; display: block; margin-top: 16rpx; text-align: center; font-size: 26rpx; }
</style>
