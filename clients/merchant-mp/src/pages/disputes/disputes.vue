<template>
  <view class="page-root">
    <view class="tabs">
      <view v-for="t in tabs" :key="t.key" class="tab" :class="{ active: activeTab === t.key }" @click="activeTab = t.key; load()">
        <text>{{ t.label }}</text>
      </view>
    </view>

    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="!list.length" class="empty">
      <text>暂无{{ activeTabLabel }}争议</text>
    </view>
    <view v-else>
      <view v-for="item in list" :key="item.ticketId" class="card" @click="onDetail(item)">
        <view class="card-header">
          <text class="card-id">#{{ item.ticketId?.substring(0, 12) }}</text>
          <text class="card-status" :class="item.status">{{ statusText(item.status) }}</text>
        </view>
        <text class="card-title">{{ item.reason || '争议' }}</text>
        <view class="card-meta">
          <text>{{ item.deviceId || '-' }}</text>
          <text>{{ formatTime(item.createdAt) }}</text>
        </view>
        <view v-if="item.lastMessage" class="card-msg"><text>{{ item.lastMessage }}</text></view>
        <view v-if="item.canReply" class="card-action"><text>点击回复 ›</text></view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { merchantApi } from '@/utils/merchant-api';

const tabs = [
  { key: 'OPEN', label: '待处理' },
  { key: 'PROCESSING', label: '处理中' },
  { key: 'RESOLVED', label: '已解决' },
];

const activeTab = ref('OPEN');
const loading = ref(false);
const list = ref<any[]>([]);

const activeTabLabel = computed(() => tabs.find(t => t.key === activeTab.value)?.label || '');

onShow(() => load());

async function load() {
  loading.value = true;
  try {
    const res = await merchantApi.get('/api/v2/merchant/disputes', {
      params: { status: activeTab.value }
    });
    list.value = res.data ?? [];
  } catch { list.value = []; }
  finally { loading.value = false; }
}

function statusText(s: string) {
  const m: Record<string, string> = { OPEN: '待处理', PROCESSING: '处理中', RESOLVED: '已解决' };
  return m[s] || s;
}

function formatTime(t: string) { if (!t) return ''; return t.substring(0, 16).replace('T', ' '); }

function onDetail(item: any) {
  uni.navigateTo({ url: `/pages/dispute-detail/dispute-detail?ticketId=${item.ticketId}` });
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f0fdfa; min-height: 100vh; }
.tabs { display: flex; background: #fff; border-radius: 16rpx; margin-bottom: 20rpx; overflow: hidden; }
.tab { flex: 1; text-align: center; padding: 20rpx 0; font-size: 26rpx; color: #666; }
.tab.active { color: #0f766e; font-weight: 600; border-bottom: 4rpx solid #0f766e; }
.loading, .empty { text-align: center; color: #999; padding: 80rpx 0; font-size: 28rpx; }
.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 16rpx; }
.card-header { display: flex; justify-content: space-between; margin-bottom: 10rpx; }
.card-id { font-size: 24rpx; color: #999; font-family: monospace; }
.card-status { font-size: 22rpx; padding: 2rpx 12rpx; border-radius: 8rpx; }
.card-status.OPEN { color: #ff9500; background: #fff8e8; }
.card-status.PROCESSING { color: #0f766e; background: #f0fdfa; }
.card-status.RESOLVED { color: #07c160; background: #e8f5e9; }
.card-title { font-size: 28rpx; color: #333; margin-bottom: 8rpx; display: block; }
.card-meta { display: flex; justify-content: space-between; font-size: 22rpx; color: #999; }
.card-msg { background: #f9f9f9; border-radius: 8rpx; padding: 12rpx; margin-top: 10rpx; font-size: 24rpx; color: #666; }
.card-action { margin-top: 10rpx; color: #0f766e; font-size: 24rpx; text-align: right; }
</style>
