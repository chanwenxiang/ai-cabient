<template>
  <view class="page">
    <app-nav-bar title="公告详情" />
    <view v-if="loading && !item" class="state">{{ UI_COPY.loading }}</view>
    <view v-else-if="error && !item" class="state">
      <text class="err">{{ error }}</text>
      <app-button label="重试" @click="() => load()" />
    </view>
    <view v-else-if="item" class="article">
      <view class="meta">
        <text v-if="priorityLabel(item.priority)" class="tag" :class="priorityClass(item.priority)">
          {{ priorityLabel(item.priority) }}
        </text>
        <text v-if="typeLabel(item.announceType)" class="tag type">{{
          typeLabel(item.announceType)
        }}</text>
        <text class="time">{{ formatTime(item.publishAt) }}</text>
      </view>
      <text class="title">{{ item.title }}</text>
      <text v-if="item.expireAt" class="expire">展示至 {{ formatTime(item.expireAt) }}</text>
      <text v-if="scopeText(item.targetScope)" class="scope">{{
        scopeText(item.targetScope)
      }}</text>
      <text class="content">{{ item.content }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { useAnnouncementDetail } from '@aicabinet/shared-uni/announcements';
import { consumerApi } from '@/utils/consumer-api';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

const { loading, error, item, load, formatTime, priorityLabel, priorityClass } =
  useAnnouncementDetail((id) => consumerApi.getAnnouncement(id));

function typeLabel(t?: string) {
  const v = String(t || '').toUpperCase();
  if (v === 'MAINTENANCE') return '维护';
  if (v === 'ACTIVITY' || v === 'CAMPAIGN') return '活动';
  if (v === 'RULE' || v === 'POLICY') return '规则';
  if (v === 'SYSTEM') return '系统';
  return t ? String(t) : '';
}

function scopeText(scope?: string) {
  const s = String(scope || '').toUpperCase();
  if (!s || s === 'ALL' || s === 'CONSUMER') return '';
  if (s === 'MERCHANT') return '面向商户（本页仅作同步查阅）';
  return `适用范围：${scope}`;
}

onLoad((query) => {
  load(Number(query?.id || 0));
});
</script>

<style scoped>
.page {
  min-height: 100%;
  padding: 0 0 64rpx;
  background: var(--card-bg, #ffffff);
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
  background: var(--brand, #0f766e);
  color: #fff;
  border: none;
}
.article {
  margin: 20rpx 24rpx 0;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 28rpx 24rpx;
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
.tag.type {
  color: var(--brand);
  background: var(--brand-soft);
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
  color: var(--text-primary, #0f172a);
  line-height: 1.35;
  margin-bottom: 16rpx;
}
.expire,
.scope {
  display: block;
  margin-bottom: 12rpx;
  font-size: var(--font-size-caption);
  color: var(--warning, #b45309);
}
.scope {
  color: var(--text-muted);
}
.content {
  display: block;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: var(--font-size-lg);
  color: var(--text-muted, #334155);
  line-height: 1.75;
}
</style>
