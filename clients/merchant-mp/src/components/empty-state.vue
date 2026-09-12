<!--
  Canonical: packages/shared-uni/src/components/empty-state.vue
  Keep in sync (uni easycom 需本地路径).
-->
<template>
  <view class="empty-state" :class="[{ compact }, kindClass]">
    <image
      v-if="resolvedIcon.startsWith('/')"
      class="empty-icon app-icon app-icon--circle"
      :src="resolvedIcon"
      mode="aspectFit"
    />
    <view
      v-else-if="useGlyph"
      class="empty-icon app-icon app-icon--circle empty-glyph"
      :class="'empty-glyph--' + (kind || 'default')"
      aria-hidden="true"
    />
    <text
      v-else-if="resolvedIcon"
      class="empty-icon app-icon app-icon--circle"
      aria-hidden="true"
      >{{ resolvedIcon }}</text
    >
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

/** icon 空字符串 = 使用 CSS 几何图形，避免 ∅ / 单汉字 */
const KIND_PRESETS: Record<EmptyKind, { title: string; hint: string; icon: string }> = {
  default: { title: '暂无数据', hint: '', icon: '' },
  orders: { title: '暂无订单', hint: '调整筛选条件后再试', icon: '' },
  devices: { title: '暂无柜机', hint: '确认账号已分配柜机后再刷新', icon: '' },
  alerts: { title: '暂无待办', hint: '当前没有需要处理的事项', icon: '' },
  search: { title: '未找到结果', hint: '试试更换关键词', icon: '' },
  wallet: { title: '暂无流水', hint: '有资金变动后会显示在这里', icon: '' }
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
const resolvedIcon = computed(() =>
  props.icon !== undefined && props.icon !== '' ? props.icon : preset.value.icon
);
const useGlyph = computed(
  () => !resolvedIcon.value || resolvedIcon.value === '∅' || resolvedIcon.value === 'glyph'
);
const kindClass = computed(() => (props.kind && props.kind !== 'default' ? `kind-${props.kind}` : ''));
</script>

<script lang="ts">
export default { name: 'EmptyState' };
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
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
  position: relative;
  box-sizing: border-box;
}
.empty-glyph::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  box-sizing: border-box;
  border: 3rpx solid currentColor;
  opacity: 0.7;
}
.empty-glyph--default::after {
  width: 28rpx;
  height: 28rpx;
  border-radius: 6rpx;
}
.empty-glyph--orders::after {
  width: 34rpx;
  height: 26rpx;
  border-radius: 4rpx;
  border-top-width: 8rpx;
}
.empty-glyph--devices::after {
  width: 30rpx;
  height: 30rpx;
  border-radius: 4rpx 4rpx 8rpx 8rpx;
}
.empty-glyph--alerts::after {
  width: 28rpx;
  height: 28rpx;
  border-radius: 50%;
  border-style: dashed;
}
.empty-glyph--search::after {
  width: 22rpx;
  height: 22rpx;
  border-radius: 50%;
  box-shadow: 10rpx 10rpx 0 -5rpx currentColor;
}
.empty-glyph--wallet::after {
  width: 34rpx;
  height: 24rpx;
  border-radius: 6rpx;
}
.kind-alerts .empty-icon {
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
  color: var(--warning, #b45309);
}
.kind-orders .empty-icon {
  background: var(--info-soft);
  color: var(--info);
}
.kind-devices .empty-icon {
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #0f766e);
}
.kind-wallet .empty-icon {
  background: var(--accent-rose-soft);
  color: var(--accent-rose);
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
  padding: 0 24rpx;
}
.empty-actions {
  margin-top: 28rpx;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  padding: 0 8rpx;
}
</style>
