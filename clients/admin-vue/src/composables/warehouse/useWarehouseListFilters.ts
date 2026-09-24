import { computed, type Ref } from 'vue';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

/** 仓储动态行（D15：禁止散落 Record<string, any>） */
export type WarehouseFilterRow = AdminDynamicRow;

export type UseWarehouseListFiltersDeps = {
  tab: Ref<string>;
  page: Ref<number>;
  selectedKeys: Ref<Array<string | number>>;
  keyword: Ref<string>;
  filterWarehouseId: Ref<string>;
  filterOutboundStatus: Ref<string>;
  overdueOnly: Ref<boolean>;
  focusDeviceId: Ref<string>;
  suppliers: Ref<WarehouseFilterRow[]>;
  warehouses: Ref<WarehouseFilterRow[]>;
  purchaseOrders: Ref<WarehouseFilterRow[]>;
  purchaseReturns: Ref<WarehouseFilterRow[]>;
  outbounds: Ref<WarehouseFilterRow[]>;
  inTransit: Ref<WarehouseFilterRow[]>;
  hideTestPurchaseOrders: Ref<boolean>;
  hideTestPoKey: string;
  syncRouteQuery: (nextTab?: string) => void;
  loadTab: (name: string, force?: boolean) => Promise<void>;
  loadedTabs: Ref<Set<string>>;
  serverPaginatedTabs: Set<string>;
  supplierName: (id: string) => string;
};

/** Matches AdminDashboardService.IN_TRANSIT_OVERDUE_HOURS */
export const TRANSIT_OVERDUE_HOURS = 24;
const TRANSIT_OVERDUE_MS = TRANSIT_OVERDUE_HOURS * 3600 * 1000;
const TRANSIT_DUE_SOON_MS = 4 * 3600 * 1000;

const OUTBOUND_STATUS_RANK: Record<string, number> = {
  PICKED: 0,
  DRAFT: 1,
  SHIPPED: 2,
  CANCELLED: 3
};

const FILTER_BAR_TABS = [
  'suppliers',
  'purchase',
  'returns',
  'suggestions',
  'payables',
  'stocktakes',
  'bins',
  'inventory',
  'movements',
  'outbounds',
  'transit'
] as const;

/**
 * 仓储列表筛选：filter-bar 显隐、出库/在途过滤与行样式、筛选项变更后重置分页。
 */
