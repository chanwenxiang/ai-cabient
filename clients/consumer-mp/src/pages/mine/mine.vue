<template>
  <view class="mine-page">
    <view class="profile-header" :style="headerPadStyle">
      <view class="profile-main">
        <view class="profile-orb orb-a" /><view class="profile-orb orb-b" />
        <view class="avatar">{{ avatarText }}</view>
        <view class="profile-mid">
          <text class="hello">{{ authed ? displayName : '未登录' }}</text>
          <view v-if="authed" class="tags">
            <text class="tag" :class="verified ? 'ok' : 'warn'">{{
              verified ? '已实名' : '待实名'
            }}</text>
            <text class="tag" :class="payReady ? 'ok' : 'warn'">{{
              payReady ? '可开门' : '待开通支付'
            }}</text>
          </view>
          <text v-else class="guest-hint">登录后可查看订单与余额</text>
        </view>
        <view v-if="authed" class="balance-side">
          <text class="balance-label">可用余额</text>
          <text class="balance-number">{{ balanceYuan }}</text>
          <text v-if="frozenYuan !== '¥0.00'" class="balance-meta">冻结 {{ frozenYuan }}</text>
          <text role="button" class="balance-action" @click="goRecharge">充值</text>
        </view>
      </view>
    </view>

    <view v-if="!authed" role="button" class="setup-banner" @click="goLogin">
      <view class="setup-text">
        <text class="setup-title">微信授权登录</text>
        <text class="setup-desc">扫码开门前需完成授权</text>
      </view>
      <view class="setup-arrow">
        <text>去登录</text>
        <view class="app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>
    <view v-else-if="needsSetup" role="button" class="setup-banner" @click="goVerify">
      <view class="setup-text">
        <text class="setup-title">完成开门准备</text>
        <text class="setup-desc">{{ setupHint }}</text>
      </view>
      <view class="setup-arrow">
        <text>去设置</text>
        <view class="app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <view v-if="authed" class="pay-pref-card">
      <text class="pay-pref-title">优先支付方式</text>
      <text class="pay-pref-hint">关门结算时优先使用；选余额可先花掉账户余额</text>
      <view class="pay-pref-chips">
        <text
          role="button"
          class="pay-pref-chip"
          :class="{ on: payPreferred === 'BALANCE', busy: payPrefBusy }"
          @click="onSetPayPreferred('BALANCE')"
          >余额</text
        >
        <text
          role="button"
          class="pay-pref-chip"
          :class="{
            on: payPreferred === 'WECHAT',
            disabled: !account?.payscoreEnabled,
            busy: payPrefBusy
          }"
          @click="onSetPayPreferred('WECHAT')"
          >微信免密</text
        >
        <text
          role="button"
          class="pay-pref-chip"
          :class="{
            on: payPreferred === 'ALIPAY',
            disabled: !account?.alipayAgreementEnabled,
            busy: payPrefBusy
          }"
          @click="onSetPayPreferred('ALIPAY')"
          >支付宝免密</text
        >
      </view>
    </view>

    <view class="quick-grid">
      <view role="button" class="quick-item" aria-label="订单" @click="goOrders">
        <image class="quick-icon" :src="menuIcon('orders')" mode="aspectFit" aria-hidden="true" />
        <text class="quick-label">订单</text>
      </view>
      <view role="button" class="quick-item" aria-label="优惠券" @click="goCoupons">
        <image class="quick-icon" :src="menuIcon('coupons')" mode="aspectFit" aria-hidden="true" />
        <text class="quick-label">优惠券</text>
      </view>
      <view role="button" class="quick-item" aria-label="会员" @click="goMember">
        <image class="quick-icon" :src="menuIcon('member')" mode="aspectFit" aria-hidden="true" />
        <text class="quick-label">会员</text>
      </view>
      <view role="button" class="quick-item" aria-label="充值" @click="goRecharge">
        <image class="quick-icon" :src="menuIcon('recharge')" mode="aspectFit" aria-hidden="true" />
        <text class="quick-label">充值</text>
      </view>
    </view>

    <view class="menu-list">
      <view role="button" class="menu-cell" @click="goIndex">
        <image class="menu-icon" :src="menuIcon('shopping')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">开门购物</text>
          <text class="menu-desc">扫码开门，取货即走</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goMarketing">
        <image class="menu-icon" :src="menuIcon('hot')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">热门活动</text>
          <text class="menu-desc">满减 · 新客礼 · 限时活动</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goPoints">
        <image class="menu-icon" :src="menuIcon('member')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">积分中心</text>
          <text class="menu-desc">消费返积分 · 积分兑优惠券</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goMessages">
        <image class="menu-icon" :src="menuIcon('notice')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">消息中心</text>
          <text class="menu-desc">订单支付 · 充值到账 · 售后提醒</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view v-if="authed" role="button" class="menu-cell" @click="goBalance">
        <image class="menu-icon" :src="menuIcon('balance')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">余额明细</text>
          <text class="menu-desc">购物扣款、退款与充值记录</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goAnnouncements">
        <image class="menu-icon" :src="menuIcon('billing')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">通知公告</text>
          <text class="menu-desc">平台维护、活动与规则变更</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goHelp">
        <image class="menu-icon" :src="menuIcon('help')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">帮助与客服</text>
          <text class="menu-desc">常见问题、热线与账单申诉说明</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goReport">
        <image class="menu-icon" :src="menuIcon('repair')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">故障报修</text>
          <text class="menu-desc">打不开门、关不上门等</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goFeedback">
        <image class="menu-icon" :src="menuIcon('feedback')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">意见反馈</text>
          <text class="menu-desc">投诉、建议或表扬</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goPolicy('agreement')">
        <image class="menu-icon" :src="menuIcon('agreement')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">用户协议</text>
          <text class="menu-desc">服务条款与使用规则</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goPolicy('privacy')">
        <image class="menu-icon" :src="menuIcon('privacy')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">隐私政策</text>
          <text class="menu-desc">信息收集、使用与保护</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goPolicy('refund')">
        <image class="menu-icon" :src="menuIcon('refund')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">退款规则</text>
          <text class="menu-desc">自助退款与人工申诉</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view role="button" class="menu-cell" @click="goPolicy('billing')">
        <image class="menu-icon" :src="menuIcon('billing')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">账单说明</text>
          <text class="menu-desc">订单构成与余额明细</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <!-- 体验充值：仅 DEV 构建可见，生产包不打包展示 -->
    <view v-if="devTools && authed" class="dev-section">
      <text class="dev-label">体验充值</text>
      <view
        v-if="wechatRechargeEnabled"
        role="button"
        class="menu-cell highlight"
        :class="{ disabled: rechargeLoading }"
        @click="onWeChatRecharge"
      >
        <image class="menu-icon" :src="menuIcon('wechat')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">{{ wechatPayLive ? '微信支付充值' : '微信充值' }}</text>
          <text class="menu-desc">{{ wechatPayLive ? '调起微信支付' : '体验到账 ¥20' }}</text>
        </view>
        <text class="menu-badge">{{ rechargeLoading ? '处理中' : '充 ¥20' }}</text>
      </view>
      <view
        v-if="alipayRechargeEnabled"
        role="button"
        class="menu-cell highlight"
        :class="{ disabled: rechargeLoading }"
        @click="onAlipayRecharge"
      >
        <image class="menu-icon" :src="menuIcon('alipay')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">支付宝充值</text>
          <text class="menu-desc">{{
            mockRechargeEnabled ? '体验到账 ¥20' : '跳转收银台充 ¥20'
          }}</text>
        </view>
        <text class="menu-badge">{{ rechargeLoading ? '处理中' : '充 ¥20' }}</text>
      </view>
      <view
        v-if="mockRechargeEnabled"
        role="button"
        class="menu-cell highlight"
        :class="{ disabled: rechargeLoading }"
        @click="onMockRecharge"
      >
        <image class="menu-icon" :src="menuIcon('mock')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">余额充值</text>
          <text class="menu-desc">体验到账 ¥20，不真实扣款</text>
        </view>
        <text class="menu-badge">{{ rechargeLoading ? '处理中' : '充 ¥20' }}</text>
      </view>
      <view role="button" class="menu-cell" @click="goLogin">
        <image class="menu-icon" :src="menuIcon('phone')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">手机号验证（兜底）</text>
          <text class="menu-desc">短信 / 密码登录</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <view v-if="authed" class="menu-list logout-wrap">
      <view role="button" class="menu-cell danger-cell" @click="onLogout">
        <image class="menu-icon" :src="menuIcon('logout')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title danger">退出登录</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { showError, showSuccess, showConfirm } from '@/utils/notify';
