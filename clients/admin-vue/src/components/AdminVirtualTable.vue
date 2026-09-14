<template>
  <div
    class="admin-virtual-table"
    :class="{
      'admin-virtual-table--bordered': bordered,
      'admin-virtual-table--resizing': resizing
    }"
    :style="{ height: heightCss }"
    data-testid="admin-virtual-table"
  >
    <el-auto-resizer>
      <template #default="{ height: h, width: w }">
        <el-table-v2
          :columns="resolvedColumns"
          :data="data"
          :width="w"
          :height="h"
          :row-height="rowHeight"
          :header-height="headerHeight"
          :row-key="rowKey"
          v-bind="$attrs"
        />
      </template>
    </el-auto-resizer>
    <div
      v-show="resizing"
      class="admin-virtual-table__resize-proxy"
      :style="{ left: `${resizeProxyLeft}px` }"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue';
import { ElCheckbox, type Column } from 'element-plus';

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
    /** 左侧多选列（导出选中等）；不固定，随横向滚动 */
    selectable?: boolean;
    selectedKeys?: Array<string | number>;
    /** 行列分割线（对齐 el-table border） */
    bordered?: boolean;
    /** 分割线可拖动调列宽（table-v2 无内建，自行实现） */
    resizable?: boolean;
    /** 未指定 align 时的默认对齐 */
    defaultAlign?: 'left' | 'center' | 'right';
  }>(),
  {
    height: 'min(560px, calc(100svh - 280px))',
    rowHeight: 48,
    headerHeight: 44,
    rowKey: 'id',
    selectable: false,
    selectedKeys: () => [],
    bordered: true,
    resizable: true,
    defaultAlign: 'center'
  }
);

const emit = defineEmits<{
  'update:selectedKeys': [keys: Array<string | number>];
  selectionChange: [rows: any[]];
}>();

const heightCss = computed(() =>
  typeof props.height === 'number' ? `${props.height}px` : props.height
);

/** 用户拖拽后的列宽覆盖 */
const widthOverrides = ref<Record<string, number>>({});
const resizing = ref(false);
const resizeProxyLeft = ref(0);

function resolveRowKey(row: any): string | number {
  if (typeof props.rowKey === 'function') return props.rowKey(row);
  return row?.[props.rowKey] as string | number;
}

function columnKey(col: Column<any>, fallbackIndex: number): string {
  if (col.key != null) return String(col.key);
  if (col.dataKey != null) return String(col.dataKey);
  return `__col_${fallbackIndex}`;
}

function columnWidth(col: Column<any>, key: string): number {
  const override = widthOverrides.value[key];
  if (override != null) return override;
  return Number(col.width) || 100;
}

const selectedSet = computed(() => new Set(props.selectedKeys.map(String)));

const allKeysOnPage = computed(() =>
  (props.data || []).map((row) => resolveRowKey(row)).filter((k) => k != null && k !== '')
);

const allSelected = computed(
  () =>
    allKeysOnPage.value.length > 0 &&
    allKeysOnPage.value.every((k) => selectedSet.value.has(String(k)))
);

const someSelected = computed(
  () => !allSelected.value && allKeysOnPage.value.some((k) => selectedSet.value.has(String(k)))
);

function emitKeys(keys: Array<string | number>) {
  emit('update:selectedKeys', keys);
  const set = new Set(keys.map(String));
  const rows = (props.data || []).filter((row) => set.has(String(resolveRowKey(row))));
  emit('selectionChange', rows);
}

function toggleAll(checked: boolean | string | number) {
  const on = checked === true || checked === 'true';
  emitKeys(on ? [...allKeysOnPage.value] : []);
}

function toggleRow(row: any, checked: boolean | string | number) {
  const on = checked === true || checked === 'true';
  const key = resolveRowKey(row);
  const next = new Set(props.selectedKeys.map(String));
  if (on) next.add(String(key));
  else next.delete(String(key));
  const keys = [...next].map((k) => {
    const n = Number(k);
    return Number.isFinite(n) && String(n) === k ? n : k;
  });
  emitKeys(keys);
}

