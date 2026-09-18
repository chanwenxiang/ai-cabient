<template>
  <div class="login-page">
    <div
      class="login-bg"
      aria-hidden="true"
      :style="{ backgroundImage: `url(${bgVendingNight})` }"
    />
    <div class="login-bg-fx" aria-hidden="true">
      <span class="fx-grid" />
      <span class="fx-scan" />
      <i v-for="p in particles" :key="p.left" class="fx-particle" :style="p.style" />
    </div>
    <div class="login-overlay" aria-hidden="true" />
    <div id="main-content" class="login-card" tabindex="-1">
      <div class="card-header">
        <div v-if="brand.logoUrl" class="brand-mark brand-mark--img" aria-hidden="true">
          <img :src="brand.logoUrl" alt="" />
        </div>
        <div v-else class="brand-mark" aria-hidden="true">{{ markChar }}</div>
        <h1>{{ brand.title }}</h1>
        <p class="sub">{{ brand.subtitle }}</p>
      </div>
      <el-form v-if="!twoFactorStep" label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="手机号">
          <el-input
            ref="phoneInput"
            v-model="phone"
            type="tel"
            name="phone"
            maxlength="11"
            inputmode="numeric"
            autocomplete="username"
            spellcheck="false"
            placeholder="请输入11位手机号…"
            size="large"
            :disabled="loading"
            @input="onPhoneInput"
            @keyup.enter="focusPassword"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            ref="passwordInput"
            v-model="password"
            type="password"
            show-password
            name="password"
            autocomplete="current-password"
            placeholder="请输入登录密码…"
            size="large"
            :disabled="loading"
            @focus="onPasswordFocus"
            @input="onPasswordInput"
            @keyup.enter="focusCaptcha"
          />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="captcha-row">
            <el-input
              ref="captchaInput"
              v-model="captchaCode"
              maxlength="8"
              autocomplete="off"
              spellcheck="false"
              placeholder="图形验证码…"
              size="large"
              :disabled="loading"
              @input="err = ''"
              @keyup.enter="onSubmit"
            />
            <button
              type="button"
              class="captcha-img-btn"
              title="点击刷新验证码"
              aria-label="刷新图形验证码"
              :data-captcha-id="captchaId"
              :disabled="captchaLoading || loading"
              @click="loadCaptcha"
            >
              <img v-if="captchaImage" :src="captchaImage" alt="验证码" width="120" height="40" />
              <span v-else>{{ captchaLoading ? UI_COPY.loading : '点击获取' }}</span>
            </button>
          </div>
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="loading"
          :disabled="loading"
          class="submit-btn"
          >登录</el-button
        >
        <p v-if="err" class="err" role="alert">{{ err }}</p>
        <div class="login-extras">
          <div class="remember-group">
            <el-checkbox v-model="rememberPhone" size="small">记住账号</el-checkbox>
          </div>
        </div>
      </el-form>
      <el-form v-else label-position="top" @submit.prevent="onSubmitTwoFactor">
        <div class="twofa-head">
          <p class="twofa-title">双因子验证</p>
          <p class="twofa-sub">
            {{
              usingRecovery
                ? '请输入 8 个后备码之一（形如 XXXXX-XXXXX-XXXXX）'
                : '请打开身份验证器 App，输入当前 6 位动态码'
            }}
          </p>
        </div>
        <el-form-item :label="usingRecovery ? '后备码' : '动态码'">
          <el-input
            ref="twoFactorInput"
            v-model="twoFactorCode"
            maxlength="20"
            :autocomplete="usingRecovery ? 'off' : 'one-time-code'"
            spellcheck="false"
            :placeholder="usingRecovery ? 'XXXXX-XXXXX-XXXXX' : '6 位动态码'"
            size="large"
            :disabled="loading"
            @input="err = ''"
            @keyup.enter="onSubmitTwoFactor"
          />
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="loading"
          :disabled="loading"
          class="submit-btn"
          >验证并登录</el-button
        >
        <p v-if="err" class="err" role="alert">{{ err }}</p>
        <div class="twofa-extras">
          <button type="button" class="link-btn" @click="usingRecovery = !usingRecovery">
            {{ usingRecovery ? '改用动态码' : '使用后备码登录' }}
          </button>
          <button type="button" class="link-btn" @click="backToPassword">返回密码登录</button>
        </div>
      </el-form>
      <p v-if="ENABLE_TEST_TOOLS" class="hint">
        内部测试账号（口令见后端 seed，不写回前端）：<br />
        超管 13900000001 · 财务 13900000002 · 运营 13900000003<br />
        补货 13900000004 · 只读 13900000005
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue';
import { storeToRefs } from 'pinia';
import { useRouter, useRoute } from 'vue-router';
import { type InputInstance } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useBrandStore } from '@/stores/brand';
import { api } from '@/api/client';
import { ENABLE_TEST_TOOLS } from '@/config/feature-flags';
import { safeRedirectPath } from '@/utils/safe-redirect';
import { resolveHomePath } from '@/composables/useNavAccess';
import { findNavByPath } from '@/config/menu';
import bgVendingNight from '@/assets/bg-vending-night.jpg';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

