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
                }}{{ r.requestNo ? ` · ${displayBizNo(r.requestNo)}` : '' }}</text
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
          v-for="item in amounts"
          role="button"
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

      <!--
        🔴 相邻按钮必须各自包一层块级 view：小程序自定义组件默认 inline 级，
        而 .app-btn--block 宽度 100% ⇒ 两个 app-button 会各自独占一行且**零间距贴死**
        （真机实测 top/bottom 相接）。间距不能靠组件自身 margin —— 组件的内部节点
        选不中，兄弟选择器也跨不过组件边界。
      -->
      <view v-if="wechatPayLive || wechatRechargeEnabled" class="btn-slot">
        <app-button
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
      </view>
      <view v-if="devTools && mockEnabled" class="btn-slot">
        <app-button
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
      </view>
      <view v-if="devTools && alipayRechargeEnabled" class="btn-slot">
        <app-button
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
      </view>

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
              <text class="record-id">{{ displayBizNo(r.orderId) }}</text>
              <text class="record-time">{{ formatTime(r.createdAt) }}</text>
              <text v-if="r.paidAt && r.status === 'PAID'" class="record-time"
                >到账 {{ formatTime(r.paidAt) }}</text
              >
            </view>
          </view>
          <view class="record-right">
            <text class="record-status" :class="r.status">{{ statusText(r.status) }}</text>
            <text
              v-if="r.status === 'PENDING'"
              role="button"
              aria-label="取消"
              class="cancel-link"
              @click="cancelOne(r.orderId)"
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
import { showError, showSuccess, showConfirm } from '@/utils/notify';
import { onShow } from '@dcloudio/uni-app';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';
import { resumePendingRechargeIfAny, runAlipayRecharge, runWeChatRecharge } from '@/utils/recharge';
import { secureRandomToken } from '@/utils/secure-id';
import {
  displayBizNo,
  formatDateTimeMinute,
  fmtMoney,
  yuanToCents
} from '@aicabinet/shared-uni/format';
import { displayLabel } from '@aicabinet/shared-dict';
import type { RechargeOrderDto, BalanceRefundRequestDto } from '@aicabinet/shared-types';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import { buildBalanceRefundBody } from '@/utils/money-ui-contracts';
import {
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
// 上限由后端下发（recharge.max_cents / balance.refund.max_cents，0=不限制）。
// 默认值与后端 seed（500_000 分）一致：配置取不到时行为与硬编码时代相同（fail-closed）。
const rechargeMaxCents = ref(500_000);
const refundMaxCents = ref(500_000);

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
  if (
    refundMaxCents.value !== Number.POSITIVE_INFINITY &&
    Math.round(yuan * 100) > refundMaxCents.value
  ) {
    refundAmountCents.value = 0;
    refundError.value = `单次申请不超过 ¥${refundMaxCents.value / 100}`;
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
    const body = buildBalanceRefundBody({ amountCents: refundAmountCents.value });
    await consumerApi.applyBalanceRefund(body.amountCents, body.reason);
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
  if (
    rechargeMaxCents.value !== Number.POSITIVE_INFINITY &&
    Math.round(yuan * 100) > rechargeMaxCents.value
  ) {
    selectedAmount.value = 0;
    customAmountError.value = `单次充值不超过 ¥${rechargeMaxCents.value / 100}`;
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

/** 解析后端下发的上限（分）。0=不限制 → Infinity；非法/负数 → 回退默认。 */
function resolveLimitCents(raw: string | undefined, fallback: number): number {
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 0) return fallback;
  return n === 0 ? Number.POSITIVE_INFINITY : n;
}

async function loadConfig() {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    mockEnabled.value = resolveMockEnabled(cfg?.mockEnabled);
    rechargeMaxCents.value = resolveLimitCents(cfg?.rechargeMaxCents, 500_000);
    refundMaxCents.value = resolveLimitCents(cfg?.balanceRefundMaxCents, 500_000);
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
    rechargeMaxCents.value = 500_000;
    refundMaxCents.value = 500_000;
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
    const data = await consumerApi.listRecharges(0, 20);
    records.value = data?.items ?? [];
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
    showSuccess('已取消');
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

<style scoped src="./recharge.page.css"></style>
