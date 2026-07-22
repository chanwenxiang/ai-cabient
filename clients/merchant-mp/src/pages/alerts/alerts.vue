<template>
  <view>
    <view v-if="loading" class="card">加载中…</view>
    <view v-else-if="error" class="card"><text class="err">{{ error }}</text></view>
    <view v-else>
      <view v-if="preferredId" class="pref-bar">
        <text>常驻柜优先：{{ preferredId }}</text>
        <text class="pref-toggle" @click="onlyPreferred = !onlyPreferred">
          {{ onlyPreferred ? '显示全部' : '仅看常驻' }}
        </text>
      </view>
      <view class="kpi-grid">
        <view class="kpi-card dispute"><text class="n">{{ counts.disputes }}</text><text class="l">争议</text></view>
        <view class="kpi-card offline"><text class="n">{{ counts.offline }}</text><text class="l">离线</text></view>
        <view class="kpi-card stock"><text class="n">{{ counts.lowStock }}</text><text class="l">低库存</text></view>
        <view class="kpi-card expiry"><text class="n">{{ counts.expiry }}</text><text class="l">临期</text></view>
      </view>

      <view v-for="(a, i) in visibleItems" :key="i" class="card alert-card" @click="handleItem(a)">
        <text class="tag" :class="tagClass(a.type)">{{ a.typeLabel }}</text>
        <text class="title">{{ a.title }}</text>
        <text v-if="a.detail" class="meta">{{ a.detail }}</text>
        <text v-if="actionHint(a)" class="action">{{ actionHint(a) }}</text>
        <button
          v-if="canResolveInventory && a.exceptionId && isInventoryException(a.type)"
          class="resolve-btn"
          size="mini"
          @click.stop="resolveInventory(a)"
        >完成库存核对</button>
      </view>
      <empty-state
        v-if="!visibleItems.length"
        icon="✅"
        title="暂无待办事项"
        hint="争议、离线、低库存与临期告警都会集中显示在这里"
      >
        <button class="empty-btn primary" @click="goDevices">查看柜机</button>
      </empty-state>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import EmptyState from '@/components/empty-state.vue';
