<template>
  <view class="mine-page">
    <view class="profile-header">
      <view class="profile-orb orb-a" /><view class="profile-orb orb-b" />
      <view class="avatar">{{ avatarText }}</view>
      <view class="profile-info">
        <text class="hello">{{ authed ? displayName : '未登录' }}</text>
        <view v-if="authed" class="balance-row" @click="goRecharge">
          <text class="balance-label">可用</text>
          <text class="balance-number">{{ balanceYuan }}</text>
          <text class="balance-action">充值 ›</text>
        </view>
        <text v-if="authed && frozenYuan !== '¥0.00'" class="guest-hint"
          >冻结 {{ frozenYuan }} · 总余额 {{ totalBalanceYuan }}</text
        >
        <text v-else class="guest-hint">登录后可查看订单与余额</text>
        <view v-if="authed" class="tags">
          <text class="tag" :class="verified ? 'ok' : 'warn'">{{
            verified ? '已实名' : '待实名'
          }}</text>
          <text class="tag" :class="payReady ? 'ok' : 'warn'">{{
            payReady ? '可开门' : '待开通支付'
          }}</text>
        </view>
      </view>
    </view>

    <view v-if="!authed" class="setup-banner" @click="goLogin">
      <view class="setup-text">
        <text class="setup-title">微信授权登录</text>
        <text class="setup-desc">扫码开门前需完成授权</text>
      </view>
      <text class="setup-arrow">去登录 ›</text>
    </view>
    <view v-else-if="needsSetup" class="setup-banner" @click="goVerify">
      <view class="setup-text">
        <text class="setup-title">完成开门准备</text>
        <text class="setup-desc">{{ setupHint }}</text>
      </view>
      <text class="setup-arrow">去设置 ›</text>
    </view>

    <view class="quick-grid">
      <view class="quick-item" @click="goOrders">
        <text class="quick-icon">订</text>
        <text class="quick-label">订单</text>
      </view>
      <view class="quick-item" @click="goCoupons">
        <text class="quick-icon">券</text>
        <text class="quick-label">优惠券</text>
      </view>
      <view class="quick-item" @click="goMember">
        <text class="quick-icon">会</text>
        <text class="quick-label">会员</text>
      </view>
      <view class="quick-item" @click="goRecharge">
        <text class="quick-icon">充</text>
        <text class="quick-label">充值</text>
      </view>
    </view>

    <view class="menu-list">
      <view class="menu-cell" @click="goIndex">
        <text class="menu-icon">购</text>
        <view class="menu-text">
          <text class="menu-title">开门购物</text>
          <text class="menu-desc">扫码开门，取货即走</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goMarketing">
        <text class="menu-icon">热</text>
        <view class="menu-text">
          <text class="menu-title">热门活动</text>
          <text class="menu-desc">满减 · 新客礼 · 限时活动</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view v-if="authed" class="menu-cell" @click="toggleTransactions">
        <text class="menu-icon">余</text>
        <view class="menu-text">
          <text class="menu-title">余额明细</text>
          <text class="menu-desc">购物扣款、退款与充值记录</text>
        </view>
        <text class="menu-arrow">{{ showTransactions ? '∨' : '›' }}</text>
      </view>
      <view v-if="showTransactions" class="transaction-list">
        <view v-if="transactionsLoading" class="transaction-empty">加载中…</view>
        <view v-else-if="!transactions.length" class="transaction-empty">
          <text class="transaction-empty-title">暂无余额流水</text>
          <text class="transaction-empty-hint">购物扣款、退款与充值会出现在这里</text>
        </view>
        <view v-for="item in transactions" :key="item.transactionId" class="transaction-row">
          <view>
            <text class="transaction-title">{{ transactionLabel(item.businessType) }}</text>
            <text class="transaction-time">{{ formatTransactionTime(item.createdAt) }}</text>
          </view>
          <view class="transaction-amount" :class="{ income: item.amountCents > 0 }">
            {{ formatTransactionAmount(item.amountCents) }}
          </view>
        </view>
        <view
          v-if="transactionsHasMore"
          class="transaction-more"
          role="button"
          @click="loadTransactions(false)"
        >
          {{ transactionsLoading ? '加载中…' : `加载更多（已显示 ${transactions.length} 条）` }}
        </view>
      </view>
      <view class="menu-cell" @click="goAnnouncements">
        <text class="menu-icon">告</text>
        <view class="menu-text">
          <text class="menu-title">通知公告</text>
          <text class="menu-desc">平台维护、活动与规则变更</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goHelp">
        <text class="menu-icon">助</text>
        <view class="menu-text">
          <text class="menu-title">帮助与客服</text>
          <text class="menu-desc">常见问题、热线与账单申诉说明</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goReport">
        <text class="menu-icon">修</text>
        <view class="menu-text">
          <text class="menu-title">故障报修</text>
          <text class="menu-desc">打不开门、关不上门等</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goFeedback">
        <text class="menu-icon">馈</text>
        <view class="menu-text">
          <text class="menu-title">意见反馈</text>
          <text class="menu-desc">投诉、建议或表扬</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goPolicy('agreement')">
        <text class="menu-icon">约</text>
        <view class="menu-text">
          <text class="menu-title">用户协议</text>
          <text class="menu-desc">服务条款与使用规则</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goPolicy('privacy')">
        <text class="menu-icon">隐</text>
        <view class="menu-text">
          <text class="menu-title">隐私政策</text>
          <text class="menu-desc">信息收集、使用与保护</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goPolicy('refund')">
        <text class="menu-icon">退</text>
        <view class="menu-text">
          <text class="menu-title">退款规则</text>
          <text class="menu-desc">自助退款与人工申诉</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goPolicy('billing')">
        <text class="menu-icon">账</text>
        <view class="menu-text">
          <text class="menu-title">账单说明</text>
          <text class="menu-desc">订单构成与余额明细</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <!-- 开发联调：仅 DEV 构建可见，生产包不打包展示 -->
    <view v-if="devTools && authed" class="dev-section">
      <text class="dev-label">开发联调</text>
      <view
        v-if="wechatRechargeEnabled"
        class="menu-cell highlight"
        :class="{ disabled: rechargeLoading }"
        @click="onWeChatRecharge"
      >
        <text class="menu-icon">微</text>
        <view class="menu-text">
          <text class="menu-title">{{ wechatPayLive ? '微信支付充值' : '微信模拟充值' }}</text>
          <text class="menu-desc">{{
            wechatPayLive ? '调起真实微信支付' : 'mock 预下单即时到账 ¥20'
          }}</text>
        </view>
        <text class="menu-badge">{{ rechargeLoading ? '处理中' : '充 ¥20' }}</text>
      </view>
      <view
        v-if="alipayRechargeEnabled"
        class="menu-cell highlight"
        :class="{ disabled: rechargeLoading }"
        @click="onAlipayRecharge"
      >
        <text class="menu-icon">支</text>
        <view class="menu-text">
          <text class="menu-title">{{
            mockRechargeEnabled ? '支付宝模拟充值' : '支付宝沙箱充值'
          }}</text>
          <text class="menu-desc">{{
            mockRechargeEnabled ? 'mock 预下单即时到账 ¥20（无需进件）' : '跳转沙箱收银台充 ¥20'
          }}</text>
        </view>
        <text class="menu-badge">{{ rechargeLoading ? '处理中' : '充 ¥20' }}</text>
      </view>
      <view
        v-if="mockRechargeEnabled"
        class="menu-cell highlight"
        :class="{ disabled: rechargeLoading }"
        @click="onMockRecharge"
      >
        <text class="menu-icon">模</text>
        <view class="menu-text">
          <text class="menu-title">模拟充值</text>
          <text class="menu-desc">本地发放余额，不真实扣款</text>
        </view>
        <text class="menu-badge">{{ rechargeLoading ? '处理中' : '充 ¥20' }}</text>
      </view>
      <view class="menu-cell" @click="goLogin">
        <text class="menu-icon">号</text>
        <view class="menu-text">
          <text class="menu-title">手机号验证（兜底）</text>
          <text class="menu-desc">短信 / 密码登录</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view v-if="authed" class="menu-list logout-wrap">
      <view class="menu-cell danger-cell" @click="onLogout">
        <text class="menu-icon">出</text>
        <view class="menu-text">
          <text class="menu-title danger">退出登录</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import type { AccountDto, BalanceTransactionDto } from '@aicabinet/shared-types';
