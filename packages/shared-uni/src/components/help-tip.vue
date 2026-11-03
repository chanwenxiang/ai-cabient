<!--
  统一提示入口：规则/口径解释按需展示，不再做常驻横幅。
  label = 无障碍标签（也是弹窗标题）；content 传入则点击直接弹 uni.showModal，
  不传则只抛 tap，由页面复用既有 explain 逻辑。
-->
<template>
  <text class="help-tip" role="button" :aria-label="ariaLabel || label || '说明'" @click="onTap"
    >?</text
  >
</template>

<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    label?: string;
    content?: string;
    ariaLabel?: string;
  }>(),
  { label: '说明', content: '', ariaLabel: '' }
);
const emit = defineEmits<{ (e: 'tap'): void }>();

function onTap() {
  emit('tap');
  if (props.content) {
    uni.showModal({
      title: props.label || '说明',
      content: props.content,
      showCancel: false,
      confirmText: '知道了'
    });
  }
}
</script>

<style scoped>
.help-tip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32rpx;
  height: 32rpx;
  border-radius: 50%;
  background: var(--color-border-subtle);
  color: var(--text-muted);
  font-size: 22rpx;
  font-weight: 700;
  flex-shrink: 0;
}
</style>