import { hasPerm, merchantApi, alertTypeLabel, merchantAlertTitle } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import type { MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canViewAlerts = computed(() => hasPerm(me.value, 'merchant:alerts:view'));
const canResolveInventory = computed(() => hasPerm(me.value, 'merchant:inventory:view'));

const loading = ref(true);
const error = ref('');
const preferredId = ref('');
const onlyPreferred = ref(false);
const counts = ref({ disputes: 0, offline: 0, lowStock: 0, expiry: 0 });
const items = ref<
  {
    type: string;
    typeLabel: string;
    title: string;
    detail: string;
    deviceId?: string;
    ticketId?: string;
    exceptionId?: string;
  }[]
>([]);

const visibleItems = computed(() => {
  if (!onlyPreferred.value || !preferredId.value) return items.value;
  return items.value.filter((a) => !a.deviceId || a.deviceId === preferredId.value);
});

function tagClass(type: string) {
  if (type === 'DISPUTE') return 'dispute';
  if (type === 'DEVICE_OFFLINE') return 'offline';
  if (type === 'LOW_STOCK') return 'stock';
  if (type === 'EXPIRY') return 'expiry';
  if (type === 'REPLENISHMENT' || type === 'REPLENISHMENT_REQUIRED') return 'stock';
  return 'default';
}

function actionHint(item: { type: string; deviceId?: string; ticketId?: string }) {
  const type = String(item.type || '').toUpperCase();
  if (type === 'DISPUTE') return item.ticketId ? '去处理争议 ›' : '查看争议 ›';
  if (type === 'EXPIRY') return '去处理临期任务 ›';
  if (type === 'LOW_STOCK') return '去发起要货 ›';
  if (type === 'REPLENISHMENT' || type === 'REPLENISHMENT_REQUIRED') return '去补货任务 ›';
  if (item.deviceId) return '查看柜机 ›';
  return '';
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  try {
    await refreshMe();
  } catch {
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canViewAlerts.value) {
    uni.showToast({ title: '无待办权限', icon: 'none' });
    uni.switchTab({ url: '/pages/home/home' });
    return;
  }
  preferredId.value = getPreferredDeviceId();
  loading.value = true;
  try {
    const [wb, exceptionPage, expiryRows] = await Promise.all([
      merchantApi.workbench(),
      merchantApi.exceptions('OPEN'),
      merchantApi.expiryAlerts().catch(() => [])
    ]);
    counts.value = {
      disputes: wb.openDisputes || 0,
      offline: wb.offlineDevices || 0,
      lowStock: wb.lowStockItems || 0,
      expiry: wb.expiryAlerts || (expiryRows || []).length || 0
    };
    const workbenchItems = (wb.actionItems || []).map((a) => ({
      type: a.type,
      typeLabel: alertTypeLabel(a.type),
      title: merchantAlertTitle(a.type, a.title),
      detail: merchantAlertTitle(a.type, a.detail || ''),
      deviceId: a.deviceId,
      ticketId: a.ticketId
    }));
    const exceptionItems = (exceptionPage.items || []).map((a) => ({
      type: a.exceptionType,
      typeLabel: alertTypeLabel(a.exceptionType),
      title: merchantAlertTitle(a.exceptionType, a.title),
      detail: merchantAlertTitle(a.exceptionType, a.detail || ''),
      deviceId: a.deviceId,
      exceptionId: a.exceptionId
    }));
    const expiryItems = (expiryRows || [])
      .filter((e) => String(e.status || 'OPEN').toUpperCase() === 'OPEN')
      .map((e) => ({
        type: 'EXPIRY',
        typeLabel: alertTypeLabel('EXPIRY'),
        title: `${e.skuId || '商品'} · 临期/过期 ${e.quantity || 0} 件`,
        detail: [e.deviceId, e.batchNo, e.reason].filter(Boolean).join(' · '),
        deviceId: e.deviceId
      }));
    // Prefer structured expiry rows; keep workbench EXPIRY only if no expiry API rows
    const hasExpiryApi = expiryItems.length > 0;
    const merged = [
      ...exceptionItems,
      ...workbenchItems.filter((a) => !(hasExpiryApi && String(a.type).toUpperCase() === 'EXPIRY')),
      ...expiryItems
    ];
    items.value = merged.slice(0, 30);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function handleItem(item: { type?: string; deviceId?: string; ticketId?: string }) {
  const type = String(item.type || '').toUpperCase();
  if (type === 'DISPUTE') {
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
  }
}

function goDevices() {
  uni.switchTab({ url: '/pages/devices/devices' });
}

function isInventoryException(type: string) {
  return ['INVENTORY_MISMATCH', 'LOW_STOCK', 'REPLENISHMENT_REQUIRED'].includes(type);
}

function resolveInventory(item: { exceptionId?: string; deviceId?: string }) {
  if (!item.exceptionId) return;
  if (!canResolveInventory.value) {
    uni.showToast({ title: '无库存处理权限', icon: 'none' });
    return;
  }
  uni.showModal({
    title: '确认完成库存核对',
    editable: true,
    placeholderText: '填写盘点结果或补货说明',
    success: async (res) => {
      const resolution = (res.content || '').trim();
      if (!res.confirm) return;
      if (!resolution) {
        uni.showToast({ title: '必须填写处理结果', icon: 'none' });
        return;
      }
      try {
        await merchantApi.resolveInventoryException(item.exceptionId!, resolution);
        uni.showToast({ title: '库存异常已处理', icon: 'success' });
        await load();
      } catch (e) {
        uni.showToast({ title: e instanceof Error ? e.message : '处理失败', icon: 'none' });
      }
    }
  });
}

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped>
.pref-bar {
  margin: 12rpx 12rpx 0;
  padding: 16rpx 20rpx;
  border-radius: 12rpx;
  background: #ecfdf5;
  color: #0f766e;
  font-size: 24rpx;
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.pref-toggle { color: #64748b; text-decoration: underline; }
.kpi-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12rpx; margin: 12rpx; }
.kpi-card { border-radius: 16rpx; padding: 24rpx; text-align: center; }
.kpi-card.dispute { background: #fef2f2; }
.kpi-card.offline { background: #f1f5f9; }
.kpi-card.stock { background: #fffbeb; }
.kpi-card.expiry { background: #ecfdf5; }
.n { font-size: 40rpx; font-weight: 700; display: block; }
.l { font-size: 22rpx; color: #64748b; }
.alert-card { margin-top: 0; }
.tag { font-size: 20rpx; padding: 4rpx 12rpx; border-radius: 6rpx; margin-right: 8rpx; }
.tag.dispute { background: #fecaca; color: #dc2626; }
.tag.offline { background: #e2e8f0; color: #475569; }
.tag.stock { background: #fde68a; color: #d97706; }
.tag.expiry { background: #a7f3d0; color: #059669; }
.tag.default { background: #e2e8f0; color: #64748b; }
.title { font-weight: 600; display: block; margin-top: 8rpx; }
.action { color: #0f766e; font-size: 24rpx; display: block; margin-top: 12rpx; }
.err { color: #ef4444; }
.resolve-btn { margin-top: 14rpx; background: #0f766e; color: #fff; border: 0; }
.empty-btn {
  margin: 0;
  padding: 0 28rpx;
  height: 64rpx;
  line-height: 64rpx;
  border-radius: 999rpx;
  font-size: 24rpx;
  color: #0f766e;
  background: #ecfdf5;
  border: none;
}
.empty-btn.primary { color: #fff; background: #0f766e; }
.empty-btn::after { border: none; }
</style>
