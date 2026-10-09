<template>
  <view class="page-root">
    <app-nav-bar title="消息中心" home-url="/pages/home/home" />
    <app-underline-tabs :items="filterTabs" :value="filter" @change="onFilterChange" />
    <view class="page-body">
      <view v-if="loading && !list.length" class="loading"
        ><text>{{ UI_COPY.loading }}</text></view
      >
      <empty-state
        v-else-if="!visibleList.length"
        icon="/static/menu/notice.png"
        :title="emptyTitle"
        hint="补货、结算到账会出现在这里。争议和库存请去「待办」。"
      />
      <view v-else class="wx-cells">
        <view
          v-for="m in visibleList"
          role="button"
          :key="m.id"
          class="wx-cell"
          :class="{ unread: !m.read }"
          hover-class="wx-cell-hover"
          @click="onOpen(m)"
        >
          <view class="msg-head">
            <view class="msg-title-row">
              <view v-if="!m.read" class="wx-dot" />
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
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { showError } from '@/utils/notify';
import { onLoad, onShow } from '@dcloudio/uni-app';
import { merchantApi, hasPerm } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { OpenApiNotificationDto } from '@aicabinet/shared-types';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import {
  displayBizNo,
  formatDateTimeMinute,
  rewriteBizNosInText,
  sanitizeNotifyTitle
} from '@aicabinet/shared-uni/format';

const { me } = useMerchantMe();

const loading = ref(false);
const list = ref<OpenApiNotificationDto[]>([]);
type MsgFilter =
  'all' | 'unread' | 'REPLENISHMENT' | 'DISPUTE' | 'ORDER' | 'SETTLEMENT' | 'WALLET' | 'OTHER';
const filter = ref<MsgFilter>('all');
const filters: Array<{ key: MsgFilter; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'unread', label: '未读' },
  { key: 'REPLENISHMENT', label: '补货' },
  { key: 'DISPUTE', label: '争议' },
  { key: 'ORDER', label: '订单' },
  { key: 'SETTLEMENT', label: '结算' },
  { key: 'WALLET', label: '钱包' },
  { key: 'OTHER', label: '其他' }
];

function matchBizFilter(m: OpenApiNotificationDto, key: MsgFilter) {
  const t = String(m.bizType || '').toUpperCase();
  if (key === 'REPLENISHMENT') return t === 'REPLENISHMENT';
  if (key === 'DISPUTE') return t === 'DISPUTE';
  if (key === 'ORDER') return t === 'ORDER';
  if (key === 'SETTLEMENT') return t === 'SETTLEMENT' || t === 'SPLIT';
  if (key === 'WALLET') return t === 'WALLET' || t === 'WITHDRAW';
  if (key === 'OTHER') {
    return ![
      'REPLENISHMENT',
      'DISPUTE',
      'ORDER',
      'SETTLEMENT',
      'SPLIT',
      'WALLET',
      'WITHDRAW'
    ].includes(t);
  }
  return true;
}

const visibleList = computed(() => {
  if (filter.value === 'all') return list.value;
  if (filter.value === 'unread') return list.value.filter((m) => !m.read);
  return list.value.filter((m) => matchBizFilter(m, filter.value));
});

const unreadCount = computed(() => list.value.filter((m) => !m.read).length);
const unreadBadge = computed(() => (unreadCount.value > 99 ? '99+' : String(unreadCount.value)));
const filterTabs = computed(() =>
  filters.map((f) => ({
    key: f.key,
    label: f.label,
    badge: f.key === 'unread' && unreadCount.value > 0 ? unreadBadge.value : undefined
  }))
);

function onFilterChange(key: string) {
  filter.value = key as MsgFilter;
}

const emptyTitle = computed(() => {
  if (filter.value === 'unread') return '暂无未读消息';
  if (filter.value === 'all') return '暂无消息';
  return `暂无${filters.find((f) => f.key === filter.value)?.label || ''}消息`;
});

