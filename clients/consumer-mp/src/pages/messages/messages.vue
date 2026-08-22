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

      <view v-if="pendingCount > 0" class="todo-banner" @click="goPendingOrders">
        <view class="todo-copy">
          <text class="todo-title">待办 · 待支付账单</text>
          <text class="todo-sub">有 {{ pendingCount }} 笔订单待补缴，公告请看「通知公告」</text>
        </view>
        <text class="todo-go">去处理 ›</text>
      </view>

      <view class="filter-row">
        <scroll-view scroll-x class="filter-scroll" :show-scrollbar="false" enable-flex>
          <view class="filter-inner">
            <text
              v-for="f in filters"
              :key="f.key"
              class="filter-chip"
              :class="{ active: filter === f.key }"
              @click="filter = f.key"
              >{{ f.label }}{{ filterCountSuffix(f.key) }}</text
            >
          </view>
        </scroll-view>
      </view>

      <view v-if="loading && !list.length" class="loading"><text>加载中…</text></view>
      <view v-else-if="!visibleList.length" class="empty">
        <text class="empty-title">{{ emptyTitle }}</text>
        <text class="empty-hint">订单支付、充值到账、优惠券提醒等会出现在这里</text>
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
import { computed, ref } from 'vue';
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
const pendingCount = ref(0);
type MsgFilter = 'all' | 'unread' | 'ORDER' | 'DISPUTE' | 'COUPON' | 'POINTS' | 'RECHARGE' | 'OTHER';
const filter = ref<MsgFilter>('all');
const filters: Array<{ key: MsgFilter; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'unread', label: '未读' },
  { key: 'ORDER', label: '订单' },
  { key: 'DISPUTE', label: '售后' },
  { key: 'COUPON', label: '优惠券' },
  { key: 'POINTS', label: '积分' },
  { key: 'RECHARGE', label: '充值' },
  { key: 'OTHER', label: '其他' }
];

function matchBizFilter(m: NotificationDto, key: MsgFilter) {
  const t = String(m.bizType || '').toUpperCase();
  if (key === 'ORDER') return t === 'ORDER';
  if (key === 'DISPUTE') return t === 'DISPUTE';
  if (key === 'COUPON') return t === 'COUPON';
  if (key === 'POINTS') return t === 'POINTS';
  if (key === 'RECHARGE') return t === 'RECHARGE';
  if (key === 'OTHER') {
    return !['ORDER', 'DISPUTE', 'COUPON', 'POINTS', 'RECHARGE'].includes(t);
  }
  return true;
}

const visibleList = computed(() => {
  if (filter.value === 'all') return list.value;
  if (filter.value === 'unread') return list.value.filter((m) => !m.read);
  return list.value.filter((m) => matchBizFilter(m, filter.value));
});

const emptyTitle = computed(() => {
  if (filter.value === 'unread') return '暂无未读消息';
  if (filter.value === 'all') return '暂无消息';
  return `暂无${filters.find((f) => f.key === filter.value)?.label || ''}消息`;
});

function filterCountSuffix(key: MsgFilter) {
  if (key === 'all') return list.value.length ? ` ${list.value.length}` : '';
  if (key === 'unread') {
    const n = list.value.filter((m) => !m.read).length;
    return n ? ` ${n}` : '';
  }
  const n = list.value.filter((m) => matchBizFilter(m, key)).length;
  return n ? ` ${n}` : '';
}

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
  if (!list.value.length) loading.value = true;
  try {
    const [rows, count, prefList, cfg, orders] = await Promise.all([
      consumerApi.notifications(100),
      consumerApi.notificationUnreadCount(),
      consumerApi.notifyPrefs(),
      consumerApi.consumerPublicConfig(),
      consumerApi.listOrders(0, 50).catch(() => null)
    ]);
    list.value = rows;
    unread.value = Number(count?.count || 0);
    prefs.value = prefList;
    subscribeEnabled.value = cfg?.wechatSubscribeEnabled === 'true';
    subscribeTemplateId.value = String(cfg?.wechatSubscribeTemplateId || '');
    const items = orders?.items || orders?.content || [];
    pendingCount.value = items.filter(
      (o: { status?: string }) => o.status === 'PENDING' || o.status === 'UNPAID'
    ).length;
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

function goPendingOrders() {
  uni.navigateTo({ url: '/pages/orders/orders?status=PENDING' });
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
  const type = String(m.bizType || '').toUpperCase();
  switch (type) {
    case 'ORDER':
      if (id) uni.navigateTo({ url: `/pages/order-detail/order-detail?orderId=${id}` });
      else uni.navigateTo({ url: '/pages/orders/orders' });
      break;
    case 'DISPUTE':
      uni.navigateTo({
        url: id
          ? `/pages/dispute/detail?ticketId=${id}`
          : '/pages/orders/orders'
      });
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
    case 'CAMPAIGN':
    case 'MARKETING':
      uni.navigateTo({ url: '/pages/marketing/index' });
      break;
    case 'ANNOUNCEMENT':
      uni.navigateTo({
        url: id
          ? `/pages/announcements/detail?id=${id}`
          : '/pages/announcements/announcements'
      });
      break;
    default:
      break;
  }
}

function bizTypeLabel(type?: string) {
  const t = String(type || '').toUpperCase();
  if (t === 'ORDER') return '订单';
  if (t === 'DISPUTE') return '售后';
  if (t === 'RECHARGE') return '充值';
  if (t === 'COUPON') return '优惠券';
  if (t === 'POINTS') return '积分';
  if (t === 'RECALL' || t === 'CAMPAIGN' || t === 'MARKETING') return '活动';
  if (t === 'ANNOUNCEMENT') return '公告';
  return '';
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
.filter-row {
  margin-bottom: 8rpx;
}
.filter-scroll {
  width: 100%;
  white-space: nowrap;
}
.filter-inner {
  display: inline-flex;
  gap: 12rpx;
  padding: 4rpx 0;
}
.filter-chip {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  padding: 10rpx 22rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  color: #64748b;
  font-size: 24rpx;
}
.filter-chip.active {
  background: #ecfdf5;
  color: #059669;
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
  color: #059669;
  background: #ecfdf5;
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
}
.msg-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #1f2a24;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  background: linear-gradient(135deg, #ecfdf5, #fff);
  border: 1rpx solid #d1fae5;
}
.todo-banner {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 16rpx;
  padding: 24rpx;
  border-radius: 22rpx;
  background: #fff7ed;
  border: 1rpx solid #fdba74;
}
.todo-copy {
  flex: 1;
  min-width: 0;
}
.todo-title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #9a3412;
}
.todo-sub {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #c2410c;
}
.todo-go {
  font-size: 26rpx;
  color: #ea580c;
  font-weight: 600;
  white-space: nowrap;
}
.subscribe-copy {
  flex: 1;
  min-width: 0;
}
.subscribe-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #14201b;
}
.subscribe-sub {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #64748b;
}
.subscribe-btn {
  margin: 0;
  padding: 0 26rpx;
  min-height: 60rpx;
  height: 60rpx;
  line-height: 1.2;
  border-radius: 999rpx;
  font-size: 24rpx;
  color: #fff;
  background: linear-gradient(135deg, #047857, #059669);
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
</style>
