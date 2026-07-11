<template>
  <view class="page">
    <view class="hero">
      <text class="hero-title">开门前准备</text>
      <text class="hero-sub">完成以下步骤即可扫码开门购物</text>
    </view>

    <view class="steps">
      <view class="step" :class="{ done: account?.verified }">
        <view class="step-dot">{{ account?.verified ? '✓' : '1' }}</view>
        <text class="step-label">实名认证</text>
      </view>
      <view class="step-line" :class="{ done: account?.verified }" />
      <view class="step" :class="{ done: payReady }">
        <view class="step-dot">{{ payReady ? '✓' : '2' }}</view>
        <text class="step-label">开通支付</text>
      </view>
    </view>

    <view v-if="!account?.verified" class="card">
      <text class="card-title">实名认证</text>
      <text class="card-desc">填写真实姓名与身份证后四位（演示环境仅做格式校验）</text>
      <input v-model="realName" class="input" placeholder="真实姓名" maxlength="32" />
      <input v-model="idCardLast4" class="input" type="number" maxlength="4" placeholder="身份证后四位" />
      <button class="btn-primary" hover-class="btn-hover" :loading="verifying" @click="onVerify">
        {{ verifying ? '提交中…' : '提交认证' }}
      </button>
      <text v-if="err" class="err">{{ err }}</text>
    </view>

    <view v-else-if="!payReady" class="card">
      <text class="card-title">开通微信支付分</text>
      <text class="card-desc">免押金开门，关门后自动扣款。演示环境一键模拟开通。</text>
      <view class="status-row">
        <text class="status-label">当前余额</text>
        <text class="status-val">¥{{ balanceYuan }}</text>
      </view>
      <view class="status-row">
        <text class="status-label">最低余额要求</text>
        <text class="status-val">¥5.00（未开通支付分时）</text>
      </view>
      <button class="btn-primary" hover-class="btn-hover" :loading="signing" @click="onSignPayScore">
        {{ signing ? '开通中…' : '一键开通微信支付分' }}
      </button>
      <text class="hint">或使用演示账号 13800138000 / 123456 登录（已实名且有余额）</text>
      <view class="link" @click="goLogin">去手机号验证 ›</view>
      <text v-if="err" class="err">{{ err }}</text>
    </view>

    <view v-else class="card done-card">
      <text class="done-icon">✅</text>
      <text class="done-title">已满足开门条件</text>
      <text class="done-desc">实名与支付均已就绪，可以扫码开门购物</text>
      <button class="btn-primary" hover-class="btn-hover" @click="goShop">去开门购物</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import type { AccountDto } from '@aicabinet/shared-types';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';

const account = ref<AccountDto | null>(null);
const realName = ref('');
const idCardLast4 = ref('');
const verifying = ref(false);
const signing = ref(false);
const err = ref('');
const fromOpen = ref(false);

const balanceYuan = computed(() => ((account.value?.balanceCents || 0) / 100).toFixed(2));
const payReady = computed(
  () => (account.value?.balanceCents || 0) >= 500
);

onLoad((opts) => {
  fromOpen.value = opts?.from === 'open';
});

onShow(async () => {
  err.value = '';
  const ok = await ensureConsumerAuth();
  if (!ok) {
    uni.showToast({ title: '请先完成微信授权', icon: 'none' });
    return;
  }
  try {
    account.value = await consumerApi.account();
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

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/verify/verify') });
}

function goShop() {
  uni.switchTab({ url: '/pages/index/index' });
}
</script>

<style scoped>
.page { padding: 24rpx 24rpx 48rpx; min-height: 100vh; background: #f7f7f7; box-sizing: border-box; }
.hero { padding: 16rpx 8rpx 32rpx; }
.hero-title { font-size: 40rpx; font-weight: 700; color: #191919; display: block; }
.hero-sub { font-size: 26rpx; color: #888; margin-top: 8rpx; display: block; }
.steps { display: flex; align-items: center; justify-content: center; padding: 24rpx 0 40rpx; }
.step { display: flex; flex-direction: column; align-items: center; gap: 8rpx; }
.step-dot { width: 56rpx; height: 56rpx; border-radius: 50%; background: #e5e5e5; color: #888; font-size: 28rpx; display: flex; align-items: center; justify-content: center; font-weight: 600; }
.step.done .step-dot { background: #07c160; color: #fff; }
.step-label { font-size: 24rpx; color: #888; }
.step.done .step-label { color: #07c160; }
.step-line { width: 120rpx; height: 4rpx; background: #e5e5e5; margin: 0 16rpx 28rpx; }
.step-line.done { background: #07c160; }
.card { background: #fff; border-radius: 24rpx; padding: 32rpx; margin-bottom: 24rpx; }
.card-title { font-size: 32rpx; font-weight: 600; color: #191919; display: block; }
.card-desc { font-size: 26rpx; color: #888; margin: 12rpx 0 28rpx; display: block; line-height: 1.5; }
.input { background: #f7f7f7; border-radius: 12rpx; padding: 24rpx; margin-bottom: 20rpx; font-size: 30rpx; }
.btn-primary { background: #07c160; color: #fff; border-radius: 12rpx; font-size: 32rpx; font-weight: 600; border: none; margin-top: 8rpx; }
.btn-hover { opacity: 0.85; }
.err { color: #fa5151; font-size: 26rpx; margin-top: 16rpx; display: block; }
.status-row { display: flex; justify-content: space-between; padding: 16rpx 0; border-bottom: 1rpx solid #f0f0f0; }
.status-label { font-size: 28rpx; color: #888; }
.status-val { font-size: 28rpx; color: #191919; }
.hint { font-size: 24rpx; color: #b2b2b2; margin-top: 24rpx; display: block; line-height: 1.5; }
.link { color: #576b95; font-size: 28rpx; margin-top: 20rpx; }
.done-card { text-align: center; padding: 48rpx 32rpx; }
.done-icon { font-size: 80rpx; display: block; margin-bottom: 16rpx; }
.done-title { font-size: 34rpx; font-weight: 600; color: #191919; display: block; }
.done-desc { font-size: 26rpx; color: #888; margin: 12rpx 0 32rpx; display: block; }
</style>
<style scoped>
.page{background:linear-gradient(180deg,#ecfdf5,#f5f7f8 340rpx)}.hero{margin:0 -24rpx;padding:42rpx 34rpx 78rpx;border-radius:0 0 38rpx 38rpx;color:#fff;background:linear-gradient(145deg,#064e3b,#059669 58%,#14b8a6)}.hero-title{color:#fff;font-size:44rpx}.hero-sub{color:rgba(255,255,255,.78)}.steps{position:relative;margin:-45rpx 0 22rpx;padding:24rpx 22rpx;border-radius:24rpx;background:#fff;box-shadow:0 14rpx 34rpx rgba(15,23,42,.09)}.step-dot{box-shadow:0 0 0 7rpx #f4f7f5}.step.done .step-dot{background:linear-gradient(135deg,#059669,#0d9488);box-shadow:0 0 0 7rpx #d1fae5}.card{padding:34rpx;border:1rpx solid #edf1ef;border-radius:26rpx;box-shadow:0 12rpx 34rpx rgba(15,23,42,.06)}.input{border:1rpx solid #e3eae6;border-radius:17rpx;background:#f8faf9}.btn-primary{border-radius:44rpx;background:linear-gradient(135deg,#059669,#0d9488);box-shadow:0 9rpx 24rpx rgba(5,150,105,.2)}.status-row{padding:20rpx 0}.done-icon{font-size:88rpx}
</style>
