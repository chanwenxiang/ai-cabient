<template>
  <view class="page">
    <app-nav-bar title="通知公告" />
    <view class="page-body">
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
  
    </view></view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { useAnnouncementsList } from '@aicabinet/shared-uni/announcements';
import { merchantApi } from '@/utils/merchant-api';

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
} = useAnnouncementsList(() => merchantApi.listAnnouncements());

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
.page-body {
  padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
