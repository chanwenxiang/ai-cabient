<template>
  <div class="login-page">
    <div class="login-bg" aria-hidden="true">
      <span class="bg-aurora" />
      <span class="bg-orb bg-orb-a" />
      <span class="bg-orb bg-orb-b" />
      <span class="bg-orb bg-orb-c" />
    </div>
    <div class="login-bg-fx" aria-hidden="true">
      <span class="fx-grid" />
      <span class="fx-scan" />
      <i v-for="p in particles" :key="p.left" class="fx-particle" :style="p.style" />
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
            type="tel"
            maxlength="11"
            inputmode="numeric"
            autocomplete="username"
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
            autocomplete="current-password"
            placeholder="请输入登录密码…"
            size="large"
            :disabled="loading"
            @input="err = ''"
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
              <span v-else>{{ captchaLoading ? '加载中…' : '点击获取' }}</span>
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
          <el-checkbox v-model="rememberPhone" size="small">记住手机号</el-checkbox>
          <button type="button" class="link-btn" @click="openResetDialog">忘记密码？</button>
        </div>
      </el-form>
      <p v-if="ENABLE_TEST_TOOLS" class="hint">
        演示账号（密码均为 123456）：<br />
        超管 13900000001 · 财务 13900000002 · 运营 13900000003<br />
        补货 13900000004 · 只读 13900000005
      </p>
    </div>

    <el-dialog
      v-model="resetVisible"
      title="重置密码"
      width="440px"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form label-position="top" @submit.prevent="submitReset">
        <el-form-item label="手机号">
          <el-input
            v-model="resetForm.phoneNumber"
            type="tel"
            maxlength="11"
            inputmode="numeric"
            placeholder="请输入 11 位手机号"
          />
        </el-form-item>
        <el-form-item label="图形验证码">
          <div class="captcha-row">
            <el-input
              v-model="resetCaptchaCode"
              maxlength="8"
              autocomplete="off"
              spellcheck="false"
              placeholder="图形验证码…"
            />
            <button
              type="button"
              class="captcha-img-btn"
              title="点击刷新验证码"
              aria-label="刷新图形验证码"
              :data-captcha-id="resetCaptchaId"
              :disabled="resetCaptchaLoading"
              @click="loadResetCaptcha"
            >
              <img
                v-if="resetCaptchaImage"
                :src="resetCaptchaImage"
                alt="验证码"
                width="120"
                height="40"
              />
              <span v-else>{{ resetCaptchaLoading ? '加载中…' : '点击获取' }}</span>
            </button>
          </div>
        </el-form-item>
        <el-form-item label="短信验证码">
          <div class="sms-row">
            <el-input
              v-model="resetForm.smsCode"
              maxlength="6"
              inputmode="numeric"
              placeholder="6 位短信验证码"
            />
            <el-button
              :disabled="smsCooldown > 0 || !/^1\d{10}$/.test(resetForm.phoneNumber.trim())"
              @click="sendSmsCode"
            >
              {{ smsCooldown > 0 ? `${smsCooldown}s 后重发` : '发送验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="resetForm.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="6-64 位"
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="resetForm.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="再次输入新密码"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetSaving" @click="submitReset">重置密码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { api } from '@/api/client';
import { ENABLE_TEST_TOOLS } from '@/config/feature-flags';
import { safeRedirectPath } from '@/utils/safe-redirect';

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
const password = ref(ENABLE_TEST_TOOLS ? '123456' : '');
const captchaCode = ref('');
const captchaId = ref('');
const captchaImage = ref('');
const captchaLoading = ref(false);
const loading = ref(false);
const err = ref('');
const rememberPhone = ref(localStorage.getItem('admin_remember_phone') !== '0');
const resetVisible = ref(false);
const resetSaving = ref(false);
const resetCaptchaId = ref('');
const resetCaptchaImage = ref('');
const resetCaptchaCode = ref('');
const resetCaptchaLoading = ref(false);
const smsCooldown = ref(0);
const smsTimer = ref<ReturnType<typeof setInterval> | null>(null);
const resetForm = ref({ phoneNumber: '', smsCode: '', newPassword: '', confirmPassword: '' });
const phoneInput = ref<{ focus?: () => void } | null>(null);
const passwordInput = ref<{ focus?: () => void } | null>(null);
const captchaInput = ref<{ focus?: () => void } | null>(null);
const auth = useAuthStore();
const router = useRouter();
const route = useRoute();

onUnmounted(() => {
  if (smsTimer.value) clearInterval(smsTimer.value);
});

function focusPassword() {
  passwordInput.value?.focus?.();
}

function onPhoneInput() {
  phone.value = phone.value.replace(/\D/g, '');
  err.value = '';
}

function focusCaptcha() {
  captchaInput.value?.focus?.();
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

async function loadResetCaptcha() {
  resetCaptchaLoading.value = true;
  try {
    const data = await api.fetchCaptcha();
    resetCaptchaId.value = data.captchaId;
    resetCaptchaImage.value = data.imageBase64;
    resetCaptchaCode.value = '';
  } catch (e) {
    err.value = e instanceof Error ? e.message : '验证码加载失败';
  } finally {
    resetCaptchaLoading.value = false;
  }
}

function openResetDialog() {
  resetForm.value = {
    phoneNumber: phone.value.trim(),
    smsCode: '',
    newPassword: '',
    confirmPassword: ''
  };
  resetCaptchaCode.value = '';
  resetVisible.value = true;
  void loadResetCaptcha();
}

async function sendSmsCode() {
  const p = resetForm.value.phoneNumber.trim();
  if (!/^1\d{10}$/.test(p)) {
    err.value = '请输入正确的 11 位手机号';
    return;
  }
  try {
    await api.request(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(p)}`, 'POST');
    ElMessage.success('短信验证码已发送');
    smsCooldown.value = 60;
    if (smsTimer.value) clearInterval(smsTimer.value);
    smsTimer.value = setInterval(() => {
      smsCooldown.value -= 1;
      if (smsCooldown.value <= 0 && smsTimer.value) {
        clearInterval(smsTimer.value);
        smsTimer.value = null;
      }
    }, 1000);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发送失败');
  }
}

async function submitReset() {
  const f = resetForm.value;
  if (!/^1\d{10}$/.test(f.phoneNumber.trim())) {
    ElMessage.warning('请输入正确的 11 位手机号');
    return;
  }
  if (!resetCaptchaCode.value.trim()) {
    ElMessage.warning('请输入图形验证码');
    return;
  }
  if (!f.smsCode.trim()) {
    ElMessage.warning('请输入短信验证码');
    return;
  }
  if (!f.newPassword || f.newPassword.length < 6 || f.newPassword.length > 64) {
    ElMessage.warning('新密码长度需为 6-64 位');
    return;
  }
  if (f.newPassword !== f.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致');
    return;
  }
  resetSaving.value = true;
  try {
    await api.request('/api/v2/auth/admin-password-reset', 'POST', {
      phoneNumber: f.phoneNumber.trim(),
      smsCode: f.smsCode.trim(),
      captchaId: resetCaptchaId.value,
      captchaCode: resetCaptchaCode.value.trim(),
      newPassword: f.newPassword
    });
    ElMessage.success('密码已重置，请用新密码登录');
    resetVisible.value = false;
    phone.value = f.phoneNumber.trim();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重置失败');
    void loadResetCaptcha();
  } finally {
    resetSaving.value = false;
  }
}

onMounted(async () => {
  await loadCaptcha();
  await nextTick();
  if (!phone.value) phoneInput.value?.focus?.();
  else if (!password.value) passwordInput.value?.focus?.();
  else captchaInput.value?.focus?.();
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
    await auth.login(normalizedPhone, password.value, {
      captchaId: captchaId.value,
      captchaCode: captchaCode.value.trim()
    });
    if (rememberPhone.value) {
      localStorage.setItem('admin_remember_phone', '1');
      localStorage.setItem('admin_phone', normalizedPhone);
    } else {
      localStorage.removeItem('admin_phone');
      localStorage.setItem('admin_remember_phone', '0');
    }
    router.replace(safeRedirectPath(route.query.redirect));
  } catch (e) {
    err.value = e instanceof Error ? e.message : '登录失败';
    await loadCaptcha();
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
  background: linear-gradient(160deg, #042f2e 0%, #0f766e 48%, #134e4a 78%, #042f2e 100%);
}
.bg-aurora {
  position: absolute;
  inset: -35%;
  background: conic-gradient(
    from 180deg at 50% 42%,
    rgba(45, 212, 191, 0.2),
    rgba(20, 184, 166, 0) 28%,
    rgba(16, 185, 129, 0.18) 52%,
    rgba(45, 212, 191, 0) 78%,
    rgba(94, 234, 212, 0.22)
  );
  filter: blur(42px);
  animation: auroraSpin 34s linear infinite;
}
.bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  will-change: transform;
}
.bg-orb-a {
  top: -10%;
  left: 6%;
  width: 46vmax;
  height: 46vmax;
  background: radial-gradient(circle, rgba(45, 212, 191, 0.34), transparent 68%);
  animation: orbFloatA 16s ease-in-out infinite;
}
.bg-orb-b {
  bottom: -16%;
  right: 2%;
  width: 42vmax;
  height: 42vmax;
  background: radial-gradient(circle, rgba(16, 185, 129, 0.3), transparent 66%);
  animation: orbFloatB 21s ease-in-out infinite;
}
.bg-orb-c {
  top: 40%;
  left: 54%;
  width: 30vmax;
  height: 30vmax;
  background: radial-gradient(circle, rgba(94, 234, 212, 0.22), transparent 62%);
  animation: orbFloatC 26s ease-in-out infinite;
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
    linear-gradient(rgba(94, 234, 212, 0.1) 1px, transparent 1px),
    linear-gradient(90deg, rgba(94, 234, 212, 0.1) 1px, transparent 1px);
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
  background: linear-gradient(135deg, rgba(15, 23, 42, 0.42), rgba(30, 58, 95, 0.32));
}
@keyframes auroraSpin {
  to {
    transform: rotate(1turn);
  }
}
@keyframes gridDrift {
  from {
    transform: translate(0, 0);
  }
  to {
    transform: translate(48px, 48px);
  }
}
@keyframes orbFloatA {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(4vw, -3vh) scale(1.12);
  }
}
@keyframes orbFloatB {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(-3vw, 2vh) scale(1.08);
  }
}
@keyframes orbFloatC {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(2vw, 3vh) scale(1.16);
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
  .bg-aurora,
  .bg-orb-a,
  .bg-orb-b,
  .bg-orb-c,
  .fx-grid,
  .fx-scan,
  .fx-particle {
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
  border: 1px solid rgba(45, 212, 191, 0.28);
  backdrop-filter: blur(16px);
  background: rgba(15, 23, 42, 0.78);
  box-shadow:
    0 24px 64px rgba(2, 6, 23, 0.45),
    inset 0 1px 0 rgba(148, 163, 184, 0.12);
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
  box-shadow: 0 0 0 1px rgba(45, 212, 191, 0.22) inset;
  padding: 4px 12px;
}
.login-card :deep(.el-input__inner) {
  color: #f1f5f9;
}
.login-card :deep(.el-input__inner::placeholder) {
  color: #64748b;
}
.login-card :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(45, 212, 191, 0.38) inset;
}
.login-card :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(15, 118, 110, 0.55) inset;
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
  border: 1px solid rgba(45, 212, 191, 0.28);
  border-radius: 10px;
  background: rgba(30, 41, 59, 0.9);
  cursor: pointer;
  overflow: hidden;
  color: #94a3b8;
  font-size: 12px;
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
.login-extras {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  color: #94a3b8;
}
.login-extras :deep(.el-checkbox__label) {
  color: #cbd5e1;
  font-size: 13px;
}
.link-btn {
  border: none;
  background: transparent;
  color: #5eead4;
  font-size: 13px;
  cursor: pointer;
  padding: 2px 4px;
}
.link-btn:hover,
.link-btn:focus-visible {
  color: #99f6e4;
  outline: none;
  text-decoration: underline;
}
.sms-row {
  display: flex;
  gap: 10px;
  width: 100%;
  align-items: stretch;
}
.sms-row .el-input {
  flex: 1;
}
.sms-row .el-button {
  flex: 0 0 auto;
}
.hint {
  color: #64748b;
  font-size: 0.8rem;
  margin: 20px 0 0;
  text-align: center;
}
</style>
