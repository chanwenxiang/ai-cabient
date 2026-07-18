<template>
  <view class="empty-state" :class="{ compact }">
    <text v-if="icon" class="empty-icon">{{ icon }}</text>
    <text class="empty-title">{{ title }}</text>
    <text v-if="hint" class="empty-hint">{{ hint }}</text>
    <view v-if="$slots.default" class="empty-actions">
      <slot />
    </view>
  </view>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  title: string;
  hint?: string;
  icon?: string;
  compact?: boolean;
}>(), {
  hint: '',
  icon: '',
  compact: false
});
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 64rpx 32rpx;
}
.empty-state.compact {
  padding: 36rpx 16rpx;
}
.empty-icon {
  font-size: 64rpx;
  line-height: 1;
  margin-bottom: 12rpx;
}
.empty-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #64748b;
}
.empty-hint {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #94a3b8;
  line-height: 1.5;
}
.empty-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12rpx;
  margin-top: 20rpx;
  width: 100%;
}
</style>
