<template>
  <view class="drawer-mask" @click="onCancel">
    <view class="drawer-panel" @click.stop>
      <view class="drawer-handle" />
      <text class="drawer-title">开门前准备</text>
      <text class="drawer-sub">完成以下步骤即可开门取货</text>

      <view class="prep-steps">
        <view class="prep-step" :class="{ done: account?.verified }">
          <view class="prep-dot">{{ account?.verified ? '✓' : '1' }}</view>
          <text>实名</text>
        </view>
        <view class="prep-line" :class="{ done: account?.verified }" />
        <view class="prep-step" :class="{ done: payReady }">
          <view class="prep-dot">{{ payReady ? '✓' : '2' }}</view>
          <text>支付</text>
        </view>
      </view>

      <view v-if="!account?.verified" class="drawer-body">
        <text class="field-label">真实姓名</text>
        <input v-model="realName" class="input" placeholder="与身份证一致" maxlength="32" />
        <text class="field-label">身份证后四位</text>
        <input v-model="idCardLast4" class="input" type="number" maxlength="4" placeholder="0000" />
        <button class="btn-primary" hover-class="btn-hover" :loading="busy" @click="onVerify">
          {{ busy ? '提交中…' : '完成实名认证' }}
        </button>
      </view>

      <view v-else-if="!payReady" class="drawer-body">
        <text class="drawer-desc">{{ payDesc }}</text>
        <view v-if="!entryChannel" class="channel-pick">
          <text class="field-label">本次扫码渠道</text>
          <view class="channel-chips">
            <text
              class="channel-chip"
              :class="{ on: pickedChannel === 'WECHAT' }"
              @click="pickedChannel = 'WECHAT'"
            >微信</text>
            <text
              class="channel-chip"
              :class="{ on: pickedChannel === 'ALIPAY' }"
              @click="pickedChannel = 'ALIPAY'"
            >支付宝</text>
          </view>
        </view>
        <view class="balance-row">
          <text>当前余额</text>
          <text class="balance-val">¥{{ balanceYuan }}</text>
        </view>
        <text class="balance-warning">{{ payReadyHintText }}</text>
        <button
          v-if="showWechatSign"
          class="btn-primary"
          hover-class="btn-hover"
          :loading="busy"
          :disabled="busy"
          @click="onSignPayScore"
        >
          {{ busy ? '开通中…' : '开通微信支付分（推荐）' }}
        </button>
        <button
          v-if="showAlipaySign"
          class="btn-alipay"
          hover-class="btn-hover"
          :loading="busy"
          :disabled="busy"
          @click="onSignAlipay"
        >
          {{ busy ? '开通中…' : '开通支付宝免密（推荐）' }}
        </button>
        <button
          v-if="wechatRechargeEnabled"
          class="btn-wechat"
          hover-class="btn-hover"
          :loading="busy"
          :disabled="busy"
          @click="onWeChatRecharge"
        >
          {{ busy ? '处理中…' : wechatPayLive ? '微信支付充值 ¥20' : '微信模拟充值 ¥20' }}
        </button>
        <button v-if="mockRechargeEnabled" class="btn-ghost-fill" hover-class="btn-hover" :loading="busy" :disabled="busy" @click="onMockRecharge">
          {{ busy ? '发放中…' : '模拟充值 ¥20（开发联调）' }}
        </button>
        <button
          v-if="alipayRechargeEnabled"
          class="btn-alipay"
          hover-class="btn-hover"
          :loading="busy"
          :disabled="busy"
          @click="onAlipayRecharge"
        >
          {{ busy ? '跳转中…' : '支付宝沙箱充值 ¥20' }}
        </button>
        <text v-if="!mockRechargeEnabled && !alipayRechargeEnabled && !wechatRechargeEnabled && !showWechatSign && !showAlipaySign" class="drawer-desc">请联系现场运营人员发放余额。</text>
        <view class="support-link" @click="contactOps">联系现场运营人员</view>
      </view>

      <text v-if="err" class="err">{{ err }}</text>
      <text class="cancel-link" @click="onCancel">稍后再说</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { AccountDto } from '@aicabinet/shared-types';