const brandStore = useBrandStore();
const { brand, markChar } = storeToRefs(brandStore);

/** 登录页动态背景粒子：固定参数，避免每次渲染随机跳动。 */
const particles = Array.from({ length: 16 }, (_, i) => {
  const left = ((i * 6.3 + 2) % 100).toFixed(1);
  const size = 3 + (i % 3) * 2;
  const delay = ((i * 1.37) % 14).toFixed(1);
  const duration = (10 + (i % 6) * 2).toFixed(1);
  return {
    left,
    style: {
      left: `${left}%`,
      width: `${size}px`,
      height: `${size}px`,
      animationDelay: `${delay}s`,
      animationDuration: `${duration}s`
    }
  };
});

const phone = ref(localStorage.getItem('admin_phone') || (ENABLE_TEST_TOOLS ? '13900000001' : ''));

/** 只记住手机号；密码不写 localStorage（安全），也不做任何预填。 */
const PW_STORE_KEY = 'admin_password';
const PW_FLAG_KEY = 'admin_remember_password';
try {
  localStorage.removeItem(PW_STORE_KEY);
  localStorage.removeItem(PW_FLAG_KEY);
} catch {
  /* ignore quota / private mode */
}

const rememberPhone = ref(localStorage.getItem('admin_remember_phone') !== '0');
/**
 * A-1：不做演示口令预填。
 * 原实现用 `ENABLE_TEST_TOOLS || isDemoPhone(phone.value)` 决定是否预填演示口令——只把门控关了一半：
 * isDemoPhone 只看 localStorage.admin_phone（开发期写过一次就永久留存），
 * 于是生产构建同样会预填口令，等于内置一个可用口令的资金后台入口。
 * 现在一律留空（靠浏览器密码管理器）；内置账号只存在于后端 seed。
 */
const password = ref('');
const captchaCode = ref('');
const captchaId = ref('');
const captchaImage = ref('');
const captchaLoading = ref(false);
const loading = ref(false);
const err = ref('');
const twoFactorStep = ref(false);
const twoFactorCode = ref('');
const usingRecovery = ref(false);
const phoneInput = ref<InputInstance | null>(null);
const passwordInput = ref<InputInstance | null>(null);
const captchaInput = ref<InputInstance | null>(null);
const auth = useAuthStore();
const router = useRouter();
const route = useRoute();

let caretFixTimers: number[] = [];

onUnmounted(() => {
  for (const id of caretFixTimers) window.clearTimeout(id);
  caretFixTimers = [];
});

function nativeOf(input: InputInstance | null | undefined): HTMLInputElement | null {
  const el = input?.input;
  return el instanceof HTMLInputElement ? el : null;
}

/**
 * 保留 type=password 以便浏览器自动填充密码。
 * 填充后可视光标常停在开头：清空再写回 + setSelectionRange 推到末尾。
 */
function placePasswordCaretAtEnd() {
  const el = nativeOf(passwordInput.value);
  if (!el?.value) return;
  const apply = () => {
    if (!el.isConnected || !el.value) return;
    if (password.value !== el.value) password.value = el.value;
    const v = el.value;
    try {
      el.value = '';
      el.value = v;
      if (document.activeElement === el) el.focus({ preventScroll: true });
      el.setSelectionRange(v.length, v.length);
    } catch {
      try {
        el.selectionStart = v.length;
        el.selectionEnd = v.length;
      } catch {
        /* ignore */
      }
    }
  };
  apply();
  for (const ms of [0, 16, 50, 100, 200, 400]) {
    caretFixTimers.push(window.setTimeout(apply, ms));
  }
}

function focusField(input: InputInstance | null | undefined) {
  input?.focus?.();
}

function focusPassword() {
  focusField(passwordInput.value);
  void nextTick(() => placePasswordCaretAtEnd());
}

