<template>
  <view class="empty-state" :class="[{ compact }, kindClass]">
    <image
      v-if="resolvedIcon.startsWith('/')"
      class="empty-icon app-icon app-icon--circle"
      :src="resolvedIcon"
      mode="aspectFit"
    />
    <text
      v-else-if="resolvedIcon"
      class="empty-icon app-icon app-icon--circle"
      aria-hidden="true"
    >{{ resolvedIcon }}</text>
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
const kindClass = computed(() => (props.kind && props.kind !== 'default' ? `kind-${props.kind}` : ''));
</script>

<script lang="ts">
export default { name: 'EmptyState' };
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
  padding: 64rpx 0 24rpx;
}
.empty-state.compact {
  padding: 40rpx 0 16rpx;
}
.empty-icon {
  width: 88rpx;
  height: 88rpx;
  margin: 0 auto 16rpx;
  border-radius: 50%;
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #0f766e);
  font-size: var(--font-size-display-sm);
  font-weight: 700;
  line-height: 88rpx;
  text-align: center;
  align-self: center;
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
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #0f766e);
}
.kind-wallet .empty-icon {
  background: #fce7f3;
  color: #be185d;
}
.empty-title {
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #14201b);
  text-align: center;
  align-self: center;
}
.empty-hint {
  margin-top: 10rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #64748b);
  line-height: 1.5;
  text-align: center;
  align-self: center;
}
.empty-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
  margin-top: 28rpx;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}
.empty-state :deep(.app-btn + .app-btn),
:deep(.app-btn + .app-btn) {
  margin-top: 16rpx;
}
:deep(.app-btn) {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
}
</style>