import { consumerApi } from '@/utils/consumer-api';
import {
  isPayReady,
  normalizeEntryChannel,
  payReadyHint,
  type EntryChannel
} from '@/utils/account';
import { openAlipayPrepay, runWeChatRecharge, savePendingRechargeOrder } from '@/utils/recharge';

const props = defineProps<{
  account: AccountDto | null;
  entryChannel?: string | null;
}>();

const emit = defineEmits<{
  done: [channel?: EntryChannel | null];
  cancel: [];
}>();

const account = ref<AccountDto | null>(props.account);
const realName = ref('');
const idCardLast4 = ref('');
const busy = ref(false);
const err = ref('');
const mockRechargeEnabled = ref(true);
const alipayRechargeEnabled = ref(false);
const wechatRechargeEnabled = ref(false);
const wechatPayLive = ref(false);
const payScoreSignEnabled = ref(true);
const pickedChannel = ref<EntryChannel | null>(normalizeEntryChannel(props.entryChannel));

watch(
  () => props.account,
  (v) => {
    account.value = v;
  }
);

watch(
  () => props.entryChannel,
  (v) => {
    const n = normalizeEntryChannel(v);
    if (n) pickedChannel.value = n;
  }
);

function resolveMockEnabled(flag?: string): boolean {
  if (flag === 'true') return true;
  if (flag === 'false') return false;
  return import.meta.env.DEV;
}

consumerApi.consumerPublicConfig().then((cfg) => {
  mockRechargeEnabled.value = resolveMockEnabled(cfg?.mockEnabled);
  alipayRechargeEnabled.value = cfg?.alipayRechargeEnabled === 'true';
  wechatRechargeEnabled.value = cfg?.wechatRechargeEnabled === 'true';
  wechatPayLive.value = cfg?.wechatPayLive === 'true';
  payScoreSignEnabled.value = cfg?.payScoreSignEnabled !== 'false';
}).catch(() => {
  mockRechargeEnabled.value = import.meta.env.DEV;
  alipayRechargeEnabled.value = false;
  wechatRechargeEnabled.value = import.meta.env.DEV;
  wechatPayLive.value = false;
  payScoreSignEnabled.value = true;
});

const entryChannel = computed(() => normalizeEntryChannel(props.entryChannel) || pickedChannel.value);
const balanceYuan = computed(() => ((account.value?.balanceCents || 0) / 100).toFixed(2));
const payReady = computed(() => isPayReady(account.value, entryChannel.value));
const payReadyHintText = computed(() => payReadyHint(account.value, entryChannel.value));
const payDesc = computed(() => {
  const c = entryChannel.value;
  if (c === 'WECHAT') return '微信扫码入柜：开通微信支付分后，关门自动扣款（余额仅作兜底）。';
  if (c === 'ALIPAY') return '支付宝扫码入柜：开通免密代扣后，关门自动扣款（余额仅作兜底）。';
  return '请选择扫码渠道并开通对应免密支付；余额 ≥ ¥5 也可临时开门。';
});
const showWechatSign = computed(
  () => payScoreSignEnabled.value && (!entryChannel.value || entryChannel.value === 'WECHAT')
);
const showAlipaySign = computed(() => !entryChannel.value || entryChannel.value === 'ALIPAY');

