<template>
  <view class="page-root">
    <!--
      🔴 顶栏「全部已读」按钮已移除（2026-10-08 用户要求；此前口述为「去掉通知」，实指本按钮）。
      它挂在 nav-bar 的 #right 插槽上，紧贴标题右侧、真机视觉上像标题的一部分，
      且与列表内既有的单条已读操作并列时功能重叠。
      连带清理：markAllRead() 函数与 .nav-read-all 样式块。
      ⚠️ 未动 `consumer-api.ts` 的 markAllNotificationsRead —— 它是公共 API 定义（:854），
         本页只是不再调用，删定义会波及其他调用方。
      ⚠️ 保留 messages 页自身的未读筛选（app-underline-tabs）与订阅提醒入口 —— 那是消息中心的
      主体功能，不属于「通知按钮」。`unread` ref 也必须保留（未读筛选 / 单条已读后递减 / 卡片样式
      共 8 处引用），只删按钮不删计数。
    -->
    <app-nav-bar title="消息中心" />
    <app-underline-tabs :items="msgTabItems" :value="filter" @change="onMsgTab" />
    <view class="page-body">
      <view v-if="showSubscribeBanner" class="subscribe-banner">
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
        <text class="todo-go app-link-chevron">去处理</text>
      </view>

      <view v-if="loading && !list.length" class="loading">
        ><text>{{ UI_COPY.loading }}</text></view
      >
      <view v-else-if="!visibleList.length" class="empty">
        <text class="empty-title">{{ emptyTitle }}</text>
        <text class="empty-hint">订单支付、充值到账、优惠券提醒等会出现在这里</text>
      </view>
      <view v-else class="msg-list">
        <view
          v-for="m in visibleList"
          role="button"
          :key="m.id"
          class="msg-card wx-cell"
          :class="{ unread: !m.read }"
          hover-class="wx-cell-hover"
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

      <!--
        🔴 「通知偏好」只在「全部」档显示（2026-10-08 用户报「切换视图一直固定显示」）。
        成因：这张卡片原先**无条件渲染**，而消息页有 8 个档位（全部/未读/订单/售后/
        优惠券/积分/充值/其他）⇒ 切到任何一个档位，页尾都是同一张偏好卡，
        看起来像「换了视图但内容没变」。它管理的是**跨档位的推送开关**
        （六个类别一一对应下面六个档位），本身不属于任何单一档位的呈现内容。

        ⚠️ 同页另外两个常驻块**刻意不改**：
          · 订阅提醒横幅（:17）—— 已有 v-if 守卫（showSubscribeBanner），且是转化入口；
          · 「待办 · 待支付账单」（:27）—— 虽同样无条件渲染，但它是**欠费催缴**，
            收敛到「全部」档等于让用户在「未读」档看不到待支付账单。
        ⇒ 判定依据不是「都常显」，而是「这张卡是否属于当前档位的语义」。
      -->
      <view v-if="filter === 'all'" class="card prefs-card">
        <text class="card-title">通知偏好</text>
        <text class="card-hint">关闭后对应类别的消息不再推送与提醒</text>
        <view v-for="p in prefs" :key="p.category" class="pref-row">
          <text class="pref-label">{{ p.label }}</text>
          <switch :checked="p.enabled" color="var(--brand)" @change="onPrefChange(p, $event)" />
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { onLoad, onShow } from '@dcloudio/uni-app';
import {
  consumerApi,
  ensureConsumerAuth,
  type NotificationDto,
  type NotifyPrefDto
} from '@/utils/consumer-api';
import { softFallback } from '@/utils/soft-fallback';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
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
/** H5 无 requestSubscribeMessage，勿展示「去开启」入口（同 M-P2-14）。 */
const isMpWeixin = (() => {
  try {
    const info = uni.getSystemInfoSync() as { uniPlatform?: string };
    return info.uniPlatform === 'mp-weixin';
  } catch {
    return false;
  }
})();
const showSubscribeBanner = computed(
  () => isMpWeixin && subscribeEnabled.value && !!subscribeTemplateId.value
);
type MsgFilter =
  'all' | 'unread' | 'ORDER' | 'DISPUTE' | 'COUPON' | 'POINTS' | 'RECHARGE' | 'OTHER';
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
const msgTabItems = computed(() =>
  filters.map((f) => ({
    key: f.key,
    label: f.label,
    badge: filterCountSuffix(f.key).replace(/^[\s·]+/, '') || undefined
  }))
);
function onMsgTab(key: string) {
  filter.value = key as MsgFilter;
}

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

