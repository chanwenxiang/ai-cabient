<template>
  <div class="admin-virtual-table" :style="{ height: heightCss }" data-testid="admin-virtual-table">
    <el-auto-resizer>
      <template #default="{ height: h, width: w }">
        <el-table-v2
          :columns="columns"
          :data="data"
          :width="w"
          :height="h"
          :row-height="rowHeight"
          :header-height="headerHeight"
          :row-key="rowKey"
          fixed
          v-bind="$attrs"
        />
      </template>
    </el-auto-resizer>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { Column } from 'element-plus';

const props = withDefaults(
  defineProps<{
    /** el-table-v2 列定义（含 cellRenderer） */
    columns: Column<any>[];
    data: any[];
    /** 容器高度，须为定高以便 AutoResizer 量出可滚动区 */
    height?: string | number;
    rowHeight?: number;
    headerHeight?: number;
    rowKey?: string | ((row: any) => string | number);
  }>(),
  {
    height: 'min(560px, calc(100svh - 280px))',
    rowHeight: 48,
    headerHeight: 44,
    rowKey: 'id'
  }
);

const heightCss = computed(() =>
  typeof props.height === 'number' ? `${props.height}px` : props.height
);
</script>

<style scoped>
.admin-virtual-table {
  width: 100%;
  min-height: 240px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  overflow: hidden;
  background: var(--el-bg-color);
}
</style>
