<template>
  <div class="crud-table">
    <div v-if="csvCtl" class="crud-table__toolbar">
      <div class="crud-table__toolbar-main">
        <slot name="toolbar" />
      </div>
      <div class="crud-table__toolbar-tools">
        <el-button
          v-if="canExport"
          size="small"
          :disabled="table.loading"
          @click="csvCtl.onExport()"
          >{{ table.exportButtonLabel }}</el-button
        >
        <template v-if="canImport">
          <el-button size="small" @click="csvCtl.onDownloadTemplate(csv?.templateSample)"
            >下载模板</el-button
          >
          <el-button
            v-hasPermi="csv?.importPerm ? toPermArray(csv.importPerm) : undefined"
            size="small"
            type="primary"
            plain
            :loading="csvCtl.importing.value"
            @click="csvCtl.triggerImport()"
            >导入</el-button
          >
        </template>
        <input
          :ref="bindCsvInput"
          type="file"
          accept=".csv,text/csv"
          class="crud-table__csv-input"
          aria-hidden="true"
          tabindex="-1"
          @change="csvCtl?.onImportFile($event)"
        />
      </div>
    </div>

    <!-- 排序切换 / 已选提示 / 刷新：全站统一交互行 -->
    <div v-if="showMetaBar" class="crud-table__meta">
      <div class="crud-table__meta-left">
        <template v-if="table.sortProp">
          <span class="crud-table__meta-label">按 {{ sortFieldLabel }}</span>
          <el-button-group size="small">
            <el-button
              :type="table.sortDir === 'asc' ? 'primary' : 'default'"
              :icon="CaretTop"
              @click="setSortDir('asc')"
              >升序</el-button
            >
            <el-button
              :type="table.sortDir === 'desc' ? 'primary' : 'default'"
              :icon="CaretBottom"
              @click="setSortDir('desc')"
              >降序</el-button
            >
          </el-button-group>
        </template>
        <span v-if="selectable && table.hasSelection" class="crud-table__selection-hint">
          已选 {{ table.selectedKeys.length }} 项
          <el-button link type="primary" size="small" @click="table.clearSelection()">清空</el-button>
        </span>
      </div>
      <el-button
        v-if="showRefresh"
        size="small"
        :icon="Refresh"
        :loading="table.loading"
        @click="table.refresh()"
        >刷新</el-button
      >
    </div>

    <el-table
      ref="tableRef"
      v-loading="table.loading"
      :data="table.displayItems"
      stripe
      border
      class="report-table"
      :row-key="rowKey || table.rowKey"
      empty-text=" "
      v-bind="$attrs"
      @selection-change="table.onSelectionChange"
    >
      <template #empty>
        <el-empty v-if="table.hydrated && !table.loading" :description="emptyText" />
      </template>
      <el-table-column
        v-if="selectable"
        type="selection"
        width="48"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <!-- 业务列由页面通过默认插槽传入（el-table-column 原样透传） -->
      <slot />
      <el-table-column
        v-if="hasActions"
        label="操作"
        fixed="right"
        align="center"
        class-name="col-action"
        :width="actionWidth"
      >
        <template #default="{ row }">
          <TableActions
            :actions="visibleActions(row)"
            :test-id-prefix="actionsTestId"
            @action="(key: string) => emit('action', { key, row })"
          />
        </template>
      </el-table-column>
    </el-table>

    <PagePager
      :hydrated="table.hydrated"
      v-model:current-page="table.page"
      v-model:page-size="table.size"
      :total="table.total"
      :page-sizes="table.pageSizes"
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="table.load()"
      @size-change="table.onSizeChange()"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, type ComponentPublicInstance } from 'vue';
import type { TableInstance } from 'element-plus';
import { CaretBottom, CaretTop, Refresh } from '@element-plus/icons-vue';
import PagePager from '@/components/PagePager.vue';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';

/**
 * 全站统一表格壳：多选 / 升降序切换（无表头箭头）/ 固定操作列 / 分页 / 空态
 * / CSV 导入导出 / 刷新 / 按钮权限 一次收口。
 * 与 useCrudTable 配套；行为调整只改本组件（+useCrudTable），所有接入页面同步生效。
 *
 * 用法（页面只写业务列与 actions 配置）：
 *   const crud = useCrudTable({ rowKey: r => r.id, fetchPage: p => api…, sort: { prop: 'id', mode: 'local' } });
 *   <CrudTable :table="crud" row-key="id" selectable :actions="rowActions" :csv="csvOpts" @action="onAction">
 *     <el-table-column prop="title" label="标题" min-width="150" />
 *   </CrudTable>
 *
 * 权限：CrudRowAction.perm（string | string[]，OR 语义，与 v-hasPermi 同源）；
 *      无 perm = 不做权限过滤；有 perm = 无权限整项隐藏。
 */
