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
            :aria-label="showPhoneForm ? '收起' : '其他方式登录'"
            data-testid="login-other-method"
            @click="showPhoneForm = !showPhoneForm"
          >
            <text class="divider-text">
              {{ showPhoneForm ? '收起' : '其他方式' }}
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
          <template v-else>
            <view class="field field-auth">
              <text class="field-label">图形验证码</text>
              <view class="row">
                <input
                  class="input flex"
                  maxlength="8"
                  :value="captchaCode"
                  placeholder="图形验证码"
                  placeholder-class="ph"
                  @input="captchaCode = eventInputValue($event)"
                />
                <view
                  class="btn-captcha"
                  role="button"
                  aria-label="刷新图形验证码"
                  @click="loadCaptcha"
                >
                  <image
                    v-if="captchaImage"
                    class="captcha-img"
                    :src="captchaImage"
                    mode="aspectFit"
                  />
                  <text v-else class="captcha-placeholder">{{
                    captchaLoading ? UI_COPY.loading : '点击获取'
                  }}</text>
                </view>
              </view>
            </view>
            <view class="field field-auth">
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
                  role="button"
                  class="btn-code"
                  :class="{ disabled: !!codeCooldown || sendingCode }"
                  @click="onSendCode"
                >
                  {{ sendingCode ? '发送中…' : codeCooldown ? codeCooldown + 's' : '获取验证码' }}
                </view>
              </view>
            </view>
          </template>

          <app-button
            class="login-submit"
            data-testid="login-submit"
            :block="false"
            :loading="loading && !wxMode"
            :disabled="loading"
            :label="loading && !wxMode ? '验证中…' : '验证并继续'"
            @click="onLogin"
          />
          <text v-if="isDev && demoHint" class="dev-hint">{{ demoHint }}</text>
        </view>

        <app-button
          class="login-back"
          variant="ghost"
          :block="false"
          label="返回"
          @click="goBack"
        />
        <text v-if="err" class="err">{{ err }}</text>
        <view class="legal-row">
          <text role="button" class="legal-link" @click="goPolicy('agreement')">用户协议</text>
          <text class="legal-dot">·</text>
          <text role="button" class="legal-link" @click="goPolicy('privacy')">隐私政策</text>
          <text class="legal-dot">·</text>
          <text role="button" class="legal-link" @click="goPolicy('refund')">退款规则</text>
        </view>
      </view>
    </view>
  </view>
  <PrivacyConsentModal
    :visible="showPrivacy"
    policy-url="/pages/policy/detail?type=privacy"
    @accepted="onPrivacyAccepted"
    @declined="onPrivacyDeclined"
  />
</template>

<script setup lang="ts">
import { onLoad, onReady, onShow, onUnload } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { computed, ref, watch } from 'vue';
import { getBelowCapsulePadPx } from '@aicabinet/shared-uni/status-bar';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import PrivacyConsentModal from '@aicabinet/shared-uni/components/privacy-consent-modal.vue';
import { usePrivacyConsentModal } from '@aicabinet/shared-uni/use-privacy-consent';
import {
  consumerApi,
  consumerPasswordLogin,
  consumerSmsLogin,
  consumerWxH5Login,
  consumerWxLogin,
  ensureConsumerAuth,
  fetchCaptcha,
  isConsumerLoggedIn,
  sendSmsCode
} from '@/utils/consumer-api';
import { eventInputValue, readDomFieldValue, readDomPassword } from '@/utils/form-bind';
import { showDevTools } from '@/utils/runtime-flags';
import loginBgUrl from '@/static/bg-cooler.jpg';

const { showPrivacy, refreshPrivacyGate, onPrivacyAccepted, onPrivacyDeclined } =
  usePrivacyConsentModal();

const redirect = ref('/pages/index/index');
// H5 无法微信静默授权，默认展开手机号，减少多点一次
const showPhoneForm = ref(typeof globalThis !== 'undefined');
const wxBtnLabel = computed(() => '微信授权登录');
const isDev = showDevTools();
const demoPhone = String(import.meta.env.VITE_DEMO_PHONE || '').trim();
const demoPassword = String(import.meta.env.VITE_DEMO_PASSWORD || '').trim();
// 演示账号主路径是短信万能码；密码页仍可选手动切。避免 H5 默认停在密码导致「获取验证码」不可见。
const mode = ref<'password' | 'sms'>('sms');
const demoHint = computed(() => {
  if (!isDev) return '';
  if (mode.value === 'password' && demoPhone && demoPassword) {
    return `体验账号：${demoPhone} / ${demoPassword}`;
  }
  if (mode.value === 'sms' && demoPassword) {
    return `体验验证码：先点「获取验证码」，可用 ${demoPassword}`;
  }
  return '';
});
const phone = ref(isDev && demoPhone ? demoPhone : '');
const password = ref(isDev && demoPassword ? demoPassword : '');
const code = ref('');
const captchaId = ref('');
const captchaImage = ref('');
const captchaCode = ref('');
const captchaLoading = ref(false);
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

async function loadCaptcha() {
  captchaLoading.value = true;
  try {
    const data = await fetchCaptcha();
    captchaId.value = data.captchaId || '';
    captchaImage.value = data.imageBase64 || '';
    captchaCode.value = '';
  } catch (e) {
    captchaId.value = '';
    captchaImage.value = '';
    err.value = e instanceof Error ? e.message : '图形验证码加载失败';
  } finally {
    captchaLoading.value = false;
  }
}

watch(
  () => showPhoneForm.value && mode.value === 'sms',
  (need) => {
    if (need && !captchaImage.value && !captchaLoading.value) {
      void loadCaptcha();
    }
  },
  { immediate: true }
);

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

/** 已登录时不应停留在 login URL（IMP-004） */
onShow(async () => {
  refreshPrivacyGate();
  if (!isConsumerLoggedIn()) return;
  try {
    const ok = await ensureConsumerAuth();
    if (ok) finishLogin();
  } catch {
    /* 保留登录页供手动重试 */
  }
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
  } catch (error_) {
    err.value = caughtErrorMessage(error_, '微信授权失败');
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
  if (!captchaId.value || !captchaCode.value.trim()) {
    err.value = '请先填写图形验证码';
    if (!captchaImage.value) void loadCaptcha();
    return;
  }
  sendingCode.value = true;
  err.value = '';
  try {
    await sendSmsCode(phoneNum, captchaId.value, captchaCode.value.trim());
    clearCodeTimer();
    codeCooldown.value = 60;
    codeTimer = setInterval(() => {
      codeCooldown.value -= 1;
      if (codeCooldown.value <= 0) clearCodeTimer();
    }, 1000);
    showSuccess('验证码已发送');
    void loadCaptcha();
  } catch (e) {
    err.value = e instanceof Error ? e.message : '发送失败';
    void loadCaptcha();
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

<style scoped src="./login.page.css"></style>
