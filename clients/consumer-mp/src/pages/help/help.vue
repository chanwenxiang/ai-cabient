<template>
  <view class="page">
    <app-nav-bar title="帮助中心" />

    <view class="card">
      <text class="card-title">联系客服</text>
      <view
        class="support-row"
        role="button"
        aria-label="拨打客服热线"
        hover-class="support-row-hover"
        @click="callSupport"
      >
        <view class="support-icon support-icon--phone" aria-hidden="true" />
        <view class="support-main">
          <text class="support-label">客服热线</text>
          <text class="support-value">{{ supportPhoneDisplay }}</text>
        </view>
        <view class="support-action">拨打</view>
      </view>
      <view
        v-if="supportEmail"
        class="support-row"
        role="button"
        aria-label="复制客服邮箱"
        hover-class="support-row-hover"
        @click="copySupportEmail"
      >
        <view class="support-icon support-icon--mail" aria-hidden="true" />
        <view class="support-main">
          <text class="support-label">客服邮箱</text>
          <text class="support-value">{{ supportEmail }}</text>
        </view>
        <view class="support-action">复制</view>
      </view>
      <view
        class="support-row"
        role="button"
        aria-label="查看平台公告"
        hover-class="support-row-hover"
        @click="goAnnouncements"
      >
        <view class="support-icon support-icon--notice" aria-hidden="true" />
        <view class="support-main">
          <text class="support-label">平台公告</text>
          <text class="support-value">维护通知、活动与规则变更</text>
        </view>
        <view class="support-action">去查看</view>
      </view>
      <view
        class="support-row"
        role="button"
        aria-label="在线留言反馈"
        hover-class="support-row-hover"
        @click="goFeedback"
      >
        <view class="support-icon support-icon--chat" aria-hidden="true" />
        <view class="support-main">
          <text class="support-label">在线留言</text>
          <text class="support-value">意见反馈，运营将跟进回复</text>
        </view>
        <view class="support-action">去反馈</view>
      </view>
      <view
        class="support-row"
        role="button"
        aria-label="报修柜机故障"
        hover-class="support-row-hover"
        @click="goReport"
      >
        <view class="support-icon support-icon--wrench" aria-hidden="true" />
        <view class="support-main">
          <text class="support-label">柜机故障</text>
          <text class="support-value">打不开门、关不上门等</text>
        </view>
        <view class="support-action">去报修</view>
      </view>
      <view
        class="support-row"
        role="button"
        aria-label="打开消息中心"
        hover-class="support-row-hover"
        @click="goMessages"
      >
        <view class="support-icon support-icon--bell" aria-hidden="true" />
        <view class="support-main">
          <text class="support-label">消息中心</text>
          <text class="support-value">订单、售后与优惠提醒</text>
        </view>
        <view class="support-action">去查看</view>
      </view>
      <view
        class="support-row"
        role="button"
        aria-label="查找附近柜机"
        hover-class="support-row-hover"
        @click="goNearby"
      >
        <view class="support-icon support-icon--pin" aria-hidden="true" />
        <view class="support-main">
          <text class="support-label">附近柜机</text>
          <text class="support-value">按距离找可开门的柜</text>
        </view>
        <view class="support-action">去找柜</view>
      </view>
    </view>

    <view class="card">
      <text class="card-title">常见问题</text>
      <view
        v-for="(item, idx) in faqs"
        :key="item.q"
        class="faq-item"
        role="button"
        :aria-expanded="openIdx === idx ? 'true' : 'false'"
        :aria-label="item.q"
        @click="toggle(idx)"
      >
        <view class="faq-head">
          <text class="faq-q">{{ item.q }}</text>
          <view
            class="faq-toggle app-icon app-icon--chevron"
            :class="{ 'is-down': openIdx === idx }"
            aria-hidden="true"
          />
        </view>
        <text v-if="openIdx === idx" class="faq-a">{{ item.a }}</text>
      </view>
    </view>

    <view class="card tip-card">
      <text class="tip-title">账单有疑问？</text>
      <text class="tip-body"
        >可在「订单详情」或购物结果页提交申诉。审核通过后会退回余额或原支付渠道，通常 24
        小时内处理。</text
      >
      <app-button label="查看我的订单" aria-label="查看我的订单" @click="goOrders" />
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { consumerApi } from '@/utils/consumer-api';
import { showError, showSuccess } from '@/utils/notify';

