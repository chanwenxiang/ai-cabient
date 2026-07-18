import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { csvFileName, csvRowsToObjects, downloadCsv, parseCsv } from '@/utils/csv';

/** Shared list CSV export / import with timestamped filenames. */
export function useListCsv(opts: {
  filePrefix: string;
  headers: string[];
  toRows: () => Array<Array<unknown>>;
  onImportRows?: (rows: Record<string, string>[]) => Promise<number>;
}) {
  const importing = ref(false);
  const importInput = ref<HTMLInputElement | null>(null);
  const canImport = computed(() => typeof opts.onImportRows === 'function');

  function onExport() {
    const rows = opts.toRows();
    if (!rows.length) {
      ElMessage.warning('暂无数据可导出');
      return;
    }
    downloadCsv(csvFileName(opts.filePrefix), opts.headers, rows);
    ElMessage.success(`已导出 ${rows.length} 条`);
  }

  function onDownloadTemplate(sampleRow?: Array<unknown>) {
    downloadCsv(
      csvFileName(`${opts.filePrefix}导入模板`),
      opts.headers,
      sampleRow ? [sampleRow] : []
    );
  }

  function triggerImport() {
    importInput.value?.click();
  }

  async function onImportFile(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (!opts.onImportRows) {
      ElMessage.info('当前页面暂不支持导入');
      return;
    }
    importing.value = true;
    try {
      const text = await file.text();
      const objects = csvRowsToObjects(parseCsv(text));
      if (!objects.length) {
        ElMessage.warning('CSV 无有效数据行');
        return;
      }
      const ok = await opts.onImportRows(objects);
      ElMessage.success(`导入成功 ${ok} 条`);
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '导入失败');
    } finally {
      importing.value = false;
    }
  }

  return {
    importing,
    importInput,
    canImport,
    onExport,
    onDownloadTemplate,
    triggerImport,
    onImportFile
  };
}
