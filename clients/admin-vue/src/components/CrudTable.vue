<template>
  <div ref="rootRef" class="crud-table" :class="{ 'crud-table--fill': fillMode }">
    <div v-if="$slots.toolbar" class="crud-table__toolbar">
      <slot name="toolbar" />
    </div>

    <!-- 吸附工具行：排序切换 / 已选提示 / 导入导出 / 刷新，随表格滚动钉在可视区顶部 -->
    <div v-if="showMetaBar" class="crud-table__meta">
      <div class="crud-table__meta-left">
        <template v-if="table.sortProp">
          <!-- 中文标签不加空格（「按会话编号」）；写成 `按 {{ … }}` 会在「按」后留一个半角空格 -->
          <span class="crud-table__meta-label">按{{ sortFieldLabel }}</span>
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
          <el-button link type="primary" size="small" @click="table.clearSelection()"
            >清空</el-button
          >
        </span>
      </div>
      <div class="crud-table__meta-right">
        <el-button
          v-if="manageTable && canDataManage && canDataDelete && table.hasSelection"
          size="small"
          type="danger"
          plain
          @click="dataBatchDelete()"
          >批量删除</el-button
        >
        <el-button
          v-if="manageTable && canDataManage"
          size="small"
          type="primary"
          plain
          @click="dataCreate()"
          >新增数据</el-button
        >
        <template v-if="csvCtl">
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
        </template>
        <el-button
          v-if="showRefresh"
          size="small"
          :icon="Refresh"
          :loading="table.loading"
          @click="table.refresh()"
          >刷新</el-button
        >
      </div>
    </div>

    <!-- 横向滚动收在本容器内（壳 .table-scroll 放行 visible）：
         否则壳一横滚，上面的工具行与下面的分页行会随表体一起左移 -->
    <div class="crud-table__table">
      <el-table
        ref="tableRef"
        v-loading="table.loading"
        :max-height="tableMaxHeight"
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
              @action="(key: string) => onDataAction(key, row)"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 吸附分页行：表格过长时分页钉在可视区底部，无需滚到底换页 -->
    <el-dialog
      v-model="jsonDialog.visible"
      :title="jsonDialog.mode === 'edit' ? '编辑数据（JSON）' : '新增数据（JSON）'"
      width="640px"
      destroy-on-close
    >
      <!-- 键名口径必须写在眼前：编辑框预填的是**数据库列名**（与后端列校验同源）；
           用接口 DTO 的驼峰名保存会被逐个判「未知列」 -->
      <p class="crud-table__json-hint">{{ jsonDialog.hint }}</p>
      <div v-loading="jsonDialog.loading" class="crud-table__json-body">
        <el-input v-model="jsonDialog.text" type="textarea" :rows="14" spellcheck="false" />
      </div>
      <template #footer>
        <el-button @click="jsonDialog.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="jsonDialog.busy"
          :disabled="jsonDialog.loading"
          @click="dataSave"
          >保存</el-button
        >
      </template>
    </el-dialog>

    <div class="crud-table__pager">
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
  </div>
</template>

<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
  type ComponentPublicInstance
} from 'vue';
import type { TableInstance } from 'element-plus';
import { CaretBottom, CaretTop, Delete, EditPen, Refresh } from '@element-plus/icons-vue';
import PagePager from '@/components/PagePager.vue';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { ElMessage, ElMessageBox } from 'element-plus';

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
    /** 通用数据管理表名（传入即在行操作/工具行内建 删除/批量删除/编辑/新增，挂 ops:data:manage） */
    manageTable?: string;
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
    showRefresh: true,
    manageTable: ''
  }
);

const emit = defineEmits<{ action: [{ key: string; row: any }] }>();

defineOptions({ inheritAttrs: false, name: 'CrudTable' });

const table = props.table as any;

