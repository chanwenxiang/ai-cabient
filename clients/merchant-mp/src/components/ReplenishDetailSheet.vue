<template>
  <!-- 兼容旧名：统一走 AppSheet -->
  <AppSheet
    :visible="visible"
    :aria-label="ariaLabel"
    :body-scroll="bodyScroll"
    @close="emit('close')"
  >
    <slot />
  </AppSheet>
</template>

<script setup lang="ts">
import AppSheet from '@/components/AppSheet.vue';

withDefaults(
  defineProps<{
    visible: boolean;
    ariaLabel?: string;
    /**
     * 透传 AppSheet：true=面板整体滚动（默认，普通 view overflow-y，微信里会被遮罩
     * touchmove 拦截导致划不动）；false=面板 flex 列、由插槽内 scroll-view 滚动（修复
     * 长内容划不动，参照 disputes 详情做法）。补货详情内容长，调用方传 false。
     */
    bodyScroll?: boolean;
  }>(),
  { ariaLabel: '补货详情', bodyScroll: true }
);

const emit = defineEmits<{ close: [] }>();
</script>
