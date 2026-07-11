<template>
  <view>
    <view v-if="loading" class="card">加载中…</view>
    <view v-else-if="error" class="card"><text class="err">{{ error }}</text></view>
    <view v-else>
      <view class="kpi-grid">
        <view class="kpi-card dispute"><text class="n">{{ counts.disputes }}</text><text class="l">争议</text></view>
        <view class="kpi-card offline"><text class="n">{{ counts.offline }}</text><text class="l">离线</text></view>
        <view class="kpi-card stock"><text class="n">{{ counts.lowStock }}</text><text class="l">低库存</text></view>
        <view class="kpi-card expiry"><text class="n">{{ counts.expiry }}</text><text class="l">临期</text></view>
      </view>

      <view v-for="(a, i) in items" :key="i" class="card alert-card" @click="handleItem(a)">
        <text class="tag" :class="tagClass(a.type)">{{ a.typeLabel }}</text>
        <text class="title">{{ a.title }}</text>
        <text v-if="a.detail" class="meta">{{ a.detail }}</text>
        <text v-if="a.deviceId" class="action">查看柜机 ›</text>
        <button v-if="a.exceptionId && isInventoryException(a.type)" class="resolve-btn" size="mini" @click.stop="resolveInventory(a)">完成库存核对</button>
      </view>
      <view v-if="!items.length" class="card meta">暂无待办</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { merchantApi, alertTypeLabel } from '@/utils/merchant-api';

const loading = ref(true);
const error = ref('');
const counts = ref({ disputes: 0, offline: 0, lowStock: 0, expiry: 0 });
const items = ref<{ type: string; typeLabel: string; title: string; detail: string; deviceId?: string; ticketId?: string; exceptionId?: string }[]>([]);

function tagClass(type: string) {
  if (type === 'DISPUTE') return 'dispute';
  if (type === 'DEVICE_OFFLINE') return 'offline';
  if (type === 'LOW_STOCK') return 'stock';
  if (type === 'EXPIRY') return 'expiry';
  return 'default';
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  loading.value = true;
  try {
    const [wb, exceptionPage] = await Promise.all([merchantApi.workbench(), merchantApi.exceptions('OPEN')]);
    counts.value = {
      disputes: wb.openDisputes || 0,
      offline: wb.offlineDevices || 0,
      lowStock: wb.lowStockItems || 0,
      expiry: wb.expiryAlerts || 0
    };
    const workbenchItems = (wb.actionItems || []).map((a) => ({
      type: a.type,
      typeLabel: alertTypeLabel(a.type),
      title: a.title,
      detail: a.detail || '',
      deviceId: a.deviceId,
      ticketId: a.ticketId
    }));
    const exceptionItems = (exceptionPage.items || []).map((a) => ({
      type: a.exceptionType,
      typeLabel: alertTypeLabel(a.exceptionType),
      title: a.title,
      detail: a.detail || '',
      deviceId: a.deviceId,
      exceptionId: a.exceptionId
    }));
    items.value = [...exceptionItems, ...workbenchItems].slice(0, 20);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function handleItem(item: { deviceId?: string }) {
  if (item.deviceId) {
    uni.navigateTo({ url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}` });
  }
}

function isInventoryException(type: string) {
  return ['INVENTORY_MISMATCH', 'LOW_STOCK', 'REPLENISHMENT_REQUIRED'].includes(type);
}

function resolveInventory(item: { exceptionId?: string; deviceId?: string }) {
  if (!item.exceptionId) return;
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
</style>