watch(payReady, (ready) => {
  if (ready && account.value?.verified) {
    emit('done', entryChannel.value);
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
  busy.value = true;
  err.value = '';
  try {
    account.value = await consumerApi.verifyIdentity({ realName: name, idCardLast4: last4 });
    if (payReady.value) emit('done', entryChannel.value);
  } catch (e) {
    err.value = e instanceof Error ? e.message : '认证失败';
  } finally {
    busy.value = false;
  }
}

async function onSignPayScore() {
  if (busy.value) return;
  if (!pickedChannel.value && !props.entryChannel) pickedChannel.value = 'WECHAT';
  busy.value = true;
  err.value = '';
  try {
    await consumerApi.signPayScore();
    account.value = await consumerApi.account();
    uni.showToast({ title: '支付分已开通', icon: 'success' });
    if (payReady.value) emit('done', entryChannel.value || 'WECHAT');
  } catch (e) {
    err.value = e instanceof Error ? e.message : '开通失败';
  } finally {
    busy.value = false;
  }
}

async function onSignAlipay() {
  if (busy.value) return;
  if (!pickedChannel.value && !props.entryChannel) pickedChannel.value = 'ALIPAY';
  busy.value = true;
  err.value = '';
  try {
    await consumerApi.signAlipayAgreement();
    account.value = await consumerApi.account();
    uni.showToast({ title: '支付宝免密已开通', icon: 'success' });
    if (payReady.value) emit('done', entryChannel.value || 'ALIPAY');
  } catch (e) {
    err.value = e instanceof Error ? e.message : '开通失败';
  } finally {
    busy.value = false;
  }
}

async function onWeChatRecharge() {
  if (busy.value) return;
  busy.value = true;
  err.value = '';
  try {
    const key = `prep-wechat-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    await runWeChatRecharge(2000, key);
    account.value = await consumerApi.account();
    uni.showToast({ title: '充值成功', icon: 'success' });
  } catch (error) {
    err.value = error instanceof Error ? error.message : '充值失败';
  } finally {
    busy.value = false;
  }
}

async function onAlipayRecharge() {
  if (busy.value) return;
  busy.value = true;
  err.value = '';
  try {
    const key = `prep-alipay-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const prepay = await consumerApi.createRechargePrepay('ALIPAY', 2000, key);
    if (!prepay.alipayPay?.payFormHtml && !prepay.alipayPay?.payUrl) {
      throw new Error('未获取到支付宝支付链接');
    }
    savePendingRechargeOrder(prepay.orderId);
    openAlipayPrepay(prepay.alipayPay);
  } catch (error) {
    err.value = error instanceof Error ? error.message : '充值失败';
  } finally {
    busy.value = false;
  }
}

async function onMockRecharge() {
  if (busy.value) return;
  const confirmed = await new Promise<boolean>((resolve) => uni.showModal({
    title: '确认模拟充值',
    content: '将发放 ¥20.00 余额（仅开发联调，不会真实扣款）。',
    confirmText: '确认发放',
    success: (result) => resolve(result.confirm),
    fail: () => resolve(false)
  }));
  if (!confirmed) return;
  busy.value = true;
  err.value = '';
  try {
    const key = `prep-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const order = await consumerApi.createMockRecharge(2000, key);
    await consumerApi.confirmMockRecharge(order.orderId);
    account.value = await consumerApi.account();
    uni.showToast({ title: '余额已到账', icon: 'success' });
  } catch (error) {
    err.value = error instanceof Error ? error.message : '余额发放失败';
  } finally { busy.value = false; }
}

function contactOps() {
  uni.showModal({
    title: '联系运营人员',
    content: '请联系柜机所在点位的现场工作人员，并提供柜机编号。运营人员可在后台发放余额。',
    showCancel: false,
    confirmText: '我知道了'
  });
}

function onCancel() {
  emit('cancel');
}
</script>

<style scoped>
.drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 200;
  display: flex;
  align-items: flex-end;
}
.drawer-panel {
  width: 100%;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  padding: 16rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.drawer-handle {
  width: 64rpx;
  height: 8rpx;
  background: #e5e5e5;
  border-radius: 4rpx;
  margin: 0 auto 24rpx;
}
.drawer-title {
  font-size: 34rpx;
  font-weight: 700;
  color: #191919;
  display: block;
  text-align: center;
}
.drawer-sub {
  font-size: 26rpx;
  color: #888;
  display: block;
  text-align: center;
  margin-top: 8rpx;
  margin-bottom: 28rpx;
}
.prep-steps {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 28rpx;
}
.prep-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
  font-size: 22rpx;
  color: #888;
}
.prep-step.done { color: #07c160; }
.prep-dot {
  width: 48rpx;
  height: 48rpx;
  border-radius: 50%;
  background: #e5e5e5;
  color: #888;
  font-size: 24rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}
.prep-step.done .prep-dot { background: #07c160; color: #fff; }
.prep-line {
  width: 80rpx;
  height: 4rpx;
  background: #e5e5e5;
  margin: 0 12rpx 20rpx;
}
.prep-line.done { background: #07c160; }
.drawer-body { margin-top: 8rpx; }
.field-label {
  font-size: 26rpx;
  color: #666;
  display: block;
  margin-bottom: 8rpx;
}
.input {
  background: #f7f7f7;
  border-radius: 12rpx;
  padding: 22rpx 24rpx;
  margin-bottom: 20rpx;
  font-size: 30rpx;
}
.drawer-desc {
  font-size: 26rpx;
  color: #888;
  line-height: 1.5;
  display: block;
  margin-bottom: 20rpx;
}
.balance-row {
  display: flex;
  justify-content: space-between;
  padding: 16rpx 0 24rpx;
  font-size: 28rpx;
  color: #666;
}
.balance-val { color: #191919; font-weight: 600; }
.btn-primary {
  margin: 0;
  background: #07c160;
  color: #fff;
  border-radius: 12rpx;
  font-size: 32rpx;
  font-weight: 600;
  line-height: 88rpx;
  height: 88rpx;
}
.btn-primary::after { border: none; }
.btn-alipay {
  margin: 16rpx 0 0;
  background: #1677ff;
  color: #fff;
  border-radius: 12rpx;
  font-size: 30rpx;
  font-weight: 600;
  line-height: 88rpx;
  height: 88rpx;
}
.btn-alipay::after { border: none; }
.btn-wechat {
  margin: 16rpx 0 0;
  background: #07c160;
  color: #fff;
  border-radius: 12rpx;
  font-size: 30rpx;
  font-weight: 600;
  line-height: 88rpx;
  height: 88rpx;
}
.btn-wechat::after { border: none; }
.btn-ghost-fill {
  margin: 16rpx 0 0;
  background: #f0fdf4;
  color: #047857;
  border-radius: 12rpx;
  font-size: 28rpx;
  font-weight: 600;
  line-height: 88rpx;
  height: 88rpx;
  border: 1rpx solid #bbf7d0;
}
.btn-ghost-fill::after { border: none; }
.btn-hover { opacity: 0.85; }
.hint {
  font-size: 24rpx;
  color: #b2b2b2;
  display: block;
  text-align: center;
  margin-top: 16rpx;
}
.balance-warning { display: block; padding: 20rpx; border-radius: 12rpx; background: #fff7e6; color: #ad6800; font-size: 25rpx; line-height: 1.5; }
.channel-pick { margin-bottom: 16rpx; }
.channel-chips { display: flex; gap: 16rpx; margin-top: 12rpx; }
.channel-chip {
  flex: 1;
  text-align: center;
  padding: 18rpx 0;
  border-radius: 12rpx;
  background: #f2f4f8;
  color: #576b95;
  font-size: 28rpx;
  border: 2rpx solid transparent;
}
.channel-chip.on {
  background: #ecfdf5;
  color: #047857;
  border-color: #34d399;
  font-weight: 600;
}
.balance-warning + .btn-primary{margin-top:22rpx}.support-link{padding:22rpx 0 4rpx;text-align:center;color:#64748b;font-size:25rpx}
.err {
  color: #fa5151;
  font-size: 26rpx;
  display: block;
  text-align: center;
  margin-top: 16rpx;
}
.cancel-link {
  display: block;
  text-align: center;
  color: #888;
  font-size: 28rpx;
  margin-top: 24rpx;
  padding: 12rpx 0;
}
</style>
<style scoped>
.drawer-mask{justify-content:center}.drawer-panel{max-width:520px;padding:18rpx 32rpx calc(34rpx + env(safe-area-inset-bottom));border-radius:30rpx 30rpx 0 0;box-shadow:0 -18rpx 55rpx rgba(15,23,42,.2)}.drawer-handle{background:#cbd5e1}.drawer-title{font-size:36rpx;color:#1b3027}.prep-dot{box-shadow:0 0 0 6rpx #f4f7f5}.prep-step.done .prep-dot{background:linear-gradient(135deg,#059669,#0d9488);box-shadow:0 0 0 6rpx #d1fae5}.input{border:1rpx solid #e3eae6;border-radius:17rpx;background:#f8faf9}.btn-primary{border-radius:44rpx;background:linear-gradient(135deg,#059669,#0d9488);box-shadow:0 9rpx 24rpx rgba(5,150,105,.2)}
</style>
