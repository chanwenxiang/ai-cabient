import { computed, ref, type Ref } from 'vue';
import type { Sort } from 'element-plus';
import { sortByPrimaryKey, type SortDirection } from '@/utils/sort-by-pk';

/**
 * 列表主键/ID 列：默认升序（小→大），点击表头切换升/降序。
 * - 表格绑定 `:default-sort="idDefaultSort"` `@sort-change="onIdSortChange"`
 * - ID 列加 `prop="<idProp>"` 与 `sortable="custom"`
 * - 展示数据用 `sortById(rows)` 或把 `idSortDir` 传给已有 sortByPrimaryKey
 */
export function useIdColumnSort<T = any>(
  idProp: string,
  options?: { defaultDir?: SortDirection; onChange?: () => void }
) {
  const idSortDir: Ref<SortDirection> = ref(options?.defaultDir ?? 'asc');

  const idDefaultSort = computed(() => ({
    prop: idProp,
    order: idSortDir.value === 'desc' ? 'descending' : 'ascending'
  }));

  function onIdSortChange(payload: Sort) {
    if (payload.prop && payload.prop !== idProp) return;
    // 取消排序时回到默认升序
    idSortDir.value = payload.order === 'descending' ? 'desc' : 'asc';
    options?.onChange?.();
  }

  function sortById(rows: T[], key: keyof T | ((row: T) => unknown) = idProp as keyof T): T[] {
    return sortByPrimaryKey(rows, key, idSortDir.value);
  }

  return {
    idProp,
    idSortDir,
    idDefaultSort,
    /** alias for destructuring as `defaultSort` */
    defaultSort: idDefaultSort,
    onIdSortChange,
    /** alias for destructuring as `onSortChange` */
    onSortChange: onIdSortChange,
    sortById
  };
}
