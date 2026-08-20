<template>
  <view class="page-root">
    <app-nav-bar title="账单结果" />
    <view v-if="loading" class="card"><text class="meta">加载中…</text></view>
    <view v-else-if="error" class="card error-card">
      <text class="err">{{ error }}</text>
      <button class="action-btn" hover-class="btn-hover" @click="goHome">回首页</button>
      <button class="ghost-btn" hover-class="btn-hover" @click="goOrders">查看订单</button>
    </view>
    <view v-else-if="order">
      <view class="status-header" :class="'tone-' + statusTone">
        <text class="status-icon">{{ statusIcon }}</text>
        <view class="status-copy">
          <text class="status-title">{{ headerTitle }}</text>
          <text class="status-detail">{{ statusLabel }}</text>
        </view>
      </view>

      <view class="card amount-card">
        <text class="amount-label">实付金额</text>
        <text class="amount">{{ fmtMoney(order.totalAmountCents) }}</text>
        <text v-if="payChannelText" class="pay-channel">{{ payChannelText }}</text>
        <text v-if="order.totalAmountCents <= 0" class="zero-hint">本次未取走商品，未产生扣款</text>
      </view>
      <view
        v-if="order.balanceBeforeCents != null && order.balanceAfterCents != null"
        class="card balance-card"
      >
        <view
          ><text class="balance-caption">扣款前余额</text
          ><text class="balance-number">{{ fmtMoney(order.balanceBeforeCents) }}</text></view
        >
        <text class="balance-arrow">→</text>
        <view
          ><text class="balance-caption">扣款后余额</text
          ><text class="balance-number strong">{{ fmtMoney(order.balanceAfterCents) }}</text></view
        >
        <text class="trial-note">本页余额为账户余额展示；免密渠道扣款以微信/支付宝为准</text>
      </view>

      <view class="card">
        <text class="section-title">商品明细</text>
        <view v-if="order.lines?.length">
          <view v-for="(line, i) in order.lines" :key="i" class="line">
            <text class="line-name">{{ line.skuName || line.skuId }} × {{ line.quantity }}</text>
            <text class="line-amt">{{ fmtMoney(line.lineAmountCents) }}</text>
          </view>
        </view>
        <text v-else class="empty-lines">本次未识别到取走商品</text>
        <view
          v-if="
            order.originalAmountCents != null &&
            order.originalAmountCents !== order.totalAmountCents
          "
          class="sum-row"
        >
          <text class="sum-label">商品合计</text>
          <text class="sum-value">{{ fmtMoney(order.originalAmountCents) }}</text>
        </view>
        <view v-if="order.couponDiscountCents" class="sum-row discount">
          <text class="sum-label">优惠券抵扣</text>
          <text class="sum-value">-{{ fmtMoney(order.couponDiscountCents) }}</text>
        </view>
        <text v-if="order.couponDiscountCents" class="coupon-hint">已自动选用最优优惠券</text>
      </view>

      <view class="footer-actions">
        <button class="action-btn" hover-class="btn-hover" @click="continueShop">返回本柜</button>
        <button class="ghost-btn" hover-class="btn-hover" @click="goOrders">查看订单</button>

        <view v-if="sessionId && !disputeFiled && !refundDone" class="secondary-actions">
          <text class="secondary-link warn" @click="openDispute">账单有问题</text>
          <text v-if="canRefundNow" class="secondary-dot">·</text>
          <text v-if="canRefundNow" class="secondary-link danger" @click="openRefund"
            >申请退款</text
          >
          <text class="secondary-dot">·</text>
          <text class="secondary-link" @click="goHelp">帮助</text>
        </view>
        <text v-else-if="disputeFiled && !refundDone" class="dispute-done"
          >申诉已提交，请在「订单」查看进度</text
        >
        <text v-else-if="refundDone" class="dispute-done">退款已完成</text>
      </view>
    </view>
    <view v-else class="card btn-stack">
      <text class="empty-title">暂无结算结果</text>
      <text class="empty-desc">订单尚未生成或已失效，可回首页继续购物，或到订单列表查看</text>
      <button class="action-btn" hover-class="btn-hover" @click="goHome">回首页</button>
      <button class="ghost-btn" hover-class="btn-hover" @click="goOrders">查看订单</button>
    </view>

    <view v-if="showDispute" class="dispute-mask" @click="closeDispute">
      <view class="dispute-panel" @click.stop>
        <text class="dispute-title">{{ refundMode ? '立即退款' : '账单申诉' }}</text>
        <text class="dispute-sub">
          {{
            refundMode
              ? '将原路退回本单已扣款项，可上传凭证图片'
              : '提交申诉后由运营审核；可上传凭证图片'
          }}
        </text>
        <view class="chip-row">
          <text
            v-for="chip in reasonChips"
            :key="chip.label"
            class="reason-chip"
            :class="{ on: selectedCategory === chip.category }"
            @click="pickChip(chip)"
            >{{ chip.label }}</text
          >
        </view>
        <text class="field-label">申诉说明</text>
        <textarea
          v-model="disputeReason"
          class="dispute-input"
          maxlength="200"
          aria-label="申诉说明"
          placeholder="例如：我没有拿这个商品 / 数量不对…"
        />
        <view class="evidence-block">
          <text class="evidence-label">申诉附图（选填）</text>
          <view class="evidence-row">
            <view v-for="(img, idx) in evidence" :key="img.localPath + idx" class="evidence-item">
              <image
                class="evidence-img"
                :src="previewEvidenceSrc(img)"
                mode="aspectFill"
                :aria-label="`证据图 ${idx + 1}`"
              />
              <text
                class="evidence-del"
                role="button"
                aria-label="删除证据图"
                @click="removeEvidence(idx)"
                >×</text
              >
              <text v-if="img.uploading" class="evidence-uploading">上传中…</text>
            </view>
            <view
              v-if="evidence.length < 5"
              class="evidence-add"
              role="button"
              aria-label="添加证据图"
              @click="onAddEvidence"
              >+</view
            >
          </view>
        </view>
        <button
          class="action-btn refund-submit"
          hover-class="btn-hover"
          :loading="disputeLoading || refundLoading"
          :disabled="disputeLoading || refundLoading"
          @click="submitAction"
        >
          {{
            refundMode
              ? refundLoading
                ? '退款中…'
                : '确认退款'
              : disputeLoading
                ? '提交中…'
                : '提交申诉'
          }}
        </button>
        <text class="dispute-cancel" @click="closeDispute">取消</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { displayLabel } from '@aicabinet/shared-dict';