/** 分类角标：业务类优先展示未读数（·N），无未读时展示总数。 */
function filterCountSuffix(key: MsgFilter) {
  if (key === 'all') return list.value.length ? ` ${list.value.length}` : '';
  if (key === 'unread') {
    const n = list.value.filter((m) => !m.read).length;
    return n ? ` ${n}` : '';
  }
  const unreadN = list.value.filter((m) => !m.read && matchBizFilter(m, key)).length;
  if (unreadN > 0) return ` ·${unreadN}`;
  const n = list.value.filter((m) => matchBizFilter(m, key)).length;
  return n ? ` ${n}` : '';
}

function applyEntryQuery(opts?: Record<string, string | undefined>) {
  const raw = String(opts?.filter || opts?.bizType || opts?.type || '')
    .trim()
    .toUpperCase();
  if (!raw) return;
  const hit = filters.find((f) => f.key === raw);
  if (hit) filter.value = hit.key;
}

onLoad((opts) => {
  applyEntryQuery(opts as Record<string, string | undefined>);
});

onShow(async () => {
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/messages/messages')
    });
    return;
  }
  await load();
});

let loadSeq = 0;

async function load() {
  const seq = ++loadSeq;
  if (!list.value.length) loading.value = true;
  try {
    const [rows, count, prefList, cfg, pending] = await Promise.all([
      consumerApi.notifications(100),
      consumerApi.notificationUnreadCount(),
      consumerApi.notifyPrefs(),
      consumerApi.consumerPublicConfig(),
      softFallback(consumerApi.pendingOrderCount(), { count: 0 }, '待支付数')
    ]);
    if (seq !== loadSeq) return;
    list.value = rows;
    unread.value = Number(count?.count || 0);
    prefs.value = prefList;
    subscribeEnabled.value = cfg?.wechatSubscribeEnabled === 'true';
    subscribeTemplateId.value = String(cfg?.wechatSubscribeTemplateId || '');
    pendingCount.value = Math.max(0, Number(pending?.count || 0));
  } catch (e) {
    if (seq !== loadSeq) return;
    showError(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

/**
 * orders 是 tabBar 页：navigateTo（含带参）到 tabBar 页在微信端会直接失败，
 * 统一改用 switchTab；「待支付」过滤经一次性 storage 传递（orders.vue onShow 读取后即清除）。
 */
function goOrdersTab(pendingOnly = false) {
  if (pendingOnly) {
    try {
      uni.setStorageSync('orders_pending_filter', 'pending');
    } catch {
      /* ignore */
    }
  }
  uni.switchTab({ url: '/pages/orders/orders' });
}

function goPendingOrders() {
  goOrdersTab(true);
}

function onSubscribe() {
  if (!isMpWeixin) {
    showError('请在微信小程序中开启提醒');
    return;
  }
  if (!subscribeTemplateId.value) return;
  subscribing.value = true;
  uni.requestSubscribeMessage({
    tmplIds: [subscribeTemplateId.value],
    success: (res) => {
      const status = Reflect.get(res as object, subscribeTemplateId.value);
      const accept = status === 'accept';
      if (accept) showSuccess('已开启，消息将及时送达');
      else showError('未开启，可在设置中打开');
    },
    fail: () => {
      showError('当前环境不支持订阅授权');
    },
    complete: () => {
      subscribing.value = false;
    }
  });
}

async function onPrefChange(p: NotifyPrefDto, ev: { detail?: { value?: boolean } } | Event) {
  // category 是更新偏好的必需键；缺失时直接放弃，避免打到 /notify-prefs/undefined
  if (!p.category) return;
  const detail = (ev as { detail?: { value?: boolean } })?.detail;
  const enabled = !!detail?.value;
  const prev = p.enabled;
  p.enabled = enabled;
  try {
    await consumerApi.updateNotifyPref(p.category, enabled);
  } catch (e) {
    p.enabled = prev;
    showError(e instanceof Error ? e.message : '设置失败');
  }
}

async function onOpen(m: NotificationDto) {
  if (!m.read && m.id != null) {
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
  const rawId = String(m.bizId || '').trim();
  const id = rawId ? encodeURIComponent(rawId) : '';
  const type = String(m.bizType || '').toUpperCase();
  const tpl = String(m.templateCode || '').toLowerCase();
  switch (type) {
    case 'ORDER':
      if (id) {
        uni.navigateTo({ url: `/pages/order-detail/order-detail?orderId=${id}` });
      } else if (tpl.includes('unpaid') || tpl.includes('pending')) {
        goOrdersTab(true);
      } else {
        goOrdersTab();
      }
      break;
    case 'DISPUTE':
      if (id) {
        // ticketId 数字走工单；否则按 sessionId 打开审核详情
        const looksTicket = /^\d+$/.test(rawId);
        uni.navigateTo({
          url: looksTicket
            ? `/pages/dispute/detail?ticketId=${id}`
            : `/pages/dispute/detail?sessionId=${id}`
        });
      } else {
        goOrdersTab();
      }
      break;
    case 'RECHARGE':
      uni.navigateTo({
        url: id ? `/pages/recharge/recharge?orderId=${id}` : '/pages/recharge/recharge'
      });
      break;
    case 'COUPON':
      // C-P2-8：后端 COUPON 消息 bizId=已持有的 user_coupon.couponId（如 coupon_expiring），
      // 不是营销活动 id。禁止误调 claimCampaign；正确动作是设优先券并打开券包。
      if (/^\d+$/.test(rawId)) {
        uni.setStorageSync('preferred_coupon_id', Number(rawId));
        showSuccess('已选中该券，下次开门优先使用');
        uni.navigateTo({ url: '/pages/coupons/coupons?tab=UNUSED&fromMsg=1' });
      } else {
        uni.navigateTo({ url: '/pages/coupons/coupons' });
      }
      break;
    case 'POINTS':
      uni.navigateTo({
        url:
          tpl.includes('redeem') || tpl.includes('exchange')
            ? '/pages/points/redeem'
            : '/pages/points/points'
      });
      break;
    case 'RECALL':
    case 'CAMPAIGN':
    case 'MARKETING':
      uni.navigateTo({
        url: id ? `/pages/marketing/index?campaignId=${id}` : '/pages/marketing/index'
      });
      break;
    case 'ANNOUNCEMENT':
      uni.navigateTo({
        url: id ? `/pages/announcements/detail?id=${id}` : '/pages/announcements/announcements'
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
  padding: 16rpx 0 calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.loading {
  padding: 120rpx 32rpx;
  text-align: center;
  color: var(--text-muted, #8a968e);
}
.empty {
  padding: 120rpx 32rpx;
  text-align: center;
}
.empty-title {
  display: block;
  font-size: var(--font-size-md);
  color: var(--text-muted, #4b5563);
}
.empty-hint {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #9aa4a0);
}
.msg-list {
  background: #ffffff;
}
.msg-card {
  margin: 0;
  padding: 26rpx 32rpx;
  border-radius: 0;
  background: #ffffff;
  box-shadow: none;
}
.msg-card.unread {
  border-left: 6rpx solid var(--brand);
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
  font-weight: 700;
  color: var(--text-primary, #1f2a24);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.msg-time {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--text-subtle, #9aa4a0);
}
.msg-body {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-caption);
  line-height: 1.55;
  color: var(--text-muted, #4b5563);
}
.msg-biz {
  margin-top: 10rpx;
  font-size: var(--font-size-xs);
  color: var(--text-muted, #8a968e);
}
.card {
  margin-top: 16rpx;
  padding: 26rpx 32rpx;
  border-radius: 0;
  background: #ffffff;
}
.card-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--text-primary, #1f2a24);
}
.card-hint {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #9aa4a0);
}
.pref-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18rpx 0 6rpx;
  border-bottom: 1rpx solid var(--color-border-subtle, #f0f2f1);
}
.pref-row:last-child {
  border-bottom: none;
}
.pref-label {
  font-size: var(--font-size-body);
  color: var(--text-primary, #1f2a24);
}
.subscribe-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin: 0 0 16rpx;
  padding: 24rpx 32rpx;
  border-radius: 0;
  background: #ffffff;
}
.todo-banner {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin: 0 0 16rpx;
  padding: 24rpx 32rpx;
  border-radius: 0;
  background: #ffffff;
}
.todo-copy {
  flex: 1;
  min-width: 0;
}
.todo-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--accent-orange, #9a3412);
}
.todo-sub {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-sm);
  color: var(--accent-orange, #c2410c);
}
.todo-go {
  font-size: var(--font-size-body);
  color: var(--accent-orange, #c2410c);
  font-weight: 600;
  white-space: nowrap;
}
.subscribe-copy {
  flex: 1;
  min-width: 0;
}
.subscribe-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--text-primary, #14201b);
}
.subscribe-sub {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.subscribe-btn {
  margin: 0;
  padding: 0 26rpx;
  min-height: 64rpx;
  height: 64rpx;
  line-height: 1.2;
  border-radius: 16rpx;
  font-size: var(--font-size-caption);
  color: var(--white);
  background: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
</style>
