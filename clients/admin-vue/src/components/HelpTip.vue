<template>
  <el-tooltip
    :content="content"
    placement="top"
    :show-after="120"
    :hide-after="0"
    :disabled="!content"
    popper-class="help-tip-popper"
  >
    <button
      type="button"
      class="help-tip"
      :aria-label="ariaLabel || content"
      @click.stop
      @keydown.enter.stop
    >
      <el-icon><QuestionFilled /></el-icon>
    </button>
  </el-tooltip>
</template>

<script setup lang="ts">
import { QuestionFilled } from '@element-plus/icons-vue';

withDefaults(
  defineProps<{
    content: string;
    ariaLabel?: string;
  }>(),
  { ariaLabel: '' }
);
</script>

<style scoped>
.help-tip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  margin-left: 4px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--layout-muted);
  cursor: help;
  vertical-align: -2px;
}

.help-tip:hover,
.help-tip:focus-visible {
  color: var(--app-primary);
  outline: none;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary) 20%, transparent);
}
</style>
