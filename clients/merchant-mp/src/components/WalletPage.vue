<template>
  <view class="page-root">
    <app-nav-bar :title="cfg.title" />
    <view class="page-body">
      <view v-if="loadError" class="banner-err">
        <text>{{ loadError }}</text>
        <text role="button" aria-label="重试" class="banner-retry" @click="load">重试</text>
      </view>

      <empty-state
        v-if="!loading && overview && !overview.bound"
        :icon="cfg.emptyIcon"
        :title="cfg.emptyTitle"
        :hint="cfg.emptyHint"
      />

      <template v-else-if="overview && overview.bound">
        <view class="summary-card">
          <text class="role-tag">{{ cfg.roleTag }}</text>
          <text class="name">{{ displayName }}</text>
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
          <view class="amount-field">
            <input
              class="amount-input"
              type="digit"
              v-model="amountYuan"
              placeholder="提现金额（元）"
              placeholder-class="amount-ph"
            />
          </view>
          <text v-if="maxWithdrawYuan" class="withdraw-hint"
            >最多可提现 ¥{{ maxWithdrawYuan }}</text
          >
          <!-- H53：绑定多商户时必须显式选择提现商户（后端未指定且多绑定返回 400「请指定提现商户」） -->
          <picker
            v-if="multiMerchant"
            :range="merchantOptions"
            range-key="label"
            @change="onMerchantPick"
          >
            <view class="picker">提现商户：{{ selectedMerchantLabel }}</view>
          </picker>
          <app-button
            :disabled="submitting"
            :loading="submitting"
            label="申请提现"
            @click="submitWithdraw"
          />
          <text class="tip">{{ cfg.tip }}</text>
        </view>

        <view class="section">
          <text class="section-title">最近提现</text>
          <view v-for="w in overview.recentWithdraws || []" :key="w.requestId" class="row-item">
            <view class="row-main">
              <text>¥{{ yuan(w.amountCents) }}</text>
              <text class="status">{{ withdrawStatus(w.status) }}</text>
            </view>
            <text class="row-sub"
              >{{ emptyDisplay(w.requestNo, 'order')
              }}{{
                w.payChannel ? ` · ${displayLabel('pay_channel', w.payChannel, w.payChannel)}` : ''
              }}</text
            >
            <text class="row-sub"
              >手续费
              {{
                w.feeCents == null
                  ? '—'
                  : `¥${yuan(w.feeCents)}${Number(w.feeCents) === 0 ? '（免收）' : ''}`
              }}
              · 到账 ¥{{
                yuan(Math.max(0, Number(w.amountCents ?? 0) - Number(w.feeCents ?? 0)))
              }}</text
            >
            <text v-if="w.payoutRef || w.payoutMessage" class="row-sub"
              >回执 {{ w.payoutRef || w.payoutMessage }}</text
            >
            <text v-if="w.reviewRemark" class="row-sub fail">备注 {{ w.reviewRemark }}</text>
            <text v-if="w.paidAt || w.createdAt" class="row-sub">{{
              formatTime(w.paidAt || w.createdAt)
            }}</text>
          </view>
          <empty-state
            v-if="!(overview.recentWithdraws || []).length"
            compact
            icon="/static/menu/wallet.png"
            title="暂无提现记录"
            hint="提交提现后会出现在这里"
          />
        </view>

        <view class="section">
          <text class="section-title">最近流水</text>
          <view v-for="l in overview.recentLedgers || []" :key="l.ledgerId" class="row-item">
            <view class="row-main">
              <text>{{ ledgerLabel(l.entryType) }}</text>
              <text
                :class="{ credit: Number(l.amountCents) > 0, debit: Number(l.amountCents) < 0 }"
              >
                {{ formatSigned(l.amountCents) }}
              </text>
            </view>
            <text class="row-sub">{{ emptyDisplay(l.remark, 'text') }}</text>
            <text v-if="l.refId" class="row-sub">{{ ledgerRef(l) }}</text>
            <text v-if="l.balanceAfter != null" class="row-sub"
              >余额后 ¥{{ yuan(l.balanceAfter) }} · 冻结后 ¥{{ yuan(l.frozenAfter) }}</text
            >
            <text v-if="cfg.showLedgerTime && l.createdAt" class="row-sub">{{
              formatTime(l.createdAt)
            }}</text>
          </view>
          <empty-state
            v-if="!(overview.recentLedgers || []).length"
            compact
            icon="/static/menu/billing.png"
            title="暂无流水记录"
            :hint="cfg.ledgerEmptyHint"
          />
        </view>
      </template>

      <view v-if="loading" class="loading-inline">{{ UI_COPY.loading }}</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
