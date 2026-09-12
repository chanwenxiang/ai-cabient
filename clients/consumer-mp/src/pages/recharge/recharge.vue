<template>
  <view class="page-root">
    <app-nav-bar title="余额充值" />
    <view class="page-body">
      <view class="balance-card">
        <text class="bal-label">当前余额</text>
        <text class="bal-amount">{{ balanceYuan }}</text>
      </view>

      <view class="refund-entry">
        <text class="refund-title">申请退余额</text>
        <text class="refund-hint"
          >仅可退回仍对应微信/支付宝充值的可用余额；审核通过后原路退回，一般 1–7
          个工作日到账。</text
        >
        <view class="refund-row">
          <input
            class="refund-input"
            type="digit"
            :value="refundYuan"
            placeholder="退款金额（元）"
            maxlength="8"
            @input="onRefundYuan"
          />
          <app-button
            variant="danger"
            compact
            :block="false"
            :disabled="refundBusy || !refundAmountCents"
            :loading="refundBusy"
            :label="refundBusy ? '提交中…' : '提交申请'"
            @click="onApplyRefund"
          />
        </view>
        <text v-if="refundError" class="custom-error">{{ refundError }}</text>
        <view v-if="refundRequests.length" class="refund-list">
          <view v-for="r in refundRequests" :key="r.requestId" class="refund-item">
            <view>
              <text class="refund-amt">{{ fmtMoney(r.amountCents) }}</text>
              <text class="refund-meta"
                >{{ refundStatusLabel(r.status)
                }}{{ r.requestNo ? ` · ${shortBizNo(r.requestNo)}` : '' }}</text
              >
              <text v-if="r.reviewRemark || r.failReason" class="refund-remark">{{
                r.reviewRemark || r.failReason
              }}</text>
            </view>
            <view class="refund-right">
              <text class="refund-time">{{ formatRefundTime(r.createdAt) }}</text>
              <text v-if="r.refundedAt" class="refund-time done"
                >到账 {{ formatRefundTime(r.refundedAt) }}</text
              >
            </view>
          </view>
        </view>
      </view>

      <view class="amount-grid">
        <view
          v-for="item in amounts" role="button"
          :key="item.value"
          class="amount-card"
          :class="{ selected: selectedAmount === item.value }"
          @click="selectAmount(item.value)"
        >
          <text class="amount-value">{{ fmtMoney(item.value) }}</text>
        </view>
      </view>

      <view class="custom-row">
        <text class="custom-label">自定义金额（元）</text>
        <input
          class="custom-input"
          type="digit"
          :value="customAmountYuan"
          placeholder="如 33.5"
          maxlength="8"
          @input="onCustomAmount"
        />
        <text v-if="customAmountError" class="custom-error">{{ customAmountError }}</text>
      </view>

      <app-button
        v-if="wechatPayLive || wechatRechargeEnabled"
        variant="wechat"
        :disabled="!selectedAmount || loading"
        :loading="loading"
        :label="
          loading
            ? '处理中…'
            : selectedAmount
              ? `${wechatPayLive ? '微信支付' : '微信充值'} ${fmtMoney(selectedAmount)}`
              : '微信充值'
        "
        @click="onWeChatRecharge"
      />
      <app-button
        v-if="devTools && mockEnabled"
        :disabled="!selectedAmount || loading"
        :loading="loading"
        :label="
          loading
            ? '充值中…'
            : selectedAmount
              ? `确认充值 ${fmtMoney(selectedAmount)}`
              : '请选择金额'
        "
        @click="onRecharge"
      />
      <app-button
        v-if="devTools && alipayRechargeEnabled"
        variant="alipay"
        :disabled="!selectedAmount || loading"
        :loading="loading"
        :label="
          loading
            ? '处理中…'
            : selectedAmount
              ? `支付宝充值 ${fmtMoney(selectedAmount)}`
              : '支付宝充值'
        "
        @click="onAlipayRecharge"
      />

      <view
        v-if="!wechatPayLive && !wechatRechargeEnabled && !(devTools && mockEnabled)"
        class="channel-hint"
      >
        <text>暂未开通在线充值，请联系现场运营或开通微信支付分后免密开门。</text>
      </view>
      <view v-else-if="devTools" class="channel-hint">
        <text v-if="paymentModeHint">{{ paymentModeHint }}</text>
        <text v-else-if="wechatPayLive">已配置微信支付商户。</text>
        <text v-else-if="wechatRechargeEnabled">微信通道为体验到账。</text>
        <text v-if="mockEnabled"> 体验充值仅用于联调验证。</text>
        <text v-if="alipayRechargeEnabled"> 支付宝可跳转收银台或体验到账。</text>
      </view>
      <view v-else class="channel-hint">
        <text>余额可用于未开通免密时的开门兜底；推荐优先开通微信支付分。</text>
      </view>

      <app-button variant="ghost" label="返回我的" @click="goBack" />

      <view class="recharge-list">
        <view class="section-head">
          <text class="section-title">充值记录</text>
          <text v-if="pendingCount" role="button" class="cleanup" @click="cancelPendings"
            >清理 {{ pendingCount }} 笔待支付</text
          >
        </view>
        <view v-if="recordsLoading" class="empty">{{ UI_COPY.loading }}</view>
        <empty-state
          v-else-if="!visibleRecords.length"
          compact
          title="暂无充值记录"
          hint="充值成功后，到账明细会出现在这里"
        />
        <view v-for="r in visibleRecords" :key="r.orderId" class="record-row">
          <view>
            <text class="record-amount">{{ fmtMoney(r.amountCents ?? 0) }}</text>
            <view class="record-meta">
              <text class="record-channel">{{ channelText(r.channel) }}</text>
              <text class="record-id">{{ shortBizNo(r.orderId) }}</text>
              <text class="record-time">{{ formatTime(r.createdAt) }}</text>
              <text v-if="r.paidAt && r.status === 'PAID'" class="record-time"
                >到账 {{ formatTime(r.paidAt) }}</text
              >
            </view>
          </view>
          <view class="record-right">
            <text class="record-status" :class="r.status">{{ statusText(r.status) }}</text>
            <text v-if="r.status === 'PENDING'" role="button" aria-label="取消" class="cancel-link" @click="cancelOne(r.orderId)"
              >取消</text
            >
          </view>
        </view>
      </view>

      <view v-if="devTools" class="note">体验充值不会产生真实扣款。</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  showError,
  showSuccess,
  showConfirm
} from '@/utils/notify';
import { onShow } from '@dcloudio/uni-app';
import { consumerApi, ensureConsumerAuth, get } from '@/utils/consumer-api';
import { resumePendingRechargeIfAny, runAlipayRecharge, runWeChatRecharge } from '@/utils/recharge';
import { secureRandomToken } from '@/utils/secure-id';
import {
  shortBizNo,
  formatDateTimeMinute,
  fmtMoney,
  yuanToCents
} from '@aicabinet/shared-uni/format';
import { displayLabel } from '@aicabinet/shared-dict';
import type {
  PageResult,
  RechargeOrderDto,
  BalanceRefundRequestDto
} from '@aicabinet/shared-types';
import {
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
  resolveMockEnabled,
  resolveSandboxRecharge,
  resolveWechatRechargeVisible,
  showDevTools
} from '@/utils/runtime-flags';

