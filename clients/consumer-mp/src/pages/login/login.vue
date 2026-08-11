<template>
  <view class="login-wrap" :class="{ 'phone-open': showPhoneForm }">
    <view class="login-bg-scene" aria-hidden="true">
      <image
        class="login-illustration login-illustration-anim"
        :src="loginBgUrl"
        mode="aspectFill"
      />
      <view class="anim-orb anim-orb-a" />
      <view class="anim-orb anim-orb-b" />
      <view class="anim-shimmer" />
    </view>
    <view class="login-overlay" />
    <view class="login-content">
      <view class="hero">
        <text class="brand">AI开门柜</text>
        <text class="tagline">扫码开门 · 拿了就走</text>
        <view class="badge">
          <text class="badge-icon">✓</text>
          <text class="badge-text">关门自动结算</text>
        </view>
      </view>

      <view class="login-spacer" :class="{ compact: showPhoneForm }" />

      <view class="form-card">
        <text class="title">登录后继续</text>
        <text class="subtitle">微信授权即可开门购物；手机号仅作备用验证</text>

        <view
          class="btn-wx"
          role="button"
          data-testid="login-wx"
          :class="{ disabled: loading }"
          @click="onWxLogin"
        >
          {{ loading && wxMode ? '授权中…' : wxBtnLabel }}
        </view>

        <view class="divider" role="separator" aria-hidden="true">
          <view class="divider-line" />
          <view
            class="divider-toggle"
            role="button"
            :aria-expanded="showPhoneForm ? 'true' : 'false'"
            :aria-label="showPhoneForm ? '收起手机号登录' : '其他方式登录'"
            data-testid="login-other-method"
            @click="showPhoneForm = !showPhoneForm"
          >
            <text class="divider-text">
              {{ showPhoneForm ? '收起手机号登录' : '其他方式' }}
            </text>
          </view>
          <view class="divider-line" />
        </view>

        <view v-if="showPhoneForm">
          <view class="tabs">
            <view :class="['tab-item', mode === 'sms' ? 'on' : '']" @click="mode = 'sms'"
              >验证码</view
            >
            <view :class="['tab-item', mode === 'password' ? 'on' : '']" @click="mode = 'password'"
              >密码</view
            >
          </view>

          <view class="field">
            <text class="field-label">手机号</text>
            <input
              class="input"
              type="number"
              maxlength="11"
              :value="phone"
              placeholder="请输入11位手机号"
              placeholder-class="ph"
              @input="phone = eventInputValue($event)"
            />
          </view>

          <view v-if="mode === 'password'" class="field">
            <text class="field-label">密码</text>
            <input
              class="input"
              password
              :value="password"
              placeholder="请输入登录密码"
              placeholder-class="ph"
              @input="password = eventInputValue($event)"
            />
          </view>
          <view v-else class="field">
            <text class="field-label">验证码</text>
            <view class="row">
              <input
                class="input flex"
                type="number"
                maxlength="6"
                :value="code"
                placeholder="请输入验证码"
                placeholder-class="ph"
                @input="code = eventInputValue($event)"
              />
              <view
                class="btn-code"
                :class="{ disabled: !!codeCooldown || sendingCode }"
                @click="onSendCode"
              >
                {{ sendingCode ? '发送中…' : codeCooldown ? codeCooldown + 's' : '获取验证码' }}
              </view>
            </view>
          </view>

          <view
            class="btn-primary"
            role="button"
            data-testid="login-submit"
            :class="{ disabled: loading }"
            @click="onLogin"
          >
            {{ loading && !wxMode ? '验证中…' : '验证并继续' }}
          </view>
          <text v-if="isDev" class="dev-hint">开发联调：13800138000 / 验证码 123456</text>
        </view>

        <view class="btn-ghost" @click="goBack">返回</view>
        <text v-if="err" class="err">{{ err }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onUnload } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import {
  consumerApi,
  consumerPasswordLogin,
  consumerSmsLogin,
  consumerWxH5Login,
  consumerWxLogin,
  ensureConsumerAuth,
  sendSmsCode
} from '@/utils/consumer-api';
import { eventInputValue, readDomFieldValue, readDomPassword } from '@/utils/form-bind';
import { showDevTools } from '@/utils/runtime-flags';
import loginBgUrl from '@/static/bg-cooler.jpg';

