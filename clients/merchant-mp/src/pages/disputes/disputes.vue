<template>
  <view class="page-root">
    <view class="tabs">
      <view
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ active: activeTab === t.key }"
        @click="switchTab(t.key)"
      >
        <text>{{ t.label }}</text>
      </view>
    </view>

    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="error" class="empty">
      <text class="err">{{ error }}</text>
      <button class="retry" @click="load">重试</button>
    </view>
    <view v-else-if="!list.length" class="empty">
      <text>暂无{{ activeTabLabel }}争议</text>
    </view>
    <view v-else>
      <view
        v-for="item in list"
        :key="item.ticketId"
        class="card"
        hover-class="card-hover"
        role="button"
        @click="onDetail(item)"
      >
        <view class="card-header">
          <text class="card-id">#{{ shortId(item.ticketId) }}</text>
          <text class="card-status" :class="item.status">{{ statusText(item.status) }}</text>
        </view>
        <text class="card-title">{{ localizeDisputeReason(item.reason) || '争议' }}</text>
        <view class="card-meta">
          <text>{{ item.deviceId || '-' }}</text>
          <text>{{ formatTime(item.createdAt) }}</text>
        </view>
        <view v-if="item.lastMessage" class="card-msg"><text>{{ item.lastMessage }}</text></view>
        <view class="card-action">
          <text
            v-if="canReplyTicket(item)"
            class="reply-hint"
            @click.stop="onReply(item)"
          >回复 ›</text>
          <text v-else class="reply-hint">查看详情 ›</text>
        </view>
      </view>
      <text v-if="listTruncated" class="trunc-hint">仅显示前 {{ list.length }} 条，共 {{ listTotal }} 条</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { formatDateTimeShort, localizeDisputeReason } from '@aicabinet/shared-uni/format';