const devTools = showDevTools();

const amounts = [
  { value: 1000, text: '10' },
  { value: 2000, text: '20' },
  { value: 5000, text: '50' },
  { value: 10000, text: '100' },
  { value: 20000, text: '200' }
];

const balanceYuan = ref('0.00');
const selectedAmount = ref(2000);
const customAmountYuan = ref('');
const customAmountError = ref('');
const loading = ref(false);
const recordsLoading = ref(false);
const cancelling = ref(false);
const records = ref<RechargeOrderDto[]>([]);
const alipayRechargeEnabled = ref(false);
const wechatRechargeEnabled = ref(false);
const wechatPayLive = ref(false);
const alipayPayLive = ref(false);
const paymentModeHint = ref('');
const mockEnabled = ref(false);
const refundYuan = ref('');
const refundAmountCents = ref(0);
const refundError = ref('');
const refundBusy = ref(false);
const refundRequests = ref<BalanceRefundRequestDto[]>([]);

const pendingCount = computed(() => records.value.filter((r) => r.status === 'PENDING').length);
const visibleRecords = computed(() =>
  records.value.filter((r) => r.status !== 'CANCELLED').slice(0, 20)
);

onShow(async () => {
  await ensureConsumerAuth();
  await loadConfig();
  await Promise.all([loadBalance(), loadRecords(), loadRefundRequests()]);
  const paid = await resumePendingRechargeIfAny();
  if (paid) {
    await Promise.all([loadBalance(), loadRecords()]);
  }
});

