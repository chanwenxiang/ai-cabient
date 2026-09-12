<template>
  <view class="page-root">
    <app-nav-bar title="账单结果" />
    <view v-if="loading" class="card"><text class="meta">{{ UI_COPY.loading }}</text></view>
    <view v-else-if="error" class="card error-card">
      <text class="err">{{ error }}</text>
      <app-button label="回首页" @click="goHome" />
      <app-button variant="ghost" label="查看订单" @click="goOrders" />
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
        <text class="trial-note">账户余额仅供参考；免密支付以微信/支付宝账单为准</text>
      </view>

      <view class="card">
        <text class="section-title">商品明细</text>
        <view v-if="order.lines?.length">
          <view v-for="(line, i) in order.lines" :key="i" class="line">
            <view class="line-main">
              <text class="line-name">{{ line.skuName || line.skuId }} × {{ line.quantity }}</text>
              <text v-if="lineMeta(line)" class="line-meta">{{ lineMeta(line) }}</text>
              <text v-if="line.unitPriceCents != null" class="line-unit"
                >单价 {{ fmtMoney(line.unitPriceCents) }}</text
              >
            </view>
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
        <view v-if="Number(order.memberDiscountCents ?? 0) > 0" class="sum-row discount">
          <text class="sum-label">会员优惠</text>
          <text class="sum-value">减{{ fmtMoney(order.memberDiscountCents) }}</text>
        </view>
        <view v-if="order.couponDiscountCents != null" class="sum-row discount">
          <text class="sum-label">优惠券抵扣</text>
          <text class="sum-value">减{{ fmtMoney(order.couponDiscountCents) }}</text>
        </view>
        <text
          v-if="order.couponDiscountCents != null || Number(order.memberDiscountCents ?? 0) > 0"
          class="coupon-hint"
          >{{ discountHint }}</text
        >
      </view>

      <view class="card info-card">
        <view class="info-row">
          <text class="info-label">订单编号</text>
          <text class="info-value">{{ order.orderId }}</text>
        </view>
        <view v-if="order.deviceId || order.deviceName" class="info-row">
          <text class="info-label">柜机</text>
          <text class="info-value">{{ order.deviceName || order.deviceId }}</text>
        </view>
        <view v-if="order.payTime" class="info-row">
          <text class="info-label">扣款时间</text>
          <text class="info-value">{{ formatPayTime(order.payTime) }}</text>
        </view>
        <view
          v-if="
            order.refundedAt ||
            order.status === 'REFUNDED' ||
            order.status === 'PARTIAL_REFUNDED' ||
            refundCents(order) > 0
          "
          class="info-row"
        >
          <text class="info-label">退款</text>
          <text class="info-value warn"
            >{{ order.status === 'PARTIAL_REFUNDED' ? '部分退款' : '已退款'
            }}{{ refundCents(order) > 0 ? ` ${fmtMoney(refundCents(order))}` : ''
            }}{{ order.refundedAt ? ` · ${formatPayTime(order.refundedAt)}` : '' }}</text
          >
        </view>
      </view>

      <view class="footer-actions">
        <app-button label="返回本柜" @click="continueShop" />
        <app-button variant="ghost" label="查看订单" @click="goOrders" />

        <view v-if="sessionId && !disputeFiled && !refundDone" class="secondary-actions">
          <text role="button" class="secondary-link warn" @click="openDispute">账单有问题</text>
          <text v-if="canRefundNow" class="secondary-dot">·</text>
          <text v-if="canRefundNow" role="button" class="secondary-link danger" @click="openRefund"
            >申请退款</text
          >
          <text class="secondary-dot">·</text>
          <text role="button" class="secondary-link" @click="goHelp">帮助</text>
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
      <app-button label="回首页" @click="goHome" />
      <app-button variant="ghost" label="查看订单" @click="goOrders" />
    </view>

    <view v-if="showDispute" role="button" aria-label="关闭" class="dispute-mask" @click="closeDispute">
      <view role="button" class="dispute-panel" @click.stop>
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
            v-for="chip in reasonChips" role="button"
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
        <app-button
          :loading="disputeLoading || refundLoading"
          :disabled="disputeLoading || refundLoading"
          :label="
            refundMode
              ? refundLoading
                ? '退款中…'
                : '确认退款'
              : disputeLoading
                ? '提交中…'
                : '提交申诉'
          "
          @click="submitAction"
        />
        <text role="button" class="dispute-cancel" aria-label="取消申诉" @click="closeDispute">取消</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app';
