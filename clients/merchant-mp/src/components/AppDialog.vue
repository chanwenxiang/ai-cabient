<template>
  <view
    v-if="visible"
    class="app-dialog-mask"
    role="dialog"
    aria-modal="true"
    :aria-label="ariaLabel || title || '对话框'"
    data-testid="app-dialog"
    @click.self="emit('close')"
    @touchmove.stop.prevent
  >
    <view role="document" class="app-dialog" @click.stop>
      <view v-if="title || showClose" class="app-dialog-head">
        <text v-if="title" class="app-dialog-title">{{ title }}</text>
        <text
          v-if="showClose"
          class="app-dialog-close"
          role="button"
          aria-label="关闭"
          @click="emit('close')"
          >×</text
        >
      </view>
      <slot />
    </view>
  </view>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    visible: boolean;
    title?: string;
    ariaLabel?: string;
    showClose?: boolean;
  }>(),
  { showClose: true }
);

const emit = defineEmits<{ close: [] }>();
</script>

<style scoped>
.app-dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 10045;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40rpx;
  background: rgba(15, 23, 42, 0.55);
  box-sizing: border-box;
}
.app-dialog {
  width: 100%;
  max-width: 620rpx;
  max-height: 80vh;
  padding: 32rpx 28rpx;
  border-radius: var(--radius-card, 24rpx);
  background: var(--card-bg, #fff);
  overflow-y: auto;
  overscroll-behavior: contain;
  box-sizing: border-box;
  box-shadow: 0 16rpx 48rpx rgba(15, 23, 42, 0.2);
}
.app-dialog-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.app-dialog-title {
  flex: 1;
  font-size: var(--font-size-title, 34rpx);
  font-weight: 600;
  color: var(--text, #0f172a);
}
.app-dialog-close {
  flex-shrink: 0;
  width: 48rpx;
  height: 48rpx;
  line-height: 48rpx;
  text-align: center;
  font-size: 36rpx;
  color: var(--text-subtle, #94a3b8);
}
</style>
