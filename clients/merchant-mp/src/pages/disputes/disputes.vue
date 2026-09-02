<template>
  <view class="page-root">
    <app-nav-bar title="争议处理" />
    <view class="page-body">
      <view class="tabs-pill">
        <text
          v-for="t in tabs"
          :key="t.key"
          class="filter-chip"
          :class="{ active: activeTab === t.key }"
          @click="switchTab(t.key)"
          >{{ t.label }}</text
        >
      </view>

      <view v-if="loading && !list.length" class="loading"><text>加载中…</text></view>
      <view v-else-if="error && !list.length" class="empty">
        <text class="err">{{ error }}</text>
        <button class="retry" @click="load">重试</button>
      </view>
      <empty-state
        v-else-if="!list.length"
        icon="/static/menu/disputes.png"
        :title="`暂无${activeTabLabel}争议`"
        hint="用户申诉与识别复核会显示在这里"
      />
      <view v-else>
        <view
          v-for="item in list"
          :key="item.ticketId"
          class="card"
          hover-class="card-hover"
          role="button"
          :aria-label="`争议 ${shortId(item.ticketId)} ${statusText(item.status)}`"
          @click="onDetail(item)"
        >
          <view class="card-header">
            <text class="card-id">#{{ shortId(item.ticketId) }}</text>
            <text class="card-status" :class="item.status">{{ statusText(item.status) }}</text>
          </view>
          <text class="card-title">{{ merchantDisputeDisplayCopy(item) || '争议' }}</text>
          <view class="card-meta">
            <text>{{ item.deviceName || item.deviceId || '无柜机' }}</text>
            <text>{{ formatTime(item.createdAt) }}</text>
            <text :class="item.slaOverdue ? 'sla-overdue' : 'sla-ok'">{{
              isTerminalDispute(item.status)
                ? '已结案'
                : item.slaOverdue
                  ? '已超时'
                  : item.slaHoursRemaining == null
                    ? '处理中'
                    : `剩余 ${item.slaHoursRemaining} 小时`
            }}</text>
          </view>
          <view
            v-if="
              item.billedAmountCents != null ||
              item.claimedAmountCents != null ||
              item.refundedAmountCents != null
            "
            class="card-amount-line"
          >
            <text v-if="item.billedAmountCents != null"
              >已扣 {{ fmtMoney(item.billedAmountCents) }}</text
            >
            <text v-if="item.claimedAmountCents != null"
              >建议 {{ fmtMoney(item.claimedAmountCents) }}</text
            >
            <text v-if="item.refundedAmountCents != null"
              >已退 {{ fmtMoney(item.refundedAmountCents) }}</text
            >
            <text v-if="item.orderId" class="card-order">订单 {{ shortId(item.orderId) }}</text>
          </view>
          <view
            v-if="item.hasVideo || item.videoUri || item.videoPreviewUrl"
            class="card-video-hint"
            >有录像</view
          >
          <view v-if="item.lastMessage" class="card-msg"
            ><text>{{ item.lastMessage }}</text></view
          >
          <view class="card-action">
            <text v-if="canReplyTicket(item)" class="reply-hint" @click.stop="onReply(item)"
              >回复 ›</text
            >
            <text v-else class="reply-hint">查看详情 ›</text>
          </view>
        </view>
        <view v-if="hasMore" class="load-more" role="button" @click="loadMore">
          {{ loadingMore ? '加载中…' : `加载更多（已显示 ${list.length}/${listTotal}）` }}
        </view>
        <text v-else-if="listTruncated" class="trunc-hint">共 {{ listTotal }} 条，已全部加载</text>
      </view>

      <!-- 争议详情底部抽屉：替代 uni.showModal 长文本，小屏可滚动 -->
      <view
        v-if="detailVisible"
        class="detail-mask"
        @click.self="detailVisible = false"
        @touchmove.stop.prevent
      >
        <view class="detail-panel" @click.stop>
          <view class="detail-handle" />
          <text class="detail-title">{{ statusText(detail?.status) }}</text>
          <text class="detail-reason">{{
            merchantDisputeDisplayCopy(detail) || emptyDisplay(detail?.reason, 'reason')
          }}</text>
          <scroll-view scroll-y class="detail-scroll">
            <view class="detail-rows">
              <view class="detail-row"
                ><text class="detail-lbl">单号</text
                ><text class="detail-val">{{ emptyDisplay(detail?.ticketId, 'order') }}</text></view
              >
              <view class="detail-row"
                ><text class="detail-lbl">状态</text
                ><text class="detail-val">{{ statusText(detail?.status) }}</text></view
              >
              <view class="detail-row"
                ><text class="detail-lbl">柜机</text
                ><text class="detail-val">{{
                  emptyDisplay(detail?.deviceName || detail?.deviceId, 'device')
                }}</text></view
              >
              <view v-if="detail?.orderId" class="detail-row"
                ><text class="detail-lbl">订单</text
                ><text class="detail-val">{{ detail.orderId }}</text></view
              >
              <view v-if="detail?.billedAmountCents != null" class="detail-row">
                <text class="detail-lbl">已扣金额</text
                ><text class="detail-val">{{ fmtMoney(detail.billedAmountCents) }}</text>
              </view>
              <view v-if="detail?.claimedAmountCents != null" class="detail-row">
                <text class="detail-lbl">建议金额</text
                ><text class="detail-val">{{ fmtMoney(detail.claimedAmountCents) }}</text>
              </view>
              <view v-if="detail?.refundedAmountCents != null" class="detail-row">
                <text class="detail-lbl">已退金额</text
                ><text class="detail-val">{{ fmtMoney(detail.refundedAmountCents) }}</text>
              </view>
              <view v-if="detailAmountDiffNote" class="detail-row amount-diff-row">
                <text class="detail-lbl">差额说明</text
                ><text class="detail-val amount-diff">{{ detailAmountDiffNote }}</text>
              </view>
              <view
                v-if="detail?.slaOverdue != null || detail?.slaHoursRemaining != null"
                class="detail-row"
              >
                <text class="detail-lbl">处理时限</text
                ><text class="detail-val" :class="detail?.slaOverdue ? 'sla-overdue' : 'sla-ok'">{{
                  detail?.slaOverdue
                    ? '已超时'
                    : detail?.slaHoursRemaining != null
                      ? `剩余 ${detail.slaHoursRemaining} 小时`
                      : '暂无'
                }}</text>
              </view>
              <view v-if="detail?.lastMessage" class="detail-row"
                ><text class="detail-lbl">最新</text
                ><text class="detail-val">{{ detail.lastMessage }}</text></view
              >
            </view>
            <view v-if="(detail?.suggestedItems || []).length" class="suggest-block">
              <text class="detail-lbl">建议明细</text>
              <view v-for="(it, i) in detail?.suggestedItems || []" :key="i" class="suggest-row">
                <text>{{ it.skuName || it.skuId || '商品' }} ×{{ it.quantity || 0 }}</text>
              </view>
            </view>
            <view v-if="detail?.videoPreviewUrl || detail?.videoUri" class="video-block">
              <text class="detail-lbl">购物录像</text>
              <video
                class="dispute-video"
                :src="detail.videoPreviewUrl || detail.videoUri"
                controls
                object-fit="contain"
                :show-center-play-btn="true"
              >
                <track
                  kind="captions"
                  srclang="zh"
                  label="现场录像无对白字幕"
                  src="data:text/vtt,WEBVTT"
                />
                <track
                  kind="descriptions"
                  srclang="zh"
                  label="购物过程监控录像"
                  src="data:text/vtt,WEBVTT"
                />
              </video>
            </view>
          </scroll-view>
          <view class="detail-actions">
            <button v-if="canReplyDetail" class="primary-btn" @click="replyFromDetail">回复</button>
            <button
              v-if="canResolveDetail"
              class="primary-btn waive"
              :loading="resolving"
              @click="resolveFromDetail('WAIVE')"
            >
              同意免单
            </button>
            <button
              v-if="canResolveDetail"
              class="btn-outline"
              :loading="resolving"
              @click="resolveFromDetail('KEEP')"
            >
              维持原单
            </button>
            <button
              v-if="canResolveDetail"
              class="btn-outline"
              :loading="resolving"
              @click="resolveFromDetail('CONFIRM')"
            >
              按识别结案
            </button>
            <button v-if="detail?.orderId" class="btn-outline" @click="goOrderFromDetail">
              查看订单
            </button>
            <button v-else-if="detail?.deviceId" class="btn-outline" @click="goDeviceFromDetail">
              查看柜机
            </button>
            <button class="btn-outline" @click="detailVisible = false">关闭</button>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { displayLabel } from '@aicabinet/shared-dict';
