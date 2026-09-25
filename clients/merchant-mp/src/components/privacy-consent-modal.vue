<!--
  Canonical: packages/shared-uni/src/components/privacy-consent-modal.vue
  Keep in sync (uni easycom 需本地路径). 同步：node scripts/sync-shared-uni-components.mjs
-->
<template>
  <view
    v-if="visible"
    class="privacy-mask"
    role="dialog"
    aria-modal="true"
    aria-label="隐私政策提示"
    data-testid="privacy-consent-dialog"
    @touchmove.stop.prevent
  >
    <view class="privacy-card" @click.stop>
      <text class="privacy-title">隐私政策提示</text>
      <text class="privacy-body">
        为向您提供扫码购物、订单与账户服务，我们需要按《隐私政策》处理必要信息。请阅读后继续使用。
      </text>
      <text role="button" class="privacy-link" data-testid="privacy-open-policy" @click="openPolicy"
        >查看隐私政策</text
      >
      <view class="privacy-actions">
        <button
          type="button"
          class="privacy-btn secondary"
          data-testid="privacy-decline"
          @click="onDecline"
        >
          暂不使用
        </button>
        <button
          type="button"
          class="privacy-btn primary"
          data-testid="privacy-accept"
          @click="onAccept"
        >
          同意并继续
        </button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { acceptPrivacyConsent } from '../privacy-consent';

const props = defineProps<{
  visible: boolean;
  /** 隐私政策页路径，如 /pages/policy/detail?type=privacy */
  policyUrl: string;
}>();

const emit = defineEmits<{
  accepted: [];
  declined: [];
}>();

function openPolicy() {
  if (!props.policyUrl) return;
  uni.navigateTo({ url: props.policyUrl });
}

function onAccept() {
  acceptPrivacyConsent();
  emit('accepted');
}

function onDecline() {
  emit('declined');
}
</script>

<style scoped>
.privacy-mask {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal, 10060);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48rpx;
  background: rgba(15, 23, 42, 0.55);
  box-sizing: border-box;
}
.privacy-card {
  width: 100%;
  max-width: 640rpx;
  padding: 40rpx 32rpx 28rpx;
  border-radius: var(--radius-card, 24rpx);
  background: var(--card-bg, #fff);
  box-shadow: 0 24rpx 48rpx rgba(15, 23, 42, 0.16);
}
.privacy-title {
  display: block;
  font-size: var(--font-size-xl, 32rpx);
  font-weight: 700;
  color: var(--text-primary, #0f172a);
}
.privacy-body {
  display: block;
  margin-top: 16rpx;
  font-size: var(--font-size-body, 26rpx);
  line-height: 1.55;
  color: var(--text-muted, #475569);
}
.privacy-link {
  display: inline-block;
  margin-top: 20rpx;
  color: var(--brand, #0f766e);
  font-size: var(--font-size-body, 26rpx);
  font-weight: 600;
  text-decoration: underline;
}
.privacy-actions {
  display: flex;
  gap: 16rpx;
  margin-top: 32rpx;
}
.privacy-btn {
  flex: 1;
  height: 80rpx;
  line-height: 80rpx;
  border-radius: var(--radius-control, 16rpx);
  font-size: var(--font-size-md, 28rpx);
  border: none;
  padding: 0;
}
.privacy-btn.secondary {
  background: var(--page-bg, #f8fafc);
  color: var(--text-muted, #64748b);
}
.privacy-btn.primary {
  background: var(--brand, #0f766e);
  color: #fff;
  font-weight: 600;
}
</style>
