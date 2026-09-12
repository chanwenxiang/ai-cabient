<template>
  <button
    class="app-btn"
    :class="[
      `app-btn--${variant}`,
      { 'app-btn--block': block, 'app-btn--compact': compact, 'is-disabled': disabled || loading }
    ]"
    :disabled="disabled || loading"
    :loading="loading"
    :hover-class="disabled || loading ? '' : 'app-btn-hover'"
    :aria-label="ariaLabel || undefined"
    @click="onClick"
  >
    <slot>{{ label }}</slot>
  </button>
</template>

<script setup lang="ts">
export type AppButtonVariant =
  | 'primary'
  | 'ghost'
  | 'outline'
  | 'danger'
  | 'text'
  | 'alipay'
  | 'wechat'
  | 'soft';

const props = withDefaults(
  defineProps<{
    variant?: AppButtonVariant;
    label?: string;
    block?: boolean;
    compact?: boolean;
    disabled?: boolean;
    loading?: boolean;
    ariaLabel?: string;
  }>(),
  {
    variant: 'primary',
    label: '',
    block: true,
    compact: false,
    disabled: false,
    loading: false,
    ariaLabel: ''
  }
);

const emit = defineEmits<{ click: [e: Event] }>();

function onClick(e: Event) {
  if (props.disabled || props.loading) return;
  emit('click', e);
}
</script>

<script lang="ts">
export default { name: 'AppButton' };
</script>

<style scoped>
.app-btn {
  margin: 0;
  min-height: 88rpx;
  height: 88rpx;
  padding: 0 36rpx;
  border-radius: var(--radius-pill, 999rpx);
  font-size: var(--font-size-md);
  font-weight: 600;
  line-height: 1.2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  border: 1rpx solid transparent;
}
.app-btn::after {
  border: none;
}
.app-btn--block {
  width: 100%;
  align-self: stretch;
}
.app-btn--compact {
  min-height: 72rpx;
  height: 72rpx;
  font-size: var(--font-size-body);
  padding: 0 28rpx;
}
.app-btn--primary {
  background: var(--brand, #0f766e);
  color: #fff;
  box-shadow: 0 8rpx 20rpx color-mix(in srgb, var(--brand, #0f766e) 28%, transparent);
}
.app-btn--ghost {
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #0f766e);
  border-color: color-mix(in srgb, var(--brand, #0f766e) 18%, transparent);
}
.app-btn--outline {
  background: transparent;
  color: var(--brand, #0f766e);
  border-color: color-mix(in srgb, var(--brand, #0f766e) 35%, transparent);
}
.app-btn--danger {
  background: var(--danger, #b91c1c);
  color: #fff;
}
.app-btn--text {
  background: transparent;
  color: var(--color-link, var(--brand, #0f766e));
  min-height: 64rpx;
  height: auto;
  padding: 8rpx 12rpx;
  box-shadow: none;
  font-weight: 600;
}
.app-btn--alipay {
  background: linear-gradient(135deg, #1677ff, #4096ff);
  color: #fff;
  box-shadow: 0 8rpx 20rpx rgba(22, 119, 255, 0.28);
}
.app-btn--wechat {
  background: linear-gradient(135deg, #07c160, #06ae56);
  color: #fff;
  box-shadow: 0 8rpx 20rpx rgba(7, 193, 96, 0.28);
}
.app-btn--soft {
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand-deep, #134e4a);
  border-color: color-mix(in srgb, var(--brand, #0f766e) 16%, transparent);
}
.app-btn.is-disabled {
  opacity: 0.55;
}
.app-btn-hover {
  opacity: 0.88;
}
</style>
