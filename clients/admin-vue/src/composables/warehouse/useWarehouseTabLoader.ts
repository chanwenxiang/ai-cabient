import { type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { adminDevWarn } from '@/utils/admin-dev-log';
import { softFallback, reportSoftFail } from '@/utils/soft-fallback';
import type { createLoadSeq } from '@/composables/createLoadSeq';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

/** 仓储动态行（D15：禁止散落 Record<string, any>） */
export type WarehouseTabRow = AdminDynamicRow;

/**
 * D28-B3：数据面恒等于「一个仓」的 tab。两种成因都算：
 *   · `inventory` / `movements`——空白 warehouseId 被后端 `resolveWarehouseId(null)` 兜底成
 *     「默认仓」，页面却写着「全部仓库」；
 *   · `monthly`——后端直接要求必填仓（`loadMonthlyClose` 自己钉第一个仓）。
 * 三处共用这一个集合，别各写一份：
 * ① 本文件的 `pinSingleWarehouseFilter()`——进这两个 tab 先钉一个具体仓；
 * ② `useWarehouseListFilters.warehouseFilterRequired`——筛选项禁清空、占位词改成「请选择仓库」；
 * ③ `useWarehouseCsv.onExport`——服务端全量导出同样不认 warehouseId，这两个 tab 要点破再导。
 */
export const SINGLE_WAREHOUSE_TABS = new Set(['inventory', 'movements', 'monthly']);

export type UseWarehouseTabLoaderDeps = {
  loadSeq: ReturnType<typeof createLoadSeq>;
  hasDeviceListPerm: () => boolean;
  page: Ref<number>;
  size: Ref<number>;
  keyword: Ref<string>;
  filterWarehouseId: Ref<string>;
  /** D28-B5：出库状态筛选（'actionable'＝客户端组合筛选，其余为真实状态、走服务端 status 参数）。 */
  filterOutboundStatus: Ref<string>;
  /** D28-B5：在途超时筛选（true ⇒ 请求带 overdueOnly=true，服务端按 24h 截止过滤）。 */
  overdueOnly: Ref<boolean>;
  hideTestPurchaseOrders: Ref<boolean>;
  focusDeviceId: Ref<string>;
  suggestionLeadTimeDays: Ref<number>;
  suggestionCoverageDays: Ref<number>;
  payableStatusFilter: Ref<string>;
  payableOverdueOnly: Ref<boolean>;
  stocktakeStatusFilter: Ref<string>;
  filterBinId: Ref<number | null>;
  warehouses: Ref<WarehouseTabRow[]>;
  suppliers: Ref<WarehouseTabRow[]>;
  purchaseOrders: Ref<WarehouseTabRow[]>;
  returnablePurchaseOrders: Ref<WarehouseTabRow[]>;
  purchaseReturns: Ref<WarehouseTabRow[]>;
  outbounds: Ref<WarehouseTabRow[]>;
  inTransit: Ref<WarehouseTabRow[]>;
  inventory: Ref<WarehouseTabRow[]>;
  movements: Ref<WarehouseTabRow[]>;
  suggestions: Ref<WarehouseTabRow[]>;
  payables: Ref<WarehouseTabRow[]>;
  payableSummary: Ref<WarehouseTabRow[]>;
  stocktakes: Ref<WarehouseTabRow[]>;
  monthlyCloseLines: Ref<WarehouseTabRow[]>;
  monthlyCloseHint: Ref<string>;
  monthlyCloseManager: Ref<string>;
  monthlyCloseDeviceCount: Ref<number>;
  monthlyCloseYearMonth: Ref<string>;
  bins: Ref<WarehouseTabRow[]>;
  binStock: Ref<WarehouseTabRow[]>;
  transfers: Ref<WarehouseTabRow[]>;
  devices: Ref<WarehouseTabRow[]>;
  skus: Ref<WarehouseTabRow[]>;
  tabTotals: Ref<Record<string, number>>;
  loadedTabs: Ref<Set<string>>;
  loadingTabs: Ref<Set<string>>;
  hydratedTabs: Ref<Set<string>>;
  /**
   * D28-B7：加载失败的 tab。**不能**用「未 hydrated」代替——各 tab 的空态文案只在 hydrated
   * 时出现，失败时若照旧标 hydrated，页面就把 5xx/超时说成「暂无数据」。
   */
  failedTabs: Ref<Set<string>>;
};

/**
 * 仓储各 Tab 列表加载与 hydrated/loaded 编排。
 * 写路径弹窗仍由各 useWarehouse*  composable 负责。
 */
export function useWarehouseTabLoader(deps: UseWarehouseTabLoaderDeps) {
  /** 每个 tab 的在途加载数（见 loadTab finally：loading 清理不受过时守卫影响） */
  const tabInflight = new Map<string, number>();

  async function ensureMeta() {
    if (!deps.devices.value.length) {
      if (!deps.hasDeviceListPerm()) {
        deps.devices.value = [];
      } else {
        deps.devices.value = await softFallback(
          api.request<WarehouseTabRow[]>(AdminEndpoints.devicesRef, 'GET'),
          [],
          '设备参照'
        );
      }
    }
    if (!deps.skus.value.length) {
      deps.skus.value =
        (
          await softFallback(
            api.request<{ items: WarehouseTabRow[] }>(AdminEndpoints.skusCatalogPage, 'GET'),
            { items: [] as WarehouseTabRow[] },
            'SKU 目录'
          )
        ).items || [];
    }
  }

  /**
   * D28-B13：`keyword` 是跨 tab 共享的 ref，而关键词输入框只在 suppliers/purchase/returns 显示。
   * 不管它的 tab 若照样带上 `q`，用户会看到一个「看不见也清不掉」的筛选条件在悄悄生效
   * ⇒ 默认不带 `q`，只有真正渲染了关键词框的 tab 传 `withKeyword = true`。
   */
  function warehouseListParams(withKeyword = false) {
    const q = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (withKeyword && deps.keyword.value.trim()) q.set('q', deps.keyword.value.trim());
    if (deps.filterWarehouseId.value) q.set('warehouseId', deps.filterWarehouseId.value);
    return q;
  }

  /**
   * D28-B3：批次库存 / 库存流水是**单仓口径**——后端 `listInventoryPage` / `listMovementsPage`
   * 对空白 warehouseId 走 `resolveWarehouseId(null)`＝取「默认仓」（WarehouseService:157/:219），
   * 而筛选项写着「全部仓库」、服务端 CSV 导出同样只导默认仓（OpsCsvExportService 传 null）。
   * 这里把口径钉回诚实的一侧：进这两个 tab 先选中一个具体仓，之后每次请求都显式带 warehouseId，
   * 筛选项同时禁清空（见 `SINGLE_WAREHOUSE_TABS` 与 WarehouseView 的 `warehouseFilterRequired`）。
   * 一家仓都没配时不改值——让后端「未配置仓库，请先新建仓库」照常冒出来。
   */
  function pinSingleWarehouseFilter() {
    if (deps.filterWarehouseId.value) return;
    const rows = deps.warehouses.value;
    const firstActive = rows.find((w) => (w.status || 'ACTIVE') === 'ACTIVE');
    const id = String((firstActive ?? rows[0])?.warehouseId ?? '');
    if (id) deps.filterWarehouseId.value = id;
  }

  async function loadWarehouses() {
    const seq = deps.loadSeq.begin('loadWarehouses');
    const q = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.keyword.value.trim()) q.set('q', deps.keyword.value.trim());
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.warehouseList(q),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadWarehouses')) return;
    deps.warehouses.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      warehouses: Number(data.total) || 0
    };
  }

  async function loadWarehousesSoft() {
    const seq = deps.loadSeq.begin('loadWarehousesSoft');
    try {
      const data = await api.request<{ items: WarehouseTabRow[] }>(
        AdminEndpoints.warehouseListAll,
        'GET'
      );
      if (!deps.loadSeq.isCurrent(seq, 'loadWarehousesSoft')) return;
      deps.warehouses.value = data.items || [];
    } catch (e) {
      if (!deps.loadSeq.isCurrent(seq, 'loadWarehousesSoft')) return;
      /* 筛选用元数据失败时保留旧列表，不拖垮库存/出库主数据；须可见提示（D3） */
      reportSoftFail(e, '仓库筛选项');
    }
  }

  async function loadSuppliers() {
    const seq = deps.loadSeq.begin('loadSuppliers');
    const q = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.keyword.value.trim()) q.set('q', deps.keyword.value.trim());
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.suppliersList(q),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadSuppliers')) return;
    deps.suppliers.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      suppliers: Number(data.total) || 0
    };
  }

  async function loadSuppliersSoft() {
    const seq = deps.loadSeq.begin('loadSuppliersSoft');
    try {
      const data = await api.request<{ items: WarehouseTabRow[] }>(
        AdminEndpoints.suppliersListAll,
        'GET'
      );
      if (!deps.loadSeq.isCurrent(seq, 'loadSuppliersSoft')) return;
      deps.suppliers.value = data.items || [];
    } catch {
      if (!deps.loadSeq.isCurrent(seq, 'loadSuppliersSoft')) return;
      /* 采购/退货筛选项可选 */
    }
  }

  async function loadPurchase() {
    const seq = deps.loadSeq.begin('loadPurchase');
    const q = warehouseListParams(true);
    if (deps.hideTestPurchaseOrders.value) q.set('excludeTestRef', 'true');
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.purchaseOrdersList(q),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadPurchase')) return;
    deps.purchaseOrders.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      purchase: Number(data.total) || 0
    };
  }

  async function loadReturnablePurchaseOrders() {
    const seq = deps.loadSeq.begin('loadReturnablePurchaseOrders');
    try {
      const items =
        (
          await api.request<{ items: WarehouseTabRow[] }>(
            AdminEndpoints.purchaseOrdersReturnable,
            'GET'
          )
        ).items || [];
      if (!deps.loadSeq.isCurrent(seq, 'loadReturnablePurchaseOrders')) return;
      deps.returnablePurchaseOrders.value = items;
    } catch {
      if (!deps.loadSeq.isCurrent(seq, 'loadReturnablePurchaseOrders')) return;
      deps.returnablePurchaseOrders.value = [];
    }
  }

  async function loadReturns() {
    const seq = deps.loadSeq.begin('loadReturns');
    const q = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.keyword.value.trim()) q.set('q', deps.keyword.value.trim());
    if (deps.filterWarehouseId.value) q.set('warehouseId', deps.filterWarehouseId.value);
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.purchaseReturnsList(q),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadReturns')) return;
    deps.purchaseReturns.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      returns: Number(data.total) || 0
    };
  }

  async function loadOutbounds() {
    const seq = deps.loadSeq.begin('loadOutbounds');
    const q = warehouseListParams();
    // D28-B5：真实状态筛选下推服务端（出库列表是服务端分页，客户端过滤只作用当前页＝假过滤）。
    // 'actionable'（有明细的 DRAFT+PICKED）是组合口径，仍由 filteredOutbounds 客户端完成。
    const st = deps.filterOutboundStatus.value.trim();
    if (st && st !== 'actionable') q.set('status', st);
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.warehouseOutbounds(q),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadOutbounds')) return;
    deps.outbounds.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      outbounds: Number(data.total) || 0
    };
  }

  async function loadTransit() {
    const seq = deps.loadSeq.begin('loadTransit');
    const q = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.focusDeviceId.value) q.set('deviceId', deps.focusDeviceId.value);
    // D28-B5：超时筛选下推服务端（24h 截止口径在 InTransitService.TRANSIT_OVERDUE_HOURS）。
    if (deps.overdueOnly.value) q.set('overdueOnly', 'true');
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.warehouseInTransit(q),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadTransit')) return;
    deps.inTransit.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      transit: Number(data.total) || 0
    };
  }

  async function loadInventory() {
    const seq = deps.loadSeq.begin('loadInventory');
    pinSingleWarehouseFilter();
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.warehouseInventory(warehouseListParams()),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadInventory')) return;
    deps.inventory.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      inventory: Number(data.total) || 0
    };
  }

  async function loadMonthlyClose() {
    const seq = deps.loadSeq.begin('loadMonthlyClose');
    let warehouseId = deps.filterWarehouseId.value;
    if (!warehouseId) {
      const first = deps.warehouses.value[0]?.warehouseId;
      if (first) {
        deps.filterWarehouseId.value = String(first);
        warehouseId = String(first);
      }
    }
    if (!warehouseId) {
      deps.monthlyCloseLines.value = [];
      deps.monthlyCloseHint.value = '请先选择仓库';
      deps.monthlyCloseManager.value = '';
      deps.monthlyCloseDeviceCount.value = 0;
      deps.tabTotals.value = { ...deps.tabTotals.value, monthly: 0 };
      return;
    }
    const q = new URLSearchParams({ warehouseId });
    if (deps.monthlyCloseYearMonth.value) q.set('yearMonth', deps.monthlyCloseYearMonth.value);
    const data = await api.request<{
      formulaHint?: string;
      managerUserName?: string | null;
      deviceCount?: number;
      lines?: WarehouseTabRow[];
    }>(AdminEndpoints.warehouseMonthlyClose(q), 'GET');
    if (!deps.loadSeq.isCurrent(seq, 'loadMonthlyClose')) return;
    deps.monthlyCloseLines.value = data.lines || [];
    deps.monthlyCloseHint.value = data.formulaHint || '';
    deps.monthlyCloseManager.value = data.managerUserName || '';
    deps.monthlyCloseDeviceCount.value = Number(data.deviceCount) || 0;
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      monthly: (data.lines || []).length
    };
  }

  /**
   * 月结「领货」列的柜机下钻明细。返回行而非写 state：弹窗归调用方（WarehouseView）持有，
   * 避免为一次性查询再开一组 deps。
   */
  async function loadMonthlyRestockDetail(skuId: string): Promise<WarehouseTabRow[]> {
    const warehouseId = deps.filterWarehouseId.value;
    if (!warehouseId) return [];
    const q = new URLSearchParams({ warehouseId });
    if (deps.monthlyCloseYearMonth.value) q.set('yearMonth', deps.monthlyCloseYearMonth.value);
    const data = await api.request<WarehouseTabRow[]>(
      AdminEndpoints.warehouseMonthlyCloseRestockDetail(q),
      'GET'
    );
    const rows = Array.isArray(data) ? data : [];
    return skuId ? rows.filter((r) => String(r.skuId ?? '') === skuId) : rows;
  }

  async function loadMovements() {
    const seq = deps.loadSeq.begin('loadMovements');
    pinSingleWarehouseFilter();
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.warehouseMovements(warehouseListParams()),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadMovements')) return;
    deps.movements.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      movements: Number(data.total) || 0
    };
  }

  async function loadSuggestions() {
    const seq = deps.loadSeq.begin('loadSuggestions');
    const params = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.suggestionLeadTimeDays.value > 0) {
      params.set('leadTimeDays', String(deps.suggestionLeadTimeDays.value));
    }
    if (deps.suggestionCoverageDays.value > 0) {
      params.set('coverageDays', String(deps.suggestionCoverageDays.value));
    }
    if (deps.filterWarehouseId.value) {
      params.set('warehouseId', deps.filterWarehouseId.value);
    }
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.procurementSuggestions(params),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadSuggestions')) return;
    deps.suggestions.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      suggestions: Number(data.total) || 0
    };
  }

  async function loadPayables() {
    const seq = deps.loadSeq.begin('loadPayables');
    const params = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.payableStatusFilter.value) params.set('status', deps.payableStatusFilter.value);
    if (deps.payableOverdueOnly.value) params.set('overdueOnly', 'true');
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.suppliersPayables(params),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadPayables')) return;
    deps.payables.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      payables: Number(data.total) || 0
    };
  }

  async function loadPayableSummary() {
    const seq = deps.loadSeq.begin('loadPayableSummary');
    const rows = await softFallback(
      api.request<WarehouseTabRow[]>(AdminEndpoints.suppliersPayablesSummary, 'GET'),
      [],
      '应付汇总'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadPayableSummary')) return;
    deps.payableSummary.value = rows;
  }

  async function loadStocktakes() {
    const seq = deps.loadSeq.begin('loadStocktakes');
    const params = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.stocktakeStatusFilter.value) params.set('status', deps.stocktakeStatusFilter.value);
    if (deps.filterWarehouseId.value) params.set('warehouseId', deps.filterWarehouseId.value);
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.warehouseStocktakes(params),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadStocktakes')) return;
    deps.stocktakes.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      stocktakes: Number(data.total) || 0
    };
  }

  async function loadBins() {
    const seq = deps.loadSeq.begin('loadBins');
    const rows = await api.request<WarehouseTabRow[]>(AdminEndpoints.warehouseBins, 'GET');
    if (!deps.loadSeq.isCurrent(seq, 'loadBins')) return;
    deps.bins.value = rows;
  }

  async function loadBinStock() {
    const seq = deps.loadSeq.begin('loadBinStock');
    const params = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.filterWarehouseId.value) params.set('warehouseId', deps.filterWarehouseId.value);
    if (deps.filterBinId.value != null) params.set('binId', String(deps.filterBinId.value));
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.warehouseBinsStock(params),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadBinStock')) return;
    deps.binStock.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      bins: Number(data.total) || 0
    };
  }

  async function loadTransfers() {
    const seq = deps.loadSeq.begin('loadTransfers');
    const q = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      AdminEndpoints.warehouseTransfers(q),
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadTransfers')) return;
    deps.transfers.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      transfers: Number(data.total) || 0
    };
  }

  async function loadWarehouseTabData(name: string) {
    const loaders: Record<string, () => Promise<unknown>> = {
      warehouses: () => loadWarehouses(),
      suppliers: () => loadSuppliers(),
      purchase: () => Promise.all([loadPurchase(), loadSuppliersSoft(), loadWarehousesSoft()]),
      returns: () =>
        Promise.all([
          loadReturns(),
          loadReturnablePurchaseOrders(),
          loadPurchase().catch((err) => {
            adminDevWarn('[warehouse] 退货弹窗预载采购单失败', err);
          }),
          loadSuppliersSoft(),
          loadWarehousesSoft()
        ]),
      suggestions: () =>
        Promise.all([loadSuggestions(), loadWarehousesSoft(), loadSuppliersSoft()]),
      payables: () => Promise.all([loadPayables(), loadPayableSummary(), loadSuppliersSoft()]),
      stocktakes: () => Promise.all([loadStocktakes(), loadWarehousesSoft()]),
      bins: () => Promise.all([loadBins(), loadBinStock(), loadWarehousesSoft()]),
      outbounds: () => Promise.all([loadOutbounds(), loadWarehousesSoft()]),
      transit: () => loadTransit(),
      transfers: () => Promise.all([loadTransfers(), loadWarehousesSoft()]),
      // 这两个 tab 必须先拿到仓库列表再拉数据：pinSingleWarehouseFilter 要在请求发出前定下具体仓，
      // 否则会退回「后端兜底默认仓」那个读不出来的口径（D28-B3）。
      inventory: () => loadWarehousesSoft().then(() => loadInventory()),
      movements: () => loadWarehousesSoft().then(() => loadMovements()),
      monthly: () => loadWarehousesSoft().then(() => loadMonthlyClose())
    };
    const loader = loaders[name];
    if (loader) await loader();
  }

  async function loadTab(name: string, force = false) {
    const seq = deps.loadSeq.begin('loadTab');
    if (
      !force &&
      deps.loadedTabs.value.has(name) &&
      name !== 'inventory' &&
      name !== 'movements' &&
      name !== 'monthly'
    ) {
      return;
    }
    // 在途计数按 tab 维护：过时的请求也必须把自己那份 loading 关掉，
    // 否则新旧请求交错后遮罩永久卡住（2026-09-30 用户实测整页假死）。过时守卫只保护数据标记。
    tabInflight.set(name, (tabInflight.get(name) || 0) + 1);
    const nextLoading = new Set(deps.loadingTabs.value);
    nextLoading.add(name);
    deps.loadingTabs.value = nextLoading;
    let failed = false;
    try {
      await ensureMeta();
      await loadWarehouseTabData(name);
      if (deps.loadSeq.isCurrent(seq, 'loadTab')) deps.loadedTabs.value.add(name);
    } catch (e) {
      if (!deps.loadSeq.isCurrent(seq, 'loadTab')) return;
      failed = true;
      ElMessage.error(errorMessage(e, '加载失败'));
    } finally {
      const left = Math.max(0, (tabInflight.get(name) || 1) - 1);
      if (left === 0) tabInflight.delete(name);
      else tabInflight.set(name, left);
      if (left === 0) {
        const doneLoading = new Set(deps.loadingTabs.value);
        doneLoading.delete(name);
        deps.loadingTabs.value = doneLoading;
      }
      if (deps.loadSeq.isCurrent(seq, 'loadTab')) {
        markTabFailed(name, failed);
        if (!failed) {
          const next = new Set(deps.hydratedTabs.value);
          next.add(name);
          deps.hydratedTabs.value = next;
        }
      }
    }
  }

  /** D28-B7：失败与空态分离；失败时同时撤掉 loadedTabs 记账，下次进这个 tab 会自动重试。 */
  function markTabFailed(name: string, failed: boolean) {
    const nextFailed = new Set(deps.failedTabs.value);
    if (failed) nextFailed.add(name);
    else nextFailed.delete(name);
    deps.failedTabs.value = nextFailed;
    if (failed) deps.loadedTabs.value.delete(name);
  }

  return {
    ensureMeta,
    loadWarehouses,
    loadWarehousesSoft,
    loadSuppliers,
    loadSuppliersSoft,
    loadPurchase,
    loadReturnablePurchaseOrders,
    loadReturns,
    loadOutbounds,
    loadTransit,
    loadInventory,
    loadMovements,
    loadMonthlyClose,
    loadMonthlyRestockDetail,
    loadSuggestions,
    loadPayables,
    loadPayableSummary,
    loadStocktakes,
    loadBins,
    loadBinStock,
    loadTransfers,
    loadWarehouseTabData,
    loadTab
  };
}
