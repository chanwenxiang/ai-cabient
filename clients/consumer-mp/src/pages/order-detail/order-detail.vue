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
            <text class="item-name">{{ item.skuName || item.skuId || '商品' }}</text>
            <text class="item-qty">x{{ item.quantity }}</text>
          </view>
          <text class="item-price">¥{{ (item.lineAmountCents / 100).toFixed(2) }}</text>
        </view>
        <view v-if="!(order?.lines || []).length" class="empty-lines">本次未识别到取走商品</view>
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
        <view class="info-row"><text class="info-label">柜机编号</text><text class="info-value mono">{{ order?.deviceId }}</text></view>
      </view>

      <view class="actions">
        <button v-if="order?.deviceId" class="btn-primary" @click="reopenCabinet">再去本柜购物</button>
        <button v-if="videoUrl" class="btn-outline" @click="playVideo">查看购物视频</button>
        <button
          v-if="canRefund"
          class="btn-refund"
          :disabled="refundLoading || disputeLoading"
          @click="openRefund"
        >
          {{ refundDone ? '已退款' : '立即退款' }}
        </button>
        <button
          v-if="canDispute"
          class="btn-outline danger"
          :disabled="disputeLoading || refundLoading"
          @click="openDispute"
        >
          {{ disputeFiled ? '申诉已提交' : (autoRefundEnabled ? '提交账单申诉' : '申请退款 / 账单申诉') }}
        </button>
        <button class="btn-outline" @click="goHelp">帮助与客服</button>
      </view>

      <view class="support" @click="callSupport">客服电话: {{ supportPhoneDisplay }} ›</view>
    </view>

    <view v-if="showDispute" class="dispute-mask" @click="closeDispute">
      <view class="dispute-panel" @click.stop>
        <text class="dispute-title">{{ refundMode ? '立即退款' : '申请退款 / 账单申诉' }}</text>
        <text class="dispute-sub">
          {{ refundMode
            ? '将原路退回本单已扣款项（余额/微信/支付宝）。可上传凭证图片辅助核对。'
            : '仅提交申诉工单，运营审核后再退款。可上传凭证图片。' }}
        </text>
        <view class="chip-row">
          <text
            v-for="chip in reasonChips"
            :key="chip.label"
            class="reason-chip"
            :class="{ on: selectedCategory === chip.category }"
            @click="pickChip(chip)"
          >{{ chip.label }}</text>
        </view>
        <textarea
          v-model="disputeReason"
          class="dispute-input"
          maxlength="200"
          placeholder="例如：我没有拿这个商品 / 数量不对"
        />
        <view class="evidence-block">
          <text class="evidence-label">申诉附图（选填，最多 5 张）</text>
          <view class="evidence-row">
            <view v-for="(img, idx) in evidence" :key="img.localPath + idx" class="evidence-item">
              <image class="evidence-img" :src="previewEvidenceSrc(img)" mode="aspectFill" />
              <text class="evidence-del" @click="removeEvidence(idx)">×</text>
              <text v-if="img.uploading" class="evidence-uploading">上传中</text>
            </view>
            <view v-if="evidence.length < 5" class="evidence-add" @click="onAddEvidence">+</view>
          </view>
        </view>
        <button
          class="btn-submit"
          :loading="disputeLoading || refundLoading"
          :disabled="disputeLoading || refundLoading"
          @click="submitAction"
        >
          {{ refundMode
            ? (refundLoading ? '退款中…' : '确认退款')
            : (disputeLoading ? '提交中…' : '提交申诉') }}
        </button>
        <text class="dispute-cancel" @click="closeDispute">取消</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { onLoad, onShow } from '@dcloudio/uni-app';
import { dictLabel } from '@aicabinet/shared-dict';
import { consumerApi, get } from '@/utils/consumer-api';
import { formatDateTimeMinute, orderStatusLabel } from '@aicabinet/shared-uni/format';
import {
  DISPUTE_REASON_CHIPS,
  appendChipToReason,
  type DisputeReasonChip
} from '@/utils/dispute-form';
import {
  pickAndUploadEvidence,
  evidenceFileIds,
  previewEvidenceSrc,
  removeEvidenceAt,
  type LocalEvidence
} from '@/utils/dispute-evidence';

