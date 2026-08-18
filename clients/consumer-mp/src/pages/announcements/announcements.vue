<template>
  <view class="page page-root">
    <app-nav-bar title="通知公告" />
    <view class="page-body">
    <view v-if="loading" class="state">加载中…</view>
    <view v-else-if="error" class="state">
      <text class="err">{{ error }}</text>
      <button class="retry" size="mini" @click="load">重试</button>
    </view>
    <empty-state
      v-else-if="!list.length"
      icon="/static/menu/notice.png"
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
      </view>
    </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { useAnnouncementsList } from '@aicabinet/shared-uni/announcements';
import { consumerApi } from '@/utils/consumer-api';

const {
  loading,
  error,
  list,
  unread,
  load,
  goDetail,
  formatTime,
  previewText,
  priorityLabel,
  priorityClass
} = useAnnouncementsList(() => consumerApi.listAnnouncements(), { previewMax: 72 });

onShow(() => {
  void load();
});
</script>

<style scoped>
.page {
  min-height: 100%;
  padding: 0;
  background: #ffffff;
  box-sizing: border-box;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
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
.err {
  color: #b91c1c;
  text-align: center;
}
.retry {
  margin-top: 8rpx;
  background: #059669;
  color: #fff;
  border: none;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.card {
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx 28rpx 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.04);
}
.card-hover {
  opacity: 0.92;
}
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
  color: #94a3b8;
  font-size: 22rpx;
  margin-left: auto;
}
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