function refundStatusLabel(status?: string) {
  switch (String(status || '').toUpperCase()) {
    case 'PENDING_REVIEW':
      return '待审核';
    case 'REFUNDED':
      return '已退款';
    case 'REJECTED':
      return '已驳回';
    case 'FAILED':
      return '失败';
    default:
      return status ? '处理中' : '暂无';
  }
}

function formatRefundTime(v?: string) {
  return formatDateTimeMinute(v, '');
}

function onRefundYuan(e: unknown) {
  const raw = String(
    (e as { detail?: { value?: unknown }; target?: { value?: unknown } })?.detail?.value ??
      (e as { target?: { value?: unknown } })?.target?.value ??
      ''
  ).trim();
  refundYuan.value = raw;
  refundError.value = '';
  if (!raw) {
    refundAmountCents.value = 0;
    return;
  }
  const yuan = Number(raw);
  if (!Number.isFinite(yuan) || yuan <= 0) {
    refundAmountCents.value = 0;
    refundError.value = '请输入大于 0 的金额';
    return;
  }
  if (yuan > 5000) {
    refundAmountCents.value = 0;
    refundError.value = '单次申请不超过 ¥5000';
    return;
  }
  const cents = yuanToCents(raw);
  if (cents == null || cents <= 0) {
    refundAmountCents.value = 0;
    refundError.value = '请输入有效金额';
    return;
  }
  refundAmountCents.value = cents;
}

async function loadRefundRequests() {
  try {
    refundRequests.value = (await consumerApi.listBalanceRefunds()) || [];
  } catch {
    refundRequests.value = [];
  }
}

async function onApplyRefund() {
  if (!refundAmountCents.value || refundBusy.value) return;
  const confirmed = await showConfirm({
    title: '提交退余额申请',
    content: `申请退回 ${fmtMoney(refundAmountCents.value)}。审核通过后原路退回微信/支付宝充值，申请中金额将冻结。`,
    confirmText: '提交'
  });
  if (!confirmed) return;
  refundBusy.value = true;
  refundError.value = '';
  try {
    await consumerApi.applyBalanceRefund(refundAmountCents.value, '用户申请退可用余额');
    showSuccess('已提交审核');
    refundYuan.value = '';
    refundAmountCents.value = 0;
    await Promise.all([loadBalance(), loadRefundRequests()]);
  } catch (e) {
    refundError.value = e instanceof Error ? e.message : '提交失败';
    showError(refundError.value);
  } finally {
    refundBusy.value = false;
  }
}

function goBack() {
  const pages = getCurrentPages();
  if (pages.length > 1) {
    uni.navigateBack({
      fail: () => uni.switchTab({ url: '/pages/mine/mine' })
    });
    return;
  }
  uni.switchTab({ url: '/pages/mine/mine' });
}

function selectAmount(value: number) {
  customAmountYuan.value = '';
  customAmountError.value = '';
  selectedAmount.value = value;
}

