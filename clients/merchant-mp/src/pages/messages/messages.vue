<template>
  <view class="page-root">
    <app-nav-bar title="消息中心" />
    <view class="page-body">
      <view class="filter-row">
        <text
          v-for="f in filters"
          :key="f.key"
          class="filter-chip"
          :class="{ active: filter === f.key }"
          @click="filter = f.key"
          >{{ f.label }}</text
        >
      </view>
      <view v-if="loading && !list.length" class="loading"><text>加载中…</text></view>
      <view v-else-if="!visibleList.length" class="empty">
        <text class="empty-title">{{ filter === 'unread' ? '暂无未读消息' : '暂无消息' }}</text>
        <text class="empty-hint"
          >补货任务指派、结算到账等消息会出现在这里；争议/库存待办请看「待办」页</text
        >
      </view>
      <view v-else class="msg-list">
        <view
          v-for="m in visibleList"
          :key="m.id"
          class="msg-card"
          :class="{ unread: !m.read }"
          @click="onOpen(m)"
        >
          <view class="msg-head">
            <view class="msg-title-row">
              <text v-if="bizTypeLabel(m.bizType)" class="biz-tag">{{
                bizTypeLabel(m.bizType)
              }}</text>
              <text class="msg-title">{{ sanitizeNotifyTitle(m.title) }}</text>
            </view>
            <text class="msg-time">{{ formatTime(m.createdAt) }}</text>
          </view>
          <text class="msg-body">{{ rewriteBizNosInText(m.body) }}</text>
          <view v-if="m.bizId" class="msg-biz">关联单号：{{ displayBizNo(m.bizId) }}</view>
        </view>
      </view>
    </view></view
  >
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
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
const filter = ref<'all' | 'unread'>('all');
const filters = [
  { key: 'all' as const, label: '全部' },
  { key: 'unread' as const, label: '未读' }
];

const visibleList = computed(() =>
  filter.value === 'unread' ? list.value.filter((m) => !m.read) : list.value
);

onShow(load);

async function load() {
  if (!list.value.length) loading.value = true;
  try {
    list.value = await merchantApi.notifications(100);
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

function bizTypeLabel(type?: string) {
  const t = String(type || '').toUpperCase();
  if (t === 'REPLENISHMENT') return '补货';
  if (t === 'DISPUTE') return '争议';
  if (t === 'ORDER') return '订单';
  if (t === 'SETTLEMENT' || t === 'SPLIT') return '结算';
  if (t === 'WALLET' || t === 'WITHDRAW') return '钱包';
  if (t === 'DEVICE' || t === 'ALERT') return '告警';
  if (t === 'ANNOUNCEMENT') return '公告';
  return '';
}

async function markNotificationReadIfNeeded(m: MerchantNotificationDto) {
  if (m.read) return;
  try {
    await merchantApi.markNotificationRead(m.id);
    m.read = true;
  } catch {
    /* 忽略已读失败 */
  }
}

function navigateReplenishment(id: string) {
  if (!id) return;
  uni.navigateTo({ url: `/pages/replenishment/replenishment?taskId=${id}` });
}

function navigateDispute(id: string) {
  uni.navigateTo({
    url: id ? `/pages/disputes/disputes?ticketId=${id}` : '/pages/disputes/disputes'
  });
}

function navigateOrder(id: string) {
  if (!id) return;
  uni.navigateTo({ url: `/pages/order-detail/order-detail?orderId=${id}` });
}

function navigateSettlement() {
  uni.navigateTo({ url: '/pages/settlements/settlements' });
}

function navigateWallet() {
  uni.navigateTo({ url: '/pages/wallet/wallet' });
}

function navigateAlerts() {
  uni.switchTab({ url: '/pages/alerts/alerts' });
}

function navigateAnnouncement(id: string) {
  if (!id) return;
  uni.navigateTo({ url: `/pages/announcements/detail?id=${id}` });
}

const NOTIFICATION_NAVIGATORS: Record<string, (id: string) => void> = {
  REPLENISHMENT: navigateReplenishment,
  DISPUTE: navigateDispute,
  ORDER: navigateOrder,
  SETTLEMENT: navigateSettlement,
  SPLIT: navigateSettlement,
  WALLET: navigateWallet,
  WITHDRAW: navigateWallet,
  DEVICE: navigateAlerts,
  ALERT: navigateAlerts,
  ANNOUNCEMENT: navigateAnnouncement
};

function navigateForNotification(type: string, id: string) {
  NOTIFICATION_NAVIGATORS[type]?.(id);
}

async function onOpen(m: MerchantNotificationDto) {
  await markNotificationReadIfNeeded(m);
  const id = m.bizId ? encodeURIComponent(m.bizId) : '';
  navigateForNotification(String(m.bizType || '').toUpperCase(), id);
}

function formatTime(t: string) {
  return formatDateTimeMinute(t, '暂无');
}
</script>

<style scoped>
.page-root {
  min-height: 100%;
  padding: 0;
  background: #ffffff;
  box-sizing: border-box;
}
.filter-row {
  display: flex;
  gap: 12rpx;
  margin-bottom: 8rpx;
}
.filter-chip {
  padding: 10rpx 22rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  color: #64748b;
  font-size: 24rpx;
}
.filter-chip.active {
  background: #ecfdf5;
  color: #0f766e;
  font-weight: 600;
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
.msg-title-row {
  display: flex;
  align-items: center;
  gap: 10rpx;
  min-width: 0;
  flex: 1;
}
.biz-tag {
  flex-shrink: 0;
  font-size: 20rpx;
  color: #0f766e;
  background: #ecfdf5;
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
}
.msg-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.msg-time {
  flex-shrink: 0;
  font-size: 22rpx;
  color: #94a3b8;
}
.msg-body {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #64748b;
  line-height: 1.5;
}
.msg-biz {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
</style>
