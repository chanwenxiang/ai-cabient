<template>
  <div class="login-page">
    <div class="login-bg login-bg-drift" :style="{ backgroundImage: `url(${loginBgUrl})` }" aria-hidden="true" />
    <div class="login-bg-fx" aria-hidden="true">
      <span class="fx-grid" />
      <span class="fx-orb fx-orb-a" />
      <span class="fx-orb fx-orb-b" />
      <span class="fx-scan" />
    </div>
    <div class="login-overlay" aria-hidden="true" />
    <div class="login-card">
      <div class="card-header">
        <div class="brand-mark" aria-hidden="true">柜</div>
        <h1>AI开门柜</h1>
        <p class="sub">运营管理系统</p>
      </div>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="手机号">
          <el-input
            ref="phoneInput"
            v-model="phone"
            maxlength="11"
            inputmode="numeric"
            autocomplete="username"
            placeholder="请输入11位手机号"
            size="large"
            @input="err = ''"
            @keyup.enter="focusPassword"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            ref="passwordInput"
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入登录密码"
            size="large"
            @input="err = ''"
          />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="submit-btn">登录</el-button>
        <p v-if="err" class="err" role="alert">{{ err }}</p>
      </el-form>
        <p v-if="ENABLE_TEST_TOOLS" class="hint">
          演示账号（密码均为 123456）：<br />
          超管 13900000001 · 财务 13900000002 · 运营 13900000003<br />
          补货 13900000004 · 只读 13900000005
        </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { ENABLE_TEST_TOOLS } from '@/config/feature-flags';
import loginBgUrl from '@/assets/login-bg.svg';

const phone = ref(ENABLE_TEST_TOOLS ? '13900000001' : '');
const password = ref(ENABLE_TEST_TOOLS ? '123456' : '');
const loading = ref(false);
const err = ref('');
const phoneInput = ref<{ focus?: () => void } | null>(null);
const passwordInput = ref<{ focus?: () => void } | null>(null);
const auth = useAuthStore();
const router = useRouter();
const route = useRoute();

function focusPassword() {
  passwordInput.value?.focus?.();
}

onMounted(async () => {
  await nextTick();
  if (!phone.value) phoneInput.value?.focus?.();
  else if (!password.value) passwordInput.value?.focus?.();
});

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
  loading.value = true;
  err.value = '';
  try {
    await auth.login(normalizedPhone, password.value);
    const rawRedirect = typeof route.query.redirect === 'string' ? route.query.redirect : '';
    const redirect = rawRedirect.startsWith('/') && !rawRedirect.startsWith('//')
      ? rawRedirect
      : '/dashboard';
    router.replace(redirect);
  } catch (e) {
    err.value = e instanceof Error ? e.message : '登录失败';
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
  background-position: center;
  background-size: cover;
  background-repeat: no-repeat;
  z-index: 0;
}
.login-bg-drift {
  animation: bgDrift 26s ease-in-out infinite alternate;
  transform-origin: center center;
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
    linear-gradient(rgba(56, 189, 248, 0.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 189, 248, 0.07) 1px, transparent 1px);
  background-size: 48px 48px;
  animation: gridDrift 24s linear infinite;
}
.fx-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(64px);
}
.fx-orb-a {
  top: 12%;
  left: 18%;
  width: 320px;
  height: 320px;
  background: rgba(56, 189, 248, 0.22);
  animation: orbPulseA 10s ease-in-out infinite;
}
.fx-orb-b {
  bottom: 8%;
  right: 12%;
  width: 280px;
  height: 280px;
  background: rgba(34, 211, 238, 0.18);
  animation: orbPulseB 12s ease-in-out infinite;
}
.fx-scan {
  position: absolute;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(56, 189, 248, 0.55), transparent);
  box-shadow: 0 0 24px rgba(56, 189, 248, 0.35);
  animation: scanLine 7s linear infinite;
  opacity: 0.7;
}
.login-overlay {
  position: absolute;
  inset: 0;
  z-index: 2;
  background: linear-gradient(135deg, rgba(15, 23, 42, 0.42), rgba(30, 58, 95, 0.32));
}
@keyframes bgDrift {
  from { transform: scale(1); }
  to { transform: scale(1.06); }
}
@keyframes gridDrift {
  from { transform: translate(0, 0); }
  to { transform: translate(48px, 48px); }
}
@keyframes orbPulseA {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.65; }
  50% { transform: translate(28px, 22px) scale(1.12); opacity: 1; }
}
@keyframes orbPulseB {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.55; }
  50% { transform: translate(-24px, -18px) scale(1.1); opacity: 0.9; }
}
@keyframes scanLine {
  0% { top: -2%; opacity: 0; }
  8% { opacity: 0.7; }
  92% { opacity: 0.7; }
  100% { top: 102%; opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .login-bg-drift,
  .fx-grid,
  .fx-orb-a,
  .fx-orb-b,
  .fx-scan {
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
  border: 1px solid rgba(56, 189, 248, 0.28);
  backdrop-filter: blur(16px);
  background: rgba(15, 23, 42, 0.78);
  box-shadow: 0 24px 64px rgba(2, 6, 23, 0.45), inset 0 1px 0 rgba(148, 163, 184, 0.12);
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
  font-size: 22px;
  font-weight: 700;
  color: #ecfeff;
  background: linear-gradient(145deg, #14b8a6, #0f766e);
  box-shadow: 0 10px 24px rgba(15, 118, 110, 0.35);
}
.card-header h1 {
  margin: 0 0 6px;
  font-size: 1.75rem;
  font-weight: 700;
  color: #e2e8f0;
  letter-spacing: 0.02em;
}
.sub {
  color: #94a3b8;
  margin: 0;
  font-size: 0.95rem;
}
.login-card :deep(.el-form-item) {
  margin-bottom: 20px;
}
.login-card :deep(.el-form-item__label) {
  font-weight: 500;
  color: #cbd5e1;
  padding-bottom: 6px;
  line-height: 1.4;
}
.login-card :deep(.el-input__wrapper) {
  border-radius: 10px;
  background: rgba(30, 41, 59, 0.85);
  box-shadow: 0 0 0 1px rgba(56, 189, 248, 0.22) inset;
  padding: 4px 12px;
}
.login-card :deep(.el-input__inner) {
  color: #f1f5f9;
}
.login-card :deep(.el-input__inner::placeholder) {
  color: #64748b;
}
.login-card :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(56, 189, 248, 0.38) inset;
}
.login-card :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(56, 189, 248, 0.5) inset;
}
.login-card :deep(.el-input__password) {
  color: #94a3b8;
}
.submit-btn {
  width: 100%;
  height: 44px;
  margin-top: 8px;
  border-radius: 10px;
  font-size: 1rem;
  font-weight: 600;
}
.err {
  color: #ef4444;
  margin: 12px 0 0;
  text-align: center;
  font-size: 0.875rem;
}
.hint {
  color: #64748b;
  font-size: 0.8rem;
  margin: 20px 0 0;
  text-align: center;
}
</style>