import {
  clearConsumerSession,
  consumerApi,
  ensureConsumerAuth,
  getConsumerToken
} from '@/utils/consumer-api';
import { formatDateTimeShort, fmtMoney } from '@aicabinet/shared-uni/format';
import {
  availableCents,
  isPayReady,
  payReadyHint,
  resolveClientPreauthCents
} from '@/utils/account';
import { resumePendingRechargeIfAny, runAlipayRecharge, runWeChatRecharge } from '@/utils/recharge';
import {
  resolveMockEnabled,
  resolveSandboxRecharge,
  resolveWechatRechargeVisible,
  showDevTools
} from '@/utils/runtime-flags';

const devTools = showDevTools();
const balanceYuan = ref('--');
const authed = ref(false);
const account = ref<AccountDto | null>(null);
const showTransactions = ref(false);
const transactionsLoading = ref(false);
const transactions = ref<BalanceTransactionDto[]>([]);
const transactionsPage = ref(0);
const transactionsHasMore = ref(false);
const TRANSACTION_PAGE_SIZE = 10;
const rechargeLoading = ref(false);
const mockRechargeEnabled = ref(false);
const alipayRechargeEnabled = ref(false);
const wechatRechargeEnabled = ref(false);
const wechatPayLive = ref(false);
const configPreauthCents = ref<number | null>(null);