import { computed, ref } from 'vue';
import type { AccountDto } from '@aicabinet/shared-types';
import { getBelowCapsulePadPx } from '@aicabinet/shared-uni/status-bar';
import {
  consumerApi,
  ensureConsumerAuth,
  getConsumerToken,
  logoutConsumerSession
} from '@/utils/consumer-api';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import { menuIcon } from '@/utils/menu-icon';
import {
  availableCents,
  isPayReady,
  payReadyHint,
  resolveClientPreauthCents
} from '@/utils/account';
import { resumePendingRechargeIfAny, runAlipayRecharge, runWeChatRecharge } from '@/utils/recharge';
import { secureRandomToken } from '@/utils/secure-id';
import {
  resolveMockEnabled,
  resolveSandboxRecharge,
  resolveWechatRechargeVisible,
  showDevTools
} from '@/utils/runtime-flags';

/** 内容从胶囊下方开始，右侧余额/充值才不会顶到胶囊 */
const headerPadStyle = {
  paddingTop: getBelowCapsulePadPx(8) + 'px'
};

const devTools = showDevTools();
const balanceYuan = ref('--');
const authed = ref(false);
const account = ref<AccountDto | null>(null);
const rechargeLoading = ref(false);
const payPrefBusy = ref(false);
const mockRechargeEnabled = ref(false);
const alipayRechargeEnabled = ref(false);
const wechatRechargeEnabled = ref(false);
const wechatPayLive = ref(false);
const configPreauthCents = ref<number | null>(null);

