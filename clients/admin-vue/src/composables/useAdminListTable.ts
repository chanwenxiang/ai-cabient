import { computed, ref } from 'vue';
import type { TableInstance } from 'element-plus';
import { useTableSelection } from '@/composables/useTableSelection';

/** 列表页通用：多选 + 关键词 + 表格 ref */
export function useAdminListTable<T>(getRowKey: (row: T) => string | number) {
  const tableRef = ref<TableInstance>();
  const keyword = ref('');
  const { selectedKeys, onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
    useTableSelection<T>(getRowKey);

  const hasSelection = computed(() => selectedKeys.value.length > 0);

  function filterByKeyword(rows: T[], matcher: (row: T, kw: string) => boolean): T[] {
    const kw = keyword.value.trim().toLowerCase();
    if (!kw) return rows;
    return rows.filter((r) => matcher(r, kw));
  }

  function clearTableSelection() {
    clearSelection();
    tableRef.value?.clearSelection();
  }

  function resetKeyword() {
    keyword.value = '';
  }

  return {
    tableRef,
    keyword,
    selectedKeys,
    hasSelection,
    onSelectionChange,
    pickSelected,
    exportButtonLabel,
    clearSelection: clearTableSelection,
    filterByKeyword,
    resetKeyword
  };
}