const supportPhoneDisplay = ref('400-888-0018');
const supportPhoneDial = ref('4008880018');
const supportEmail = ref('');
const openIdx = ref<number | null>(0);

const faqs = [
  {
    q: '怎么开门购物？',
    a: '扫描柜门二维码，完成实名与免密/余额准备后即可开门。取完商品关上门，系统自动识别并扣款。'
  },
  {
    q: '如何扣款？会不会多扣？',
    a: '优先使用微信/支付宝免密；未开通时可使用账户余额兜底。关门后识别取走商品并结算；若有疑问可提交账单申诉申请退款。'
  },
  {
    q: '如何申请退款？',
    a: '进入「我的订单」→ 订单详情。若该柜机开启自助退款，可点「立即退款」；否则请点「申请退款/申诉」，运营核对录像通过后原路或退回余额。'
  },
  {
    q: '余额怎么充值？',
    a: '在「我的」或「账户充值」选择微信/支付宝充值即可。'
  },
  {
    q: '柜机打不开或关不上怎么办？',
    a: '可先重试扫码；仍异常请使用「故障报修」提交柜机编号与问题描述，或拨打客服热线。'
  },
  {
    q: '优惠券怎么用？',
    a: '购物结算时系统会自动选用可用优惠券。券面会标注门槛、有效期与适用柜范围；即将过期的券会在「我的优惠券」中提示。'
  },
  {
    q: '积分怎么获得和兑换？',
    a: '支付成功后按会员等级倍率返积分。可在「积分明细」查看有效期，在「积分兑换」换券时注意门槛与适用柜说明。'
  },
  {
    q: '附近没有柜机怎么办？',
    a: '可在「附近柜机」扩大搜索半径，或直接扫描柜门二维码；离线/停售柜会标注状态，请选择在线可开门的柜。'
  }
];

onShow(async () => {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    const phone = String(cfg?.servicePhone || cfg?.['consumer.service_phone'] || '').trim();
    if (phone) {
      supportPhoneDisplay.value = phone;
      supportPhoneDial.value = phone.replaceAll(/[^\d+]/g, '');
    }
    const email = String(cfg?.supportEmail || cfg?.['ops.support_email'] || '').trim();
    if (email) supportEmail.value = email;
  } catch {
    /* keep defaults */
  }
});

function toggle(idx: number) {
  openIdx.value = openIdx.value === idx ? null : idx;
}

function callSupport() {
  uni.makePhoneCall({
    phoneNumber: supportPhoneDial.value,
    fail: () => showError(`请拨打 ${supportPhoneDisplay.value}`)
  });
}

function copySupportEmail() {
  const email = supportEmail.value;
  if (!email) return;
  uni.setClipboardData({
    data: email,
    success: () => showSuccess('邮箱已复制'),
    fail: () => showError(email)
  });
}

function goAnnouncements() {
  uni.navigateTo({ url: '/pages/announcements/announcements' });
}

function goFeedback() {
  uni.navigateTo({ url: '/pages/feedback/feedback' });
}

function goReport() {
  uni.navigateTo({ url: '/pages/report/report' });
}

function goMessages() {
  uni.navigateTo({ url: '/pages/messages/messages' });
}

function goNearby() {
  uni.navigateTo({ url: '/pages/nearby/nearby' });
}

function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}
</script>