const orderId = ref('');
const order = ref<any>(null);
const loading = ref(true);
const error = ref('');
const videoUrl = ref('');
const showDispute = ref(false);
const refundMode = ref(false);
const disputeReason = ref('');
const disputeLoading = ref(false);
const refundLoading = ref(false);
const disputeFiled = ref(false);
const refundDone = ref(false);
const reasonChips = DISPUTE_REASON_CHIPS;
const selectedCategory = ref('USER_APPEAL');
const evidence = ref<LocalEvidence[]>([]);
const supportPhoneDisplay = ref('400-888-0018');
const supportPhoneDial = ref('4008880018');

/** 合并 onLoad/onShow 同刻并发，避免首屏打两次订单详情 */
let bootstrapPromise: Promise<void> | null = null;
let bootstrapTarget = '';

function resolveOrderId(opt?: any): string {
  const fromOpt = String(opt?.orderId || opt?.id || '').trim();
  if (fromOpt) return fromOpt;
  if (typeof window === 'undefined' || typeof window.location === 'undefined') return '';
  try {
    const hash = String(window.location.hash || '');
    const hashQuery = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : '';
    const search = String(window.location.search || '').replace(/^\?/, '');
    const q = new URLSearchParams(hashQuery || search);
    return String(q.get('orderId') || q.get('id') || '').trim();
  } catch {
    return '';
  }
}

async function bootstrap(opt?: any) {
  const nextId = resolveOrderId(opt);
  if (!nextId) {
    orderId.value = '';
    error.value = '缺少订单编号';
    loading.value = false;
    return;
  }
  // H5 同页改 hash/query 时 onLoad 可能不触发；同单 onShow 需拉最新状态
  if (bootstrapPromise && bootstrapTarget === nextId) {
    await bootstrapPromise;
    return;
  }
  const idChanged = nextId !== orderId.value;
  orderId.value = nextId;
  if (idChanged) {
    disputeFiled.value = false;
    refundDone.value = false;
    showDispute.value = false;
  }
  bootstrapTarget = nextId;
  bootstrapPromise = (async () => {
    void loadSupportPhone();
    await reload();
  })().finally(() => {
    if (bootstrapTarget === nextId) {
      bootstrapPromise = null;
      bootstrapTarget = '';
    }
  });
  await bootstrapPromise;
}

onLoad((opt: any) => {
  void bootstrap(opt);
});

onShow(() => {
  void bootstrap();
});

function onHashChange() {
  void bootstrap();
}

onMounted(() => {
  if (typeof window !== 'undefined') {
    window.addEventListener('hashchange', onHashChange);
  }
});

onUnmounted(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('hashchange', onHashChange);
  }
});

