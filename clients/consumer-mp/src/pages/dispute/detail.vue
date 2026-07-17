<template>
  <view class="page-root">
    <view v-if="loading" class="state"><text class="meta">加载中…</text></view>
    <view v-else-if="error" class="state">
      <text class="err">{{ error }}</text>
      <button class="btn-primary" @click="reload">重试</button>
    </view>
    <view v-else-if="ticket">
      <view class="hero" :class="'tone-' + copy.tone">
        <text class="hero-icon">{{ copy.icon }}</text>
        <text class="hero-title">{{ copy.title }}</text>
        <text class="hero-status">{{ statusText }}</text>
      </view>

      <view class="card">
        <text class="section-title">审核说明</text>
        <text class="reason">{{ copy.detail }}</text>
        <view v-if="ticket.deviceId" class="info-row">
          <text class="info-label">柜机</text>
          <text class="info-value mono">{{ ticket.deviceId }}</text>
        </view>
        <view class="info-row">
          <text class="info-label">会话</text>
          <text class="info-value mono">{{ ticket.sessionId }}</text>
        </view>
        <view v-if="ticket.createdAt" class="info-row">
          <text class="info-label">提交时间</text>
          <text class="info-value">{{ formatTime(ticket.createdAt) }}</text>
        </view>
        <view v-if="ticket.resolvedAt" class="info-row">
          <text class="info-label">处理时间</text>
          <text class="info-value">{{ formatTime(ticket.resolvedAt) }}</text>
        </view>
      </view>

      <view v-if="suggestedLines.length" class="card">
        <text class="section-title">识别参考明细</text>
        <text class="section-sub">以下为系统识别结果，最终以人工审核为准</text>
        <view v-for="(line, i) in suggestedLines" :key="'s-' + i" class="line">
          <text class="line-name">{{ line.skuName || line.skuId }} × {{ line.quantity }}</text>
          <text class="line-amt">{{ fmtLine(line) }}</text>
        </view>
      </view>

      <view v-if="isResolved" class="card">
        <text class="section-title">审核结果</text>
        <view v-if="resolutionLines.length">
          <view v-for="(line, i) in resolutionLines" :key="'r-' + i" class="line">
            <text class="line-name">{{ line.skuName || line.skuId }} × {{ line.quantity }}</text>
            <text class="line-amt">{{ fmtLine(line) }}</text>
          </view>
        </view>
        <view v-else class="empty-lines">本次未计费商品</view>
        <view class="bill-row">
          <text class="bill-label">最终扣款</text>
          <text class="bill-amount">{{ fmtMoney(ticket.billedAmountCents ?? 0) }}</text>
        </view>
      </view>

      <view class="actions">
        <button v-if="ticket.orderId" class="btn-primary" @click="goOrder">查看账单订单</button>
        <button class="btn-ghost" @click="goOrders">返回订单列表</button>
        <button class="btn-ghost subtle" @click="contactOps">联系运营</button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad, onShow } from '@dcloudio/uni-app';
import { consumerApi } from '@/utils/consumer-api';
import { consumerDisputeReviewCopy } from '@/utils/dispute-copy';
import { fmtMoney, formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import type { DisputeTicketDto, OrderLineDto } from '@aicabinet/shared-types';

const loading = ref(true);
const error = ref('');
const ticket = ref<DisputeTicketDto | null>(null);
const sessionId = ref('');
const ticketId = ref('');
const servicePhone = ref('400-888-0018');

const copy = computed(() => consumerDisputeReviewCopy(ticket.value));
const isResolved = computed(() => ticket.value?.status === 'RESOLVED');
const suggestedLines = computed<OrderLineDto[]>(() => ticket.value?.suggestedItems || []);
const resolutionLines = computed<OrderLineDto[]>(() => ticket.value?.resolutionItems || []);
const statusText = computed(() => {
  const s = ticket.value?.status || '';
  if (s === 'OPEN') return '审核中 · 暂未扣款';
  if (s === 'RESOLVED') return '已处理完成';
  if (s === 'CLOSED') return '已关闭';
  return s || '处理中';
});

onLoad((opts) => {
  applyQuery(opts as Record<string, string>);
});

onShow(() => {
  // 同页不同 query 跳转时 onLoad 不一定重跑，从当前页 options / H5 hash 再读一遍
  const pages = getCurrentPages();
  const cur = pages[pages.length - 1] as { options?: Record<string, string> } | undefined;
  applyQuery({ ...readHashQuery(), ...(cur?.options || {}) });
  reload();
  loadServicePhone();
});

function readHashQuery(): Record<string, string> {
  // #ifdef H5
  try {
    const hash = window.location.hash || '';
    const q = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : '';
    const params = new URLSearchParams(q);
    return {
      ticketId: params.get('ticketId') || '',
      sessionId: params.get('sessionId') || ''
    };
  } catch {
    return {};
  }
  // #endif
  // #ifndef H5
  return {};
  // #endif
}

function applyQuery(opts?: Record<string, string>) {
  if (!opts) return;
  if (opts.ticketId) ticketId.value = String(opts.ticketId);
  if (opts.sessionId) sessionId.value = String(opts.sessionId);
}

async function loadServicePhone() {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    if (cfg?.servicePhone) servicePhone.value = cfg.servicePhone;
  } catch {
    /* keep default */
  }
}