function onPasswordFocus() {
  if (nativeOf(passwordInput.value)?.value) placePasswordCaretAtEnd();
}

function onPasswordInput() {
  err.value = '';
  const el = nativeOf(passwordInput.value);
  if (el && el.value.length >= 4 && el.selectionStart === 0 && el.selectionEnd === 0) {
    placePasswordCaretAtEnd();
  }
}

function onPhoneInput() {
  phone.value = phone.value.replaceAll(/\D/g, '');
  err.value = '';
}

function focusCaptcha() {
  captchaInput.value?.focus?.();
}

function applyLoginAutofocus() {
  if (!phone.value) {
    focusField(phoneInput.value);
    return;
  }
  focusPassword();
}

async function loadCaptcha() {
  captchaLoading.value = true;
  try {
    const data = await api.fetchCaptcha();
    captchaId.value = data.captchaId;
    captchaImage.value = data.imageBase64;
    captchaCode.value = '';
  } catch (e) {
    err.value = e instanceof Error ? e.message : '验证码加载失败';
  } finally {
    captchaLoading.value = false;
  }
}

onMounted(async () => {
  void brandStore.load();
  await loadCaptcha();
  await nextTick();
  applyLoginAutofocus();
  // 自动填充可能晚于挂载：轮询一小会儿，有值就把光标推到末尾
  for (const ms of [50, 100, 200, 400, 800, 1200]) {
    caretFixTimers.push(
      window.setTimeout(() => {
        const el = nativeOf(passwordInput.value);
        if (el?.value) placePasswordCaretAtEnd();
      }, ms)
    );
  }
});

async function finishLogin(normalizedPhone: string) {
  if (rememberPhone.value) {
    localStorage.setItem('admin_remember_phone', '1');
    localStorage.setItem('admin_phone', normalizedPhone);
  } else {
    localStorage.removeItem('admin_phone');
    localStorage.setItem('admin_remember_phone', '0');
  }
  localStorage.removeItem(PW_STORE_KEY);
  localStorage.removeItem(PW_FLAG_KEY);
  const home = resolveHomePath(auth);
  const requested = safeRedirectPath(route.query.redirect, home);
  const nav = findNavByPath(requested);
  const ok = !nav?.perm || auth.canAccessNav(nav);
  router.replace(ok ? requested : home);
}

async function onSubmit() {
  const normalizedPhone = phone.value.trim();
  if (!/^1\d{10}$/.test(normalizedPhone)) {
    err.value = '请输入正确的11位手机号';
    return;
  }
  if (!password.value) {
    err.value = '请输入登录密码';
    return;
  }
  if (!captchaCode.value.trim()) {
    err.value = '请输入图形验证码';
    return;
  }
  if (!captchaId.value) {
    err.value = '验证码未加载，请点击刷新';
    await loadCaptcha();
    return;
  }
  loading.value = true;
  err.value = '';
  try {
    const result = await auth.login(normalizedPhone, password.value, {
      captchaId: captchaId.value,
      captchaCode: captchaCode.value.trim()
    });
    if (result?.twoFactorRequired) {
      twoFactorStep.value = true;
      twoFactorCode.value = '';
      usingRecovery.value = false;
      return;
    }
    await finishLogin(normalizedPhone);
  } catch (e) {
    err.value = e instanceof Error ? e.message : '登录失败';
    await loadCaptcha();
  } finally {
    loading.value = false;
  }
}

function backToPassword() {
  twoFactorStep.value = false;
  twoFactorCode.value = '';
  usingRecovery.value = false;
}