async function loadSupportPhone() {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    const phone = String(cfg?.servicePhone || cfg?.['consumer.service_phone'] || '').trim();
    if (phone) {
      supportPhoneDisplay.value = phone;
      supportPhoneDial.value = phone.replace(/[^\d+]/g, '');
    }
  } catch {
    /* keep defaults */
  }
}

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
    if (order.value?.status === 'DISPUTED') disputeFiled.value = true;
    if (order.value?.status === 'REFUNDED') {
      refundDone.value = true;
      disputeFiled.value = true;
    }
  } catch (e: any) {
    error.value = e?.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

const statusIcon = computed(() => {
  const map: Record<string, string> = {
    paid: '✓',
    completed: '✓',
    refunded: '↩',
    partial_refunded: '↩',
    disputed: '!',
    failed: '✕',
    cancelled: '—'
  };
  return map[(order.value?.status || '').toLowerCase()] || '✓';
});

const statusTitle = computed(() => orderStatusLabel(order.value?.status) || '订单详情');

const canDispute = computed(() => {
  const s = order.value?.status;
  if (!order.value?.sessionId || disputeFiled.value) return false;
  if (s === 'REFUNDED' || s === 'PARTIAL_REFUNDED' || s === 'DISPUTED' || s === 'CANCELLED' || s === 'FAILED') {
    return false;
  }
  return s === 'PAID' || s === 'COMPLETED';
});

const autoRefundEnabled = computed(() => order.value?.refundPolicy !== 'DISPUTE_ONLY');

const canRefund = computed(() => {
  const s = order.value?.status;
  return (
    autoRefundEnabled.value &&
    !!order.value?.orderId &&
    !refundDone.value &&
    (s === 'PAID' || s === 'COMPLETED')
  );
});

const statusDetail = computed(() => {
  if (order.value?.status === 'PAID' || order.value?.status === 'COMPLETED') {
    return autoRefundEnabled.value
      ? '关门自动扣款成功，如有疑问可立即退款或提交申诉'
      : '关门自动扣款成功，如有疑问请提交账单申诉，由运营审核后退款';
  }
  if (order.value?.status === 'REFUNDED') return '已退款至原支付渠道或账户余额';
  if (order.value?.status === 'PARTIAL_REFUNDED') return '本单已部分退款，可在账单明细中核对金额';
  if (order.value?.status === 'DISPUTED') return '账单审核中，请耐心等待';
  if (order.value?.status === 'PENDING' || order.value?.status === 'PROCESSING') {
    return '订单待支付，请完成补扣后再继续购物';
  }
  if (order.value?.status === 'CANCELLED') return '本次购物已取消，未产生扣款';
  return '';
});

const payChannelText = computed(() => {
  const ch = order.value?.payChannel;
  if (!ch) return '-';
  return dictLabel('pay_channel', ch) || ch;
});

function formatTime(t: string) {
  return formatDateTimeMinute(t, '');
}

function playVideo() {
  if (!videoUrl.value) return;
  // #ifdef H5
  if (typeof window !== 'undefined') {
    window.open(videoUrl.value, '_blank');
    return;
  }
  // #endif
  uni.setClipboardData({
    data: videoUrl.value,
    success: () => uni.showToast({ title: '视频链接已复制，请到浏览器打开', icon: 'none' })
  });
}

function openDispute() {
  refundMode.value = false;
  disputeReason.value = '';
  selectedCategory.value = 'USER_APPEAL';
  evidence.value = [];
  showDispute.value = true;
}

function openRefund() {
  refundMode.value = true;
  disputeReason.value = '申请退回本单已扣款项';
  selectedCategory.value = 'USER_APPEAL';
  evidence.value = [];
  showDispute.value = true;
}

function closeDispute() {
  showDispute.value = false;
}

function pickChip(chip: DisputeReasonChip) {
  selectedCategory.value = chip.category;
  disputeReason.value = appendChipToReason(disputeReason.value, chip);
}

async function onAddEvidence() {
  evidence.value = await pickAndUploadEvidence(evidence.value);
}

function removeEvidence(idx: number) {
  evidence.value = removeEvidenceAt(evidence.value, idx);
}

async function submitAction() {
  if (refundMode.value) await submitRefund();
  else await submitDispute();
}

async function submitDispute() {
  const sessionId = order.value?.sessionId;
  const reason = disputeReason.value.trim();
  if (!sessionId) {
    uni.showToast({ title: '缺少订单信息', icon: 'none' });
    return;
  }
  if (reason.length < 4) {
    uni.showToast({ title: '请至少填写 4 个字', icon: 'none' });
    return;
  }
  if (evidence.value.some((e) => e.uploading)) {
    uni.showToast({ title: '图片仍在上传', icon: 'none' });
    return;
  }
  disputeLoading.value = true;
  try {
    await consumerApi.fileDispute({
      sessionId,
      reason,
      category: selectedCategory.value || 'USER_APPEAL',
      priority: 'NORMAL',
      evidenceFileIds: evidenceFileIds(evidence.value)
    });
    disputeFiled.value = true;
    showDispute.value = false;
    uni.showToast({ title: '申诉已提交', icon: 'success' });
    await reload();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '提交失败', icon: 'none' });
  } finally {
    disputeLoading.value = false;
  }
}

