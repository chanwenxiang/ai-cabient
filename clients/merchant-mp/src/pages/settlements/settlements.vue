<template>
  <view class="page-root">
    <view class="date-bar">
      <!-- H5：原生 date 用浏览器浮层日历，避免 uni-picker 窄屏把年列表撑进页面 -->
      <input
        v-if="isH5"
        class="date-input"
        type="date"
        :value="startDate"
        :max="endDate"
        @change="onStartDate"
      />
      <picker
        v-else
        mode="date"
        :value="startDate"
        :start="pickerStart"
        :end="endDate"
        @change="onStartDate"
      >
        <text class="date-text">{{ startDate }}</text>
      </picker>
      <text class="date-sep">至</text>
      <input
        v-if="isH5"
        class="date-input"
        type="date"
        :value="endDate"
        :min="startDate"
        @change="onEndDate"
      />
      <picker
        v-else
        mode="date"
        :value="endDate"
        :start="startDate"
        :end="pickerEnd"
        @change="onEndDate"
      >
        <text class="date-text">{{ endDate }}</text>
      </picker>
    </view>

    <view v-if="loadError" class="banner-err">
      <text>{{ loadError }}</text>
      <text class="banner-retry" @click="load">重试</text>
    </view>

    <view class="summary-card">
      <view class="summary-row">
        <text class="summary-label">区间营收</text>
        <text class="summary-value">¥{{ summary.gross }}</text>
      </view>
      <view class="summary-row">
        <text class="summary-label">平台抽成</text>
        <text class="summary-value minus">-¥{{ summary.platformFee }}</text>
      </view>
      <view class="summary-row total">
        <text class="summary-label">商户所得</text>
        <text class="summary-value">¥{{ summary.merchantIncome }}</text>
      </view>
      <view class="summary-row">
        <text class="summary-label">待分账</text>
        <text class="summary-value">¥{{ summary.pending }}</text>
      </view>
      <view class="summary-row">
        <text class="summary-label">本月已结算</text>
        <text class="summary-value">¥{{ summary.settledMonth }}</text>
      </view>
    </view>

    <view class="tip-card">
      <text class="tip-text">T+1 结算：当日支付流水通常次日完成入账；分账由平台定期提交至微信收款账户，商户端不支持自主提现。</text>
      <text v-if="profitNote" class="tip-meta">{{ profitNote }}</text>
    </view>

    <view class="section">
      <text class="section-title">按日汇总</text>
      <view v-if="loading" class="loading-inline">结算数据加载中…</view>
      <template v-else>
        <view v-for="d in daily" :key="d.date" class="device-row">
          <view class="device-info">
            <text class="device-name">{{ d.date }}</text>
            <text class="device-orders">
              {{ d.orderCount }} 笔 · 实付 ¥{{ (d.grossCents / 100).toFixed(2) }} · 抽成 ¥{{ (d.platformCents / 100).toFixed(2) }}
            </text>
            <text class="device-orders">待分 ¥{{ (d.pendingCents / 100).toFixed(2) }} · 已结 ¥{{ (d.settledCents / 100).toFixed(2) }}</text>
          </view>
          <text class="device-amount">¥{{ (d.merchantCents / 100).toFixed(2) }}</text>
        </view>
        <empty-state
          v-if="!daily.length"
          compact
          icon="📅"
          title="所选日期暂无结算数据"
          hint="可调整上方日期范围，或等待订单完成分账"
        />
      </template>
    </view>

    <view class="section">
      <text class="section-title">结算批次</text>
      <view v-if="batchWarn" class="section-warn">{{ batchWarn }}</view>
      <view v-if="loading" class="loading-inline">批次加载中…</view>
      <template v-else>
        <view v-for="b in batches" :key="b.batchNo" class="device-row">
          <view class="device-info">
            <text class="device-name">{{ b.batchNo }}</text>
            <text class="device-orders">{{ batchStatusLabel(b.batchStatus) }} · {{ b.orderCount }} 笔</text>
          </view>
          <text class="device-amount">¥{{ (b.merchantCents / 100).toFixed(2) }}</text>
        </view>
        <empty-state
          v-if="!batches.length"
          compact
          icon="📦"
          title="暂无结算批次"
          hint="平台定期提交分账后，批次会显示在这里"
        />
      </template>
    </view>

    <view v-if="canExport" class="actions">
      <button class="btn-outline" @click="onExport">导出对账单</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import EmptyState from '@/components/empty-state.vue';
