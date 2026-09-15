<template>
  <!-- 兼容旧名：统一走 AppSheet -->
  <AppSheet :visible="visible" :aria-label="ariaLabel" @close="emit('close')">
    <slot />
  </AppSheet>
</template>

<script setup lang="ts">
import AppSheet from '@/components/AppSheet.vue';

withDefaults(
  defineProps<{
    visible: boolean;
    ariaLabel?: string;
  }>(),
  { ariaLabel: '补货详情' }
);

const emit = defineEmits<{ close: [] }>();
</script>
