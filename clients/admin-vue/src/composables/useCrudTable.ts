import { computed, onMounted, reactive, ref, type Ref } from 'vue';
import type { Sort } from 'element-plus';
import { ElMessage } from 'element-plus';
import { createLoadSeq } from '@/composables/createLoadSeq';
import { useTableSelection } from '@/composables/useTableSelection';
import { normalizeListPage, type ListPageLike } from '@/utils/normalize-list-page';
import { sortByPrimaryKey, type SortDirection } from '@/utils/sort-by-pk';
import { ADMIN_LIST_PAGE_SIZES, clampAdminPageSize } from '@/utils/admin-list-pager';

/**
 * 列表页统一状态机：分页 / 排序 / 多选 / 竞态防护一次收口。
 * 与 CrudTable.vue 配套使用；行为改动只改这里 + 组件，全站列表页同步生效。
 *
 * - wire 分页约定：后端 page 为 0 起（fetchPage 收到的 page 已是 0 起）；
 * - 排序：mode 'local' 页面内排序（配合 sortByPrimaryKey），'server' 把 sortProp/sortDir 透传给 fetchPage；
 * - 响应约定：{ items, total } 或纯数组（normalizeListPage 兼容）。
 */
export interface CrudPageParams {
  /** 后端页码（0 起，全站约定 page = 前端页 - 1） */
  page: number;
  size: number;
  sortProp?: string;
  sortDir?: SortDirection;
}

export interface CrudSortOptions<T = any> {
  prop: string;
  mode: 'local' | 'server';
  defaultDir?: SortDirection;
  /**
   * 置顶谓词（local 模式生效）：命中的行稳定排在最前，排序方向只影响其余行的相对顺序。
   * 典型用途：滞留会话置顶等「优先级分组 + 主键排序」。
   */
  pinned?: (row: T) => boolean;
}

export interface CrudTableOptions<T> {
  /** 行主键取值（多选 key 与 row-key 共用） */
  rowKey: (row: T) => string | number;
  /** 拉取一页；把页面筛选项合并进这里。返回 PageResult({items,total})/纯数组/可选字段形态均可 */
  fetchPage: (params: CrudPageParams) => Promise<ListPageLike<T>>;
  sort?: CrudSortOptions<T>;
  /** 默认每页条数，默认 20（上限 50，对齐 A-P1-002） */
  pageSize?: number;
  /** 组件挂载后自动加载，默认 true */
  autoLoad?: boolean;
  /** 加载失败提示文案 */
  errorMessage?: string;
}

export interface CrudTableController<T> {
  rowKey: (row: T) => string | number;
  items: T[];
  displayItems: T[];
  total: number;
  page: number;
  size: number;
  pageSizes: readonly number[];
  loading: boolean;
  hydrated: boolean;
  selectedKeys: Array<string | number>;
  hasSelection: boolean;
  exportButtonLabel: string;
  /** 排序字段（未配置排序时为空） */
  sortProp: string;
  /** 排序方向；CrudTable 工具栏按钮据此渲染升/降序 */
  sortDir: SortDirection;
  /** 翻转升降序；server 模式自动重查，local 模式由 displayItems 自动重排 */
  toggleSortDir: () => void;
  defaultSort?: { prop: string; order: 'ascending' | 'descending' };
  load: (opts?: { resetPage?: boolean }) => Promise<void>;
  refresh: () => Promise<void>;
  search: () => Promise<void>;
  onSizeChange: () => Promise<void>;
  onSortChange: (payload: Sort) => void;
  onSelectionChange: (rows: T[]) => void;
  clearSelection: () => void;
  pickSelected: (all: T[]) => T[];
}

export function useCrudTable<T>(options: CrudTableOptions<T>): CrudTableController<T> {
  const loadSeq = createLoadSeq();
  const loading = ref(false);
  const hydrated = ref(false);
  const page = ref(1);
  const size = ref(clampAdminPageSize(options.pageSize ?? 20));
  const items: Ref<T[]> = ref([]);
  const sortDir = ref<SortDirection>(options.sort?.defaultDir ?? 'asc');
  const total = ref(0);

  const { selectedKeys, onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
    useTableSelection<T>(options.rowKey);
  const hasSelection = computed(() => selectedKeys.value.length > 0);

  const displayItems = computed(() => {
    const sort = options.sort;
    if (!sort || sort.mode !== 'local') return items.value;
    const sorted = sortByPrimaryKey(items.value, sort.prop as keyof T, sortDir.value);
    const pinned = sort.pinned;
    if (!pinned) return sorted;
    const top = sorted.filter(pinned);
    // 稳定分组：置顶行保持其排序后的相对顺序，其余行紧随其后
    return top.length && top.length < sorted.length
      ? [...top, ...sorted.filter((r) => !pinned(r))]
      : sorted;
  });

  const defaultSort = computed(() => {
    const sort = options.sort;
    if (!sort) return undefined;
    return {
      prop: sort.prop,
      order: sortDir.value === 'desc' ? ('descending' as const) : ('ascending' as const)
    };
  });

  async function load(opts?: { resetPage?: boolean }) {
    if (opts?.resetPage) page.value = 1;
    const seq = loadSeq.begin();
    loading.value = true;
    clearSelection();
    try {
      const data = await options.fetchPage({
        page: page.value - 1,
        size: size.value,
        sortProp: options.sort?.prop,
        sortDir: sortDir.value
      });
      if (!loadSeq.isCurrent(seq)) return;
      const norm = normalizeListPage<T>(data);
      items.value = norm.items;
      total.value = norm.total;
    } catch (e) {
      if (!loadSeq.isCurrent(seq)) return;
      ElMessage.error(e instanceof Error ? e.message : options.errorMessage || '加载失败');
    } finally {
      if (!loadSeq.isCurrent(seq)) return;
      hydrated.value = true;
      loading.value = false;
    }
  }

  function onSortChange(payload: Sort) {
    const sort = options.sort;
    if (!sort || !payload.prop || payload.prop !== sort.prop) return;
    // 取消排序时回到默认方向
    sortDir.value = payload.order === 'descending' ? 'desc' : 'asc';
    if (sort.mode === 'server') void load();
  }

  /** 升/降序切换按钮：server 模式翻转后重查，local 模式 displayItems 自动重排 */
  function toggleSortDir() {
    sortDir.value = sortDir.value === 'desc' ? 'asc' : 'desc';
    if (options.sort?.mode === 'server') void load();
  }

  function onSizeChange() {
    size.value = clampAdminPageSize(size.value);
    return load({ resetPage: true });
  }

  if (options.autoLoad !== false) {
    onMounted(() => {
      void load();
    });
  }

  return reactive({
    rowKey: options.rowKey,
    items,
    displayItems,
    total,
    page,
    size,
    pageSizes: ADMIN_LIST_PAGE_SIZES,
    loading,
    hydrated,
    selectedKeys,
    hasSelection,
    exportButtonLabel,
    sortProp: options.sort?.prop ?? '',
    sortDir,
    toggleSortDir,
    defaultSort,
    load,
    refresh: () => load(),
    search: () => load({ resetPage: true }),
    onSizeChange,
    onSortChange,
    onSelectionChange,
    clearSelection,
    pickSelected
  }) as unknown as CrudTableController<T>;
}
