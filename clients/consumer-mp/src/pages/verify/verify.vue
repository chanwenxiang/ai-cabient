<template>
  <view class="page-root">
    <app-nav-bar title="开通支付" />
    <view class="page-body">
      <view class="hero">
        <text class="hero-title">开通免密支付</text>
        <text class="hero-sub">完成后即可扫码开门，关门自动扣款</text>
      </view>

      <!--
        V326（CB-014）：**已去掉消费者实名一步**，只保留「开通免密支付」单步。
        依据：① 五家同行（友宝/美智微/哈哈零兽/小麦便利/丰e足食）消费者流程都是
        「扫码 → 开门 → 拿货 → 关门自动扣款」，无一家要消费者填身份证；
        ② 《微信支付分用户服务协议》2.3：平台只返「是否可以使用服务的结果」，
        不含身份信息与分值 ⇒ 商家侧实名无必要且越界；
        ③ 多一步表单 = 一道流失点（用户原话：顾客购买要实名肯定不愿意，流失客源）。
        ⚠️ **商家侧实名保留**（WithdrawEligibilityService，提现合规）——两者不要混为一谈。
      -->
      <view class="steps">
        <view class="step" :class="{ done: payReady, active: !payReady }">
          <view class="step-dot">{{ payReady ? '✓' : '1' }}</view>
          <text class="step-label">开通免密支付</text>
        </view>
      </view>

      <view v-if="!payReady" class="card">
        <text class="card-title">开通免密支付</text>
        <text class="card-desc"
          >推荐开通支付分 / 免密代扣；可用余额 ≥ ¥{{ needYuan }} 也可临时开门。开通后扫码即开门，
          关门自动扣款。</text
        >
        <view v-if="account?.phoneNumber" class="status-row">
          <text class="status-label">登录手机号</text>
          <text class="status-val">{{ maskPhone(account.phoneNumber) }}</text>
        </view>
        <view class="status-row">
          <text class="status-label">可用余额</text>
          <text class="status-val">{{ balanceYuan }}</text>
        </view>
        <view v-if="frozenYuan !== '¥0.00'" class="status-row">
          <text class="status-label">冻结中</text>
          <text class="status-val">{{ frozenYuan }}</text>
        </view>
        <view class="status-row">
          <text class="status-label">优先支付</text>
          <text class="status-val">{{ preferredPayText }}</text>
        </view>
        <view class="status-row">
          <text class="status-label">开门预授权</text>
          <text class="status-val">约 ¥{{ needYuan }}</text>
        </view>
        <view class="status-row">
          <text class="status-label">微信支付分</text>
          <text class="status-val">{{ wechatReady ? '已开通' : '未开通' }}</text>
        </view>
        <view class="status-row">
          <text class="status-label">支付宝免密</text>
          <text class="status-val">{{ alipayReady ? '已开通' : '未开通' }}</text>
        </view>
        <view class="btn-slot">
          <app-button
            :loading="signing"
            :label="signing ? '开通中…' : '开通微信支付分'"
            @click="onSignPayScore"
          />
        </view>
        <view class="btn-slot">
          <app-button
            variant="alipay"
            :loading="signingAlipay"
            :label="signingAlipay ? '开通中…' : '开通支付宝免密'"
            @click="onSignAlipay"
          />
        </view>
        <view role="button" class="link app-link-chevron" @click="goRecharge"
          >余额不足？去充值</view
        >
        <text v-if="devTools" class="hint"
          >当前为体验开通流程；正式环境将跳转微信/支付宝签约。</text
        >
        <text v-if="err" class="err">{{ err }}</text>
      </view>

      <view v-else class="card done-card">
        <text class="done-icon">✓</text>
        <text class="done-title">可以开门购物了</text>
        <text class="done-desc">扫柜门二维码即可开门取货</text>
        <view class="done-meta">
          <text>优先支付：{{ preferredPayText }}</text>
          <text>可用余额 {{ balanceYuan }}</text>
        </view>
        <app-button label="去扫码开门" @click="goShop" />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app';