export function useWarehouseListFilters(deps: UseWarehouseListFiltersDeps) {
  /** 仅真正有筛选项的 Tab 才挂 filter-bar，避免空条占位像「中间少了字」 */
  const showFilterBar = computed(() =>
    FILTER_BAR_TABS.includes(deps.tab.value as (typeof FILTER_BAR_TABS)[number])
  );

  const activeSuppliers = computed(() => deps.suppliers.value.filter((s) => s.status === 'ACTIVE'));
  const activeWarehouses = computed(() =>
    deps.warehouses.value.filter((w) => (w.status || 'ACTIVE') === 'ACTIVE')
  );
  const filteredSuppliers = computed(() => deps.suppliers.value);
  const filteredPurchaseOrders = computed(() => deps.purchaseOrders.value);

  const filteredPurchaseReturns = computed(() => {
    const q = deps.keyword.value.trim().toLowerCase();
    let list = deps.purchaseReturns.value;
    if (deps.filterWarehouseId.value) {
      list = list.filter((r) => r.warehouseId === deps.filterWarehouseId.value);
    }
    if (!q) return list;
    return list.filter((r) =>
      [r.returnId, r.purchaseOrderId, r.supplierId, deps.supplierName(r.supplierId)]
        .join(' ')
        .toLowerCase()
        .includes(q)
    );
  });

  function isOutboundActionable(row: WarehouseFilterRow) {
    const hasLines = (row.lines?.length || 0) > 0;
    return (row.status === 'DRAFT' && hasLines) || (row.status === 'PICKED' && hasLines);
  }

  const filteredOutbounds = computed(() => {
    let list = deps.outbounds.value;
    const st = deps.filterOutboundStatus.value;
    if (st === 'actionable') {
      list = list.filter((o) => isOutboundActionable(o));
    } else if (st) {
      list = list.filter((o) => o.status === st);
    }
    return [...list].sort((a, b) => {
      const ra = OUTBOUND_STATUS_RANK[String(a.status)] ?? 9;
      const rb = OUTBOUND_STATUS_RANK[String(b.status)] ?? 9;
      if (ra !== rb) return ra - rb;
      return Number(b.outboundId) - Number(a.outboundId);
    });
  });

  function outboundRowClassName({ row }: { row: WarehouseFilterRow }) {
    const parts = [`outbound-tr-${row.outboundId}`];
    if (isOutboundActionable(row)) parts.push('outbound-row--actionable');
    return parts.join(' ');
  }

  function onOutboundStatusFilter() {
    deps.page.value = 1;
    deps.selectedKeys.value = [];
  }

  function parseTs(value: unknown) {
    if (value == null || value === '') return Number.NaN;
    if (typeof value === 'number') return value;
    const t = Date.parse(String(value));
    return Number.isNaN(t) ? Number.NaN : t;
  }

  function transitCreatedMs(row: WarehouseFilterRow) {
    return parseTs(row.createdAt);
  }

  function transitAgeMs(row: WarehouseFilterRow) {
    const t = transitCreatedMs(row);
    return Number.isNaN(t) ? 0 : Math.max(0, Date.now() - t);
  }

  function transitRemainMs(row: WarehouseFilterRow) {
    const t = transitCreatedMs(row);
    if (Number.isNaN(t)) return TRANSIT_OVERDUE_MS;
    return Math.max(0, t + TRANSIT_OVERDUE_MS - Date.now());
  }

  function transitOverdueMs(row: WarehouseFilterRow) {
    const t = transitCreatedMs(row);
    if (Number.isNaN(t)) return 0;
    return Math.max(0, Date.now() - (t + TRANSIT_OVERDUE_MS));
  }

  function isTransitOverdue(row: WarehouseFilterRow) {
    const t = transitCreatedMs(row);
    if (Number.isNaN(t)) return false;
    return Date.now() - t >= TRANSIT_OVERDUE_MS;
  }

  function isTransitDueSoon(row: WarehouseFilterRow) {
    if (isTransitOverdue(row)) return false;
    const left = transitRemainMs(row);
    return left > 0 && left <= TRANSIT_DUE_SOON_MS;
  }

  function formatAge(ms: number) {
    const abs = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(abs / 3600);
    const m = Math.floor((abs % 3600) / 60);
    if (h >= 48) return `${Math.floor(h / 24)} 天`;
    if (h > 0) return `${h} 小时 ${m} 分`;
    if (m > 0) return `${m} 分钟`;
    return '不到 1 分钟';
  }

  function transitRowClassName({ row }: { row: WarehouseFilterRow }) {
    const classes: string[] = [];
    if (isTransitOverdue(row)) classes.push('is-overdue');
    else if (isTransitDueSoon(row)) classes.push('is-due-soon');
    if (deps.focusDeviceId.value && row.deviceId === deps.focusDeviceId.value) {
      classes.push('is-focus');
    }
    return classes.join(' ');
  }

  const filteredInTransit = computed(() => {
    let list = [...deps.inTransit.value];
    if (deps.focusDeviceId.value) {
      list = list.filter((r) => r.deviceId === deps.focusDeviceId.value);
    }
    if (deps.overdueOnly.value) {
      list = list.filter((r) => isTransitOverdue(r));
    }
    return list.sort((a, b) => {
      const ao = isTransitOverdue(a) ? 0 : 1;
      const bo = isTransitOverdue(b) ? 0 : 1;
      if (ao !== bo) return ao - bo;
      const at = transitCreatedMs(a);
      const bt = transitCreatedMs(b);
      if (Number.isNaN(at) && Number.isNaN(bt)) return 0;
      if (Number.isNaN(at)) return 1;
      if (Number.isNaN(bt)) return -1;
      return at - bt;
    });
  });

  const overdueTransitCount = computed(() => {
    let list = deps.inTransit.value;
    if (deps.focusDeviceId.value) {
      list = list.filter((r) => r.deviceId === deps.focusDeviceId.value);
    }
    return list.filter((r) => isTransitOverdue(r)).length;
  });

  const transitEmptyHint = computed(() => {
    if (deps.overdueOnly.value) {
      return deps.focusDeviceId.value
        ? `设备 ${deps.focusDeviceId.value} 无超过 ${TRANSIT_OVERDUE_HOURS} 小时的到柜超时`
        : `当前无超过 ${TRANSIT_OVERDUE_HOURS} 小时的到柜超时`;
    }
    if (deps.focusDeviceId.value) {
      return `设备 ${deps.focusDeviceId.value} 暂无在途（已到柜签收或不在发运中）`;
    }
    return '暂无在途（发运后出现于此；补货员到柜完成任务后自动消失）';
  });

  function onOverdueToggle() {
    deps.page.value = 1;
    deps.selectedKeys.value = [];
    deps.syncRouteQuery();
  }

  function clearFocusDevice() {
    deps.focusDeviceId.value = '';
    deps.page.value = 1;
    deps.syncRouteQuery();
  }

  const pagedOutbounds = computed(() => filteredOutbounds.value);
  const pagedInTransit = computed(() => filteredInTransit.value);

  function onWarehouseFilter() {
    deps.page.value = 1;
    if (deps.serverPaginatedTabs.has(deps.tab.value)) {
      deps.loadedTabs.value.delete(deps.tab.value);
      void deps.loadTab(deps.tab.value, true);
    }
  }

  function onPurchaseFilterChange() {
    localStorage.setItem(deps.hideTestPoKey, deps.hideTestPurchaseOrders.value ? '1' : '0');
    deps.page.value = 1;
    deps.loadedTabs.value.delete('purchase');
    void deps.loadTab('purchase', true);
  }

  function onSuggestionParamsChange() {
    deps.page.value = 1;
    deps.loadedTabs.value.delete('suggestions');
    void deps.loadTab('suggestions', true);
  }

  function onPayableFilter() {
    deps.page.value = 1;
    deps.loadedTabs.value.delete('payables');
    void deps.loadTab('payables', true);
  }

  function onStocktakeFilter() {
    deps.page.value = 1;
    deps.loadedTabs.value.delete('stocktakes');
    void deps.loadTab('stocktakes', true);
  }

  function onBinFilter() {
    deps.page.value = 1;
    deps.loadedTabs.value.delete('bins');
    void deps.loadTab('bins', true);
  }

  return {
    TRANSIT_OVERDUE_HOURS,
    showFilterBar,
    activeSuppliers,
    activeWarehouses,
    filteredSuppliers,
    filteredPurchaseOrders,
    filteredPurchaseReturns,
    isOutboundActionable,
    filteredOutbounds,
    outboundRowClassName,
    onOutboundStatusFilter,
    transitAgeMs,
    transitRemainMs,
    transitOverdueMs,
    isTransitOverdue,
    isTransitDueSoon,
    formatAge,
    transitRowClassName,
    filteredInTransit,
    overdueTransitCount,
    transitEmptyHint,
    onOverdueToggle,
    clearFocusDevice,
    pagedOutbounds,
    pagedInTransit,
    onWarehouseFilter,
    onPurchaseFilterChange,
    onSuggestionParamsChange,
    onPayableFilter,
    onStocktakeFilter,
    onBinFilter
  };
}
