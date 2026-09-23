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

    <!-- 纵横滚动都收在本容器内（壳 .table-scroll 放行 visible）：
         ① 壳一旦自己横滚，上面的工具行与下面的分页行会随表体一起左移；
         ② 内滚收在这里，「表头 th」与「表体 td」的最近滚动容器才同源 ⇒ 右侧操作列两侧一致吸附。
         高度上限由 calcMaxHeight 写进本容器的 max-height。
         🔴 切勿改回把 max-height 交给 el-table：EP 的 body-wrapper/el-scrollbar__wrap 会因此
            另立滚动上下文，表体 td 的最近滚动容器变成「宽度=表格总宽」的纵向滚动容器，
            其 sticky right:0 只能钉在表格右缘＝自然位置 ⇒ 操作列不吸附，且与表头不一致。
         见 main.css「.crud-table__table」段落。 -->
    <div
      class="crud-table__table"
      :style="tableMaxHeight ? { maxHeight: `${tableMaxHeight}px` } : undefined"
    >
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
              @action="(key: string) => onDataAction(key, row)"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!--
      🔴 这里**不再**有「通用原始表的写入口」弹窗（即工具行的「新增」与行内的「编辑」）。

      2026-09-23 决策：通用原始表（`manage-table`）的写入口对任何表都「加上去也没用」——
      行内数据一律由业务侧的正规弹窗（新建设备 / 新建订单…）维护，绕过业务校验直接写原始列
      只会造脏行；编辑侧唯一存在的价值是排障时改一行，而这可以用 psql 做，
      不值得为此在 8 个业务页面上常驻一个能写任意列的入口。
      删除侧**保留**（能力由后端 capabilities 推导，见 canDataDelete），
      因为它对应「运营确实要清理一条脏记录」这个真实动作。
      ⚠️ 后端 /ops/admin/data/{schema,row} 与 POST/PUT 仍保留为 API，只是前端不再有入口 ——
         所以回归判据是「产物里不再出现这两个按钮文案」，而不是「接口 404」。
    -->

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
  ref,
  watch,
  type ComponentPublicInstance
} from 'vue';
import type { TableInstance } from 'element-plus';
import { CaretBottom, CaretTop, Delete, Refresh } from '@element-plus/icons-vue';
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
    /**
     * 通用数据管理表名（传入即在行操作内建 删除/批量删除，挂 ops:data:manage）。
     * 只读 + 删：原始表的写入口已撤掉（2026-09-23 决策，见模板顶部说明）。
     */
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
    // 只留删除：编辑/新增已按 2026-09-23 决策撤掉（见模板顶部说明）
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

const sortFieldLabel = computed(() => props.sortFieldLabel || table.sortProp);
const showMetaBar = computed(
  () => Boolean(table.sortProp) || props.selectable || props.showRefresh || Boolean(csvCtl)
);
const hasActions = computed(() => typeof props.actions === 'function');

function onDataAction(key: string, row: any) {
  if (key === 'data-delete') void dataDelete(row);
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
/**
 * 表格纵向滚动归谁 —— 两种模式都是完整实现，切换只改这一处。
 *
 * · 'page'（**当前产品决策**，2026-09-23）：不设 max-height、取消吸附 ⇒
 *   表格按内容展开，整页一起滚动，表头随页面滚走、分页排在表格之后。
 *   产品原话：「他的表头不固定，而且可以整页都可以动」—— 要的就是这个观感，
 *   并且参照「数据一致性」页（那页因主区放不下，一直是这个模式）。
 *   有意接受的代价：滚到下面看不见列名，也看不见排序/刷新按钮；翻页要滚到页面底部。
 * · 'table'：表格内滚 + 工具行/分页行吸附（方案 B，同日早些时候落地）。
 *   一屏内永远有列名与分页，但数据区被锁在主区剩余高度里
 *   （实测 1440×900 只有 402px ⇒ 可见 7 行，这正是「显示区域太小」的来源）。
 *
 * 🔴 calcMaxHeight 的整套算法（连同 MIN_FILL_HEIGHT）**只在 'table' 模式有意义**：
 *    它是为「吸附行不盖表体」逐像素实测出来的。'page' 模式下不要再设 max-height，
 *    否则立刻退回「小窗口内滚」，与产品要求相反。
 */
const VSCROLL_OWNER: 'page' | 'table' = 'page';
const MIN_FILL_HEIGHT = 240;
const rootRef = ref<HTMLElement>();
const tableMaxHeight = ref<number>();
/** 填充模式：表格吃满主区剩余高度并启用吸附行；false = 文档流模式（'page' 恒为此态） */
const fillMode = ref(false);
function calcMaxHeight() {
  // 'page' 模式：整页滚，表格按内容展开 —— 不设 max-height 即天然走这里
  if (VSCROLL_OWNER === 'page') {
    fillMode.value = false;
    tableMaxHeight.value = undefined;
    return;
  }
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
  /* 纵向内边距收到最小：这一行与分页行都是「表格的自己人」，
     它们的上下空白同样从数据区高度里扣（见 calcMaxHeight 的 used 累加）。 */
  padding: 3px 0 5px;
  margin-bottom: 5px;
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
  padding: 3px 0 2px;
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
.crud-table__csv-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
