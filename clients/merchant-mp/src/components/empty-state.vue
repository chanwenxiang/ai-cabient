<template>
  <view class="empty-state" :class="[{ compact }, kindClass]">
    <image
      v-if="resolvedIcon.startsWith('/')"
      class="empty-icon"
      :src="resolvedIcon"
      mode="aspectFit"
    />
    <text v-else-if="resolvedIcon" class="empty-icon" aria-hidden="true">{{ resolvedIcon }}</text>
    <text class="empty-title">{{ resolvedTitle }}</text>
    <text v-if="resolvedHint" class="empty-hint">{{ resolvedHint }}</text>
    <view v-if="$slots.default" class="empty-actions">
      <slot />
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';

export type EmptyKind = 'default' | 'orders' | 'devices' | 'alerts' | 'search' | 'wallet';

const KIND_PRESETS: Record<EmptyKind, { title: string; hint: string; icon: string }> = {
  default: { title: '暂无数据', hint: '', icon: '∅' },
  orders: { title: '暂无订单', hint: '调整筛选条件后再试', icon: '单' },
  devices: { title: '暂无柜机', hint: '确认账号已分配柜机后再刷新', icon: '柜' },
  alerts: { title: '暂无待办', hint: '当前没有需要处理的事项', icon: '办' },
  search: { title: '未找到结果', hint: '试试更换关键词', icon: '搜' },
  wallet: { title: '暂无流水', hint: '有资金变动后会显示在这里', icon: '账' }
};

const props = withDefaults(
  defineProps<{
    title?: string;
    hint?: string;
    icon?: string;
    compact?: boolean;
    /** 业务空态类型，可与 title/hint/icon 叠加覆盖 */
    kind?: EmptyKind;
  }>(),
  {
    title: '',
    hint: '',
    icon: '',
    compact: false,
    kind: 'default'
  }
);

const preset = computed(() => KIND_PRESETS[props.kind] || KIND_PRESETS.default);
const resolvedTitle = computed(() => props.title || preset.value.title);
const resolvedHint = computed(() =>
  props.hint !== undefined && props.hint !== '' ? props.hint : preset.value.hint
);
const resolvedIcon = computed(() => props.icon || preset.value.icon);
const kindClass = computed(() => `kind-${props.kind}`);
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 48rpx 32rpx;
}
.empty-state.compact {
  padding: 28rpx 16rpx;
}
.empty-icon {
  width: 104rpx;
  height: 104rpx;
  border-radius: 28rpx;
  background: var(--brand-tint, #ccfbf1);
  color: var(--brand, #0f766e);
  font-size: 52rpx;
  font-weight: 700;
  line-height: 104rpx;
  margin-bottom: 16rpx;
}
.kind-alerts .empty-icon {
  background: #fef3c7;
  color: #b45309;
}
.kind-orders .empty-icon {
  background: #e0f2fe;
  color: #0369a1;
}
.kind-devices .empty-icon {
  background: #ecfdf5;
  color: #047857;
}
.kind-wallet .empty-icon {
  background: #fce7f3;
  color: #be185d;
}
.empty-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #64748b;
  text-align: center;
}
.empty-hint {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #94a3b8;
  line-height: 1.5;
  text-align: center;
}
.empty-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
  margin-top: 20rpx;
  width: 100%;
}
.empty-actions > *:not(:first-child) {
  margin-top: 16rpx;
}
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
  height: 88rpx;
  min-height: 88rpx;
}
</style>
