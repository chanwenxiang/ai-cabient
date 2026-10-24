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
const loadError = ref('');

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
// 2026-10-23 审计 M4：原先两 tab 固定 page0/size50 静默截断，补加载更多。
const PAGE_SIZE = 50;
const restockPage = ref(0);
const stocktakePage = ref(0);
const restockHasMore = ref(false);
const stocktakeHasMore = ref(false);
const loadingMore = ref(false);
/** loadMore 序号守卫（2026-10-23 第五轮审计 L-3）：切 tab 使迟到的旧页 append 失效 */
let loadMoreSeq = 0;

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
  loadError.value = '';
  // 🔴 作废在途 loadMore（2026-10-23 第五轮审计）：切 tab 触发 load() 时，
  // 迟到的旧页 append 会污染新列表；同时复位 loadingMore 防分页死锁
  // （同 orders/splits/disputes 已修模式，check-mp-defect-classes 门禁兜底）
  loadMoreSeq += 1;
  loadingMore.value = false;
  try {
    if (mode.value === 'restock') {
      const res = await merchantApi.deviceTallyRecords(deviceId.value, 0, PAGE_SIZE, 30);
      restockRows.value = toRestockRows(res);
      restockTotal.value = Number(res.total ?? 0);
      restockPage.value = 0;
      restockHasMore.value = restockRows.value.length < restockTotal.value;
      restockLoaded.value = true;
    } else {
      const res = await merchantApi.deviceStocktakeEvents(deviceId.value, 0, PAGE_SIZE);
      stocktakeRows.value = toStocktakeRows(res);
      stocktakeTotal.value = Number(res.total ?? 0);
      stocktakePage.value = 0;
      stocktakeHasMore.value = stocktakeRows.value.length < stocktakeTotal.value;
      stocktakeLoaded.value = true;
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载理货记录失败';
    showError(loadError.value);
  } finally {
    loading.value = false;
  }
}

async function loadMore() {
  if (loadingMore.value || loading.value) return;
  if (mode.value === 'restock' && !restockHasMore.value) return;
  if (mode.value === 'stocktake' && !stocktakeHasMore.value) return;
  // seq 守卫（2026-10-23 第五轮审计 L-3）：restock 翻页在途时切到 stocktake tab，
  // load() 已重置 restockRows，迟到的旧页append 进来会让 restock 列表缺第 0 页
  const seq = ++loadMoreSeq;
  loadingMore.value = true;
  try {
    if (mode.value === 'restock') {
      const res = await merchantApi.deviceTallyRecords(
        deviceId.value,
        restockPage.value + 1,
        PAGE_SIZE,
        30
      );
      if (seq !== loadMoreSeq) return;
      const rows = toRestockRows(res);
      restockRows.value = restockRows.value.concat(rows);
      restockTotal.value = Number(res.total ?? restockTotal.value);
      restockPage.value += 1;
      restockHasMore.value = restockRows.value.length < restockTotal.value;
    } else {
      const res = await merchantApi.deviceStocktakeEvents(
        deviceId.value,
        stocktakePage.value + 1,
        PAGE_SIZE
      );
      if (seq !== loadMoreSeq) return;
      const rows = toStocktakeRows(res);
      stocktakeRows.value = stocktakeRows.value.concat(rows);
      stocktakeTotal.value = Number(res.total ?? stocktakeTotal.value);
      stocktakePage.value += 1;
      stocktakeHasMore.value = stocktakeRows.value.length < stocktakeTotal.value;
    }
  } catch (e) {
    if (seq !== loadMoreSeq) return;
    showError(e instanceof Error ? e.message : '加载更多失败');
  } finally {
    if (seq === loadMoreSeq) loadingMore.value = false;
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
          <text class="title">{{ deviceName || `柜机 ${deviceId}` }}</text>
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
        <error-state
          v-else-if="loadError"
          :title="loadError"
          hint="请检查网络后重试"
          @retry="load"
        />
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
          <text v-if="restockHasMore" role="button" class="load-more" @click="loadMore">
            {{
              loadingMore
                ? UI_COPY.loading
                : `加载更多（已显示 ${restockRows.length}/${restockTotal}）`
            }}
          </text>
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
            <text class="meta">暂无盘点记录——可在柜机详情点「柜机盘点」开始第一次实盘</text>
          </view>
          <text v-if="stocktakeHasMore" role="button" class="load-more" @click="loadMore">
            {{
              loadingMore
                ? UI_COPY.loading
                : `加载更多（已显示 ${stocktakeRows.length}/${stocktakeTotal}）`
            }}
          </text>
        </template>
      </template>
    </view>
  </view>
</template>

<style scoped>
.page-body {
  padding: 24rpx var(--page-gutter, 24rpx) calc(48rpx + env(safe-area-inset-bottom));
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.load-more {
  display: block;
  text-align: center;
  color: var(--brand, #0d9488);
  font-size: var(--font-size-caption);
  font-weight: 600;
  padding: 12rpx 0 8rpx;
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
