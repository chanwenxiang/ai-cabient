<template>
  <view>
    <view v-if="showDock" class="action-dock">
      <app-button
        v-if="!linesConfirmed"
        variant="outline"
        data-testid="replenish-confirm-lines"
        :disabled="submitting || !hasLines"
        label="确认商品与数量"
        @click="$emit('confirm-lines')"
      />
      <app-button
        data-testid="replenish-complete"
        :disabled="submitting || !hasLines || !linesConfirmed"
        :label="pullOff ? '确认全部下架' : '确认全部上架'"
        @click="$emit('complete')"
      />
    </view>
    <view v-if="completed" class="complete-banner">
      {{
        pullOff
          ? '任务已完成，下架库存已同步更新'
          : '任务已完成，商品库存和在途状态已同步更新'
      }}
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
.action-dock :deep(.app-btn) {
  margin-top: 0;
}
.complete-banner {
  margin-top: 22rpx;
  padding: 22rpx;
  border-radius: 18rpx;
  color: var(--brand-deep, #166534);
  background: var(--brand-soft, #dcfce7);
  text-align: center;
  font-size: var(--font-size-caption);
}
</style>