import { showSuccess, showConfirm } from '@/utils/notify';
import { computed, ref } from 'vue';
import type { AccountDto } from '@aicabinet/shared-types';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';
import { softFallback } from '@/utils/soft-fallback';
import { fmtMoney } from '@aicabinet/shared-uni/format';
import {
  availableCents,
  isPayReady,
  preauthYuanLabel,
  resolveClientPreauthCents
} from '@/utils/account';
import { showDevTools } from '@/utils/runtime-flags';

const devTools = showDevTools();
const account = ref<AccountDto | null>(null);
const signing = ref(false);
const signingAlipay = ref(false);
const err = ref('');
const fromOpen = ref(false);
const configPreauthCents = ref<number | null>(null);

const preauthCents = computed(() =>
  resolveClientPreauthCents({ configPreauthCents: configPreauthCents.value })
);
const needYuan = computed(() => preauthYuanLabel(preauthCents.value));
const balanceYuan = computed(() => fmtMoney(availableCents(account.value)));
const frozenYuan = computed(() => fmtMoney(Math.max(0, account.value?.frozenCents || 0)));
const payReady = computed(() => isPayReady(account.value, null, preauthCents.value));
const wechatReady = computed(() => !!account.value?.payscoreEnabled);
const alipayReady = computed(() => !!account.value?.alipayAgreementEnabled);
const preferredPayText = computed(() => {
  const ch = String(account.value?.payPreferredChannel || '').toUpperCase();
  if (ch === 'WECHAT' || ch === 'WECHAT_PAYSCORE') return '微信支付分';
  if (ch === 'ALIPAY') return '支付宝免密';
  if (ch === 'BALANCE') return '账户余额';
  if (wechatReady.value) return '微信支付分';
  if (alipayReady.value) return '支付宝免密';
  return '余额兜底';
});

function maskPhone(phone?: string | number) {
  const p = String(phone || '').replaceAll(/\D/g, '');
  if (p.length < 7) return String(phone || '');
  return p.slice(0, 3) + '****' + p.slice(-4);
}

onLoad((opts) => {
  fromOpen.value = opts?.from === 'open';
});

onShow(async () => {
  err.value = '';
  const ok = await ensureConsumerAuth();
  if (!ok) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/verify/verify')
    });
    return;
  }
  try {
    const [acc, cfg] = await Promise.all([
      consumerApi.account(),
      softFallback(consumerApi.consumerPublicConfig(), null, '公开配置')
    ]);
    account.value = acc;
    const p = Number(cfg?.preauthCents);
    configPreauthCents.value = Number.isFinite(p) && p > 0 ? p : null;
  } catch (e) {
    err.value = e instanceof Error ? e.message : '加载账户失败';
  }
});

async function onSignPayScore() {
  signing.value = true;
  err.value = '';
  try {
    const res = await consumerApi.signPayScore();
    account.value = await consumerApi.account();
    showSuccess(res.message || '开通成功');
    if (fromOpen.value) {
      setTimeout(goShop, 600);
    }
  } catch (e) {
    err.value = e instanceof Error ? e.message : '开通失败';
  } finally {
    signing.value = false;
  }
}

