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
  padding: 80rpx 40rpx;
}
.empty-state.compact {
  padding: 48rpx 24rpx;
}
.empty-icon {
  font-size: 72rpx;
  line-height: 1;
  margin-bottom: 16rpx;
}
.empty-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #223029;
}
.empty-hint {
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #849087;
  line-height: 1.5;
}
.empty-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  margin-top: 28rpx;
  width: 100%;
}
</style>
