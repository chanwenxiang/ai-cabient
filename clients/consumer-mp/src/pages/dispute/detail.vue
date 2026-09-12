<template>
  <view class="page-root">
    <app-nav-bar title="账单审核" />
    <view v-if="loading && !ticket" class="state"><text class="meta">{{ UI_COPY.loading }}</text></view>
    <view v-else-if="error && !ticket" class="state">
      <text class="err">{{ error }}</text>
      <app-button label="重试" @click="bootstrap" />
    </view>
    <empty-state
      v-else-if="!ticket"
      icon="/static/menu/disputes.png"
      title="未找到审核单"
      hint="可能已归档或尚未生成"
    />
    <view v-else-if="ticket">
      <view class="status-header" :class="'tone-' + copy.tone">
        <text class="status-icon">{{ copy.icon }}</text>
        <view class="status-copy">
          <text class="status-title">{{ copy.title }}</text>
          <text class="status-detail">{{ statusText }}</text>
        </view>
      </view>

      <view class="card">
        <text class="section-title">审核说明</text>
        <text class="reason">{{ copy.detail }}</text>
        <view class="info-row">
          <text class="info-label">柜机</text>
          <text class="info-value">{{ emptyDisplay(ticket.deviceId, 'device') }}</text>
        </view>
        <view class="info-row">
          <text class="info-label">购物单号</text>
          <text class="info-value mono">{{ shortId(ticket.sessionId) }}</text>
        </view>
        <view v-if="ticket.createdAt" class="info-row">
          <text class="info-label">提交时间</text>
          <text class="info-value">{{ formatTime(ticket.createdAt) }}</text>
        </view>
        <view v-if="ticket.resolvedAt" class="info-row">
          <text class="info-label">处理时间</text>
          <text class="info-value">{{ formatTime(ticket.resolvedAt) }}</text>
        </view>
        <view v-if="refundChannelText" class="info-row">
          <text class="info-label">退款渠道</text>
          <text class="info-value">{{ refundChannelText }}</text>
        </view>
      </view>

      <view class="card">
        <text class="section-title">处理进度</text>
        <view v-for="(step, i) in timeline" :key="i" class="tl-row">
          <view class="tl-dot" :class="{ done: step.done, current: step.current }" />
          <view class="tl-copy">
            <text class="tl-title">{{ step.title }}</text>
            <text v-if="step.time" class="tl-time">{{ step.time }}</text>
            <text v-if="step.detail" class="tl-detail">{{ step.detail }}</text>
          </view>
        </view>
      </view>

      <view v-if="ticket.evidence?.length" class="card">
        <text class="section-title">申诉附图</text>
        <view class="evidence-row">
          <image
            v-for="img in ticket.evidence"
            :key="img.fileId"
            class="evidence-img"
            :src="evidenceSrc(img)"
            mode="aspectFill"
            @click="previewEvidence(img)"
          />
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
        <text v-if="amountDiffNote" class="amount-diff">{{ amountDiffNote }}</text>
      </view>

      <view class="actions">
        <app-button v-if="ticket.orderId" label="查看账单订单" @click="goOrder" />
        <app-button
          :variant="ticket.orderId ? 'ghost' : 'primary'"
          label="返回订单列表"
          @click="goOrders"
        />
        <text role="button" class="contact-link" @click="contactOps">联系客服 {{ servicePhone }}</text>
        <text v-if="supportEmail" role="button" class="contact-link" @click="copySupportEmail"
          >邮箱 {{ supportEmail }}</text
        >
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  showError
} from '@/utils/notify';
import { onLoad, onShow } from '@dcloudio/uni-app';
import { consumerApi, getConsumerToken, requireConsumerAuth } from '@/utils/consumer-api';
import {
  consumerDisputeReviewCopy,
  consumerDisputeStatusLabel,
  shouldShowConsumerRefundChannel,
  disputeAmountDiffNote
} from '@/utils/dispute-copy';
import { fetchEvidenceLocalPath } from '@/utils/dispute-evidence';
import { displayLabel } from '@aicabinet/shared-dict';
import {
  emptyDisplay,
  shortBizNo,
  formatDateTimeMinute,
  fmtMoney
} from '@aicabinet/shared-uni/format';
import { parseQuery } from '@aicabinet/shared-uni/query';
import type { DisputeTicketDto, FileAttachmentDto, OrderLineDto } from '@aicabinet/shared-types';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