async function onSubmitTwoFactor() {
  const code = twoFactorCode.value.trim();
  if (!code) {
    err.value = usingRecovery.value ? '请输入后备码' : '请输入动态码';
    return;
  }
  loading.value = true;
  err.value = '';
  try {
    await auth.completeTwoFactor(code, usingRecovery.value);
    await finishLogin(phone.value.trim());
  } catch (e) {
    err.value = e instanceof Error ? e.message : '验证失败';
    twoFactorCode.value = '';
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  padding: 24px;
}
.login-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  background-color: #082f34;
  background-position: center;
  background-size: cover;
  animation: bgKenBurns 28s ease-in-out infinite alternate;
}
@keyframes bgKenBurns {
  from {
    transform: scale(1) translate(0, 0);
  }
  to {
    transform: scale(1.08) translate(-1.2%, -1%);
  }
}
.login-bg-fx {
  position: absolute;
  inset: 0;
  z-index: 1;
  overflow: hidden;
  pointer-events: none;
}
.fx-grid {
  position: absolute;
  inset: -40%;
  background-image:
    linear-gradient(rgba(94, 234, 212, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(94, 234, 212, 0.055) 1px, transparent 1px);
  background-size: 52px 52px;
  animation: gridDrift 24s linear infinite;
  -webkit-mask-image: radial-gradient(ellipse 78% 72% at 50% 36%, #000 32%, transparent 80%);
  mask-image: radial-gradient(ellipse 78% 72% at 50% 36%, #000 32%, transparent 80%);
}
.fx-scan {
  position: absolute;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(45, 212, 191, 0.55), transparent);
  box-shadow: 0 0 24px rgba(15, 118, 110, 0.35);
  animation: scanLine 7s linear infinite;
  opacity: 0.7;
}
.fx-particle {
  position: absolute;
  bottom: -14px;
  border-radius: 50%;
  background: rgba(94, 234, 212, 0.6);
  box-shadow: 0 0 10px rgba(45, 212, 191, 0.55);
  animation-name: particleRise;
  animation-timing-function: linear;
  animation-iteration-count: infinite;
}
.login-overlay {
  position: absolute;
  inset: 0;
  z-index: 2;
  background: linear-gradient(
    160deg,
    rgba(4, 25, 30, 0.4) 0%,
    rgba(6, 36, 43, 0.22) 46%,
    rgba(3, 20, 25, 0.5) 100%
  );
}
@keyframes gridDrift {
  from {
    transform: translate(0, 0);
  }
  to {
    transform: translate(48px, 48px);
  }
}
@keyframes particleRise {
  0% {
    transform: translate(0, 0);
    opacity: 0;
  }
  12% {
    opacity: 0.85;
  }
  85% {
    opacity: 0.5;
  }
  100% {
    transform: translate(28px, -108vh);
    opacity: 0;
  }
}
@keyframes scanLine {
  0% {
    top: -2%;
    opacity: 0;
  }
  8% {
    opacity: 0.7;
  }
  92% {
    opacity: 0.7;
  }
  100% {
    top: 102%;
    opacity: 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .fx-grid,
  .fx-scan,
  .fx-particle,
  .login-bg {
    animation: none;
  }
}
.login-card {
  position: relative;
  z-index: 3;
  width: 100%;
  max-width: 400px;
  padding: 36px 32px 28px;
  border-radius: 16px;
  border: 1px solid rgba(148, 210, 198, 0.22);
  /* 扁平实心卡：去掉玻璃模糊，与两端小程序登录一致（R3-A01） */
  background: rgba(8, 24, 30, 0.82);
  backdrop-filter: none;
  box-shadow:
    0 24px 64px rgba(2, 10, 14, 0.38),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
}
.login-card:focus {
  outline: none;
}
.login-card:focus-visible {
  outline: 2px solid #5eead4;
  outline-offset: 3px;
}
.card-header {
  margin-bottom: 28px;
  text-align: center;
}
.brand-mark {
  width: 48px;
  height: 48px;
  margin: 0 auto 14px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-size: var(--admin-font-size-display-md);
  font-weight: 700;
  color: #ecfeff;
  background: linear-gradient(145deg, #14b8a6, var(--app-primary, #0f766e));
  box-shadow: 0 10px 24px rgba(15, 118, 110, 0.35);
  overflow: hidden;
}
.brand-mark--img {
  padding: 0;
  background: rgba(8, 24, 30, 0.35);
}
.brand-mark--img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.card-header h1 {
  margin: 0 0 6px;
  font-size: 1.75rem;
  font-weight: 700;
  color: #ecfeff;
  letter-spacing: 0.02em;
  text-shadow: 0 2px 18px rgba(2, 10, 14, 0.75);
}
.sub {
  color: rgba(207, 250, 254, 0.72);
  margin: 0;
  font-size: 0.95rem;
  text-shadow: 0 1px 10px rgba(2, 10, 14, 0.7);
}
.login-card :deep(.el-form-item) {
  margin-bottom: 20px;
}
.login-card :deep(.el-form-item__label) {
  font-weight: 500;
  color: rgba(204, 251, 241, 0.88);
  padding-bottom: 6px;
  line-height: 1.4;
  text-shadow: 0 1px 8px rgba(2, 10, 14, 0.65);
}
.login-card :deep(.el-input__wrapper) {
  border-radius: 10px;
  background: rgba(8, 24, 30, 0.42);
  box-shadow: 0 0 0 1px rgba(148, 210, 198, 0.26) inset;
  padding: 4px 12px;
  backdrop-filter: none;
}
.login-card :deep(.el-input__inner) {
  color: #f0fdfa;
  /* 覆盖 Chrome 自动填充浅色底，否则会露出浅蓝块盖住输入 wrapper */
  background-color: transparent !important;
  box-shadow: none !important;
  -webkit-text-fill-color: #f0fdfa;
  caret-color: #f0fdfa;
  transition: background-color 99999s ease-out;
}
.login-card :deep(.el-input__inner:-webkit-autofill),
.login-card :deep(.el-input__inner:-webkit-autofill:hover),
.login-card :deep(.el-input__inner:-webkit-autofill:focus),
.login-card :deep(input.el-input__inner:-webkit-autofill) {
  -webkit-text-fill-color: #f0fdfa !important;
  caret-color: #f0fdfa;
  /* 用极大 inset shadow 盖住浏览器默认 autofill 底色 */
  box-shadow: 0 0 0 1000px rgba(8, 24, 30, 0.42) inset !important;
  transition: background-color 99999s ease-out;
  /* 触发 animationstart，便于 JS 在自动填充后把光标挪到末尾 */
  animation-name: onAutoFillStart;
  animation-duration: 0.001s;
}
@keyframes onAutoFillStart {
  from {
    opacity: 0.99;
  }
  to {
    opacity: 1;
  }
}
.login-card :deep(.el-input__inner::placeholder) {
  color: rgba(148, 210, 198, 0.55);
  -webkit-text-fill-color: rgba(148, 210, 198, 0.55);
}
.login-card :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(94, 234, 212, 0.42) inset;
}
.login-card :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(45, 212, 191, 0.5) inset;
}
.login-card :deep(.el-input__password) {
  color: rgba(148, 210, 198, 0.72);
}
.submit-btn {
  width: 100%;
  height: 44px;
  margin-top: 8px;
  border-radius: 10px;
  font-size: 1rem;
  font-weight: 600;
}
.captcha-row {
  display: flex;
  gap: 10px;
  width: 100%;
  align-items: stretch;
}
.captcha-row .el-input {
  flex: 1;
}
.captcha-img-btn {
  flex: 0 0 120px;
  height: 40px;
  padding: 0;
  border: 1px solid rgba(148, 210, 198, 0.3);
  border-radius: 10px;
  background: rgba(8, 24, 30, 0.42);
  cursor: pointer;
  overflow: hidden;
  color: rgba(207, 250, 254, 0.8);
  font-size: var(--admin-font-size-sm);
  backdrop-filter: none;
}
.captcha-img-btn:disabled {
  opacity: 0.7;
  cursor: wait;
}
.captcha-img-btn img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.err {
  color: #ef4444;
  margin: 12px 0 0;
  text-align: center;
  font-size: 0.875rem;
}
.twofa-head {
  margin-bottom: 4px;
}
.twofa-title {
  margin: 0;
  font-size: var(--admin-font-size-display-sm);
  font-weight: 700;
  color: #f8fafc;
}
.twofa-sub {
  margin: 6px 0 0;
  font-size: var(--admin-font-size-table);
  color: #cbd5e1;
  line-height: 1.5;
}
.twofa-extras {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 10px;
}
.login-extras {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  margin-top: 12px;
  color: rgba(207, 250, 254, 0.72);
  text-shadow: 0 1px 8px rgba(2, 10, 14, 0.6);
  --el-color-primary: var(--app-primary, #0f766e);
  --el-color-primary-light-3: #5aa89e;
  --el-color-primary-light-5: #9dcfc7;
  --el-color-primary-light-7: #d3ece6;
  --el-color-primary-light-8: #e6f5f1;
  --el-color-primary-light-9: #f2faf8;
  --el-color-primary-dark-2: #0b5c55;
}
.login-extras :deep(.el-checkbox__label) {
  color: rgba(204, 251, 241, 0.82);
  font-size: var(--admin-font-size-table);
}
.remember-group {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.link-btn {
  border: none;
  background: transparent;
  color: #5eead4;
  font-size: var(--admin-font-size-table);
  cursor: pointer;
  padding: 2px 4px;
}
.link-btn:hover,
.link-btn:focus-visible {
  color: #99f6e4;
  outline: none;
  text-decoration: underline;
}
.hint {
  color: rgba(207, 250, 254, 0.6);
  font-size: 0.8rem;
  margin: 20px 0 0;
  text-align: center;
}
</style>