<style scoped>
.page {
  min-height: 100%;
  padding: 0 var(--page-gutter) calc(var(--spacing-lg) * 2);
  box-sizing: border-box;
  background: var(--color-bg-card, #ffffff);
}
.card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card, 24rpx);
  padding: 24rpx;
  margin: 0 0 16rpx;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.04);
}
.card-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--text-primary, #14201b);
  margin-bottom: 8rpx;
}
.support-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 22rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f3f4f6);
  box-sizing: border-box;
  width: 100%;
}
.support-row:last-child {
  border-bottom: none;
}
.support-row-hover {
  opacity: 0.85;
}
.support-icon {
  flex: 0 0 64rpx;
  width: 64rpx;
  height: 64rpx;
  border-radius: 50%;
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #0f766e);
  position: relative;
  box-sizing: border-box;
}
.support-icon::before {
  content: '';
  position: absolute;
  inset: 0;
  margin: auto;
  background-color: currentColor;
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  -webkit-mask-position: center;
  mask-position: center;
  -webkit-mask-size: 28rpx 28rpx;
  mask-size: 28rpx 28rpx;
}
.support-icon--phone::before {
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6A19.79 19.79 0 0 1 2.12 4.18 2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.81.36 1.6.7 2.35a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.75.34 1.54.57 2.35.7A2 2 0 0 1 22 16.92z'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6A19.79 19.79 0 0 1 2.12 4.18 2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.81.36 1.6.7 2.35a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.75.34 1.54.57 2.35.7A2 2 0 0 1 22 16.92z'/%3E%3C/svg%3E");
}
.support-icon--mail::before {
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Crect x='2' y='4' width='20' height='16' rx='2'/%3E%3Cpath d='m22 7-10 7L2 7'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Crect x='2' y='4' width='20' height='16' rx='2'/%3E%3Cpath d='m22 7-10 7L2 7'/%3E%3C/svg%3E");
}
.support-icon--notice::before {
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M4 22h16'/%3E%3Cpath d='M15 2H9v12h6V2z'/%3E%3Cpath d='M9 14h6v8H9z'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M4 22h16'/%3E%3Cpath d='M15 2H9v12h6V2z'/%3E%3Cpath d='M9 14h6v8H9z'/%3E%3C/svg%3E");
}
.support-icon--chat::before {
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z'/%3E%3C/svg%3E");
}
.support-icon--wrench::before {
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z'/%3E%3C/svg%3E");
}
.support-icon--bell::before {
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9'/%3E%3Cpath d='M13.73 21a2 2 0 0 1-3.46 0'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9'/%3E%3Cpath d='M13.73 21a2 2 0 0 1-3.46 0'/%3E%3C/svg%3E");
}
.support-icon--pin::before {
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z'/%3E%3Ccircle cx='12' cy='10' r='3'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z'/%3E%3Ccircle cx='12' cy='10' r='3'/%3E%3C/svg%3E");
}
.support-main {
  /* 0 基准 + 可伸缩：避免 H5/小程序里内容宽度把中间列压成「客…」 */
  flex: 1 1 0%;
  min-width: 0;
  max-width: 100%;
}
.support-label {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary, #14201b);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.support-value {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #64748b);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.support-action {
  flex: 0 0 auto;
  color: var(--color-link, var(--brand, #0f766e));
  font-size: var(--font-size-body);
  font-weight: 600;
  text-align: right;
  white-space: nowrap;
}
.support-row:active .support-action {
  opacity: 0.72;
}
.faq-item {
  padding: 20rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f3f4f6);
}
.faq-item:last-child {
  border-bottom: none;
}
.faq-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16rpx;
  width: 100%;
  box-sizing: border-box;
}
.faq-q {
  flex: 1 1 0;
  min-width: 0;
  font-size: var(--font-size-md);
  color: var(--text-primary, #14201b);
  font-weight: 600;
  line-height: 1.4;
}
.faq-toggle {
  flex: 0 0 48rpx;
  width: 48rpx;
  height: 48rpx;
  color: var(--color-link, var(--brand, #0f766e));
  font-size: var(--font-size-md);
}
.faq-toggle.app-icon--chevron {
  transform: rotate(-45deg);
  transition: transform 0.18s ease;
}
.faq-toggle.app-icon--chevron.is-down {
  transform: rotate(45deg);
}
.faq-a {
  display: block;
  margin-top: 14rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #64748b);
  line-height: 1.6;
}
.tip-card {
  background: color-mix(in srgb, var(--warning, #b45309) 8%, var(--white));
  border: 1rpx solid color-mix(in srgb, var(--warning, #b45309) 28%, var(--white));
}
.tip-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--warning, #b45309);
}
.tip-body {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-caption);
  color: var(--warning, #b45309);
  line-height: 1.55;
  opacity: 0.9;
}
.tip-card :deep(.app-btn) {
  margin-top: 20rpx;
}
</style>
