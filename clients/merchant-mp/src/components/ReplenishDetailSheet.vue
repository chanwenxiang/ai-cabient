<template>
  <view
    v-if="visible"
    role="button"
    aria-label="关闭"
    class="mask"
    @click.self="$emit('close')"
    @touchmove.stop.prevent
  >
    <view role="button" class="sheet" @click.stop>
      <view class="sheet-handle" />
      <slot />
    </view>
  </view>
</template>

<script setup lang="ts">
defineProps<{ visible: boolean }>();
defineEmits<{ close: [] }>();
</script>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  /* 加深遮罩，避免列表文字从弹层边缘透出 */
  background: rgba(15, 23, 42, 0.62);
}
.sheet {
  width: 100%;
  max-width: 520px;
  max-height: 88vh;
  padding: 30rpx 26rpx calc(30rpx + env(safe-area-inset-bottom));
  border-radius: var(--radius-card) 32rpx 0 0;
  background: var(--card-bg, #fff);
  overflow-y: auto;
  overscroll-behavior: contain;
  box-sizing: border-box;
  /* 实心底 + 顶部分隔，杜绝背后列表透视 */
  isolation: isolate;
  box-shadow: 0 -12rpx 40rpx rgba(15, 23, 42, 0.18);
}
.sheet-handle {
  width: 64rpx;
  height: 8rpx;
  margin: 0 auto 16rpx;
  border-radius: 4rpx;
  background: var(--text-subtle, #cbd5e1);
}
</style>
