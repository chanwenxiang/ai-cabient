<template>
  <view class="page">
    <view v-if="loading" class="state">加载中…</view>
    <view v-else-if="error" class="state">
      <text class="err">{{ error }}</text>
      <button class="retry" size="mini" @click="load">重试</button>
    </view>
    <empty-state
      v-else-if="!list.length"
      icon="告"
      title="暂无通知公告"
      hint="平台维护、活动与规则变更会在这里发布"
    />
    <view v-else class="list">
      <view
        v-for="item in list"
        :key="item.announceId"
        class="card"
        hover-class="card-hover"
        @click="goDetail(item.announceId)"
      >
        <view class="card-head">
          <text v-if="priorityLabel(item.priority)" class="tag" :class="priorityClass(item.priority)">
            {{ priorityLabel(item.priority) }}
          </text>
          <text v-if="unread(item.announceId)" class="unread-dot" aria-label="未读">新</text>
          <text class="time">{{ formatTime(item.publishAt) }}</text>
        </view>
        <text class="title">{{ item.title }}</text>
        <text class="preview">{{ previewText(item.content) }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { consumerApi } from '@/utils/consumer-api';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import { announcementReadMap } from '@aicabinet/shared-uni/announcement-read';
import type { AnnouncementDto } from '@aicabinet/shared-types';

const loading = ref(true);
const error = ref('');
const list = ref<AnnouncementDto[]>([]);
const readMap = ref<Record<string, number>>({});

function unread(id?: number) {
  return id != null && readMap.value[String(id)] == null;
}

onShow(() => {
  void load();
});

async function load() {
  loading.value = true;
  error.value = '';
  try {
    list.value = (await consumerApi.listAnnouncements()) || [];
    readMap.value = announcementReadMap();
  } catch (e) {
    list.value = [];
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function goDetail(id?: number) {
  if (!id) return;
  uni.navigateTo({ url: `/pages/announcements/detail?id=${id}` });
}

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '');
}

function previewText(content?: string) {
  const text = String(content || '').replace(/\s+/g, ' ').trim();
  return text.length > 72 ? `${text.slice(0, 72)}…` : text;
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
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  background: #f7f7f7;
  box-sizing: border-box;
}
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  padding: 80rpx 24rpx;
  color: #64748b;
  font-size: 28rpx;
}
.err { color: #b91c1c; text-align: center; }
.retry {
  margin-top: 8rpx;
  background: #059669;
  color: #fff;
  border: none;
}
.list { display: flex; flex-direction: column; gap: 16rpx; }
.card {
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx 28rpx 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.04);
}
.card-hover { opacity: 0.92; }
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
  margin-bottom: 12rpx;
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
.unread-dot {
  color: #fff;
  background: #ef4444;
  font-size: 20rpx;
  line-height: 1;
  padding: 6rpx 10rpx;
  border-radius: 999rpx;
  margin-left: 8rpx;
}
.time { color: #94a3b8; font-size: 22rpx; margin-left: auto; }
.title {
  display: block;
  font-size: 32rpx;
  font-weight: 650;
  color: #0f172a;
  line-height: 1.4;
}
.preview {
  display: block;
  margin-top: 10rpx;
  font-size: 26rpx;
  color: #64748b;
  line-height: 1.55;
}
</style>