import { consumerApi } from '@/utils/consumer-api';
import { fmtMoney, orderStatusLabel } from '@aicabinet/shared-uni/format';
import { parseQuery } from '@aicabinet/shared-uni/query';
import type { OrderDetailDto } from '@aicabinet/shared-types';
import {
  DISPUTE_REASON_CHIPS,
  appendChipToReason,
  type DisputeReasonChip
} from '@/utils/dispute-form';
import { consumerAppealErrorMessage } from '@/utils/dispute-copy';
import {
  pickAndUploadEvidence,
  evidenceFileIds,
  previewEvidenceSrc,
  removeEvidenceAt,
  type LocalEvidence
} from '@/utils/dispute-evidence';

const loading = ref(true);
const error = ref('');
const order = ref<OrderDetailDto | null>(null);
const statusLabel = ref('');
const statusTone = computed(() => {
  const s = (order.value?.status || '').toUpperCase();
  if (s === 'DISPUTED') return 'warn';
  if (s === 'REFUNDED' || s === 'PARTIAL_REFUNDED') return 'refund';
  if (s === 'FAILED' || s === 'CANCELLED') return 'muted';
  if (s === 'PENDING' || s === 'PROCESSING') return 'pending';
  return 'ok';
});
const statusIcon = computed(() => {
  const map: Record<string, string> = {
    ok: '✓',
    warn: '!',
    refund: '↩',
    muted: '无',
    pending: '…'
  };
  return map[statusTone.value] || '✓';
});
const headerTitle = computed(() => {
  const s = (order.value?.status || '').toUpperCase();
  if (s === 'DISPUTED') return '账单审核中';
  if (s === 'REFUNDED' || s === 'PARTIAL_REFUNDED') return '退款已处理';
  if (s === 'PENDING' || s === 'PROCESSING') return '待支付';
  if (s === 'FAILED' || s === 'CANCELLED') return '本次未完成';
  return (order.value?.totalAmountCents || 0) > 0 ? '购物完成' : '感谢使用';
});
let sessionId = '';
let loadedKey = '';
const deviceId = ref('');
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

const canRefundNow = computed(
  () =>
    !!order.value?.orderId &&
    !refundDone.value &&
    !disputeFiled.value &&
    (order.value?.totalAmountCents || 0) > 0 &&
    order.value?.refundPolicy !== 'DISPUTE_ONLY' &&
    ['PAID', 'COMPLETED'].includes(String(order.value?.status || ''))
);

