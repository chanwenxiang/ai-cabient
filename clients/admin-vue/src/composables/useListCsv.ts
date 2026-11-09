import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { csvFileName, csvRowsToObjects, downloadCsv, parseCsv } from '@/utils/csv';

/** 导出范围：用于在「只导当前页」时明确告知真实总量，避免静默截断（P2-3/P2-4）。 */
export type CsvExportScope = {
  /** 服务端总条数（未知传 0/缺省） */
  total?: number;
  /** 是否"导出勾选项"模式（勾选优先时不提示） */
  selected?: boolean;
};

/** Shared list CSV export / import with timestamped filenames. */
export function useListCsv(opts: {
  filePrefix: string;
  headers: string[];
  toRows: () => Array<Array<unknown>>;
  onImportRows?: (rows: Record<string, string>[]) => Promise<number>;
  scope?: () => CsvExportScope;
}) {
  const importing = ref(false);
  const importInput = ref<HTMLInputElement | null>(null);
  const canImport = computed(() => typeof opts.onImportRows === 'function');

  async function onExport() {
    const rows = opts.toRows();
    if (!rows.length) {
      ElMessage.warning('暂无数据可导出');
      return;
    }
    const scope = opts.scope?.();
    const total = Number(scope?.total) || 0;
    // 只有「未勾选 + 服务端还有更多」才提示：勾选导出是明确的局部意图，不打扰
    if (scope && !scope.selected && total > rows.length) {
      try {
        await ElMessageBox.confirm(
          `内置导出只包含当前页 ${rows.length} 条，服务端共 ${total} 条。` +
            `需要完整数据请使用页面顶部的「全量导出」按钮（如有）。`,
          '导出范围确认',
          { confirmButtonText: '仅导出当前页', cancelButtonText: '取消', type: 'warning' }
        );
      } catch {
        return;
      }
    }
    downloadCsv(csvFileName(opts.filePrefix), opts.headers, rows);
    ElMessage.success(
      total > rows.length
        ? `已导出当前页 ${rows.length} 条（共 ${total} 条）`
        : `已导出 ${rows.length} 条`
    );
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