const tableRef = ref<TableInstance>();
onMounted(() => {
  calcMaxHeight();
  window.addEventListener('resize', calcMaxHeight);
});
onBeforeUnmount(() => window.removeEventListener('resize', calcMaxHeight));
watch(
  () => table.items,
  () => requestAnimationFrame(calcMaxHeight)
);
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
  const list: TableAction[] = props.actions
    ? props.actions(row).filter((a) => a && a.key && hasAnyPerm(a.perm))
    : [];
  if (canDataManage.value && props.manageTable) {
    list.push({
      key: 'data-edit',
      label: '编辑数据',
      icon: EditPen,
      type: 'primary',
      overflow: true
    });
    // 删除可能被下游外键挡住（会话 / 订单 / 主数据等）⇒ 后端不开放删除时，干脆不渲染该入口
    if (canDataDelete.value) {
      list.push({
        key: 'data-delete',
        label: '删除数据',
        icon: Delete,
        type: 'danger',
        overflow: true
      });
    }
  }
  return list;
}

// —— 通用数据管理（ops:data:manage）——
const canDataManage = computed(() => auth.hasPerm('ops:data:manage'));

/**
 * 删除能力：由后端按 pg_constraint 推导（删除可能被下游外键挡住 ⇒ 不开放删除），
 * 前端不硬编码受保护表清单（手写清单必漂）。拉取失败按「不可删除」保守处理 ——
 * 宁可少给一个入口，也不给一个注定失败的入口。
 */
let deleteCapsPromise: Promise<Record<string, boolean>> | null = null;
function loadDeleteCaps(): Promise<Record<string, boolean>> {
  if (!deleteCapsPromise) {
    deleteCapsPromise = api
      .request<Record<string, boolean>>(AdminEndpoints.dataCapabilities, 'GET')
      .catch(() => ({}) as Record<string, boolean>);
  }
  return deleteCapsPromise;
}
const canDataDelete = ref(false);
watch(
  [() => props.manageTable, canDataManage],
  async ([tbl, manage]) => {
    if (!tbl || !manage) {
      canDataDelete.value = false;
      return;
    }
    const caps = await loadDeleteCaps();
    canDataDelete.value = caps[tbl] === true;
  },
  { immediate: true }
);
/** 后端列元数据（GET /ops/admin/data/schema/{table}） */
interface AdminColumnMeta {
  name: string;
  type: string;
  nullable: boolean;
  hasDefault: boolean;
  primaryKey: boolean;
  /** 必填（NOT NULL 且无默认值且非 identity）—— 由后端单一事实源算出，前端不自行推导 */
  required: boolean;
}

const jsonDialog = reactive({
  visible: false,
  mode: 'edit' as 'edit' | 'create',
  id: '',
  text: '',
  hint: '',
  busy: false,
  loading: false
});

/**
 * 行主键取值。useCrudTable 的 rowKey 是**函数**（rowKey: row => row.id），
 * 直接写成 `row[table.rowKey || props.rowKey]` 会把函数当属性名 —— 取到 undefined，
 * 于是删除/编辑都请求到 `/ops/admin/data/<表>/`（没有 id），后端只能回 404「资源不存在」。
 * 这里按「函数 → 字段名 → id」依次取值，取不到就返回空串（调用方 fail-closed）。
 */
function rowId(row: any): string {
  const key = table.rowKey ?? props.rowKey;
  if (typeof key === 'function') return String(key(row) ?? '');
  if (typeof key === 'string' && key) return String(row?.[key] ?? '');
  return String(row?.id ?? '');
}

