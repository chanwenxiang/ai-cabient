<template>
  <view class="mine-page">
    <view class="profile-header">
      <view class="profile-orb orb-a" /><view class="profile-orb orb-b" />
      <view class="avatar">{{ avatarText }}</view>
      <view class="profile-info">
        <text class="account-kicker">AI CABINET MEMBER</text>
        <text class="hello">{{ authed ? displayName : '游客模式' }}</text>
        <view v-if="authed" class="balance-row" @click="goRecharge">
          <text class="balance-label">测试余额</text>
          <text class="balance-number">¥{{ balanceYuan }}</text>
          <text class="balance-action">充值 ›</text>
        </view>
        <text v-else class="balance">扫码购物无需注册</text>
        <view v-if="authed" class="tags">
          <text class="tag" :class="verified ? 'ok' : 'warn'">{{ verified ? '已实名' : '待实名' }}</text>
          <text class="tag" :class="payReady ? 'ok' : 'warn'">{{ payReady ? '支付已开通' : '待开通支付' }}</text>
        </view>
      </view>
    </view>

    <view v-if="authed && needsSetup" class="setup-banner" @click="goVerify">
      <view class="setup-text">
        <text class="setup-title">完成开门准备</text>
        <text class="setup-desc">{{ setupHint }}</text>
      </view>
      <text class="setup-arrow">去设置 ›</text>
    </view>

    <view class="menu-list">
      <view v-if="authed && !verified" class="menu-cell highlight" @click="goVerify">
        <text class="menu-icon">🪪</text>
        <view class="menu-text">
          <text class="menu-title">实名认证</text>
          <text class="menu-desc">填写姓名与身份证后四位</text>
        </view>
        <text class="menu-badge">待完成</text>
        <text class="menu-arrow">›</text>
      </view>
      <view v-if="authed && verified && !payReady" class="menu-cell highlight" @click="goVerify">
        <text class="menu-icon">💳</text>
        <view class="menu-text">
          <text class="menu-title">开通微信支付分</text>
          <text class="menu-desc">免押金开门，关门自动扣款</text>
        </view>
        <text class="menu-badge">待开通</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goIndex">
        <text class="menu-icon">🛒</text>
        <view class="menu-text">
          <text class="menu-title">开门购物</text>
          <text class="menu-desc">扫码开门，取货即走</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
            <view class="menu-cell" @click="goOrders">
        <text class="menu-icon">📋</text>
        <view class="menu-text">
          <text class="menu-title">我的订单</text>
          <text class="menu-desc">查看历史购物记录</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goCoupons">
        <text class="menu-icon">🎫</text>
        <view class="menu-text">
          <text class="menu-title">我的优惠券</text>
          <text class="menu-desc">查看和使用优惠券</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goMember">
        <text class="menu-icon">👑</text>
        <view class="menu-text">
          <text class="menu-title">会员中心</text>
          <text class="menu-desc">等级权益 · 积分兑券</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goMarketing">
        <text class="menu-icon">🔥</text>
        <view class="menu-text">
          <text class="menu-title">热门活动</text>
          <text class="menu-desc">满减周 · 新客礼 · 积分兑好礼</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goRecharge">
        <text class="menu-icon">💰</text>
        <view class="menu-text">
          <text class="menu-title">账户充值</text>
          <text class="menu-desc">充值余额，关门自动扣款</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view v-if="authed && wechatRechargeEnabled" class="menu-cell highlight" :class="{ disabled: rechargeLoading }" @click="onWeChatRecharge">
        <text class="menu-icon">💚</text>
        <view class="menu-text">
          <text class="menu-title">{{ wechatPayLive ? '微信支付充值' : '微信模拟充值' }}</text>
          <text class="menu-desc">{{ wechatPayLive ? '小程序内调起微信支付' : '本地 mock 预下单并即时到账 ¥20' }}</text>
        </view>
        <text class="menu-badge">{{ rechargeLoading ? '处理中' : '充 ¥20' }}</text>
      </view>
      <view v-if="authed && alipayRechargeEnabled" class="menu-cell highlight" :class="{ disabled: rechargeLoading }" @click="onAlipayRecharge">
        <text class="menu-icon">💰</text>
        <view class="menu-text">
          <text class="menu-title">支付宝沙箱充值</text>
          <text class="menu-desc">充值 ¥20 测试余额，用于真实环境购物扣款</text>
        </view>
        <text class="menu-badge">{{ rechargeLoading ? '处理中' : '充 ¥20' }}</text>
      </view>
      <view v-if="authed && mockRechargeEnabled" class="menu-cell highlight" :class="{ disabled: rechargeLoading }" @click="onMockRecharge">
        <text class="menu-icon">🧪</text>
        <view class="menu-text">
          <text class="menu-title">模拟充值测试余额</text>
          <text class="menu-desc">灰度测试专用，不会收取微信或支付宝资金</text>
        </view>
        <text class="menu-badge">{{ rechargeLoading ? '处理中' : '充 ¥20' }}</text>
      </view>
      <view v-if="authed" class="menu-cell" @click="showTransactions = !showTransactions">
        <text class="menu-icon">💳</text>
        <view class="menu-text">
          <text class="menu-title">测试余额明细</text>
          <text class="menu-desc">查看购物扣款、退款和运营发放记录</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view v-if="showTransactions" class="transaction-list">
        <view v-if="transactionsLoading" class="transaction-empty">加载中…</view>
        <view v-else-if="!transactions.length" class="transaction-empty">暂无余额流水</view>
        <view v-for="item in transactions" :key="item.transactionId" class="transaction-row">
          <view><text class="transaction-title">{{ transactionLabel(item.businessType) }}</text><text class="transaction-time">{{ formatTransactionTime(item.createdAt) }}</text></view>
          <view class="transaction-amount" :class="{ income: item.amountCents > 0 }">{{ formatTransactionAmount(item.amountCents) }}</view>
        </view>
      </view>
      <view class="menu-cell" @click="goReport">
        <text class="menu-icon">🔧</text>
        <view class="menu-text">
          <text class="menu-title">故障报修</text>
          <text class="menu-desc">柜机打不开、门关不上等问题</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goFeedback">
        <text class="menu-icon">💬</text>
        <view class="menu-text">
          <text class="menu-title">意见反馈</text>
          <text class="menu-desc">投诉、建议或表扬</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-cell" @click="goLogin">
        <text class="menu-icon">📱</text>
        <view class="menu-text">
          <text class="menu-title">手机号验证</text>
          <text class="menu-desc">绑定演示账号或已有账户</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
      <view v-if="authed" class="menu-cell danger-cell" @click="onLogout">
        <text class="menu-icon">🚪</text>
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
import { clearConsumerSession, consumerApi, ensureConsumerAuth, getConsumerToken } from '@/utils/consumer-api';
import { formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { isPayReady, payReadyHint } from '@/utils/account';
import {
  openAlipayPrepay,
  resumePendingRechargeIfAny,
  runWeChatRecharge,
  savePendingRechargeOrder
} from '@/utils/recharge';

const balanceYuan = ref('-');
const authed = ref(false);
const account = ref<AccountDto | null>(null);
const showTransactions = ref(false);
const transactionsLoading = ref(false);
const transactions = ref<BalanceTransactionDto[]>([]);
const rechargeLoading = ref(false);
const mockRechargeEnabled = ref(true);
const alipayRechargeEnabled = ref(false);
const wechatRechargeEnabled = ref(false);
const wechatPayLive = ref(false);

const verified = computed(() => !!account.value?.verified);
const payReady = computed(() => isPayReady(account.value));
const needsSetup = computed(() => !verified.value || !payReady.value);
const displayName = computed(() => (verified.value ? '我的账户' : '我的账户（待实名）'));
const avatarText = computed(() => account.value?.realName?.slice(0, 1) || '我');
const setupHint = computed(() => {
  if (!verified.value) return '需先完成实名认证';
  return payReadyHint(account.value);
});

onShow(async () => {
  await ensureConsumerAuth();
  authed.value = !!getConsumerToken();
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    mockRechargeEnabled.value = cfg?.mockEnabled !== 'false';
    alipayRechargeEnabled.value = cfg?.alipayRechargeEnabled === 'true';
    wechatRechargeEnabled.value = cfg?.wechatRechargeEnabled === 'true';
    wechatPayLive.value = cfg?.wechatPayLive === 'true';
  } catch {
    mockRechargeEnabled.value = true;
    alipayRechargeEnabled.value = false;
    wechatRechargeEnabled.value = true;
    wechatPayLive.value = false;
  }
  if (!authed.value) {
    balanceYuan.value = '-';
    account.value = null;
    return;
  }
  // 先拉账户（避免 pending 轮询期间误显示「待实名 / ¥-」）
  try {
    account.value = await consumerApi.account();
    balanceYuan.value = ((account.value.balanceCents || 0) / 100).toFixed(2);
  } catch (e) {
    balanceYuan.value = '-';
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
      balanceYuan.value = ((account.value.balanceCents || 0) / 100).toFixed(2);
    } catch {
      /* keep previous snapshot */
    }
  }
  if (!authed.value) return;
  transactionsLoading.value = true;
  consumerApi
    .balanceTransactions(0, 10)
    .then((page) => {
      transactions.value = page.items || [];
    })
    .catch(() => {
      /* ignore */
    })
    .finally(() => {
      transactionsLoading.value = false;
    });
});

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
  const amount = Math.abs(cents || 0) / 100;
  return `${cents > 0 ? '+' : cents < 0 ? '-' : ''}¥${amount.toFixed(2)}`;
}

