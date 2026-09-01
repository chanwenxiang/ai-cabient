<template>
  <view class="page">
    <app-nav-bar title="公告详情" />
    <view class="page-body">
      <view v-if="loading && !item" class="card state">加载中…</view>
      <view v-else-if="error && !item" class="card state">
        <text class="err">{{ error }}</text>
        <button class="retry" @click="() => load()">重试</button>
      </view>
      <view v-else-if="item" class="card article">
        <view class="meta">
          <text
            v-if="priorityLabel(item.priority)"
            class="tag"
            :class="priorityClass(item.priority)"
          >
            {{ priorityLabel(item.priority) }}
          </text>
          <text class="time">{{ formatTime(item.publishAt) }}</text>
        </view>
        <text class="title">{{ item.title }}</text>
        <text class="content">{{ item.content }}</text>
      </view>
    </view></view
  >
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { useAnnouncementDetail } from '@aicabinet/shared-uni/announcements';
import { merchantApi } from '@/utils/merchant-api';

const { loading, error, item, load, formatTime, priorityLabel, priorityClass } =
  useAnnouncementDetail((id) => merchantApi.getAnnouncement(id));

onLoad((query) => {
  const announceId = Number(query?.id || 0);
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  load(announceId);
});
</script>

<style scoped>
.page {
  padding: 0;
  min-height: 100vh;
  box-sizing: border-box;
}
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
.err {
  color: #b91c1c;
  text-align: center;
}
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
.tag.high {
  color: #b45309;
  background: #fef3c7;
}
.tag.urgent {
  color: #b91c1c;
  background: #fee2e2;
}
.time {
  color: #94a3b8;
  font-size: 24rpx;
}
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
.page-body {
  padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
