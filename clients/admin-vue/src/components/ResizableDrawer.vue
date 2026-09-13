<template>
  <el-drawer
    v-model="open"
    append-to-body
    :size="`${width}px`"
    :class="['resizable-drawer-panel', attrsClass]"
    v-bind="drawerAttrs"
  >
    <template v-if="slots.header" #header>
      <slot name="header" />
    </template>
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
    <template v-if="slots.footer" #footer>
      <slot name="footer" />
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, useAttrs, useSlots } from 'vue';
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
const slots = useSlots();
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
  /* 禁止 EP 宽度 transition，避免松手/同步 :size 时「自己变宽」的动画感 */
  transition: none !important;
}
.resizable-drawer-panel .el-drawer__body {
  /* 与 main.css 一致：常驻纵向滚动条 + gutter，点击滑块不挤内容 */
  overflow-x: hidden;
  overflow-y: scroll;
  scrollbar-gutter: stable;
  overflow-anchor: none;
}

/*
 * 拖左缘改宽：冻结内部重排与过渡，避免工作台下半（调整明细 / 表格）每帧抖动。
 * 松手后移除 .is-resizing 再让 EP 表格正常测宽。
 */
.resizable-drawer-panel.is-resizing,
.resizable-drawer-panel.is-resizing .el-drawer__body,
.resizable-drawer-panel.is-resizing .resizable-drawer-body {
  transition: none !important;
  animation: none !important;
}
.resizable-drawer-panel.is-resizing .el-drawer__body {
  overflow: hidden !important;
  pointer-events: none;
  /* 拖宽期间隔离布局，减轻栅格/表格连锁 reflow 传到可视下半区 */
  contain: layout style;
}
.resizable-drawer-panel.is-resizing .el-table__body-wrapper,
.resizable-drawer-panel.is-resizing .el-table__header-wrapper,
.resizable-drawer-panel.is-resizing .table-scroll {
  overflow: hidden !important;
  pointer-events: none;
}
.resizable-drawer-panel.is-resizing .el-table,
.resizable-drawer-panel.is-resizing .el-table__inner-wrapper {
  table-layout: fixed !important;
  width: 100% !important;
  min-width: 0 !important;
  max-width: 100% !important;
}
.resizable-drawer-panel.is-resizing .workbench-grid {
  /* 拖宽时勿用 minmax 反复改列宽，稳定两栏比例 */
  grid-template-columns: 1fr 1.15fr !important;
}
</style>