const preauthCents = computed(() =>
  resolveClientPreauthCents({ configPreauthCents: configPreauthCents.value })
);
const frozenYuan = computed(() => fmtMoney(Math.max(0, account.value?.frozenCents || 0)));
const verified = computed(() => !!account.value?.verified);
const payReady = computed(() => isPayReady(account.value, null, preauthCents.value));
const needsSetup = computed(() => !verified.value || !payReady.value);
const displayName = computed(() => account.value?.realName || '我的账户');
const avatarText = computed(() => account.value?.realName?.slice(0, 1) || '我');
const payPreferred = computed(() => {
  const c = String(account.value?.payPreferredChannel || 'BALANCE').toUpperCase();
  if (c === 'WECHAT' || c === 'ALIPAY' || c === 'BALANCE') return c;
  return 'BALANCE';
});
const setupHint = computed(() => {
  if (!verified.value) return '完成实名并开通免密支付后即可开门';
  return payReadyHint(account.value, null, preauthCents.value);
});

async function onSetPayPreferred(channel: 'BALANCE' | 'WECHAT' | 'ALIPAY') {
  if (!authed.value || payPrefBusy.value) return;
  if (channel === payPreferred.value) return;
  if (channel === 'WECHAT' && !account.value?.payscoreEnabled) {
    showError('请先开通微信支付分');
    return;
  }
  if (channel === 'ALIPAY' && !account.value?.alipayAgreementEnabled) {
    showError('请先开通支付宝免密');
    return;
  }
  payPrefBusy.value = true;
  try {
    account.value = await consumerApi.setPayPreferred(channel);
    syncBalanceDisplay(account.value);
    const payLabels: Record<string, string> = {
      BALANCE: '余额',
      WECHAT: '微信免密'
    };
    const label = payLabels[channel] ?? '支付宝免密';
    showSuccess(`已优先${label}`);
  } catch (e) {
    showError(e instanceof Error ? e.message : '设置失败');
  } finally {
    payPrefBusy.value = false;
  }
}

function syncBalanceDisplay(acc: AccountDto | null) {
  if (!acc) {
    balanceYuan.value = '--';
    return;
  }
  balanceYuan.value = fmtMoney(availableCents(acc));
}

onShow(async () => {
  uni.showTabBar({ animation: false });
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
      showError('登录已失效，请重新登录');
      return;
    }
    showError(e instanceof Error ? e.message : '账户加载失败');
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
});

async function refreshAccount() {
  account.value = await consumerApi.account();
  syncBalanceDisplay(account.value);
}

async function onWeChatRecharge() {
  if (rechargeLoading.value) return;
  const confirmed = await showConfirm({
    title: wechatPayLive.value ? '微信支付充值' : '微信充值',
    content: wechatPayLive.value
      ? '将调起微信支付充值 ¥20.00。'
      : '将充值 ¥20.00 到余额（体验到账，不会真实扣款）。',
    confirmText: '确认'
  });
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = `mine-wechat-${Date.now()}-${secureRandomToken(6)}`;
    await runWeChatRecharge(2000, key);
    await refreshAccount();
    showSuccess('充值成功');
  } catch (error) {
    showError(error instanceof Error ? error.message : '充值失败');
  } finally {
    rechargeLoading.value = false;
  }
}

