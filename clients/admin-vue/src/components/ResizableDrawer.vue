<template>
  <el-drawer
    v-model="open"
    :size="`${width}px`"
    :class="['resizable-drawer-panel', attrsClass]"
    v-bind="drawerAttrs"
  >
    <div class="resizable-drawer-shell">
      <hr
        class="resizable-drawer-resize"
        aria-orientation="vertical"
        aria-label="拖动调整抽屉宽度"
        title="拖动调整宽度"
        @pointerdown="onResizeStart"
      />
      <div class="resizable-drawer-body">
        <slot />
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, useAttrs } from 'vue';
import { useResizableDrawer } from '@/composables/useResizableDrawer';

defineOptions({ inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    /** sessionStorage 键 */
    storageKey: string;
    defaultWidth?: number;
    minWidth?: number;
    maxWidth?: number;
  }>(),
  {
    defaultWidth: 560,
    minWidth: 420,
    maxWidth: 1200
  }
);

const open = defineModel<boolean>({ default: false });

const attrs = useAttrs();
const attrsClass = computed(() => attrs.class);
const drawerAttrs = computed(() => {
  const { class: _c, ...rest } = attrs as Record<string, unknown>;
  return rest;
});

const { width, onResizeStart } = useResizableDrawer({
  storageKey: props.storageKey,
  defaultWidth: props.defaultWidth,
  minWidth: props.minWidth,
  maxWidth: props.maxWidth
});
</script>

<style scoped>
.resizable-drawer-shell {
  position: relative;
  min-height: 100%;
  padding-left: 6px;
}
.resizable-drawer-resize {
  position: absolute;
  left: -10px;
  top: 0;
  bottom: 0;
  width: 10px;
  height: auto;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  cursor: col-resize;
  z-index: 5;
  touch-action: none;
  border-radius: 0 4px 4px 0;
}
.resizable-drawer-resize::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 30%;
  bottom: 30%;
  width: 2px;
  border-radius: 1px;
  background: var(--el-border-color);
  opacity: 0.7;
}
.resizable-drawer-resize:hover,
.resizable-drawer-resize:active {
  background: color-mix(in srgb, var(--el-color-primary) 16%, transparent);
}
.resizable-drawer-resize:hover::after,
.resizable-drawer-resize:active::after {
  background: var(--el-color-primary);
  opacity: 1;
}
.resizable-drawer-body {
  min-width: 0;
}
</style>

<!-- append-to-body 时抽屉在组件外，需非 scoped -->
<style>
.resizable-drawer-panel.el-drawer {
  overflow: visible !important;
}
.resizable-drawer-panel .el-drawer__body {
  overflow: auto;
}
.resizable-drawer-panel.is-resizing .el-table__body-wrapper,
.resizable-drawer-panel.is-resizing .el-table__header-wrapper,
.resizable-drawer-panel.is-resizing .table-scroll {
  overflow-x: hidden !important;
  pointer-events: none;
}
.resizable-drawer-panel.is-resizing .el-table {
  table-layout: fixed;
}
</style>