function onCustomAmount(e: unknown) {
  const raw = String(
    (e as { detail?: { value?: unknown }; target?: { value?: unknown } })?.detail?.value ??
      (e as { target?: { value?: unknown } })?.target?.value ??
      ''
  ).trim();
  customAmountYuan.value = raw;
  customAmountError.value = '';
  if (!raw) {
    selectedAmount.value = 0;
    return;
  }
  const yuan = Number(raw);
  if (!Number.isFinite(yuan) || yuan <= 0) {
    selectedAmount.value = 0;
    customAmountError.value = '请输入大于 0 的金额';
    return;
  }
  if (yuan > 5000) {
    selectedAmount.value = 0;
    customAmountError.value = '单次充值不超过 ¥5000';
    return;
  }
  const cents = yuanToCents(raw);
  if (cents == null || cents <= 0) {
    selectedAmount.value = 0;
    customAmountError.value = '请输入有效金额';
    return;
  }
  selectedAmount.value = cents;
}

async function loadConfig() {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    mockEnabled.value = resolveMockEnabled(cfg?.mockEnabled);
    alipayRechargeEnabled.value = resolveSandboxRecharge(cfg?.alipayRechargeEnabled);
    wechatPayLive.value = cfg?.wechatPayLive === 'true';
    alipayPayLive.value = cfg?.alipayPayLive === 'true';
    paymentModeHint.value = cfg?.paymentModeHint || '';
    wechatRechargeEnabled.value = resolveWechatRechargeVisible({
      wechatRechargeEnabled: cfg?.wechatRechargeEnabled,
      wechatPayLive: cfg?.wechatPayLive
    });
  } catch {
    mockEnabled.value = false;
    alipayRechargeEnabled.value = false;
    wechatRechargeEnabled.value = false;
    wechatPayLive.value = false;
    alipayPayLive.value = false;
    paymentModeHint.value = '';
  }
}

async function loadBalance() {
  try {
    const acc = await consumerApi.account();
    balanceYuan.value = fmtMoney(acc.balanceCents ?? 0);
  } catch {
    balanceYuan.value = '--';
  }
}

async function loadRecords() {
  recordsLoading.value = true;
  try {
    const res = await get<PageResult<RechargeOrderDto> | RechargeOrderDto[]>(
      '/api/v2/payment/recharges'
    );
    const data = res.data;
    records.value = Array.isArray(data) ? data : (data?.items ?? []);
  } catch {
    records.value = [];
  } finally {
    recordsLoading.value = false;
  }
}

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '');
}

function statusText(s: string) {
  return displayLabel('recharge_status', s, '未知状态');
}

function channelText(channel?: string) {
  return displayLabel('pay_channel', channel, '未知渠道');
}

async function cancelOne(orderId: string) {
  if (cancelling.value) return;
  const confirmed = await showConfirm({
    title: '取消充值',
    content: '确定取消这笔待支付充值单吗？',
    confirmText: '取消订单',
    cancelText: '保留'
  });
  if (!confirmed) return;
  cancelling.value = true;
  try {
    await consumerApi.cancelRecharge(orderId);
    showError('已取消');
    await loadRecords();
  } catch (e) {
    showError(e instanceof Error ? e.message : '取消失败');
  } finally {
    cancelling.value = false;
  }
}

async function cancelPendings() {
  if (cancelling.value || !pendingCount.value) return;
  const confirmed = await showConfirm({
    title: '清理待支付',
    content: `将取消 ${pendingCount.value} 笔未完成的充值单`
  });
  if (!confirmed) return;
  cancelling.value = true;
  try {
    const pendings = records.value.filter((r) => r.status === 'PENDING');
    for (const r of pendings) {
      try {
        await consumerApi.cancelRecharge(r.orderId);
      } catch {
        /* 单笔失败继续 */
      }
    }
    showSuccess('已清理');
    await loadRecords();
  } finally {
    cancelling.value = false;
  }
}

async function onRecharge() {
  if (!selectedAmount.value || loading.value) return;
  if (!mockEnabled.value) {
    showError('模拟充值未开启');
    return;
  }
  loading.value = true;
  try {
    const key = `recharge-${Date.now()}-${secureRandomToken(6)}`;
    const prepay = await consumerApi.createMockRecharge(selectedAmount.value, key);
    await consumerApi.confirmMockRecharge(prepay.orderId);
    showSuccess('充值成功');
    await loadBalance();
    await loadRecords();
  } catch (e) {
    showError(e instanceof Error ? e.message : '充值失败');
  } finally {
    loading.value = false;
  }
}

