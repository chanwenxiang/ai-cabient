<template>
  <view class="page-root">
    <view v-if="loadError" class="banner-err">
      <text>{{ loadError }}</text>
      <text class="banner-retry" @click="load">重试</text>
    </view>

    <empty-state
      v-if="!loading && overview && !overview.bound"
      icon="线"
      title="未绑定线长身份"
      hint="线长钱包仅对已绑定的线长成员开放。商户主体提现请使用「商户钱包」"
    />

    <template v-else-if="overview && overview.bound">
      <view class="summary-card">
        <text class="role-tag">线长 · 可自主提现</text>
        <text class="name"
          >{{ emptyDisplay(overview.managerName, 'text') }} ·
          {{ emptyDisplay(overview.phone, 'text') }}</text
        >
        <view class="bal-row">
          <text class="bal-label">可用余额</text>
          <text class="bal-value">¥{{ yuan(overview.availableCents) }}</text>
        </view>
        <view class="bal-sub">
          <text>账面 ¥{{ yuan(overview.balanceCents) }}</text>
          <text>冻结 ¥{{ yuan(overview.frozenCents) }}</text>
        </view>
      </view>

      <view class="action-card">
        <input
          class="amount-input"
          type="digit"
          v-model="amountYuan"
          placeholder="提现金额（元）"
        />
        <text v-if="maxWithdrawYuan" class="withdraw-hint">最多可提现 ¥{{ maxWithdrawYuan }}</text>
        <button class="btn-primary" :disabled="submitting" @click="submitWithdraw">申请提现</button>
        <text class="tip">提交后由运营审核；演示环境默认 Mock 打款到账。</text>
      </view>

      <view class="section">
        <text class="section-title">最近提现</text>
        <view v-for="w in overview.recentWithdraws || []" :key="w.requestId" class="row-item">
          <view class="row-main">
            <text>¥{{ yuan(w.amountCents) }}</text>
            <text class="status">{{ withdrawStatus(w.status) }}</text>
          </view>
          <text class="row-sub">{{ emptyDisplay(w.requestNo, 'order') }}</text>
        </view>
        <empty-state
          v-if="!(overview.recentWithdraws || []).length"
          compact
          icon="提"
          title="暂无提现记录"
          hint="提交提现后会出现在这里"
        />
      </view>

      <view class="section">
        <text class="section-title">最近流水</text>
        <view v-for="l in overview.recentLedgers || []" :key="l.ledgerId" class="row-item">
          <view class="row-main">
            <text>{{ ledgerLabel(l.entryType) }}</text>
            <text :class="{ credit: Number(l.amountCents) > 0, debit: Number(l.amountCents) < 0 }">
              {{ formatSigned(l.amountCents) }}
            </text>
          </view>
          <text class="row-sub">{{ emptyDisplay(l.remark, 'text') }}</text>
        </view>
        <empty-state
          v-if="!(overview.recentLedgers || []).length"
          compact
          icon="流"
          title="暂无流水记录"
          hint="佣金入账与提现变动会显示在这里"
        />
      </view>
    </template>

    <view v-if="loading" class="loading-inline">加载中…</view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { displayLabel } from '@aicabinet/shared-dict';
import { emptyDisplay } from '@aicabinet/shared-uni/format';
import EmptyState from '@/components/empty-state.vue';
import {
  merchantApi,
  getToken,
  handleUnauthorized,
  type LineWalletOverview
} from '@/utils/merchant-api';

const loading = ref(false);
const submitting = ref(false);
const loadError = ref('');
const amountYuan = ref('');
const overview = ref<LineWalletOverview | null>(null);
const maxWithdrawYuan = computed(() =>
  overview.value?.availableCents != null ? yuan(overview.value.availableCents) : ''
);