const loading = ref(true);
const error = ref('');
const ticket = ref<DisputeTicketDto | null>(null);
const sessionId = ref('');
const ticketId = ref('');
const servicePhone = ref('400-888-0018');
const supportEmail = ref('');
/** fileId/url -> 本地临时路径，避免 image src 带 token */
const evidenceLocalSrc = ref<Record<string, string>>({});

const copy = computed(() => consumerDisputeReviewCopy(ticket.value));
const isResolved = computed(() => ticket.value?.status === 'RESOLVED');
const suggestedLines = computed<OrderLineDto[]>(() => ticket.value?.suggestedItems || []);
const resolutionLines = computed<OrderLineDto[]>(() => ticket.value?.resolutionItems || []);
const refundChannelText = ref('');
const orderDiscount = ref<{ memberDiscountCents?: number; couponDiscountCents?: number }>({});
const amountDiffNote = computed(() => disputeAmountDiffNote(ticket.value, orderDiscount.value));
const statusText = computed(() => consumerDisputeStatusLabel(ticket.value));

function reviewStepDetail(t: NonNullable<typeof ticket.value>, resolved: boolean): string {
  const note = (t as { operatorNote?: string }).operatorNote;
  if (note) return note;
  if (resolved) return '审核结论已生成';
  if ((t as { slaOverdue?: boolean }).slaOverdue) return '已超时，加急处理中';
  return '请耐心等待';
}

function closeStepTitle(status: string): string {
  const s = String(status || '').toUpperCase();
  if (s === 'RESOLVED' || s === 'CLOSED') {
    return displayLabel('dispute_status', s);
  }
  return '待结案';
}

const timeline = computed(() => {
  const t = ticket.value;
  if (!t) return [];
  const status = String(t.status || '').toUpperCase();
  const resolved = status === 'RESOLVED' || status === 'CLOSED';
  const steps = [
    {
      title: '已提交申诉',
      time: t.createdAt ? formatTime(t.createdAt) : '',
      detail: t.reason || '',
      done: true,
      current: status === 'OPEN' || status === 'PENDING'
    },
    {
      title:
        t.consumerReviewTitle ||
        (status === 'OPEN' || status === 'PENDING'
          ? displayLabel('dispute_status', 'OPEN')
          : displayLabel('dispute_status', 'RESOLVED')),
      time: t.resolvedAt ? formatTime(t.resolvedAt) : '',
      detail: reviewStepDetail(t, resolved),
      done: resolved,
      current: !resolved
    },
    {
      title: closeStepTitle(status),
      time:
        t.resolvedAt || (t as { closedAt?: string }).closedAt
          ? formatTime(t.resolvedAt || (t as { closedAt?: string }).closedAt)
          : '',
      detail: resolved
        ? t.consumerStatusLabel ||
          t.consumerReviewDetail ||
          `最终扣款 ${fmtMoney(t.billedAmountCents ?? 0)}`
        : '结案后可在订单详情查看退款到账',
      done: resolved,
      current: false
    }
  ];
  return steps;
});

onLoad((opts) => {
  applyQuery(opts as Record<string, string>);
  void bootstrap();
});

onShow(() => {
  // 同页不同 query 跳转时 onLoad 不一定重跑；空 query 不得冲掉已有单号
  applyQuery({ ...readHashQuery(), ...currentPageOptions() });
  if (!ticketId.value && !sessionId.value) return;
  void bootstrap();
  loadServicePhone();
});

function currentPageOptions(): Record<string, string> {
  const pages = getCurrentPages();
  const cur = pages[pages.length - 1] as { options?: Record<string, string> } | undefined;
  return cur?.options || {};
}