export interface CrudRowAction extends TableAction {
  /** 权限码（OR）；缺省不做权限过滤 */
  perm?: string | string[];
}

export interface CrudCsvOptions {
  filePrefix: string;
  headers: string[];
  toRows: (rows: any[]) => Array<Array<unknown>>;
  onImportRows?: (rows: Record<string, string>[]) => Promise<number>;
  /** 内置导出按钮的权限码；缺省不拦截 */
  exportPerm?: string | string[];
  /** 内置导入按钮的权限码；缺省不拦截 */
  importPerm?: string | string[];
  /** 下载模板时的示例行 */
  templateSample?: Array<unknown>;
}

const props = withDefaults(
  defineProps<{
    /** useCrudTable 返回的控制器 */
    table: object;
    rowKey?: string;
    /** 是否内建多选列 */
    selectable?: boolean;
    /** 行操作配置（渲染在 fixed=right 操作列，TableActions 呈现） */
    actions?: (row: any) => CrudRowAction[];
    actionWidth?: number;
    actionsTestId?: string;
    emptyText?: string;
    /** 内建 CSV 导出/导入；传了才渲染工具条按钮 */
    csv?: CrudCsvOptions;
    /** 排序字段展示名；缺省用 sortProp */
    sortFieldLabel?: string;
    /** 是否内建刷新按钮 */
    showRefresh?: boolean;
  }>(),
  {
    rowKey: '',
    selectable: false,
    actions: undefined,
    actionWidth: 120,
    actionsTestId: '',
    emptyText: '暂无数据',
    csv: undefined,
    sortFieldLabel: '',
    showRefresh: true
  }
);

const emit = defineEmits<{ action: [{ key: string; row: any }] }>();

defineOptions({ inheritAttrs: false, name: 'CrudTable' });

const table = props.table as any;

const tableRef = ref<TableInstance>();
// 每次拉取新数据后同步清空勾选（与既有页面行为一致：跨页不保留选择）
watch(
  () => table.items,
  () => tableRef.value?.clearSelection()
);

const auth = useAuthStore();

function toPermArray(perm: string | string[]): string[] {
  return Array.isArray(perm) ? perm : [perm];
}

function hasAnyPerm(perm?: string | string[]): boolean {
  if (!perm) return true;
  return toPermArray(perm).some((code) => auth.hasPerm(code));
}

/** 行操作：先做权限过滤（无权限整项隐藏），其余交给 TableActions 呈现 */
function visibleActions(row: any): TableAction[] {
  if (!props.actions) return [];
  return props
    .actions(row)
    .filter((a) => a && a.key && hasAnyPerm(a.perm))
    .map(({ perm: _perm, ...rest }) => rest);
}

const sortFieldLabel = computed(() => props.sortFieldLabel || table.sortProp);
const showMetaBar = computed(
  () => Boolean(table.sortProp) || props.selectable || props.showRefresh
);
const hasActions = computed(() => typeof props.actions === 'function');

function setSortDir(dir: 'asc' | 'desc') {
  if (table.sortDir !== dir) table.toggleSortDir();
}

// —— 内置 CSV 导出/导入（选中优先：勾选了就只导选中）——
const csvCtl = props.csv
  ? useListCsv({
      filePrefix: props.csv.filePrefix,
      headers: props.csv.headers,
      toRows: () => props.csv!.toRows(table.pickSelected(table.displayItems)),
      onImportRows: props.csv.onImportRows
    })
  : null;

const canExport = computed(() => Boolean(csvCtl) && hasAnyPerm(props.csv?.exportPerm));
const canImport = computed(
  () => Boolean(csvCtl && props.csv?.onImportRows) && hasAnyPerm(props.csv?.importPerm)
);

function bindCsvInput(el: Element | ComponentPublicInstance | null) {
  if (csvCtl) csvCtl.importInput.value = (el as HTMLInputElement) ?? null;
}
</script>

<style scoped>
.crud-table {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.crud-table__toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.crud-table__toolbar-main {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.crud-table__toolbar-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.crud-table__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.crud-table__meta-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.crud-table__meta-label {
  color: var(--layout-muted, var(--el-text-color-secondary));
  font-size: var(--admin-font-size-table, 13px);
}
.crud-table__selection-hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: var(--radius-tag, 8px);
  background: color-mix(in srgb, var(--app-primary, #0f766e) 10%, transparent);
  color: var(--app-primary, #0f766e);
  font-size: var(--admin-font-size-table, 13px);
}
.crud-table__csv-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
