import { computed, ref, type Ref } from 'vue';
import { showError } from '@/utils/notify';
import { displayLabel } from '@aicabinet/shared-dict';
import { isMerchantLoggedIn, merchantApi, softFallback } from '@/utils/merchant-api';
import type {
  MerchantSkuPricing,
  OpenApiDeviceInventoryDto,
  OpenApiMerchantReplenishmentEfficiencyDto
} from '@aicabinet/shared-types';

type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;

function filterTasksByDevice(rows: Task[], deviceKey: string) {
  return rows.filter(
    (t) =>
      String(t.deviceId || '')
        .trim()
        .toUpperCase() === deviceKey
  );
}

function sortTasksByPreferred(rows: Task[], preferred: string) {
  if (!preferred) return rows;
  return [...rows].sort((a, b) => {
    if (a.deviceId === preferred) return -1;
    if (b.deviceId === preferred) return 1;
    return 0;
  });
}

/** 按柜聚合低库存明细：缺货 SKU 数 + 缺口件数，按严重度排序取前 5。 */
export function aggregateLowStock(items: OpenApiDeviceInventoryDto[]) {
  const map = new Map<string, { skuCount: number; shortageQty: number }>();
  for (const row of items || []) {
    const key = String(row.deviceId || '')
      .trim()
      .toUpperCase();
    if (!key) continue;
    const cur = map.get(key) || { skuCount: 0, shortageQty: 0 };
    cur.skuCount += 1;
    cur.shortageQty += Math.max(0, (Number(row.lowThreshold) || 0) - (Number(row.quantity) || 0));
    map.set(key, cur);
  }
  return [...map.entries()]
    .map(([deviceId, v]) => ({ deviceId, ...v }))
    .sort((a, b) => b.skuCount - a.skuCount || b.shortageQty - a.shortageQty)
    .slice(0, 5);
}

/**
 * 补货列表：任务/设备/SKU/效率/低库存 + 筛选与聚合徽标。
 * 深链打开详情由页面回调处理。
 */
