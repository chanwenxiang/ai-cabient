<template>
  <view class="page-root">
    <app-nav-bar title="订单详情" />
    <view class="page-body">
      <view v-if="loading && !order" class="loading"><text>{{ UI_COPY.loading }}</text></view>
      <view v-else-if="error && !order" class="error">
        <text>{{ error }}</text>
        <app-button variant="outline" style="margin-top: 24rpx" label="重试" @click="reload" />
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
              fmtMoney(order?.originalAmountCents ?? order?.totalAmountCents ?? 0)
            }}</text>
          </view>
          <view v-if="order?.couponDiscountCents != null" class="discount-row">
            <text class="discount-label">优惠券抵扣</text>
            <text class="discount-amount">减{{ fmtMoney(order.couponDiscountCents) }}</text>
          </view>
          <view v-if="Number(order?.memberDiscountCents ?? 0) > 0" class="discount-row">
            <text class="discount-label">会员优惠</text>
            <text class="discount-amount">减{{ fmtMoney(order.memberDiscountCents) }}</text>
          </view>
          <view
            v-if="order?.couponDiscountCents != null || Number(order?.memberDiscountCents ?? 0) > 0"
            class="total-row pay"
          >
            <text class="total-label">实付</text>
            <text class="total-amount">{{ fmtMoney(order?.totalAmountCents ?? 0) }}</text>
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
              formatTime(order?.paidAt || order?.payTime || order?.createdAt)
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
              >{{
                displayLabel(
                  'order_status',
                  order?.status === 'PARTIAL_REFUNDED' ? 'PARTIAL_REFUNDED' : 'REFUNDED'
                )
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
            ><text class="info-label">柜机</text
            ><text class="info-value mono">{{
              emptyDisplay(order?.deviceName || order?.deviceId, 'device')
            }}</text></view
          >
        </view>

        <view class="actions">
          <app-button v-if="order?.deviceId" label="再去本柜购物" @click="reopenCabinet" />
          <app-button
            v-if="order?.status === 'UNPAID'"
            :disabled="paying"
            :loading="paying"
            :label="paying ? '支付中…' : '去支付'"
            @click="payNow"
          />
          <app-button
            v-if="canShowVideo"
            variant="outline"
            label="查看购物视频"
            @click="playVideo"
          />
          <app-button
            v-if="canRefund"
            variant="danger"
            :disabled="refundLoading || disputeLoading"
            :label="refundDone ? displayLabel('order_status', 'REFUNDED') : '立即退款'"
            @click="openRefund"
          />
          <app-button
            v-if="canDispute"
            variant="outline"
            :disabled="disputeLoading || refundLoading"
            :label="
              disputeFiled
                ? '申诉已提交'
                : autoRefundEnabled
                  ? '提交账单申诉'
                  : '申请退款 / 账单申诉'
            "
            @click="openDispute"
          />
          <app-button
            v-if="canInvoice"
            variant="outline"
            :disabled="invoiceLoading || invoiceDone"
            :label="invoiceDone ? '已申请开票' : '申请开票'"
            @click="openInvoice"
          />
          <app-button variant="outline" label="帮助与客服" @click="goHelp" />
        </view>

        <view role="button" class="support app-link-chevron" @click="callSupport">客服电话: {{ supportPhoneDisplay }}</view>
      </view>

      <view v-if="showInvoice" role="button" aria-label="关闭" class="dispute-mask" @click="closeInvoice">
        <view role="button" class="dispute-panel" @click.stop>
          <text class="dispute-title">申请开票</text>
          <text class="dispute-sub">提交后由运营开具电子发票，并发送至您填写的邮箱</text>
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
            <app-button variant="outline" :block="false" label="取消" @click="closeInvoice" />
            <app-button
              :block="false"
              :loading="invoiceLoading"
              :label="invoiceLoading ? '提交中…' : '提交申请'"
              @click="submitInvoice"
            />
          </view>
        </view>
      </view>

      <view v-if="showDispute" role="button" aria-label="关闭" class="dispute-mask" @click="closeDispute">
        <view role="button" class="dispute-panel" @click.stop>
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
  </view>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import {
  showError,
  showSuccess,
  showConfirm
} from '@/utils/notify';
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
import { queryGet } from '@aicabinet/shared-uni/query';
import type { OrderDetailDto } from '@aicabinet/shared-types';
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
  if (globalThis.location != null) {
    try {
      const hash = String(globalThis.location.hash || '');
      const hashQuery = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : '';
      const search = String(globalThis.location.search || '').replace(/^\?/, '');
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
  if (typeof globalThis !== 'undefined') {
    globalThis.addEventListener('hashchange', onHashChange);
  }
});

onUnmounted(() => {
  if (typeof globalThis !== 'undefined') {
    globalThis.removeEventListener('hashchange', onHashChange);
  }
});

async function loadSupportPhone() {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    const phone = String(cfg?.servicePhone || cfg?.['consumer.service_phone'] || '').trim();
    if (phone) {
      supportPhoneDisplay.value = phone;
      supportPhoneDial.value = phone.replaceAll(/[^\d+]/g, '');
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
  // M-6：只信服务端 refundedCents
  const n = Number(o.refundedCents ?? 0);
  return Number.isFinite(n) && n > 0 ? n : 0;
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

/** 有会话且已产生账单的订单可查看录像（由后端 /orders/{id}/video 鉴权拉流） */
const canShowVideo = computed(() => {
  const o = order.value;
  if (!o?.sessionId || !o.orderId) return false;
  const s = String(o.status || '').toUpperCase();
  return s === 'PAID' || s === 'COMPLETED' || s === 'REFUNDED' || s === 'PARTIAL_REFUNDED';
});

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
    (order.value?.totalAmountCents ?? 0) > 0
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
    showError('请填写发票抬头');
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
    showSuccess('开票申请已提交');
  } catch (e: unknown) {
    showError(e instanceof Error ? e.message : '提交失败');
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

function onPartialQty(
  row: RefundLineRow,
  e: { detail?: { value?: string }; target?: { value?: string } } | Event
) {
  const raw = String(
    (e as { detail?: { value?: string } })?.detail?.value ??
      (e as { target?: { value?: string } })?.target?.value ??
      ''
  ).trim();
  if (!raw) {
    row.qty = 0;
    return;
  }
  if (!/^\d+$/.test(raw)) {
    showError('请输入有效退款件数');
    row.qty = 0;
    return;
  }
  row.qty = Math.min(row.maxQty, Number.parseInt(raw, 10));
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
  const oid = String(order.value?.orderId || '').trim();
  if (!oid) return;
  const did = encodeURIComponent(String(order.value?.deviceId || ''));
  uni.navigateTo({
    url: `/pages/video/video?orderId=${encodeURIComponent(oid)}&deviceId=${did}`
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
    showSuccess('支付成功');
    await reload();
  } catch (e) {
    showError(e instanceof Error ? e.message : '支付失败');
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
  const sessionId = order.value?.sessionId;
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
    showSuccess('申诉已提交');
    await reload();
  } catch (e) {
    showError(consumerAppealErrorMessage(e, '提交失败'));
  } finally {
    disputeLoading.value = false;
  }
}

function refundConfirmContent(
  isPartial: boolean,
  restoreInventory: boolean | undefined,
  lineCount: number
): string {
  if (restoreInventory == null) {
    return isPartial
      ? `将退款所选 ${lineCount} 行商品；是否回库由平台规则判定。是否继续？`
      : '将立即全额退款；是否回库由平台规则判定。是否继续？';
  }
  if (isPartial) {
    if (restoreInventory) {
      return `将退款所选 ${lineCount} 行商品并回库。是否继续？`;
    }
    return `将退款所选 ${lineCount} 行商品（不回库）。是否继续？`;
  }
  if (restoreInventory) {
    return '将立即全额退款，并把本单商品回库（适用于没拿/误识别）。是否继续？';
  }
  return '将立即全额退款，但库存不回库（货已拿走/仅退款）。是否继续？';
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
  const lines = refundLineRows.value
    .filter((r) => r.qty > 0)
    .map((r) => ({
      skuId: r.skuId,
      quantity: r.qty,
      ...(restoreInventory != null ? { restoreInventory } : {})
    }));
  const isPartial = lines.length > 0;
  const confirmed = await showConfirm({
    title: isPartial ? '确认按行退款' : '确认退款',
    content: refundConfirmContent(isPartial, restoreInventory, lines.length),
    confirmText: '确认退款'
  });
  if (!confirmed) return;
  refundLoading.value = true;
  try {
    const result = await consumerApi.refundOrder(oid, {
      reason,
      evidenceFileIds: evidenceFileIds(evidence.value),
      ...(restoreInventory != null ? { restoreInventory } : {}),
      ...(isPartial ? { lines } : {})
    });
    refundDone.value = true;
    disputeFiled.value = true;
    showDispute.value = false;
    showSuccess(result.message || '退款成功');
    await reload();
  } catch (e) {
    showError(consumerAppealErrorMessage(e, '退款失败'));
  } finally {
    refundLoading.value = false;
  }
}

function reopenCabinet() {
  const id = order.value?.deviceId;
  if (!id) {
    showError('缺少柜机编号');
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
    fail: () => showError(`请拨打 ${supportPhoneDisplay.value}`)
  });
}
</script>

<style scoped>
.page-root {
  padding: 0;
  background: var(--card-bg, #ffffff);
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
  color: var(--text-subtle, #999);
  font-size: var(--font-size-md);
}
.empty-lines {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #999);
  padding: 12rpx 0;
}
.status-bar {
  display: flex;
  align-items: center;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 30rpx;
  margin-bottom: 20rpx;
}
.status-bar.status-paid,
.status-bar.status-completed {
  background: linear-gradient(135deg, var(--brand-soft, #e8f5e9), #fff);
}
.status-bar.status-refunded {
  background: linear-gradient(135deg, #fff3e0, #fff);
}
.status-icon {
  width: 60rpx;
  height: 60rpx;
  border-radius: var(--radius-card);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-xl);
  font-weight: 700;
  margin-right: 20rpx;
  background: linear-gradient(135deg, var(--brand), var(--brand));
  color: #fff;
  flex-shrink: 0;
}
.status-bar.status-refunded .status-icon {
  background: #ff9500;
}
.status-title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  display: block;
}
.status-detail {
  font-size: var(--font-size-caption);
  color: var(--text-muted, #666);
  margin-top: 4rpx;
  display: block;
}
.section {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 24rpx;
  margin-bottom: 20rpx;
}
.section-title {
  font-size: var(--font-size-md);
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
  border-radius: var(--radius-control);
  background: var(--brand-soft, #f0fdf4);
  flex-shrink: 0;
}
.item-info {
  flex: 1;
  min-width: 0;
}
.item-name {
  font-size: var(--font-size-md);
  display: block;
}
.item-qty {
  font-size: var(--font-size-caption);
  color: var(--text-subtle, #999);
  margin-left: 12rpx;
}
.item-unit {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.item-price {
  font-size: var(--font-size-md);
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
  color: var(--brand);
  font-size: var(--font-size-h3);
}
.total-label {
  font-size: var(--font-size-md);
  font-weight: 600;
}
.total-amount {
  font-size: var(--font-size-display-sm);
  font-weight: 700;
  color: #ff3b30;
}
.discount-row {
  display: flex;
  justify-content: space-between;
  padding: 8rpx 0;
}
.discount-label {
  font-size: var(--font-size-caption);
  color: var(--brand);
}
.discount-amount {
  font-size: var(--font-size-caption);
  color: var(--brand);
}
.info-row {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 0;
}
.info-label {
  font-size: var(--font-size-body);
  color: var(--text-muted, #666);
}
.info-value {
  font-size: var(--font-size-body);
  color: #333;
}
.mono {
  font-family: var(--app-font-mono);
  font-size: var(--font-size-sm);
}
.actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 20rpx;
  padding: 10rpx 0;
}
/* 纵向操作区：通栏等宽，避免「立即退款」等比「再去本柜购物」短一截 */
.actions .app-btn,
.actions .btn-outline,
.actions .btn-refund,
.actions .app-btn {
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
.btn-outline {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  height: 80rpx;
  line-height: 1.2;
  border: 2rpx solid var(--brand);
  color: var(--brand);
  border-radius: var(--radius-pill);
  background: var(--card-bg, #fff);
  font-size: var(--font-size-md);
  font-weight: 600;
}
.btn-outline.danger {
  border-color: var(--color-danger);
  color: var(--color-danger);
}
.btn-refund {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  height: 88rpx;
  line-height: 1.2;
  border: none;
  color: #fff;
  border-radius: var(--radius-pill);
  background: linear-gradient(135deg, var(--color-danger), var(--color-danger));
  font-size: var(--font-size-md);
  font-weight: 600;
  box-shadow: 0 8rpx 24rpx rgba(239, 68, 68, 0.22);
}
.app-btn::after,
.btn-outline::after,
.btn-refund::after,
.btn-submit::after {
  border: none;
}
.support {
  text-align: center;
  padding: 30rpx;
  color: var(--brand);
  font-size: var(--font-size-caption);
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
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card) 24rpx 0 0;
  padding: 32rpx 28rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.dispute-title {
  font-size: var(--font-size-h3);
  font-weight: 700;
  display: block;
}
.dispute-sub {
  font-size: var(--font-size-caption);
  color: var(--text-subtle, #888);
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
  border-radius: var(--radius-pill);
  background: var(--color-border-subtle);
  color: #374151;
  font-size: var(--font-size-caption);
  border: 1rpx solid transparent;
}
.reason-chip.on {
  background: color-mix(in srgb, var(--danger, #b91c1c) 8%, #fff);
  color: var(--color-danger);
  border-color: color-mix(in srgb, var(--danger, #b91c1c) 18%, #fff);
}
.dispute-input {
  width: 100%;
  min-height: 140rpx;
  background: var(--page-bg, #f5f7f8);
  border-radius: var(--radius-control);
  padding: 20rpx;
  box-sizing: border-box;
  font-size: var(--font-size-md);
  margin-bottom: 16rpx;
}
.evidence-block {
  margin-bottom: 20rpx;
}
.evidence-label {
  display: block;
  font-size: var(--font-size-caption);
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
  border-radius: var(--radius-control);
  background: var(--color-border-subtle);
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
  font-size: var(--font-size-caption);
}
.evidence-uploading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: var(--font-size-sm);
  border-radius: var(--radius-control);
}
.evidence-add {
  width: 140rpx;
  height: 140rpx;
  border-radius: var(--radius-control);
  border: 2rpx dashed #d1d5db;
  color: #9ca3af;
  font-size: var(--font-size-display);
  display: flex;
  align-items: center;
  justify-content: center;
}
.btn-submit {
  width: 100%;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: var(--color-danger);
  color: #fff;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.dispute-actions {
  display: flex;
  align-items: stretch;
  gap: 16rpx;
  width: 100%;
  margin-top: 8rpx;
  box-sizing: border-box;
}
.dispute-actions .btn-outline,
.dispute-actions .app-btn,
.dispute-actions uni-button.btn-outline,
.dispute-actions uni-button.app-btn,
.dispute-actions button.btn-outline,
.dispute-actions button.app-btn,
.dispute-actions .app-btn {
  flex: 1 1 0;
  width: auto !important;
  max-width: none !important;
  min-width: 0 !important;
  margin: 0 !important;
  height: 88rpx;
  line-height: 1.2;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
.dispute-cancel {
  display: block;
  text-align: center;
  color: var(--text-subtle, #888);
  margin-top: 20rpx;
  font-size: var(--font-size-md);
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
  font-size: var(--font-size-body);
  color: var(--text-primary, #1e293b);
}
.partial-meta {
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.partial-qty {
  width: 100rpx;
  height: 56rpx;
  border: 1rpx solid var(--color-border);
  border-radius: var(--radius-tag);
  text-align: center;
  font-size: var(--font-size-body);
  background: var(--card-bg, #fff);
}
</style>