const redirect = ref('/pages/index/index');
// H5 无法微信静默授权，默认展开手机号，减少多点一次
const showPhoneForm = ref(typeof window !== 'undefined');
const wxBtnLabel = computed(() => '微信授权登录');
const mode = ref<'password' | 'sms'>('sms');
const phone = ref(import.meta.env.DEV ? '13800138000' : '');
const password = ref('');
const code = ref(import.meta.env.DEV ? '123456' : '');
const loading = ref(false);
const sendingCode = ref(false);
const wxMode = ref(false);
const err = ref('');
const codeCooldown = ref(0);
const isDev = showDevTools();
let codeTimer: ReturnType<typeof setInterval> | null = null;

function clearCodeTimer() {
  if (codeTimer) {
    clearInterval(codeTimer);
    codeTimer = null;
  }
}

onLoad((opts) => {
  if (opts?.redirect) redirect.value = decodeRedirectParam(String(opts.redirect));
});

onUnload(() => clearCodeTimer());

/** uni-app H5 可能对 query 二次编码，循环解码直到稳定 */
function decodeRedirectParam(raw: string) {
  let cur = String(raw || '').trim();
  for (let i = 0; i < 3; i++) {
    try {
      const next = decodeURIComponent(cur);
      if (next === cur) break;
      cur = next;
    } catch {
      break;
    }
  }
  if (!cur.startsWith('/')) cur = '/' + cur.replace(/^\/+/, '');
  return cur || '/pages/index/index';
}

function finishLogin() {
  const target = redirect.value.split('?')[0];
  if (
    target.startsWith('/pages/index') ||
    target.startsWith('/pages/orders') ||
    target.startsWith('/pages/mine')
  ) {
    uni.switchTab({ url: target });
  } else {
    uni.redirectTo({
      url: redirect.value,
      fail: () => uni.switchTab({ url: '/pages/index/index' })
    });
  }
}

function goBack() {
  uni.navigateBack({
    fail: () => uni.switchTab({ url: '/pages/index/index' })
  });
}

async function onWxLogin() {
  if (loading.value) return;
  // #ifdef H5
  // H5 微信网页授权：公众号 OAuth 跳转；dev mock 直连建档；未配置则回落手机号
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    const oauthUrl = String(cfg?.wechatH5OauthUrl || '').trim();
    if (oauthUrl) {
      window.location.href = oauthUrl;
      return;
    }
    if (cfg?.wechatH5OauthEnabled === 'true') {
      loading.value = true;
      wxMode.value = true;
      err.value = '';
      await consumerWxH5Login('dev-mock-web-code');
      finishLogin();
      return;
    }
  } catch {
    /* fall through to phone login */
  }
  showPhoneForm.value = true;
  err.value = '当前环境未配置微信网页授权，请使用手机号登录';
  return;
  // #endif
  loading.value = true;
  wxMode.value = true;
  err.value = '';
  try {
    const ok = await ensureConsumerAuth();
    if (!ok) {
      // H5 / 非微信：引导展开手机号
      showPhoneForm.value = true;
      err.value = '当前环境无法微信授权，请使用手机号登录';
      return;
    }
    finishLogin();
  } catch (e) {
    err.value = e instanceof Error ? e.message : '微信授权失败';
    showPhoneForm.value = true;
  } finally {
    loading.value = false;
    wxMode.value = false;
  }
}

