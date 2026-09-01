<template>
  <view class="page-root">
    <app-nav-bar title="订单详情" />
    <view class="page-body">
      <view v-if="loading && !order" class="loading"><text>加载中…</text></view>
      <view v-else-if="error && !order" class="empty">
        <text class="err">{{ error }}</text>
        <button class="retry" @click="load">重试</button>
      </view>
      <view v-else-if="order">
        <view class="status-bar" :class="'s-' + (order.status || '').toLowerCase()">
          <text class="status-title">{{ statusText(order.status) }}</text>
          <text class="status-amt">{{ money(order.totalAmountCents) }}</text>
        </view>

        <view class="section">
          <text class="section-title">商品明细</text>
          <view v-for="(line, i) in order.lines || []" :key="i" class="line">
            <image
              class="line-thumb"
              :src="skuImageFor(line.skuId, line.skuName)"
              mode="aspectFill"
              aria-hidden="true"
            />
            <view class="line-info">
              <text class="line-name">{{ line.skuName || line.skuId || '商品' }}</text>
              <text class="line-qty"
                >x{{ line.quantity }}{{ line.slotId ? ` · 货道 ${line.slotId}` : ''
                }}{{ line.batchNo ? ` · 批次 ${line.batchNo}` : '' }}</text
              >
              <text v-if="line.unitPriceCents != null" class="line-unit"
                >单价 {{ money(line.unitPriceCents) }}</text
              >
            </view>
            <text class="line-amt">{{ money(line.lineAmountCents) }}</text>
          </view>
          <view v-if="!(order.lines || []).length" class="muted">无商品明细</view>
          <view v-if="Number(order.originalAmountCents || 0) > 0" class="sum-row">
            <text>原价</text>
            <text>{{ money(order.originalAmountCents) }}</text>
          </view>
          <view v-if="order.couponDiscountCents" class="sum-row">
            <text>券优惠</text>
            <text>减{{ money(order.couponDiscountCents) }}</text>
          </view>
          <view v-if="Number(order.memberDiscountCents || 0) > 0" class="sum-row">
            <text>会员优惠</text>
            <text>减{{ money(order.memberDiscountCents) }}</text>
          </view>
          <view class="sum-row strong">
            <text>实付</text>
            <text>{{ money(order.totalAmountCents) }}</text>
          </view>
        </view>

        <view class="section">
          <text class="section-title">订单信息</text>
          <view class="info-row"
            ><text class="lbl">订单号</text
            ><text class="val mono">{{ emptyDisplay(order.orderId, 'order') }}</text></view
          >
          <view class="info-row"
            ><text class="lbl">会话</text
            ><text class="val mono">{{ emptyDisplay(order.sessionId, 'session') }}</text></view
          >
          <view class="info-row"
            ><text class="lbl">柜机</text
            ><text class="val mono">{{ emptyDisplay(order.deviceName || order.deviceId, 'device') }}</text></view
          >
          <view class="info-row"
            ><text class="lbl">支付方式</text><text class="val">{{ payChannelText }}</text></view
          >
          <view v-if="order.payTradeNo || order.paymentOperationId" class="info-row"
            ><text class="lbl">流水号</text
            ><text class="val mono">{{
              displayBizNo(order.payTradeNo || order.paymentOperationId)
            }}</text></view
          >
          <view v-if="order.splitStatus" class="info-row"
            ><text class="lbl">分账状态</text
            ><text class="val">{{ splitStatusText(order.splitStatus) }}</text></view
          >
          <view v-if="order.refundPolicy" class="info-row"
            ><text class="lbl">退款策略</text
            ><text class="val">{{ refundPolicyText(order.refundPolicy) }}</text></view
          >
          <view class="info-row"
            ><text class="lbl">创建时间</text
            ><text class="val">{{ formatTime(order.createdAt) }}</text></view
          >
          <view
            v-if="
              order.refundedAt ||
              order.status === 'REFUNDED' ||
              order.status === 'PARTIAL_REFUNDED' ||
              refundCents > 0
            "
            class="info-row"
            ><text class="lbl">退款</text
            ><text class="val"
              >{{ order.status === 'PARTIAL_REFUNDED' ? '部分退款' : '已退款'
              }}{{ refundCents > 0 ? ` ${fmtMoney(refundCents)}` : ''
              }}{{ order.refundedAt ? ` · ${formatTime(order.refundedAt)}` : '' }}</text
            ></view
          >
        </view>

        <view class="actions">
          <button v-if="order.deviceId" class="btn-primary" @click="goDevice">查看柜机</button>
          <button v-if="canShowVideo" class="btn-outline" @click="playVideo">查看购物视频</button>
          <button class="btn-outline" @click="goDisputes">相关争议</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app';
