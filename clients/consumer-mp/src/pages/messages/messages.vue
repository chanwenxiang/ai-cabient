<template>
  <view class="page-root">
    <app-nav-bar title="消息中心">
      <template #right>
        <text v-if="unread > 0" class="nav-read-all" @click.stop="markAllRead">全部已读</text>
      </template>
    </app-nav-bar>
    <view class="page-body">
      <view v-if="subscribeEnabled" class="subscribe-banner">
        <view class="subscribe-copy">
          <text class="subscribe-title">开启微信消息提醒</text>
          <text class="subscribe-sub">订单支付、充值到账、优惠券与积分提醒及时送达</text>
        </view>
        <button class="subscribe-btn" :disabled="subscribing" @click="onSubscribe">
          {{ subscribing ? '请求中…' : '去开启' }}
        </button>
      </view>

      <view v-if="loading" class="loading"><text>加载中…</text></view>
      <view v-else-if="!list.length" class="empty">
        <text class="empty-title">暂无消息</text>
        <text class="empty-hint">订单支付、充值到账、优惠券提醒等会出现在这里</text>
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

      <view class="card prefs-card">
        <text class="card-title">通知偏好</text>
        <text class="card-hint">关闭后对应类别的消息不再推送与提醒</text>
        <view v-for="p in prefs" :key="p.category" class="pref-row">
          <text class="pref-label">{{ p.label }}</text>
          <switch :checked="p.enabled" color="#059669" @change="onPrefChange(p, $event)" />
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import {
  consumerApi,
  ensureConsumerAuth,
  type NotificationDto,
  type NotifyPrefDto
} from '@/utils/consumer-api';
import {
  displayBizNo,
  formatDateTimeMinute,
  rewriteBizNosInText,
  sanitizeNotifyTitle
} from '@aicabinet/shared-uni/format';

const loading = ref(false);
const list = ref<NotificationDto[]>([]);
const unread = ref(0);
const prefs = ref<NotifyPrefDto[]>([]);
const subscribeEnabled = ref(false);
const subscribeTemplateId = ref('');
const subscribing = ref(false);

onShow(async () => {
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/messages/messages')
    });
    return;
  }
  await load();
});

async function load() {
  loading.value = true;
  try {
    const [rows, count, prefList, cfg] = await Promise.all([
      consumerApi.notifications(100),
      consumerApi.notificationUnreadCount(),
      consumerApi.notifyPrefs(),
      consumerApi.consumerPublicConfig()
    ]);
    list.value = rows;
    unread.value = Number(count?.count || 0);
    prefs.value = prefList;
    subscribeEnabled.value = cfg?.wechatSubscribeEnabled === 'true';
    subscribeTemplateId.value = String(cfg?.wechatSubscribeTemplateId || '');
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

function onSubscribe() {
  if (!subscribeTemplateId.value) return;
  subscribing.value = true;
  uni.requestSubscribeMessage({
    tmplIds: [subscribeTemplateId.value],
    success: (res) => {
      const status = Reflect.get(res as object, subscribeTemplateId.value);
      const accept = status === 'accept';
      uni.showToast({
        title: accept ? '已开启，消息将及时送达' : '未开启，可在设置中打开',
        icon: accept ? 'success' : 'none'
      });
    },
    fail: () => {
      uni.showToast({ title: '当前环境不支持订阅授权', icon: 'none' });
    },
    complete: () => {
      subscribing.value = false;
    }
  });
}

async function onPrefChange(p: NotifyPrefDto, ev: { detail?: { value?: boolean } } | Event) {
  const detail = (ev as { detail?: { value?: boolean } })?.detail;
  const enabled = !!detail?.value;
  const prev = p.enabled;
  p.enabled = enabled;
  try {
    await consumerApi.updateNotifyPref(p.category, enabled);
  } catch (e) {
    p.enabled = prev;
    uni.showToast({ title: e instanceof Error ? e.message : '设置失败', icon: 'none' });
  }
}

async function onOpen(m: NotificationDto) {
  if (!m.read) {
    try {
      await consumerApi.markNotificationRead(m.id);
      m.read = true;
      unread.value = Math.max(0, unread.value - 1);
    } catch {
      /* 忽略已读失败 */
    }
  }
  goByBiz(m);
}

function goByBiz(m: NotificationDto) {
  const id = m.bizId ? encodeURIComponent(m.bizId) : '';
  switch (m.bizType) {
    case 'ORDER':
      if (id) uni.navigateTo({ url: `/pages/order-detail/order-detail?orderId=${id}` });
      break;
    case 'RECHARGE':
      uni.navigateTo({ url: '/pages/recharge/recharge' });
      break;
    case 'COUPON':
      uni.navigateTo({ url: '/pages/coupons/coupons' });
      break;
    case 'POINTS':
      uni.navigateTo({ url: '/pages/points/points' });
      break;
    case 'RECALL':
      uni.navigateTo({ url: '/pages/marketing/index' });
      break;
    default:
      break;
  }
}

async function markAllRead() {
  try {
    await consumerApi.markAllNotificationsRead();
    list.value.forEach((m) => (m.read = true));
    unread.value = 0;
    uni.showToast({ title: '已全部标记为已读', icon: 'none' });
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '操作失败', icon: 'none' });
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
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.nav-read-all {
  font-size: 24rpx;
  color: #ffffff;
  opacity: 0.92;
  white-space: nowrap;
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
.card {
  margin-top: 24rpx;
  padding: 26rpx 24rpx;
  border-radius: 22rpx;
  background: #fff;
}
.card-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #1f2a24;
}
.card-hint {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #9aa4a0;
}
.pref-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18rpx 0 6rpx;
  border-bottom: 1rpx solid #f0f2f1;
}
.pref-row:last-child {
  border-bottom: none;
}
.pref-label {
  font-size: 26rpx;
  color: #1f2a24;
}
.subscribe-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-bottom: 16rpx;
  padding: 24rpx;
  border-radius: 22rpx;
  background: linear-gradient(135deg, #064e3b 0%, #059669 60%, #059669 100%);
}
.subscribe-copy {
  flex: 1;
  min-width: 0;
}
.subscribe-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #fff;
}
.subscribe-sub {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.85);
}
.subscribe-btn {
  margin: 0;
  padding: 0 26rpx;
  height: 60rpx;
  line-height: 60rpx;
  border-radius: 999rpx;
  font-size: 24rpx;
  color: #064e3b;
  background: #fff;
}
</style>
