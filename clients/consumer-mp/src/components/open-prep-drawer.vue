<template>
  <view class="drawer-mask" @click="onCancel">
    <view class="drawer-panel" @click.stop>
      <view class="drawer-handle" />
      <text class="drawer-title">开通后即可开门</text>
      <text class="drawer-sub">首次使用需完成实名与免密支付</text>

      <view class="prep-steps">
        <view class="prep-step" :class="{ done: account?.verified }">
          <view class="prep-dot">{{ account?.verified ? '✓' : '1' }}</view>
          <text>实名</text>
        </view>
        <view class="prep-line" :class="{ done: account?.verified }" />
        <view class="prep-step" :class="{ done: payReady }">
          <view class="prep-dot">{{ payReady ? '✓' : '2' }}</view>
          <text>免密支付</text>
        </view>
      </view>

      <view v-if="!account?.verified" class="drawer-body">
        <text class="drawer-desc">用于保障交易安全，信息仅用于本柜购物核验</text>
        <text class="field-label">真实姓名</text>
        <input v-model="realName" class="input" placeholder="与身份证一致" maxlength="32" />
        <text class="field-label">身份证后四位</text>
        <input v-model="idCardLast4" class="input" type="number" maxlength="4" placeholder="0000" />
        <button class="btn-primary" hover-class="btn-hover" :loading="busy" @click="onVerify">
          {{ busy ? '提交中…' : '下一步' }}
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
              >微信</text
            >
            <text
              class="channel-chip"
              :class="{ on: pickedChannel === 'ALIPAY' }"
              @click="pickedChannel = 'ALIPAY'"
              >支付宝</text
            >
          </view>
        </view>
        <button
          v-if="showWechatSign"
          class="btn-primary"
          hover-class="btn-hover"
          :loading="busy"
          :disabled="busy"
          @click="onSignPayScore"
        >
          {{ busy ? '开通中…' : '开通微信支付分' }}
        </button>
        <button
          v-if="showAlipaySign"
          class="btn-alipay"
          hover-class="btn-hover"
          :loading="busy"
          :disabled="busy"
          @click="onSignAlipay"
        >
          {{ busy ? '开通中…' : '开通支付宝免密' }}
        </button>
        <view class="fallback-block">
          <text class="fallback-title">或使用余额开门</text>
          <view class="balance-row">
            <text>可用余额</text>
            <text class="balance-val">{{ balanceYuan }}</text>
          </view>
          <text v-if="frozenYuan !== '¥0.00'" class="balance-sub"
            >含冻结 {{ frozenYuan }}（开门预授权等）</text
          >
          <text class="balance-warning">{{ payReadyHintText }}</text>
          <text v-if="balanceInsufficient" class="balance-warning">
            可用余额不足预授权 ¥{{ needYuan }}，请先充值或开通免密后再开门
          </text>
          <button
            v-if="wechatPayLive || (devTools && wechatRechargeEnabled)"
            class="btn-wechat"
            hover-class="btn-hover"
            :loading="busy"
            :disabled="busy"
            @click="onWeChatRecharge"
          >
            {{ busy ? '处理中…' : wechatPayLive ? '微信支付充值 ¥20' : '微信模拟充值 ¥20' }}
          </button>
          <button
            v-if="devTools && mockRechargeEnabled"
            class="btn-ghost-fill"
            hover-class="btn-hover"
            :loading="busy"
            :disabled="busy"
            @click="onMockRecharge"
          >
            {{ busy ? '发放中…' : '模拟充值 ¥20' }}
          </button>
          <button
            v-if="devTools && alipayRechargeEnabled"
            class="btn-alipay"
            hover-class="btn-hover"
            :loading="busy"
            :disabled="busy"
            @click="onAlipayRecharge"
          >
            {{
              busy ? '处理中…' : mockRechargeEnabled ? '支付宝模拟充值 ¥20' : '支付宝沙箱充值 ¥20'
            }}
          </button>
          <view class="support-link" @click="goRechargePage">去充值页选择金额 ›</view>
        </view>
        <view class="support-link muted" @click="contactOps">联系现场运营</view>
      </view>

      <text v-if="err" class="err">{{ err }}</text>
      <text class="cancel-link" @click="onCancel">稍后再说</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { AccountDto } from '@aicabinet/shared-types';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import { consumerApi } from '@/utils/consumer-api';
