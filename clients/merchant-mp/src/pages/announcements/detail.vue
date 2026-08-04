<template>
  <view class="page">
    <view v-if="loading" class="card state">加载中…</view>
    <view v-else-if="error" class="card state">
      <text class="err">{{ error }}</text>
      <button class="retry" size="mini" @click="load">重试</button>
    </view>
    <view v-else-if="item" class="card article">
      <view class="meta">
        <text v-if="priorityLabel(item.priority)" class="tag" :class="priorityClass(item.priority)">
          {{ priorityLabel(item.priority) }}
        </text>
        <text class="time">{{ formatTime(item.publishAt) }}</text>
      </view>
      <text class="title">{{ item.title }}</text>
      <text class="content">{{ item.content }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { merchantApi } from '@/utils/merchant-api';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import type { AnnouncementDto } from '@aicabinet/shared-types';

const loading = ref(true);
const error = ref('');
const item = ref<AnnouncementDto | null>(null);
let announceId = 0;

onLoad((query) => {
  announceId = Number(query?.id || 0);
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  void load();
});

async function load() {
  if (!announceId) {
    loading.value = false;
    error.value = '公告不存在';
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    item.value = await merchantApi.getAnnouncement(announceId);
  } catch (e: any) {
    item.value = null;
    error.value = e?.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '暂无');
}

function priorityLabel(p?: string) {
  if (p === 'URGENT') return '紧急';
  if (p === 'HIGH') return '重要';
  return '';
}

function priorityClass(p?: string) {
  if (p === 'URGENT') return 'urgent';
  if (p === 'HIGH') return 'high';
  return '';
}
</script>

<style scoped>
.page { padding: 24rpx; min-height: 100vh; box-sizing: border-box; }
.card {
  background: #fff;
  border-radius: 20rpx;
  padding: 32rpx 28rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
}
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  color: #64748b;
}
.err { color: #b91c1c; text-align: center; }
.retry {
  background: #0f766e;
  color: #fff;
  border: none;
}
.meta {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.tag {
  font-size: 22rpx;
  line-height: 1;
  padding: 8rpx 12rpx;
  border-radius: 999rpx;
  font-weight: 600;
}
.tag.high { color: #b45309; background: #fef3c7; }
.tag.urgent { color: #b91c1c; background: #fee2e2; }
.time { color: #94a3b8; font-size: 24rpx; }
.title {
  display: block;
  font-size: 40rpx;
  font-weight: 700;
  color: #134e4a;
  line-height: 1.35;
  margin-bottom: 24rpx;
}
.content {
  display: block;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 30rpx;
  color: #334155;
  line-height: 1.75;
}
</style>