async function submitRefund() {
  const oid = order.value?.orderId;
  const reason = disputeReason.value.trim();
  if (!oid) {
    uni.showToast({ title: '缺少订单编号', icon: 'none' });
    return;
  }
  if (reason.length < 4) {
    uni.showToast({ title: '请至少填写 4 字退款原因', icon: 'none' });
    return;
  }
  if (evidence.value.some((e) => e.uploading)) {
    uni.showToast({ title: '图片仍在上传', icon: 'none' });
    return;
  }
  const confirmed = await new Promise<boolean>((resolve) =>
    uni.showModal({
      title: '确认退款',
      content: '将立即原路退回本单金额，是否继续？',
      confirmText: '确认退款',
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false)
    })
  );
  if (!confirmed) return;
  refundLoading.value = true;
  try {
    const result = await consumerApi.refundOrder(oid, {
      reason,
      evidenceFileIds: evidenceFileIds(evidence.value)
    });
    refundDone.value = true;
    disputeFiled.value = true;
    showDispute.value = false;
    uni.showToast({ title: result.message || '退款成功', icon: 'success' });
    await reload();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '退款失败', icon: 'none' });
  } finally {
    refundLoading.value = false;
  }
}

function reopenCabinet() {
  const id = order.value?.deviceId;
  if (!id) {
    uni.showToast({ title: '缺少柜机编号', icon: 'none' });
    return;
  }
  uni.setStorageSync('reopen_device_id', id);
  uni.switchTab({ url: '/pages/index/index' });
}

function goHelp() {
  uni.navigateTo({ url: '/pages/help/help' });
}

