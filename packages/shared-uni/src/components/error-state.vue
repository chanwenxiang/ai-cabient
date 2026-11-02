<template>
  <view class="error-state" :class="{ compact }">
    <text class="error-icon app-icon app-icon--circle" aria-hidden="true">!</text>
    <text class="error-title">{{ shownTitle }}</text>
    <text v-if="shownHint" class="error-hint">{{ shownHint }}</text>
    <app-button
      v-if="showRetry"
      class="error-retry"
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
import { computed } from 'vue';
const props = withDefaults(
  defineProps<{
    title?: string;
    /** 兼容写法：调用方常用 :message= 传业务原因（2026-11-02 前该属性被忽略 ⇒ 永远显示默认文案） */
    message?: string;
    hint?: string;
    showRetry?: boolean;
    retryText?: string;
    retrying?: boolean;
    compact?: boolean;
  }>(),
  {
    title: '加载失败',
    message: '',
    hint: '请检查网络后重试',
    showRetry: true,
    retryText: '重试',
    retrying: false,
    compact: false
  }
);

defineEmits<{ retry: [] }>();

/**
 * 2026-11-02：业务错误被「请检查网络后重试」带偏的根因在这里——
 * 调用方把后端原因塞进 title（如「没有已指定您为负责人的分仓…」），组件仍默认附上网络提示，
 * 用户会去查网络。规则：title 已是具体业务原因时不再追加网络提示，只有通用标题才提示。
 */
const shownTitle = computed(() =>
  props.title !== '加载失败' ? props.title : props.message || '加载失败'
);
const shownHint = computed(() =>
  props.hint === '请检查网络后重试' && shownTitle.value !== '加载失败' ? '' : props.hint
);
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
  background: #f7e4e4;
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
/* 🔴 原 `.error-state :deep(.app-btn)` 即使在本组件内也**穿透不了**另一个组件的样式隔离
 * （app-button 带自己的 data-v-xxx，lessons #288）。间距加在宿主节点上。 */
.error-retry {
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