import { emptyDisplay, formatDateTimeShort, fmtMoney } from '@aicabinet/shared-uni/format';
import { merchantDisputeDisplayCopy, merchantDisputeAmountDiffNote } from '@/utils/dispute-copy';
import EmptyState from '@/components/empty-state.vue';
import { hasPerm, merchantApi, type MerchantDisputeTicket } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { promptText } from '@/utils/text-prompt';
import type { MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canListDisputes = computed(() => hasPerm(me.value, 'merchant:disputes:list'));
const canReply = computed(() => hasPerm(me.value, 'merchant:disputes:reply'));
const canResolve = computed(() => hasPerm(me.value, 'merchant:disputes:resolve'));

const tabs = [
  { key: 'OPEN', label: '待处理' },
  { key: 'RESOLVED', label: '已结案' },
  { key: 'CLOSED', label: '已关闭' }
];

const activeTab = ref('OPEN');
const loading = ref(false);
const loadingMore = ref(false);
const error = ref('');
const list = ref<MerchantDisputeTicket[]>([]);
let loadSeq = 0;
const listTotal = ref(0);
const pageIndex = ref(0);
const hasMore = ref(false);
const PAGE_SIZE = 100;
const pendingTicketId = ref('');
const pendingSessionId = ref('');
const detailVisible = ref(false);
const detail = ref<MerchantDisputeTicket | null>(null);
const detailAmountDiffNote = computed(() => merchantDisputeAmountDiffNote(detail.value));
const canReplyDetail = ref(false);
const canResolveDetail = ref(false);
const resolving = ref(false);

const activeTabLabel = computed(() => tabs.find((t) => t.key === activeTab.value)?.label || '');
const listTruncated = computed(
  () => listTotal.value > 0 && list.value.length > 0 && listTotal.value > list.value.length
);

onLoad((opt) => {
  const q = (opt || {}) as Record<string, string | undefined>;
  pendingTicketId.value = String(q.ticketId || '').trim();
  pendingSessionId.value = String(q.sessionId || '').trim();
});
onShow(() => load());
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

function switchTab(key: string) {
  activeTab.value = key;
  load();
}

function isTerminalDispute(status?: string | null) {
  const s = (status || '').toUpperCase();
  return s === 'RESOLVED' || s === 'CLOSED';
}

function canReplyTicket(item: MerchantDisputeTicket) {
  return canReply.value && (item.status || '').toUpperCase() === 'OPEN';
}

async function refreshDisputesMerchantMe(seq: number): Promise<boolean> {
  try {
    await refreshMe();
  } catch {
    if (!uni.getStorageSync('merchant_token')) return false;
    me.value = me.value || (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (seq !== loadSeq) return false;
  if (!me.value) {
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  return true;
}

function applyDisputesResponse(
  res: MerchantDisputeTicket[] | { items?: MerchantDisputeTicket[]; total?: number }
) {
  if (Array.isArray(res)) {
    list.value = res;
    listTotal.value = res.length;
  } else {
    list.value = res?.items || [];
    listTotal.value = res?.total ?? list.value.length;
  }
  pageIndex.value = 0;
  hasMore.value = list.value.length < listTotal.value;
}

function denyDisputesAccess() {
  uni.showToast({ title: '无争议权限', icon: 'none' });
  uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
}

async function fetchDisputesList(seq: number) {
  const res = await merchantApi.disputes(activeTab.value, 0, 100);
  if (seq !== loadSeq) return;
  applyDisputesResponse(res);
  await handlePendingSessionId();
  await handlePendingTicketId();
}

async function handlePendingSessionId() {
  if (!pendingSessionId.value) return;
  const sid = pendingSessionId.value;
  pendingSessionId.value = '';
  const matched = list.value.filter((t) => t.sessionId === sid);
  if (matched.length === 1) {
    onDetail(matched[0]);
  } else if (matched.length > 1) {
    list.value = matched;
    listTotal.value = matched.length;
  }
}

async function handlePendingTicketId() {
  if (!pendingTicketId.value) return;
  const tid = pendingTicketId.value;
  pendingTicketId.value = '';
  let row = list.value.find((t) => t.ticketId === tid);
  if (!row) {
    try {
      const detailRes = await merchantApi.disputeDetail(tid);
      row = detailRes?.ticket;
    } catch {
      row = undefined;
    }
  }
  if (row) onDetail(row);
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const seq = ++loadSeq;
  if (!(await refreshDisputesMerchantMe(seq))) return;
  if (!canListDisputes.value) {
    denyDisputesAccess();
    return;
  }
  if (!list.value.length) loading.value = true;
  error.value = '';
  try {
    await fetchDisputesList(seq);
  } catch (e) {
    if (seq !== loadSeq) return;
    list.value = [];
    listTotal.value = 0;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function statusText(s?: string) {
  return displayLabel('dispute_status', s, '未知状态');
}

function shortId(id?: string) {
  if (!id) return emptyDisplay(id, 'order');
  return id.length > 12 ? id.substring(0, 12) : id;
}

function formatTime(t?: string) {
  return formatDateTimeShort(t, '暂无');
}

async function onDetail(item: MerchantDisputeTicket) {
  let row: MerchantDisputeTicket = { ...item };
  let canReplyFromApi: boolean | undefined;
  let canResolveFromApi: boolean | undefined;
  try {
    const res = await merchantApi.disputeDetail(item.ticketId);
    if (res?.ticket) row = { ...item, ...res.ticket };
    canReplyFromApi = res?.canReply;
    canResolveFromApi = res?.canResolve;
    const lastMsg = res?.messages?.length
      ? res.messages[res.messages.length - 1]?.body
      : row.lastMessage;
    if (lastMsg) row = { ...row, lastMessage: lastMsg };
  } catch {
    // 列表摘要兜底
  }
  detail.value = row;
  canReplyDetail.value =
    canReplyFromApi == null ? canReplyTicket(row) : canReplyFromApi && canReply.value;
  canResolveDetail.value =
    canResolveFromApi == null
      ? canResolve.value && (row.status || '').toUpperCase() === 'OPEN'
      : !!canResolveFromApi && canResolve.value;
  detailVisible.value = true;
}

async function resolveFromDetail(type: 'KEEP' | 'WAIVE' | 'CONFIRM') {
  if (!detail.value?.ticketId || resolving.value) return;
  const labels = { KEEP: '维持原单', WAIVE: '同意免单退款', CONFIRM: '按识别清单结案' };
  const ok = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: labels[type],
      content:
        type === 'WAIVE'
          ? '确认免单并原路退款？货已离柜请选「仅退款」逻辑由系统按默认处理。'
          : `确认${labels[type]}？`,
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false)
    });
  });
  if (!ok) return;
  resolving.value = true;
  try {
    const body: {
      resolutionType: 'KEEP' | 'WAIVE' | 'CONFIRM';
      restoreInventory?: boolean;
    } = { resolutionType: type };
    if (type === 'WAIVE') body.restoreInventory = false;
    const res = await merchantApi.disputeResolve(detail.value.ticketId, body);
    uni.showToast({ title: res.message || '已结案', icon: 'success' });
    detailVisible.value = false;
    await load();
  } catch (e) {
    uni.showToast({
      title: e instanceof Error ? e.message : '结案失败',
      icon: 'none'
    });
  } finally {
    resolving.value = false;
  }
}

function replyFromDetail() {
  if (!detail.value) return;
  detailVisible.value = false;
  void onReply(detail.value);
}

function goOrderFromDetail() {
  const oid = detail.value?.orderId;
  detailVisible.value = false;
  if (oid) {
    uni.navigateTo({
      url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(oid)}`
    });
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value || loading.value) return;
  loadingMore.value = true;
  try {
    const next = pageIndex.value + 1;
    const res = await merchantApi.disputes(activeTab.value, next, PAGE_SIZE);
    const items = Array.isArray(res) ? res : res?.items || [];
    if (!items.length) {
      hasMore.value = false;
      return;
    }
    const seen = new Set(list.value.map((t) => t.ticketId));
    const appended = items.filter((t) => t.ticketId && !seen.has(t.ticketId));
    list.value = list.value.concat(appended);
    pageIndex.value = next;
    const total = Array.isArray(res) ? list.value.length : Number(res?.total ?? list.value.length);
    listTotal.value = total;
    hasMore.value = list.value.length < total && items.length >= PAGE_SIZE;
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  } finally {
    loadingMore.value = false;
  }
}

function goDeviceFromDetail() {
  const deviceId = detail.value?.deviceId;
  detailVisible.value = false;
  if (deviceId) {
    uni.navigateTo({
      url: `/pages/device-detail/device-detail?id=${encodeURIComponent(deviceId)}`
    });
  }
}

async function onReply(item: MerchantDisputeTicket) {
  if (!canReply.value) {
    uni.showToast({ title: '无回复权限', icon: 'none' });
    return;
  }
  const body = await promptText({
    title: '回复争议',
    hint: '回复内容将同步给消费者与运营',
    placeholder: '填写商户回复内容',
    required: true,
    requiredMessage: '请填写回复内容',
    maxLength: 200,
    testId: 'dispute-reply-prompt'
  });
  if (body == null) return;
  try {
    await merchantApi.disputeReply(item.ticketId, body);
    uni.showToast({ title: '已回复', icon: 'success' });
    await load();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '回复失败', icon: 'none' });
  }
}
</script>

<style scoped>
.sla-overdue {
  color: #b91c1c;
  font-weight: 700;
}
.sla-ok {
  color: #b45309;
}

.page-root {
  padding: 0;

  background: #ffffff;
  min-height: 100vh;
  box-sizing: border-box;
}
.tabs-pill {
  margin-bottom: 20rpx;
}
.tab.active {
  color: #0f766e;
  font-weight: 600;
  border-bottom: 4rpx solid #0f766e;
}
.loading,
.empty {
  text-align: center;
  color: #999;
  padding: 80rpx 0;
  font-size: 28rpx;
}
.err {
  color: #ef4444;
  display: block;
  margin-bottom: 20rpx;
}
.retry {
  margin: 0 auto;
  width: 200rpx;
  height: 72rpx;
  line-height: 72rpx;
  border-radius: 44rpx;
  background: linear-gradient(135deg, #134e4a, #0f766e);
  color: #fff;
  font-size: 26rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 20rpx rgba(15, 118, 110, 0.2);
  border: none;
}
.retry::after {
  border: none;
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
  border: 1rpx solid #e2e8f0;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.card-hover {
  background: #f8fafc !important;
}
.card-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10rpx;
}
.card-id,
.card-status,
.card-title,
.card-meta,
.card-msg {
  pointer-events: none;
}
.card-amount-line {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #0f766e;
  font-weight: 600;
}
.card-order {
  color: #64748b;
  font-weight: 400;
}
.card-video-hint {
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #0369a1;
}
.card-id {
  font-size: 22rpx;
  color: #94a3b8;
}
.card-status {
  font-size: 22rpx;
  color: #92400e;
  background: #fef3c7;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
}
.card-status.RESOLVED,
.card-status.resolved {
  color: #166534;
  background: #dcfce7;
}
.card-status.CLOSED,
.card-status.closed {
  color: #475569;
  background: #e2e8f0;
}
.card-title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 12rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
.card-msg {
  margin-top: 12rpx;
  padding: 12rpx;
  background: #f8fafc;
  border-radius: 12rpx;
  font-size: 24rpx;
  color: #475569;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.card-action {
  margin-top: 12rpx;
  text-align: right;
  color: #0f766e;
  font-size: 24rpx;
}
.reply-hint {
  font-weight: 600;
}
.trunc-hint {
  display: block;
  text-align: center;
  color: #94a3b8;
  font-size: 22rpx;
  padding: 8rpx 0 24rpx;
}
.load-more {
  display: block;
  text-align: center;
  color: var(--brand, #0f766e);
  font-size: 24rpx;
  font-weight: 600;
  padding: 20rpx 0 8rpx;
}
.detail-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.55);
  z-index: 300;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.detail-panel {
  width: 100%;
  max-width: 520px;
  margin: 0 auto;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 18rpx 28rpx calc(28rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  max-height: 82vh;
  display: flex;
  flex-direction: column;
}
.detail-handle {
  width: 64rpx;
  height: 8rpx;
  background: #cbd5e1;
  border-radius: 4rpx;
  margin: 0 auto 20rpx;
  flex-shrink: 0;
}
.detail-title {
  font-size: 32rpx;
  font-weight: 700;
  color: #0f172a;
  text-align: center;
  flex-shrink: 0;
}
.detail-reason {
  display: block;
  margin-top: 10rpx;
  font-size: 26rpx;
  color: #475569;
  line-height: 1.5;
  text-align: center;
  flex-shrink: 0;
}
.detail-scroll {
  flex: 1;
  min-height: 0;
  margin-top: 16rpx;
}
.detail-rows {
  border-top: 1rpx solid #f1f5f9;
}
.detail-row {
  display: flex;
  justify-content: space-between;
  gap: 20rpx;
  padding: 18rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}
.detail-lbl {
  font-size: 24rpx;
  color: #94a3b8;
  flex-shrink: 0;
}
.detail-val {
  font-size: 24rpx;
  color: #1e293b;
  text-align: right;
  word-break: break-all;
  max-width: 70%;
}
.detail-val.amount-diff {
  color: #64748b;
  font-size: 22rpx;
  line-height: 1.45;
}
.detail-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 14rpx;
  margin-top: 20rpx;
  flex-shrink: 0;
}
.detail-actions .primary-btn,
.detail-actions .btn-outline {
  margin: 0;
  width: 100%;
  min-height: 80rpx;
  line-height: 1.2;
  border-radius: 40rpx;
  font-size: 28rpx;
  font-weight: 600;
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
.detail-actions .primary-btn.waive {
  background: #dc2626;
  color: #fff;
}
.video-block {
  margin-top: 20rpx;
}
.suggest-block {
  margin-top: 16rpx;
}
.suggest-row {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #334155;
}
.dispute-video {
  width: 100%;
  height: 360rpx;
  margin-top: 12rpx;
  background: #0f172a;
  border-radius: 12rpx;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
