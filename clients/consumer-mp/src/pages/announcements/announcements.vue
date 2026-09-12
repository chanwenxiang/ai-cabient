<template>
  <view class="page page-root">
    <app-nav-bar title="通知公告" />
    <view class="page-body">
      <view v-if="loading && !list.length" class="state">{{ UI_COPY.loading }}</view>
      <view v-else-if="error && !list.length" class="state">
        <text class="err">{{ error }}</text>
        <app-button label="重试" @click="load" />
      </view>
      <empty-state
        v-else-if="!list.length"
        icon="/static/menu/notice.png"
        title="暂无通知公告"
        hint="平台维护、活动与规则变更会在这里发布"
      />
      <view v-else class="list">
        <view
          v-for="item in list" role="button"
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
            <text v-if="typeLabel(item.announceType)" class="tag type">{{
              typeLabel(item.announceType)
            }}</text>
            <text v-if="unread(item.announceId)" class="unread-dot" aria-label="未读">新</text>
            <text class="time">{{ formatTime(item.publishAt) }}</text>
          </view>
          <text class="title">{{ item.title }}</text>
          <text class="preview">{{ previewText(item.content) }}</text>
          <text v-if="expireHint(item.expireAt)" class="expire">{{
            expireHint(item.expireAt)
          }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { useAnnouncementsList } from '@aicabinet/shared-uni/announcements';
import { consumerApi } from '@/utils/consumer-api';
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
} = useAnnouncementsList(() => consumerApi.listAnnouncements(), { previewMax: 72 });

function typeLabel(t?: string) {
  const v = String(t || '').toUpperCase();
  if (v === 'MAINTENANCE') return '维护';
  if (v === 'ACTIVITY' || v === 'CAMPAIGN') return '活动';
  if (v === 'RULE' || v === 'POLICY') return '规则';
  if (v === 'SYSTEM') return '系统';
  return t ? String(t) : '';
}

function expireHint(expireAt?: string) {
  if (!expireAt) return '';
  const t = new Date(expireAt).getTime();
  if (!Number.isFinite(t)) return '';
  const diff = t - Date.now();
  if (diff <= 0) return '已过展示期';
  const days = Math.ceil(diff / (24 * 60 * 60 * 1000));
  if (days <= 3) return `展示至 ${formatTime(expireAt)} · 即将下线`;
  return `展示至 ${formatTime(expireAt)}`;
}

onShow(() => {
  void load();
});
</script>

<style scoped>
.page {
  min-height: 100%;
  padding: 0;
  background: var(--card-bg, #ffffff);
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
  color: var(--text-muted);
  font-size: var(--font-size-md);
}
.err {
  color: var(--color-danger);
  text-align: center;
}
.retry {
  margin-top: 8rpx;
  background: var(--brand);
  color: var(--white);
  border: none;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  padding: 28rpx 28rpx 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.04);
}
.card-hover {
  opacity: 0.92;
}
.card-head {
  display: flex;
  flex-wrap: wrap;
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
.tag.type {
  color: var(--brand);
  background: var(--brand-soft);
}
.tag.high {
  color: var(--warning, #b45309);
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
}
.tag.urgent {
  color: var(--color-danger);
  background: color-mix(in srgb, var(--danger, #b91c1c) 12%, var(--white));
}
.unread-dot {
  color: var(--white);
  background: var(--color-danger);
  font-size: var(--font-size-xs);
  line-height: 1;
  padding: 6rpx 10rpx;
  border-radius: var(--radius-pill);
}
.time {
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
  margin-left: auto;
}
.title {
  display: block;
  font-size: var(--font-size-xl);
  font-weight: 650;
  color: var(--text-primary, #0f172a);
  line-height: 1.4;
}
.preview {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-body);
  color: var(--text-muted);
  line-height: 1.55;
}
.expire {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-sm);
  color: var(--warning, #b45309);
}
</style>