function readHashQuery(): Record<string, string> {
  // #ifdef H5
  try {
    const hash = globalThis.location.hash || '';
    const q = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : '';
    const params = parseQuery(q);
    return {
      ticketId: params.ticketId || '',
      sessionId: params.sessionId || ''
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

function disputeRedirectPath() {
  const q = [
    ticketId.value ? `ticketId=${encodeURIComponent(ticketId.value)}` : '',
    sessionId.value ? `sessionId=${encodeURIComponent(sessionId.value)}` : ''
  ]
    .filter(Boolean)
    .join('&');
  return q ? `/pages/dispute/detail?${q}` : '/pages/dispute/detail';
}

async function bootstrap() {
  if (!(await requireConsumerAuth('查看审核详情需先完成登录', disputeRedirectPath()))) {
    loading.value = false;
    error.value = '请先登录后查看审核详情';
    ticket.value = null;
    return;
  }
  await reload();
}

async function loadServicePhone() {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    if (cfg?.servicePhone) servicePhone.value = cfg.servicePhone;
    const email = String(cfg?.supportEmail || '').trim();
    if (email) supportEmail.value = email;
  } catch {
    /* keep default */
  }
}

function applyFoundTicket(found: DisputeTicketDto) {
  ticket.value = found;
  sessionId.value = found.sessionId || sessionId.value;
  ticketId.value = found.ticketId || ticketId.value;
  void hydrateEvidencePreviews();
  void loadOrderDiscount(found.orderId);
  if (shouldShowConsumerRefundChannel(found)) {
    void loadRefundChannel(found.orderId);
  } else {
    refundChannelText.value = '';
  }
}

async function loadOrderDiscount(orderId?: string) {
  orderDiscount.value = {};
  if (!orderId) return;
  try {
    const order = await consumerApi.getOrder(orderId);
    orderDiscount.value = {
      memberDiscountCents:
        order?.memberDiscountCents == null ? undefined : Number(order.memberDiscountCents),
      couponDiscountCents:
        order?.couponDiscountCents == null ? undefined : Number(order.couponDiscountCents)
    };
  } catch {
    orderDiscount.value = {};
  }
}

function findTicketInList(list: DisputeTicketDto[]): DisputeTicketDto | null {
  if (ticketId.value) {
    const byId = list.find((d) => d.ticketId === ticketId.value);
    if (byId) return byId;
  }
  if (sessionId.value) {
    return list.find((d) => d.sessionId === sessionId.value) || null;
  }
  return null;
}

async function reloadFromListFallback(primaryError: unknown) {
  const list = await consumerApi.listMyDisputes();
  const found = findTicketInList(list);
  if (!found) {
    ticket.value = null;
    error.value =
      primaryError instanceof Error ? primaryError.message : '未找到该审核单，可能已归档或尚未生成';
    return;
  }
  applyFoundTicket(found);
}

function abortReload(message: string) {
  error.value = message;
  ticket.value = null;
  loading.value = false;
}

function canReloadDispute(): boolean {
  if (!getConsumerToken()) {
    abortReload('请先登录后查看审核详情');
    return false;
  }
  if (!ticketId.value && !sessionId.value) {
    abortReload('缺少审核单参数');
    return false;
  }
  return true;
}

async function fetchDisputeDetailPrimary() {
  const found = await consumerApi.getMyDispute({
    ticketId: ticketId.value || undefined,
    sessionId: sessionId.value || undefined
  });
  applyFoundTicket(found);
}

async function reload() {
  if (!canReloadDispute()) return;
  if (!ticket.value) loading.value = true;
  error.value = '';
  try {
    await fetchDisputeDetailPrimary();
  } catch (e) {
    // 旧后端无 detail 接口时回退列表查找
    try {
      await reloadFromListFallback(e);
    } catch (error_) {
      error.value = error_ instanceof Error ? error_.message : '加载失败';
    }
  } finally {
    loading.value = false;
  }
}

function evidenceKey(img: FileAttachmentDto) {
  return String(img.fileId || img.url || '');
}

function evidenceSrc(img: FileAttachmentDto) {
  const key = evidenceKey(img);
  return (key && evidenceLocalSrc.value[key]) || '';
}

async function hydrateEvidencePreviews() {
  const list = ticket.value?.evidence || [];
  if (!list.length) {
    evidenceLocalSrc.value = {};
    return;
  }
  const next: Record<string, string> = { ...evidenceLocalSrc.value };
  await Promise.all(
    list.map(async (img) => {
      const key = evidenceKey(img);
      if (!key || next[key]) return;
      const local = await fetchEvidenceLocalPath(img.url);
      if (local) next[key] = local;
    })
  );
  evidenceLocalSrc.value = next;
}

function fmtLine(line: OrderLineDto) {
  const cents = line.lineAmountCents ?? 0;
  return fmtMoney(cents);
}

function shortId(id?: string) {
  return shortBizNo(id, 12, '暂无');
}

async function loadRefundChannel(orderId?: string) {
  refundChannelText.value = '';
  if (!orderId) return;
  try {
    const order = await consumerApi.getOrder(orderId);
    const ch = displayLabel('pay_channel', order?.payChannel, '');
    refundChannelText.value = ch ? `原路退回 · ${ch}` : '原支付渠道 / 账户余额';
  } catch {
    refundChannelText.value = '原支付渠道 / 账户余额';
  }
}

function formatTime(v?: string) {
  return formatDateTimeMinute(v, '暂无');
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

function copySupportEmail() {
  const email = supportEmail.value;
  if (!email) return;
  uni.setClipboardData({
    data: email,
    success: () => showError('邮箱已复制'),
    fail: () => showError(email)
  });
}

function previewEvidence(img: FileAttachmentDto) {
  const src = evidenceSrc(img);
  if (!src) return;
  const list = (ticket.value?.evidence || []).map((e) => evidenceSrc(e)).filter(Boolean);
  uni.previewImage({ urls: list.length ? list : [src], current: src });
}
</script>

<style scoped>
.page-root {
  min-height: 100vh;
  background: var(--card-bg, #ffffff);
  padding-bottom: 48rpx;
}
.state {
  padding: 120rpx 48rpx;
  text-align: center;
}
.meta {
  color: var(--text-subtle, #888);
}
.err {
  display: block;
  color: var(--color-danger);
  margin-bottom: 24rpx;
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
.status-header.tone-wait {
  background: linear-gradient(135deg, var(--brand-soft), var(--white));
}
.status-header.tone-warn {
  background: linear-gradient(135deg, color-mix(in srgb, var(--warning, #b45309) 8%, var(--white)), var(--white));
}
.status-header.tone-success {
  background: linear-gradient(135deg, var(--brand-soft, #e8f5e9), var(--white));
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
  background: linear-gradient(135deg, var(--warning, #b45309), var(--warning, #f59e0b));
}
.status-copy {
  flex: 1;
  min-width: 0;
}
.status-title {
  display: block;
  font-size: var(--font-size-xl);
  font-weight: 700;
  color: var(--color-text-primary);
}
.status-detail {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #666);
}
.card {
  margin: 20rpx 24rpx 0;
  padding: 28rpx;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.04);
}
.tl-row {
  display: flex;
  gap: 16rpx;
  margin-top: 18rpx;
}
.tl-dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  margin-top: 10rpx;
  background: var(--text-subtle, #cbd5e1);
  flex-shrink: 0;
}
.tl-dot.done {
  background: var(--brand);
}
.tl-dot.current {
  background: var(--brand);
  box-shadow: 0 0 0 6rpx rgba(15, 118, 110, 0.15);
}
.tl-copy {
  flex: 1;
  min-width: 0;
}
.tl-title {
  display: block;
  font-size: var(--font-size-body);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
}
.tl-time,
.tl-detail {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  line-height: 1.4;
}
.section-title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #1e293b);
  margin-bottom: 12rpx;
}
.section-sub {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  margin-bottom: 12rpx;
}
.evidence-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.evidence-img {
  width: 160rpx;
  height: 160rpx;
  border-radius: var(--radius-control);
  background: var(--color-border-subtle, #f1f5f9);
}
.reason {
  display: block;
  font-size: var(--font-size-md);
  color: var(--text-muted, #334155);
  line-height: 1.55;
  margin-bottom: 16rpx;
}
.info-row {
  display: flex;
  justify-content: space-between;
  padding: 10rpx 0;
}
.info-label {
  font-size: var(--font-size-caption);
  color: var(--text-subtle);
}
.info-value {
  font-size: var(--font-size-caption);
  color: var(--text-muted, #475569);
  max-width: 70%;
  text-align: right;
}
.info-value.mono {
  font-family: var(--app-font-mono);
  font-size: var(--font-size-sm);
}
.line {
  display: flex;
  justify-content: space-between;
  padding: 14rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
}
.line-name {
  color: var(--text-primary, #1e293b);
  font-size: var(--font-size-md);
}
.line-amt {
  color: var(--brand);
  font-weight: 600;
}
.empty-lines {
  font-size: var(--font-size-body);
  color: var(--text-subtle);
  padding: 8rpx 0;
}
.bill-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid var(--color-border);
}
.bill-label {
  font-size: var(--font-size-md);
  color: var(--text-muted);
}
.bill-amount {
  font-size: var(--font-size-h2);
  font-weight: 800;
  color: var(--brand);
}
.amount-diff {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-caption);
  line-height: 1.5;
  color: var(--text-muted);
}
.actions {
  padding: 28rpx 24rpx 8rpx;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
}
.app-btn,
.btn-ghost {
  margin: 0;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  width: 100%;
  box-sizing: border-box;
}
.app-btn::after,
.btn-ghost::after {
  border: none;
}
.btn-ghost {
  background: var(--card-bg, #fff);
  color: var(--text-muted, #334155);
  border: 1rpx solid var(--color-border);
}
.btn-hover {
  opacity: 0.88;
}
.contact-link {
  display: block;
  text-align: center;
  padding: 12rpx 0 8rpx;
  font-size: var(--font-size-body);
  color: var(--text-muted);
}
</style>
