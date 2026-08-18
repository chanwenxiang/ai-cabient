<template>
  <view class="page-root">
    <app-nav-bar title="消息中心" />
    <view class="page-body">
    <view v-if="loading" class="loading"><text>加载中…</text></view>
    <view v-else-if="!list.length" class="empty">
      <text class="empty-title">暂无消息</text>
      <text class="empty-hint">补货任务指派、结算到账等消息会出现在这里</text>
    </view>
    <view v-else class="msg-list">
      <view
        v-for="m in list"
        :key="m.id"
        class="msg-card"
        :class="{ unread: !m.read }"
        @click="onOpen(m)"
      >
        <view class="msg-head">
          <text class="msg-title">{{ sanitizeNotifyTitle(m.title) }}</text>
          <text class="msg-time">{{ formatTime(m.createdAt) }}</text>
        </view>
        <text class="msg-body">{{ rewriteBizNosInText(m.body) }}</text>
        <view v-if="m.bizId" class="msg-biz">关联单号：{{ displayBizNo(m.bizId) }}</view>
      </view>
    </view>
  
    </view></view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { merchantApi, type MerchantNotificationDto } from '@/utils/merchant-api';
import {
  displayBizNo,
  formatDateTimeMinute,
  rewriteBizNosInText,
  sanitizeNotifyTitle
} from '@aicabinet/shared-uni/format';

const loading = ref(false);
const list = ref<MerchantNotificationDto[]>([]);

onShow(load);

async function load() {
  loading.value = true;
  try {
    list.value = await merchantApi.notifications(100);
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

async function onOpen(m: MerchantNotificationDto) {
  if (m.read) return;
  try {
    await merchantApi.markNotificationRead(m.id);
    m.read = true;
  } catch {
    /* 忽略已读失败 */
  }
  const id = m.bizId ? encodeURIComponent(m.bizId) : '';
  if (m.bizType === 'REPLENISHMENT' && id) {
    uni.navigateTo({ url: `/pages/replenishment/replenishment?taskId=${id}` });
  }
}

function formatTime(t: string) {
  return formatDateTimeMinute(t, '—');
}
</script>

<style scoped>
.page-root {
  min-height: 100%;
  padding: 0;
  background: #ffffff;
  box-sizing: border-box;
}
.loading {
  padding: 120rpx 0;
  text-align: center;
  color: #8a968e;
}
.empty {
  padding: 120rpx 0;
  text-align: center;
}
.empty-title {
  display: block;
  font-size: 28rpx;
  color: #4b5563;
}
.empty-hint {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #9aa4a0;
}
.msg-card {
  margin-top: 18rpx;
  padding: 26rpx 24rpx;
  border-radius: 22rpx;
  background: #fff;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.msg-card.unread {
  border-left: 6rpx solid #059669;
}
.msg-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 16rpx;
}
.msg-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #1f2a24;
}
.msg-time {
  flex-shrink: 0;
  font-size: 20rpx;
  color: #9aa4a0;
}
.msg-body {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  line-height: 1.55;
  color: #4b5563;
}
.msg-biz {
  margin-top: 10rpx;
  font-size: 20rpx;
  color: #8a968e;
}
.page-body {
  padding: 24rpx 24rpx 48rpx;
  box-sizing: border-box;
}
</style>
