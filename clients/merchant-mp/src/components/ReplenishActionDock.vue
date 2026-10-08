<template>
  <view>
    <view v-if="showDock" class="action-dock">
      <app-button
        class="dock-btn"
        v-if="!linesConfirmed"
        variant="outline"
        data-testid="replenish-confirm-lines"
        :disabled="submitting || !hasLines"
        label="确认商品与数量"
        @click="$emit('confirm-lines')"
      />
      <app-button
        class="dock-btn"
        data-testid="replenish-complete"
        :disabled="submitting || !hasLines || !linesConfirmed"
        :label="pullOff ? '确认全部下架' : '确认全部上架'"
        @click="$emit('complete')"
      />
    </view>
    <view v-if="completed" class="complete-banner">
      {{ pullOff ? '下架流程已全部完成' : '补货流程已全部完成' }}
    </view>
  </view>
</template>

<script setup lang="ts">
defineProps<{
  showDock: boolean;
  completed: boolean;
  linesConfirmed: boolean;
  hasLines: boolean;
  submitting: boolean;
  pullOff: boolean;
}>();

defineEmits<{
  'confirm-lines': [];
  complete: [];
}>();
</script>

<style scoped>
/* 非 sticky：避免滚动选择货道时底栏遮挡操作区（P0-27） */
.action-dock {
  position: relative;
  z-index: 1;
  margin-top: 22rpx;
  padding: 16rpx 0 calc(8rpx + env(safe-area-inset-bottom));
  background: var(--color-bg-card, #fff);
  border-top: 1rpx solid var(--color-border, #e2e8f0);
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
/* 🔴 原 `.action-dock :deep(.app-btn)` 在小程序端永不命中（组件样式隔离，lessons #288）。 */
.dock-btn {
  margin-top: 0;
}
.complete-banner {
  margin-top: 8rpx;
  padding: 18rpx 16rpx;
  border-radius: 18rpx;
  color: var(--brand-deep, #166534);
  background: var(--brand-soft, #dcfce7);
  text-align: center;
  font-size: var(--font-size-sm);
  font-weight: 650;
}
</style>
