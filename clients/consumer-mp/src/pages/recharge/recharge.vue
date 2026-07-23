<template>
  <view class="page-root">
    <view class="balance-card">
      <text class="bal-label">当前余额</text>
      <text class="bal-amount">¥{{ balanceYuan }}</text>
    </view>

    <view class="amount-grid">
      <view
        v-for="item in amounts"
        :key="item.value"
        class="amount-card"
        :class="{ selected: selectedAmount === item.value }"
        @click="selectedAmount = item.value"
      >
        <text class="amount-value">¥{{ item.text }}</text>
      </view>
    </view>

    <button
      v-if="wechatPayLive || wechatRechargeEnabled"
      class="btn-wechat"
      :disabled="!selectedAmount || loading"
      :loading="loading"
      @click="onWeChatRecharge"
    >
      {{
        loading
          ? '处理中…'
          : selectedAmount
            ? `${wechatPayLive ? '微信支付' : '微信充值'} ¥${(selectedAmount / 100).toFixed(0)}`
            : '微信充值'
      }}
    </button>
    <button
      v-if="devTools && mockEnabled"
      class="btn-primary"
      :disabled="!selectedAmount || loading"
      :loading="loading"
      @click="onRecharge"
    >
      {{ loading ? '充值中…' : selectedAmount ? `模拟到账 ¥${(selectedAmount / 100).toFixed(0)}` : '请选择金额' }}
    </button>
    <button
      v-if="devTools && alipayRechargeEnabled"
      class="btn-alipay"
      :disabled="!selectedAmount || loading"
      :loading="loading"
      @click="onAlipayRecharge"
    >
      {{
        loading
          ? '处理中…'
          : selectedAmount
            ? `${alipayPayLive ? '支付宝沙箱' : '支付宝模拟充值'} ¥${(selectedAmount / 100).toFixed(0)}`
            : alipayPayLive
              ? '支付宝沙箱'
              : '支付宝模拟充值'
      }}
    </button>

    <view v-if="!wechatPayLive && !wechatRechargeEnabled && !(devTools && mockEnabled)" class="channel-hint">
      <text>暂未开通在线充值，请联系现场运营或开通微信支付分后免密开门。</text>
    </view>
    <view v-else-if="devTools" class="channel-hint">
      <text v-if="paymentModeHint">{{ paymentModeHint }}</text>
      <text v-else-if="wechatPayLive">已配置真实微信商户。</text>
      <text v-else-if="wechatRechargeEnabled">开发：微信通道为 mock 即时到账。</text>
      <text v-if="mockEnabled"> 模拟到账仅本地联调。</text>
      <text v-if="alipayRechargeEnabled && alipayPayLive"> 支付宝沙箱可跳转收银台。</text>
      <text v-else-if="alipayRechargeEnabled"> 支付宝 mock 与微信一致，一键到账（无需进件）。</text>
    </view>
    <view v-else class="channel-hint">
      <text>余额可用于未开通免密时的开门兜底；推荐优先开通微信支付分。</text>
    </view>

    <button class="btn-back" hover-class="btn-hover" @click="goBack">返回我的</button>

    <view class="recharge-list">
      <view class="section-head">
        <text class="section-title">充值记录</text>
        <text v-if="pendingCount" class="cleanup" @click="cancelPendings">清理 {{ pendingCount }} 笔待支付</text>
      </view>
      <view v-if="recordsLoading" class="empty">加载中…</view>
      <empty-state
        v-else-if="!visibleRecords.length"
        compact
        title="暂无充值记录"
        hint="充值成功后，到账明细会出现在这里"
      />
      <view v-for="r in visibleRecords" :key="r.orderId" class="record-row">
        <view>
          <text class="record-amount">¥{{ ((r.amountCents || 0) / 100).toFixed(2) }}</text>
          <view class="record-meta">
            <text class="record-channel">{{ channelText(r.channel) }}</text>
            <text class="record-time">{{ formatTime(r.createdAt) }}</text>
          </view>
        </view>
        <view class="record-right">
          <text class="record-status" :class="r.status">{{ statusText(r.status) }}</text>
          <text v-if="r.status === 'PENDING'" class="cancel-link" @click="cancelOne(r.orderId)">取消</text>
        </view>
      </view>
    </view>

    <view v-if="devTools" class="note">开发提示：模拟到账 / 沙箱不会产生生产扣款。</view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { consumerApi, ensureConsumerAuth, get } from '@/utils/consumer-api';
