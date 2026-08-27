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
    <view class="login-content" :style="loginPadStyle">
      <view class="hero">
        <text class="brand">AI开门柜</text>
        <text class="tagline">扫码开门 · 拿了就走</text>
        <view class="badge">
          <text class="badge-icon">✓</text>
          <text class="badge-text">关门自动结算</text>
        </view>
      </view>

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
            <view
              :class="['tab-item', mode === 'sms' ? 'on' : '']"
              role="tab"
              :aria-selected="mode === 'sms' ? 'true' : 'false'"
              data-testid="login-tab-sms"
              @click="mode = 'sms'"
              >验证码</view
            >
            <view
              :class="['tab-item', mode === 'password' ? 'on' : '']"
              role="tab"
              :aria-selected="mode === 'password' ? 'true' : 'false'"
              data-testid="login-tab-password"
              @click="mode = 'password'"
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

          <view v-if="mode === 'password'" class="field field-auth">
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
          <view v-else class="field field-auth">
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
          <text v-if="isDev && demoHint" class="dev-hint">{{ demoHint }}</text>
        </view>

        <view class="btn-ghost" @click="goBack">返回</view>
        <text v-if="err" class="err">{{ err }}</text>
        <view class="legal-row">
          <text class="legal-link" @click="goPolicy('agreement')">用户协议</text>
          <text class="legal-dot">·</text>
          <text class="legal-link" @click="goPolicy('privacy')">隐私政策</text>
          <text class="legal-dot">·</text>
          <text class="legal-link" @click="goPolicy('refund')">退款规则</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onReady, onUnload } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { getBelowCapsulePadPx } from '@aicabinet/shared-uni/status-bar';
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
const showPhoneForm = ref(typeof globalThis !== 'undefined');
const wxBtnLabel = computed(() => '微信授权登录');
const isDev = showDevTools();
const demoPhone = String(import.meta.env.VITE_DEMO_PHONE || '').trim();
const demoPassword = String(import.meta.env.VITE_DEMO_PASSWORD || '').trim();
// H5/本地联调：演示账号已设密码；短信万能码依赖后端 mock，默认走密码更稳
const mode = ref<'password' | 'sms'>(isDev ? 'password' : 'sms');
const demoHint = computed(() => {
  if (!isDev) return '';
  if (mode.value === 'password' && demoPhone && demoPassword) {
    return `开发联调：${demoPhone} / 密码 ${demoPassword}`;
  }
  if (mode.value === 'sms' && demoPassword) {
    return `开发联调：先点「获取验证码」；mock 开时可用 ${demoPassword}`;
  }
  return '';
});
const phone = ref(isDev && demoPhone ? demoPhone : '');
const password = ref(isDev && demoPassword ? demoPassword : '');
const code = ref('');
const loading = ref(false);
const sendingCode = ref(false);
const wxMode = ref(false);
const err = ref('');
const codeCooldown = ref(0);
const loginPadStyle = ref({ paddingTop: getBelowCapsulePadPx(10) + 'px' });
function refreshLoginPad() {
  loginPadStyle.value = { paddingTop: getBelowCapsulePadPx(10) + 'px' };
}
let codeTimer: ReturnType<typeof setInterval> | null = null;

function clearCodeTimer() {
  if (codeTimer) {
    clearInterval(codeTimer);
    codeTimer = null;
  }
}

onLoad((opts) => {
  refreshLoginPad();
  if (opts?.redirect) redirect.value = decodeRedirectParam(String(opts.redirect));
});

onReady(() => refreshLoginPad());

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

function goPolicy(kind: 'agreement' | 'privacy' | 'refund') {
  uni.navigateTo({ url: `/pages/policy/detail?type=${kind}` });
}

async function tryH5WechatOauth(): Promise<boolean> {
  // #ifdef H5
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    const oauthUrl = String(cfg?.wechatH5OauthUrl || '').trim();
    if (oauthUrl) {
      globalThis.location.href = oauthUrl;
      return true;
    }
    if (cfg?.wechatH5OauthEnabled === 'true') {
      loading.value = true;
      wxMode.value = true;
      err.value = '';
      await consumerWxH5Login('dev-mock-web-code');
      finishLogin();
      return true;
    }
  } catch {
    /* fall through to phone login */
  }
  showPhoneForm.value = true;
  err.value = '当前环境未配置微信网页授权，请使用手机号登录';
  return true;
  // #endif
  return false;
}

async function runMiniProgramWxLogin() {
  loading.value = true;
  wxMode.value = true;
  err.value = '';
  try {
    const ok = await ensureConsumerAuth({ force: true });
    if (!ok) {
      showPhoneForm.value = true;
      err.value = '当前环境无法微信授权，请使用手机号登录';
      return;
    }
    finishLogin();
  } catch (caught) {
    err.value = caughtErrorMessage(caught, '微信授权失败');
    showPhoneForm.value = true;
  } finally {
    loading.value = false;
    wxMode.value = false;
  }
}

async function onWxLogin() {
  if (loading.value) return;
  if (await tryH5WechatOauth()) return;
  await runMiniProgramWxLogin();
}

