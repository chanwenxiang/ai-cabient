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
  /* stretch：避免 center 导致小程序里百分比宽度参照错误、按钮比上方卡片更宽 */
  align-items: stretch;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  text-align: center;
  /* 水平 padding 交给外层 page-body，与卡片同宽 */
  padding: 64rpx 0 24rpx;
}
.empty-state.compact {
  padding: 40rpx 0 16rpx;
}
.empty-icon {
  width: 88rpx;
  height: 88rpx;
  margin: 0 auto 16rpx;
  border-radius: 24rpx;
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #047857);
  font-size: 36rpx;
  font-weight: 700;
  line-height: 88rpx;
  text-align: center;
  align-self: center;
}
.empty-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #223029;
  text-align: center;
  align-self: center;
}
.empty-hint {
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #849087;
  line-height: 1.5;
  text-align: center;
  align-self: center;
}
.empty-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  /* 勿用 > *：scoped 会编译成 >*.data-v-xxx，WXSS 不支持通配符 * */
  gap: 16rpx;
  margin-top: 28rpx;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
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
