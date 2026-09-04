<template>
  <view class="alerts-page">
    <app-nav-bar title="待办" />
    <view v-if="preferredId" class="pref-bar">
      <text>常驻柜优先：{{ preferredId }}</text>
      <text class="pref-toggle" @click="onlyPreferred = !onlyPreferred">
        {{ onlyPreferred ? '显示全部' : '仅看常驻' }}
      </text>
    </view>
    <view class="kpi-grid">
      <view class="kpi-card dispute"
        ><text class="n">{{ counts.disputes }}</text
        ><text class="l">审核</text></view
      >
      <view class="kpi-card offline"
        ><text class="n">{{ counts.offline }}</text
        ><text class="l">故障</text></view
      >
      <view class="kpi-card stock"
        ><text class="n">{{ counts.lowStock }}</text
        ><text class="l">库存</text></view
      >
      <view class="kpi-card expiry"
        ><text class="n">{{ counts.expiry }}</text
        ><text class="l">临期</text></view
      >
    </view>

    <view v-if="loading && !items.length" class="card">加载中…</view>
    <view v-else-if="error && !items.length" class="card">
      <text class="err">{{ error }}</text>
      <button class="retry" size="mini" @click="load">重试</button>
    </view>
    <view v-else>
      <view
        v-for="(a, i) in visibleItems"
        :key="a.exceptionId || a.ticketId || `${a.type}-${a.deviceId}-${i}`"
        class="card alert-card"
        hover-class="alert-card-hover"
        role="button"
        @click="handleItem(a)"
      >
        <text class="tag" :class="tagClass(a.type)">{{ a.typeLabel }}</text>
        <text class="title">{{ a.title }}</text>
        <text v-if="a.deviceId" class="meta">柜机 {{ a.deviceId }}</text>
        <text v-if="a.detail" class="meta">{{ a.detail }}</text>
        <text v-if="a.dueAt" class="meta due" :class="{ overdue: isOverdue(a.dueAt) }">{{
          dueText(a.dueAt)
        }}</text>
        <text v-if="a.severity" class="meta sev">优先级 {{ severityText(a.severity) }}</text>
        <text v-if="actionHint(a)" class="action">{{ actionHint(a) }}</text>
        <button
          v-if="canResolveInventory && a.exceptionId && isInventoryException(a.type)"
          class="resolve-btn"
          size="mini"
          @click.stop="resolveInventory(a)"
        >
          完成库存核对
        </button>
      </view>
      <empty-state
        v-if="!visibleItems.length"
        icon="/static/menu/check-circle.png"
        title="暂无待办事项"
        hint="争议、离线、低库存与临期告警都会集中显示在这里"
      >
        <button class="empty-btn primary" @click="goDevices">查看柜机</button>
      </empty-state>

      <view v-if="slotDiscrepancies.length" class="card section-card">
        <text class="section-title">货道差异（账实不符）</text>
        <view v-for="(s, i) in slotDiscrepancies" :key="i" class="slot-row">
          <view class="slot-main">
            <text class="slot-name">{{ s.deviceName || s.deviceId }} · {{ s.slotCode }}</text>
            <text class="slot-sku">{{ s.assignedSkuName || s.assignedSkuId || '未绑定商品' }}</text>
          </view>
          <text class="slot-diff"
            >账 {{ s.bookQty }} / 实 {{ s.physicalQty }} · 差 {{ s.qtyDiff }}</text
          >
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import EmptyState from '@/components/empty-state.vue';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import type { MerchantSlotDiscrepancy } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import { promptText } from '@/utils/text-prompt';
import { setAlertsTabBadge } from '@/utils/todo-badge';
import { mergeTodoItems } from '@/utils/todo-list';
import type { MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canViewAlerts = computed(() => hasPerm(me.value, 'merchant:alerts:view'));
const canResolveInventory = computed(() => hasPerm(me.value, 'merchant:inventory:view'));

const loading = ref(true);
const error = ref('');
const preferredId = ref('');
const onlyPreferred = ref(false);
const counts = ref({ disputes: 0, offline: 0, lowStock: 0, expiry: 0 });
const slotDiscrepancies = ref<MerchantSlotDiscrepancy[]>([]);
const items = ref<
  {
    type: string;
    typeLabel: string;
    title: string;
    detail: string;
    deviceId?: string;
    ticketId?: string;
    exceptionId?: string;
    dueAt?: string;
    severity?: string;
  }[]
>([]);
let loadSeq = 0;

const visibleItems = computed(() => {
  if (!onlyPreferred.value || !preferredId.value) return items.value;
  return items.value.filter((a) => !a.deviceId || a.deviceId === preferredId.value);
});

function isOverdue(dueAt?: string) {
  if (!dueAt) return false;
  const t = new Date(dueAt).getTime();
  return Number.isFinite(t) && t < Date.now();
}

function dueText(dueAt?: string) {
  if (!dueAt) return '';
  const d = new Date(dueAt);
  if (Number.isNaN(d.getTime())) return `时限 ${dueAt}`;
  const p = (n: number) => String(n).padStart(2, '0');
  const label = `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  return isOverdue(dueAt) ? `已超时 · ${label}` : `截止 ${label}`;
}

function severityText(sev?: string) {
  const s = String(sev || '').toUpperCase();
  if (s === 'HIGH' || s === 'CRITICAL') return '高';
  if (s === 'MEDIUM') return '中';
  if (s === 'LOW') return '低';
  return sev || '';
}

function tagClass(type: string) {
  if (type === 'DISPUTE') return 'dispute';
  if (type === 'DEVICE_OFFLINE') return 'offline';
  if (type === 'SALES_LOCKED' || type === 'DEVICE_FAULT') return 'offline';
  if (type === 'LOW_STOCK') return 'stock';
  if (type === 'EXPIRY') return 'expiry';
  if (type === 'REPLENISHMENT' || type === 'REPLENISHMENT_REQUIRED') return 'stock';
  return 'default';
}

function actionHint(item: { type: string; deviceId?: string; ticketId?: string }) {
  const type = String(item.type || '').toUpperCase();
  if (type === 'DISPUTE') return item.ticketId ? '去处理争议 ›' : '查看争议 ›';
  if (type.startsWith('RECOGNITION')) return item.deviceId ? '查看柜机 ›' : '查看争议 ›';
  if (type === 'EXPIRY') return '去处理临期任务 ›';
  if (type === 'LOW_STOCK') return '去发起要货 ›';
  if (type === 'REPLENISHMENT' || type === 'REPLENISHMENT_REQUIRED') return '去补货任务 ›';
  if (type === 'DEVICE_OFFLINE' || type === 'DEVICE_FAULT' || type === 'SALES_LOCKED')
    return '查看柜机 ›';
  if (item.deviceId) return '查看柜机 ›';
  return '查看详情 ›';
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const seq = ++loadSeq;
  try {
    await refreshMe();
  } catch {
    if (!uni.getStorageSync('merchant_token')) return;
    me.value = me.value || (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (seq !== loadSeq) return;
  if (!me.value) {
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canViewAlerts.value) {
    uni.showToast({ title: '无待办权限', icon: 'none' });
    uni.switchTab({ url: '/pages/home/home' });
    return;
  }
  preferredId.value = getPreferredDeviceId();
  // 已有列表时静默刷新，避免 Tab 切换时整页先缩成「加载中」再撑开（先小后大）
  if (!items.value.length) loading.value = true;
  error.value = '';
  try {
    const [wb, exceptionPage, expiryRows, slotRows] = await Promise.all([
      merchantApi.workbench().catch(() => ({
        offlineDevices: 0,
        openDisputes: 0,
        lowStockItems: 0,
        expiryAlerts: 0,
        slotDiscrepancies: 0,
        actionItems: [] as {
          type: string;
          title: string;
          detail?: string;
          deviceId?: string;
          ticketId?: string;
          exceptionId?: string;
        }[]
      })),
      merchantApi.openExceptions(100).catch(() => ({ items: [], total: 0 })),
      merchantApi.expiryAlerts().catch(() => []),
      merchantApi.slotDiscrepancies().catch(() => [] as MerchantSlotDiscrepancy[])
    ]);
    if (seq !== loadSeq) return;
    const deduped = mergeTodoItems({
      exceptions: exceptionPage.items || [],
      actionItems: (wb.actionItems || []).map((a) => ({
        type: String(a.type || ''),
        title: String(a.title || ''),
        detail: a.detail,
        deviceId: a.deviceId,
        ticketId: a.ticketId,
        exceptionId: (a as { exceptionId?: string }).exceptionId,
        dueAt: (a as { dueAt?: string }).dueAt,
        severity: (a as { severity?: string }).severity
      })),
      expiryRows: expiryRows || []
    });
    items.value = deduped;
    slotDiscrepancies.value = slotRows || [];
    const typeOf = (t: string) => String(t || '').toUpperCase();
    const audit = deduped.filter(
      (a) => typeOf(a.type) === 'DISPUTE' || typeOf(a.type).startsWith('RECOGNITION')
    ).length;
    const fault = deduped.filter((a) =>
      ['DEVICE_OFFLINE', 'DEVICE_FAULT', 'SALES_LOCKED', 'DOOR_OPEN_TOO_LONG'].includes(
        typeOf(a.type)
      )
    ).length;
    const stock = deduped.filter((a) =>
      [
        'LOW_STOCK',
        'SLOT_DISCREPANCY',
        'INVENTORY_MISMATCH',
        'REPLENISHMENT',
        'REPLENISHMENT_REQUIRED'
      ].includes(typeOf(a.type))
    ).length;
    const expiry = deduped.filter((a) => typeOf(a.type) === 'EXPIRY').length;
    counts.value = {
      disputes: audit,
      offline: fault,
      lowStock: stock,
      expiry
    };
    setAlertsTabBadge(deduped.length);
  } catch (e) {
    if (seq !== loadSeq) return;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function handleItem(item: {
  type?: string;
  deviceId?: string;
  ticketId?: string;
  exceptionId?: string;
}) {
  const type = String(item.type || '').toUpperCase();
  if (type === 'DISPUTE') {
    uni.navigateTo({ url: '/pages/disputes/disputes' });
    return;
  }
  if (type.startsWith('RECOGNITION')) {
    // 识别存疑：有柜机则看柜机详情，否则进争议列表继续处理
    if (item.deviceId) {
      uni.navigateTo({
        url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}`
      });
      return;
    }
    uni.navigateTo({ url: '/pages/disputes/disputes' });
    return;
  }
  if (type === 'EXPIRY' || type === 'REPLENISHMENT' || type === 'REPLENISHMENT_REQUIRED') {
    const q = item.deviceId ? `?deviceId=${encodeURIComponent(item.deviceId)}` : '';
    uni.navigateTo({ url: `/pages/replenishment/replenishment${q}` });
    return;
  }
  if (type === 'LOW_STOCK') {
    const q = item.deviceId ? `?deviceId=${encodeURIComponent(item.deviceId)}` : '';
    uni.navigateTo({ url: `/pages/request/request${q}` });
    return;
  }
  if (item.deviceId) {
    uni.navigateTo({
      url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}`
    });
    return;
  }
  uni.showToast({ title: '暂无跳转目标', icon: 'none' });
}

function goDevices() {
  uni.switchTab({ url: '/pages/devices/devices' });
}

function isInventoryException(type: string) {
  return ['INVENTORY_MISMATCH', 'LOW_STOCK', 'REPLENISHMENT_REQUIRED'].includes(
    String(type || '').toUpperCase()
  );
}

async function resolveInventory(item: { exceptionId?: string; deviceId?: string }) {
  if (!item.exceptionId) return;
  if (!canResolveInventory.value) {
    uni.showToast({ title: '无库存处理权限', icon: 'none' });
    return;
  }
  const resolution = await promptText({
    title: '确认完成库存核对',
    hint: '请填写盘点结果或补货说明，便于后台留痕',
    placeholder: '填写盘点结果或补货说明',
    required: true,
    requiredMessage: '必须填写处理结果',
    maxLength: 200,
    testId: 'inventory-resolve-prompt'
  });
  if (resolution == null) return;
  try {
    await merchantApi.resolveInventoryException(item.exceptionId!, resolution);
    uni.showToast({ title: '库存异常已处理', icon: 'success' });
    await load();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '处理失败', icon: 'none' });
  }
}

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped>
.alerts-page {
  min-height: 100%;
  padding: 0 0 calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  background: var(--page-tint, #f0fdfa);
}
.section-card {
  margin-top: 18rpx;
}
.section-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--brand-deep, #134e4a);
  margin-bottom: 12rpx;
}
.slot-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 14rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}
.slot-row:last-child {
  border-bottom: none;
}
.slot-main {
  flex: 1;
  min-width: 0;
}
.slot-name {
  display: block;
  font-size: 26rpx;
  font-weight: 650;
}
.slot-sku {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #64748b;
}
.slot-diff {
  font-size: 24rpx;
  font-weight: 700;
  color: #b45309;
}

.pref-bar {
  margin: 12rpx 20rpx 0;
  padding: 16rpx 20rpx;
  border-radius: 16rpx;
  background: #fff;
  border: 1rpx solid #e2e8f0;
  color: var(--brand, #0f766e);
  font-size: 24rpx;
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.pref-toggle {
  color: #64748b;
  text-decoration: underline;
}
.kpi-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12rpx;
  margin: 12rpx 20rpx 0;
}
.kpi-card {
  border-radius: 16rpx;
  padding: 22rpx 16rpx;
  text-align: center;
  background: #fff;
  border: 1rpx solid #e2e8f0;
  box-shadow: 0 4rpx 14rpx rgba(15, 118, 110, 0.04);
}
.kpi-card .n {
  color: var(--brand-deep, #134e4a);
}
.kpi-card.dispute .n {
  color: #dc2626;
}
.kpi-card.offline .n {
  color: #475569;
}
.kpi-card.stock .n {
  color: #d97706;
}
.kpi-card.expiry .n {
  color: var(--brand, #0f766e);
}
.n {
  font-size: 40rpx;
  font-weight: 700;
  display: block;
}
.l {
  font-size: 22rpx;
  color: #64748b;
  margin-top: 4rpx;
  display: block;
}
.alert-card {
  margin-top: 0;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.alert-card-hover {
  background: #f8fafc !important;
  opacity: 0.96;
}
.tag {
  font-size: 20rpx;
  padding: 4rpx 12rpx;
  border-radius: 6rpx;
  margin-right: 8rpx;
  pointer-events: none;
}
.tag.dispute {
  background: #fecaca;
  color: #dc2626;
}
.tag.offline {
  background: #e2e8f0;
  color: #475569;
}
.tag.stock {
  background: #fde68a;
  color: #d97706;
}
.tag.expiry {
  background: #a7f3d0;
  color: var(--brand, #0f766e);
}
.tag.default {
  background: #e2e8f0;
  color: #64748b;
}
.title {
  font-weight: 600;
  display: block;
  margin-top: 8rpx;
  pointer-events: none;
}
.meta {
  display: block;
  margin-top: 6rpx;
  color: #64748b;
  font-size: 24rpx;
  pointer-events: none;
}
.meta.due {
  color: #0f766e;
}
.meta.due.overdue {
  color: #dc2626;
  font-weight: 600;
}
.meta.sev {
  color: #b45309;
}
.action {
  color: var(--brand, #0f766e);
  font-size: 24rpx;
  display: block;
  margin-top: 12rpx;
  pointer-events: none;
}
.err {
  color: #ef4444;
  display: block;
}
.retry {
  margin-top: 16rpx;
  background: linear-gradient(135deg, var(--brand-deep, #134e4a), var(--brand, #0f766e));
  color: #fff;
  border-radius: 44rpx;
  font-weight: 600;
  border: none;
  box-shadow: 0 8rpx 20rpx rgba(15, 118, 110, 0.2);
}
.retry::after {
  border: none;
}
.resolve-btn {
  margin-top: 14rpx;
  background: var(--brand, #0f766e);
  color: #fff;
  border: 0;
}
.empty-btn {
  margin: 0;
  padding: 0 28rpx;
  min-height: 64rpx;
  height: 64rpx;
  line-height: 1.2;
  border-radius: 999rpx;
  font-size: 24rpx;
  color: var(--brand, #0f766e);
  background: #ecfdf5;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.empty-btn.primary {
  color: #fff;
  background: var(--brand, #0f766e);
}
.empty-btn::after {
  border: none;
}
</style>