function yuan(cents?: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

function formatSigned(cents?: number) {
  const n = Number(cents) || 0;
  const abs = Math.abs(n) / 100;
  const sign = n > 0 ? '+' : n < 0 ? '-' : '';
  return `${sign}¥${abs.toFixed(2)}`;
}

function withdrawStatus(status?: string) {
  return displayLabel('line_withdraw_status', status, '未知状态');
}

function ledgerLabel(type?: string) {
  return displayLabel('wallet_ledger_type', type, emptyDisplay(type, 'text'));
}

async function load() {
  if (!getToken()) {
    handleUnauthorized();
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    overview.value = await merchantApi.lineWallet();
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function submitWithdraw() {
  const yuanNum = Number(amountYuan.value);
  if (!yuanNum || yuanNum <= 0) {
    uni.showToast({ title: '请输入金额', icon: 'none' });
    return;
  }
  const available = Number(overview.value?.availableCents ?? 0);
  if (Math.round(yuanNum * 100) > available) {
    uni.showToast({ title: `超出可提现余额（最多 ¥${yuan(available)}）`, icon: 'none' });
    return;
  }
  submitting.value = true;
  try {
    await merchantApi.lineWalletWithdraw({
      amountCents: Math.round(yuanNum * 100),
      // 客户端请求号：时间戳 + 随机段，降低同毫秒重复请求的幂等碰撞风险
      requestNo: 'MP-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
    });
    uni.showToast({ title: '已提交', icon: 'success' });
    amountYuan.value = '';
    await load();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '提交失败', icon: 'none' });
  } finally {
    submitting.value = false;
  }
}

onShow(load);
</script>

<style scoped>
.summary-card,
.action-card,
.section {
  background: #fff;
  border-radius: var(--card-radius, 22rpx);
  padding: 28rpx;
  margin-bottom: 20rpx;
  border: 1rpx solid var(--card-border, #e2e8f0);
}
.role-tag {
  font-size: 22rpx;
  color: var(--brand, #0f766e);
  font-weight: 600;
}
.name {
  display: block;
  margin-top: 8rpx;
  color: var(--text-muted, #64748b);
  font-size: 24rpx;
}
.bal-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-top: 24rpx;
}
.bal-label {
  color: var(--text-muted, #64748b);
}
.bal-value {
  font-size: 48rpx;
  font-weight: 700;
  color: #0f172a;
}
.bal-sub {
  display: flex;
  gap: 24rpx;
  margin-top: 12rpx;
  color: var(--text-subtle, #94a3b8);
  font-size: 22rpx;
}
.amount-input {
  width: 100%;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 16rpx;
  padding: 20rpx;
  margin-bottom: 16rpx;
  box-sizing: border-box;
}
.withdraw-hint {
  display: block;
  font-size: 22rpx;
  color: #94a3b8;
  margin: -6rpx 0 12rpx;
}
.tip {
  display: block;
  margin-top: 12rpx;
  font-size: 22rpx;
  color: var(--text-subtle, #94a3b8);
  line-height: 1.45;
}
.section-title {
  font-weight: 600;
  margin-bottom: 12rpx;
  display: block;
}
.row-item {
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}
.row-main {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
  font-size: 28rpx;
}
.row-sub {
  font-size: 22rpx;
  color: var(--text-subtle, #94a3b8);
  margin-top: 4rpx;
  display: block;
}
.status {
  color: var(--brand, #0f766e);
  font-weight: 500;
}
.credit {
  color: #059669;
  font-weight: 600;
}
.debit {
  color: #b91c1c;
  font-weight: 600;
}
.banner-err {
  background: #fef2f2;
  color: #b91c1c;
  padding: 16rpx 20rpx;
  border-radius: 12rpx;
  margin-bottom: 16rpx;
  font-size: 26rpx;
}
.banner-retry {
  margin-left: 16rpx;
  text-decoration: underline;
}
.loading-inline {
  text-align: center;
  color: var(--text-subtle, #94a3b8);
  padding: 40rpx;
}
</style>