const payChannelText = computed(() => {
  const ch = String(order.value?.payChannel || '').toUpperCase();
  if (!ch) return '';
  return displayLabel('pay_channel', ch, '');
});

function currentPageOptions(): Record<string, string> {
  const pages = getCurrentPages();
  const cur = pages[pages.length - 1] as { options?: Record<string, string> } | undefined;
  return cur?.options || {};
}

onLoad((opts) => {
  void bootstrap(opts as Record<string, string>);
});

onShow(() => {
  // H5 同页换 query 时 onLoad 不重跑；微信 onShow 无入参，空 query 不得冲掉已有单号
  const merged = { ...readHashQuery(), ...currentPageOptions() };
  if (!String(merged.sessionId || merged.orderId || '').trim()) {
    if (sessionId || order.value?.orderId) {
      merged.sessionId = sessionId;
      merged.orderId = String(order.value?.orderId || '');
    } else {
      return;
    }
  }
  void bootstrap(merged);
});

function readHashQuery(): Record<string, string> {
  // #ifdef H5
  try {
    const hash = window.location.hash || '';
    const q = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : '';
    const params = parseQuery(q);
    return {
      sessionId: params.sessionId || '',
      orderId: params.orderId || ''
    };
  } catch {
    return {};
  }
  // #endif
  // #ifndef H5
  return {};
  // #endif
}

async function bootstrap(opts?: Record<string, string>) {
  const nextSession = String(opts?.sessionId || sessionId || '').trim();
  const nextOrder = String(opts?.orderId || order.value?.orderId || '').trim();
  const key = `${nextOrder}|${nextSession}`;
  if (!nextOrder && !nextSession) {
    error.value = '缺少订单信息';
    loading.value = false;
    return;
  }
  if (key === loadedKey && (order.value || error.value)) return;
  loadedKey = key;
  sessionId = nextSession;
  order.value = null;
  error.value = '';
  disputeFiled.value = false;
  refundDone.value = false;
  showDispute.value = false;
  loading.value = true;
  if (nextOrder) {
    await loadByOrderId(nextOrder);
    return;
  }
  await loadBySession(nextSession);
}

async function loadBySession(sid: string) {
  try {
    const sess = await consumerApi.getSession(sid);
    deviceId.value = sess.deviceId || '';
    order.value = await consumerApi.getSessionOrder(sid);
    statusLabel.value = orderStatusLabel(order.value?.status);
    if (order.value?.status === 'DISPUTED') disputeFiled.value = true;
    if (order.value?.status === 'REFUNDED') {
      refundDone.value = true;
      disputeFiled.value = true;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function loadByOrderId(oid: string) {
  try {
    order.value = await consumerApi.getOrder(oid);
    statusLabel.value = orderStatusLabel(order.value?.status);
    sessionId = order.value?.sessionId || sessionId;
    deviceId.value = order.value?.deviceId || deviceId.value;
    if (order.value?.status === 'DISPUTED') disputeFiled.value = true;
    if (order.value?.status === 'REFUNDED') {
      refundDone.value = true;
      disputeFiled.value = true;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
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

async function removeEvidence(idx: number) {
  const confirmed = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '删除图片',
      content: '确定删除这张申诉附图吗？',
      confirmText: '删除',
      cancelText: '保留',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    });
  });
  if (!confirmed) return;
  evidence.value = removeEvidenceAt(evidence.value, idx);
}

async function submitAction() {
  if (refundMode.value) await submitRefund();
  else await submitDispute();
}

async function submitDispute() {
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
    // 刷新订单状态，让页头切到「争议中」而不是仍显示购物完成
    loadedKey = '';
    if (order.value?.orderId) {
      await loadByOrderId(order.value.orderId);
    } else if (sessionId) {
      await loadBySession(sessionId);
    }
    uni.showToast({ title: '申诉已提交', icon: 'success' });
  } catch (e) {
    uni.showToast({ title: consumerAppealErrorMessage(e, '提交失败'), icon: 'none' });
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
    statusLabel.value = '已退款';
    uni.showToast({ title: result.message || '退款成功', icon: 'success' });
  } catch (e) {
    uni.showToast({ title: consumerAppealErrorMessage(e, '退款失败'), icon: 'none' });
  } finally {
    refundLoading.value = false;
  }
}

function continueShop() {
  const id = deviceId.value || order.value?.deviceId;
  if (id) {
    uni.setStorageSync('browse_device_id', id);
    uni.setStorageSync('last_device_id', id);
  }
  uni.switchTab({ url: '/pages/index/index' });
}

function goHome() {
  uni.switchTab({ url: '/pages/index/index' });
}

function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}