// 🔴 组件里必须用 onPageShow：小程序端 uni 运行时把组件的 pageLifetimes.show 映射成
// onPageShow，组件级的 onShow 永远不会被调用（页面级 onShow 只对页面实例生效）。
import { onPageShow } from '@dcloudio/uni-app';
import { useAutoRefresh } from '@/composables/use-auto-refresh';
import { displayLabel } from '@aicabinet/shared-dict';
import {
  emptyDisplay,
  formatDateTimeMinute,
  formatDateTimeShort,
  yuanToCents
} from '@aicabinet/shared-uni/format';
import EmptyState from '@/components/empty-state.vue';
import { merchantApi, isMerchantLoggedIn, handleUnauthorized } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type {
  OpenApiLineWalletOverviewDto,
  OpenApiMerchantWalletOverviewDto
} from '@aicabinet/shared-types';
import { secureRandomToken } from '@/utils/secure-id';
import {
  buildWalletWithdrawBody,
  buildWalletWithdrawRequestNo,
  isTerminalWithdrawStatus,
  validateWalletWithdrawAmount,
  validateWalletWithdrawMerchant
} from '@/utils/money-ui-contracts';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

export type WalletPageRole = 'merchant' | 'line';

const props = defineProps<{ role: WalletPageRole }>();

type Overview = OpenApiMerchantWalletOverviewDto | OpenApiLineWalletOverviewDto;

const cfg = computed(() =>
  props.role === 'merchant'
    ? {
        title: '商户钱包',
        emptyIcon: '/static/menu/wallet.png',
        emptyTitle: '暂无商户钱包',
        emptyHint: '当前账号未绑定开通经营工具的商户，无法查看可提现余额',
        roleTag: '商户 · 可自主提现',
        tip: '分账入账后可提现；大额需运营审核，到账以银行/微信回执为准。',
        withdrawStatusDict: 'merchant_withdraw_status',
        requestNoPrefix: 'MW-',
        ledgerEmptyHint: '分账入账与提现变动会显示在这里',
        showLedgerTime: false,
        showRefType: true
      }
    : {
        title: '线长钱包',
        emptyIcon: '/static/menu/line-wallet.png',
        emptyTitle: '未绑定线长身份',
        emptyHint: '线长钱包仅对已绑定的线长成员开放。商户主体提现请使用「商户钱包」',
        roleTag: '线长 · 可自主提现',
        tip: '提交后由运营审核；到账以银行/微信回执为准。',
        withdrawStatusDict: 'line_withdraw_status',
        requestNoPrefix: 'MP-',
        ledgerEmptyHint: '佣金入账与提现变动会显示在这里',
        showLedgerTime: true,
        showRefType: false
      }
);

const loading = ref(false);
const submitting = ref(false);
const loadError = ref('');
const amountYuan = ref('');
const overview = ref<Overview | null>(null);
const maxWithdrawYuan = computed(() =>
  overview.value?.availableCents == null ? '' : yuan(overview.value.availableCents)
);

// H53：多商户绑定时的提现商户选择；单商户不渲染选择器、请求也不带 merchantId
const { me, refresh: refreshMerchantMe } = useMerchantMe();
const selectedMerchantId = ref('');
const boundMerchants = computed(() => (props.role === 'merchant' ? me.value?.merchants || [] : []));
const multiMerchant = computed(() => boundMerchants.value.length > 1);
const merchantOptions = computed(() =>
  boundMerchants.value.map((m) => ({
    merchantId: m.merchantId,
    label: `${emptyDisplay(m.merchantName, 'text')} · ${emptyDisplay(m.merchantId, 'text')}`
  }))
);
const selectedMerchantLabel = computed(() => {
  const hit = merchantOptions.value.find((m) => m.merchantId === selectedMerchantId.value);
  return hit?.label || '请选择';
});

