<template>
  <view class="page-root">
    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="error" class="error"><text>{{ error }}</text></view>
    <view v-else>
      <!-- 状态条 -->
      <view class="status-bar" :class="'status-' + (order?.status || '').toLowerCase()">
        <text class="status-icon">{{ statusIcon }}</text>
        <view class="status-copy">
          <text class="status-title">{{ statusTitle }}</text>
          <text class="status-detail">{{ statusDetail }}</text>
        </view>
      </view>

      <!-- 商品清单 -->
      <view class="section">
        <text class="section-title">商品清单</text>
        <view v-for="item in (order?.lines || [])" :key="item.skuId" class="item-row">
          <view class="item-info">
            <text class="item-name">{{ item.skuName }}</text>
            <text class="item-qty">x{{ item.quantity }}</text>
          </view>
          <text class="item-price">¥{{ (item.lineAmountCents / 100).toFixed(2) }}</text>
        </view>
        <view class="total-row">
          <text class="total-label">合计</text>
          <text class="total-amount">¥{{ (order?.totalAmountCents / 100).toFixed(2) }}</text>
        </view>
        <view v-if="order?.couponDiscountCents" class="discount-row">
          <text class="discount-label">优惠券抵扣</text>
          <text class="discount-amount">-¥{{ (order.couponDiscountCents / 100).toFixed(2) }}</text>
        </view>
      </view>

      <!-- 支付信息 -->
      <view class="section">
        <text class="section-title">支付信息</text>
        <view class="info-row"><text class="info-label">支付方式</text><text class="info-value">{{ payChannelText }}</text></view>
        <view class="info-row"><text class="info-label">扣款时间</text><text class="info-value">{{ formatTime(order?.payTime || order?.createdAt) }}</text></view>
        <view class="info-row"><text class="info-label">订单编号</text><text class="info-value mono">{{ order?.orderId }}</text></view>
        <view class="info-row"><text class="info-label">设备编号</text><text class="info-value mono">{{ order?.deviceId }}</text></view>
      </view>

      <!-- 操作区 -->
      <view class="actions">
        <button v-if="videoUrl" class="btn-outline" @click="playVideo">查看购物视频</button>
        <button class="btn-outline danger" @click="onDispute">对账单有疑问</button>
      </view>

      <view class="support">客服电话: 400-888-0018</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { get } from '@/utils/consumer-api';

const orderId = ref('');
const order = ref<any>(null);
const loading = ref(true);
const error = ref('');
const videoUrl = ref('');

onLoad(async (opt: any) => {
  orderId.value = opt?.orderId || '';
  if (!orderId.value) { error.value = '缺少订单编号'; loading.value = false; return; }
  try {
    const res = await get('/api/v2/orders/' + orderId.value);
    order.value = res.data;
    if (order.value?.videoUri) videoUrl.value = order.value.videoUri;
  } catch (e: any) {
    error.value = e?.message || '加载失败';
  } finally { loading.value = false; }
});

const statusIcon = computed(() => {
  const map: Record<string, string> = { paid: '✓', refunded: '↩', disputed: '!', failed: '✕' };
  return map[(order.value?.status || '').toLowerCase()] || '✓';
});

const statusTitle = computed(() => {
  const map: Record<string, string> = { PAID: '交易完成', REFUNDED: '已退款', DISPUTED: '争议处理中', FAILED: '交易失败' };
  return map[order.value?.status || ''] || '已完成';
});

const statusDetail = computed(() => {
  if (order.value?.status === 'PAID') return '关门自动扣款成功，如有疑问请联系客服';
  if (order.value?.status === 'REFUNDED') return '已退款至您的账户余额';
  return '';
});

const payChannelText = computed(() => {
  const map: Record<string, string> = { BALANCE: '余额支付', WECHAT: '微信支付', ALIPAY: '支付宝' };
  return map[order.value?.payChannel || ''] || order.value?.payChannel || '-';
});

function formatTime(t: string) {
  if (!t) return ''; return t.substring(0, 16).replace('T', ' ');
}

function playVideo() {
  if (videoUrl.value) uni.previewImage({ urls: [videoUrl.value], current: 0 });
}

function onDispute() {
  uni.showModal({
    title: '对账单有疑问？',
    content: '如您对购物清单有疑问，可提交争议，我们将人工核实处理。',
    confirmText: '提交争议',
    success: (res) => {
      if (res.confirm) {
        uni.navigateTo({ url: `/pages/dispute/dispute?orderId=${order.value?.orderId}&sessionId=${order.value?.sessionId}` });
      }
    }
  });
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f7f7f7; min-height: 100vh; }
.loading, .error { text-align: center; padding: 80rpx 0; color: #999; font-size: 28rpx; }
.status-bar { display: flex; align-items: center; background: #fff; border-radius: 16rpx; padding: 30rpx; margin-bottom: 20rpx; }
.status-bar.status-paid { background: linear-gradient(135deg, #e8f5e9, #fff); }
.status-bar.status-refunded { background: linear-gradient(135deg, #fff3e0, #fff); }
.status-icon { width: 60rpx; height: 60rpx; border-radius: 30rpx; display: flex; align-items: center; justify-content: center; font-size: 32rpx; font-weight: 700; margin-right: 20rpx; background: #07c160; color: #fff; flex-shrink: 0; }
.status-bar.status-refunded .status-icon { background: #ff9500; }
.status-title { font-size: 32rpx; font-weight: 600; display: block; }
.status-detail { font-size: 24rpx; color: #666; margin-top: 4rpx; display: block; }
.section { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; }
.section-title { font-size: 28rpx; font-weight: 600; margin-bottom: 16rpx; display: block; color: #333; }
.item-row { display: flex; justify-content: space-between; align-items: center; padding: 14rpx 0; border-bottom: 1rpx solid #f5f5f5; }
.item-name { font-size: 28rpx; }
.item-qty { font-size: 24rpx; color: #999; margin-left: 12rpx; }
.item-price { font-size: 28rpx; font-weight: 500; }
.total-row { display: flex; justify-content: space-between; padding: 20rpx 0 0; }
.total-label { font-size: 28rpx; font-weight: 600; }
.total-amount { font-size: 36rpx; font-weight: 700; color: #ff3b30; }
.discount-row { display: flex; justify-content: space-between; padding: 8rpx 0; }
.discount-label { font-size: 24rpx; color: #07c160; }
.discount-amount { font-size: 24rpx; color: #07c160; }
.info-row { display: flex; justify-content: space-between; padding: 12rpx 0; }
.info-label { font-size: 26rpx; color: #666; }
.info-value { font-size: 26rpx; color: #333; }
.mono { font-family: monospace; font-size: 22rpx; }
.actions { display: flex; gap: 20rpx; padding: 10rpx 0; }
.btn-outline { flex: 1; height: 72rpx; line-height: 72rpx; border: 2rpx solid #07c160; color: #07c160; border-radius: 36rpx; background: #fff; font-size: 28rpx; text-align: center; }
.btn-outline.danger { border-color: #ff3b30; color: #ff3b30; }
.support { text-align: center; padding: 30rpx; color: #999; font-size: 24rpx; }
</style>