async function onSignAlipay() {
  signingAlipay.value = true;
  err.value = '';
  try {
    const res = await consumerApi.signAlipayAgreement();
    if (res.pending && res.signFormHtml) {
      // 生产：支付宝内 H5 自动提交签约表单；微信小程序不内嵌支付宝签约
      let isH5Runtime = false;
      // #ifdef H5
      isH5Runtime = true;
      // #endif
      if (isH5Runtime && typeof document !== 'undefined') {
        const blob = new Blob([res.signFormHtml], { type: 'text/html;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        globalThis.location.href = url;
        return;
      }
      await showConfirm({
        title: '请在支付宝内开通',
        content:
          '支付宝免密需在支付宝扫柜码进入后开通（不做支付宝小程序）。当前环境无法跳转签约页。',
        showCancel: false
      });
      account.value = await consumerApi.account();
      return;
    }
    account.value = await consumerApi.account();
    showSuccess(res.message || '开通成功');
    if (fromOpen.value) {
      setTimeout(goShop, 600);
    }
  } catch (e) {
    err.value = e instanceof Error ? e.message : '开通失败';
  } finally {
    signingAlipay.value = false;
  }
}

function goRecharge() {
  uni.navigateTo({ url: '/pages/recharge/recharge' });
}

function goShop() {
  uni.switchTab({ url: '/pages/index/index' });
}
</script>

<style scoped>
.page-root {
  min-height: 100%;
  background: var(--card-bg, #ffffff);
  padding: 0;
  box-sizing: border-box;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.hero {
  padding: 16rpx 8rpx 24rpx;
}
.hero-title {
  font-size: var(--font-size-h2);
  font-weight: 700;
  color: var(--color-text-primary);
  display: block;
}
.hero-sub {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #888);
  margin-top: 8rpx;
  display: block;
}
.steps {
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 0 20rpx;
  padding: 24rpx 22rpx;
  border-radius: var(--radius-card);
  background: var(--page-bg, #f8faf9);
  border: 1rpx solid var(--color-border-subtle, #edf1ef);
}
.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
}
.step-dot {
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  background: var(--card-border);
  color: var(--text-muted, #334155);
  font-size: var(--font-size-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}
.step.active .step-dot,
.step.done .step-dot {
  background: linear-gradient(135deg, var(--brand), var(--brand));
  color: var(--white);
}
.step-label {
  font-size: var(--font-size-caption);
  color: var(--text-subtle, #888);
}
.step.active .step-label,
.step.done .step-label {
  color: var(--brand);
  font-weight: 600;
}
.step-line {
  width: 120rpx;
  height: 4rpx;
  background: var(--card-border);
  margin: 0 16rpx 28rpx;
}
.step-line.done {
  background: var(--brand);
}
.card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  padding: 32rpx;
  margin: 0;
  border: none;
  box-shadow: none;
}
/*
 * 块级按钮槽：小程序自定义组件默认 inline 级 + .app-btn--block 宽 100%
 * ⇒ 相邻两个 app-button 零间距贴死；页面样式进不去组件内部，间距只能挂在 wrapper 上。
 */
.btn-slot + .btn-slot {
  margin-top: 20rpx;
}
.card-title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  color: var(--color-text-primary);
  display: block;
}
.card-desc {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #888);
  margin: 12rpx 0 28rpx;
  display: block;
  line-height: 1.5;
}
.field-label {
  display: block;
  font-size: var(--font-size-body);
  color: var(--text-muted, #666);
  margin-bottom: 12rpx;
  margin-top: 8rpx;
}
.input {
  width: 100%;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.4;
  background: var(--page-bg, #f5f7f8);
  border: 1rpx solid var(--card-border, #e8eeeb);
  border-radius: var(--radius-panel);
  padding: 0 24rpx;
  margin-bottom: 16rpx;
  font-size: var(--font-size-lg);
  color: var(--color-text-primary);
  box-sizing: border-box;
}
.btn-stack > .app-btn + .app-btn {
  margin-top: 16rpx;
}
.btn-hover {
  opacity: 0.85;
}
.err {
  color: var(--color-danger);
  font-size: var(--font-size-body);
  margin-top: 16rpx;
  display: block;
}
.status-row {
  display: flex;
  justify-content: space-between;
  padding: 20rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle);
}
.status-label {
  font-size: var(--font-size-md);
  color: var(--text-subtle, #888);
}
.status-val {
  font-size: var(--font-size-md);
  color: var(--color-text-primary);
}
.hint {
  font-size: var(--font-size-caption);
  color: var(--text-subtle);
  margin-top: 24rpx;
  display: block;
  line-height: 1.5;
}
.link {
  color: var(--color-link-secondary);
  font-size: var(--font-size-md);
  margin-top: 20rpx;
}
.done-card {
  text-align: center;
  padding: 48rpx 32rpx;
}
.done-icon {
  font-size: 88rpx;
  display: block;
  margin-bottom: 16rpx;
  color: var(--brand);
}
.done-title {
  font-size: var(--font-size-h3);
  font-weight: 600;
  color: var(--color-text-primary);
  display: block;
}
.done-desc {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #888);
  margin: 12rpx 0 16rpx;
  display: block;
}
.done-meta {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin-bottom: 28rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
}
</style>