async function onAlipayRecharge() {
  if (rechargeLoading.value) return;
  const isMock = mockRechargeEnabled.value;
  const confirmed = await showConfirm({
    title: '支付宝充值',
    content: isMock
      ? '将充值 ¥20.00 到余额（体验到账，不会真实扣款）。'
      : '将跳转支付宝支付页充值 ¥20.00。',
    confirmText: isMock ? '确认到账' : '去支付'
  });
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = `alipay-recharge-${Date.now()}-${secureRandomToken(6)}`;
    const { mode } = await runAlipayRecharge(2000, key);
    if (mode === 'live') {
      showError('请在支付宝完成支付');
      return;
    }
    await refreshAccount();
    showSuccess('充值成功');
  } catch (error) {
    showError(error instanceof Error ? error.message : '充值失败');
  } finally {
    rechargeLoading.value = false;
  }
}

async function onMockRecharge() {
  if (rechargeLoading.value) return;
  const confirmed = await showConfirm({
    title: '确认充值',
    content: '将向当前账户发放 ¥20.00 余额（体验到账，不会真实扣款）。',
    confirmText: '确认发放'
  });
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = `mock-recharge-${Date.now()}-${secureRandomToken(6)}`;
    const prepay = await consumerApi.createMockRecharge(2000, key);
    await consumerApi.confirmMockRecharge(prepay.orderId);
    await refreshAccount();
    showSuccess('余额已到账');
  } catch (error) {
    showError(error instanceof Error ? error.message : '充值失败');
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

function goPoints() {
  uni.navigateTo({ url: '/pages/points/points' });
}

function goBalance() {
  uni.navigateTo({ url: '/pages/balance/balance' });
}

function goMessages() {
  uni.navigateTo({ url: '/pages/messages/messages' });
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

async function onLogout() {
  const confirmed = await showConfirm({
    title: '退出登录',
    content: '确定退出当前账户吗？',
    confirmText: '退出'
  });
  if (!confirmed) return;
  await logoutConsumerSession();
  authed.value = false;
  account.value = null;
  balanceYuan.value = '--';
  showError('已退出');
}
</script>

<style scoped>
.mine-page {
  min-height: 100%;
  box-sizing: border-box;
  /* 原生 tabBar 已在页面外占位，只需少量底距 */
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  background: var(--card-bg, #ffffff);
}
.profile-header {
  position: relative;
  overflow: hidden;
  margin: 0;
  padding: 0;
  border-radius: 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
  box-sizing: border-box;
  background: linear-gradient(145deg, var(--brand-deep, #134e4a) 0%, var(--brand, #0f766e) 100%);
  box-shadow: none;
  color: var(--white);
}
.profile-main {
  position: relative;
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  align-items: center;
  gap: 20rpx;
  z-index: 1;
  padding: 12rpx 28rpx 36rpx;
  box-sizing: border-box;
  width: 100%;
}
.profile-orb {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.09);
  pointer-events: none;
  z-index: 0;
}
.orb-a {
  width: 180rpx;
  height: 180rpx;
  right: -60rpx;
  top: -90rpx;
}
.orb-b {
  width: 100rpx;
  height: 100rpx;
  right: 100rpx;
  bottom: -60rpx;
}
.avatar {
  position: relative;
  width: 128rpx;
  height: 128rpx;
  border-radius: 50%;
  border: 3rpx solid rgba(255, 255, 255, 0.4);
  background: rgba(255, 255, 255, 0.18);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-display);
  font-weight: 700;
  flex-shrink: 0;
  z-index: 1;
}
.profile-mid {
  position: relative;
  flex: 1 1 auto;
  min-width: 0;
  z-index: 1;
  overflow: hidden;
}
.hello {
  display: block;
  font-size: var(--font-size-xl);
  font-weight: 700;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.guest-hint {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-sm);
  opacity: 0.82;
  line-height: 1.35;
}
.balance-side {
  position: relative;
  flex: 0 0 auto;
  margin-left: auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: center;
  z-index: 1;
  min-width: 180rpx;
}
.balance-label {
  font-size: var(--font-size-sm);
  opacity: 0.72;
  letter-spacing: 0.5rpx;
  text-align: right;
}
.balance-number {
  margin-top: 4rpx;
  font-size: var(--font-size-h2);
  font-weight: 800;
  letter-spacing: -1rpx;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
  text-align: right;
  white-space: nowrap;
}
.balance-meta {
  margin-top: 4rpx;
  font-size: var(--font-size-xs);
  opacity: 0.72;
  text-align: right;
}
.balance-action {
  margin-top: 12rpx;
  padding: 8rpx 22rpx;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
  font-weight: 600;
  color: var(--brand-deep, #134e4a);
  background: rgba(255, 255, 255, var(--on-deep-opacity-92));
  text-align: center;
}
.tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-top: 12rpx;
}
.tag {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  padding: 4rpx 12rpx;
  border-radius: var(--radius-pill);
  background: rgba(255, 255, 255, 0.2);
  white-space: nowrap;
}
.tag.ok {
  background: rgba(255, 255, 255, 0.32);
}
.tag.warn {
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand);
}
.setup-banner {
  margin: 12rpx 24rpx 0;
  padding: 14rpx 18rpx;
  border-radius: var(--radius-control);
  background: var(--brand-deep, #134e4a);
  border: 1rpx solid rgba(255, 255, 255, 0.14);
  box-shadow: 0 6rpx 16rpx rgba(6, 78, 59, 0.16);
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--white);
}
.setup-title {
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--white);
  display: block;
}
.setup-desc {
  font-size: var(--font-size-sm);
  color: rgba(255, 255, 255, var(--on-deep-opacity-78));
  display: block;
  margin-top: 2rpx;
}
.setup-arrow {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  color: var(--white);
  font-size: var(--font-size-body);
  font-weight: 500;
  white-space: nowrap;
  margin-left: 12rpx;
}
.setup-arrow .app-icon--chevron {
  width: 0.45em;
  height: 0.45em;
  border-width: 2rpx;
}

.pay-pref-card {
  margin: 12rpx 24rpx 0;
  padding: 22rpx 24rpx 20rpx;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-control);
  border: 1rpx solid var(--color-border-subtle, #edf1ef);
}
.pay-pref-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--text-primary, #223029);
}
.pay-pref-hint {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #849087);
  line-height: 1.45;
}
.pay-pref-chips {
  display: flex;
  margin-top: 16rpx;
}
.pay-pref-chip {
  flex: 1;
  text-align: center;
  padding: 16rpx 8rpx;
  margin-right: 12rpx;
  border-radius: var(--radius-control);
  background: var(--surface-muted);
  color: var(--text-muted);
  font-size: var(--font-size-caption);
  border: 2rpx solid transparent;
  box-sizing: border-box;
}
.pay-pref-chip:last-child {
  margin-right: 0;
}
.pay-pref-chip.on {
  background: var(--brand-soft);
  color: var(--brand);
  border-color: var(--success);
  font-weight: 700;
}
.pay-pref-chip.disabled {
  opacity: 0.45;
}
.pay-pref-chip.busy {
  opacity: 0.7;
}

