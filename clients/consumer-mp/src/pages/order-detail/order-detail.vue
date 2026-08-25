<template>
  <view class="page-root">
    <app-nav-bar title="订单详情" />
    <view class="page-body">
      <view v-if="loading && !order" class="loading"><text>加载中…</text></view>
      <view v-else-if="error && !order" class="error">
        <text>{{ error }}</text>
        <button class="btn-outline" style="margin-top: 24rpx" @click="reload">重试</button>
      </view>
      <view v-else-if="order">
        <view class="status-bar" :class="'status-' + (order?.status || '').toLowerCase()">
          <text class="status-icon">{{ statusIcon }}</text>
          <view class="status-copy">
            <text class="status-title">{{ statusTitle }}</text>
            <text class="status-detail">{{ statusDetail }}</text>
          </view>
        </view>

        <view class="section">
          <text class="section-title">商品清单</text>
          <view
            v-for="item in order?.lines || []"
            :key="item.skuId + '-' + (item.slotId || '')"
            class="item-row"
          >
            <image
              class="item-thumb"
              :src="skuImageFor(item.skuId, item.skuName)"
              mode="aspectFill"
              aria-hidden="true"
            />
            <view class="item-info">
              <text class="item-name">{{ item.skuName || item.skuId || '商品' }}</text>
              <text class="item-qty"
                >x{{ item.quantity }}{{ item.slotId ? ` · 货道 ${item.slotId}` : ''
                }}{{ item.batchNo ? ` · 批次 ${item.batchNo}` : '' }}</text
              >
              <text v-if="item.unitPriceCents != null" class="item-unit"
                >单价 {{ fmtMoney(item.unitPriceCents) }}</text
              >
            </view>
            <text class="item-price">{{ fmtMoney(item.lineAmountCents) }}</text>
          </view>
          <view v-if="!(order?.lines || []).length" class="empty-lines">本次未识别到取走商品</view>
          <view class="total-row">
            <text class="total-label">商品合计</text>
            <text class="total-amount">{{
              fmtMoney(order?.originalAmountCents || order?.totalAmountCents || 0)
            }}</text>
          </view>
          <view v-if="order?.couponDiscountCents" class="discount-row">
            <text class="discount-label">优惠券抵扣</text>
            <text class="discount-amount">减{{ fmtMoney(order.couponDiscountCents) }}</text>
          </view>
          <view v-if="Number(order?.memberDiscountCents || 0) > 0" class="discount-row">
            <text class="discount-label">会员优惠</text>
            <text class="discount-amount">减{{ fmtMoney(order.memberDiscountCents) }}</text>
          </view>
          <view
            v-if="order?.couponDiscountCents || Number(order?.memberDiscountCents || 0) > 0"
            class="total-row pay"
          >
            <text class="total-label">实付</text>
            <text class="total-amount">{{ fmtMoney(order?.totalAmountCents || 0) }}</text>
          </view>
        </view>

        <view class="section">
          <text class="section-title">支付信息</text>
          <view class="info-row"
            ><text class="info-label">支付方式</text
            ><text class="info-value">{{ payChannelText }}</text></view
          >
          <view v-if="order?.payTradeNo || order?.paymentOperationId" class="info-row"
            ><text class="info-label">流水号</text
            ><text class="info-value mono">{{
              displayBizNo(order?.payTradeNo || order?.paymentOperationId)
            }}</text></view
          >
          <view class="info-row"
            ><text class="info-label">扣款时间</text
            ><text class="info-value">{{
              formatTime(order?.payTime || order?.createdAt)
            }}</text></view
          >
          <view
            v-if="
              order?.status === 'REFUNDED' ||
              order?.status === 'PARTIAL_REFUNDED' ||
              refundCents > 0
            "
            class="info-row"
          >
            <text class="info-label">退款</text>
            <text class="info-value"
              >{{ order?.status === 'PARTIAL_REFUNDED' ? '部分退款' : '已退款'
              }}{{ refundCents > 0 ? ` ${fmtMoney(refundCents)}` : '' }}</text
            >
          </view>
          <view
            v-if="order?.status === 'REFUNDED' || order?.status === 'PARTIAL_REFUNDED'"
            class="info-row"
          >
            <text class="info-label">退款时间</text>
            <text class="info-value">{{
              order?.refundedAt ? formatTime(order.refundedAt) : '暂无'
            }}</text>
          </view>
          <view class="info-row"
            ><text class="info-label">订单编号</text
            ><text class="info-value mono">{{ displayBizNo(order?.orderId) }}</text></view
          >
          <view class="info-row"
            ><text class="info-label">柜机编号</text
            ><text class="info-value mono">{{
              emptyDisplay(order?.deviceId, 'device')
            }}</text></view
          >
        </view>

        <view class="actions">
          <button v-if="order?.deviceId" class="btn-primary" @click="reopenCabinet">
            再去本柜购物
          </button>
          <button
            v-if="order?.status === 'UNPAID'"
            class="btn-primary"
            :disabled="paying"
            @click="payNow"
          >
            {{ paying ? '支付中…' : '去支付' }}
          </button>
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
            {{
              disputeFiled
                ? '申诉已提交'
                : autoRefundEnabled
                  ? '提交账单申诉'
                  : '申请退款 / 账单申诉'
            }}
          </button>
          <button
            v-if="canInvoice"
            class="btn-outline"
            :disabled="invoiceLoading || invoiceDone"
            @click="openInvoice"
          >
            {{ invoiceDone ? '已申请开票' : '申请开票' }}
          </button>
          <button class="btn-outline" @click="goHelp">帮助与客服</button>
        </view>

        <view class="support" @click="callSupport">客服电话: {{ supportPhoneDisplay }} ›</view>
      </view>

      <view v-if="showInvoice" class="dispute-mask" @click="closeInvoice">
        <view class="dispute-panel" @click.stop>
          <text class="dispute-title">申请开票</text>
          <text class="dispute-sub">提交后运营开具电子发票（演示环境为申请留痕）</text>
          <text class="field-label">发票抬头</text>
          <input
            v-model="invoiceTitle"
            class="dispute-input"
            maxlength="64"
            placeholder="个人姓名或公司全称"
          />
          <text class="field-label">税号（企业选填）</text>
          <input
            v-model="invoiceTaxNo"
            class="dispute-input"
            maxlength="32"
            placeholder="纳税人识别号"
          />
          <text class="field-label">接收邮箱（选填）</text>
          <input
            v-model="invoiceEmail"
            class="dispute-input"
            maxlength="128"
            placeholder="发票发送邮箱"
          />
          <view class="dispute-actions">
            <button class="btn-outline" @click="closeInvoice">取消</button>
            <button class="btn-primary" :loading="invoiceLoading" @click="submitInvoice">
              {{ invoiceLoading ? '提交中…' : '提交申请' }}
            </button>
          </view>
        </view>
      </view>

      <view v-if="showDispute" class="dispute-mask" @click="closeDispute">
        <view class="dispute-panel" @click.stop>
          <text class="dispute-title">{{ refundMode ? '立即退款' : '申请退款 / 账单申诉' }}</text>
          <text class="dispute-sub">
            {{
              refundMode
                ? '将原路退回本单已扣款项。选「没拿/识别有误」会回库；选「质量问题(已拿走)」仅退款不回库。'
                : '仅提交申诉工单，运营审核后再退款。可上传凭证图片。'
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
          <view v-if="refundMode && refundLineRows.length" class="partial-block">
            <text class="field-label">按行退款（不选则全额退）</text>
            <view v-for="row in refundLineRows" :key="row.skuId" class="partial-row">
              <text class="partial-name">{{ row.skuName }}</text>
              <text class="partial-meta">可退 {{ row.maxQty }}</text>
              <input
                class="partial-qty"
                type="number"
                :value="String(row.qty)"
                @input="(e: any) => onPartialQty(row, e)"
              />
            </view>
          </view>
          <view class="evidence-block">
            <text class="evidence-label">申诉附图（选填，最多 5 张）</text>
            <view class="evidence-row">
              <view v-for="(img, idx) in evidence" :key="img.localPath + idx" class="evidence-item">
                <image
                  class="evidence-img"
                  :src="previewEvidenceSrc(img)"
                  mode="aspectFill"
                  :alt="`证据图 ${idx + 1}`"
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
            class="btn-submit"
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
  </view>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { onLoad, onShow } from '@dcloudio/uni-app';
import { displayLabel } from '@aicabinet/shared-dict';
import { consumerApi } from '@/utils/consumer-api';
import { skuImageFor } from '@aicabinet/shared-uni/product-image';
import {
  emptyDisplay,
  displayBizNo,
  formatDateTimeMinute,
  orderStatusLabel,
  fmtMoney
} from '@aicabinet/shared-uni/format';
import { parseQuery, queryGet } from '@aicabinet/shared-uni/query';
import type { OrderDetailDto } from '@aicabinet/shared-types';
import {
  DISPUTE_REASON_CHIPS,
  appendChipToReason,
  inferRestoreInventory,
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

const orderId = ref('');
const order = ref<OrderDetailDto | null>(null);
const loading = ref(true);
const error = ref('');
const videoUrl = ref('');
const showDispute = ref(false);
const refundMode = ref(false);
const disputeReason = ref('');
const disputeLoading = ref(false);
const refundLoading = ref(false);
const paying = ref(false);
const disputeFiled = ref(false);
const refundDone = ref(false);
const invoiceLoading = ref(false);
const invoiceDone = ref(false);
const showInvoice = ref(false);
const invoiceTitle = ref('');
const invoiceTaxNo = ref('');
const invoiceEmail = ref('');
const reasonChips = DISPUTE_REASON_CHIPS;
const selectedCategory = ref('USER_APPEAL');
const selectedChip = ref<DisputeReasonChip | null>(null);
const evidence = ref<LocalEvidence[]>([]);
const supportPhoneDisplay = ref('400-888-0018');
const supportPhoneDial = ref('4008880018');

/** 合并 onLoad/onShow 同刻并发，避免首屏打两次订单详情 */
let bootstrapPromise: Promise<void> | null = null;
let bootstrapTarget = '';

function currentPageOptions(): Record<string, string | undefined> {
  const pages = getCurrentPages();
  const cur = pages[pages.length - 1] as { options?: Record<string, string> } | undefined;
  return cur?.options || {};
}

function resolveOrderId(opt?: Record<string, string | undefined>): string {
  const merged = { ...currentPageOptions(), ...opt };
  const fromOpt = String(merged.orderId || merged.id || '').trim();
  if (fromOpt) return fromOpt;
  if (typeof window !== 'undefined' && typeof window.location !== 'undefined') {
    try {
      const hash = String(window.location.hash || '');
      const hashQuery = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : '';
      const search = String(window.location.search || '').replace(/^\?/, '');
      const fromUrl =
        queryGet(hashQuery || search, 'orderId') || queryGet(hashQuery || search, 'id');
      if (fromUrl.trim()) return fromUrl.trim();
    } catch {
      /* keep fallback */
    }
  }
  return String(orderId.value || '').trim();
}

async function bootstrap(opt?: Record<string, string | undefined>) {
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

onLoad((opt) => {
  void bootstrap(opt);
});

onShow(() => {
  void bootstrap(currentPageOptions());
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
  if (!order.value) loading.value = true;
  error.value = '';
  try {
    order.value = await consumerApi.getOrder(orderId.value);
    if (order.value?.videoUri) videoUrl.value = order.value.videoUri;
    if (order.value?.status === 'DISPUTED') disputeFiled.value = true;
    if (order.value?.status === 'REFUNDED') {
      refundDone.value = true;
      disputeFiled.value = true;
    }
  } catch (e) {
    if (!order.value) error.value = e instanceof Error ? e.message : '加载失败';
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
    cancelled: '无'
  };
  return map[(order.value?.status || '').toLowerCase()] || '✓';
});

const statusTitle = computed(() => orderStatusLabel(order.value?.status) || '订单详情');

const refundCents = computed(() => {
  const o = order.value;
  if (!o) return 0;
  const n = Number(o.refundedCents || 0);
  if (n > 0) return n;
  if (o.status === 'REFUNDED') return Number(o.totalAmountCents || 0);
  return 0;
});

const canDispute = computed(() => {
  const s = order.value?.status;
  if (!order.value?.sessionId || disputeFiled.value) return false;
  if (
    s === 'REFUNDED' ||
    s === 'PARTIAL_REFUNDED' ||
    s === 'DISPUTED' ||
    s === 'CANCELLED' ||
    s === 'FAILED'
  ) {
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
    (s === 'PAID' || s === 'COMPLETED' || s === 'PARTIAL_REFUNDED')
  );
});

const canInvoice = computed(() => {
  const s = order.value?.status;
  return (
    !!order.value?.orderId &&
    !invoiceDone.value &&
    (s === 'PAID' || s === 'COMPLETED' || s === 'PARTIAL_REFUNDED') &&
    (order.value?.totalAmountCents || 0) > 0
  );
});

function openInvoice() {
  invoiceTitle.value = '';
  invoiceTaxNo.value = '';
  invoiceEmail.value = '';
  showInvoice.value = true;
}

function closeInvoice() {
  showInvoice.value = false;
}

async function submitInvoice() {
  const oid = order.value?.orderId;
  if (!oid) return;
  const title = invoiceTitle.value.trim();
  if (!title) {
    uni.showToast({ title: '请填写发票抬头', icon: 'none' });
    return;
  }
  invoiceLoading.value = true;
  try {
    await consumerApi.applyInvoice(oid, {
      title,
      taxNo: invoiceTaxNo.value.trim() || undefined,
      email: invoiceEmail.value.trim() || undefined
    });
    invoiceDone.value = true;
    showInvoice.value = false;
    uni.showToast({ title: '开票申请已提交', icon: 'success' });
  } catch (e: any) {
    uni.showToast({ title: e?.message || '提交失败', icon: 'none' });
  } finally {
    invoiceLoading.value = false;
  }
}
type RefundLineRow = { skuId: string; skuName: string; maxQty: number; qty: number };
const refundLineRows = ref<RefundLineRow[]>([]);

function syncRefundLines() {
  refundLineRows.value = (order.value?.lines || [])
    .filter((l) => l?.skuId && (l.quantity || 0) > 0)
    .map((l) => ({
      skuId: String(l.skuId),
      skuName: String(l.skuName || l.skuId),
      maxQty: Number(l.quantity || 0),
      qty: 0
    }));
}

function onPartialQty(row: RefundLineRow, e: any) {
  const n = Math.max(
    0,
    Math.min(row.maxQty, parseInt(String(e?.detail?.value ?? e?.target?.value ?? 0), 10) || 0)
  );
  row.qty = n;
}

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
  if (!ch) return '未记录';
  return displayLabel('pay_channel', ch, '未知渠道');
});

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '暂无');
}