import {
  availableCents,
  isPayReady,
  normalizeEntryChannel,
  payReadyHint,
  preauthYuanLabel,
  resolveClientPreauthCents,
  type EntryChannel
} from '@/utils/account';
import { runAlipayRecharge, runWeChatRecharge } from '@/utils/recharge';
import {
  resolveMockEnabled,
  resolveSandboxRecharge,
  resolveWechatRechargeVisible,
  showDevTools
} from '@/utils/runtime-flags';

const props = defineProps<{
  account: AccountDto | null;
  entryChannel?: string | null;
  /** 柜机侧预授权门槛（分），来自 DeviceStatus.preauthCents */
  devicePreauthCents?: number | null;
}>();

const emit = defineEmits<{
  done: [channel?: EntryChannel | null];
  cancel: [];
}>();

const devTools = showDevTools();
const account = ref<AccountDto | null>(props.account);
const realName = ref('');
const idCardLast4 = ref('');
const busy = ref(false);
const err = ref('');
const mockRechargeEnabled = ref(false);
const alipayRechargeEnabled = ref(false);
const wechatRechargeEnabled = ref(false);
const wechatPayLive = ref(false);
const payScoreSignEnabled = ref(true);
const configPreauthCents = ref<number | null>(null);
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

consumerApi
  .consumerPublicConfig()
  .then((cfg) => {
    mockRechargeEnabled.value = resolveMockEnabled(cfg?.mockEnabled);
    alipayRechargeEnabled.value = resolveSandboxRecharge(cfg?.alipayRechargeEnabled);
    wechatPayLive.value = cfg?.wechatPayLive === 'true';
    wechatRechargeEnabled.value = resolveWechatRechargeVisible({
      wechatRechargeEnabled: cfg?.wechatRechargeEnabled,
      wechatPayLive: cfg?.wechatPayLive
    });
    payScoreSignEnabled.value = cfg?.payScoreSignEnabled !== 'false';
    const p = Number(cfg?.preauthCents);
    configPreauthCents.value = Number.isFinite(p) && p > 0 ? p : null;
  })
  .catch(() => {
    mockRechargeEnabled.value = false;
    alipayRechargeEnabled.value = false;
    wechatRechargeEnabled.value = false;
    wechatPayLive.value = false;
    payScoreSignEnabled.value = true;
  });

const entryChannel = computed(
  () => normalizeEntryChannel(props.entryChannel) || pickedChannel.value
);
const preauthCents = computed(() =>
  resolveClientPreauthCents({
    devicePreauthCents: props.devicePreauthCents,
    configPreauthCents: configPreauthCents.value
  })
);
const needYuan = computed(() => preauthYuanLabel(preauthCents.value));
/** 与后端开门预授权门槛对齐；免密未开通且可用余额不足时不可完成开门准备 */
const balanceYuan = computed(() => fmtMoney(availableCents(account.value)));
const frozenYuan = computed(() => fmtMoney(Math.max(0, account.value?.frozenCents || 0)));
const payReady = computed(() => isPayReady(account.value, entryChannel.value, preauthCents.value));
const payReadyHintText = computed(() =>
  payReadyHint(account.value, entryChannel.value, preauthCents.value)
);
const balanceInsufficient = computed(() => {
  if (!account.value || payReady.value) return false;
  return availableCents(account.value) < preauthCents.value;
});
const payDesc = computed(() => {
  const c = entryChannel.value;
  if (c === 'WECHAT') return '推荐开通微信支付分：关门后自动扣款，无需每次确认。';
  if (c === 'ALIPAY') return '推荐开通支付宝免密：关门后自动扣款，无需每次确认。';
  return '请开通对应渠道免密支付；可用余额满足预授权也可临时开门。';
});
const showWechatSign = computed(
  () => payScoreSignEnabled.value && (!entryChannel.value || entryChannel.value === 'WECHAT')
);
const showAlipaySign = computed(() => !entryChannel.value || entryChannel.value === 'ALIPAY');

