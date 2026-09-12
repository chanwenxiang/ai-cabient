<template>
  <view class="page">
    <app-nav-bar title="公告详情" />
    <view class="page-body">
      <view v-if="loading && !item" class="card state">{{ UI_COPY.loading }}</view>
      <view v-else-if="error && !item" class="card state">
        <text class="err">{{ error }}</text>
        <app-button label="重试" @click="() => load()" />
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
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

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
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  padding: 32rpx 28rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
}
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  color: var(--text-muted);
}
.err {
  color: var(--color-danger);
  text-align: center;
}
.retry {
  background: var(--brand);
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
  font-size: var(--font-size-sm);
  line-height: 1;
  padding: 8rpx 12rpx;
  border-radius: var(--radius-pill);
  font-weight: 600;
}
.tag.high {
  color: var(--warning, #b45309);
  background: color-mix(in srgb, var(--warning, #b45309) 14%, #fff);
}
.tag.urgent {
  color: var(--color-danger);
  background: color-mix(in srgb, var(--danger, #b91c1c) 12%, #fff);
}
.time {
  color: var(--text-subtle);
  font-size: var(--font-size-caption);
}
.title {
  display: block;
  font-size: var(--font-size-h2);
  font-weight: 700;
  color: var(--brand-deep);
  line-height: 1.35;
  margin-bottom: 24rpx;
}
.content {
  display: block;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: var(--font-size-lg);
  color: var(--text-muted, #334155);
  line-height: 1.75;
}
.page-body {
  padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
