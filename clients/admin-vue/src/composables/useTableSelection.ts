import { computed, ref } from 'vue';

/** Shared table checkbox selection for export (and batch actions). */
export function useTableSelection<T>(getRowKey: (row: T) => string | number) {
  const selectedKeys = ref<Array<string | number>>([]);

  function onSelectionChange(rows: T[]) {
    selectedKeys.value = rows
      .map((r) => getRowKey(r))
      .filter((k) => k != null && k !== '');
  }

  function pickSelected(all: T[]): T[] {
    if (!selectedKeys.value.length) return all;
    const set = new Set(selectedKeys.value.map(String));
    return all.filter((r) => set.has(String(getRowKey(r))));
  }

  const exportButtonLabel = computed(() =>
    selectedKeys.value.length ? `导出选中 (${selectedKeys.value.length})` : '导出'
  );

  function clearSelection() {
    selectedKeys.value = [];
  }

  return {
    selectedKeys,
    onSelectionChange,
    pickSelected,
    exportButtonLabel,
    clearSelection
  };
}