function goRechargePage() {
  emit('cancel');
  uni.navigateTo({ url: '/pages/recharge/recharge' });
}

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
    const { mode } = await runAlipayRecharge(2000, key);
    if (mode === 'live') {
      uni.showToast({ title: '请在支付宝完成支付', icon: 'none' });
      return;
    }
    account.value = await consumerApi.account();
    uni.showToast({ title: '支付宝模拟充值成功', icon: 'success' });
  } catch (error) {
    err.value = error instanceof Error ? error.message : '充值失败';
  } finally {
    busy.value = false;
  }
}

async function onMockRecharge() {
  if (busy.value) return;
  const confirmed = await new Promise<boolean>((resolve) =>
    uni.showModal({
      title: '确认模拟充值',
      content: '将发放 ¥20.00 余额（仅开发联调，不会真实扣款）。',
      confirmText: '确认发放',
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    })
  );
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
  } finally {
    busy.value = false;
  }
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
  justify-content: center;
}
.drawer-panel {
  width: 100%;
  max-width: 520px;
  margin: 0 auto;
  background: #fff;
  border-radius: 30rpx 30rpx 0 0;
  padding: 18rpx 32rpx calc(34rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  box-shadow: 0 -18rpx 55rpx rgba(15, 23, 42, 0.2);
  max-height: 90vh;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.drawer-handle {
  width: 64rpx;
  height: 8rpx;
  background: #cbd5e1;
  border-radius: 4rpx;
  margin: 0 auto 24rpx;
}
.drawer-title {
  font-size: 36rpx;
  font-weight: 700;
  color: #1b3027;
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
.prep-step.done {
  color: #07c160;
}
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
  box-shadow: 0 0 0 6rpx #f4f7f5;
}
.prep-step.done .prep-dot {
  background: linear-gradient(135deg, #059669, #0d9488);
  color: #fff;
  box-shadow: 0 0 0 6rpx #d1fae5;
}
.prep-line {
  width: 80rpx;
  height: 4rpx;
  background: #e5e5e5;
  margin: 0 12rpx 20rpx;
}
.prep-line.done {
  background: #07c160;
}
.drawer-body {
  margin-top: 8rpx;
}
.field-label {
  font-size: 26rpx;
  color: #666;
  display: block;
  margin-bottom: 8rpx;
}
.input {
  background: #f8faf9;
  border: 1rpx solid #e3eae6;
  border-radius: 17rpx;
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
.balance-val {
  color: #191919;
  font-weight: 600;
}
.btn-primary {
  margin: 0;
  background: linear-gradient(135deg, #059669, #0d9488);
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
  font-weight: 600;
  line-height: 88rpx;
  height: 88rpx;
  box-shadow: 0 9rpx 24rpx rgba(5, 150, 105, 0.2);
}
.btn-primary::after {
  border: none;
}
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
.btn-alipay::after {
  border: none;
}
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
.btn-wechat::after {
  border: none;
}
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
.btn-ghost-fill::after {
  border: none;
}
.btn-hover {
  opacity: 0.85;
}
.hint {
  font-size: 24rpx;
  color: #b2b2b2;
  display: block;
  text-align: center;
  margin-top: 16rpx;
}
.fallback-block {
  margin-top: 28rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid #eef2f0;
}
.fallback-title {
  display: block;
  font-size: 26rpx;
  color: #64748b;
  margin-bottom: 8rpx;
  font-weight: 600;
}
.balance-sub {
  display: block;
  margin: 8rpx 0 12rpx;
  color: #64748b;
  font-size: 22rpx;
}
.balance-warning {
  display: block;
  padding: 20rpx;
  border-radius: 12rpx;
  background: #fff7e6;
  color: #ad6800;
  font-size: 25rpx;
  line-height: 1.5;
}
.channel-pick {
  margin-bottom: 16rpx;
}
.channel-chips {
  display: flex;
  gap: 16rpx;
  margin-top: 12rpx;
}
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
.balance-warning + .btn-primary {
  margin-top: 22rpx;
}
.support-link {
  padding: 22rpx 0 4rpx;
  text-align: center;
  color: #059669;
  font-size: 25rpx;
  font-weight: 500;
}
.support-link.muted {
  color: #64748b;
  font-weight: 400;
}
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
