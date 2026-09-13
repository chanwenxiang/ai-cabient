import { type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { errorMessage } from '@/utils/error-message';
import type { createLoadSeq } from '@/composables/createLoadSeq';

/** 仓储多 Tab 共用行（字段随业务表变化） */
export type WarehouseTabRow = Record<string, any>;

export type UseWarehouseTabLoaderDeps = {
  loadSeq: ReturnType<typeof createLoadSeq>;
  hasDeviceListPerm: () => boolean;
  page: Ref<number>;
  size: Ref<number>;
  keyword: Ref<string>;
  filterWarehouseId: Ref<string>;
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
  bins: Ref<WarehouseTabRow[]>;
  binStock: Ref<WarehouseTabRow[]>;
  transfers: Ref<WarehouseTabRow[]>;
  devices: Ref<WarehouseTabRow[]>;
  skus: Ref<WarehouseTabRow[]>;
  tabTotals: Ref<Record<string, number>>;
  loadedTabs: Ref<Set<string>>;
  loadingTabs: Ref<Set<string>>;
  hydratedTabs: Ref<Set<string>>;
};

/**
 * 仓储各 Tab 列表加载与 hydrated/loaded 编排。
 * 写路径弹窗仍由各 useWarehouse*  composable 负责。
 */
export function useWarehouseTabLoader(deps: UseWarehouseTabLoaderDeps) {
  async function ensureMeta() {
    if (!deps.devices.value.length) {
      if (!deps.hasDeviceListPerm()) {
        deps.devices.value = [];
      } else {
        deps.devices.value = await api
          .request<WarehouseTabRow[]>('/api/v2/ops/admin/devices/ref', 'GET')
          .catch(() => []);
      }
    }
    if (!deps.skus.value.length) {
      deps.skus.value =
        (
          await api
            .request<{ items: WarehouseTabRow[] }>('/api/v2/ops/admin/skus?page=0&size=500', 'GET')
            .catch(() => ({ items: [] as WarehouseTabRow[] }))
        ).items || [];
    }
  }

  function warehouseListParams() {
    const q = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.keyword.value.trim()) q.set('q', deps.keyword.value.trim());
    if (deps.filterWarehouseId.value) q.set('warehouseId', deps.filterWarehouseId.value);
    return q;
  }

  async function loadWarehouses() {
    const seq = deps.loadSeq.begin('loadWarehouses');
    const q = new URLSearchParams({
      page: String(deps.page.value - 1),
      size: String(deps.size.value)
    });
    if (deps.keyword.value.trim()) q.set('q', deps.keyword.value.trim());
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      `/api/v2/ops/admin/warehouse/list?${q}`,
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
        '/api/v2/ops/admin/warehouse/list?page=0&size=500',
        'GET'
      );
      if (!deps.loadSeq.isCurrent(seq, 'loadWarehousesSoft')) return;
      deps.warehouses.value = data.items || [];
    } catch {
      if (!deps.loadSeq.isCurrent(seq, 'loadWarehousesSoft')) return;
      /* 筛选用元数据失败时保留旧列表，不拖垮库存/出库主数据 */
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
      `/api/v2/ops/admin/suppliers?${q}`,
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
        '/api/v2/ops/admin/suppliers?page=0&size=500',
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
    const q = warehouseListParams();
    if (deps.hideTestPurchaseOrders.value) q.set('excludeTestRef', 'true');
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      `/api/v2/ops/admin/purchase-orders?${q}`,
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
            '/api/v2/ops/admin/purchase-orders?returnableOnly=true&page=0&size=500',
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
      `/api/v2/ops/admin/purchase-returns?${q}`,
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
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      `/api/v2/ops/admin/warehouse/outbounds?${warehouseListParams()}`,
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
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      `/api/v2/ops/admin/warehouse/in-transit?${q}`,
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
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      `/api/v2/ops/admin/warehouse/inventory?${warehouseListParams()}`,
      'GET'
    );
    if (!deps.loadSeq.isCurrent(seq, 'loadInventory')) return;
    deps.inventory.value = data.items || [];
    deps.tabTotals.value = {
      ...deps.tabTotals.value,
      inventory: Number(data.total) || 0
    };
  }

  async function loadMovements() {
    const seq = deps.loadSeq.begin('loadMovements');
    const data = await api.request<{ items: WarehouseTabRow[]; total: number }>(
      `/api/v2/ops/admin/warehouse/movements?${warehouseListParams()}`,
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
      `/api/v2/ops/admin/procurement/suggestions?${params}`,
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
      `/api/v2/ops/admin/suppliers/payables?${params}`,
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
    const rows = await api
      .request<WarehouseTabRow[]>('/api/v2/ops/admin/suppliers/payables/summary', 'GET')
      .catch(() => []);
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
      `/api/v2/ops/admin/warehouse/stocktakes?${params}`,
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
    const rows = await api.request<WarehouseTabRow[]>('/api/v2/ops/admin/warehouse/bins', 'GET');
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
      `/api/v2/ops/admin/warehouse/bins/stock?${params}`,
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
      `/api/v2/ops/admin/warehouse/transfers?${q}`,
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
            console.warn('[warehouse] 退货弹窗预载采购单失败', err);
          }),
          loadSuppliersSoft(),
          loadWarehousesSoft()
        ]),
      suggestions: () => Promise.all([loadSuggestions(), loadWarehousesSoft(), loadSuppliersSoft()]),
      payables: () => Promise.all([loadPayables(), loadPayableSummary(), loadSuppliersSoft()]),
      stocktakes: () => Promise.all([loadStocktakes(), loadWarehousesSoft()]),
      bins: () => Promise.all([loadBins(), loadBinStock(), loadWarehousesSoft()]),
      outbounds: () => Promise.all([loadOutbounds(), loadWarehousesSoft()]),
      transit: () => loadTransit(),
      transfers: () => Promise.all([loadTransfers(), loadWarehousesSoft()]),
      inventory: () => Promise.all([loadInventory(), loadWarehousesSoft()]),
      movements: () => Promise.all([loadMovements(), loadWarehousesSoft()])
    };
    const loader = loaders[name];
    if (loader) await loader();
  }

  async function loadTab(name: string, force = false) {
    const seq = deps.loadSeq.begin('loadTab');
    if (!force && deps.loadedTabs.value.has(name) && name !== 'inventory' && name !== 'movements') {
      return;
    }
    const nextLoading = new Set(deps.loadingTabs.value);
    nextLoading.add(name);
    deps.loadingTabs.value = nextLoading;
    try {
      await ensureMeta();
      await loadWarehouseTabData(name);
      deps.loadedTabs.value.add(name);
    } catch (e) {
      if (!deps.loadSeq.isCurrent(seq, 'loadTab')) return;
      ElMessage.error(errorMessage(e, '加载失败'));
    } finally {
      if (!deps.loadSeq.isCurrent(seq, 'loadTab')) return;
      const next = new Set(deps.hydratedTabs.value);
      next.add(name);
      deps.hydratedTabs.value = next;
      const doneLoading = new Set(deps.loadingTabs.value);
      doneLoading.delete(name);
      deps.loadingTabs.value = doneLoading;
    }
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