function onMerchantPick(e: { detail: { value: string | number } }) {
  const idx = Number(e.detail.value);
  const next = merchantOptions.value[idx]?.merchantId || '';
  if (!next || next === selectedMerchantId.value) return;
  selectedMerchantId.value = next;
  overview.value = null;
  void load();
}

const displayName = computed(() => {
  const o = overview.value;
  if (!o) return '';
  if (props.role === 'merchant') {
    const m = o as OpenApiMerchantWalletOverviewDto;
    return `${emptyDisplay(m.merchantName, 'text')} · ${emptyDisplay(m.merchantId, 'text')}`;
  }
  const l = o as OpenApiLineWalletOverviewDto;
  return `${emptyDisplay(l.managerName, 'text')} · ${emptyDisplay(l.phone, 'text')}`;
});

function formatTime(t?: string) {
  return props.role === 'merchant' ? formatDateTimeShort(t, '') : formatDateTimeMinute(t, '暂无');
}

function yuan(cents?: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

function formatSigned(cents?: number) {
  const n = Number(cents) || 0;
  const abs = Math.abs(n) / 100;
  let sign = '';
  if (n > 0) sign = '+';
  else if (n < 0) sign = '-';
  return `${sign}¥${abs.toFixed(2)}`;
}

function withdrawStatus(status?: string) {
  return displayLabel(cfg.value.withdrawStatusDict, status, '未知状态');
}

function ledgerLabel(type?: string) {
  return displayLabel('wallet_ledger_type', type, emptyDisplay(type, 'text'));
}

function ledgerRef(l: { refId?: string; refType?: string }) {
  if (cfg.value.showRefType) {
    return `关联 ${l.refType || 'REF'} ${l.refId}`;
  }
  return `关联 ${l.refId}`;
}

async function load() {
  if (!isMerchantLoggedIn()) {
    handleUnauthorized();
    return;
  }
  loading.value = !overview.value;
  loadError.value = '';
  try {
    if (props.role === 'merchant') {
      // H53：确保 me.merchants 已就绪，用于多商户判断与默认选中（软失败不阻断展示）
      await refreshMerchantMe().catch(() => null);
      if (
        multiMerchant.value &&
        !merchantOptions.value.some((m) => m.merchantId === selectedMerchantId.value)
      ) {
        // 默认选中第一个（与后端未指定时的旧行为一致），用户可通过 picker 切换
        selectedMerchantId.value = merchantOptions.value[0]?.merchantId || '';
      }
      overview.value = await merchantApi.wallet(selectedMerchantId.value || undefined);
    } else {
      overview.value = await merchantApi.lineWallet();
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function submitWithdraw() {
  const amountCents = yuanToCents(amountYuan.value);
  const available = Number(overview.value?.availableCents ?? 0);
  const amountErr = validateWalletWithdrawAmount({ amountCents, availableCents: available });
  if (amountErr === 'INVALID_AMOUNT') {
    showError('请输入金额');
    return;
  }
  if (amountErr === 'EXCEEDS_AVAILABLE') {
    showError(`超出可提现余额（最多 ¥${yuan(available)}）`);
    return;
  }
  const merchantErr = validateWalletWithdrawMerchant({
    role: props.role,
    boundMerchantCount: boundMerchants.value.length,
    merchantId: selectedMerchantId.value
  });
  if (merchantErr === 'MERCHANT_REQUIRED') {
    showError('请先选择提现商户');
    return;
  }
  submitting.value = true;
  try {
    // H53：多商户绑定必须显式指定提现商户；单商户/未知绑定交由后端自动解析
    const body = buildWalletWithdrawBody({
      role: props.role,
      amountCents: amountCents!,
      requestNo: buildWalletWithdrawRequestNo({
        prefix: cfg.value.requestNoPrefix,
        nowMs: Date.now(),
        randomSuffix: secureRandomToken(5)
      }),
      merchantId: selectedMerchantId.value || undefined,
      boundMerchantCount: boundMerchants.value.length
    });
    if (props.role === 'merchant') {
      await merchantApi.walletWithdraw(body);
    } else {
      await merchantApi.lineWalletWithdraw(body);
    }
    showSuccess('已提交');
    amountYuan.value = '';
    await load();
  } catch (e) {
    showError(e instanceof Error ? e.message : '提交失败');
  } finally {
    submitting.value = false;
  }
}

onPageShow(load);

/**
 * 提现审核 / 打款由运营与支付通道异步处理：还有未到终态的提现单时每 10 秒静默跟进一次，
 * 全部到终态即停表。load() 在已有快照时不闪 loading，可安全重复调用。
 */
useAutoRefresh({
  intervalMs: 10_000,
  load,
  shouldContinue: () =>
    (overview.value?.recentWithdraws || []).some((w) => !isTerminalWithdrawStatus(w.status)),
  maxDurationMs: 300_000,
  canRefresh: () => !submitting.value && isMerchantLoggedIn()
});
</script>

<style scoped>
.summary-card,
.action-card,
.section {
  background: var(--card-bg, #fff);
  border-radius: var(--card-radius, 22rpx);
  padding: 28rpx;
  margin-bottom: 20rpx;
  border: 1rpx solid var(--card-border, var(--color-border));
}
.role-tag {
  font-size: var(--font-size-sm);
  color: var(--brand, #0f766e);
  font-weight: 600;
}
.name {
  display: block;
  margin-top: 8rpx;
  color: var(--text-muted, #64748b);
  font-size: var(--font-size-caption);
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
  font-size: var(--font-size-display);
  font-weight: 700;
  color: var(--text-primary, #0f172a);
}
.bal-sub {
  display: flex;
  gap: 24rpx;
  margin-top: 12rpx;
  color: var(--text-subtle, #94a3b8);
  font-size: var(--font-size-sm);
}
.amount-field {
  display: flex;
  align-items: center;
  width: 100%;
  height: 108rpx;
  min-height: 108rpx;
  margin-bottom: 16rpx;
  padding: 0 28rpx;
  box-sizing: border-box;
  background: var(--page-bg, #f8fafc);
  border: 1rpx solid var(--color-border);
  border-radius: var(--radius-panel);
}
.amount-input {
  flex: 1;
  width: 100%;
  height: 108rpx;
  min-height: 108rpx;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  font-size: var(--font-size-xl);
  color: var(--text-primary, #0f172a);
  line-height: normal;
}
.amount-field :deep(uni-input),
.amount-field :deep(.uni-input-wrapper),
.amount-field :deep(.uni-input-input),
.amount-field :deep(input) {
  width: 100% !important;
  height: 108rpx !important;
  min-height: 108rpx !important;
  line-height: normal !important;
  font-size: var(--font-size-xl) !important;
  color: var(--text-primary, #0f172a) !important;
}
.amount-ph {
  color: var(--text-subtle);
  font-size: var(--font-size-md);
  line-height: normal;
}
.withdraw-hint {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  margin: -6rpx 0 12rpx;
}
.picker {
  margin-bottom: 16rpx;
  padding: 20rpx 24rpx;
  background: var(--page-bg, #f8fafc);
  border: 1rpx solid var(--color-border);
  border-radius: var(--radius-panel);
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
}
.tip {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-sm);
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
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
}
.row-main {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
  font-size: var(--font-size-md);
}
.row-sub {
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #94a3b8);
  margin-top: 4rpx;
  display: block;
}
.row-sub.fail {
  color: var(--color-danger);
}
.status {
  color: var(--brand, #0f766e);
  font-weight: 500;
}
.credit {
  color: var(--brand);
  font-weight: 600;
}
.debit {
  color: var(--color-danger);
  font-weight: 600;
}
.banner-err {
  background: #f9eded;
  color: var(--color-danger);
  padding: 16rpx 20rpx;
  border-radius: var(--radius-control);
  margin-bottom: 16rpx;
  font-size: var(--font-size-body);
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
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
