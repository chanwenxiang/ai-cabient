<template>
  <view class="page">
    <view class="hero">
      <text class="hero-title">开通免密支付</text>
      <text class="hero-sub">完成后即可扫码开门，关门自动扣款</text>
    </view>

    <view class="steps">
      <view class="step" :class="{ done: account?.verified }">
        <view class="step-dot">{{ account?.verified ? '✓' : '1' }}</view>
        <text class="step-label">实名</text>
      </view>
      <view class="step-line" :class="{ done: account?.verified }" />
      <view class="step" :class="{ done: payReady }">
        <view class="step-dot">{{ payReady ? '✓' : '2' }}</view>
        <text class="step-label">免密支付</text>
      </view>
    </view>

    <view v-if="!account?.verified" class="card">
      <text class="card-title">实名认证</text>
      <text class="card-desc">用于保障交易安全，信息仅用于本柜购物核验</text>
      <text class="field-label">真实姓名</text>
      <input v-model="realName" class="input" aria-label="真实姓名" placeholder="真实姓名…" maxlength="32" />
      <text class="field-label">身份证后四位</text>
      <input v-model="idCardLast4" class="input" type="number" maxlength="4" aria-label="身份证后四位" placeholder="后四位…" />
      <button class="btn-primary" hover-class="btn-hover" :loading="verifying" @click="onVerify">
        {{ verifying ? '提交中…' : '下一步' }}
      </button>
      <text v-if="devTools" class="hint">开发环境仅做格式校验，上线需对接实名核验。</text>
      <text v-if="err" class="err">{{ err }}</text>
    </view>

    <view v-else-if="!payReady" class="card">
      <text class="card-title">开通免密支付</text>
      <text class="card-desc">推荐开通支付分 / 免密代扣；可用余额 ≥ ¥{{ needYuan }} 也可临时开门。</text>
      <view class="status-row">
        <text class="status-label">可用余额</text>
        <text class="status-val">{{ balanceYuan }}</text>
      </view>
      <view v-if="frozenYuan !== '¥0.00'" class="status-row">
        <text class="status-label">冻结中</text>
        <text class="status-val">{{ frozenYuan }}</text>
      </view>
      <view class="status-row">
        <text class="status-label">微信支付分</text>
        <text class="status-val">{{ wechatReady ? '已开通' : '未开通' }}</text>
      </view>
      <view class="status-row">
        <text class="status-label">支付宝免密</text>
        <text class="status-val">{{ alipayReady ? '已开通' : '未开通' }}</text>
      </view>
      <button class="btn-primary" hover-class="btn-hover" :loading="signing" @click="onSignPayScore">
        {{ signing ? '开通中…' : '开通微信支付分' }}
      </button>
      <button class="btn-alipay" hover-class="btn-hover" :loading="signingAlipay" @click="onSignAlipay">
        {{ signingAlipay ? '开通中…' : '开通支付宝免密' }}
      </button>
      <view class="link" @click="goRecharge">余额不足？去充值 ›</view>
      <text v-if="devTools" class="hint">开发环境为模拟开通；正式环境将跳转微信/支付宝签约页。</text>
      <text v-if="err" class="err">{{ err }}</text>
    </view>

    <view v-else class="card done-card">
      <text class="done-icon">✓</text>
      <text class="done-title">可以开门购物了</text>
      <text class="done-desc">扫柜门二维码即可开门取货</text>
      <button class="btn-primary" hover-class="btn-hover" @click="goShop">去扫码开门</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import type { AccountDto } from '@aicabinet/shared-types';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import {
  availableCents,
  isPayReady,
  preauthYuanLabel,
  resolveClientPreauthCents
} from '@/utils/account';
import { showDevTools } from '@/utils/runtime-flags';

const devTools = showDevTools();
const account = ref<AccountDto | null>(null);
const realName = ref('');
const idCardLast4 = ref('');
const verifying = ref(false);
const signing = ref(false);
const signingAlipay = ref(false);
const err = ref('');
const fromOpen = ref(false);
const configPreauthCents = ref<number | null>(null);

const preauthCents = computed(() =>
  resolveClientPreauthCents({ configPreauthCents: configPreauthCents.value })
);
const needYuan = computed(() => preauthYuanLabel(preauthCents.value));
const balanceYuan = computed(() => fmtMoney(availableCents(account.value)));
const frozenYuan = computed(() => fmtMoney(Math.max(0, account.value?.frozenCents || 0)));
const payReady = computed(() => isPayReady(account.value, null, preauthCents.value));
const wechatReady = computed(() => !!account.value?.payscoreEnabled);
const alipayReady = computed(() => !!account.value?.alipayAgreementEnabled);

onLoad((opts) => {
  fromOpen.value = opts?.from === 'open';
});

onShow(async () => {
  err.value = '';
  const ok = await ensureConsumerAuth();
  if (!ok) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/verify/verify')
    });
    return;
  }
  try {
    const [acc, cfg] = await Promise.all([
      consumerApi.account(),
      consumerApi.consumerPublicConfig().catch(() => null)
    ]);
    account.value = acc;
    const p = Number(cfg?.preauthCents);
    configPreauthCents.value = Number.isFinite(p) && p > 0 ? p : null;
  } catch (e) {
    err.value = e instanceof Error ? e.message : '加载账户失败';
  }
});

