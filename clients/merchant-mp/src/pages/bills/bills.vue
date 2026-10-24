<template>
  <view class="page-root">
    <app-nav-bar title="账单" home-url="/pages/home/home" />
    <view class="page-body">
      <!-- 筛选：月份 + 设备（全部设备/单台），对照老系统账单页 -->
      <view class="filter-card">
        <picker mode="date" fields="month" :value="month" @change="onMonthChange">
          <view class="filter-chip">
            <text class="filter-label">月份</text>
            <text class="filter-value">{{ month }}</text>
          </view>
        </picker>
        <picker mode="selector" :range="deviceLabels" :value="deviceIndex" @change="onDeviceChange">
          <view class="filter-chip">
            <text class="filter-label">设备</text>
            <text class="filter-value">{{ deviceLabels[deviceIndex] || '全部设备' }}</text>
          </view>
        </picker>
      </view>

      <view v-if="loadError" class="banner-err">
        <text>{{ loadError }}</text>
        <text role="button" aria-label="重试" class="banner-retry" @click="load">重试</text>
      </view>

      <!-- 月销售报表：销售额/销售量/即时退款/余额退款/流量费/利润额 -->
      <view v-if="bill" class="bill-summary">
        <view class="bill-hero">
          <text class="hero-kicker"
            >{{ month }} · {{ deviceLabels[deviceIndex] || '全部设备' }}</text
          >
          <text class="hero-amount">{{ money(bill.salesCents) }}</text>
          <text class="hero-sub">销售额 {{ bill.salesQty }} 件 · {{ bill.orderCount }} 单</text>
        </view>
        <view class="kpi-grid">
          <view class="kpi-cell">
            <text class="kpi-label">销售额</text>
            <text class="kpi-value">{{ money(bill.salesCents) }}</text>
          </view>
          <view class="kpi-cell">
            <text class="kpi-label">销售量</text>
            <text class="kpi-value">{{ bill.salesQty }}</text>
          </view>
          <view class="kpi-cell">
            <text class="kpi-label">即时退款</text>
            <text class="kpi-value">{{ money(bill.instantRefundCents) }}</text>
          </view>
          <view class="kpi-cell">
            <text class="kpi-label">余额退款</text>
            <text class="kpi-value">{{ money(bill.balanceRefundCents) }}</text>
          </view>
          <view class="kpi-cell">
            <text class="kpi-label">流量费</text>
            <text class="kpi-value">{{ money(bill.dataFeeCents) }}</text>
          </view>
          <view class="kpi-cell kpi-profit">
            <text class="kpi-label">利润额</text>
            <text class="kpi-value">{{ money(bill.profitCents) }}</text>
          </view>
        </view>
        <view class="bill-note">
          <text>利润额 = 销售额 − 退款 − 商品成本 − 流量费</text>
        </view>
      </view>

      <view v-if="loading" class="muted center">{{ loadingLabel() }}</view>
      <EmptyState v-else-if="bill && bill.days.length === 0" description="该月暂无账单数据" />

      <!-- 按日明细：每天一块（对照老系统逐日展开行） -->
      <view v-if="bill && bill.days.length" class="section">
        <view class="section-title">
          <text>按日明细</text>
        </view>
        <view v-for="day in bill.days" :key="day.day" class="day-card">
          <view class="day-head">
            <text class="day-date">{{ day.day }}</text>
            <text class="day-profit" :class="{ neg: day.profitCents < 0 }">
              利润 {{ money(day.profitCents) }}
            </text>
          </view>
          <view class="day-grid">
            <view class="day-cell">
              <text class="day-label">销售额</text>
              <text class="day-value">{{ money(day.salesCents) }}</text>
            </view>
            <view class="day-cell">
              <text class="day-label">销售量</text>
              <text class="day-value">{{ day.salesQty }}</text>
            </view>
            <view class="day-cell">
              <text class="day-label">订单</text>
              <text class="day-value">{{ day.orderCount }}</text>
            </view>
            <view class="day-cell">
              <text class="day-label">即时退款</text>
              <text class="day-value">{{ money(day.instantRefundCents) }}</text>
            </view>
            <view class="day-cell">
              <text class="day-label">余额退款</text>
              <text class="day-value">{{ money(day.balanceRefundCents) }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { hasPerm, merchantApi, isMerchantLoggedIn } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantMonthlyBill } from '@aicabinet/shared-types';

const { me } = useMerchantMe();
const canView = computed(() => hasPerm(me.value, 'merchant:settlements:view'));

function currentMonth() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