async function refreshAccount() {
  account.value = await consumerApi.account();
  balanceYuan.value = ((account.value.balanceCents || 0) / 100).toFixed(2);
  const page = await consumerApi.balanceTransactions(0, 10);
  transactions.value = page.items || [];
}

async function onWeChatRecharge() {
  if (rechargeLoading.value) return;
  const confirmed = await new Promise<boolean>((resolve) =>
    uni.showModal({
      title: wechatPayLive.value ? '微信支付充值' : '微信模拟充值',
      content: wechatPayLive.value
        ? '将调起微信支付充值 ¥20.00。'
        : '将通过微信 mock 通道充值 ¥20.00 测试余额，不会真实扣款。',
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
  const confirmed = await new Promise<boolean>((resolve) => uni.showModal({
    title: '支付宝沙箱充值',
    content: '将跳转支付宝沙箱支付页充值 ¥20.00 测试余额。支付完成后返回本页自动确认到账。',
    confirmText: '去支付',
    success: (res) => resolve(res.confirm),
    fail: () => resolve(false)
  }));
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = `alipay-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const prepay = await consumerApi.createRechargePrepay('ALIPAY', 2000, key);
    if (!prepay.alipayPay?.payFormHtml && !prepay.alipayPay?.payUrl) {
      throw new Error('未获取到支付宝支付链接，请检查沙箱配置');
    }
    savePendingRechargeOrder(prepay.orderId);
    openAlipayPrepay(prepay.alipayPay);
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '充值失败', icon: 'none' });
  } finally {
    rechargeLoading.value = false;
  }
}

async function onMockRecharge() {
  if (rechargeLoading.value) return;
  const confirmed = await new Promise<boolean>((resolve) => uni.showModal({
    title: '确认模拟充值',
    content: '将向当前账户发放 ¥20.00 测试余额，不会发生真实扣款。',
    confirmText: '确认发放',
    success: (res) => resolve(res.confirm),
    fail: () => resolve(false)
  }));
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = `mock-recharge-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const prepay = await consumerApi.createMockRecharge(2000, key);
    await consumerApi.confirmMockRecharge(prepay.orderId);
    await refreshAccount();
    uni.showToast({ title: '测试余额已到账', icon: 'success' });
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
    url: id
      ? `/pages/report/report?deviceId=${encodeURIComponent(id)}`
      : '/pages/report/report'
  });
}

function goFeedback() {
  const id = uni.getStorageSync('last_device_id') || '';
  uni.navigateTo({
    url: id
      ? `/pages/feedback/feedback?deviceId=${encodeURIComponent(id)}`
      : '/pages/feedback/feedback'
  });
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
      balanceYuan.value = '-';
      transactions.value = [];
      showTransactions.value = false;
      uni.showToast({ title: '已退出', icon: 'none' });
    }
  });
}
</script>

