<template>
  <view role="button" aria-label="关闭" class="drawer-mask" @click="onCancel">
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
        <app-button :loading="busy" :label="busy ? '提交中…' : '下一步'" @click="onVerify" />
      </view>

      <view v-else-if="!payReady" class="drawer-body">
        <text class="drawer-desc">{{ payDesc }}</text>
        <view v-if="!entryChannel" class="channel-pick">
          <text class="field-label">本次扫码渠道</text>
          <view class="channel-chips">
            <text role="button"
              class="channel-chip"
              :class="{ on: pickedChannel === 'WECHAT' }"
              @click="pickedChannel = 'WECHAT'"
              >微信</text
            >
            <text role="button"
              class="channel-chip"
              :class="{ on: pickedChannel === 'ALIPAY' }"
              @click="pickedChannel = 'ALIPAY'"
              >支付宝</text
            >
          </view>
        </view>
        <app-button
          v-if="showWechatSign"
          :loading="busy"
          :disabled="busy"
          :label="busy ? '开通中…' : '开通微信支付分'"
          @click="onSignPayScore"
        />
        <app-button
          v-if="showAlipaySign"
          variant="alipay"
          :loading="busy"
          :disabled="busy"
          :label="busy ? '开通中…' : '开通支付宝免密'"
          @click="onSignAlipay"
        />
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
          <app-button
            v-if="wechatPayLive || (devTools && wechatRechargeEnabled)"
            variant="wechat"
            :loading="busy"
            :disabled="busy"
            :label="busy ? '处理中…' : wechatPayLive ? '微信支付充值 ¥20' : '微信充值 ¥20'"
            @click="onWeChatRecharge"
          />
          <app-button
            v-if="devTools && mockRechargeEnabled"
            variant="soft"
            :loading="busy"
            :disabled="busy"
            :label="busy ? '发放中…' : '余额充值 ¥20'"
            @click="onMockRecharge"
          />
          <app-button
            v-if="devTools && alipayRechargeEnabled"
            variant="alipay"
            :loading="busy"
            :disabled="busy"
            :label="busy ? '处理中…' : '支付宝充值 ¥20'"
            @click="onAlipayRecharge"
          />
          <view role="button" class="support-link app-link-chevron" @click="goRechargePage">去充值页选择金额</view>
        </view>
        <view role="button" class="support-link muted" @click="contactOps">联系现场运营</view>
      </view>

      <text v-if="err" class="err">{{ err }}</text>
      <text role="button" aria-label="取消" class="cancel-link" @click="onCancel">稍后再说</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  showError,
  showSuccess,
  showConfirm
} from '@/utils/notify';
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
import { secureRandomToken } from '@/utils/secure-id';
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

/**
 * 禁止 script setup 顶层 await：否则 setup 返回 Promise，
 * 父页无 Suspense 时 H5 会告警并偶发整页主区空白（BUG-006）。
 */
onMounted(async () => {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
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
  } catch {
    mockRechargeEnabled.value = false;
    alipayRechargeEnabled.value = false;
    wechatRechargeEnabled.value = false;
    wechatPayLive.value = false;
    payScoreSignEnabled.value = true;
  }
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
    showSuccess('支付分已开通');
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
    showSuccess('支付宝免密已开通');
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
    const key = `prep-wechat-${Date.now()}-${secureRandomToken(6)}`;
    await runWeChatRecharge(2000, key);
    account.value = await consumerApi.account();
    showSuccess('充值成功');
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
    const key = `prep-alipay-${Date.now()}-${secureRandomToken(6)}`;
    const { mode } = await runAlipayRecharge(2000, key);
    if (mode === 'live') {
      showError('请在支付宝完成支付');
      return;
    }
    account.value = await consumerApi.account();
    showSuccess('充值成功');
  } catch (error) {
    err.value = error instanceof Error ? error.message : '充值失败';
  } finally {
    busy.value = false;
  }
}

async function onMockRecharge() {
  if (busy.value) return;
  const confirmed = await showConfirm({
    title: '确认充值',
    content: '将发放 ¥20.00 余额（体验到账，不会真实扣款）。',
    confirmText: '确认发放'
  });
  if (!confirmed) return;
  busy.value = true;
  err.value = '';
  try {
    const key = `prep-recharge-${Date.now()}-${secureRandomToken(6)}`;
    const order = await consumerApi.createMockRecharge(2000, key);
    await consumerApi.confirmMockRecharge(order.orderId);
    account.value = await consumerApi.account();
    showSuccess('余额已到账');
  } catch (error) {
    err.value = error instanceof Error ? error.message : '余额发放失败';
  } finally {
    busy.value = false;
  }
}