import {
  showError,
  showSuccess,
  showConfirm
} from '@/utils/notify';
import { computed, ref } from 'vue';
import { displayLabel } from '@aicabinet/shared-dict';
import { consumerApi } from '@/utils/consumer-api';
import { fmtMoney, formatDateTimeMinute, orderStatusLabel } from '@aicabinet/shared-uni/format';
import { parseQuery } from '@aicabinet/shared-uni/query';
import type { OrderDetailDto, OrderLineDto } from '@aicabinet/shared-types';
import {
  DISPUTE_REASON_CHIPS,
  appendChipToReason,
  inferRestoreInventory,
  type DisputeReasonChip
} from '@/utils/dispute-form';
import { consumerAppealErrorMessage } from '@/utils/dispute-copy';
import {
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
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
  return Number(order.value?.totalAmountCents ?? 0) > 0 ? '购物完成' : '感谢使用';
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
const selectedChip = ref<DisputeReasonChip | null>(null);
const evidence = ref<LocalEvidence[]>([]);

const canRefundNow = computed(
  () =>
    !!order.value?.orderId &&
    !refundDone.value &&
    !disputeFiled.value &&
    Number(order.value?.totalAmountCents ?? 0) > 0 &&
    order.value?.refundPolicy !== 'DISPUTE_ONLY' &&
    ['PAID', 'COMPLETED'].includes(String(order.value?.status || ''))
);

const payChannelText = computed(() => {
  const ch = String(order.value?.payChannel || '').toUpperCase();
  if (!ch) return '';
  return displayLabel('pay_channel', ch, '');
});

const discountHint = computed(() => {
  const hasCoupon = order.value?.couponDiscountCents != null;
  const hasMember = Number(order.value?.memberDiscountCents ?? 0) > 0;
  if (hasCoupon && hasMember) return '已自动抵扣会员价与优惠券';
  if (hasMember) return '已享受会员优惠';
  if (hasCoupon) return '已自动选用最优优惠券';
  return '';
});

function lineMeta(line: OrderLineDto) {
  const parts: string[] = [];
  if (line.slotId) parts.push(`货道 ${line.slotId}`);
  if (line.batchNo) parts.push(`批次 ${line.batchNo}`);
  return parts.join(' · ');
}

function formatPayTime(t?: string) {
  return formatDateTimeMinute(t, '暂无');
}

function refundCents(o?: OrderDetailDto | null) {
  if (!o) return 0;
  // M-6：只信服务端 refundedCents
  const n = Number(o.refundedCents ?? 0);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

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
    const hash = globalThis.location.hash || '';
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
  selectedChip.value = null;
  evidence.value = [];
  showDispute.value = true;
}

function openRefund() {
  refundMode.value = true;
  disputeReason.value = '申请退回本单已扣款项';
  selectedCategory.value = 'USER_APPEAL';
  selectedChip.value = DISPUTE_REASON_CHIPS.find((c) => c.label === '申请退款') || null;
  evidence.value = [];
  showDispute.value = true;
}

function closeDispute() {
  showDispute.value = false;
}

function pickChip(chip: DisputeReasonChip) {
  selectedCategory.value = chip.category;
  selectedChip.value = chip;
  disputeReason.value = appendChipToReason(disputeReason.value, chip);
}

async function onAddEvidence() {
  evidence.value = await pickAndUploadEvidence(evidence.value, 5, (items) => {
    evidence.value = items;
  });
}

async function removeEvidence(idx: number) {
  const confirmed = await showConfirm({
    title: '删除图片',
    content: '确定删除这张申诉附图吗？',
    confirmText: '删除',
    cancelText: '保留'
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
    showError('缺少订单信息');
    return;
  }
  if (reason.length < 4) {
    showError('请至少填写 4 个字');
    return;
  }
  if (evidence.value.some((e) => e.uploading)) {
    showError('图片仍在上传');
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
    showSuccess('申诉已提交');
  } catch (e) {
    showError(consumerAppealErrorMessage(e, '提交失败'));
  } finally {
    disputeLoading.value = false;
  }
}

async function submitRefund() {
  const oid = order.value?.orderId;
  const reason = disputeReason.value.trim();
  if (!oid) {
    showError('缺少订单编号');
    return;
  }
  if (reason.length < 4) {
    showError('请至少填写 4 字退款原因');
    return;
  }
  if (evidence.value.some((e) => e.uploading)) {
    showError('图片仍在上传');
    return;
  }
  const restoreInventory = inferRestoreInventory(reason, selectedChip.value);
  const confirmed = await showConfirm({
    title: '确认退款',
    content:
      restoreInventory == null
        ? '将立即退款；是否回库由平台规则判定。是否继续？'
        : restoreInventory
          ? '将立即退款，并把本单商品回库（适用于没拿/误识别）。是否继续？'
          : '将立即退款，但库存不回库（货已拿走/仅退款）。是否继续？',
    confirmText: '确认退款'
  });
  if (!confirmed) return;
  refundLoading.value = true;
  try {
    const result = await consumerApi.refundOrder(oid, {
      reason,
      evidenceFileIds: evidenceFileIds(evidence.value),
      ...(restoreInventory != null ? { restoreInventory } : {})
    });
    refundDone.value = true;
    disputeFiled.value = true;
    showDispute.value = false;
    statusLabel.value = '已退款';
    showSuccess(result.message || '退款成功');
  } catch (e) {
    showError(consumerAppealErrorMessage(e, '退款失败'));
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
  background: var(--card-bg, #ffffff);
  box-sizing: border-box;
}
.status-header {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin: 24rpx 24rpx 0;
  padding: 30rpx;
  border-radius: var(--radius-card);
  background: linear-gradient(135deg, var(--brand-soft, #e8f5e9), var(--white));
  box-sizing: border-box;
}
.status-header.tone-warn {
  background: linear-gradient(135deg, color-mix(in srgb, var(--warning, #b45309) 8%, var(--white)), var(--white));
}
.status-header.tone-refund {
  background: linear-gradient(135deg, var(--info-soft), var(--white));
}
.status-header.tone-pending {
  background: linear-gradient(135deg, var(--warning-soft), var(--white));
}
.status-header.tone-muted {
  background: linear-gradient(135deg, var(--color-border-subtle, #f1f5f9), var(--white));
}
.status-icon {
  width: 64rpx;
  height: 64rpx;
  border-radius: var(--radius-card);
  background: linear-gradient(135deg, var(--brand), var(--brand));
  color: var(--white);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-xl);
  font-weight: 700;
  flex-shrink: 0;
}
.status-header.tone-warn .status-icon {
  background: linear-gradient(135deg, var(--accent-orange, #c2410c), var(--warning, #f59e0b));
}
.status-header.tone-refund .status-icon {
  background: linear-gradient(135deg, var(--info), var(--info));
}
.status-header.tone-pending .status-icon {
  background: linear-gradient(135deg, var(--warning), var(--warning));
}
.status-header.tone-muted .status-icon {
  background: linear-gradient(135deg, var(--text-muted), var(--text-subtle));
}
.status-copy {
  flex: 1;
  min-width: 0;
}
.status-title {
  font-size: var(--font-size-xl);
  font-weight: 700;
  color: var(--color-text-primary);
  display: block;
}
.status-detail {
  font-size: var(--font-size-caption);
  color: var(--text-muted, #666);
  display: block;
  margin-top: 4rpx;
}
.amount-card {
  text-align: center;
  margin: 20rpx 24rpx 18rpx;
  padding: 34rpx;
  border-radius: var(--radius-card);
  box-shadow: none;
  border: 1rpx solid var(--color-border-subtle, #edf1ef);
}
.amount-label {
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  display: block;
}
.amount {
  font-size: 66rpx;
  font-weight: 800;
  color: var(--brand);
  letter-spacing: -2rpx;
  display: block;
  margin-top: 4rpx;
}
.pay-channel {
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  display: block;
  margin-top: 12rpx;
}
.zero-hint {
  font-size: var(--font-size-caption);
  color: var(--text-subtle, #888);
  display: block;
  margin-top: 12rpx;
}
.balance-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  border-radius: var(--radius-card);
}
.balance-caption {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #888);
}
.balance-number {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-lg);
  color: var(--text-muted);
}
.balance-number.strong {
  color: var(--brand-wx, #07c160);
  font-weight: 700;
}
.balance-arrow {
  color: var(--text-subtle);
}
.trial-note {
  width: 100%;
  margin-top: 18rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid var(--card-border);
  font-size: var(--font-size-sm);
  color: var(--warning);
}
.section-title {
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary);
  display: block;
  margin-bottom: 12rpx;
}
.line {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16rpx;
  padding: 18rpx 0;
  border-bottom: 1px solid var(--color-border-subtle, #f1f5f9);
}
.line-main {
  flex: 1;
  min-width: 0;
}
.line-name {
  display: block;
  color: var(--text-primary, #1e293b);
  font-weight: 600;
}
.line-meta,
.line-unit {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
}
.line-amt {
  color: var(--brand-wx, #07c160);
  font-weight: 600;
  flex-shrink: 0;
}
.info-card {
  margin-top: 20rpx;
}
.info-row {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
  padding: 12rpx 0;
  border-bottom: 1px solid var(--page-bg, #f8fafc);
}
.info-row:last-child {
  border-bottom: none;
}
.info-label {
  font-size: var(--font-size-caption);
  color: var(--text-muted, #849087);
  flex-shrink: 0;
}
.info-value {
  font-size: var(--font-size-caption);
  color: var(--text-muted, #334155);
  text-align: right;
  word-break: break-all;
}
.info-value.warn {
  color: var(--warning, #b45309);
}
.empty-lines {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #888);
}
.sum-row {
  display: flex;
  justify-content: space-between;
  padding: 14rpx 0 0;
  margin-top: 8rpx;
}
.sum-row.discount .sum-value {
  color: var(--warning, #d97706);
  font-weight: 600;
}
.sum-row.points .sum-value {
  color: var(--brand);
  font-weight: 700;
}
.sum-label {
  font-size: var(--font-size-body);
  color: var(--text-muted);
}
.sum-value {
  font-size: var(--font-size-md);
  color: var(--text-primary, #1e293b);
  font-weight: 600;
}
.coupon-hint {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--warning);
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
  font-size: var(--font-size-body);
  color: var(--text-muted);
  padding: 8rpx;
}
.secondary-link.warn {
  color: var(--warning);
}
.secondary-link.danger {
  color: var(--color-danger);
}
.secondary-dot {
  color: var(--text-subtle, #cbd5e1);
  font-size: var(--font-size-body);
}
.action-btn {
  margin: 0;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: linear-gradient(135deg, var(--brand), var(--brand));
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xl);
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
  background: var(--card-bg, #fff);
  color: var(--text-muted);
  border: 1rpx solid var(--color-border-subtle);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
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
  color: var(--warning, #92400e);
  border: 1rpx solid var(--warning-soft);
  background: color-mix(in srgb, var(--warning, #b45309) 8%, var(--white));
}
.ghost-btn.subtle {
  color: var(--text-subtle, #999);
  font-size: var(--font-size-md);
}
.refund-btn {
  margin: 0;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: var(--color-danger);
  color: var(--white);
  border-radius: var(--radius-control);
  font-size: var(--font-size-lg);
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
  background: var(--color-danger);
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.reason-chip {
  padding: 10rpx 18rpx;
  border-radius: var(--radius-pill);
  background: var(--color-border-subtle);
  color: var(--text-primary);
  font-size: var(--font-size-caption);
  border: 1rpx solid transparent;
}
.reason-chip.on {
  background: color-mix(in srgb, var(--danger, #b91c1c) 8%, var(--white));
  color: var(--color-danger);
  border-color: color-mix(in srgb, var(--danger, #b91c1c) 18%, var(--white));
}
.evidence-block {
  margin-bottom: 16rpx;
}
.evidence-label {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-subtle, #888);
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
  border-radius: var(--radius-tag);
  background: var(--color-border-subtle);
}
.evidence-del {
  position: absolute;
  top: -8rpx;
  right: -8rpx;
  width: 32rpx;
  height: 32rpx;
  border-radius: 50%;
  background: var(--text-primary);
  color: var(--white);
  text-align: center;
  line-height: 32rpx;
  font-size: var(--font-size-sm);
}
.evidence-uploading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  color: var(--white);
  font-size: var(--font-size-xs);
  border-radius: var(--radius-tag);
}
.evidence-add {
  width: 120rpx;
  height: 120rpx;
  border-radius: var(--radius-tag);
  border: 2rpx dashed var(--card-border);
  color: var(--text-subtle);
  font-size: var(--font-size-h2);
  display: flex;
  align-items: center;
  justify-content: center;
}
.dispute-done {
  text-align: center;
  font-size: var(--font-size-body);
  color: var(--brand-wx, #07c160);
  padding: 8rpx 0;
}
.btn-hover {
  opacity: 0.85;
}
.err {
  color: var(--color-danger);
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
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card) 30rpx 0 0;
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  max-height: 90vh;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.dispute-title {
  font-size: var(--font-size-h3);
  font-weight: 700;
  display: block;
  text-align: center;
}
.dispute-sub {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #888);
  display: block;
  text-align: center;
  margin: 12rpx 0 24rpx;
}
.dispute-input {
  width: 100%;
  min-height: 180rpx;
  background: var(--page-bg, #f8faf9);
  border: 1rpx solid var(--color-border-subtle);
  border-radius: var(--radius-control);
  padding: 20rpx;
  font-size: var(--font-size-md);
  box-sizing: border-box;
  margin-bottom: 20rpx;
}
.dispute-cancel {
  display: block;
  text-align: center;
  color: var(--text-subtle, #888);
  font-size: var(--font-size-md);
  margin-top: 16rpx;
  padding: 12rpx;
}
</style>
