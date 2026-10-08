<template>
  <view class="mine-page">
    <app-nav-bar title="我的" hide-back home-url="/pages/index/index" />
    <view
      class="profile-cell"
      hover-class="wx-cell-hover"
      role="button"
      :aria-label="authed ? '设置昵称' : '去登录'"
      @click="onProfileTap"
    >
      <view class="avatar">
        <text class="avatar-text">{{ avatarText }}</text>
      </view>
      <view class="profile-mid">
        <text class="hello">{{ authed ? displayName : '未登录' }}</text>
        <text class="hello-sub">{{ profileSub }}</text>
        <view v-if="authed" class="tags">
          <text class="tag" :class="verified ? 'ok' : 'warn'">{{
            verified ? '已实名' : '待实名'
          }}</text>
          <text class="tag" :class="payReady ? 'ok' : 'warn'">{{
            payReady ? '可开门' : '待开通支付'
          }}</text>
          <!--
            🔴 当前支付方式标签（2026-10-08 用户要求：「已实名 可开门」后面加当前支付方式）。
            它是**状态展示**不是达标项 ⇒ 不用 ok/warn（那是「过/不过」的语义），
            用默认灰即可；文案与下方「优先支付方式」区块同名（余额/微信免密/支付宝免密），
            用户在两处看到的是同一个词，不会对不上。
          -->
          <text class="tag" aria-label="当前优先支付方式">{{ payPreferredLabel }}</text>
        </view>
      </view>
      <view v-if="authed" class="profile-side">
        <text class="profile-bal">{{ balanceYuan }}</text>
        <text class="profile-bal-hint">可用余额</text>
      </view>
      <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
    </view>

    <view v-if="authed" class="menu-list wallet-row" role="button" @click="goRecharge">
      <view class="menu-cell">
        <view class="menu-text">
          <text class="menu-title">可用余额</text>
          <text v-if="frozenYuan !== '¥0.00'" class="menu-desc">冻结 {{ frozenYuan }}</text>
        </view>
        <text class="wallet-amt">{{ balanceYuan }}</text>
        <text class="wallet-go">充值</text>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
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
    <view v-else-if="needsSetup" role="button" class="setup-banner" @click="goVerify()">
      <view class="setup-text">
        <text class="setup-title">完成开门准备</text>
        <text class="setup-desc">{{ setupHint }}</text>
      </view>
      <view class="setup-arrow">
        <text>去设置</text>
        <view class="app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <view v-if="authed" class="menu-list pay-pref-block">
      <view class="pay-pref-inner">
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
          <!--
          🔴 未开通的两个免密 chip：文案自带「去开通」，且点击**直接跳开通页**。
          用户反馈「怎么开通」的根因是：灰字 + 短文案「微信免密」既没说状态、也没说能点。
          这里用 aria-label 把语义补全（读屏会念「未开通，点击去开通」），
          视觉由 .pay-pref-chip.disabled 的虚线描边承担（不再用禁用灰）。
        -->
          <text
            role="button"
            class="pay-pref-chip"
            :class="{
              on: payPreferred === 'WECHAT',
              disabled: !account?.payscoreEnabled,
              busy: payPrefBusy
            }"
            :aria-label="
              account?.payscoreEnabled ? '设为优先支付方式：微信免密' : '未开通，点击去开通微信免密'
            "
            @click="onSetPayPreferred('WECHAT')"
            >{{ account?.payscoreEnabled ? '微信免密' : '微信免密 去开通' }}</text
          >
          <text
            role="button"
            class="pay-pref-chip"
            :class="{
              on: payPreferred === 'ALIPAY',
              disabled: !account?.alipayAgreementEnabled,
              busy: payPrefBusy
            }"
            :aria-label="
              account?.alipayAgreementEnabled
                ? '设为优先支付方式：支付宝免密'
                : '未开通，点击去开通支付宝免密'
            "
            @click="onSetPayPreferred('ALIPAY')"
            >{{ account?.alipayAgreementEnabled ? '支付宝免密' : '支付宝免密 去开通' }}</text
          >
        </view>
        <!-- G9：免密代扣的用户自助解约入口。没有它，用户只能等渠道侧通知才能撤回授权。 -->
        <view v-if="passwordFreeReady" class="pay-pref-unsign-row">
          <text
            role="button"
            class="pay-pref-unsign"
            :class="{ busy: payPrefBusy }"
            aria-label="关闭免密支付"
            @click="onUnsignPayContract"
            >关闭免密支付</text
          >
          <text class="pay-pref-unsign-hint">关闭后可随时重新开通</text>
        </view>
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
      <view class="menu-list">
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
import {
  consumerApi,
  ensureConsumerAuth,
  isConsumerLoggedIn,
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
import {
  MINE_DEV_RECHARGE_CENTS,
  mineAlipayRechargeConfirm,
  mineMockRechargeConfirm,
  mineRechargeIdempotencyKey,
  mineWechatRechargeConfirm
} from '@/utils/mine-recharge-copy';
import {
  resolveMockEnabled,
  resolveSandboxRecharge,
  resolveWechatRechargeVisible,
  showDevTools
} from '@/utils/runtime-flags';

/** Tab「我的」：资料行点进去设昵称或登录 */

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
const displayName = computed(
  () => account.value?.nickname || account.value?.name || maskedPhone.value || '我的账户'
);
const avatarText = computed(() => displayName.value.slice(0, 1) || '我');
/** 手机号掩码（138****8000）；微信用户 phoneNumber 是 openid 占位，不展示 */
const maskedPhone = computed(() => {
  if (!account.value || account.value.wechatUser) return '';
  const p = String(account.value.phoneNumber || '');
  return /^\d{11}$/.test(p) ? `${p.slice(0, 3)}****${p.slice(7)}` : '';
});
const profileSub = computed(() => {
  if (!authed.value) return '登录后可查看订单与余额';
  if (maskedPhone.value) return maskedPhone.value;
  if (account.value?.wechatUser) return '微信登录 · 可扫码开门';
  return '已登录';
});
const nicknameBusy = ref(false);
/** 微信昵称自助编辑（L2-身份展示）：系统弹窗输入，保存后整份刷新账户。 */
async function onEditNickname() {
  if (!authed.value || nicknameBusy.value) return;
  const res = await new Promise<UniApp.ShowModalRes>((resolve) => {
    uni.showModal({
      title: '设置昵称',
      editable: true,
      placeholderText: '1-20 个字符',
      content: account.value?.nickname || '',
      success: resolve,
      fail: () => resolve({ confirm: false, cancel: true, content: '' } as UniApp.ShowModalRes)
    });
  });
  const nickname = String(res.content || '').trim();
  if (!res.confirm || !nickname) return;
  nicknameBusy.value = true;
  try {
    await consumerApi.updateNickname(nickname);
    account.value = await consumerApi.account();
    showSuccess('昵称已更新');
  } catch (e) {
    showError(e instanceof Error ? e.message : '昵称保存失败');
  } finally {
    nicknameBusy.value = false;
  }
}

function onProfileTap() {
  if (!authed.value) {
    goLogin();
    return;
  }
  void onEditNickname();
}
const payPreferred = computed(() => {
  const c = String(account.value?.payPreferredChannel || 'BALANCE').toUpperCase();
  if (c === 'WECHAT' || c === 'ALIPAY' || c === 'BALANCE') return c;
  return 'BALANCE';
});
/** 身份标签行展示用：当前优先支付方式的中文（与「优先支付方式」区块用词一致）。 */
const payPreferredLabel = computed(
  () => ({ BALANCE: '余额支付', WECHAT: '微信免密', ALIPAY: '支付宝免密' })[payPreferred.value]
);
/** G9：是否已有生效的免密代扣（任一渠道）——决定是否显示「关闭免密支付」。 */
const passwordFreeReady = computed(() => !!account.value?.passwordFreeReady);
const setupHint = computed(() => {
  if (!verified.value) return '完成实名并开通免密支付后即可开门';
  return payReadyHint(account.value, null, preauthCents.value);
});

async function onSetPayPreferred(channel: 'BALANCE' | 'WECHAT' | 'ALIPAY') {
  if (!authed.value || payPrefBusy.value) return;
  if (channel === payPreferred.value) return;
  /*
   * 🔴 未开通免密时**直接跳到开通页**，不要用 toast 让用户自己找（2026-10-08 两轮反馈）。
   *
   * 成因（分两次，都是我判断错）：
   *   v1 只弹「请先开通支付宝免密」—— 说清了「不能做」却没说「去哪做」；
   *   v2 改成「未开通支付宝免密，请点上方『完成开门准备 → 去设置』」（24 字）
   *       ⇒ 用户反馈**「还是不明白」**，且真机截图显示 toast 被**截断**成
   *          「…请点上方『完成开门准备...」—— 微信 showToast 的 title 有长度上限，
   *          长文案既读不完、又要用户自己在页面上找对应横幅。
   * ⇒ 正解：**别让用户读路径，直接把他送到** pages/verify/verify（标题「开通支付」，
   *    微信/支付宝两个开通按钮都在那里）。toast 只留一句短的过场提示。
   *    开通页本身也能「返回」到本页，用户不会迷失。
   *
   * 后端链路是通的（签约端点见 ConsumerEndpoints.payscoreSign / alipayAgreementSign），
   * 本地 mock 模式（infra/.env 的 AICABINET_MOCK_ENABLED=true）点一下即开通。
   *
   * v5（2026-10-08）：跳转带 `channel` 参数。用户实测反馈「开通后还是显示去开通」——
   * 取证结论：后端 24h 日志里**零签约请求**（用户到了开通页但没按对应的签约按钮，
   * 开通页有两个大按钮「开通微信支付分」「开通支付宝免密」，从 mine 跳过去后
   * 用户不知道该按哪个 / 按了返回以为就算开通了）。⇒ 跳转时带上是哪个渠道，
   * 开通页据此**高亮 + 脉冲提示**对应的按钮（见 verify.vue 的 pendingChannel）。
   */
  if (channel === 'WECHAT' && !account.value?.payscoreEnabled) {
    showError('请先开通');
    goVerify(channel);
    return;
  }
  if (channel === 'ALIPAY' && !account.value?.alipayAgreementEnabled) {
    showError('请先开通');
    goVerify(channel);
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

/**
 * G9：用户主动解约（关闭免密代扣）。
 *
 * 后端返回**刷新后的账户** ⇒ 直接整份替换，避免「按钮点完了、状态还显示已开通」的前后端漂移；
 * 提示文案按调用前后的 `passwordFreeReady` 差异决定，不在后端再写一套 UI 判断。
 */
async function onUnsignPayContract() {
  if (!authed.value || payPrefBusy.value) return;
  const confirmed = await showConfirm({
    title: '关闭免密支付',
    content: '关闭后购物将改用余额或当场付款，可随时重新开通。',
    confirmText: '确认关闭'
  });
  if (!confirmed) return;
  const wasReady = passwordFreeReady.value;
  payPrefBusy.value = true;
  try {
    account.value = await consumerApi.unsignPayContract();
    syncBalanceDisplay(account.value);
    showSuccess(wasReady ? '免密支付已关闭' : '当前没有已开通的免密支付');
  } catch (e) {
    showError(e instanceof Error ? e.message : '关闭失败，请稍后重试');
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
  authed.value = isConsumerLoggedIn();
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
    authed.value = isConsumerLoggedIn();
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
  const confirmed = await showConfirm(mineWechatRechargeConfirm(wechatPayLive.value));
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = mineRechargeIdempotencyKey('wechat');
    await runWeChatRecharge(MINE_DEV_RECHARGE_CENTS, key);
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
  const confirmed = await showConfirm(mineAlipayRechargeConfirm(isMock));
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = mineRechargeIdempotencyKey('alipay');
    const { mode } = await runAlipayRecharge(MINE_DEV_RECHARGE_CENTS, key);
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
  const confirmed = await showConfirm(mineMockRechargeConfirm());
  if (!confirmed) return;
  rechargeLoading.value = true;
  try {
    const key = mineRechargeIdempotencyKey('mock');
    const prepay = await consumerApi.createMockRecharge(MINE_DEV_RECHARGE_CENTS, key);
    await consumerApi.confirmMockRecharge(prepay.orderId);
    await refreshAccount();
    showSuccess('余额已到账');
  } catch (error) {
    showError(error instanceof Error ? error.message : '充值失败');
  } finally {
    rechargeLoading.value = false;
  }
}

function goVerify(channel?: 'WECHAT' | 'ALIPAY') {
  const query = channel ? `?channel=${channel}` : '';
  uni.navigateTo({ url: '/pages/verify/verify' + query });
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
  showSuccess('已退出');
}
</script>

<style scoped src="./mine.page.css"></style>
