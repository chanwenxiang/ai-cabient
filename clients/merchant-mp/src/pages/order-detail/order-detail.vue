<template>
  <view class="page-root">
    <app-nav-bar title="订单详情" />
    <view class="page-body">
      <view v-if="loading && !order" class="loading"
        ><text>{{ UI_COPY.loading }}</text></view
      >
      <view v-else-if="error && !order" class="empty">
        <text class="err">{{ error }}</text>
        <app-button label="重试" @click="load" />
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
            ><text class="val mono">{{
              emptyDisplay(order.deviceName || order.deviceId, 'device')
            }}</text></view
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
              >{{
                displayLabel(
                  'order_status',
                  order.status === 'PARTIAL_REFUNDED' ? 'PARTIAL_REFUNDED' : 'REFUNDED'
                )
              }}{{ refundCents > 0 ? ` ${fmtMoney(refundCents)}` : ''
              }}{{ order.refundedAt ? ` · ${formatTime(order.refundedAt)}` : '' }}</text
            ></view
          >
        </view>

        <view class="actions">
          <app-button v-if="order.deviceId" label="查看柜机" @click="goDevice" />
          <app-button
            v-if="canShowVideo"
            variant="outline"
            label="查看购物视频"
            @click="playVideo"
          />
          <app-button variant="outline" label="相关争议" @click="goDisputes" />
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { showError } from '@/utils/notify';
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
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import type { MerchantMe } from '@aicabinet/shared-types';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

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
  // M-6：只信服务端 refundedCents
  const n = Number(o.refundedCents ?? 0);
  return Number.isFinite(n) && n > 0 ? n : 0;
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
    seedMerchantMeDisplayCache(me);
  }
  if (!canList.value) {
    showError('无订单权限');
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
  background: var(--card-bg, #ffffff);
  padding: 0;

  box-sizing: border-box;
}
.loading,
.empty {
  text-align: center;
  padding: 80rpx 24rpx;
  color: var(--text-muted);
  font-size: var(--font-size-md);
}
.err {
  color: var(--color-danger);
  display: block;
  margin-bottom: 20rpx;
}
.status-bar {
  background: linear-gradient(135deg, var(--brand-soft), var(--white));
  color: var(--text-primary, #14201b);
  border-radius: var(--radius-panel);
  padding: 28rpx 24rpx;
  margin-bottom: 20rpx;
  border: 1rpx solid var(--brand-soft, #d1fae5);
}
.status-bar.s-disputed {
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--warning, #b45309) 8%, var(--white)),
    var(--white)
  );
  border-color: color-mix(in srgb, var(--warning, #b45309) 28%, var(--white));
}
.status-bar.s-refunded,
.status-bar.s-partial_refunded {
  background: linear-gradient(135deg, var(--info-soft), var(--white));
  border-color: #bfdbfe;
}
.status-bar.s-pending,
.status-bar.s-processing {
  background: linear-gradient(135deg, var(--warning-soft), var(--white));
  border-color: var(--warning-soft);
}
.status-title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #0f172a);
}
.status-amt {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-h1);
  font-weight: 700;
  color: var(--brand);
}
.section {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 24rpx;
  margin-bottom: 16rpx;
  border: 1rpx solid var(--color-border);
}
.section-title {
  display: block;
  font-size: var(--font-size-body);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
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
  border-radius: var(--radius-control);
  background: var(--brand-soft);
  flex-shrink: 0;
}
.line-info {
  display: flex;
  gap: 12rpx;
  align-items: baseline;
  min-width: 0;
}
.line-name {
  font-size: var(--font-size-md);
  color: var(--text-primary, #0f172a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 360rpx;
}
.line-qty {
  font-size: var(--font-size-caption);
  color: var(--text-subtle);
}
.line-unit {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.line-amt {
  font-size: var(--font-size-md);
  color: var(--text-primary, #0f172a);
  font-weight: 600;
}
.sum-row {
  display: flex;
  justify-content: space-between;
  margin-top: 12rpx;
  padding-top: 12rpx;
  border-top: 1rpx solid var(--color-border-subtle, #f1f5f9);
  font-size: var(--font-size-body);
  color: var(--text-muted);
}
.sum-row.strong {
  color: var(--text-primary, #0f172a);
  font-weight: 700;
  font-size: var(--font-size-lg);
}
.info-row {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
  padding: 10rpx 0;
  font-size: var(--font-size-body);
}
.lbl {
  color: var(--text-subtle);
  flex-shrink: 0;
}
.val {
  color: var(--text-primary, #0f172a);
  text-align: right;
  word-break: break-all;
}
.mono {
  font-family: ui-monospace, monospace;
  font-size: var(--font-size-caption);
}
.muted {
  color: var(--text-subtle);
  font-size: var(--font-size-body);
  padding: 12rpx 0;
}
.actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
  margin-top: 8rpx;
}
.app-btn,
.btn-outline {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-md);
  font-weight: 600;
  min-height: 88rpx;
  line-height: 1.2;
  padding: 0 32rpx;
  margin: 0;
}
.btn-outline {
  background: var(--card-bg, #fff);
  color: var(--brand);
  border: 2rpx solid var(--brand);
  min-height: 80rpx;
}
.app-btn::after,
.btn-outline::after {
  border: none;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
