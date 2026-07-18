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
      <view v-for="item in list" :key="item.ticketId" class="card" @click="onDetail(item)">
        <view class="card-header">
          <text class="card-id">#{{ shortId(item.ticketId) }}</text>
          <text class="card-status" :class="item.status">{{ statusText(item.status) }}</text>
        </view>
        <text class="card-title">{{ item.reason || '争议' }}</text>
        <view class="card-meta">
          <text>{{ item.deviceId || '-' }}</text>
          <text>{{ formatTime(item.createdAt) }}</text>
        </view>
        <view v-if="item.lastMessage" class="card-msg"><text>{{ item.lastMessage }}</text></view>
        <view class="card-action">
          <text v-if="canReplyTicket(item)" class="reply-hint" @click.stop="onReply(item)">回复 ›</text>
          <text v-else>查看详情 ›</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { hasPerm, merchantApi, type MerchantDisputeTicket } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canListDisputes = computed(() => hasPerm(me.value, 'merchant:disputes:list'));
const canReply = computed(() => hasPerm(me.value, 'merchant:disputes:reply'));

const tabs = [
  { key: 'OPEN', label: '待处理' },
  { key: 'PROCESSING', label: '处理中' },
  { key: 'RESOLVED', label: '已解决' }
];

const activeTab = ref('OPEN');
const loading = ref(false);
const error = ref('');
const list = ref<MerchantDisputeTicket[]>([]);

const activeTabLabel = computed(() => tabs.find((t) => t.key === activeTab.value)?.label || '');

onShow(() => load());

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
  try {
    await refreshMe();
  } catch {
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
    const res = await merchantApi.disputes(activeTab.value);
    if (Array.isArray(res)) {
      list.value = res;
    } else {
      list.value = res?.items || [];
    }
  } catch (e) {
    list.value = [];
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function statusText(s?: string) {
  const m: Record<string, string> = { OPEN: '待处理', PROCESSING: '处理中', RESOLVED: '已解决' };
  return m[s || ''] || s || '-';
}

function shortId(id?: string) {
  if (!id) return '-';
  return id.length > 12 ? id.substring(0, 12) : id;
}

function formatTime(t?: string) {
  if (!t) return '';
  return t.substring(0, 16).replace('T', ' ');
}

function onDetail(item: MerchantDisputeTicket) {
  const lines = [
    `单号：${item.ticketId || '-'}`,
    `状态：${statusText(item.status)}`,
    `柜机：${item.deviceId || '-'}`,
    `原因：${item.reason || '-'}`,
    item.lastMessage ? `最新：${item.lastMessage}` : ''
  ]
    .filter(Boolean)
    .join('\n');
  const replyable = canReplyTicket(item);
  uni.showModal({
    title: '争议详情',
    content: lines,
    showCancel: true,
    cancelText: replyable ? '关闭' : item.deviceId ? '关闭' : '知道了',
    confirmText: replyable ? '回复' : item.deviceId ? '查看柜机' : '知道了',
    success(res) {
      if (!res.confirm) return;
      if (replyable) {
        onReply(item);
        return;
      }
      if (item.deviceId) {
        uni.navigateTo({
          url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}`
        });
      }
    }
  });
}

function onReply(item: MerchantDisputeTicket) {
  if (!canReply.value) {
    uni.showToast({ title: '无回复权限', icon: 'none' });
    return;
  }
  uni.showModal({
    title: '回复争议',
    editable: true,
    placeholderText: '填写商户回复内容',
    success: async (res) => {
      if (!res.confirm) return;
      const body = (res.content || '').trim();
      if (!body) {
        uni.showToast({ title: '请填写回复内容', icon: 'none' });
        return;
      }
      try {
        await merchantApi.disputeReply(item.ticketId, body);
        uni.showToast({ title: '已回复', icon: 'success' });
        await load();
      } catch (e) {
        uni.showToast({ title: e instanceof Error ? e.message : '回复失败', icon: 'none' });
      }
    }
  });
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
  border-radius: 36rpx;
  background: #0f766e;
  color: #fff;
  font-size: 26rpx;
}
.retry::after { border: none; }
.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 16rpx; border: 1rpx solid #e2e8f0; }
.card-header { display: flex; justify-content: space-between; margin-bottom: 10rpx; }
.card-id { font-size: 22rpx; color: #94a3b8; }
.card-status { font-size: 22rpx; color: #92400e; background: #fef3c7; padding: 4rpx 12rpx; border-radius: 999rpx; }
.card-status.RESOLVED, .card-status.resolved { color: #166534; background: #dcfce7; }
.card-title { display: block; font-size: 28rpx; font-weight: 600; color: #0f172a; }
.card-meta { display: flex; justify-content: space-between; margin-top: 12rpx; font-size: 22rpx; color: #94a3b8; }
.card-msg { margin-top: 12rpx; padding: 12rpx; background: #f8fafc; border-radius: 12rpx; font-size: 24rpx; color: #475569; }
.card-action { margin-top: 12rpx; text-align: right; color: #0f766e; font-size: 24rpx; }
.reply-hint { font-weight: 600; }
</style>
