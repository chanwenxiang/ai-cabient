<template>
  <view class="page">
    <app-nav-bar title="通知公告" />
    <view class="page-body">
      <view v-if="loading && !list.length" class="card state">{{ UI_COPY.loading }}</view>
      <view v-else-if="error && !list.length" class="card state">
        <text class="err">{{ error }}</text>
        <app-button label="重试" @click="load" />
      </view>
      <empty-state
        v-else-if="!list.length"
        icon="/static/menu/notice.png"
        title="暂无平台公告"
        hint="运营发布的维护、活动与规则通知会出现在这里"
      />
      <view v-else>
        <view
          v-for="item in list" role="button"
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
          <text class="action app-link-chevron">查看详情</text>
        </view>
      </view>
    </view></view
  >
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { useAnnouncementsList } from '@aicabinet/shared-uni/announcements';
import { merchantApi } from '@/utils/merchant-api';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

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
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  padding: 28rpx;
  margin-bottom: 16rpx;
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
.unread-dot {
  color: #fff;
  background: var(--color-danger);
  font-size: var(--font-size-xs);
  line-height: 1;
  padding: 6rpx 10rpx;
  border-radius: var(--radius-pill);
  margin-left: 8rpx;
}
.time {
  margin-left: auto;
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
}
.title {
  display: block;
  font-size: var(--font-size-xl);
  font-weight: 650;
  color: var(--brand-deep);
  line-height: 1.4;
}
.preview {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-body);
  color: var(--text-muted);
  line-height: 1.55;
}
.action {
  display: block;
  margin-top: 16rpx;
  font-size: var(--font-size-caption);
  color: var(--brand);
  font-weight: 600;
  text-align: right;
  min-width: 140rpx;
}
.page-body {
  padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