import { displayLabel } from '@aicabinet/shared-dict';
import { skuImageFor } from '@aicabinet/shared-uni/product-image';
import {
  displayBizNo,
  emptyDisplay,
  formatDateTimeShort,
  orderStatusLabel,
  fmtMoney
} from '@aicabinet/shared-uni/format';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantMe } from '@aicabinet/shared-types';

type OrderLine = {
  skuId?: string;
  skuName?: string;
  quantity?: number;
  unitPriceCents?: number;
  lineAmountCents?: number;
  batchNo?: string;
  slotId?: string;
};

type OrderDetail = {
  orderId?: string;
  sessionId?: string;
  deviceId?: string;
  deviceName?: string;
  status?: string;
  payChannel?: string;
  payTradeNo?: string;
  paymentOperationId?: string;
  totalAmountCents?: number;
  couponDiscountCents?: number;
  memberDiscountCents?: number;
  originalAmountCents?: number;
  refundPolicy?: string;
  refundedAt?: string;
  refundedCents?: number;
  lines?: OrderLine[];
  createdAt?: string;
  splitStatus?: string;
};

const { me, refresh: refreshMe } = useMerchantMe();
const canList = computed(() => hasPerm(me.value, 'merchant:orders:list'));

const orderId = ref('');
const order = ref<OrderDetail | null>(null);
const loading = ref(true);
const error = ref('');

const payChannelText = computed(() =>
  displayLabel('pay_channel', order.value?.payChannel, '未知渠道')
);

const refundCents = computed(() => {
  const o = order.value;
  if (!o) return 0;
  const n = Number(o.refundedCents || 0);
  if (n > 0) return n;
  if (o.status === 'REFUNDED') return Number(o.totalAmountCents || 0);
  return 0;
});

/** 有会话且已产生账单的订单可查看录像（由后端 /merchant/orders/{id}/video 鉴权拉流） */
const canShowVideo = computed(() => {
  const o = order.value;
  if (!o?.sessionId || !o.orderId) return false;
  const s = String(o.status || '').toUpperCase();
  return s === 'PAID' || s === 'COMPLETED' || s === 'REFUNDED' || s === 'PARTIAL_REFUNDED';
});