export function useReplenishmentList(opts: { preferredId: Ref<string> }) {
  const loading = ref(false);
  let loadSeq = 0;
  const allTasks = ref<Task[]>([]);
  const evidenceCountMap = ref<Record<number, number>>({});
  const lineSummaryMap = ref<Record<number, string>>({});
  const devices = ref<Record<string, unknown>[]>([]);
  /** SKU 目录（缩略图/条码）按需懒加载，禁止列表 onShow 全量拉 pricing。 */
  const skus = ref<MerchantSkuPricing[]>([]);
  let skuCatalogPromise: Promise<void> | null = null;
  let skuCatalogLoaded = false;
  const efficiency = ref<OpenApiMerchantReplenishmentEfficiencyDto | null>(null);
  const lowStockList = ref<{ deviceId: string; skuCount: number; shortageQty: number }[]>([]);
  const status = ref('');
  const filterDeviceId = ref('');

  const tasks = computed(() => {
    let rows = allTasks.value.filter((t) => t.status !== 'CANCELLED');
    if (filterDeviceId.value) {
      rows = filterTasksByDevice(rows, filterDeviceId.value.trim().toUpperCase());
    }
    if (status.value) {
      rows = rows.filter((t) => t.status === status.value);
    }
    if (filterDeviceId.value || !opts.preferredId.value) return rows;
    return sortTasksByPreferred(rows, opts.preferredId.value);
  });

  const pendingCount = computed(
    () =>
      allTasks.value.filter((item) => item.status !== 'COMPLETED' && item.status !== 'CANCELLED')
        .length
  );
  const completedCount = computed(
    () => allTasks.value.filter((item) => item.status === 'COMPLETED').length
  );

  function evidenceCountOf(taskId?: number) {
    if (!taskId) return 0;
    const fromMap = Number(evidenceCountMap.value[taskId] || 0);
    if (fromMap > 0) return fromMap;
    const hit = allTasks.value.find((t) => t.taskId === taskId) as Task & {
      evidenceCount?: number;
    };
    return Number(hit?.evidenceCount || 0);
  }

  function lineSummaryOf(taskId?: number) {
    if (!taskId) return '';
    const fromMap = String(lineSummaryMap.value[taskId] || '');
    if (fromMap) return fromMap;
    const hit = allTasks.value.find((t) => t.taskId === taskId) as Task & {
      lineSummary?: string;
    };
    return String(hit?.lineSummary || '');
  }

  /** 列表接口已聚合 evidenceCount/lineSummary，写入本地 map 供详情内增量更新复用。 */
  function seedListAggregates(taskRows: Task[]) {
    const evidenceNext: Record<number, number> = { ...evidenceCountMap.value };
    const lineNext: Record<number, string> = { ...lineSummaryMap.value };
    for (const row of taskRows || []) {
      const id = Number(row.taskId);
      if (!id) continue;
      const ext = row as Task & { evidenceCount?: number; lineSummary?: string };
      if (ext.evidenceCount != null) evidenceNext[id] = Number(ext.evidenceCount) || 0;
      if (ext.lineSummary != null && String(ext.lineSummary)) {
        lineNext[id] = String(ext.lineSummary);
      }
    }
    evidenceCountMap.value = evidenceNext;
    lineSummaryMap.value = lineNext;
  }

  function applyReplenishmentListData(
    taskRows: Task[],
    deviceRows: Record<string, unknown>[],
    eff: OpenApiMerchantReplenishmentEfficiencyDto | null,
    lowStockRows: OpenApiDeviceInventoryDto[]
  ) {
    allTasks.value = taskRows || [];
    devices.value = deviceRows;
    efficiency.value = eff;
    lowStockList.value = aggregateLowStock(lowStockRows || []);
    seedListAggregates(allTasks.value);
  }

  /**
   * M-P2-9：详情/扫码才需要 SKU 图与条码；列表页不预拉整表 pricing。
   */
  async function ensureSkuCatalog(force = false) {
    if (skuCatalogLoaded && !force) return;
    if (skuCatalogPromise) {
      await skuCatalogPromise;
      return;
    }
    skuCatalogPromise = (async () => {
      try {
        skus.value = (await merchantApi.pricing()) || [];
      } catch {
        skus.value = [];
      } finally {
        skuCatalogLoaded = true;
        skuCatalogPromise = null;
      }
    })();
    await skuCatalogPromise;
  }

  function syncTaskInList(task: Task) {
    const idx = allTasks.value.findIndex((t) => t.taskId === task.taskId);
    if (idx >= 0) {
      allTasks.value[idx] = { ...allTasks.value[idx], ...task };
    }
  }

  function changeStatus(value: string) {
    status.value = value;
  }

  function clearDeviceFilter() {
    filterDeviceId.value = '';
  }

  function emptyHintForDeviceFilter(): string {
    return status.value
      ? `该柜机暂无「${displayLabel('replenishment_task_status', status.value, '该状态')}」任务`
      : '该柜机暂无补货任务';
  }

  function emptyHintForStatusFilter(): string {
    if (status.value === 'IN_PROGRESS' && pendingCount.value === 0 && completedCount.value > 0) {
      return '暂无进行中的任务，可查看已完成记录';
    }
    if (status.value) {
      return `暂无「${displayLabel('replenishment_task_status', status.value, '该状态')}」任务`;
    }
    return '当前没有补货任务';
  }

  /**
   * 拉取列表数据。返回 loadSeq；调用方应用深链逻辑。
   * canReplenish 用 getter 延迟求值：调用时 me 尚未 ensureMe（冷启动为 null → false），
   * 必须在 ensureMe 完成后再读取，否则权限快照永远取到旧值（C24）。
   * @returns null 表示被更新请求取代或未登录/无权限中断
   */
  async function fetchList(hooks: {
    ensureMe: (seq: number) => Promise<boolean>;
    canReplenish: () => boolean;
  }): Promise<{ seq: number; aborted: boolean } | null> {
    if (!isMerchantLoggedIn()) {
      uni.reLaunch({ url: '/pages/login/login' });
      return null;
    }
    const seq = ++loadSeq;
    if (!(await hooks.ensureMe(seq))) return null;
    if (!hooks.canReplenish()) {
      showError('无补货权限');
      uni.switchTab({ url: '/pages/home/home' });
      return null;
    }
    if (!allTasks.value.length) loading.value = true;
    try {
      // 主列表硬失败：禁止 soft 成 [] 伪装「暂无补货任务」（M1）
      const taskRows = await merchantApi.replenishmentTasks();
      if (seq !== loadSeq) return { seq, aborted: true };
      const [deviceRows, eff, lowStockRows] = await Promise.all([
        softFallback(merchantApi.devices(), [] as Record<string, unknown>[], '柜机列表'),
        softFallback(merchantApi.myReplenishmentEfficiency(), null, '补货效率'),
        softFallback(merchantApi.lowStockDevices(), [] as OpenApiDeviceInventoryDto[], '缺货柜机')
      ]);
      if (seq !== loadSeq) return { seq, aborted: true };
      applyReplenishmentListData(taskRows, deviceRows, eff, lowStockRows);
      return { seq, aborted: false };
    } catch (error) {
      if (seq !== loadSeq) return { seq, aborted: true };
      showError(error instanceof Error ? error.message : '加载失败');
      return { seq, aborted: true };
    } finally {
      if (seq === loadSeq) {
        loading.value = false;
        uni.stopPullDownRefresh();
      }
    }
  }

  function isLatestLoad(seq: number) {
    return seq === loadSeq;
  }

  return {
    loading,
    allTasks,
    evidenceCountMap,
    lineSummaryMap,
    devices,
    skus,
    efficiency,
    lowStockList,
    status,
    filterDeviceId,
    tasks,
    pendingCount,
    completedCount,
    evidenceCountOf,
    lineSummaryOf,
    seedListAggregates,
    applyReplenishmentListData,
    ensureSkuCatalog,
    syncTaskInList,
    changeStatus,
    clearDeviceFilter,
    emptyHintForDeviceFilter,
    emptyHintForStatusFilter,
    fetchList,
    isLatestLoad,
    getLoadSeq: () => loadSeq
  };
}