function playVideo() {
  if (!videoUrl.value) return;
  // 统一进入原生视频播放页（H5 / 小程序均可）
  const oid = encodeURIComponent(String(order.value?.orderId || ''));
  const did = encodeURIComponent(String(order.value?.deviceId || ''));
  uni.navigateTo({
    url: `/pages/video/video?url=${encodeURIComponent(videoUrl.value)}&orderId=${oid}&deviceId=${did}`
  });
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
  syncRefundLines();
  showDispute.value = true;
}

async function payNow() {
  if (!order.value?.orderId || paying.value) return;
  paying.value = true;
  try {
    await consumerApi.payOrder(order.value.orderId);
    uni.showToast({ title: '支付成功', icon: 'success' });
    await reload();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '支付失败', icon: 'none' });
  } finally {
    paying.value = false;
  }
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
  const restoreInventory = inferRestoreInventory(reason, selectedChip.value);
  const lines = refundLineRows.value
    .filter((r) => r.qty > 0)
    .map((r) => ({
      skuId: r.skuId,
      quantity: r.qty,
      restoreInventory
    }));
  const isPartial = lines.length > 0;
  const confirmed = await new Promise<boolean>((resolve) =>
    uni.showModal({
      title: isPartial ? '确认按行退款' : '确认退款',
      content: isPartial
        ? restoreInventory
          ? `将退款所选 ${lines.length} 行商品并回库。是否继续？`
          : `将退款所选 ${lines.length} 行商品（不回库）。是否继续？`
        : restoreInventory
          ? '将立即全额退款，并把本单商品回库（适用于没拿/误识别）。是否继续？'
          : '将立即全额退款，但库存不回库（货已拿走/仅退款）。是否继续？',
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
      evidenceFileIds: evidenceFileIds(evidence.value),
      restoreInventory,
      ...(isPartial ? { lines } : {})
    });
    refundDone.value = true;
    disputeFiled.value = true;
    showDispute.value = false;
    uni.showToast({ title: result.message || '退款成功', icon: 'success' });
    await reload();
  } catch (e) {
    uni.showToast({ title: consumerAppealErrorMessage(e, '退款失败'), icon: 'none' });
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
.page-root {
  padding: 0;
  background: #ffffff;
  /* 用 100% 贴齐 page 高度；100vh 在桌面手机框内会撑出多余内滚动条 */
  min-height: 100%;
  box-sizing: border-box;
}
.page-body {
  padding: 20rpx 20rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.loading,
.error {
  text-align: center;
  padding: 80rpx 0;
  color: #999;
  font-size: 28rpx;
}
.empty-lines {
  font-size: 26rpx;
  color: #999;
  padding: 12rpx 0;
}
.status-bar {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 16rpx;
  padding: 30rpx;
  margin-bottom: 20rpx;
}
.status-bar.status-paid,
.status-bar.status-completed {
  background: linear-gradient(135deg, #e8f5e9, #fff);
}
.status-bar.status-refunded {
  background: linear-gradient(135deg, #fff3e0, #fff);
}
.status-icon {
  width: 60rpx;
  height: 60rpx;
  border-radius: 30rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32rpx;
  font-weight: 700;
  margin-right: 20rpx;
  background: linear-gradient(135deg, #047857, #059669);
  color: #fff;
  flex-shrink: 0;
}
.status-bar.status-refunded .status-icon {
  background: #ff9500;
}
.status-title {
  font-size: 32rpx;
  font-weight: 600;
  display: block;
}
.status-detail {
  font-size: 24rpx;
  color: #666;
  margin-top: 4rpx;
  display: block;
}
.section {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 20rpx;
}
.section-title {
  font-size: 28rpx;
  font-weight: 600;
  margin-bottom: 16rpx;
  display: block;
  color: #333;
}
.item-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14rpx 0;
  border-bottom: 1rpx solid #f5f5f5;
  gap: 16rpx;
}
.item-thumb {
  width: 80rpx;
  height: 80rpx;
  border-radius: 14rpx;
  background: #f0fdf4;
  flex-shrink: 0;
}
.item-info {
  flex: 1;
  min-width: 0;
}
.item-name {
  font-size: 28rpx;
  display: block;
}
.item-qty {
  font-size: 24rpx;
  color: #999;
  margin-left: 12rpx;
}
.item-unit {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
.item-price {
  font-size: 28rpx;
  font-weight: 500;
}
.total-row {
  display: flex;
  justify-content: space-between;
  padding: 20rpx 0 0;
}
.total-row.pay {
  padding-top: 12rpx;
  border-top: 1rpx solid #eee;
  margin-top: 8rpx;
}
.total-row.pay .total-amount {
  color: #059669;
  font-size: 34rpx;
}
.total-label {
  font-size: 28rpx;
  font-weight: 600;
}
.total-amount {
  font-size: 36rpx;
  font-weight: 700;
  color: #ff3b30;
}
.discount-row {
  display: flex;
  justify-content: space-between;
  padding: 8rpx 0;
}
.discount-label {
  font-size: 24rpx;
  color: #059669;
}
.discount-amount {
  font-size: 24rpx;
  color: #059669;
}
.info-row {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 0;
}
.info-label {
  font-size: 26rpx;
  color: #666;
}
.info-value {
  font-size: 26rpx;
  color: #333;
}
.mono {
  font-family: var(--app-font-mono);
  font-size: 22rpx;
}
.actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 20rpx;
  padding: 10rpx 0;
}
/* 纵向操作区：通栏等宽，避免「立即退款」等比「再去本柜购物」短一截 */
.actions .btn-primary,
.actions .btn-outline,
.actions .btn-refund {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  margin-left: 0 !important;
  margin-right: 0 !important;
  align-self: stretch !important;
  justify-content: center;
  padding-left: 36rpx;
  padding-right: 36rpx;
}
.btn-primary {
  width: fit-content;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  height: 88rpx;
  line-height: 1.2;
  border: none;
  color: #fff;
  border-radius: 44rpx;
  background: linear-gradient(135deg, #047857, #059669);
  font-size: 28rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 24rpx rgba(5, 150, 105, 0.22);
}
.btn-outline {
  width: fit-content;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  height: 80rpx;
  line-height: 1.2;
  border: 2rpx solid #059669;
  color: #059669;
  border-radius: 44rpx;
  background: #fff;
  font-size: 28rpx;
  font-weight: 600;
}
.btn-outline.danger {
  border-color: #ef4444;
  color: #b91c1c;
}
.btn-refund {
  width: fit-content;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  height: 88rpx;
  line-height: 1.2;
  border: none;
  color: #fff;
  border-radius: 44rpx;
  background: linear-gradient(135deg, #dc2626, #ef4444);
  font-size: 28rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 24rpx rgba(239, 68, 68, 0.22);
}
.btn-primary::after,
.btn-outline::after,
.btn-refund::after,
.btn-submit::after {
  border: none;
}
.support {
  text-align: center;
  padding: 30rpx;
  color: #059669;
  font-size: 24rpx;
}
.dispute-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 100;
  display: flex;
  align-items: flex-end;
}
.dispute-panel {
  width: 100%;
  max-height: 90vh;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  padding: 32rpx 28rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.dispute-title {
  font-size: 34rpx;
  font-weight: 700;
  display: block;
}
.dispute-sub {
  font-size: 24rpx;
  color: #888;
  display: block;
  margin: 12rpx 0 20rpx;
  line-height: 1.5;
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
.dispute-input {
  width: 100%;
  min-height: 140rpx;
  background: #f5f7f8;
  border-radius: 12rpx;
  padding: 20rpx;
  box-sizing: border-box;
  font-size: 28rpx;
  margin-bottom: 16rpx;
}
.evidence-block {
  margin-bottom: 20rpx;
}
.evidence-label {
  display: block;
  font-size: 24rpx;
  color: #6b7280;
  margin-bottom: 12rpx;
}
.evidence-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.evidence-item {
  position: relative;
  width: 140rpx;
  height: 140rpx;
}
.evidence-img {
  width: 140rpx;
  height: 140rpx;
  border-radius: 12rpx;
  background: #f3f4f6;
}
.evidence-del {
  position: absolute;
  top: -8rpx;
  right: -8rpx;
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  background: #111;
  color: #fff;
  text-align: center;
  line-height: 36rpx;
  font-size: 24rpx;
}
.evidence-uploading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: 22rpx;
  border-radius: 12rpx;
}
.evidence-add {
  width: 140rpx;
  height: 140rpx;
  border-radius: 12rpx;
  border: 2rpx dashed #d1d5db;
  color: #9ca3af;
  font-size: 48rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.btn-submit {
  width: 100%;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: #ef4444;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.dispute-cancel {
  display: block;
  text-align: center;
  color: #888;
  margin-top: 20rpx;
  font-size: 28rpx;
  padding: 8rpx;
}
.partial-block {
  margin: 16rpx 0 8rpx;
}
.partial-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 12rpx;
}
.partial-name {
  flex: 1;
  font-size: 26rpx;
  color: #1e293b;
}
.partial-meta {
  font-size: 22rpx;
  color: #94a3b8;
}
.partial-qty {
  width: 100rpx;
  height: 56rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 8rpx;
  text-align: center;
  font-size: 26rpx;
  background: #fff;
}
</style>