function applyEntryQuery(opts?: Record<string, string | undefined>) {
  const raw = String(opts?.filter || opts?.bizType || opts?.type || '')
    .trim()
    .toUpperCase();
  if (!raw) return;
  if (raw === 'SPLIT') {
    filter.value = 'SETTLEMENT';
    return;
  }
  if (raw === 'WITHDRAW') {
    filter.value = 'WALLET';
    return;
  }
  const hit = filters.find((f) => f.key === raw);
  if (hit) filter.value = hit.key;
}

onLoad((opts) => {
  applyEntryQuery(opts as Record<string, string | undefined>);
});

onShow(load);

async function load() {
  if (!list.value.length) loading.value = true;
  try {
    list.value = await merchantApi.notifications(100);
  } catch (e) {
    showError(e instanceof Error ? e.message : '加载失败');
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

async function markNotificationReadIfNeeded(m: OpenApiNotificationDto) {
  // id 缺失时无法标记已读（生成类型可选），直接跳过，避免打到 /notifications/undefined/read
  if (m.read || m.id == null) return;
  try {
    await merchantApi.markNotificationRead(m.id);
    m.read = true;
  } catch {
    /* 忽略已读失败 */
  }
}

function navigateReplenishment(id: string) {
  uni.navigateTo({
    url: id
      ? `/pages/replenishment/replenishment?taskId=${id}`
      : '/pages/replenishment/replenishment'
  });
}

function navigateDispute(id: string) {
  uni.navigateTo({
    url: id ? `/pages/disputes/disputes?ticketId=${id}` : '/pages/disputes/disputes'
  });
}

function navigateOrder(id: string) {
  uni.navigateTo({
    url: id ? `/pages/order-detail/order-detail?orderId=${id}` : '/pages/orders/orders'
  });
}

function navigateSettlement(id: string) {
  uni.navigateTo({
    url: id ? `/pages/splits/splits?status=FAILED&orderId=${id}` : '/pages/settlements/settlements'
  });
}

function navigateWallet(_id: string) {
  // 「无权限=看不见」：无 wallet:view 时入口不渲染，此守卫兜底消息深链直跳
  if (!hasPerm(me.value, 'merchant:wallet:view')) {
    showError('当前账号无钱包查看权限');
    return;
  }
  uni.navigateTo({ url: '/pages/wallet/wallet' });
}

function navigateAlerts(_id: string) {
  uni.switchTab({ url: '/pages/alerts/alerts' });
}

function navigateAnnouncement(id: string) {
  uni.navigateTo({
    url: id ? `/pages/announcements/detail?id=${id}` : '/pages/announcements/announcements'
  });
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

async function onOpen(m: OpenApiNotificationDto) {
  await markNotificationReadIfNeeded(m);
  const id = m.bizId ? encodeURIComponent(m.bizId) : '';
  navigateForNotification(String(m.bizType || '').toUpperCase(), id);
}

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '暂无');
}
</script>

<style scoped>
.page-root {
  min-height: 100%;
  padding: 0;
  background: var(--page-bg, #ededed);
  box-sizing: border-box;
}
.page-body {
  padding-left: 0;
  padding-right: 0;
  padding-top: 0;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
}
.wx-cell-hover {
  background: #ececec !important;
}
.msg-card.unread,
.wx-cell.unread {
  border-left: none;
}
.loading {
  padding: 120rpx 0;
  text-align: center;
  color: var(--text-muted, #8a968e);
}
.empty {
  padding: 80rpx 48rpx;
  text-align: center;
}
.empty-title {
  display: block;
  font-size: var(--font-size-md);
  color: var(--text-muted);
}
.empty-hint {
  display: block;
  margin-top: 8rpx;
  padding: 0 24rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  line-height: 1.55;
}
.msg-card {
  display: none;
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
  font-size: var(--font-size-xs);
  color: var(--brand);
  background: var(--brand-soft);
  padding: 2rpx 10rpx;
  border-radius: var(--radius-tag);
}
.msg-title {
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.msg-time {
  flex-shrink: 0;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.msg-body {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  line-height: 1.5;
}
.msg-biz {
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
</style>