<style scoped>
.profile-header { background: linear-gradient(135deg, #07c160, #06ae56); padding: 48rpx 32rpx; display: flex; align-items: center; gap: 24rpx; }
.avatar { width: 100rpx; height: 100rpx; border-radius: 50%; background: rgba(255,255,255,0.25); display: flex; align-items: center; justify-content: center; font-size: 48rpx; }
.profile-info { color: #fff; flex: 1; }
.hello { font-size: 36rpx; font-weight: 700; display: block; }
.balance { font-size: 28rpx; opacity: 0.9; display: block; margin-top: 4rpx; }
.tags { display: flex; gap: 12rpx; margin-top: 12rpx; flex-wrap: wrap; }
.tag { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 8rpx; background: rgba(255,255,255,0.2); }
.tag.ok { background: rgba(255,255,255,0.35); }
.tag.warn { background: #fff3cd; color: #856404; }
.setup-banner { margin: 12px; background: #fff7e6; border: 1rpx solid #ffd591; border-radius: 16px; padding: 24rpx; display: flex; align-items: center; justify-content: space-between; }
.setup-title { font-size: 30rpx; font-weight: 600; color: #d48806; display: block; }
.setup-desc { font-size: 24rpx; color: #ad6800; display: block; margin-top: 4rpx; }
.setup-arrow { color: #d48806; font-size: 28rpx; font-weight: 500; white-space: nowrap; margin-left: 16rpx; }
.menu-list {
  margin: 12px;
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
}
.menu-cell { background: #fff; border-radius: 16px; padding: 28rpx 24rpx; margin-bottom: 12rpx; display: flex; align-items: center; gap: 20rpx; box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.menu-cell.highlight { border: 1rpx solid #07c160; }
.menu-cell.disabled { opacity: 0.6; pointer-events: none; }
.menu-icon { font-size: 40rpx; }
.menu-text { flex: 1; min-width: 0; }
.menu-title { font-size: 30rpx; font-weight: 500; display: block; color: #191919; }
.menu-desc { font-size: 24rpx; color: #888; display: block; margin-top: 4rpx; }
.menu-badge { font-size: 22rpx; color: #fa5151; background: #fff1f0; padding: 4rpx 12rpx; border-radius: 8rpx; }
.menu-arrow { color: #ccc; font-size: 36rpx; }
.danger { color: #fa5151; }
.transaction-list { background: #fff; border-radius: 16px; margin-bottom: 12rpx; padding: 0 24rpx; }
.transaction-row { display: flex; justify-content: space-between; align-items: center; padding: 24rpx 0; border-bottom: 1rpx solid #eee; }
.transaction-title { display: block; font-size: 28rpx; color: #191919; }
.transaction-time { display: block; margin-top: 6rpx; font-size: 22rpx; color: #999; }
.transaction-amount { font-size: 30rpx; font-weight: 600; color: #191919; }
.transaction-amount.income { color: #07c160; }
.transaction-empty { padding: 28rpx; text-align: center; color: #999; font-size: 25rpx; }
</style>
<style scoped>
.mine-page{min-height:100%;box-sizing:border-box;padding-bottom:calc(160rpx + env(safe-area-inset-bottom));background:linear-gradient(180deg,#e9fbf3 0,#f5f7f8 390rpx,#f5f7f8 100%)}.profile-header{position:relative;overflow:hidden;margin:20rpx 24rpx 0;padding:40rpx 32rpx;border-radius:30rpx;background:linear-gradient(140deg,#064e3b 0%,#059669 56%,#14b8a6 100%);box-shadow:0 20rpx 46rpx rgba(5,150,105,.23)}.profile-orb{position:absolute;border-radius:50%;background:rgba(255,255,255,.09)}.orb-a{width:230rpx;height:230rpx;right:-80rpx;top:-110rpx}.orb-b{width:120rpx;height:120rpx;right:120rpx;bottom:-80rpx}.avatar{position:relative;width:112rpx;height:112rpx;border:2rpx solid rgba(255,255,255,.35);background:rgba(255,255,255,.18);box-shadow:0 10rpx 25rpx rgba(0,0,0,.1)}.profile-info{position:relative}.account-kicker{display:block;margin-bottom:7rpx;font-size:19rpx;letter-spacing:3rpx;opacity:.68}.hello{font-size:36rpx}.balance-row{display:flex;align-items:baseline;gap:13rpx;margin-top:9rpx}.balance-label{font-size:22rpx;opacity:.72}.balance-number{font-size:38rpx;font-weight:800;letter-spacing:-1rpx}.balance-action{margin-left:auto;font-size:22rpx;opacity:.85}.tags{margin-top:15rpx}.tag{padding:6rpx 15rpx;border-radius:999rpx}.setup-banner{margin:20rpx 24rpx 0;padding:24rpx 26rpx;border:0;border-radius:21rpx;background:linear-gradient(135deg,#fff7df,#fffbeb);box-shadow:0 8rpx 22rpx rgba(217,119,6,.08)}.menu-list{margin:22rpx 24rpx 0;padding-bottom:24rpx}.menu-cell{margin-bottom:14rpx;padding:25rpx 22rpx;border:1rpx solid #edf1ef;border-radius:22rpx;box-shadow:0 8rpx 25rpx rgba(15,23,42,.05)}.menu-cell.highlight{border:1rpx solid rgba(5,150,105,.32);background:linear-gradient(90deg,#fff,#f0fdf7)}.menu-icon{display:flex;width:72rpx;height:72rpx;align-items:center;justify-content:center;border-radius:19rpx;background:#f0fdf4;font-size:34rpx}.menu-title{font-size:28rpx;font-weight:650;color:#223029}.menu-desc{margin-top:5rpx;color:#849087;font-size:22rpx}.menu-badge{border-radius:999rpx}.transaction-list{margin-bottom:14rpx;border:1rpx solid #edf1ef;border-radius:22rpx;box-shadow:0 8rpx 25rpx rgba(15,23,42,.045)}.transaction-row:last-child{border-bottom:0}.danger-cell{background:#fffafa}.danger-cell .menu-icon{background:#fff1f0}
</style>