const preauthCents = computed(() =>
  resolveClientPreauthCents({ configPreauthCents: configPreauthCents.value })
);
const frozenYuan = computed(() => fmtMoney(Math.max(0, account.value?.frozenCents || 0)));
const totalBalanceYuan = computed(() => fmtMoney(account.value?.balanceCents || 0));
const verified = computed(() => !!account.value?.verified);
const payReady = computed(() => isPayReady(account.value, null, preauthCents.value));
const needsSetup = computed(() => !verified.value || !payReady.value);
const displayName = computed(() => account.value?.realName || '我的账户');
const avatarText = computed(() => account.value?.realName?.slice(0, 1) || '我');
const setupHint = computed(() => {
  if (!verified.value) return '完成实名并开通免密支付后即可开门';
  return payReadyHint(account.value, null, preauthCents.value);
});

function syncBalanceDisplay(acc: AccountDto | null) {
  if (!acc) {
    balanceYuan.value = '--';
    return;
  }
  balanceYuan.value = fmtMoney(availableCents(acc));
}

onShow(async () => {
  await ensureConsumerAuth();
  authed.value = !!getConsumerToken();
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    mockRechargeEnabled.value = resolveMockEnabled(cfg?.mockEnabled);
    alipayRechargeEnabled.value = resolveSandboxRecharge(cfg?.alipayRechargeEnabled);
    wechatPayLive.value = cfg?.wechatPayLive === 'true';
    wechatRechargeEnabled.value = resolveWechatRechargeVisible({
      wechatRechargeEnabled: cfg?.wechatRechargeEnabled,
      wechatPayLive: cfg?.wechatPayLive
    });
    const p = Number(cfg?.preauthCents);
    configPreauthCents.value = Number.isFinite(p) && p > 0 ? p : null;
  } catch {
    mockRechargeEnabled.value = false;
    alipayRechargeEnabled.value = false;
    wechatRechargeEnabled.value = false;
    wechatPayLive.value = false;
  }
  if (!authed.value) {
    syncBalanceDisplay(null);
    account.value = null;
    return;
  }
  try {
    account.value = await consumerApi.account();
    syncBalanceDisplay(account.value);
  } catch (e) {
    syncBalanceDisplay(null);
    account.value = null;
    authed.value = !!getConsumerToken();
    if (!authed.value) {
      uni.showToast({ title: '登录已失效，请重新登录', icon: 'none' });
      return;
    }
    uni.showToast({ title: e instanceof Error ? e.message : '账户加载失败', icon: 'none' });
  }
  const resumed = await resumePendingRechargeIfAny();
  if (resumed) {
    try {
      account.value = await consumerApi.account();
      syncBalanceDisplay(account.value);
    } catch {
      /* keep previous snapshot */
    }
  }
  if (!authed.value) return;
  if (showTransactions.value) loadTransactions();
});

