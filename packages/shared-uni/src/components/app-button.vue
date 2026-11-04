<template>
  <button
    class="app-btn"
    :class="[
      `app-btn--${variant}`,
      `app-btn--${size}`,
      `app-btn--${shape}`,
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
  'primary' | 'ghost' | 'outline' | 'danger' | 'text' | 'alipay' | 'wechat' | 'soft';

/**
 * 尺寸档。`md` 是登录/表单类主按钮，`lg` 是常规行动点，`sm` 是窄按钮。
 *
 * 🔴 为什么需要它：小程序端自定义组件有**样式隔离**（`data-v-xxx` 各自独立），
 * 页面里写 `:deep(.app-btn)` 只会被编译成 `.data-v-页面 .app-btn`（后代选择器），
 * 而 `app-btn` 带的是**组件自己的**作用域标记 ⇒ 永远匹配不到。
 * 结果是页面覆写静默失效、按钮按内容收缩。凡是要改按钮外观，必须走这里的 prop，
 * 不要写 `:deep()`（lessons #288）。
 */
export type AppButtonSize = 'sm' | 'md' | 'lg';

/** 圆角形状。`pill` 全圆角，`panel` 跟随 --radius-btn。 */
export type AppButtonShape = 'pill' | 'panel';

const props = withDefaults(
  defineProps<{
    variant?: AppButtonVariant;
    size?: AppButtonSize;
    shape?: AppButtonShape;
    label?: string;
    block?: boolean;
    compact?: boolean;
    disabled?: boolean;
    loading?: boolean;
    ariaLabel?: string;
  }>(),
  {
    variant: 'primary',
    size: 'lg',
    shape: 'panel',
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
  position: relative;
  margin: 0;
  min-height: 88rpx;
  height: 88rpx;
  /* 横向可证下限：按钮本体靠 padding 撑宽，判据量不出；88rpx 是热区阈值本身，
     内容更宽时不受影响（sm/text 等变体也继承这一条） */
  min-width: 88rpx;
  padding: 0 36rpx;
  border-radius: var(--radius-btn, 16rpx);
  font-size: 34rpx;
  font-weight: 500;
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
/* MA-156（2026-11-04）：sm/compact 本体 72rpx < 88rpx 热区阈值 ⇒ 伪元素四周扩 8rpx
 * （72+16=88，touch-target 棘轮按「尺寸+2×hit」计）。compact 是历史别名，仍受支持。 */
.app-btn--sm::after,
.app-btn--compact::after {
  content: '';
  position: absolute;
  inset: -8rpx;
}
/* size 档位：sm 72rpx / md 88rpx / lg 保持基线 88rpx。compact 是历史别名，仍受支持。 */
.app-btn--sm {
  min-height: 72rpx;
  height: 72rpx;
  font-size: var(--font-size-body);
  padding: 0 28rpx;
}
.app-btn--md {
  min-height: 88rpx;
  height: 88rpx;
}
.app-btn--lg {
  min-height: 88rpx;
  height: 88rpx;
}
/* shape 档位：pill 全圆角（登录等主行动点），panel 跟随设计令牌。 */
.app-btn--pill {
  border-radius: var(--radius-pill);
}
.app-btn--panel {
  border-radius: var(--radius-btn, 16rpx);
}
.app-btn--compact {
  min-height: 72rpx;
  height: 72rpx;
  font-size: var(--font-size-body);
  padding: 0 28rpx;
}
.app-btn--primary {
  background: var(--brand, #0d9488);
  color: var(--white, #ffffff);
  box-shadow: none;
}
.app-btn--ghost {
  background: var(--brand-soft, #f0fdfa);
  color: var(--brand, #0d9488);
  border-color: rgba(13, 148, 136, 0.18);
}
.app-btn--outline {
  background: var(--card-bg, #fff);
  color: var(--brand, #0d9488);
  border-color: var(--color-border, rgba(13, 148, 136, 0.35));
}
.app-btn--danger {
  background: var(--danger, #b91c1c);
  color: var(--white, #ffffff);
}
.app-btn--text {
  position: relative;
  /* 热区/视觉分离：视觉尺寸不变，可点范围纵向 ≥88rpx；min-width 让横向可证 */
  min-width: 88rpx;
  background: transparent;
  color: var(--color-link, var(--brand, #0d9488));
  min-height: 64rpx;
  height: auto;
  padding: 8rpx 12rpx;
  box-shadow: none;
  font-weight: 600;
}

.app-btn--text::after {
  content: '';
  position: absolute;
  inset: -12rpx 0;
}
.app-btn--alipay {
  background: linear-gradient(135deg, var(--brand-alipay, #1677ff), #4096ff);
  color: var(--white, #ffffff);
  box-shadow: 0 8rpx 20rpx rgba(22, 119, 255, 0.28);
}
.app-btn--wechat {
  background: linear-gradient(135deg, var(--brand-wx, #07c160), #06ae56);
  color: var(--white, #ffffff);
  box-shadow: 0 8rpx 20rpx rgba(7, 193, 96, 0.28);
}
.app-btn--soft {
  background: var(--brand-soft, #f0fdfa);
  color: var(--brand-deep, #115e59);
  border-color: rgba(13, 148, 136, 0.16);
}
.app-btn.is-disabled {
  opacity: 0.55;
}
.app-btn-hover {
  opacity: 0.88;
}
</style>
