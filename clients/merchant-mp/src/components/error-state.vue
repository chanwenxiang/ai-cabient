<template>
  <view class="error-state" :class="{ compact }">
    <text class="error-icon" aria-hidden="true">!</text>
    <text class="error-title">{{ title }}</text>
    <text v-if="hint" class="error-hint">{{ hint }}</text>
    <button
      v-if="showRetry"
      class="error-retry empty-btn"
      :loading="retrying"
      aria-label="重试"
      @click="$emit('retry')"
    >
      {{ retryText }}
    </button>
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

<style scoped>
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 48rpx 32rpx;
}
.error-state.compact {
  padding: 28rpx 16rpx;
}
.error-icon {
  width: 104rpx;
  height: 104rpx;
  border-radius: 28rpx;
  background: #fee2e2;
  color: #b91c1c;
  font-size: 56rpx;
  font-weight: 700;
  line-height: 104rpx;
  margin-bottom: 16rpx;
}
.error-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #64748b;
}
.error-hint {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #94a3b8;
  line-height: 1.5;
}
.error-retry {
  margin-top: 24rpx;
  width: 100%;
  height: 88rpx;
  min-height: 88rpx;
  border: none;
  border-radius: 16rpx;
  background: var(--brand, #0f766e);
  color: #fff;
  font-size: 28rpx;
  font-weight: 600;
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