onLoad((opt) => {
  const q = (opt || {}) as Record<string, string | undefined>;
  orderId.value = String(q.orderId || q.id || '').trim();
  void load();
});
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  if (!orderId.value) {
    error.value = '缺少订单号';
    loading.value = false;
    return;
  }
  try {
    await refreshMe();
  } catch {
    me.value = me.value || (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canList.value) {
    uni.showToast({ title: '无订单权限', icon: 'none' });
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  if (!order.value) loading.value = true;
  error.value = '';
  try {
    order.value = (await merchantApi.orderDetail(orderId.value)) as OrderDetail;
  } catch (e) {
    if (!order.value) {
      order.value = null;
      error.value = e instanceof Error ? e.message : '加载失败';
    }
  } finally {
    loading.value = false;
  }
}

function statusText(s?: string) {
  return orderStatusLabel(s);
}

function refundPolicyText(policy?: string) {
  if (policy === 'AUTO_REFUND') return '自助退';
  if (policy === 'DISPUTE_ONLY') return '仅争议';
  if (!policy) return '默认规则';
  if (/^[A-Z][A-Z0-9_]*$/.test(policy)) return '默认规则';
  return policy;
}

function splitStatusText(status?: string) {
  return (
    (
      {
        PENDING: '待处理',
        LEDGER_ONLY: '仅记账',
        ACCRUED: '待分账',
        WECHAT_SUBMITTED: '已提交',
        WECHAT_FAILED: '失败',
        SUBMITTED: '已提交',
        SUCCESS: '成功',
        FAILED: '失败',
        SETTLED: '已完结',
        VOIDED: '已冲正'
      } as Record<string, string>
    )[String(status || '').toUpperCase()] || String(status || '')
  );
}

function money(cents?: number) {
  return fmtMoney(cents);
}

function formatTime(t?: string) {
  return formatDateTimeShort(t, '暂无');
}

function goDevice() {
  const id = order.value?.deviceId;
  if (!id) return;
  uni.navigateTo({
    url: `/pages/device-detail/device-detail?id=${encodeURIComponent(id)}`
  });
}

function goDisputes() {
  const sid = order.value?.sessionId;
  if (sid) {
    uni.navigateTo({
      url: `/pages/disputes/disputes?sessionId=${encodeURIComponent(sid)}`
    });
    return;
  }
  uni.navigateTo({ url: '/pages/disputes/disputes' });
}

function playVideo() {
  const oid = order.value?.orderId;
  const did = order.value?.deviceId || '';
  if (!oid) return;
  uni.navigateTo({
    url: `/pages/video/video?orderId=${encodeURIComponent(oid)}&deviceId=${encodeURIComponent(did)}`
  });
}
</script>

<style scoped>
.page-root {
  min-height: 100vh;
  background: #ffffff;
  padding: 0;

  box-sizing: border-box;
}
.loading,
.empty {
  text-align: center;
  padding: 80rpx 24rpx;
  color: #64748b;
  font-size: 28rpx;
}
.err {
  color: #b91c1c;
  display: block;
  margin-bottom: 20rpx;
}
.retry {
  display: inline-block;
  margin-top: 12rpx;
  padding: 0 36rpx;
  min-height: 72rpx;
  line-height: 72rpx;
  border-radius: 999rpx;
  background: linear-gradient(135deg, #134e4a, #0f766e);
  color: #fff;
  font-size: 26rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 20rpx rgba(15, 118, 110, 0.2);
}
.retry::after {
  border: none;
}
.status-bar {
  background: linear-gradient(135deg, #ecfdf5, #fff);
  color: #14201b;
  border-radius: 16rpx;
  padding: 28rpx 24rpx;
  margin-bottom: 20rpx;
  border: 1rpx solid #d1fae5;
}
.status-bar.s-disputed {
  background: linear-gradient(135deg, #fff7ed, #fff);
  border-color: #fed7aa;
}
.status-bar.s-refunded,
.status-bar.s-partial_refunded {
  background: linear-gradient(135deg, #eff6ff, #fff);
  border-color: #bfdbfe;
}
.status-bar.s-pending,
.status-bar.s-processing {
  background: linear-gradient(135deg, #fefce8, #fff);
  border-color: #fde68a;
}
.status-title {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: #0f172a;
}
.status-amt {
  display: block;
  margin-top: 8rpx;
  font-size: 44rpx;
  font-weight: 700;
  color: #0f766e;
}
.section {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
  border: 1rpx solid #e2e8f0;
}
.section-title {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 16rpx;
}
.line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12rpx 0;
  gap: 16rpx;
}
.line-thumb {
  width: 80rpx;
  height: 80rpx;
  border-radius: 14rpx;
  background: #ecfdf5;
  flex-shrink: 0;
}
.line-info {
  display: flex;
  gap: 12rpx;
  align-items: baseline;
  min-width: 0;
}
.line-name {
  font-size: 28rpx;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 360rpx;
}
.line-qty {
  font-size: 24rpx;
  color: #94a3b8;
}
.line-unit {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #64748b;
}
.line-amt {
  font-size: 28rpx;
  color: #0f172a;
  font-weight: 600;
}
.sum-row {
  display: flex;
  justify-content: space-between;
  margin-top: 12rpx;
  padding-top: 12rpx;
  border-top: 1rpx solid #f1f5f9;
  font-size: 26rpx;
  color: #64748b;
}
.sum-row.strong {
  color: #0f172a;
  font-weight: 700;
  font-size: 30rpx;
}
.info-row {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
  padding: 10rpx 0;
  font-size: 26rpx;
}
.lbl {
  color: #94a3b8;
  flex-shrink: 0;
}
.val {
  color: #0f172a;
  text-align: right;
  word-break: break-all;
}
.mono {
  font-family: ui-monospace, monospace;
  font-size: 24rpx;
}
.muted {
  color: #94a3b8;
  font-size: 26rpx;
  padding: 12rpx 0;
}
.actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
  margin-top: 8rpx;
}
.btn-primary,
.btn-outline {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  border-radius: 44rpx;
  font-size: 28rpx;
  font-weight: 600;
  min-height: 88rpx;
  line-height: 1.2;
  padding: 0 32rpx;
  margin: 0;
}
.btn-primary {
  background: linear-gradient(135deg, #134e4a, #0f766e);
  color: #fff;
  border: none;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.22);
}
.btn-outline {
  background: #fff;
  color: #0f766e;
  border: 2rpx solid #0f766e;
  min-height: 80rpx;
}
.btn-primary::after,
.btn-outline::after {
  border: none;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