function loadTransactions(reset = true): Promise<void> {
  if (transactionsLoading.value) return Promise.resolve();
  if (!reset && !transactionsHasMore.value) return Promise.resolve();
  transactionsLoading.value = true;
  const page = reset ? 0 : transactionsPage.value + 1;
  return consumerApi
    .balanceTransactions(page, TRANSACTION_PAGE_SIZE)
    .then((data) => {
      // 注意：回调参数命名为 data，避免遮蔽外层请求页码 page
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
      transactionsPage.value = page;
      transactionsHasMore.value =
        items.length >= TRANSACTION_PAGE_SIZE &&
        transactions.value.length < Math.max(total, transactions.value.length);
    })
    .catch(() => {
      /* ignore */
    })
    .finally(() => {
      transactionsLoading.value = false;
    });
}

function toggleTransactions() {
  showTransactions.value = !showTransactions.value;
  if (showTransactions.value) loadTransactions();
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
  return `${cents > 0 ? '+' : cents < 0 ? '-' : ''}${signed}`;
}

async function refreshAccount() {
  account.value = await consumerApi.account();
  syncBalanceDisplay(account.value);
  if (showTransactions.value) {
    await loadTransactions(true);
  }
}

async function onWeChatRecharge() {
  if (rechargeLoading.value) return;
  const confirmed = await new Promise<boolean>((resolve) =>
    uni.showModal({
      title: wechatPayLive.value ? '微信支付充值' : '微信模拟充值',
      content: wechatPayLive.value
        ? '将调起微信支付充值 ¥20.00。'
        : '将通过微信 mock 通道充值 ¥20.00 余额，不会真实扣款。',
      confirmText: '确认',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  );
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = `mine-wechat-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    await runWeChatRecharge(2000, key);
    await refreshAccount();
    uni.showToast({ title: '充值成功', icon: 'success' });
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '充值失败', icon: 'none' });
  } finally {
    rechargeLoading.value = false;
  }
}

async function onAlipayRecharge() {
  if (rechargeLoading.value) return;
  const isMock = mockRechargeEnabled.value;
  const confirmed = await new Promise<boolean>((resolve) =>
    uni.showModal({
      title: isMock ? '支付宝模拟充值' : '支付宝沙箱充值',
      content: isMock
        ? '将模拟支付宝充值 ¥20.00 到余额（无需进件，不会真实扣款）。'
        : '将跳转支付宝沙箱支付页充值 ¥20.00 余额。',
      confirmText: isMock ? '确认到账' : '去支付',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  );
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = `alipay-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const { mode } = await runAlipayRecharge(2000, key);
    if (mode === 'live') {
      uni.showToast({ title: '请在支付宝完成支付', icon: 'none' });
      return;
    }
    await refreshAccount();
    uni.showToast({ title: '支付宝模拟充值成功', icon: 'success' });
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '充值失败', icon: 'none' });
  } finally {
    rechargeLoading.value = false;
  }
}