function callSupport() {
  uni.makePhoneCall({
    phoneNumber: supportPhoneDial.value,
    fail: () => uni.showToast({ title: `请拨打 ${supportPhoneDisplay.value}`, icon: 'none' })
  });
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f7f7f7; min-height: 100vh; box-sizing: border-box; }
.loading, .error { text-align: center; padding: 80rpx 0; color: #999; font-size: 28rpx; }
.empty-lines { font-size: 26rpx; color: #999; padding: 12rpx 0; }
.status-bar { display: flex; align-items: center; background: #fff; border-radius: 16rpx; padding: 30rpx; margin-bottom: 20rpx; }
.status-bar.status-paid, .status-bar.status-completed { background: linear-gradient(135deg, #e8f5e9, #fff); }
.status-bar.status-refunded { background: linear-gradient(135deg, #fff3e0, #fff); }
.status-icon { width: 60rpx; height: 60rpx; border-radius: 30rpx; display: flex; align-items: center; justify-content: center; font-size: 32rpx; font-weight: 700; margin-right: 20rpx; background: linear-gradient(135deg, #059669, #0d9488); color: #fff; flex-shrink: 0; }
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
.total-row.pay .total-amount { color: #059669; font-size: 34rpx; }
.total-label { font-size: 28rpx; font-weight: 600; }
.total-amount { font-size: 36rpx; font-weight: 700; color: #ff3b30; }
.points-row { display: flex; justify-content: space-between; padding: 10rpx 0 4rpx; }
.points-label { font-size: 26rpx; color: #849087; }
.points-amount { font-size: 28rpx; font-weight: 700; color: #d97706; }
.discount-row { display: flex; justify-content: space-between; padding: 8rpx 0; }
.discount-label { font-size: 24rpx; color: #059669; }
.discount-amount { font-size: 24rpx; color: #059669; }
.info-row { display: flex; justify-content: space-between; padding: 12rpx 0; }
.info-label { font-size: 26rpx; color: #666; }
.info-value { font-size: 26rpx; color: #333; }
.mono { font-family: monospace; font-size: 22rpx; }
.actions { display: flex; flex-direction: column; gap: 20rpx; padding: 10rpx 0; }
.btn-primary {
  width: 100%;
  display: block;
  box-sizing: border-box;
  height: 88rpx;
  line-height: 88rpx;
  border: none;
  color: #fff;
  border-radius: 44rpx;
  background: linear-gradient(135deg, #059669, #0d9488);
  font-size: 28rpx;
  font-weight: 600;
  text-align: center;
  box-shadow: 0 8rpx 24rpx rgba(5, 150, 105, 0.22);
}
.btn-outline {
  width: 100%;
  display: block;
  box-sizing: border-box;
  height: 80rpx;
  line-height: 80rpx;
  border: 2rpx solid #059669;
  color: #059669;
  border-radius: 44rpx;
  background: #fff;
  font-size: 28rpx;
  font-weight: 600;
  text-align: center;
}
.btn-outline.danger { border-color: #ef4444; color: #b91c1c; }
.btn-refund {
  width: 100%;
  display: block;
  box-sizing: border-box;
  height: 88rpx;
  line-height: 88rpx;
  border: none;
  color: #fff;
  border-radius: 44rpx;
  background: linear-gradient(135deg, #dc2626, #ef4444);
  font-size: 28rpx;
  font-weight: 600;
  text-align: center;
  box-shadow: 0 8rpx 24rpx rgba(239, 68, 68, 0.22);
}
.btn-primary::after, .btn-outline::after, .btn-refund::after, .btn-submit::after { border: none; }
.support { text-align: center; padding: 30rpx; color: #059669; font-size: 24rpx; }
.dispute-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 100; display: flex; align-items: flex-end; }
.dispute-panel { width: 100%; max-height: 90vh; overflow-y: auto; background: #fff; border-radius: 24rpx 24rpx 0 0; padding: 32rpx 28rpx calc(32rpx + env(safe-area-inset-bottom)); box-sizing: border-box; }
.dispute-title { font-size: 34rpx; font-weight: 700; display: block; }
.dispute-sub { font-size: 24rpx; color: #888; display: block; margin: 12rpx 0 20rpx; line-height: 1.5; }
.chip-row { display: flex; flex-wrap: wrap; gap: 12rpx; margin-bottom: 16rpx; }
.reason-chip { padding: 10rpx 18rpx; border-radius: 999rpx; background: #f3f4f6; color: #374151; font-size: 24rpx; border: 1rpx solid transparent; }
.reason-chip.on { background: #fef2f2; color: #b91c1c; border-color: #fecaca; }
.dispute-input { width: 100%; min-height: 140rpx; background: #f7f7f7; border-radius: 12rpx; padding: 20rpx; box-sizing: border-box; font-size: 28rpx; margin-bottom: 16rpx; }
.evidence-block { margin-bottom: 20rpx; }
.evidence-label { display: block; font-size: 24rpx; color: #6b7280; margin-bottom: 12rpx; }
.evidence-row { display: flex; flex-wrap: wrap; gap: 16rpx; }
.evidence-item { position: relative; width: 140rpx; height: 140rpx; }
.evidence-img { width: 140rpx; height: 140rpx; border-radius: 12rpx; background: #f3f4f6; }
.evidence-del { position: absolute; top: -8rpx; right: -8rpx; width: 36rpx; height: 36rpx; border-radius: 50%; background: #111; color: #fff; text-align: center; line-height: 36rpx; font-size: 24rpx; }
.evidence-uploading { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,.45); color: #fff; font-size: 22rpx; border-radius: 12rpx; }
.evidence-add { width: 140rpx; height: 140rpx; border-radius: 12rpx; border: 2rpx dashed #d1d5db; color: #9ca3af; font-size: 48rpx; display: flex; align-items: center; justify-content: center; }
.btn-submit { width: 100%; height: 88rpx; line-height: 88rpx; background: #ef4444; color: #fff; border-radius: 44rpx; font-size: 30rpx; border: none; }
.dispute-cancel { display: block; text-align: center; color: #888; margin-top: 20rpx; font-size: 28rpx; padding: 8rpx; }
</style>