async function onWeChatRecharge() {
  if (!selectedAmount.value || loading.value) return;
  loading.value = true;
  try {
    const key = `wechat-recharge-${Date.now()}-${secureRandomToken(6)}`;
    const { mode } = await runWeChatRecharge(selectedAmount.value, key);
    showSuccess(mode === 'live' ? '充值已到账' : '充值成功');
    await loadBalance();
    await loadRecords();
  } catch (e) {
    showError(e instanceof Error ? e.message : '微信充值失败');
  } finally {
    loading.value = false;
  }
}

async function onAlipayRecharge() {
  if (!selectedAmount.value || loading.value) return;
  loading.value = true;
  try {
    const key = `alipay-recharge-${Date.now()}-${secureRandomToken(6)}`;
    const { mode } = await runAlipayRecharge(selectedAmount.value, key);
    if (mode === 'live') {
      showError('请在支付宝完成支付');
      return;
    }
    showSuccess('支付宝模拟充值成功');
    await loadBalance();
    await loadRecords();
  } catch (e) {
    showError(e instanceof Error ? e.message : '支付宝下单失败');
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.page-root {
  padding: 0;
  background: var(--card-bg, #ffffff);
  min-height: 100%;
  box-sizing: border-box;
}
.page-body {
  padding: 20rpx 20rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.balance-card {
  background: linear-gradient(135deg, var(--brand-soft), var(--white));
  border: 1rpx solid var(--brand-soft, #d1fae5);
  border-radius: var(--radius-card);
  padding: 40rpx;
  text-align: center;
  margin-bottom: 30rpx;
}
.bal-label {
  color: var(--text-muted);
  font-size: var(--font-size-md);
}
.bal-amount {
  color: var(--brand);
  font-size: 72rpx;
  font-weight: 700;
  margin-top: 10rpx;
  display: block;
}
.refund-entry {
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--color-border-subtle, #edf1ef);
  border-radius: var(--radius-panel);
  padding: 24rpx;
  margin-bottom: 28rpx;
}
.refund-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--text-primary, #223029);
}
.refund-hint {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
  line-height: 1.5;
}
.refund-row {
  display: flex;
  align-items: center;
  margin-top: 18rpx;
  gap: 12rpx;
}
.refund-input {
  flex: 1;
  min-height: 72rpx;
  height: 72rpx;
  padding: 0 20rpx;
  background: var(--page-bg, #f8faf9);
  border: 1rpx solid var(--color-border-subtle);
  border-radius: var(--radius-control);
  font-size: var(--font-size-md);
  box-sizing: border-box;
}
.btn-refund {
  margin: 0;
  min-width: 180rpx;
  min-height: 72rpx;
  height: 72rpx;
  padding: 0 24rpx;
  background: var(--card-bg, #fff);
  color: var(--brand);
  border: 2rpx solid var(--brand);
  border-radius: var(--radius-card);
  font-size: var(--font-size-body);
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 1.2;
  box-sizing: border-box;
}
.btn-refund::after {
  border: none;
}
.btn-refund[disabled] {
  opacity: 0.45;
}
.refund-list {
  margin-top: 18rpx;
  border-top: 1rpx solid var(--color-border-subtle);
  padding-top: 12rpx;
}
.refund-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 12rpx 0;
  gap: 12rpx;
}
.refund-amt {
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--text-primary, #223029);
  margin-right: 12rpx;
}
.refund-meta {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--brand);
}
.refund-remark {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-xs);
  color: var(--warning, #b45309);
  max-width: 420rpx;
}
.refund-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4rpx;
  flex-shrink: 0;
}
.refund-time {
  font-size: var(--font-size-xs);
  color: var(--text-subtle);
}
.refund-time.done {
  color: var(--brand);
}
.amount-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20rpx;
  margin-bottom: 30rpx;
}
.amount-card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 30rpx 20rpx;
  text-align: center;
  border: 2rpx solid var(--card-border);
}
.amount-card.selected {
  border-color: var(--brand);
  background: var(--brand-soft);
}
.amount-value {
  font-size: var(--font-size-h2);
  font-weight: 700;
  color: var(--text-primary);
}
.amount-bonus {
  font-size: var(--font-size-sm);
  color: var(--accent-orange);
  margin-top: 8rpx;
  display: block;
}
.custom-row {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  padding: 20rpx 24rpx;
  margin-bottom: 20rpx;
  display: flex;
  align-items: center;
  gap: 16rpx;
  flex-wrap: wrap;
}
.custom-label {
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  flex-shrink: 0;
}
.custom-input {
  flex: 1;
  min-width: 200rpx;
  background: var(--page-bg, #f8faf9);
  border: 1rpx solid var(--color-border-subtle);
  border-radius: var(--radius-control);
  padding: 16rpx 20rpx;
  font-size: var(--font-size-md);
}
.custom-error {
  width: 100%;
  font-size: var(--font-size-sm);
  color: var(--color-danger);
}
.app-btn[disabled] {
  opacity: 0.5;
}
.btn-wechat {
  width: 100%;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: linear-gradient(135deg, var(--brand), var(--brand));
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
  font-weight: 600;
  border: none;
  margin-bottom: 16rpx;
  box-shadow: 0 8rpx 24rpx rgba(5, 150, 105, 0.22);
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.btn-wechat[disabled] {
  opacity: 0.5;
}
.btn-alipay {
  width: 100%;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: var(--info);
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
  border: none;
  margin-bottom: 16rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.btn-alipay[disabled] {
  opacity: 0.5;
}
.btn-alipay::after,
.app-btn::after,
.btn-back::after,
.btn-wechat::after {
  border: none;
}
.btn-back {
  width: 100%;
  min-height: 80rpx;
  height: 80rpx;
  line-height: 1.2;
  background: var(--card-bg, #fff);
  color: var(--color-link-secondary);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-md);
  border: 1rpx solid var(--card-border);
  margin-bottom: 24rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.btn-hover {
  opacity: 0.85;
}
.channel-hint {
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #999);
  text-align: center;
  margin-bottom: 24rpx;
  line-height: 1.5;
}
.recharge-list {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 24rpx;
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16rpx;
}
.section-title {
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary);
}
.cleanup {
  font-size: var(--font-size-caption);
  color: var(--color-link-secondary);
}
.empty {
  text-align: center;
  color: var(--text-subtle, #999);
  padding: 40rpx;
  font-size: var(--font-size-body);
}
.record-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle);
}
.record-row > view:first-child {
  flex: 1;
  min-width: 0;
}
.record-amount {
  font-size: var(--font-size-lg);
  font-weight: 600;
  display: block;
}
.record-meta {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 6rpx;
  flex-wrap: wrap;
}
.record-channel {
  font-size: var(--font-size-sm);
  color: var(--color-link-secondary);
  background: var(--surface-muted);
  padding: 2rpx 10rpx;
  border-radius: var(--radius-tag);
}
.record-time {
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #999);
}
.record-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8rpx;
  flex-shrink: 0;
  margin-left: 16rpx;
}
.record-status {
  font-size: var(--font-size-caption);
  padding: 4rpx 12rpx;
  border-radius: var(--radius-tag);
  white-space: nowrap;
  line-height: 1.2;
}
.record-status.PAID,
.record-status.SUCCESS {
  color: var(--brand);
  background: #f0fff4;
}
.record-status.PENDING {
  color: var(--accent-orange, #c2410c);
  background: #fff8e8;
}
.record-status.FAILED,
.record-status.REFUNDED,
.record-status.CANCELLED {
  color: var(--danger, #991b1b);
  background: #fff0ee;
}
.cancel-link {
  font-size: var(--font-size-sm);
  color: var(--color-link-secondary);
}
.note {
  text-align: center;
  font-size: var(--font-size-caption);
  color: var(--text-subtle, #999);
  margin-top: 24rpx;
  padding-bottom: 40rpx;
}
</style>