import { hasPerm, merchantApi, type MerchantDisputeTicket } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { promptText } from '@/utils/text-prompt';
import type { MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canListDisputes = computed(() => hasPerm(me.value, 'merchant:disputes:list'));
const canReply = computed(() => hasPerm(me.value, 'merchant:disputes:reply'));

const tabs = [
  { key: 'OPEN', label: '待处理' },
  { key: 'RESOLVED', label: '已结案' },
  { key: 'CLOSED', label: '已关闭' }
];

const activeTab = ref('OPEN');
const loading = ref(false);
const error = ref('');
const list = ref<MerchantDisputeTicket[]>([]);
let loadSeq = 0;
const listTotal = ref(0);
const pendingTicketId = ref('');
const pendingSessionId = ref('');

const activeTabLabel = computed(() => tabs.find((t) => t.key === activeTab.value)?.label || '');
const listTruncated = computed(
  () => listTotal.value > 0 && list.value.length > 0 && listTotal.value > list.value.length
);

onLoad((opt: Record<string, string | undefined>) => {
  pendingTicketId.value = String(opt?.ticketId || '').trim();
  pendingSessionId.value = String(opt?.sessionId || '').trim();
});
onShow(() => load());
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

function switchTab(key: string) {
  activeTab.value = key;
  load();
}

function canReplyTicket(item: MerchantDisputeTicket) {
  return canReply.value && (item.status || '').toUpperCase() === 'OPEN';
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const seq = ++loadSeq;
  try {
    await refreshMe();
  } catch {
    if (!uni.getStorageSync('merchant_token')) return;
    me.value = me.value || (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (seq !== loadSeq) return;
  if (!me.value) {
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canListDisputes.value) {
    uni.showToast({ title: '无争议权限', icon: 'none' });
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const res = await merchantApi.disputes(activeTab.value, 0, 100);
    if (seq !== loadSeq) return;
    if (Array.isArray(res)) {
      list.value = res;
      listTotal.value = res.length;
    } else {
      list.value = res?.items || [];
      listTotal.value = res?.total ?? list.value.length;
    }
    if (pendingSessionId.value) {
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
    if (pendingTicketId.value) {
      const tid = pendingTicketId.value;
      pendingTicketId.value = '';
      let row = list.value.find((t) => t.ticketId === tid);
      if (!row) {
        try {
          const detail = await merchantApi.disputeDetail(tid);
          row = detail?.ticket;
        } catch {
          row = undefined;
        }
      }
      if (row) onDetail(row);
    }
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
  const m: Record<string, string> = {
    OPEN: '待处理',
    RESOLVED: '已结案',
    CLOSED: '已关闭'
  };
  return m[s || ''] || s || '-';
}

function shortId(id?: string) {
  if (!id) return '-';
  return id.length > 12 ? id.substring(0, 12) : id;
}

function formatTime(t?: string) {
  return formatDateTimeShort(t) || '';
}

async function onDetail(item: MerchantDisputeTicket) {
  let detail: MerchantDisputeTicket = { ...item };
  let canReplyFromApi: boolean | undefined;
  try {
    const res = await merchantApi.disputeDetail(item.ticketId);
    if (res?.ticket) detail = { ...item, ...res.ticket };
    canReplyFromApi = res?.canReply;
    const lastMsg = res?.messages?.length
      ? res.messages[res.messages.length - 1]?.body
      : detail.lastMessage;
    if (lastMsg) detail = { ...detail, lastMessage: lastMsg };
  } catch {
    // 列表摘要兜底
  }
  const amount =
    detail.billedAmountCents != null
      ? `¥${(detail.billedAmountCents / 100).toFixed(2)}`
      : '';
  const lines = [
    `单号：${detail.ticketId || '-'}`,
    `状态：${statusText(detail.status)}`,
    `柜机：${detail.deviceId || '-'}`,
    detail.orderId ? `订单：${detail.orderId}` : '',
    amount ? `金额：${amount}` : '',
    `原因：${detail.reason || '-'}`,
    detail.lastMessage ? `最新：${detail.lastMessage}` : ''
  ]
    .filter(Boolean)
    .join('\n');
  const replyable =
    canReplyFromApi != null ? canReplyFromApi && canReply.value : canReplyTicket(detail);
  const hasOrder = !!detail.orderId;
  uni.showModal({
    title: '争议详情',
    content: lines,
    showCancel: true,
    cancelText: replyable ? '关闭' : hasOrder || detail.deviceId ? '关闭' : '知道了',
    confirmText: replyable ? '回复' : hasOrder ? '查看订单' : detail.deviceId ? '查看柜机' : '知道了',
    success(res) {
      if (!res.confirm) return;
      if (replyable) {
        onReply(detail);
        return;
      }
      if (hasOrder) {
        uni.navigateTo({
          url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(detail.orderId!)}`
        });
        return;
      }
      if (detail.deviceId) {
        uni.navigateTo({
          url: `/pages/device-detail/device-detail?id=${encodeURIComponent(detail.deviceId)}`
        });
      }
    }
  });
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
.page-root { padding: 20rpx; background: #f0fdfa; min-height: 100vh; box-sizing: border-box; }
.tabs { display: flex; background: #fff; border-radius: 16rpx; margin-bottom: 20rpx; overflow: hidden; border: 1rpx solid #e2e8f0; }
.tab { flex: 1; text-align: center; padding: 20rpx 0; font-size: 26rpx; color: #666; }
.tab.active { color: #0f766e; font-weight: 600; border-bottom: 4rpx solid #0f766e; }
.loading, .empty { text-align: center; color: #999; padding: 80rpx 0; font-size: 28rpx; }
.err { color: #ef4444; display: block; margin-bottom: 20rpx; }
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
.retry::after { border: none; }
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
  border: 1rpx solid #e2e8f0;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.card-hover { background: #f8fafc !important; }
.card-header { display: flex; justify-content: space-between; margin-bottom: 10rpx; }
.card-id, .card-status, .card-title, .card-meta, .card-msg { pointer-events: none; }
.card-id { font-size: 22rpx; color: #94a3b8; }
.card-status { font-size: 22rpx; color: #92400e; background: #fef3c7; padding: 4rpx 12rpx; border-radius: 999rpx; }
.card-status.RESOLVED, .card-status.resolved { color: #166534; background: #dcfce7; }
.card-status.CLOSED, .card-status.closed { color: #475569; background: #e2e8f0; }
.card-title { display: block; font-size: 28rpx; font-weight: 600; color: #0f172a; }
.card-meta { display: flex; justify-content: space-between; margin-top: 12rpx; font-size: 22rpx; color: #94a3b8; }
.card-msg { margin-top: 12rpx; padding: 12rpx; background: #f8fafc; border-radius: 12rpx; font-size: 24rpx; color: #475569; }
.card-action { margin-top: 12rpx; text-align: right; color: #0f766e; font-size: 24rpx; }
.reply-hint { font-weight: 600; }
.trunc-hint {
  display: block;
  text-align: center;
  color: #94a3b8;
  font-size: 22rpx;
  padding: 8rpx 0 24rpx;
}
</style>