function caughtErrorMessage(error: unknown, fallback: string) {
  if (error instanceof Error) return error.message;
  return fallback;
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

function resolvePhoneInput(): string | null {
  let phoneNum = phone.value.trim();
  if (!phoneNum) phoneNum = readDomFieldValue('input');
  phone.value = phoneNum;
  if (!/^1\d{10}$/.test(phoneNum)) {
    err.value = '请输入11位有效手机号';
    return null;
  }
  return phoneNum;
}

function validateLoginCredentials(): boolean {
  if (mode.value === 'password') {
    let pwd = password.value;
    if (!pwd) pwd = readDomPassword();
    password.value = pwd;
    if (!pwd) {
      err.value = '请输入登录密码';
      return false;
    }
    return true;
  }
  let sms = code.value.trim();
  if (!sms) {
    err.value = '请输入验证码';
    return false;
  }
  if (!/^\d{4,6}$/.test(sms)) {
    err.value = '请输入4-6位验证码';
    return false;
  }
  code.value = sms;
  return true;
}

async function bindWeixinIfPossible(phoneNum: string) {
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
}

async function performPhoneLogin(phoneNum: string) {
  if (mode.value === 'password') {
    await consumerPasswordLogin(phoneNum, password.value);
  } else {
    await consumerSmsLogin(phoneNum, code.value.trim());
  }
  await bindWeixinIfPossible(phoneNum);
  finishLogin();
}

async function onLogin() {
  if (loading.value) return;
  const phoneNum = resolvePhoneInput();
  if (!phoneNum || !validateLoginCredentials()) return;
  loading.value = true;
  wxMode.value = false;
  err.value = '';
  try {
    await performPhoneLogin(phoneNum);
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
  height: 100%;
  min-height: 100%;
  overflow-x: hidden;
  overflow-y: hidden;
  background: #0b1220;
  box-sizing: border-box;
}
.login-wrap.phone-open {
  overflow-y: hidden;
}
.login-wrap.phone-open .hero {
  padding-top: 0;
}
.login-wrap.phone-open .brand {
  font-size: 40rpx;
}
.login-wrap.phone-open .tagline {
  font-size: 24rpx;
  margin-top: 4rpx;
}
.login-wrap.phone-open .badge {
  display: none;
}
.login-wrap.phone-open .login-content {
  height: 100%;
  min-height: 100%;
  overflow: hidden;
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
  height: 100%;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  align-items: center;
  padding: 0 24rpx;
  overflow: hidden;
  box-sizing: border-box;
}
/* #ifdef MP-WEIXIN */
.login-wrap,
.login-content {
  height: 100vh;
  min-height: 100vh;
}
/* #endif */
.hero {
  flex-shrink: 0;
  padding-top: 0;
  text-align: center;
  width: 100%;
  max-width: 320px;
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
.form-card {
  /* 收起态：绝对贴底，避免垂直居中悬空 */
  position: absolute;
  left: 50%;
  bottom: calc(12rpx + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  width: calc(100% - 48rpx);
  max-width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 32rpx 28rpx 36rpx;
  border-radius: 28rpx;
  background: rgba(8, 24, 30, 0.58);
  border: 2rpx solid rgba(148, 210, 198, 0.22);
  backdrop-filter: blur(52rpx);
  box-shadow:
    0 -8rpx 40rpx rgba(2, 12, 16, 0.18),
    0 16rpx 48rpx rgba(2, 10, 14, 0.42);
  box-sizing: border-box;
}
.phone-open .form-card {
  /* 展开仍贴底，内部滚动，避免整页高度随验证码/密码切换跳动 */
  position: absolute;
  left: 50%;
  bottom: calc(12rpx + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  width: calc(100% - 48rpx);
  max-width: 320px;
  max-height: calc(100% - 140rpx);
  margin: 0;
  padding: 28rpx 24rpx 32rpx;
  overflow-y: auto;
}
.field-auth {
  min-height: 148rpx;
}
.phone-open .field {
  margin-bottom: 28rpx;
}
.phone-open .field-label {
  margin-bottom: 14rpx;
}
.phone-open .subtitle {
  margin-bottom: 12rpx;
  font-size: 22rpx;
}
.phone-open .title {
  font-size: 32rpx;
  margin-bottom: 4rpx;
}
.phone-open .divider {
  margin: 20rpx 0 24rpx;
}
.phone-open .btn-wx {
  height: 84rpx;
  line-height: 84rpx;
  font-size: 30rpx;
}
.phone-open .tabs {
  margin-bottom: 28rpx;
}
.phone-open .divider-toggle {
  min-height: 56rpx;
  padding: 10rpx 22rpx;
}
.title {
  font-size: 36rpx;
  font-weight: 700;
  display: block;
  margin-bottom: 8rpx;
  color: #f0fdfa;
  text-align: center;
}
.subtitle {
  font-size: 24rpx;
  color: rgba(204, 251, 241, 0.74);
  display: block;
  margin-bottom: 28rpx;
  text-align: center;
  line-height: 1.5;
}
.btn-wx {
  background: linear-gradient(135deg, var(--brand-wx, #07c160), var(--brand, #047857));
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
  background: linear-gradient(135deg, var(--brand, #047857), var(--brand-2, #047857));
  box-shadow: 0 4rpx 12rpx rgba(5, 150, 105, 0.28);
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
  align-self: center;
  width: 200px !important;
  max-width: 200px !important;
  min-width: 168px !important;
  padding: 0 !important;
  background: linear-gradient(135deg, var(--brand, #047857), var(--brand, #047857));
  color: #fff;
  border-radius: 44rpx;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  text-align: center;
  font-size: 30rpx;
  font-weight: 600;
  box-shadow: 0 10rpx 28rpx rgba(5, 150, 105, 0.28);
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
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
.legal-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: 8rpx;
  margin-top: 24rpx;
}
.legal-link {
  font-size: 22rpx;
  color: #99f6e4;
  opacity: 0.9;
}
.legal-dot {
  font-size: 22rpx;
  color: rgba(153, 246, 228, 0.5);
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