import { dictLabel } from '@aicabinet/shared-dict';
import { hasPerm, merchantApi, downloadAuthedFile, openExportedFile, getToken } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantDailySettlement, MerchantMe, MerchantSettlementBatch } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canViewSettlements = computed(() => hasPerm(me.value, 'merchant:settlements:view'));
const canExport = computed(() => hasPerm(me.value, 'merchant:settlements:export'));
const isH5 = typeof document !== 'undefined';
/** 小程序 picker 限制可选年份，避免滚轮过长 */
const pickerStart = '2020-01-01';
const pickerEnd = '2035-12-31';

function localDateISO(d: Date) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function batchStatusLabel(status?: string) {
  if (!status) return '-';
  const key = status.toUpperCase();
  const fallback: Record<string, string> = {
    PENDING: '待结算',
    PROCESSING: '结算中',
    SETTLED: '已结算',
    PAID: '已支付',
    FAILED: '失败',
    PARTIAL_FAILED: '部分失败',
    COMPLETED: '已完成'
  };
  const fromDict = dictLabel('settlement_batch_status', status);
  // dictLabel returns the raw code when type/code is missing — prefer Chinese fallback then.
  if (fromDict && fromDict.toUpperCase() !== key) return fromDict;
  return fallback[key] || status;
}

const today = localDateISO(new Date());
const sevenDaysAgo = localDateISO(new Date(Date.now() - 7 * 86400000));

const startDate = ref(sevenDaysAgo);
const endDate = ref(today);
const summary = ref({
  gross: '0.00',
  platformFee: '0.00',
  merchantIncome: '0.00',
  pending: '0.00',
  settledMonth: '0.00'
});
const daily = ref<MerchantDailySettlement[]>([]);
const batches = ref<MerchantSettlementBatch[]>([]);
const profitNote = ref('');
const loading = ref(false);
const loadError = ref('');
const batchWarn = ref('');
let loadSeq = 0;

function readDateEvent(e: { detail?: { value?: string }; target?: { value?: string } }) {
  return String(e?.detail?.value ?? e?.target?.value ?? '').trim();
}

function onStartDate(e: { detail?: { value?: string }; target?: { value?: string } }) {
  const v = readDateEvent(e);
  if (!v) return;
  startDate.value = v;
  void load();
}

function onEndDate(e: { detail?: { value?: string }; target?: { value?: string } }) {
  const v = readDateEvent(e);
  if (!v) return;
  endDate.value = v;
  void load();
}

onShow(() => load());
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