async function contactOps() {
  await showConfirm({
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
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card) 30rpx 0 0;
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
  background: var(--text-subtle, #cbd5e1);
  border-radius: 4rpx;
  margin: 0 auto 24rpx;
}
.drawer-title {
  font-size: var(--font-size-display-sm);
  font-weight: 700;
  color: var(--text-primary, #1b3027);
  display: block;
  text-align: center;
}
.drawer-sub {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #888);
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
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #888);
}
.prep-step.done {
  color: var(--brand-wx, #07c160);
}
.prep-dot {
  width: 48rpx;
  height: 48rpx;
  border-radius: 50%;
  background: #d4d4d4;
  color: var(--text-muted, #334155);
  font-size: var(--font-size-caption);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  box-shadow: 0 0 0 6rpx #f4f7f5;
}
.prep-step.done .prep-dot {
  background: linear-gradient(135deg, var(--brand), var(--brand));
  color: #fff;
  box-shadow: 0 0 0 6rpx var(--brand-soft, #d1fae5);
}
.prep-line {
  width: 80rpx;
  height: 4rpx;
  background: #e5e5e5;
  margin: 0 12rpx 20rpx;
}
.prep-line.done {
  background: var(--brand-wx, #07c160);
}
.drawer-body {
  margin-top: 8rpx;
}
.field-label {
  font-size: var(--font-size-body);
  color: var(--text-muted, #666);
  display: block;
  margin-bottom: 8rpx;
}
.input {
  background: var(--page-bg, #f8faf9);
  border: 1rpx solid #e3eae6;
  border-radius: 17rpx;
  padding: 22rpx 24rpx;
  margin-bottom: 20rpx;
  font-size: var(--font-size-lg);
}
.drawer-desc {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #888);
  line-height: 1.5;
  display: block;
  margin-bottom: 20rpx;
}
.balance-row {
  display: flex;
  justify-content: space-between;
  padding: 16rpx 0 24rpx;
  font-size: var(--font-size-md);
  color: var(--text-muted, #666);
}
.balance-val {
  color: var(--color-text-primary);
  font-weight: 600;
}
.drawer-body > .app-btn {
  margin: 16rpx 0 0;
  width: 100%;
}
.btn-hover {
  opacity: 0.85;
}
.hint {
  font-size: var(--font-size-caption);
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
  font-size: var(--font-size-body);
  color: var(--text-muted);
  margin-bottom: 8rpx;
  font-weight: 600;
}
.balance-sub {
  display: block;
  margin: 8rpx 0 12rpx;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.balance-warning {
  display: block;
  padding: 20rpx;
  border-radius: var(--radius-control);
  background: #fff7e6;
  color: var(--warning, #92400e);
  font-size: var(--font-size-body);
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
  border-radius: var(--radius-control);
  background: #f2f4f8;
  color: var(--color-link-secondary);
  font-size: var(--font-size-md);
  border: 2rpx solid transparent;
}
.channel-chip.on {
  background: var(--brand-soft);
  color: var(--brand);
  border-color: #34d399;
  font-weight: 600;
}
.drawer-body > .app-btn,
.drawer-body > .app-btn {
  margin: 16rpx 0 0;
  width: 100%;
}
.balance-warning + .app-btn,
.balance-warning + .app-btn {
  margin-top: 22rpx;
}
.support-link {
  padding: 22rpx 0 4rpx;
  text-align: center;
  color: var(--brand);
  font-size: var(--font-size-body);
  font-weight: 500;
}
.support-link.muted {
  color: var(--text-muted);
  font-weight: 400;
}
.err {
  color: var(--color-danger);
  font-size: var(--font-size-body);
  display: block;
  text-align: center;
  margin-top: 16rpx;
}
.cancel-link {
  display: block;
  text-align: center;
  color: var(--text-subtle, #888);
  font-size: var(--font-size-md);
  margin-top: 24rpx;
  padding: 12rpx 0;
}
</style>
