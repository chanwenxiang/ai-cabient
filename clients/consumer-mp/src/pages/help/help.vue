<template>
  <view class="page">
    <view class="hero">
      <text class="hero-sub">常见问题与客服入口，快速解决购物疑问</text>
    </view>

    <view class="card">
      <text class="card-title">联系客服</text>
      <view class="support-row" @click="callSupport">
        <view>
          <text class="support-label">客服热线</text>
          <text class="support-value">{{ supportPhoneDisplay }}</text>
        </view>
        <text class="support-action">拨打</text>
      </view>
      <view class="support-row" @click="goAnnouncements">
        <view>
          <text class="support-label">平台公告</text>
          <text class="support-value">维护通知、活动与规则变更</text>
        </view>
        <text class="support-action">去查看</text>
      </view>
      <view class="support-row" @click="goFeedback">
        <view>
          <text class="support-label">在线留言</text>
          <text class="support-value">意见反馈，运营将跟进回复</text>
        </view>
        <text class="support-action">去反馈</text>
      </view>
      <view class="support-row" @click="goReport">
        <view>
          <text class="support-label">柜机故障</text>
          <text class="support-value">打不开门、关不上门等</text>
        </view>
        <text class="support-action">去报修</text>
      </view>
    </view>

    <view class="card">
      <text class="card-title">常见问题</text>
      <view v-for="(item, idx) in faqs" :key="item.q" class="faq-item" @click="toggle(idx)">
        <view class="faq-head">
          <text class="faq-q">{{ item.q }}</text>
          <text class="faq-toggle">{{ openIdx === idx ? '−' : '+' }}</text>
        </view>
        <text v-if="openIdx === idx" class="faq-a">{{ item.a }}</text>
      </view>
    </view>

    <view class="card tip-card">
      <text class="tip-title">账单有疑问？</text>
      <text class="tip-body">可在「订单详情」或购物结果页提交申诉。审核通过后会退回余额或原支付渠道，通常 24 小时内处理。</text>
      <button class="tip-btn" hover-class="btn-hover" @click="goOrders">查看我的订单</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { consumerApi } from '@/utils/consumer-api';

const supportPhoneDisplay = ref('400-888-0018');
const supportPhoneDial = ref('4008880018');
const openIdx = ref<number | null>(0);

const faqs = [
  {
    q: '怎么开门购物？',
    a: '扫描柜门二维码（或手动输入柜机编号），完成实名与免密/余额准备后即可开门。取完商品关上门，系统自动识别并扣款。'
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
    a: '在「我的」或「账户充值」页选择微信/支付宝充值。正式环境仅展示真实支付；开发构建才可能出现模拟充值。'
  },
  {
    q: '柜机打不开或关不上怎么办？',
    a: '可先重试扫码；仍异常请使用「故障报修」提交柜机编号与问题描述，或拨打客服热线。'
  },
  {
    q: '优惠券怎么用？',
    a: '购物结算时系统会自动选用可用优惠券。具体规则以活动页说明为准。'
  }
];

onShow(async () => {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    const phone = String(cfg?.servicePhone || cfg?.['consumer.service_phone'] || '').trim();
    if (phone) {
      supportPhoneDisplay.value = phone;
      supportPhoneDial.value = phone.replace(/[^\d+]/g, '');
    }
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
    fail: () => uni.showToast({ title: `请拨打 ${supportPhoneDisplay.value}`, icon: 'none' })
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

function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  box-sizing: border-box;
  background: linear-gradient(180deg, #ecfdf5 0%, #f7f7f7 28%);
}
.hero {
  padding: 8rpx 8rpx 20rpx;
}
.hero-sub {
  display: block;
  font-size: 26rpx;
  color: #6b7280;
  line-height: 1.5;
}
.card {
  background: #fff;
  border-radius: 20rpx;
  padding: 24rpx;
  margin-bottom: 20rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.04);
}
.card-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #111827;
  margin-bottom: 8rpx;
}
.support-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22rpx 0;
  border-bottom: 1rpx solid #f3f4f6;
}
.support-row:last-child {
  border-bottom: none;
}
.support-label {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f2937;
}
.support-value {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #6b7280;
}
.support-action {
  flex-shrink: 0;
  margin-left: 16rpx;
  color: #059669;
  font-size: 26rpx;
  font-weight: 600;
  white-space: nowrap;
}
.faq-item {
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f3f4f6;
}
.faq-item:last-child {
  border-bottom: none;
}
.faq-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16rpx;
}
.faq-q {
  flex: 1;
  font-size: 28rpx;
  color: #111827;
  font-weight: 600;
  line-height: 1.4;
}
.faq-toggle {
  color: #059669;
  font-size: 32rpx;
  line-height: 1;
}
.faq-a {
  display: block;
  margin-top: 14rpx;
  font-size: 24rpx;
  color: #4b5563;
  line-height: 1.6;
}
.tip-card {
  background: linear-gradient(135deg, #fff7ed, #fff);
  border: 1rpx solid #fed7aa;
}
.tip-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #9a3412;
}
.tip-body {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #9a3412;
  line-height: 1.55;
  opacity: 0.9;
}
.tip-btn {
  margin-top: 20rpx;
  height: 80rpx;
  line-height: 80rpx;
  border-radius: 44rpx;
  background: linear-gradient(135deg, #059669, #0d9488);
  color: #fff;
  font-size: 28rpx;
  font-weight: 600;
  border: none;
  box-shadow: 0 8rpx 20rpx rgba(5, 150, 105, 0.2);
}
.tip-btn::after { border: none; }
.btn-hover {
  opacity: 0.88;
}
</style>