async function onSendCode() {
  if (codeCooldown.value || sendingCode.value) return;
  let phoneNum = phone.value.trim();
  if (!phoneNum) phoneNum = readDomFieldValue('input');
  phone.value = phoneNum;
  if (!/^1\d{10}$/.test(phoneNum)) {
    err.value = '请输入11位有效手机号';
    return;
  }
  sendingCode.value = true;
  err.value = '';
  try {
    await sendSmsCode(phoneNum);
    clearCodeTimer();
    codeCooldown.value = 60;
    codeTimer = setInterval(() => {
      codeCooldown.value -= 1;
      if (codeCooldown.value <= 0) clearCodeTimer();
    }, 1000);
    uni.showToast({ title: '验证码已发送', icon: 'none' });
  } catch (e) {
    err.value = e instanceof Error ? e.message : '发送失败';
  } finally {
    sendingCode.value = false;
  }
}

async function onLogin() {
  if (loading.value) return;
  let phoneNum = phone.value.trim();
  if (!phoneNum) phoneNum = readDomFieldValue('input');
  phone.value = phoneNum;
  if (!/^1\d{10}$/.test(phoneNum)) {
    err.value = '请输入11位有效手机号';
    return;
  }
  if (mode.value === 'password') {
    let pwd = password.value;
    if (!pwd) pwd = readDomPassword();
    password.value = pwd;
    if (!pwd) {
      err.value = '请输入登录密码';
      return;
    }
  } else {
    let sms = code.value.trim();
    if (!sms) {
      err.value = '请输入验证码';
      return;
    }
    if (!/^\d{4,6}$/.test(sms)) {
      err.value = '请输入4-6位验证码';
      return;
    }
    code.value = sms;
  }
  loading.value = true;
  wxMode.value = false;
  err.value = '';
  try {
    if (mode.value === 'password') {
      await consumerPasswordLogin(phoneNum, password.value);
    } else {
      await consumerSmsLogin(phoneNum, code.value.trim());
    }
    try {
      const wxCode = await new Promise<string>((resolve, reject) => {
        uni.login({
          provider: 'weixin',
          success: (r) => (r.code ? resolve(r.code) : reject()),
          fail: reject
        });
      });
      await consumerWxLogin(wxCode, phoneNum);
    } catch {
      /* 非微信环境或绑定失败时仍可用手机号会话 */
    }
    finishLogin();
  } catch (e) {
    err.value = e instanceof Error ? e.message : '验证失败';
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-wrap {
  position: relative;
  min-height: 100vh;
  overflow-x: hidden;
  overflow-y: hidden;
  background: #0b1220;
}
.login-wrap.phone-open {
  overflow-y: auto;
}
.login-wrap.phone-open .hero {
  padding-top: 28rpx;
}
.login-wrap.phone-open .brand {
  font-size: 48rpx;
}
.login-wrap.phone-open .tagline {
  font-size: 26rpx;
}
.login-wrap.phone-open .login-content {
  min-height: auto;
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
  top: 12%;
  right: 6%;
  width: 200rpx;
  height: 200rpx;
  background: rgba(45, 212, 191, 0.3);
  animation: orbFloatA 9s ease-in-out infinite;
}
.anim-orb-b {
  top: 38%;
  left: 4%;
  width: 160rpx;
  height: 160rpx;
  background: rgba(56, 189, 248, 0.26);
  animation: orbFloatB 11s ease-in-out infinite;
}
.anim-shimmer {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    115deg,
    transparent 38%,
    rgba(255, 255, 255, 0.14) 50%,
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
    transform: translate(-16rpx, 20rpx) scale(1.08);
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
    transform: translate(20rpx, -14rpx) scale(1.06);
    opacity: 0.75;
  }
}
@keyframes shimmerSweep {
  0%,
  100% {
    background-position: 120% 0;
    opacity: 0.4;
  }
  50% {
    background-position: -20% 0;
    opacity: 0.75;
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
  display: flex;
  flex-direction: column;
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
  font-size: 24rpx;
  font-weight: 700;
}
.badge-text {
  color: #cbd5e1;
  font-size: 22rpx;
}
.login-spacer {
  flex: 1;
  min-height: 48rpx;
}
.login-spacer.compact {
  flex: 0 0 auto;
  min-height: 12rpx;
  max-height: 24rpx;
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
.phone-open .form-card {
  padding-top: 28rpx;
}
.phone-open .field {
  margin-bottom: 16rpx;
}
.phone-open .subtitle {
  margin-bottom: 20rpx;
}
.phone-open .divider {
  margin: 20rpx 0 16rpx;
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
  margin-bottom: 28rpx;
  line-height: 1.5;
}
.btn-wx {
  background: linear-gradient(135deg, #07c160, #059669);
  color: #fff;
  border-radius: 44rpx;
  height: 96rpx;
  line-height: 96rpx;
  text-align: center;
  font-size: 32rpx;
  font-weight: 700;
  box-shadow: 0 10rpx 28rpx rgba(5, 150, 105, 0.28);
}
.btn-wx.disabled {
  opacity: 0.6;
  pointer-events: none;
}
.divider {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin: 28rpx 0 20rpx;
}
.divider-line {
  flex: 1;
  height: 1rpx;
  background: rgba(148, 163, 184, 0.24);
}
.divider-toggle {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  min-width: 200rpx;
  min-height: 72rpx;
  padding: 16rpx 28rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.08);
  border: 2rpx solid rgba(94, 234, 212, 0.24);
  box-sizing: border-box;
  cursor: pointer;
}
.divider-text {
  font-size: 26rpx;
  color: #5eead4;
  font-weight: 600;
  line-height: 1.2;
  pointer-events: none;
}
.tabs {
  display: flex;
  gap: 8rpx;
  margin-bottom: 28rpx;
  padding: 6rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.07);
}
.tab-item {
  flex: 1;
  padding: 16rpx 0;
  text-align: center;
  font-size: 28rpx;
  color: rgba(204, 251, 241, 0.62);
  border-radius: 12rpx;
  transition:
    color 0.2s ease,
    background 0.2s ease,
    box-shadow 0.2s ease,
    font-weight 0.2s ease;
}
.tab-item.on {
  color: #ffffff;
  font-weight: 600;
  background: linear-gradient(135deg, #0f766e, #14b8a6);
  box-shadow: 0 4rpx 12rpx rgba(13, 148, 136, 0.28);
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
.row {
  display: flex;
  gap: 16rpx;
  align-items: stretch;
}
.flex {
  flex: 1;
  min-width: 0;
}
.btn-code {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 28rpx;
  min-width: 180rpx;
  height: 88rpx;
  border-radius: 16rpx;
  background: rgba(8, 24, 30, 0.42);
  border: 2rpx solid rgba(148, 210, 198, 0.3);
  color: #5eead4;
  font-size: 26rpx;
  font-weight: 600;
  white-space: nowrap;
  backdrop-filter: blur(16rpx);
}
.btn-primary {
  margin-top: 12rpx;
  background: linear-gradient(135deg, #059669, #0d9488);
  color: #fff;
  border-radius: 44rpx;
  height: 96rpx;
  line-height: 96rpx;
  text-align: center;
  font-size: 30rpx;
  font-weight: 600;
  box-shadow: 0 10rpx 28rpx rgba(5, 150, 105, 0.28);
}
.btn-primary.disabled,
.btn-code.disabled {
  opacity: 0.55;
  pointer-events: none;
}
.btn-ghost {
  margin-top: 20rpx;
  text-align: center;
  color: #99f6e4;
  font-size: 28rpx;
  padding: 8rpx;
  background: transparent;
  border: none;
}
.err {
  color: #ef4444;
  display: block;
  margin-top: 16rpx;
  text-align: center;
  font-size: 26rpx;
}
.dev-hint {
  display: block;
  margin-top: 18rpx;
  text-align: center;
  color: rgba(204, 251, 241, 0.62);
  font-size: 22rpx;
  line-height: 1.4;
  opacity: 0.85;
}
</style>