const month = ref(currentMonth());
const deviceIndex = ref(0);
const devices = ref<Array<{ deviceId: string; deviceName?: string }>>([]);
const deviceLabels = computed(() => [
  '全部设备',
  ...devices.value.map((d) => d.deviceName || d.deviceId)
]);
const bill = ref<MerchantMonthlyBill | null>(null);
const loading = ref(false);
const loadError = ref('');

/** 金额展示（分→元）；非法值回落 0.00 避免模板 NaN */
function money(cents?: number | null) {
  const n = Number(cents);
  return fmtMoney(Number.isFinite(n) ? n : 0);
}

function onMonthChange(e: { detail: { value: string } }) {
  month.value = e.detail.value;
  void load();
}

function onDeviceChange(e: { detail: { value: number } }) {
  deviceIndex.value = Number(e.detail.value) || 0;
  void load();
}

async function loadDevices() {
  try {
    devices.value = ((await merchantApi.devices()) || []).filter(
      (d): d is typeof d & { deviceId: string } => !!d.deviceId
    );
  } catch {
    devices.value = [];
  }
  // 设备列表晚于账单到达时，当前选中的"单台"下标可能越界，回落全部设备
  if (deviceIndex.value > devices.value.length) {
    deviceIndex.value = 0;
    void load();
  }
}

/** 请求序号守卫（接口层审计 2026-10-20 M10）：快速切换月份/设备时旧响应不得覆盖新结果 */
let loadSeq = 0;

async function load() {
  if (!isMerchantLoggedIn()) return;
  if (!canView.value) {
    loadError.value = '没有查看账单的权限';
    return;
  }
  const seq = ++loadSeq;
  loading.value = true;
  loadError.value = '';
  try {
    const deviceId =
      deviceIndex.value > 0 ? devices.value[deviceIndex.value - 1]?.deviceId : undefined;
    const data = await merchantApi.monthlyBill(month.value, deviceId);
    if (seq !== loadSeq) return;
    bill.value = data;
  } catch (err) {
    if (seq !== loadSeq) return;
    loadError.value = err instanceof Error ? err.message : '账单加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

onShow(() => {
  void load();
  // 设备列表低频变化：已有缓存时 onShow 不重拉（省一次全量 devices 请求），下拉刷新才强制更新
  if (!devices.value.length) void loadDevices();
});

onPullDownRefresh(async () => {
  try {
    await Promise.all([load(), loadDevices()]);
  } finally {
    uni.stopPullDownRefresh();
  }
});
</script>

<style scoped>
.page-root {
  min-height: 100vh;
  background: var(--page-bg, #ededed);
}
.page-body {
  padding: 12px 12px 32px;
}
.filter-card {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}
.filter-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--card-bg, #ffffff);
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 14px;
}
.filter-label {
  color: var(--text-muted, #8a8f99);
}
.filter-value {
  font-weight: 600;
  color: var(--text-primary, #1f2329);
}
.banner-err {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fdecec;
  color: var(--danger, #b91c1c);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 13px;
  margin-bottom: 12px;
}
.banner-retry {
  font-weight: 600;
}
.bill-summary {
  background: var(--card-bg, #ffffff);
  border-radius: 12px;
  padding: 14px;
  margin-bottom: 12px;
}
.bill-hero {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line-light, #f0f1f4);
}
.hero-kicker {
  font-size: 12px;
  color: var(--text-muted, #8a8f99);
}
.hero-amount {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-primary, #1f2329);
}
.hero-sub {
  font-size: 12px;
  color: var(--text-muted, #8a8f99);
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  padding-top: 10px;
}
.kpi-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.kpi-label {
  font-size: 12px;
  color: var(--text-muted, #8a8f99);
}
.kpi-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #1f2329);
}
.kpi-profit .kpi-value {
  color: var(--danger, #b91c1c);
}
.bill-note {
  margin-top: 10px;
  font-size: 11px;
  color: var(--text-subtle, #b0b5bd);
}
.section {
  margin-top: 4px;
}
.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 8px 2px;
  color: var(--text-primary, #1f2329);
}
.day-card {
  background: var(--card-bg, #ffffff);
  border-radius: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
}
.day-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--line-light, #f0f1f4);
}
.day-date {
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary, #1f2329);
}
.day-profit {
  font-size: 13px;
  font-weight: 600;
  color: var(--danger, #b91c1c);
}
.day-profit.neg {
  color: var(--brand-deep, #1f9d55);
}
.day-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  padding-top: 8px;
}
.day-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.day-label {
  font-size: 11px;
  color: var(--text-muted, #8a8f99);
}
.day-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary, #1f2329);
}
.muted.center {
  text-align: center;
  color: var(--text-muted, #8a8f99);
  padding: 24px 0;
  font-size: 13px;
}
</style>