function goHelp() {
  uni.navigateTo({ url: '/pages/help/help' });
}
</script>

<style scoped>
.page-root {
  min-height: 100%;
  background: #ffffff;
  box-sizing: border-box;
}
.status-header {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin: 24rpx 24rpx 0;
  padding: 30rpx;
  border-radius: 20rpx;
  background: linear-gradient(135deg, #e8f5e9, #fff);
  box-sizing: border-box;
}
.status-header.tone-warn {
  background: linear-gradient(135deg, #fff7ed, #fff);
}
.status-header.tone-refund {
  background: linear-gradient(135deg, #eff6ff, #fff);
}
.status-header.tone-pending {
  background: linear-gradient(135deg, #fefce8, #fff);
}
.status-header.tone-muted {
  background: linear-gradient(135deg, #f1f5f9, #fff);
}
.status-icon {
  width: 64rpx;
  height: 64rpx;
  border-radius: 32rpx;
  background: linear-gradient(135deg, #047857, #059669);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32rpx;
  font-weight: 700;
  flex-shrink: 0;
}
.status-header.tone-warn .status-icon {
  background: linear-gradient(135deg, #ea580c, #f59e0b);
}
.status-header.tone-refund .status-icon {
  background: linear-gradient(135deg, #2563eb, #38bdf8);
}
.status-header.tone-pending .status-icon {
  background: linear-gradient(135deg, #ca8a04, #eab308);
}
.status-header.tone-muted .status-icon {
  background: linear-gradient(135deg, #64748b, #94a3b8);
}
.status-copy {
  flex: 1;
  min-width: 0;
}
.status-title {
  font-size: 32rpx;
  font-weight: 700;
  color: #191919;
  display: block;
}
.status-detail {
  font-size: 24rpx;
  color: #666;
  display: block;
  margin-top: 4rpx;
}
.amount-card {
  text-align: center;
  margin: 20rpx 24rpx 18rpx;
  padding: 34rpx;
  border-radius: 24rpx;
  box-shadow: none;
  border: 1rpx solid #edf1ef;
}
.amount-label {
  font-size: 24rpx;
  color: #64748b;
  display: block;
}
.amount {
  font-size: 66rpx;
  font-weight: 800;
  color: #047857;
  letter-spacing: -2rpx;
  display: block;
  margin-top: 4rpx;
}
.pay-channel {
  font-size: 24rpx;
  color: #64748b;
  display: block;
  margin-top: 12rpx;
}
.zero-hint {
  font-size: 24rpx;
  color: #888;
  display: block;
  margin-top: 12rpx;
}
.balance-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  border-radius: 24rpx;
}
.balance-caption {
  display: block;
  font-size: 23rpx;
  color: #888;
}
.balance-number {
  display: block;
  margin-top: 6rpx;
  font-size: 30rpx;
  color: #555;
}
.balance-number.strong {
  color: #07c160;
  font-weight: 700;
}
.balance-arrow {
  color: #bbb;
}
.trial-note {
  width: 100%;
  margin-top: 18rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid #eee;
  font-size: 22rpx;
  color: #ad6800;
}
.section-title {
  font-size: 29rpx;
  font-weight: 600;
  color: #26342d;
  display: block;
  margin-bottom: 12rpx;
}
.line {
  display: flex;
  justify-content: space-between;
  padding: 18rpx 0;
  border-bottom: 1px solid #f1f5f9;
}
.line-name {
  color: #1e293b;
  font-weight: 600;
}
.line-amt {
  color: #07c160;
  font-weight: 600;
}
.empty-lines {
  font-size: 26rpx;
  color: #888;
}
.sum-row {
  display: flex;
  justify-content: space-between;
  padding: 14rpx 0 0;
  margin-top: 8rpx;
}
.sum-row.discount .sum-value {
  color: #d97706;
  font-weight: 600;
}
.sum-row.points .sum-value {
  color: #059669;
  font-weight: 700;
}
.sum-label {
  font-size: 26rpx;
  color: #64748b;
}
.sum-value {
  font-size: 28rpx;
  color: #1e293b;
  font-weight: 600;
}
.coupon-hint {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #ad6800;
}
.footer-actions {
  padding: 20rpx 24rpx 38rpx;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
}
.secondary-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8rpx;
  padding: 12rpx 0 8rpx;
}
.secondary-link {
  font-size: 26rpx;
  color: #64748b;
  padding: 8rpx;
}
.secondary-link.warn {
  color: #d48806;
}
.secondary-link.danger {
  color: #ef4444;
}
.secondary-dot {
  color: #cbd5e1;
  font-size: 26rpx;
}
.action-btn {
  margin: 0;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: linear-gradient(135deg, #047857, #059669);
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
  font-weight: 700;
  box-shadow: 0 10rpx 26rpx rgba(5, 150, 105, 0.22);
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  width: 100%;
  box-sizing: border-box;
}
.action-btn::after {
  border: none;
}
.ghost-btn {
  margin: 0;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: #fff;
  color: #53645b;
  border: 1rpx solid #e4ebe7;
  border-radius: 44rpx;
  font-size: 30rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  width: 100%;
  box-sizing: border-box;
}
.ghost-btn::after {
  border: none;
}
.ghost-btn.warn {
  color: #d48806;
  border: 1rpx solid #ffd591;
  background: #fffbeb;
}
.ghost-btn.subtle {
  color: #999;
  font-size: 28rpx;
}
.refund-btn {
  margin: 0;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: #ef4444;
  color: #fff;
  border-radius: 12rpx;
  font-size: 30rpx;
  font-weight: 600;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  width: 100%;
  box-sizing: border-box;
}
.refund-btn::after {
  border: none;
}
.refund-submit {
  background: #ef4444;
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.reason-chip {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: #f3f4f6;
  color: #374151;
  font-size: 24rpx;
  border: 1rpx solid transparent;
}
.reason-chip.on {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fecaca;
}
.evidence-block {
  margin-bottom: 16rpx;
}
.evidence-label {
  display: block;
  font-size: 24rpx;
  color: #888;
  margin-bottom: 10rpx;
}
.evidence-row {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}
.evidence-item {
  position: relative;
  width: 120rpx;
  height: 120rpx;
}
.evidence-img {
  width: 120rpx;
  height: 120rpx;
  border-radius: 10rpx;
  background: #f3f4f6;
}
.evidence-del {
  position: absolute;
  top: -8rpx;
  right: -8rpx;
  width: 32rpx;
  height: 32rpx;
  border-radius: 50%;
  background: #111;
  color: #fff;
  text-align: center;
  line-height: 32rpx;
  font-size: 22rpx;
}
.evidence-uploading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: 20rpx;
  border-radius: 10rpx;
}
.evidence-add {
  width: 120rpx;
  height: 120rpx;
  border-radius: 10rpx;
  border: 2rpx dashed #d1d5db;
  color: #9ca3af;
  font-size: 40rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.dispute-done {
  text-align: center;
  font-size: 26rpx;
  color: #07c160;
  padding: 8rpx 0;
}
.btn-hover {
  opacity: 0.85;
}
.err {
  color: #fa5151;
  display: block;
  margin-bottom: 24rpx;
  text-align: center;
}
.error-card {
  margin: 24rpx;
  padding: 40rpx 28rpx;
  text-align: center;
}
.error-card .action-btn {
  margin-top: 12rpx;
}
.error-card .ghost-btn {
  margin-top: 16rpx;
}

.dispute-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 300;
  display: flex;
  align-items: flex-end;
}
.dispute-panel {
  width: 100%;
  max-width: 520px;
  margin: 0 auto;
  background: #fff;
  border-radius: 30rpx 30rpx 0 0;
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  max-height: 90vh;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.dispute-title {
  font-size: 34rpx;
  font-weight: 700;
  display: block;
  text-align: center;
}
.dispute-sub {
  font-size: 26rpx;
  color: #888;
  display: block;
  text-align: center;
  margin: 12rpx 0 24rpx;
}
.dispute-input {
  width: 100%;
  min-height: 180rpx;
  background: #f8faf9;
  border: 1rpx solid #e4ebe7;
  border-radius: 12rpx;
  padding: 20rpx;
  font-size: 28rpx;
  box-sizing: border-box;
  margin-bottom: 20rpx;
}
.dispute-cancel {
  display: block;
  text-align: center;
  color: #888;
  font-size: 28rpx;
  margin-top: 16rpx;
  padding: 12rpx;
}
</style>
