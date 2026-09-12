<template>
  <view class="page-root">
    <app-nav-bar title="余额明细" />
    <view class="page-body">
      <view role="button" class="summary" @click="goRecharge">
        <view class="summary-main">
          <text class="summary-label">可用余额</text>
          <text class="summary-value">{{ balanceYuan }}</text>
          <text v-if="frozenYuan !== '¥0.00'" class="summary-sub">冻结 {{ frozenYuan }}</text>
        </view>
        <text class="summary-link app-link-chevron">去充值</text>
      </view>

      <view class="card">
        <view class="card-head">
          <text class="card-title">流水记录</text>
        </view>
        <view v-if="loading && !transactions.length" class="empty">{{ UI_COPY.loading }}</view>
        <view v-else-if="!transactions.length" class="empty">
          <text class="empty-title">暂无余额流水</text>
          <text class="empty-hint">购物扣款、退款与充值会出现在这里</text>
        </view>
        <view v-else class="log-list">
          <view v-for="item in transactions" :key="item.transactionId" class="log-row">
            <view class="log-main">
              <text class="log-title">{{ transactionLabel(item.businessType) }}</text>
              <text class="log-time">{{ formatTransactionTime(item.createdAt) }}</text>
              <text v-if="item.businessId" class="log-meta"
                >单号 {{ shortBizNo(item.businessId) }}</text
              >
              <text v-if="item.balanceAfterCents != null" class="log-meta"
                >余额 {{ fmtMoney(item.balanceAfterCents) }}</text
              >
            </view>
            <text class="log-amount" :class="{ income: item.amountCents > 0 }">{{
              formatTransactionAmount(item.amountCents)
            }}</text>
          </view>
          <view v-if="hasMore" class="more" role="button" @click="loadTransactions(false)">
            {{ loading ? UI_COPY.loading : `加载更多（已显示 ${transactions.length} 条）` }}
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { showError } from '@/utils/notify';
import { onShow } from '@dcloudio/uni-app';
import type { AccountDto, BalanceTransactionDto } from '@aicabinet/shared-types';
import { formatDateTimeShort, fmtMoney, shortBizNo } from '@aicabinet/shared-uni/format';
import { consumerApi, ensureConsumerAuth, getConsumerToken } from '@/utils/consumer-api';
import { availableCents } from '@/utils/account';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

const PAGE_SIZE = 20;
const loading = ref(false);
const balanceYuan = ref('--');
const frozenYuan = ref('¥0.00');
const account = ref<AccountDto | null>(null);
const transactions = ref<BalanceTransactionDto[]>([]);
const page = ref(0);
const hasMore = ref(false);

onShow(async () => {
  if (!(await ensureConsumerAuth()) || !getConsumerToken()) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/balance/balance')
    });
    return;
  }
  await loadAccount();
  await loadTransactions(true);
});

async function loadAccount() {
  try {
    account.value = await consumerApi.account();
    balanceYuan.value = fmtMoney(availableCents(account.value));
    const frozen = Number(account.value?.frozenCents ?? 0);
    frozenYuan.value = fmtMoney(Number.isFinite(frozen) ? frozen : 0);
  } catch {
    balanceYuan.value = '--';
    frozenYuan.value = '¥0.00';
  }
}

function loadTransactions(reset = true): Promise<void> {
  if (loading.value) return Promise.resolve();
  if (!reset && !hasMore.value) return Promise.resolve();
  loading.value = true;
  const nextPage = reset ? 0 : page.value + 1;
  return consumerApi
    .balanceTransactions(nextPage, PAGE_SIZE)
    .then((data) => {
      const items = data.items || [];
      const total = Number(data.total ?? items.length);
      if (reset) {
        transactions.value = items;
      } else {
        const seen = new Set(transactions.value.map((t) => t.transactionId));
        transactions.value = transactions.value.concat(
          items.filter((t) => t.transactionId && !seen.has(t.transactionId))
        );
      }
      page.value = nextPage;
      hasMore.value =
        items.length >= PAGE_SIZE &&
        transactions.value.length < Math.max(total, transactions.value.length);
    })
    .catch((e) => {
      showError(e instanceof Error ? e.message : '加载失败');
    })
    .finally(() => {
      loading.value = false;
    });
}

function transactionLabel(type: string) {
  if (type === 'CHARGE') return '购物扣款';
  if (type === 'REFUND') return '订单退款';
  if (type === 'ADMIN_ADJUST') return '运营调整';
  if (type === 'ADJUST_CHARGE') return '订单补扣';
  if (type === 'RECHARGE') return '余额充值';
  return '余额变动';
}

function formatTransactionTime(value?: string) {
  return formatDateTimeShort(value);
}

function formatTransactionAmount(cents: number) {
  const signed = fmtMoney(Math.abs(cents || 0));
  let sign = '';
  if (cents > 0) sign = '+';
  else if (cents < 0) sign = '-';
  return `${sign}${signed}`;
}

function goRecharge() {
  uni.navigateTo({ url: '/pages/recharge/recharge' });
}
</script>

<style scoped>
.page-root {
  min-height: 100%;
  padding: 0;
  background: var(--card-bg, #ffffff);
  box-sizing: border-box;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.summary {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20rpx;
  padding: 32rpx;
  border-radius: var(--radius-card);
  background: linear-gradient(135deg, var(--brand-soft), var(--white));
  border: 1rpx solid var(--brand-soft, #d1fae5);
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  margin: 0 0 24rpx;
}
.summary-main {
  flex: 1;
  min-width: 0;
}
.summary-label {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
}
.summary-value {
  display: block;
  margin-top: 8rpx;
  font-size: 56rpx;
  font-weight: 800;
  line-height: 1;
  color: var(--brand);
}
.summary-sub {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
}
.summary-link {
  flex-shrink: 0;
  font-size: var(--font-size-body);
  color: var(--brand);
  font-weight: 600;
}
.card {
  margin: 0;
  padding: 28rpx 24rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.04);
}
.card-head {
  margin-bottom: 8rpx;
}
.card-title {
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #1b3027);
}
.empty {
  padding: 48rpx 0 32rpx;
  text-align: center;
  color: var(--text-muted, #8a968e);
  font-size: var(--font-size-body);
}
.empty-title {
  display: block;
  font-size: var(--font-size-body);
  color: var(--text-muted, #4b5563);
}
.empty-hint {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #9aa4a0);
}
.log-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 22rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f0f2f1);
}
.log-row:last-child {
  border-bottom: none;
}
.log-main {
  flex: 1;
  min-width: 0;
  padding-right: 16rpx;
}
.log-title {
  display: block;
  font-size: var(--font-size-md);
  color: var(--text-primary, #1f2a24);
}
.log-time,
.log-meta {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #9aa4a0);
}
.log-amount {
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #1f2a24);
}
.log-amount.income {
  color: var(--brand);
}
.more {
  padding: 24rpx 0 8rpx;
  text-align: center;
  font-size: var(--font-size-caption);
  color: var(--brand);
}
</style>
