<template>
  <view>
    <view v-if="loading" class="card"><text>加载中…</text></view>
    <view v-else-if="error" class="card"><text class="err">{{ error }}</text></view>
    <view v-else>
      <view class="dash-header">
        <text class="hello">你好，{{ meName }}</text>
        <text class="sub">{{ merchantNames }}</text>
      </view>

      <view class="kpi-grid">
        <view class="kpi-card">
          <text class="kpi-label">今日营收</text>
          <text class="kpi-value">{{ revenueToday }}</text>
        </view>
        <view class="kpi-card">
          <text class="kpi-label">今日收入</text>
          <text class="kpi-value">{{ incomeToday }}</text>
        </view>
        <view class="kpi-card">
          <text class="kpi-label">在线柜机</text>
          <text class="kpi-value">{{ stats.onlineDevices ?? '-' }}</text>
        </view>
        <view class="kpi-card">
          <text class="kpi-label">总柜机</text>
          <text class="kpi-value">{{ stats.totalDevices ?? '-' }}</text>
        </view>
      </view>

      <view class="action-grid">
        <view class="action-card" @click="goTab('/pages/alerts/alerts')">
          <text class="action-value" :class="{ urgent: pendingCount > 0 }">{{ pendingCount }}</text>
          <text class="action-label">项待处理</text>
        </view>
        <view class="action-card" @click="goTab('/pages/devices/devices')">
          <text class="action-value">{{ offlineCount }}</text>
          <text class="action-label">台离线柜机</text>
        </view>
        <view class="action-card" @click="goPricing">
          <text class="action-icon">¥</text>
          <text class="action-label">点位定价</text>
        </view>
      </view>

      <view v-if="actionItems.length" class="card todo-card" @click="goTab('/pages/alerts/alerts')">
        <view class="todo-head"><text class="section">优先待办</text><text class="todo-more">查看全部 ›</text></view>
        <view v-for="item in actionItems" :key="item.type + item.title" class="todo-row">
          <text class="todo-dot" />
          <view class="todo-copy"><text class="todo-title">{{ item.title }}</text><text v-if="item.detail" class="todo-detail">{{ item.detail }}</text></view>
        </view>
      </view>

      <view class="card">
        <text class="section">近7日营收趋势</text>
        <view class="bars">
          <view v-for="b in trendBars" :key="b.date" class="bar-wrap">
            <view class="bar" :style="{ height: b.height + 'rpx' }" />
            <text class="bar-label">{{ b.label }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { merchantApi } from '@/utils/merchant-api';
import type { MerchantMe } from '@aicabinet/shared-types';

const loading = ref(true);
const error = ref('');
const meName = ref('');
const merchantNames = ref('');
const stats = ref<Record<string, unknown>>({});
const revenueToday = ref('-');
const incomeToday = ref('-');
const trendBars = ref<{ date: string; label: string; height: number }[]>([]);
const pendingCount = ref(0);
const offlineCount = ref(0);
const actionItems = ref<{ type: string; title: string; detail?: string }[]>([]);

function fmtMoney(cents?: number) {
  if (cents == null) return '-';
  return '¥' + (cents / 100).toFixed(2);
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const me = (await merchantApi.me()) as MerchantMe;
    uni.setStorageSync('merchant_me', me);
    const [s, trend, workbench] = await Promise.all([
      merchantApi.stats() as Promise<Record<string, number>>,
      merchantApi.trend(7) as Promise<{ last7Days?: { date: string; revenueCents: number }[] }>,
      merchantApi.workbench()
    ]);
    const days = trend.last7Days || [];
    const maxRev = Math.max(...days.map((d) => d.revenueCents), 1);
    meName.value = me.displayName || me.phoneNumber || '商户';
    merchantNames.value = (me.merchants || []).map((m) => m.merchantName).join('、') || '未绑定';
    stats.value = s;
    revenueToday.value = fmtMoney(s.revenueTodayCents);
    incomeToday.value = fmtMoney(s.merchantIncomeTodayCents);
    offlineCount.value = workbench.offlineDevices || 0;
    pendingCount.value = (workbench.openDisputes || 0) + (workbench.offlineDevices || 0) +
      (workbench.lowStockItems || 0) + (workbench.expiryAlerts || 0);
    actionItems.value = (workbench.actionItems || []).slice(0, 3);
    trendBars.value = days.map((d) => ({
      date: d.date,
      label: d.date.slice(5),
      height: Math.max(16, Math.round((d.revenueCents / maxRev) * 120))
    }));
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function goTab(url: string) { uni.switchTab({ url }); }
function goPricing() { uni.navigateTo({ url: '/pages/pricing/pricing' }); }

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));
</script>

<style scoped>
.dash-header { background: linear-gradient(135deg, #134e4a, #0f766e); padding: 40rpx 32rpx; color: #fff; }
.hello { font-size: 36rpx; font-weight: 700; display: block; }
.sub { font-size: 24rpx; opacity: 0.85; display: block; margin-top: 4rpx; }
.kpi-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12rpx; margin: 12rpx; }
.kpi-card { background: #fff; border-radius: 16rpx; padding: 24rpx; box-shadow: 0 2px 12px rgba(15,118,110,0.08); }
.kpi-label { font-size: 22rpx; color: #64748b; display: block; }
.kpi-value { font-size: 32rpx; font-weight: 700; color: #0f766e; display: block; margin-top: 8rpx; }
.section { font-weight: 600; display: block; margin-bottom: 16rpx; }
.action-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12rpx; margin: 0 12rpx 12rpx; }
.action-card { background: #fff; border-radius: 16rpx; padding: 20rpx 10rpx; text-align: center; box-shadow: 0 2px 12px rgba(15,118,110,0.08); }
.action-value, .action-icon { display: block; color: #0f766e; font-size: 34rpx; font-weight: 700; }
.action-value.urgent { color: #dc2626; }
.action-label { display: block; color: #64748b; font-size: 22rpx; margin-top: 6rpx; }
.todo-head { display: flex; align-items: center; justify-content: space-between; }
.todo-more { color: #0f766e; font-size: 24rpx; }
.todo-row { display: flex; align-items: flex-start; padding: 14rpx 0; border-top: 1rpx solid #f1f5f9; }
.todo-dot { width: 12rpx; height: 12rpx; flex: 0 0 auto; margin: 13rpx 14rpx 0 0; border-radius: 50%; background: #f59e0b; }
.todo-copy { min-width: 0; }
.todo-title { display: block; font-size: 26rpx; color: #0f172a; }
.todo-detail { display: block; margin-top: 4rpx; color: #64748b; font-size: 22rpx; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bars { display: flex; align-items: flex-end; gap: 8rpx; height: 160rpx; }
.bar-wrap { flex: 1; display: flex; flex-direction: column; align-items: center; }
.bar { width: 100%; background: linear-gradient(180deg, #14b8a6, #0f766e); border-radius: 6rpx 6rpx 0 0; min-height: 8rpx; }
.bar-label { font-size: 20rpx; color: #64748b; margin-top: 6rpx; }
.err { color: #ef4444; }
</style>