function startColumnResize(key: string, startWidth: number, e: MouseEvent) {
  if (!props.resizable) return;
  e.preventDefault();
  e.stopPropagation();
  const startX = e.clientX;
  const tableEl = (e.currentTarget as HTMLElement)?.closest(
    '.admin-virtual-table'
  ) as HTMLElement | null;
  const tableLeft = tableEl?.getBoundingClientRect().left ?? 0;
  const minW = key === '__selection__' ? 44 : 64;
  resizing.value = true;
  resizeProxyLeft.value = e.clientX - tableLeft;

  const onMove = (ev: MouseEvent) => {
    const next = Math.max(minW, startWidth + (ev.clientX - startX));
    widthOverrides.value = { ...widthOverrides.value, [key]: next };
    resizeProxyLeft.value = ev.clientX - tableLeft;
  };
  const onUp = () => {
    resizing.value = false;
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    document.body.style.cursor = '';
    document.body.style.userSelect = '';
  };
  document.body.style.cursor = 'col-resize';
  document.body.style.userSelect = 'none';
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
}

function wrapHeader(
  col: Column<any>,
  key: string,
  width: number,
  inner: (() => ReturnType<typeof h> | string) | null
) {
  return () =>
    h('div', { class: 'avt-header' }, [
      h('div', { class: 'avt-header__label' }, [
        inner ? inner() : col.title != null ? String(col.title) : ''
      ]),
      props.resizable
        ? h('span', {
            class: 'avt-col-resizer',
            title: '拖动调整列宽',
            onMousedown: (e: MouseEvent) => startColumnResize(key, width, e)
          })
        : null
    ]);
}

const selectionColumnBase = computed<Column<any>>(() => ({
  key: '__selection__',
  width: 52,
  align: 'center',
  headerCellRenderer: () =>
    h(ElCheckbox, {
      modelValue: allSelected.value,
      indeterminate: someSelected.value,
      'onUpdate:modelValue': toggleAll,
      ariaLabel: '全选本页'
    }),
  cellRenderer: ({ rowData }) =>
    h(ElCheckbox, {
      modelValue: selectedSet.value.has(String(resolveRowKey(rowData))),
      'onUpdate:modelValue': (v: boolean | string | number) => toggleRow(rowData, v),
      ariaLabel: '选择行'
    })
}));

const resolvedColumns = computed(() => {
  const source = props.selectable
    ? [selectionColumnBase.value, ...props.columns]
    : [...props.columns];
  return source.map((col, index) => {
    const key = columnKey(col, index);
    const width = columnWidth(col, key);
    const align = col.align ?? props.defaultAlign;
    const originalHeader = col.headerCellRenderer;
    const { fixed: _dropFixed, ...rest } = col as Column<any> & { fixed?: unknown };
    return {
      ...rest,
      key,
      width,
      align,
      fixed: undefined,
      headerCellRenderer: wrapHeader(col, key, width, () => {
        if (typeof originalHeader === 'function') {
          return originalHeader({
            column: col,
            columns: source,
            columnIndex: index,
            headerIndex: 0
          } as any);
        }
        return col.title != null ? String(col.title) : '';
      })
    } as Column<any>;
  });
});
</script>

<style scoped>
.admin-virtual-table {
  position: relative;
  width: 100%;
  min-height: 240px;
  border: 1px solid var(--el-border-color-lighter, var(--layout-border));
  border-radius: var(--el-border-radius-base);
  overflow: hidden;
  background: var(--el-bg-color);
  --el-table-border-color: var(--layout-border, var(--el-border-color));
  --el-table-border: 1px solid var(--el-table-border-color);
}

.admin-virtual-table--bordered :deep(.el-table-v2__header-row),
.admin-virtual-table--bordered :deep(.el-table-v2__row) {
  border-bottom: var(--el-table-border);
}

.admin-virtual-table--bordered :deep(.el-table-v2__header-cell),
.admin-virtual-table--bordered :deep(.el-table-v2__row-cell) {
  border-right: var(--el-table-border);
}

.admin-virtual-table--bordered :deep(.el-table-v2__header-cell:last-child),
.admin-virtual-table--bordered :deep(.el-table-v2__row-cell:last-child) {
  border-right: none;
}

.admin-virtual-table--resizing {
  cursor: col-resize;
}

.admin-virtual-table__resize-proxy {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 0;
  border-left: 1px dashed var(--el-color-primary);
  z-index: 5;
  pointer-events: none;
}

.avt-header {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
}

.avt-header__label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avt-col-resizer {
  position: absolute;
  top: 0;
  right: -4px;
  width: 8px;
  height: 100%;
  cursor: col-resize;
  z-index: 3;
}

.avt-col-resizer:hover,
.admin-virtual-table--resizing .avt-col-resizer {
  background: color-mix(in srgb, var(--el-color-primary) 35%, transparent);
}
</style>
