<template>
  <div class="page-pager">
    <el-pagination v-if="hydrated" v-bind="$attrs" />
    <span v-else class="page-pager__hint" aria-live="polite">加载中…</span>
  </div>
</template>

<script setup lang="ts">
defineOptions({ inheritAttrs: false });

withDefaults(
  defineProps<{
    /** 首次列表加载完成后再展示 total，避免「共 0 条」闪现 */
    hydrated?: boolean;
  }>(),
  { hydrated: true }
);
</script>

<style scoped>
.page-pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  min-height: 40px;
  padding-bottom: 8px;
  overflow: visible;
  position: relative;
  z-index: 2;
  flex-shrink: 0;
  isolation: isolate;
  background: var(--layout-card, var(--el-bg-color, #fff));
  box-sizing: border-box;
}
.page-pager__hint {
  color: var(--layout-muted, var(--el-text-color-secondary));
  font-size: 13px;
}
</style>
