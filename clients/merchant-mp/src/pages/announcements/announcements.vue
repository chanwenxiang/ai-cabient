<template>
  <view class="page">
    <view v-if="loading" class="card state">加载中…</view>
    <view v-else-if="error" class="card state">
      <text class="err">{{ error }}</text>
      <button class="retry" size="mini" @click="load">重试</button>
    </view>
    <empty-state
      v-else-if="!list.length"
  icon="/static/menu/notice.png"
      title="暂无平台公告"
      hint="运营发布的维护、活动与规则通知会出现在这里"
    />
    <view v-else>
      <view
        v-for="item in list"
        :key="item.announceId"
        class="card item"
        hover-class="item-hover"
        @click="goDetail(item.announceId)"
      >
        <view class="head">
          <text
            v-if="priorityLabel(item.priority)"
            class="tag"
            :class="priorityClass(item.priority)"
          >
            {{ priorityLabel(item.priority) }}
          </text>
          <text v-if="unread(item.announceId)" class="unread-dot" aria-label="未读">新</text>
          <text class="time">{{ formatTime(item.publishAt) }}</text>
        </view>
        <text class="title">{{ item.title }}</text>
        <text class="preview">{{ previewText(item.content) }}</text>
        <text class="action">查看详情 ›</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { merchantApi } from '@/utils/merchant-api';
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
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  void load();
});

onPullDownRefresh(async () => {
  try {
    await load();
  } finally {
    uni.stopPullDownRefresh();
  }
});

async function load() {
  loading.value = true;
  error.value = '';
  try {
    list.value = (await merchantApi.listAnnouncements()) || [];
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
  const text = String(content || '')
    .replace(/\s+/g, ' ')
    .trim();
  return text.length > 80 ? `${text.slice(0, 80)}…` : text;
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
  padding: 24rpx;
  min-height: 100vh;
  box-sizing: border-box;
}
.card {
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx;
  margin-bottom: 16rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
}
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  color: #64748b;
}
.err {
  color: #b91c1c;
  text-align: center;
}
.retry {
  background: #0f766e;
  color: #fff;
  border: none;
}
.item-hover {
  opacity: 0.92;
}
.head {
  display: flex;
  align-items: center;
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
.tag.high {
  color: #b45309;
  background: #fef3c7;
}
.tag.urgent {
  color: #b91c1c;
  background: #fee2e2;
}
.unread-dot {
  color: #fff;
  background: #ef4444;
  font-size: 20rpx;
  line-height: 1;
  padding: 6rpx 10rpx;
  border-radius: 999rpx;
  margin-left: 8rpx;
}
.time {
  margin-left: auto;
  color: #94a3b8;
  font-size: 22rpx;
}
.title {
  display: block;
  font-size: 32rpx;
  font-weight: 650;
  color: #134e4a;
  line-height: 1.4;
}
.preview {
  display: block;
  margin-top: 10rpx;
  font-size: 26rpx;
  color: #64748b;
  line-height: 1.55;
}
.action {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: #0f766e;
  font-weight: 600;
}
</style>
