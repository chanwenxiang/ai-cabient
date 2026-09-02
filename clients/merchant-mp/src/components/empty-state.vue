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
  padding: 48rpx 32rpx;
}
.empty-state.compact {
  padding: 28rpx 16rpx;
}
.empty-icon {
  width: 104rpx;
  height: 104rpx;
  border-radius: 28rpx;
  background: var(--brand-tint, #ccfbf1);
  color: var(--brand, #0f766e);
  font-size: 52rpx;
  font-weight: 700;
  line-height: 104rpx;
  margin-bottom: 16rpx;
}
.empty-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #64748b;
  text-align: center;
}
.empty-hint {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #94a3b8;
  line-height: 1.5;
  text-align: center;
}
.empty-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
  margin-top: 20rpx;
  width: 100%;
}
.empty-actions > *:not(:first-child) {
  margin-top: 16rpx;
}
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
  height: 88rpx;
  min-height: 88rpx;
}
</style>
