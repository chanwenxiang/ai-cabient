<template>
  <view class="page">
    <app-nav-bar title="公告详情" />
    <view v-if="loading && !item" class="state">加载中…</view>
    <view v-else-if="error && !item" class="state">
      <text class="err">{{ error }}</text>
      <button class="retry" @click="() => load()">重试</button>
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
  background: #ffffff;
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
  background: var(--brand, #047857);
  color: #fff;
  border: none;
}
.article {
  margin: 20rpx 24rpx 0;
  background: #fff;
  border-radius: 16rpx;
  padding: 28rpx 24rpx;
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
.tag.type {
  color: #047857;
  background: #ecfdf5;
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
  color: #0f172a;
  line-height: 1.35;
  margin-bottom: 16rpx;
}
.expire,
.scope {
  display: block;
  margin-bottom: 12rpx;
  font-size: 24rpx;
  color: #b45309;
}
.scope {
  color: #64748b;
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
