<script setup lang="ts">
/**
 * 理货记录（CB-050 扩展，嗨便利对照）：按柜机聚合「盘点 / 配货」两类动作历史。
 * 配货 = 补货任务行粒度（TallyLedgerService 只读聚合）；盘点 = AISLE_AUDIT 事件流。
 */
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { showError } from '@/utils/notify';
import { isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import type {
  OpenApiPageResultDeviceOpsEventDto,
  OpenApiPageResultTallyRecordRowDto
} from '@aicabinet/shared-types';

const deviceId = ref('');
const deviceName = ref('');
const mode = ref<'restock' | 'stocktake'>('restock');
const loading = ref(false);

interface RestockRow {
  taskId: number;
  lineType: string;
  skuName: string;
  quantity: number | null;
  slotId: string | null;
  operatorName: string | null;
  taskStatus: string;
  completedAt: string | null;
}
interface StocktakeRow {
  eventId: number;
  title: string;
  detail: string | null;
  createdAt: string;
}
const restockRows = ref<RestockRow[]>([]);
const stocktakeRows = ref<StocktakeRow[]>([]);
const restockTotal = ref(0);
const stocktakeTotal = ref(0);
const restockLoaded = ref(false);
const stocktakeLoaded = ref(false);

onLoad((opts) => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  deviceId.value = opts?.deviceId ? decodeURIComponent(String(opts.deviceId)) : '';
  deviceName.value = opts?.name ? decodeURIComponent(String(opts.name)) : '';
  void load();
});

async function load() {
  if (!deviceId.value) return;
  loading.value = true;
  try {
    if (mode.value === 'restock') {
      const res = await merchantApi.deviceTallyRecords(deviceId.value, 0, 50, 30);
      restockRows.value = toRestockRows(res);
      restockTotal.value = Number(res.total ?? 0);
      restockLoaded.value = true;
    } else {
      const res = await merchantApi.deviceStocktakeEvents(deviceId.value, 0, 50);
      stocktakeRows.value = toStocktakeRows(res);
      stocktakeTotal.value = Number(res.total ?? 0);
      stocktakeLoaded.value = true;
    }
  } catch (e) {
    showError(e instanceof Error ? e.message : '加载理货记录失败');
  } finally {
    loading.value = false;
  }
}

function toRestockRows(res: OpenApiPageResultTallyRecordRowDto): RestockRow[] {
  return (res.items || []).map((r) => ({
    taskId: Number(r.taskId ?? 0),
    lineType: String(r.lineType || ''),
    skuName: String(r.skuName || ''),
    quantity: r.quantity == null ? null : Number(r.quantity),
    slotId: r.slotId == null ? null : String(r.slotId),
    operatorName: r.operatorName == null ? null : String(r.operatorName),
    taskStatus: String(r.taskStatus || ''),
    completedAt: r.completedAt == null ? null : String(r.completedAt)
  }));
}

function toStocktakeRows(res: OpenApiPageResultDeviceOpsEventDto): StocktakeRow[] {
  return (res.items || []).map((e) => ({
    eventId: Number(e.eventId ?? 0),
    title: String(e.title || ''),
    detail: e.detail == null ? null : String(e.detail),
    createdAt: String(e.createdAt || '')
  }));
}

function switchMode(m: 'restock' | 'stocktake') {
  mode.value = m;
  void load();
}

function lineTypeLabel(t: string): string {
  if (t === 'PUT_ON') return '上架';
  if (t === 'PULL_OFF') return '下架';
  return t || '调整';
}

onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));
</script>

<template>
  <view>
    <app-nav-bar title="理货记录" home-url="/pages/home/home" />
    <view class="page-body">
      <view v-if="!deviceId" class="card"
        ><text class="err">缺少柜机参数，请从柜机详情进入</text></view
      >
      <template v-else>
        <view class="card head-card">
          <text class="title">{{ deviceName || `柜机 EG${deviceId}` }}</text>
          <text class="meta">近 30 天记录 · 实时同步自平台台账</text>
        </view>

        <view class="mode-tabs">
          <view
            class="mode-tab"
            :class="{ active: mode === 'restock' }"
            role="button"
            @click="switchMode('restock')"
          >
            <text>配货（{{ restockLoaded ? restockTotal : '…' }}）</text>
          </view>
          <view
            class="mode-tab"
            :class="{ active: mode === 'stocktake' }"
            role="button"
            @click="switchMode('stocktake')"
          >
            <text>盘点（{{ stocktakeLoaded ? stocktakeTotal : '…' }}）</text>
          </view>
        </view>

        <view v-if="loading" class="card"
          ><text class="meta">{{ UI_COPY.loading }}</text></view
        >
        <template v-else-if="mode === 'restock'">
          <view
            v-for="r in restockRows"
            :key="`${r.taskId}-${r.skuName}-${r.slotId}`"
            class="card record-card"
          >
            <view class="record-head">
              <text class="record-tag" :class="r.lineType === 'PULL_OFF' ? 'off' : 'on'">
                {{ lineTypeLabel(r.lineType) }}
              </text>
              <text class="record-title">{{ r.skuName }}</text>
              <text class="record-qty" :class="r.lineType === 'PULL_OFF' ? 'off' : 'on'">
                {{ r.lineType === 'PULL_OFF' ? '-' : '+' }}{{ r.quantity ?? 0 }}
              </text>
            </view>
            <text class="meta">
              {{ r.slotId ? `货道 ${r.slotId} · ` : '' }}{{ r.operatorName || '未知操作员' }} ·
              {{ r.taskStatus === 'COMPLETED' ? '已完成' : r.taskStatus }}
            </text>
          </view>
          <view v-if="restockLoaded && !restockRows.length" class="card">
            <text class="meta">近 30 天没有配货记录</text>
          </view>
        </template>
        <template v-else>
          <view v-for="e in stocktakeRows" :key="e.eventId" class="card record-card">
            <view class="record-head">
              <text class="record-tag st">盘点</text>
              <text class="record-title">{{ e.title }}</text>
            </view>
            <text class="meta">{{ e.detail || '' }}</text>
          </view>
          <view v-if="stocktakeLoaded && !stocktakeRows.length" class="card">
            <text class="meta">近 14 天没有盘点记录——可在柜机详情点「柜机盘点」开始第一次实盘</text>
          </view>
        </template>
      </template>
    </view>
  </view>
</template>

<style scoped>
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.head-card {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.title {
  font-size: 32rpx;
  font-weight: 700;
}
.meta {
  font-size: 24rpx;
  color: #909399;
}
.err {
  color: #d03050;
}
.mode-tabs {
  display: flex;
  gap: 16rpx;
  margin-bottom: 24rpx;
}
.mode-tab {
  flex: 1;
  text-align: center;
  padding: 16rpx 0;
  border-radius: 12rpx;
  background: #fff;
  font-size: 28rpx;
  color: #606266;
  border: 1rpx solid #ebeef5;
}
.mode-tab.active {
  background: var(--color-primary, #0f766e);
  color: #fff;
  border-color: var(--color-primary, #0f766e);
  font-weight: 600;
}
.record-card {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.record-head {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.record-tag {
  font-size: 22rpx;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
  background: #ecfdf5;
  color: #047857;
}
.record-tag.off {
  background: #fef2f2;
  color: #b91c1c;
}
.record-tag.st {
  background: #eff6ff;
  color: #1d4ed8;
}
.record-title {
  font-size: 28rpx;
  font-weight: 600;
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.record-qty {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.record-qty.on {
  color: #047857;
}
.record-qty.off {
  color: #b91c1c;
}
</style>