async function dataDelete(row: any) {
  const id = rowId(row);
  if (!id) {
    ElMessage.error('无法确定该行主键，已取消删除');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认删除 ${props.manageTable}.${id}？其可级联的下游数据会一并删除，且不可恢复。`,
      '删除数据',
      { type: 'warning', confirmButtonText: '删除' }
    );
  } catch {
    return;
  }
  try {
    await api.request(AdminEndpoints.dataDelete(props.manageTable, id), 'DELETE');
    ElMessage.success('已删除');
    await table.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

async function dataBatchDelete() {
  const rows = table.pickSelected(table.displayItems);
  if (!rows.length) return;
  try {
    await ElMessageBox.confirm(
      `确认删除选中的 ${rows.length} 行？其可级联的下游数据会一并删除，且不可恢复。`,
      '批量删除',
      {
        type: 'warning',
        confirmButtonText: '删除'
      }
    );
  } catch {
    return;
  }
  let ok = 0;
  let noKey = 0;
  const failures: string[] = [];
  for (const row of rows) {
    const id = rowId(row);
    if (!id) {
      noKey++;
      continue;
    }
    try {
      await api.request(AdminEndpoints.dataDelete(props.manageTable, id), 'DELETE');
      ok++;
    } catch (e) {
      // 不静默吞错：留下首条失败原因，避免「已删除 N 行」掩盖真实失败
      if (failures.length === 0) failures.push(e instanceof Error ? e.message : '删除失败');
    }
  }
  const failed = rows.length - ok - noKey;
  const tail = [
    failed ? `失败 ${failed} 行${failures.length ? `（${failures[0]}）` : ''}` : '',
    noKey ? `无主键 ${noKey} 行` : ''
  ]
    .filter(Boolean)
    .join('，');
  const summary = `已删除 ${ok} 行${tail ? `，${tail}` : ''}`;
  if (failed || noKey) {
    ElMessage.warning(summary);
  } else {
    ElMessage.success(summary);
  }
  await table.load();
}

/**
 * 编辑数据：预填必须用**数据库列名** —— 后端列校验走 information_schema，只认 snake_case。
 * 早先用列表 DTO（camelCase）预填，保存时每个键都判「未知列」⇒ 400，
 * 等于「编辑数据」这个大路上任何表都必然失败（实测 PUT {sessionId,…} → 400 未知列：sessionId）。
 * 故改为现拉该行的原始列值（GET /row/{table}/{id}），做到「看到的键就是能保存的键」。
 */
function dataEdit(row: any) {
  const id = rowId(row);
  if (!id) {
    ElMessage.error('无法确定该行主键，已取消编辑');
    return;
  }
  jsonDialog.mode = 'edit';
  jsonDialog.id = id;
  jsonDialog.text = '';
  jsonDialog.hint =
    '键名必须是数据库列名（与表结构一致）；数字 / 时间 / 布尔可写成字符串，保存时按列类型转换。';
  jsonDialog.loading = true;
  jsonDialog.visible = true;
  void (async () => {
    try {
      const detail = await api.request<Record<string, unknown>>(
        AdminEndpoints.dataRow(props.manageTable, id),
        'GET'
      );
      jsonDialog.text = JSON.stringify(detail, null, 2);
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '读取该行数据失败');
      jsonDialog.visible = false;
    } finally {
      jsonDialog.loading = false;
    }
  })();
}

/**
 * 新增数据：用列元数据生成骨架。「必填」由后端算好（{@code required}），前端**不自行推导** ——
 * 曾自己判过一版（误以为主键一律不必填），于是模板漏掉主键列（{@code exception_id} 这类
 * 应用侧赋值的 varchar 主键 NOT NULL 无默认）⇒ 点保存必 400「缺少必填字段」。
 * 必填列一律先给 null 占位：给 0/'' 之类「合法默认值」会顺手插出一条脏行，
 * 给 null 则得到可读的 400，逼调用方填真值。
 */
function dataCreate() {
  jsonDialog.mode = 'create';
  jsonDialog.id = '';
  jsonDialog.text = '';
  jsonDialog.hint = '正在读取表结构…';
  jsonDialog.loading = true;
  jsonDialog.visible = true;
  void (async () => {
    try {
      const cols = await api.request<AdminColumnMeta[]>(
        AdminEndpoints.dataSchema(props.manageTable),
        'GET'
      );
      const required = cols.filter((c) => c.required);
      const tpl: Record<string, null> = {};
      for (const c of required) tpl[c.name] = null;
      jsonDialog.text = JSON.stringify(tpl, null, 2);
      const names = cols.map((c) => (c.required ? `${c.name}*` : c.name)).join(', ');
      jsonDialog.hint = required.length
        ? `本表列（* 为必填，已用 null 占位，请替换成具体值）：${names}`
        : `本表无必填列（均有默认值），留 {} 即插入一行默认值。列：${names}`;
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '读取表结构失败');
      jsonDialog.visible = false;
    } finally {
      jsonDialog.loading = false;
    }
  })();
}

async function dataSave() {
  let body: Record<string, unknown>;
  try {
    body = JSON.parse(jsonDialog.text);
  } catch {
    ElMessage.error('JSON 格式不合法');
    return;
  }
  jsonDialog.busy = true;
  try {
    if (jsonDialog.mode === 'edit') {
      await api.request(AdminEndpoints.dataUpdate(props.manageTable, jsonDialog.id), 'PUT', body);
    } else {
      await api.request(AdminEndpoints.dataCreate(props.manageTable), 'POST', body);
    }
    ElMessage.success('已保存');
    jsonDialog.visible = false;
    await table.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    jsonDialog.busy = false;
  }
}

const sortFieldLabel = computed(() => props.sortFieldLabel || table.sortProp);
const showMetaBar = computed(
  () => Boolean(table.sortProp) || props.selectable || props.showRefresh || Boolean(csvCtl)
);
const hasActions = computed(() => typeof props.actions === 'function');

function onDataAction(key: string, row: any) {
  if (key === 'data-delete') void dataDelete(row);
  else if (key === 'data-edit') dataEdit(row);
  else emit('action', { key, row });
}

// —— 表格内滚：给 el-table 设可用最大高度，页面（el-main）不再滚动，
//    工具行/分页行天然常驻可见，不再依赖 position: sticky（超宽表格页与壳横滚互斥的问题就此消除）
//
// ⚠ 只减分页行是不够的：吸附工具行（.crud-table__meta）也是同层占位元素，
//   分页行又是 sticky bottom:0 —— 少扣一点，它就会吸到表体上面盖住最后几行
//   （实测少扣工具行 39+8px ⇒ 表体与分页行重叠 58px）。故把同层非表体元素全扣掉，
//   再扣主滚动区自身的 padding-bottom（否则仍差 12px 重叠）。
//
// ⚠ 也不能只扣「上方 + 同层」：**表格之后**同在主区里的内容一样占位。漏掉它，填充高度
//   会把主区撑出可滚动范围，主区一可滚，吸附元素就又会盖住表体（实测 /sessions 残留 16.4px、
//   /vision-mappings 表后还有一整张卡片 ⇒ 主区高出 289px）。
//
// ⚠ 更不能「算出来多少就至少给 240」：主区放不下时抬高到 240 等于**保证**溢出。
//   实测 /system-configs 的 avail=102.2 被抬到 240 ⇒ 多出 137.4px，而实测被吸底分页行盖住的
//   正是 137px；/dashboard avail=52.7 ⇒ 多出 186.8px，实测盖住 186.8px —— 逐位吻合。
//   故主区放不下时**退回文档流（flow 模式）**：不设 max-height、且取消吸附
//   （见 .crud-table:not(.crud-table--fill) 的样式），表体与工具行/分页行都在流里，互不覆盖。
const MIN_FILL_HEIGHT = 240;
const rootRef = ref<HTMLElement>();
const tableMaxHeight = ref<number>();
/** 填充模式：表格吃满主区剩余高度并启用吸附行；false = 文档流模式（主区放不下时） */
const fillMode = ref(false);
function calcMaxHeight() {
  const rootEl = rootRef.value;
  const main = rootEl?.closest('.layout-main-scroll') as HTMLElement | null;
  if (!rootEl || !main) return;
  const px = (v: string) => Number.parseFloat(v) || 0;
  // 主区已下滚时 getBoundingClientRect 的差会少算 scrollTop，补回来才是真实偏移
  const top =
    rootEl.getBoundingClientRect().top - main.getBoundingClientRect().top + main.scrollTop;
  let used = 0;
  for (const el of Array.from(rootEl.children) as HTMLElement[]) {
    // 表体自己不算占位；其余（页面工具栏 / 吸附工具行 / 分页行）都要扣
    if (el.classList.contains('crud-table__table')) continue;
    const cs = getComputedStyle(el);
    // 弹层（el-dialog 的 .el-overlay 也渲染在本容器内）是 fixed 全屏遮罩，不是占位元素：
    // 若在弹窗打开时重算，整屏高度会被当成占位 ⇒ 表格被压到最小值
    if (cs.position === 'fixed' || cs.position === 'absolute') continue;
    if (el.getBoundingClientRect().height === 0) continue;
    used +=
      el.getBoundingClientRect().height +
      (Number.parseFloat(cs.marginTop) || 0) +
      (Number.parseFloat(cs.marginBottom) || 0);
  }
  const padBottom = px(getComputedStyle(main).paddingBottom);
  // 「表格之后同在主区里的内容」必须沿 DOM 逐层累加，且一层都不能漏：
  //  · 根自身的下外边距（它的 border box 之后还有一段属于它）
  //  · 父容器的下内边距 / 下边框（排在最后一个子元素之后，同样占位）
  //  · 每层里根之后的流式兄弟（高度 + 上下外边距）
  // 🔴 不能用 main.scrollHeight − 组件底 来求 —— scrollHeight 会被**下限钳到 clientHeight**，
  //   内容不满一屏时会凭空多出一段「剩余空白」，代数化简后 avail 恒等于「当前表体高度」，
  //   于是填充高度自我循环：本来放得下的页面（实测 /fund-bills、/notifications）被误判成放不下。
  // 实测漏掉「父层下内边距/边框」这一项，/sessions 会在填充模式下仍残留 16px 纵滚
  //   （主区内容 10+785.6+24 = 819.6 > 804，而根自己的 margin 与后续兄弟都是 0）。
  let spaceAfter = 0;
  let node: HTMLElement | null = rootEl;
  while (node && node !== main) {
    spaceAfter += px(getComputedStyle(node).marginBottom);
    const parentEl: HTMLElement | null = node.parentElement;
    if (parentEl && parentEl !== main) {
      const pcs = getComputedStyle(parentEl);
      spaceAfter += px(pcs.paddingBottom) + px(pcs.borderBottomWidth);
    }
    let sib: HTMLElement | null = node.nextElementSibling as HTMLElement | null;
    while (sib) {
      const cs = getComputedStyle(sib);
      if (cs.position === 'fixed' || cs.position === 'absolute') {
        sib = sib.nextElementSibling as HTMLElement | null;
        continue;
      }
      const h = sib.getBoundingClientRect().height;
      if (h > 0) spaceAfter += h + px(cs.marginTop) + px(cs.marginBottom);
      sib = sib.nextElementSibling as HTMLElement | null;
    }
    node = parentEl;
  }
  const avail = main.clientHeight - Math.max(top, 0) - used - spaceAfter - padBottom;
  if (avail >= MIN_FILL_HEIGHT) {
    fillMode.value = true;
    tableMaxHeight.value = Math.round(avail);
  } else {
    fillMode.value = false;
    tableMaxHeight.value = undefined;
  }
}

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
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
/* 吸附工具行：页面滚动时钉在主滚动区顶部，排序/导出/刷新始终可见 */
.crud-table__meta {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  background: var(--layout-card, var(--el-bg-color, #fff));
  padding: 6px 0 8px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--layout-border, var(--el-border-color-light));
}
.crud-table__meta-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.crud-table__meta-right {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
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
/* 吸附分页行：长表格滚动时钉在主滚动区底部 */
.crud-table__pager {
  position: sticky;
  bottom: 0;
  z-index: 20;
  background: var(--layout-card, var(--el-bg-color, #fff));
  padding: 6px 0 4px;
  border-top: 1px solid var(--layout-border, var(--el-border-color-light));
}
/*
 * 文档流模式（主区放不下整张表，见脚本里的 MIN_FILL_HEIGHT）：
 * 取消吸附。吸附元素是「悬浮」在内容之上的，一旦页面还得纵滚，
 * 吸底分页行就会横压在表体上 —— 那正是这套样式的原始缺陷。
 * 退回流式后表体与工具行/分页行互不覆盖，只是要跟着页面滚。
 */
.crud-table:not(.crud-table--fill) .crud-table__meta,
.crud-table:not(.crud-table--fill) .crud-table__pager {
  position: static;
}
/* 对话框里的列名/口径提示：长列清单要能换行，别把对话框撑宽 */
.crud-table__json-hint {
  margin: 0 0 10px;
  color: var(--layout-muted, var(--el-text-color-secondary));
  font-size: var(--admin-font-size-table, 13px);
  line-height: 1.6;
  word-break: break-word;
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