async function onVerify() {
  const name = realName.value.trim();
  const last4 = idCardLast4.value.trim();
  if (name.length < 2) {
    err.value = '请输入真实姓名';
    return;
  }
  if (!/^\d{4}$/.test(last4)) {
    err.value = '身份证后四位须为 4 位数字';
    return;
  }
  verifying.value = true;
  err.value = '';
  try {
    account.value = await consumerApi.verifyIdentity({ realName: name, idCardLast4: last4 });
    uni.showToast({ title: '实名成功', icon: 'success' });
    if (payReady.value && fromOpen.value) {
      setTimeout(goShop, 600);
    }
  } catch (e) {
    err.value = e instanceof Error ? e.message : '认证失败';
  } finally {
    verifying.value = false;
  }
}

async function onSignPayScore() {
  signing.value = true;
  err.value = '';
  try {
    const res = await consumerApi.signPayScore();
    account.value = await consumerApi.account();
    uni.showToast({ title: res.message || '开通成功', icon: 'success' });
    if (fromOpen.value) {
      setTimeout(goShop, 600);
    }
  } catch (e) {
    err.value = e instanceof Error ? e.message : '开通失败';
  } finally {
    signing.value = false;
  }
}

async function onSignAlipay() {
  signingAlipay.value = true;
  err.value = '';
  try {
    const res = await consumerApi.signAlipayAgreement();
    account.value = await consumerApi.account();
    uni.showToast({ title: res.message || '开通成功', icon: 'success' });
    if (fromOpen.value) {
      setTimeout(goShop, 600);
    }
  } catch (e) {
    err.value = e instanceof Error ? e.message : '开通失败';
  } finally {
    signingAlipay.value = false;
  }
}

function goRecharge() {
  uni.navigateTo({ url: '/pages/recharge/recharge' });
}

function goShop() {
  uni.switchTab({ url: '/pages/index/index' });
}
</script>

<style scoped>
.page {
  padding: 24rpx 24rpx 48rpx;
  min-height: 100vh;
  background: linear-gradient(180deg, #ecfdf5, #f5f7f8 340rpx);
  box-sizing: border-box;
}
.hero {
  margin: 0 -24rpx;
  padding: 42rpx 34rpx 78rpx;
  border-radius: 0 0 38rpx 38rpx;
  color: #fff;
  background: linear-gradient(145deg, #064e3b, #059669 58%, #14b8a6);
}
.hero-title { font-size: 44rpx; font-weight: 700; color: #fff; display: block; }
.hero-sub { font-size: 26rpx; color: rgba(255, 255, 255, 0.78); margin-top: 8rpx; display: block; }
.steps {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  margin: -45rpx 0 22rpx;
  padding: 24rpx 22rpx;
  border-radius: 24rpx;
  background: #fff;
  box-shadow: 0 14rpx 34rpx rgba(15, 23, 42, 0.09);
}
.step { display: flex; flex-direction: column; align-items: center; gap: 8rpx; }
.step-dot {
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  background: #e5e5e5;
  color: #888;
  font-size: 28rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  box-shadow: 0 0 0 7rpx #f4f7f5;
}
.step.done .step-dot {
  background: linear-gradient(135deg, #059669, #0d9488);
  color: #fff;
  box-shadow: 0 0 0 7rpx #d1fae5;
}
.step-label { font-size: 24rpx; color: #888; }
.step.done .step-label { color: #07c160; }
.step-line { width: 120rpx; height: 4rpx; background: #e5e5e5; margin: 0 16rpx 28rpx; }
.step-line.done { background: #07c160; }
.card {
  background: #fff;
  border-radius: 26rpx;
  padding: 34rpx;
  margin-bottom: 24rpx;
  border: 1rpx solid #edf1ef;
  box-shadow: 0 12rpx 34rpx rgba(15, 23, 42, 0.06);
}
.card-title { font-size: 32rpx; font-weight: 600; color: #191919; display: block; }
.card-desc { font-size: 26rpx; color: #888; margin: 12rpx 0 28rpx; display: block; line-height: 1.5; }
.field-label {
  display: block;
  font-size: 24rpx;
  color: #64748b;
  margin-bottom: 8rpx;
}
.input {
  background: #f8faf9;
  border: 1rpx solid #e3eae6;
  border-radius: 17rpx;
  padding: 24rpx;
  margin-bottom: 20rpx;
  font-size: 30rpx;
}
.btn-primary {
  background: linear-gradient(135deg, #059669, #0d9488);
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
  font-weight: 600;
  border: none;
  margin-top: 8rpx;
  box-shadow: 0 9rpx 24rpx rgba(5, 150, 105, 0.2);
}
.btn-alipay { background: #1677ff; color: #fff; border-radius: 12rpx; font-size: 32rpx; font-weight: 600; border: none; margin-top: 16rpx; }
.btn-alipay::after { border: none; }
.btn-hover { opacity: 0.85; }
.err { color: #fa5151; font-size: 26rpx; margin-top: 16rpx; display: block; }
.status-row { display: flex; justify-content: space-between; padding: 20rpx 0; border-bottom: 1rpx solid #f0f0f0; }
.status-label { font-size: 28rpx; color: #888; }
.status-val { font-size: 28rpx; color: #191919; }
.hint { font-size: 24rpx; color: #b2b2b2; margin-top: 24rpx; display: block; line-height: 1.5; }
.link { color: #576b95; font-size: 28rpx; margin-top: 20rpx; }
.done-card { text-align: center; padding: 48rpx 32rpx; }
.done-icon { font-size: 88rpx; display: block; margin-bottom: 16rpx; }
.done-title { font-size: 34rpx; font-weight: 600; color: #191919; display: block; }
.done-desc { font-size: 26rpx; color: #888; margin: 12rpx 0 32rpx; display: block; }
</style>