async function onMockRecharge() {
  if (rechargeLoading.value) return;
  const confirmed = await new Promise<boolean>((resolve) =>
    uni.showModal({
      title: '确认模拟充值',
      content: '将向当前账户发放 ¥20.00 余额（仅开发联调，不会真实扣款）。',
      confirmText: '确认发放',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  );
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = `mock-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const prepay = await consumerApi.createMockRecharge(2000, key);
    await consumerApi.confirmMockRecharge(prepay.orderId);
    await refreshAccount();
    uni.showToast({ title: '余额已到账', icon: 'success' });
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '充值失败', icon: 'none' });
  } finally {
    rechargeLoading.value = false;
  }
}

function goVerify() {
  uni.navigateTo({ url: '/pages/verify/verify' });
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/mine/mine') });
}

function goIndex() {
  uni.switchTab({ url: '/pages/index/index' });
}

function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}

function goCoupons() {
  uni.navigateTo({ url: '/pages/coupons/coupons' });
}

function goMember() {
  uni.navigateTo({ url: '/pages/member/index' });
}

function goMarketing() {
  uni.navigateTo({ url: '/pages/marketing/index' });
}

function goRecharge() {
  uni.navigateTo({ url: '/pages/recharge/recharge' });
}

function goReport() {
  const id = uni.getStorageSync('last_device_id') || '';
  uni.navigateTo({
    url: id ? `/pages/report/report?deviceId=${encodeURIComponent(id)}` : '/pages/report/report'
  });
}

function goAnnouncements() {
  uni.navigateTo({ url: '/pages/announcements/announcements' });
}

function goHelp() {
  uni.navigateTo({ url: '/pages/help/help' });
}

function goFeedback() {
  const id = uni.getStorageSync('last_device_id') || '';
  uni.navigateTo({
    url: id
      ? `/pages/feedback/feedback?deviceId=${encodeURIComponent(id)}`
      : '/pages/feedback/feedback'
  });
}

function goPolicy(type: 'agreement' | 'privacy' | 'refund' | 'billing') {
  uni.navigateTo({ url: `/pages/policy/detail?type=${type}` });
}

function onLogout() {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前账户吗？',
    confirmText: '退出',
    success(res) {
      if (!res.confirm) return;
      clearConsumerSession();
      authed.value = false;
      account.value = null;
      balanceYuan.value = '--';
      transactions.value = [];
      showTransactions.value = false;
      uni.showToast({ title: '已退出', icon: 'none' });
    }
  });
}
</script>

<style scoped>
.mine-page {
  min-height: 100%;
  box-sizing: border-box;
  padding-bottom: calc(160rpx + env(safe-area-inset-bottom));
  background: linear-gradient(180deg, #e9fbf3 0, #f5f7f8 390rpx, #f5f7f8 100%);
}
.profile-header {
  position: relative;
  overflow: hidden;
  margin: 20rpx 24rpx 0;
  padding: 40rpx 32rpx;
  border-radius: 30rpx;
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: linear-gradient(140deg, #064e3b 0%, #059669 56%, #14b8a6 100%);
  box-shadow: 0 20rpx 46rpx rgba(5, 150, 105, 0.23);
  color: #fff;
}
.profile-orb {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.09);
}
.orb-a {
  width: 230rpx;
  height: 230rpx;
  right: -80rpx;
  top: -110rpx;
}
.orb-b {
  width: 120rpx;
  height: 120rpx;
  right: 120rpx;
  bottom: -80rpx;
}
.avatar {
  position: relative;
  width: 112rpx;
  height: 112rpx;
  border-radius: 50%;
  border: 2rpx solid rgba(255, 255, 255, 0.35);
  background: rgba(255, 255, 255, 0.18);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48rpx;
  box-shadow: 0 10rpx 25rpx rgba(0, 0, 0, 0.1);
}
.profile-info {
  position: relative;
  flex: 1;
  min-width: 0;
}
.hello {
  font-size: 36rpx;
  font-weight: 700;
  display: block;
}
.guest-hint {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  opacity: 0.88;
}
.balance-row {
  display: flex;
  align-items: baseline;
  gap: 13rpx;
  margin-top: 9rpx;
}
.balance-label {
  font-size: 22rpx;
  opacity: 0.72;
}
.balance-number {
  font-size: 38rpx;
  font-weight: 800;
  letter-spacing: -1rpx;
}
.balance-action {
  margin-left: auto;
  font-size: 22rpx;
  opacity: 0.85;
}
.tags {
  display: flex;
  gap: 12rpx;
  margin-top: 15rpx;
  flex-wrap: wrap;
}
.tag {
  font-size: 22rpx;
  padding: 6rpx 15rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.2);
}
.tag.ok {
  background: rgba(255, 255, 255, 0.35);
}
.tag.warn {
  background: #fff3cd;
  color: #856404;
}
.setup-banner {
  margin: 20rpx 24rpx 0;
  padding: 24rpx 26rpx;
  border-radius: 21rpx;
  background: linear-gradient(135deg, #fff7df, #fffbeb);
  box-shadow: 0 8rpx 22rpx rgba(217, 119, 6, 0.08);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.setup-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #d48806;
  display: block;
}
.setup-desc {
  font-size: 24rpx;
  color: #ad6800;
  display: block;
  margin-top: 4rpx;
}
.setup-arrow {
  color: #d48806;
  font-size: 28rpx;
  font-weight: 500;
  white-space: nowrap;
  margin-left: 16rpx;
}

.quick-grid {
  margin: 22rpx 24rpx 0;
  padding: 28rpx 12rpx;
  background: #fff;
  border-radius: 22rpx;
  border: 1rpx solid #edf1ef;
  box-shadow: 0 8rpx 25rpx rgba(15, 23, 42, 0.05);
  display: flex;
}
.quick-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
}
.quick-icon {
  width: 72rpx;
  height: 72rpx;
  border-radius: 20rpx;
  background: #f0fdf4;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28rpx;
  font-weight: 700;
  color: #059669;
}
.quick-label {
  font-size: 24rpx;
  color: #223029;
  font-weight: 500;
}

.menu-list {
  margin: 22rpx 24rpx 0;
}
.logout-wrap {
  margin-top: 12rpx;
  padding-bottom: 24rpx;
}
.menu-cell {
  background: #fff;
  margin-bottom: 14rpx;
  padding: 25rpx 22rpx;
  border: 1rpx solid #edf1ef;
  border-radius: 22rpx;
  box-shadow: 0 8rpx 25rpx rgba(15, 23, 42, 0.05);
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.menu-cell.highlight {
  border: 1rpx solid rgba(5, 150, 105, 0.32);
  background: linear-gradient(90deg, #fff, #f0fdf7);
}
.menu-cell.disabled {
  opacity: 0.6;
  pointer-events: none;
}
.menu-icon {
  display: flex;
  width: 72rpx;
  height: 72rpx;
  align-items: center;
  justify-content: center;
  border-radius: 19rpx;
  background: #f0fdf4;
  font-size: 28rpx;
  font-weight: 700;
  color: #059669;
}
.menu-text {
  flex: 1;
  min-width: 0;
}
.menu-title {
  font-size: 28rpx;
  font-weight: 650;
  color: #223029;
  display: block;
}
.menu-desc {
  margin-top: 5rpx;
  color: #849087;
  font-size: 22rpx;
  display: block;
}
.menu-badge {
  font-size: 22rpx;
  color: #fa5151;
  background: #fff1f0;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
}
.menu-arrow {
  color: #ccc;
  font-size: 36rpx;
}
.danger {
  color: #fa5151;
}
.danger-cell {
  background: #fffafa;
}
.danger-cell .menu-icon {
  background: #fff1f0;
  color: #ef4444;
}
.transaction-list {
  background: #fff;
  border-radius: 22rpx;
  margin-bottom: 14rpx;
  padding: 0 24rpx;
  border: 1rpx solid #edf1ef;
  box-shadow: 0 8rpx 25rpx rgba(15, 23, 42, 0.045);
}
.transaction-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #eee;
}
.transaction-row:last-child {
  border-bottom: 0;
}
.transaction-title {
  display: block;
  font-size: 28rpx;
  color: #191919;
}
.transaction-time {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #999;
}
.transaction-amount {
  font-size: 30rpx;
  font-weight: 600;
  color: #191919;
}
.transaction-amount.income {
  color: #07c160;
}
.transaction-more {
  text-align: center;
  padding: 20rpx 0 6rpx;
  font-size: 24rpx;
  color: var(--brand, #059669);
  font-weight: 600;
}
.transaction-empty {
  padding: 28rpx;
  text-align: center;
  color: #849087;
  font-size: 25rpx;
}
.transaction-empty-title {
  display: block;
  font-size: 26rpx;
  font-weight: 650;
  color: #64748b;
}
.transaction-empty-hint {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #94a3b8;
}

.dev-section {
  margin: 8rpx 24rpx 0;
  padding: 16rpx 0 0;
}
.dev-label {
  display: block;
  margin: 0 8rpx 12rpx;
  font-size: 22rpx;
  color: #94a3b8;
  letter-spacing: 1rpx;
}
</style>