import { resumePendingRechargeIfAny, runAlipayRecharge, runWeChatRecharge } from '@/utils/recharge';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import { dictLabel } from '@aicabinet/shared-dict';
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
const loading = ref(false);
const recordsLoading = ref(false);
const cancelling = ref(false);
const records = ref<any[]>([]);
const alipayRechargeEnabled = ref(false);
const wechatRechargeEnabled = ref(false);
const wechatPayLive = ref(false);
const alipayPayLive = ref(false);
const paymentModeHint = ref('');
const mockEnabled = ref(false);

const pendingCount = computed(() => records.value.filter((r) => r.status === 'PENDING').length);
const visibleRecords = computed(() =>
  records.value.filter((r) => r.status !== 'CANCELLED').slice(0, 20)
);

onShow(async () => {
  await ensureConsumerAuth();
  loadConfig();
  // 先展示余额/记录，避免 pending 轮询期间长时间停在 ¥0.00
  await Promise.all([loadBalance(), loadRecords()]);
  const paid = await resumePendingRechargeIfAny();
  if (paid) {
    await Promise.all([loadBalance(), loadRecords()]);
  }
});

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
    mockEnabled.value = resolveMockEnabled();
    alipayRechargeEnabled.value = false;
    wechatRechargeEnabled.value = showDevTools();
    wechatPayLive.value = false;
    alipayPayLive.value = false;
    paymentModeHint.value = '';
  }
}

async function loadBalance() {
  try {
    const acc = await consumerApi.account();
    balanceYuan.value = ((acc.balanceCents || 0) / 100).toFixed(2);
  } catch {
    balanceYuan.value = '-';
  }
}

async function loadRecords() {
  recordsLoading.value = true;
  try {
    const res = await get<{ items?: any[] } | any[]>('/api/v2/payment/recharges');
    const data = res.data;
    records.value = Array.isArray(data) ? data : data?.items ?? [];
  } catch {
    records.value = [];
  } finally {
    recordsLoading.value = false;
  }
}

function formatTime(t: string) {
  return formatDateTimeMinute(t, '');
}

function statusText(s: string) {
  const map: Record<string, string> = {
    PENDING: '待支付',
    PAID: '已完成',
    SUCCESS: '已完成',
    REFUNDED: '已退款',
    FAILED: '失败',
    CANCELLED: '已取消'
  };
  return map[s] || s;
}

function channelText(channel?: string) {
  return dictLabel('pay_channel', channel || '') || channel || '未知渠道';
}

async function cancelOne(orderId: string) {
  if (cancelling.value) return;
  cancelling.value = true;
  try {
    await consumerApi.cancelRecharge(orderId);
    uni.showToast({ title: '已取消', icon: 'none' });
    await loadRecords();
  } catch (e: any) {
    uni.showToast({ title: e?.message || '取消失败', icon: 'none' });
  } finally {
    cancelling.value = false;
  }
}

