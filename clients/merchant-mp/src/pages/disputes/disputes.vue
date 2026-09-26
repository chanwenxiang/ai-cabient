<template>
  <view class="page-root">
    <app-nav-bar title="争议处理" />
    <view class="page-body">
      <view class="tabs-pill">
        <text
          v-for="t in tabs"
          role="button"
          :key="t.key"
          class="filter-chip"
          :class="{ active: activeTab === t.key }"
          @click="switchTab(t.key)"
          >{{ t.label }}</text
        >
      </view>

      <view v-if="loading && !list.length" class="loading"
        ><text>{{ UI_COPY.loading }}</text></view
      >
      <error-state v-else-if="error && !list.length" :title="error" @retry="load" />
      <empty-state
        v-else-if="!list.length"
        kind="alerts"
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
          :aria-label="`争议 ${fullId(item.ticketId)} ${statusText(item.status)}`"
          @click="onDetail(item)"
        >
          <view class="card-header">
            <text class="card-id">#{{ fullId(item.ticketId) }}</text>
            <text class="card-status" :class="item.status">{{ statusText(item.status) }}</text>
          </view>
          <text class="card-title">{{ merchantDisputeDisplayCopy(item) || '争议' }}</text>
          <view class="card-meta">
            <text>{{ item.deviceName || item.deviceId || '无柜机' }}</text>
            <text>{{ formatTime(item.createdAt) }}</text>
            <text :class="item.slaOverdue ? 'sla-overdue' : 'sla-ok'">{{
              disputeSlaListLabel(
                item,
                isTerminalDispute(item.status),
                displayLabel('dispute_status', 'RESOLVED'),
                displayLabel('order_status', 'PROCESSING')
              )
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
            <text v-if="item.orderId" class="card-order">订单 {{ fullId(item.orderId) }}</text>
          </view>
          <!-- 列表行契约（MerchantDisputeSummaryDto）只有 hasVideo；videoUri / videoPreviewUrl
               仅存在于**详情**契约，原先这里写成三选一的死条件（后两项恒为 undefined） -->
          <view v-if="item.hasVideo" class="card-video-hint">有录像</view>
          <view v-if="item.lastMessage" class="card-msg"
            ><text>{{ item.lastMessage }}</text></view
          >
          <view class="card-action">
            <text
              v-if="canReplyTicket(item)"
              role="button"
              class="reply-hint app-link-chevron"
              @click.stop="onReply(item)"
              >回复</text
            >
            <text v-else class="reply-hint app-link-chevron">查看详情</text>
          </view>
        </view>
        <view v-if="hasMore" class="load-more" role="button" @click="loadMore">
          {{ loadingMore ? UI_COPY.loading : `加载更多（已显示 ${list.length}/${listTotal}）` }}
        </view>
        <text v-else-if="listTruncated" class="trunc-hint">共 {{ listTotal }} 条，已全部加载</text>
      </view>

      <!-- 争议详情底部抽屉：替代 uni.showModal 长文本，小屏可滚动 -->
      <AppSheet :visible="detailVisible" aria-label="争议详情" @close="detailVisible = false">
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
                disputeSlaDetailLabel(detail || {})
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
          <view v-if="playbackUrl" class="video-block">
            <text class="detail-lbl">购物录像</text>
            <video
              class="dispute-video"
              :src="playbackUrl"
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
          <view v-else-if="detail?.videoUri" class="video-block">
            <text class="detail-lbl">购物录像</text>
            <text class="video-unavailable">录像暂不可用（对象不存在或链接已过期）</text>
          </view>
        </scroll-view>
        <view class="detail-actions">
          <app-button
            v-if="canResolveDetail && !detail?.assignee"
            :loading="claiming"
            label="认领工单"
            @click="claimFromDetail"
          />
          <app-button v-if="canReplyDetail" label="回复" @click="replyFromDetail" />
          <app-button
            v-if="canResolveDetail"
            variant="danger"
            :loading="resolving"
            :label="displayLabel('dispute_resolution', 'WAIVE')"
            @click="resolveFromDetail('WAIVE')"
          />
          <app-button
            v-if="canResolveDetail"
            variant="outline"
            :label="moreActionsOpen ? '收起' : '更多'"
            @click="moreActionsOpen = !moreActionsOpen"
          />
          <template v-if="canResolveDetail && moreActionsOpen">
            <app-button
              variant="outline"
              :loading="resolving"
              :label="displayLabel('dispute_resolution', 'KEEP')"
              @click="resolveFromDetail('KEEP')"
            />
            <app-button
              variant="outline"
              :loading="resolving"
              :label="displayLabel('dispute_resolution', 'CONFIRM')"
              @click="resolveFromDetail('CONFIRM')"
            />
          </template>
          <app-button
            v-if="detail?.orderId"
            variant="outline"
            label="查看订单"
            @click="goOrderFromDetail"
          />
          <app-button
            v-else-if="detail?.deviceId"
            variant="outline"
            label="查看柜机"
            @click="goDeviceFromDetail"
          />
          <app-button variant="ghost" label="关闭" @click="detailVisible = false" />
        </view>
      </AppSheet>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { showError, showSuccess, showConfirm } from '@/utils/notify';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { useAutoRefresh } from '@/composables/use-auto-refresh';
import { displayLabel } from '@aicabinet/shared-dict';
import { emptyDisplay, formatDateTimeShort, fmtMoney } from '@aicabinet/shared-uni/format';
import { merchantDisputeDisplayCopy, merchantDisputeAmountDiffNote } from '@/utils/dispute-copy';
import EmptyState from '@/components/empty-state.vue';
import AppSheet from '@/components/AppSheet.vue';
import {
  hasPerm,
  merchantApi,
  type MerchantDisputeTicket,
  type MerchantDisputeDetailView,
  isMerchantLoggedIn
} from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { promptText } from '@/utils/text-prompt';
import type { MerchantMe } from '@aicabinet/shared-types';
import {
  buildMerchantDisputeResolveBody,
  canReplyMerchantDispute,
  isTerminalDisputeStatus,
  type MerchantDisputeResolutionType
} from '@/utils/money-ui-contracts';
import {
  DISPUTES_FOCUS_SCAN_MAX_PAGES,
  DISPUTES_PAGE_SIZE,
  appendDisputePageItems,
  applyDisputesFirstPage,
  disputeSlaDetailLabel,
  disputeSlaListLabel,
  merchantDisputeResolveConfirmContent,
  playablePlaybackUrl
} from '@/utils/dispute-list';
import { mergeDisputeDetailRow, resolveDisputeDetailPermissions } from '@/utils/dispute-detail';
import {
  DISPUTE_REPLY_DENIED_MESSAGE,
  DISPUTE_REPLY_PROMPT,
  canStartDisputeClaim,
  canStartDisputeResolve,
  disputeActionErrorMessage,
  disputeDeviceDetailUrl,
  disputeOrderDetailUrl,
  disputeResolveTypeLabels,
  mergeClaimedDisputeDetail
} from '@/utils/dispute-actions';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

const { me, refresh: refreshMe } = useMerchantMe();
const canListDisputes = computed(() => hasPerm(me.value, 'merchant:disputes:list'));
const canReply = computed(() => hasPerm(me.value, 'merchant:disputes:reply'));
const canResolve = computed(() => hasPerm(me.value, 'merchant:disputes:resolve'));

const tabs = [
  { key: 'OPEN', label: displayLabel('dispute_status', 'OPEN') },
  { key: 'RESOLVED', label: displayLabel('dispute_status', 'RESOLVED') },
  { key: 'CLOSED', label: displayLabel('dispute_status', 'CLOSED') }
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
const PAGE_SIZE = DISPUTES_PAGE_SIZE;
const pendingTicketId = ref('');
const pendingSessionId = ref('');
const detailVisible = ref(false);
const detail = ref<MerchantDisputeDetailView | null>(null);
const detailAmountDiffNote = computed(() => merchantDisputeAmountDiffNote(detail.value));
/**
 * 可播放的录像地址。
 *
 * `videoPreviewUrl` 是后端预签名后的 HTTP 地址；当对象不存在时后端**故意**返回空
 * （MinioVideoService.presignPlaybackUrl 的注释："对象不存在时不返回 URL，避免指向 404"）。
 * 旧模板在这种情况下回退到 `videoUri`，而它是一个 `minio://bucket/key` 私有协议地址，
 * 浏览器 `<video>` 解析不了 → 控制台报 `net::ERR_UNKNOWN_URL_SCHEME`、播放器显示黑屏 00:00。
 * 实测：`minio://cabinet-videos/sim/.../1789459576844846197-top.mp4`。
 * 所以这里只在地址真的可播放时才渲染播放器，否则给出明确提示。
 */
const playbackUrl = computed(() => playablePlaybackUrl(detail.value?.videoPreviewUrl));
const canReplyDetail = ref(false);
const canResolveDetail = ref(false);
const resolving = ref(false);
const claiming = ref(false);
const moreActionsOpen = ref(false);

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

/**
 * 争议结案由运营侧异步处理：列表里还有未结案工单时每 10 秒静默跟进一次，结案即停表。
 * 已翻页 / 详情抽屉打开时不轮询（load() 会重置回第一页，会吞掉翻页结果与打断阅读）。
 */
useAutoRefresh({
  intervalMs: 10_000,
  load,
  shouldContinue: () => list.value.some((t) => !isTerminalDispute(t.status)),
  maxDurationMs: 300_000,
  canRefresh: () =>
    !loading.value && !loadingMore.value && pageIndex.value === 0 && !detailVisible.value
});

function switchTab(key: string) {
  activeTab.value = key;
  load();
}

function isTerminalDispute(status?: string | null) {
  return isTerminalDisputeStatus(status);
}

function canReplyTicket(item: MerchantDisputeTicket | MerchantDisputeDetailView) {
  return canReplyMerchantDispute({ status: item.status, hasReplyPerm: canReply.value });
}

async function refreshDisputesMerchantMe(seq: number): Promise<boolean> {
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return false;
    seedMerchantMeDisplayCache(me);
  }
  if (seq !== loadSeq) return false;
  if (!me.value) {
    seedMerchantMeDisplayCache(me);
  }
  return true;
}

function applyDisputesResponse(
  res: MerchantDisputeTicket[] | { items?: MerchantDisputeTicket[]; total?: number }
) {
  const next = applyDisputesFirstPage(res);
  list.value = next.list;
  listTotal.value = next.total;
  pageIndex.value = next.pageIndex;
  hasMore.value = next.hasMore;
}

function denyDisputesAccess() {
  showError('无争议权限');
  uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
}

async function fetchDisputesList(seq: number) {
  const res = await merchantApi.disputes(activeTab.value, 0, PAGE_SIZE);
  if (seq !== loadSeq) return;
  applyDisputesResponse(res);
  await handlePendingSessionId(seq);
  await handlePendingTicketId();
}

async function handlePendingSessionId(seq: number) {
  if (!pendingSessionId.value) return;
  const sid = pendingSessionId.value;
  pendingSessionId.value = '';
  let matched = list.value.filter((t) => t.sessionId === sid);
  // 首屏 PAGE_SIZE 缩小后，深链可能不在第 0 页：有限翻页扫描（对齐 splits）
  for (let p = 1; matched.length === 0 && p < DISPUTES_FOCUS_SCAN_MAX_PAGES && hasMore.value; p++) {
    const res = await merchantApi.disputes(activeTab.value, p, PAGE_SIZE);
    if (seq !== loadSeq) return;
    const next = appendDisputePageItems({
      list: list.value,
      pageIndex: p,
      res,
      pageSize: PAGE_SIZE,
      previousTotal: listTotal.value
    });
    list.value = next.list;
    listTotal.value = next.total;
    pageIndex.value = next.pageIndex;
    hasMore.value = next.hasMore;
    if (!next.appended && !next.hasMore) break;
    matched = list.value.filter((t) => t.sessionId === sid);
  }
  if (matched.length === 1) {
    onDetail(matched[0]);
  } else if (matched.length > 1) {
    list.value = matched;
    listTotal.value = matched.length;
    hasMore.value = false;
  }
}

async function handlePendingTicketId() {
  if (!pendingTicketId.value) return;
  const tid = pendingTicketId.value;
  pendingTicketId.value = '';
  let row: MerchantDisputeTicket | MerchantDisputeDetailView | undefined = list.value.find(
    (t) => t.ticketId === tid
  );
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
  if (!isMerchantLoggedIn()) {
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

/**
 * 工单号 / 订单号都是客服与商户的**查询凭据**，列表必须与详情页展示同一个完整号。
 * 旧实现截断为前 12 位（W-8），导致「凭列表号查不到工单」——详情页给的是完整 19 位。
 * 此处只做空值兜底，不再截断。
 */
function fullId(id?: string) {
  return emptyDisplay(id, 'order');
}

function formatTime(t?: string) {
  return formatDateTimeShort(t, '暂无');
}

async function onDetail(item: MerchantDisputeTicket | MerchantDisputeDetailView) {
  if (!item.ticketId) return;
  let apiSlice: Parameters<typeof mergeDisputeDetailRow>[1] = null;
  try {
    const res = await merchantApi.disputeDetail(item.ticketId);
    apiSlice = res;
  } catch {
    // 列表摘要兜底
  }
  const row = mergeDisputeDetailRow(item, apiSlice);
  detail.value = row;
  moreActionsOpen.value = false;
  const perms = resolveDisputeDetailPermissions({
    status: row.status,
    canReplyFromApi: apiSlice?.canReply,
    canResolveFromApi: apiSlice?.canResolve,
    hasReplyPerm: canReply.value,
    hasResolvePerm: canResolve.value
  });
  canReplyDetail.value = perms.canReplyDetail;
  canResolveDetail.value = perms.canResolveDetail;
  detailVisible.value = true;
}

async function claimFromDetail() {
  if (!canStartDisputeClaim({ ticketId: detail.value?.ticketId, claiming: claiming.value })) return;
  claiming.value = true;
  try {
    const ticket = await merchantApi.disputeClaim(detail.value!.ticketId!);
    detail.value = mergeClaimedDisputeDetail(detail.value!, ticket);
    showSuccess('已认领');
    await load();
  } catch (e) {
    showError(disputeActionErrorMessage(e, '认领失败'));
  } finally {
    claiming.value = false;
  }
}

async function resolveFromDetail(type: MerchantDisputeResolutionType) {
  if (!canStartDisputeResolve({ ticketId: detail.value?.ticketId, resolving: resolving.value }))
    return;
  const labels = disputeResolveTypeLabels(displayLabel);
  const ok = await showConfirm({
    title: labels[type],
    content: merchantDisputeResolveConfirmContent(type, labels[type])
  });
  if (!ok) return;
  resolving.value = true;
  try {
    const body = buildMerchantDisputeResolveBody({ resolutionType: type });
    const res = await merchantApi.disputeResolve(detail.value!.ticketId!, body);
    showSuccess(res.message || displayLabel('dispute_status', 'RESOLVED'));
    detailVisible.value = false;
    await load();
  } catch (e) {
    showError(disputeActionErrorMessage(e, '结案失败'));
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
    uni.navigateTo({ url: disputeOrderDetailUrl(oid) });
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value || loading.value) return;
  loadingMore.value = true;
  try {
    const next = pageIndex.value + 1;
    const res = await merchantApi.disputes(activeTab.value, next, PAGE_SIZE);
    const page = appendDisputePageItems({
      list: list.value,
      pageIndex: next,
      res,
      pageSize: PAGE_SIZE,
      previousTotal: listTotal.value
    });
    list.value = page.list;
    listTotal.value = page.total;
    pageIndex.value = page.pageIndex;
    hasMore.value = page.hasMore;
  } catch (e) {
    showError(disputeActionErrorMessage(e, '加载失败'));
  } finally {
    loadingMore.value = false;
  }
}

function goDeviceFromDetail() {
  const deviceId = detail.value?.deviceId;
  detailVisible.value = false;
  if (deviceId) {
    uni.navigateTo({ url: disputeDeviceDetailUrl(deviceId) });
  }
}

async function onReply(item: MerchantDisputeTicket | MerchantDisputeDetailView) {
  if (!canReply.value) {
    showError(DISPUTE_REPLY_DENIED_MESSAGE);
    return;
  }
  if (!item.ticketId) return;
  const body = await promptText({ ...DISPUTE_REPLY_PROMPT });
  if (body == null) return;
  try {
    await merchantApi.disputeReply(item.ticketId, body);
    showSuccess('已回复');
    await load();
  } catch (e) {
    showError(disputeActionErrorMessage(e, '回复失败'));
  }
}
</script>

<style scoped src="./disputes.page.css"></style>
