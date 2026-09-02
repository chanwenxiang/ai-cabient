<template>
  <view class="empty-state" :class="{ compact }">
    <image v-if="icon && icon.startsWith('/')" class="empty-icon" :src="icon" mode="aspectFit" />
    <text v-else-if="icon" class="empty-icon">{{ icon }}</text>
    <text class="empty-title">{{ title }}</text>
    <text v-if="hint" class="empty-hint">{{ hint }}</text>
    <view v-if="$slots.default" class="empty-actions">
      <slot />
    </view>
  </view>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string;
    hint?: string;
    icon?: string;
    compact?: boolean;
  }>(),
  {
    hint: '',
    icon: '',
    compact: false
  }
);
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 80rpx 40rpx;
}
.empty-state.compact {
  padding: 48rpx 24rpx;
}
.empty-icon {
  width: 88rpx;
  height: 88rpx;
  margin-bottom: 16rpx;
  border-radius: 24rpx;
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #047857);
  font-size: 36rpx;
  font-weight: 700;
  line-height: 88rpx;
  text-align: center;
}
.empty-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #223029;
  text-align: center;
}
.empty-hint {
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #849087;
  line-height: 1.5;
  text-align: center;
}
.empty-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  /* 微信小程序对 flex gap 支持不稳，用相邻 margin 保证间距 */
  gap: 16rpx;
  margin-top: 28rpx;
  width: 100%;
  box-sizing: border-box;
}
.empty-actions > *:not(:first-child) {
  margin-top: 16rpx;
}
/* uni 插槽按钮可能落在组件根下：保证空态竖排间距与通栏 */
:deep(.empty-btn + .empty-btn),
:deep(uni-button.empty-btn + uni-button.empty-btn),
:deep(button.empty-btn + button.empty-btn) {
  margin-top: 24rpx !important;
}
:deep(.empty-btn),
:deep(uni-button.empty-btn) {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  margin-left: 0 !important;
  margin-right: 0 !important;
  align-self: stretch !important;
  height: 88rpx !important;
  min-height: 88rpx !important;
}
</style>