async function cancelPendings() {
  if (cancelling.value || !pendingCount.value) return;
  const confirmed = await new Promise<boolean>((resolve) =>
    uni.showModal({
      title: '清理待支付',
      content: `将取消 ${pendingCount.value} 笔未完成的充值单`,
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  );
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
    uni.showToast({ title: '已清理', icon: 'success' });
    await loadRecords();
  } finally {
    cancelling.value = false;
  }
}

async function onRecharge() {
  if (!selectedAmount.value || loading.value) return;
  if (!mockEnabled.value) {
    uni.showToast({ title: '模拟充值未开启', icon: 'none' });
    return;
  }
  loading.value = true;
  try {
    const key = `recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const prepay = await consumerApi.createMockRecharge(selectedAmount.value, key);
    await consumerApi.confirmMockRecharge(prepay.orderId);
    uni.showToast({ title: '充值成功', icon: 'success' });
    await loadBalance();
    await loadRecords();
  } catch (e: any) {
    uni.showToast({ title: e?.message || '充值失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

async function onWeChatRecharge() {
  if (!selectedAmount.value || loading.value) return;
  loading.value = true;
  try {
    const key = `wechat-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const { mode } = await runWeChatRecharge(selectedAmount.value, key);
    uni.showToast({
      title: mode === 'live' ? '充值已到账' : '微信模拟充值成功',
      icon: 'success'
    });
    await loadBalance();
    await loadRecords();
  } catch (e: any) {
    uni.showToast({ title: e?.message || '微信充值失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

async function onAlipayRecharge() {
  if (!selectedAmount.value || loading.value) return;
  loading.value = true;
  try {
    const key = `alipay-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const { mode } = await runAlipayRecharge(selectedAmount.value, key);
    if (mode === 'live') {
      uni.showToast({ title: '请在支付宝完成支付', icon: 'none' });
      return;
    }
    uni.showToast({ title: '支付宝模拟充值成功', icon: 'success' });
    await loadBalance();
    await loadRecords();
  } catch (e: any) {
    uni.showToast({ title: e?.message || '支付宝下单失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f7f7f7; min-height: 100vh; box-sizing: border-box; }
.balance-card { background: linear-gradient(135deg, #07c160, #06ad56); border-radius: 20rpx; padding: 40rpx; text-align: center; margin-bottom: 30rpx; }
.bal-label { color: rgba(255,255,255,.8); font-size: 28rpx; }
.bal-amount { color: #fff; font-size: 72rpx; font-weight: 700; margin-top: 10rpx; display: block; }
.amount-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20rpx; margin-bottom: 30rpx; }
.amount-card { background: #fff; border-radius: 16rpx; padding: 30rpx 20rpx; text-align: center; border: 2rpx solid #eee; }
.amount-card.selected { border-color: #07c160; background: #f0fff4; }
.amount-value { font-size: 40rpx; font-weight: 700; color: #333; }
.amount-bonus { font-size: 22rpx; color: #ff6b35; margin-top: 8rpx; display: block; }
.btn-primary { width: 100%; height: 88rpx; line-height: 88rpx; background: #07c160; color: #fff; border-radius: 44rpx; font-size: 30rpx; border: none; margin-bottom: 16rpx; }
.btn-primary[disabled] { opacity: 0.5; }
.btn-wechat {
  width: 100%;
  height: 88rpx;
  line-height: 88rpx;
  background: #07c160;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
  border: none;
  margin-bottom: 16rpx;
}
.btn-wechat[disabled] { opacity: 0.5; }
.btn-alipay {
  width: 100%;
  height: 88rpx;
  line-height: 88rpx;
  background: #1677ff;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
  border: none;
  margin-bottom: 16rpx;
}
.btn-alipay[disabled] { opacity: 0.5; }
.btn-alipay::after, .btn-primary::after, .btn-back::after, .btn-wechat::after { border: none; }
.btn-back {
  width: 100%;
  height: 80rpx;
  line-height: 80rpx;
  background: #fff;
  color: #576b95;
  border-radius: 40rpx;
  font-size: 28rpx;
  border: 1rpx solid #e5e5e5;
  margin-bottom: 24rpx;
}
.btn-hover { opacity: 0.85; }
.channel-hint { font-size: 22rpx; color: #999; text-align: center; margin-bottom: 24rpx; line-height: 1.5; }
.recharge-list { background: #fff; border-radius: 16rpx; padding: 24rpx; }
.section-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16rpx; }
.section-title { font-size: 28rpx; font-weight: 600; color: #333; }
.cleanup { font-size: 24rpx; color: #576b95; }
.empty { text-align: center; color: #999; padding: 40rpx; font-size: 26rpx; }
.record-row { display: flex; justify-content: space-between; align-items: center; padding: 20rpx 0; border-bottom: 1rpx solid #f0f0f0; }
.record-amount { font-size: 30rpx; font-weight: 600; display: block; }
.record-meta { display: flex; align-items: center; gap: 12rpx; margin-top: 6rpx; flex-wrap: wrap; }
.record-channel {
  font-size: 22rpx;
  color: #576b95;
  background: #f2f4f8;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
}
.record-time { font-size: 22rpx; color: #999; }
.record-right { display: flex; flex-direction: column; align-items: flex-end; gap: 8rpx; }
.record-status { font-size: 24rpx; padding: 4rpx 12rpx; border-radius: 8rpx; }
.record-status.PAID, .record-status.SUCCESS { color: #07c160; background: #f0fff4; }
.record-status.PENDING { color: #ff9500; background: #fff8e8; }
.record-status.FAILED, .record-status.REFUNDED, .record-status.CANCELLED { color: #ff3b30; background: #fff0ee; }
.cancel-link { font-size: 22rpx; color: #576b95; }
.note { text-align: center; font-size: 24rpx; color: #999; margin-top: 24rpx; padding-bottom: 40rpx; }
</style>
