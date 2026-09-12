<template>
  <view class="error-state" :class="{ compact }">
    <text class="error-icon app-icon app-icon--circle" aria-hidden="true">!</text>
    <text class="error-title">{{ title }}</text>
    <text v-if="hint" class="error-hint">{{ hint }}</text>
    <app-button
      v-if="showRetry"
      :label="retryText"
      :loading="retrying"
      aria-label="重试"
      @click="$emit('retry')"
    />
    <view v-if="$slots.default" class="error-actions">
      <slot />
    </view>
  </view>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string;
    hint?: string;
    showRetry?: boolean;
    retryText?: string;
    retrying?: boolean;
    compact?: boolean;
  }>(),
  {
    title: '加载失败',
    hint: '请检查网络后重试',
    showRetry: true,
    retryText: '重试',
    retrying: false,
    compact: false
  }
);

defineEmits<{ retry: [] }>();
</script>

<script lang="ts">
export default { name: 'ErrorState' };
</script>

<style scoped>
.error-state {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  text-align: center;
  padding: 48rpx 0 24rpx;
}
.error-state.compact {
  padding: 28rpx 0 16rpx;
}
.error-icon {
  width: 88rpx;
  height: 88rpx;
  margin: 0 auto 16rpx;
  border-radius: 50%;
  background: color-mix(in srgb, var(--danger, var(--color-danger)) 12%, var(--white));
  color: var(--danger, var(--color-danger));
  font-size: var(--font-size-h1);
  font-weight: 700;
  line-height: 88rpx;
  text-align: center;
  align-self: center;
}
.error-title {
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #14201b);
  text-align: center;
  align-self: center;
}
.error-hint {
  margin-top: 10rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #64748b);
  line-height: 1.5;
  text-align: center;
  align-self: center;
}
.error-state :deep(.app-btn) {
  margin-top: 28rpx;
}
.error-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
  margin-top: 20rpx;
  width: 100%;
}
</style>