async function reload() {
  loading.value = true;
  error.value = '';
  try {
    const list = await consumerApi.listMyDisputes();
    let found: DisputeTicketDto | null = null;
    if (ticketId.value) {
      found = list.find((d) => d.ticketId === ticketId.value) || null;
    }
    if (!found && sessionId.value) {
      found = list.find((d) => d.sessionId === sessionId.value) || null;
    }
    if (!found) {
      ticket.value = null;
      error.value = '未找到该审核单，可能已归档或尚未生成';
      return;
    }
    ticket.value = found;
    sessionId.value = found.sessionId || sessionId.value;
    ticketId.value = found.ticketId || ticketId.value;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function fmtLine(line: OrderLineDto) {
  const cents = line.lineAmountCents ?? 0;
  return fmtMoney(cents);
}

function formatTime(v?: string) {
  return formatDateTimeMinute(v);
}

function goOrder() {
  const oid = ticket.value?.orderId;
  if (!oid) return;
  uni.navigateTo({
    url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(oid)}`
  });
}

function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}

function contactOps() {
  uni.makePhoneCall({ phoneNumber: servicePhone.value });
}
</script>

<style scoped>
.page-root { min-height: 100vh; background: #f7f7f7; padding-bottom: 48rpx; }
.state { padding: 120rpx 48rpx; text-align: center; }
.meta { color: #888; }
.err { display: block; color: #fa5151; margin-bottom: 24rpx; }
.hero {
  padding: 48rpx 40rpx 56rpx;
  color: #fff;
  border-radius: 0 0 32rpx 32rpx;
}
.hero.tone-wait { background: linear-gradient(145deg, #0f766e, #14b8a6); }
.hero.tone-warn { background: linear-gradient(145deg, #b45309, #f59e0b); }
.hero.tone-success { background: linear-gradient(145deg, #047857, #10b981); }
.hero-icon {
  width: 72rpx; height: 72rpx; border-radius: 50%;
  background: rgba(255,255,255,.22); display: flex; align-items: center; justify-content: center;
  font-size: 36rpx; font-weight: 700; margin-bottom: 16rpx;
}
.hero-title { display: block; font-size: 36rpx; font-weight: 700; }
.hero-status { display: block; margin-top: 8rpx; font-size: 26rpx; opacity: .92; }
.card {
  margin: 20rpx 24rpx 0; padding: 28rpx; background: #fff; border-radius: 20rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, .04);
}
.section-title { display: block; font-size: 30rpx; font-weight: 700; color: #1e293b; margin-bottom: 12rpx; }
.section-sub { display: block; font-size: 22rpx; color: #94a3b8; margin-bottom: 12rpx; }
.reason { display: block; font-size: 28rpx; color: #334155; line-height: 1.55; margin-bottom: 16rpx; }
.info-row { display: flex; justify-content: space-between; padding: 10rpx 0; }
.info-label { font-size: 24rpx; color: #94a3b8; }
.info-value { font-size: 24rpx; color: #475569; max-width: 70%; text-align: right; }
.info-value.mono { font-family: ui-monospace, monospace; font-size: 22rpx; }
.line { display: flex; justify-content: space-between; padding: 14rpx 0; border-bottom: 1rpx solid #f1f5f9; }
.line-name { color: #1e293b; font-size: 28rpx; }
.line-amt { color: #059669; font-weight: 600; }
.empty-lines { font-size: 26rpx; color: #94a3b8; padding: 8rpx 0; }
.bill-row {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 16rpx; padding-top: 16rpx; border-top: 1rpx solid #e2e8f0;
}
.bill-label { font-size: 28rpx; color: #64748b; }
.bill-amount { font-size: 40rpx; font-weight: 800; color: #047857; }
.actions { padding: 28rpx 24rpx; display: flex; flex-direction: column; gap: 16rpx; }
.btn-primary, .btn-ghost {
  margin: 0; height: 88rpx; line-height: 88rpx; border-radius: 44rpx; font-size: 30rpx;
}
.btn-primary { background: linear-gradient(135deg, #059669, #0d9488); color: #fff; font-weight: 700; }
.btn-primary::after, .btn-ghost::after { border: none; }
.btn-ghost { background: #fff; color: #53645b; border: 1rpx solid #e4ebe7; }
.btn-ghost.subtle { color: #94a3b8; }
</style>
