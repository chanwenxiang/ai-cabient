<template>
  <view class="page-root">
    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="error" class="error">
      <text>{{ error }}</text>
      <button class="btn-outline" style="margin-top: 24rpx" @click="reload">重试</button>
    </view>
    <view v-else>
      <view class="status-bar" :class="'status-' + (order?.status || '').toLowerCase()">
        <text class="status-icon">{{ statusIcon }}</text>
        <view class="status-copy">
          <text class="status-title">{{ statusTitle }}</text>
          <text class="status-detail">{{ statusDetail }}</text>
        </view>
      </view>

      <view class="section">
        <text class="section-title">商品清单</text>
        <view v-for="item in (order?.lines || [])" :key="item.skuId" class="item-row">
          <view class="item-info">
            <text class="item-name">{{ item.skuName }}</text>
            <text class="item-qty">x{{ item.quantity }}</text>
          </view>
          <text class="item-price">¥{{ (item.lineAmountCents / 100).toFixed(2) }}</text>
        </view>
        <view v-if="!(order?.lines || []).length" class="empty-lines">暂无商品明细</view>
        <view class="total-row">
          <text class="total-label">商品合计</text>
          <text class="total-amount">¥{{ ((order?.originalAmountCents || order?.totalAmountCents || 0) / 100).toFixed(2) }}</text>
        </view>
        <view v-if="order?.couponDiscountCents" class="discount-row">
          <text class="discount-label">优惠券抵扣</text>
          <text class="discount-amount">-¥{{ (order.couponDiscountCents / 100).toFixed(2) }}</text>
        </view>
        <view v-if="order?.couponDiscountCents" class="total-row pay">
          <text class="total-label">实付</text>
          <text class="total-amount">¥{{ ((order?.totalAmountCents || 0) / 100).toFixed(2) }}</text>
        </view>
        <view v-if="order?.pointsEarned" class="points-row">
          <text class="points-label">本次获得积分</text>
          <text class="points-amount">+{{ order.pointsEarned }}</text>
        </view>
      </view>

      <view class="section">
        <text class="section-title">支付信息</text>
        <view class="info-row"><text class="info-label">支付方式</text><text class="info-value">{{ payChannelText }}</text></view>
        <view class="info-row"><text class="info-label">扣款时间</text><text class="info-value">{{ formatTime(order?.payTime || order?.createdAt) }}</text></view>
        <view class="info-row"><text class="info-label">订单编号</text><text class="info-value mono">{{ order?.orderId }}</text></view>
        <view class="info-row"><text class="info-label">设备编号</text><text class="info-value mono">{{ order?.deviceId }}</text></view>
      </view>

      <view class="actions">
        <button v-if="videoUrl" class="btn-outline" @click="playVideo">查看购物视频</button>
        <button
          v-if="canDispute"
          class="btn-outline danger"
          :disabled="disputeLoading"
          @click="openDispute"
        >
          {{ disputeFiled ? '申诉已提交' : '对账单有疑问' }}
        </button>
      </view>

      <view class="support">客服电话: 400-888-0018</view>
    </view>

    <view v-if="showDispute" class="dispute-mask" @click="closeDispute">
      <view class="dispute-panel" @click.stop>
        <text class="dispute-title">账单申诉</text>
        <text class="dispute-sub">请描述您认为有误的地方，运营将在 24 小时内处理</text>
        <textarea
          v-model="disputeReason"
          class="dispute-input"
          maxlength="200"
          placeholder="例如：我没有拿这个商品 / 数量不对"
        />
        <button class="btn-submit" :loading="disputeLoading" :disabled="disputeLoading" @click="submitDispute">
          {{ disputeLoading ? '提交中…' : '提交申诉' }}
        </button>
        <text class="dispute-cancel" @click="closeDispute">取消</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { consumerApi, get } from '@/utils/consumer-api';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';

const orderId = ref('');
const order = ref<any>(null);
const loading = ref(true);
const error = ref('');
const videoUrl = ref('');
const showDispute = ref(false);
const disputeReason = ref('');
const disputeLoading = ref(false);
const disputeFiled = ref(false);

onLoad(async (opt: any) => {
  orderId.value = opt?.orderId || '';
  await reload();
});