.quick-grid {
  margin: 12rpx 24rpx 0;
  padding: 18rpx 4rpx;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-control);
  display: flex;
}
.quick-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
}
.quick-icon {
  width: 48rpx;
  height: 48rpx;
}
.quick-label {
  font-size: var(--font-size-sm);
  color: var(--text-muted, #334155);
  font-weight: 500;
}

.menu-list {
  margin: 12rpx 24rpx 0;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-control);
  overflow: hidden;
}
.logout-wrap {
  margin-top: 12rpx;
  padding-bottom: 8rpx;
}
.menu-cell {
  background: transparent;
  margin-bottom: 0;
  padding: 22rpx 24rpx;
  border: none;
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
  border-radius: 0;
  box-shadow: none;
  display: flex;
  align-items: center;
  gap: 16rpx;
  min-height: 88rpx;
  box-sizing: border-box;
}
.menu-cell:last-child {
  border-bottom: none;
}
.menu-cell.highlight {
  border: none;
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
  background: #f8fffb;
}
.menu-cell.disabled {
  opacity: 0.6;
  pointer-events: none;
}
.menu-icon {
  display: flex;
  width: 40rpx;
  height: 40rpx;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.menu-text {
  flex: 1;
  min-width: 0;
}
.menu-title {
  font-size: var(--font-size-md);
  font-weight: 500;
  color: var(--text-primary, #0f172a);
  display: block;
  line-height: 1.3;
}
.menu-desc {
  margin-top: 2rpx;
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
  display: block;
  line-height: 1.3;
}
.menu-arrow {
  color: var(--text-subtle, #cbd5e1);
  flex-shrink: 0;
  width: 0.55em;
  height: 0.55em;
  font-size: var(--font-size-md);
  margin-left: 8rpx;
}
.menu-badge {
  font-size: var(--font-size-sm);
  color: var(--danger, #991b1b);
  background: color-mix(in srgb, var(--danger, #b91c1c) 8%, var(--white));
  padding: 4rpx 12rpx;
  border-radius: var(--radius-pill);
}
.danger {
  color: var(--color-danger);
}
.danger-cell {
  background: #fffafa;
}
.danger-cell .menu-icon {
  background: transparent;
  color: var(--color-danger);
}

.dev-section {
  margin: 8rpx 24rpx 0;
  padding: 16rpx 0 0;
}
.dev-label {
  display: block;
  margin: 0 8rpx 12rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  letter-spacing: 1rpx;
}
</style>