async function load() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  // 日期非法时先短路，避免无意义的 refreshMe / 接口等待
  if (startDate.value > endDate.value) {
    loadError.value = '开始日期不能晚于结束日期';
    daily.value = [];
    batches.value = [];
    summary.value = { gross: '0.00', platformFee: '0.00', merchantIncome: '0.00', pending: '0.00', settledMonth: '0.00' };
    profitNote.value = '';
    loading.value = false;
    return;
  }
  const seq = ++loadSeq;
  try {
    await refreshMe();
  } catch {
    if (!getToken()) return;
    me.value = me.value || (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (seq !== loadSeq) return;
  if (!canViewSettlements.value) {
    uni.showToast({ title: '无结算权限', icon: 'none' });
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  loading.value = true;
  loadError.value = '';
  batchWarn.value = '';
  try {
    const [overviewRes, daysRes, batchRes] = await Promise.allSettled([
      merchantApi.settlements(),
      merchantApi.dailySettlements(startDate.value, endDate.value),
      merchantApi.settlementBatches(startDate.value, endDate.value)
    ]);
    if (seq !== loadSeq) return;

    if (overviewRes.status === 'rejected' && daysRes.status === 'rejected') {
      throw overviewRes.reason instanceof Error
        ? overviewRes.reason
        : new Error('结算数据加载失败');
    }

    const days = daysRes.status === 'fulfilled' ? daysRes.value || [] : [];
    daily.value = days;
    if (daysRes.status === 'rejected') {
      loadError.value =
        daysRes.reason instanceof Error ? daysRes.reason.message : '按日汇总加载失败';
    }

    if (batchRes.status === 'fulfilled') {
      batches.value = batchRes.value || [];
    } else {
      batches.value = [];
      batchWarn.value =
        batchRes.reason instanceof Error ? batchRes.reason.message : '结算批次加载失败';
    }

    const overview =
      overviewRes.status === 'fulfilled'
        ? overviewRes.value
        : ({ pendingAmountCents: 0, settledMonthCents: 0 } as Awaited<
            ReturnType<typeof merchantApi.settlements>
          >);

    const gross = days.reduce((s, d) => s + (d.grossCents || 0), 0);
    const platform = days.reduce((s, d) => s + (d.platformCents || 0), 0);
    const merchant = days.reduce((s, d) => s + (d.merchantCents || 0), 0);
    summary.value = {
      gross: (gross / 100).toFixed(2),
      platformFee: (platform / 100).toFixed(2),
      merchantIncome: (merchant / 100).toFixed(2),
      pending: ((overview.pendingAmountCents || 0) / 100).toFixed(2),
      settledMonth: ((overview.settledMonthCents || 0) / 100).toFixed(2)
    };
    profitNote.value = overview.profitSharing?.note || '';
  } catch (e: unknown) {
    if (seq !== loadSeq) return;
    loadError.value = e instanceof Error ? e.message : '加载失败';
    uni.showToast({ title: loadError.value, icon: 'none' });
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function onExport() {
  if (!canExport.value) {
    uni.showToast({ title: '无导出权限', icon: 'none' });
    return;
  }
  if (startDate.value > endDate.value) {
    uni.showToast({ title: '开始日期不能晚于结束日期', icon: 'none' });
    return;
  }
  const url = merchantApi.exportSettlementsUrl(startDate.value, endDate.value);
  void downloadAuthedFile(url)
    .then(async (tempFilePath) => {
      await openExportedFile(tempFilePath, `settlements-${startDate.value}-${endDate.value}.xlsx`);
      uni.showToast({ title: '导出成功', icon: 'success' });
    })
    .catch((e) => {
      uni.showToast({ title: e instanceof Error ? e.message : '导出失败', icon: 'none' });
    });
}
</script>

<style scoped>
.page-root { padding: 20rpx; background: #f0fdfa; min-height: 100vh; }
.date-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
  background: #fff;
  border-radius: 16rpx;
  padding: 16rpx 20rpx;
  margin-bottom: 20rpx;
  overflow: hidden;
  max-height: 96rpx;
  position: relative;
  z-index: 2;
}
.date-text { font-size: 28rpx; color: #0f766e; font-weight: 500; }
.date-input {
  flex: 1;
  min-width: 0;
  max-width: 280rpx;
  height: 56rpx;
  line-height: 56rpx;
  text-align: center;
  font-size: 26rpx;
  color: #0f766e;
  font-weight: 500;
  background: #f0fdfa;
  border: 1rpx solid #ccfbf1;
  border-radius: 12rpx;
  padding: 0 12rpx;
  box-sizing: border-box;
}
.date-sep { color: #999; flex-shrink: 0; }
/* 兜底：若仍混入 uni picker 系统输入，禁止把年列表撑进文档流 */
.date-bar :deep(.uni-picker-system_input),
.date-bar :deep(input.uni-input-input) {
  max-height: 56rpx !important;
  overflow: hidden !important;
}
.summary-card { background: linear-gradient(135deg, #0f766e, #134e4a); border-radius: 16rpx; padding: 30rpx; margin-bottom: 20rpx; }
.summary-row { display: flex; justify-content: space-between; padding: 12rpx 0; }
.summary-row.total { border-top: 1rpx solid rgba(255,255,255,.2); margin-top: 10rpx; padding-top: 20rpx; }
.summary-label { color: rgba(255,255,255,.8); font-size: 26rpx; }
.summary-value { color: #fff; font-size: 30rpx; font-weight: 600; }
.summary-value.minus { color: rgba(255,255,255,.7); }
.tip-card { background: #ecfdf5; border-radius: 12rpx; padding: 20rpx; margin-bottom: 20rpx; }
.tip-text { font-size: 24rpx; color: #0f766e; display: block; line-height: 1.5; }
.tip-meta { font-size: 22rpx; color: #64748b; margin-top: 8rpx; display: block; }
.banner-err {
  margin-bottom: 16rpx;
  padding: 16rpx 20rpx;
  border-radius: 12rpx;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 24rpx;
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.banner-retry { color: #0f766e; font-weight: 600; }
.section-warn {
  margin-bottom: 12rpx;
  padding: 12rpx 16rpx;
  border-radius: 10rpx;
  background: #fff7ed;
  color: #c2410c;
  font-size: 22rpx;
}
.section { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; }
.section-title { font-size: 28rpx; font-weight: 600; margin-bottom: 16rpx; display: block; }
.device-row { display: flex; justify-content: space-between; align-items: center; padding: 14rpx 0; border-bottom: 1rpx solid #f0fdfa; }
.device-name { font-size: 28rpx; display: block; }
.device-orders { font-size: 22rpx; color: #999; }
.device-amount { font-size: 28rpx; font-weight: 600; }
.loading-inline { font-size: 24rpx; color: #94a3b8; padding: 24rpx 0; text-align: center; }
.actions { padding: 20rpx 0; }
.btn-outline {
  width: 100%;
  height: 80rpx;
  line-height: 80rpx;
  border: 2rpx solid #0f766e;
  color: #0f766e;
  border-radius: 44rpx;
  background: #fff;
  font-size: 28rpx;
  font-weight: 600;
  text-align: center;
}
.btn-outline::after { border: none; }
</style>