async function reload() {
  if (!orderId.value) {
    error.value = '缺少订单编号';
    loading.value = false;
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const res = await get('/api/v2/orders/' + orderId.value);
    order.value = res.data;
    if (order.value?.videoUri) videoUrl.value = order.value.videoUri;
  } catch (e: any) {
    error.value = e?.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

const statusIcon = computed(() => {
  const map: Record<string, string> = { paid: '✓', refunded: '↩', disputed: '!', failed: '✕' };
  return map[(order.value?.status || '').toLowerCase()] || '✓';
});

const statusTitle = computed(() => {
  const map: Record<string, string> = {
    PAID: '交易完成',
    COMPLETED: '交易完成',
    REFUNDED: '已退款',
    DISPUTED: '争议处理中',
    FAILED: '交易失败'
  };
  return map[order.value?.status || ''] || '已完成';
});

const statusDetail = computed(() => {
  if (order.value?.status === 'PAID' || order.value?.status === 'COMPLETED') {
    return '关门自动扣款成功，如有疑问请联系客服';
  }
  if (order.value?.status === 'REFUNDED') return '已退款至您的账户余额';
  if (order.value?.status === 'DISPUTED') return '账单审核中，请耐心等待';
  return '';
});

const payChannelText = computed(() => {
  const map: Record<string, string> = { BALANCE: '余额支付', WECHAT: '微信支付', ALIPAY: '支付宝' };
  return map[order.value?.payChannel || ''] || order.value?.payChannel || '-';
});

const canDispute = computed(() => {
  const s = order.value?.status;
  return !!order.value?.sessionId && !disputeFiled.value && s !== 'REFUNDED' && s !== 'DISPUTED';
});

function formatTime(t: string) {
  return formatDateTimeMinute(t, '');
}

function playVideo() {
  if (videoUrl.value) uni.previewImage({ urls: [videoUrl.value], current: 0 });
}

function openDispute() {
  disputeReason.value = '';
  showDispute.value = true;
}

function closeDispute() {
  showDispute.value = false;
}

async function submitDispute() {
  const sessionId = order.value?.sessionId;
  const reason = disputeReason.value.trim();
  if (!sessionId) {
    uni.showToast({ title: '缺少会话信息', icon: 'none' });
    return;
  }
  if (reason.length < 4) {
    uni.showToast({ title: '请至少填写 4 个字', icon: 'none' });
    return;
  }
  disputeLoading.value = true;
  try {
    await consumerApi.fileDispute({
      sessionId,
      reason,
      category: 'USER_APPEAL',
      priority: 'NORMAL'
    });
    disputeFiled.value = true;
    showDispute.value = false;
    uni.showToast({ title: '申诉已提交', icon: 'success' });
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '提交失败', icon: 'none' });
  } finally {
    disputeLoading.value = false;
  }
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f7f7f7; min-height: 100vh; box-sizing: border-box; }
.loading, .error { text-align: center; padding: 80rpx 0; color: #999; font-size: 28rpx; }
.empty-lines { font-size: 26rpx; color: #999; padding: 12rpx 0; }
.status-bar { display: flex; align-items: center; background: #fff; border-radius: 16rpx; padding: 30rpx; margin-bottom: 20rpx; }
.status-bar.status-paid, .status-bar.status-completed { background: linear-gradient(135deg, #e8f5e9, #fff); }
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
.total-row.pay { padding-top: 12rpx; border-top: 1rpx solid #eee; margin-top: 8rpx; }
.total-row.pay .total-amount { color: #07c160; font-size: 34rpx; }
.total-label { font-size: 28rpx; font-weight: 600; }
.total-amount { font-size: 36rpx; font-weight: 700; color: #ff3b30; }
.points-row { display: flex; justify-content: space-between; padding: 10rpx 0 4rpx; }
.points-label { font-size: 26rpx; color: #849087; }
.points-amount { font-size: 28rpx; font-weight: 700; color: #d97706; }
.discount-row { display: flex; justify-content: space-between; padding: 8rpx 0; }
.discount-label { font-size: 24rpx; color: #07c160; }
.discount-amount { font-size: 24rpx; color: #07c160; }
.info-row { display: flex; justify-content: space-between; padding: 12rpx 0; }
.info-label { font-size: 26rpx; color: #666; }
.info-value { font-size: 26rpx; color: #333; }
.mono { font-family: monospace; font-size: 22rpx; }
.actions { display: flex; flex-direction: column; gap: 20rpx; padding: 10rpx 0; }
.btn-outline { height: 72rpx; line-height: 72rpx; border: 2rpx solid #07c160; color: #07c160; border-radius: 36rpx; background: #fff; font-size: 28rpx; text-align: center; }
.btn-outline.danger { border-color: #ff3b30; color: #ff3b30; }
.support { text-align: center; padding: 30rpx; color: #999; font-size: 24rpx; }
.dispute-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 100; display: flex; align-items: flex-end; }
.dispute-panel { width: 100%; background: #fff; border-radius: 24rpx 24rpx 0 0; padding: 32rpx 28rpx calc(32rpx + env(safe-area-inset-bottom)); box-sizing: border-box; }
.dispute-title { font-size: 34rpx; font-weight: 700; display: block; }
.dispute-sub { font-size: 24rpx; color: #888; display: block; margin: 12rpx 0 20rpx; }
.dispute-input { width: 100%; min-height: 160rpx; background: #f7f7f7; border-radius: 12rpx; padding: 20rpx; box-sizing: border-box; font-size: 28rpx; margin-bottom: 20rpx; }
.btn-submit { width: 100%; height: 88rpx; line-height: 88rpx; background: #07c160; color: #fff; border-radius: 44rpx; font-size: 30rpx; border: none; }
.dispute-cancel { display: block; text-align: center; color: #888; margin-top: 20rpx; font-size: 28rpx; padding: 8rpx; }
</style>
