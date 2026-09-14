import { onActivated, onMounted, onUnmounted, watch, type Ref } from 'vue';
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router';
import { onPurchaseOrderReviewed } from '@/utils/purchase-order-sync';
import { adminDevWarn } from '@/utils/admin-dev-log';

/** 仓储多 Tab 共用行（字段随业务表变化） */
export type WarehouseRouteRow = Record<string, any>;

export type WarehouseTabGroup = 'overview' | 'procurement' | 'inventory' | 'fulfillment';

export type UseWarehouseRouteLifecycleDeps = {
  route: RouteLocationNormalizedLoaded;
  router: Router;
  tab: Ref<string>;
  tabGroup: Ref<WarehouseTabGroup>;
  page: Ref<number>;
  overdueOnly: Ref<boolean>;
  focusDeviceId: Ref<string>;
  loadedTabs: Ref<Set<string>>;
  serverPaginatedTabs: Set<string>;
  syncTabGroupFromTab: (name?: string) => void;
  tabGroupFor: (name: string) => WarehouseTabGroup;
  loadTab: (name: string, force?: boolean) => Promise<void>;
  patchPurchaseOrderRow: (updated: WarehouseRouteRow) => void;
};

/**
 * 仓储页路由深链、分页切换与 keep-alive 激活时的列表刷新。
 */
export function useWarehouseRouteLifecycle(deps: UseWarehouseRouteLifecycleDeps) {
  function syncRouteQuery(nextTab = deps.tab.value) {
    const query: Record<string, string> = {
      ...Object.fromEntries(
        Object.entries(deps.route.query)
          .filter((entry): entry is [string, string] => typeof entry[1] === 'string')
          .filter(([k]) => !['tab', 'overdue', 'deviceId'].includes(k))
      )
    };
    if (nextTab && nextTab !== 'warehouses') query.tab = nextTab;
    if (nextTab === 'transit') {
      if (deps.overdueOnly.value) query.overdue = '1';
      if (deps.focusDeviceId.value) query.deviceId = deps.focusDeviceId.value;
    }
    const same =
      String(deps.route.query.tab || '') === String(query.tab || '') &&
      String(deps.route.query.overdue || '') === String(query.overdue || '') &&
      String(deps.route.query.deviceId || '') === String(query.deviceId || '');
    if (!same) {
      void deps.router.replace({ query });
    }
  }

  function applyQueryFilters() {
    const qOverdue = deps.route.query.overdue === '1' || deps.route.query.overdue === 'true';
    if (qOverdue !== deps.overdueOnly.value) {
      deps.overdueOnly.value = qOverdue;
    }
    const qDevice =
      typeof deps.route.query.deviceId === 'string' ? deps.route.query.deviceId : '';
    if (qDevice !== deps.focusDeviceId.value) {
      deps.focusDeviceId.value = qDevice;
    }
  }

  function applyTabFromQuery() {
    const qTab = typeof deps.route.query.tab === 'string' ? deps.route.query.tab : '';
    const qDevice =
      typeof deps.route.query.deviceId === 'string' ? deps.route.query.deviceId : '';
    const allowed = [
      'warehouses',
      'transfers',
      'suppliers',
      'purchase',
      'returns',
      'suggestions',
      'payables',
      'stocktakes',
      'bins',
      'outbounds',
      'transit',
      'inventory',
      'movements'
    ];
    if (allowed.includes(qTab) && deps.tab.value !== qTab) {
      deps.tab.value = qTab;
    } else if (!qTab && qDevice) {
      // deviceId deep-link without tab → in-transit (replenishment / dashboard)
      if (deps.tab.value !== 'transit') deps.tab.value = 'transit';
    } else if (!qTab && deps.tab.value !== 'warehouses' && !qDevice) {
      // keep current tab when user switched locally; only reset when query fully cleared
    }
    deps.syncTabGroupFromTab(deps.tab.value);
    if (deps.tab.value === 'transit') {
      applyQueryFilters();
    } else {
      deps.overdueOnly.value = false;
      deps.focusDeviceId.value = '';
    }
  }

  function onPagerChange() {
    if (deps.serverPaginatedTabs.has(deps.tab.value)) {
      void deps.loadTab(deps.tab.value, true);
    }
  }

  function onPagerSizeChange() {
    deps.page.value = 1;
    if (deps.serverPaginatedTabs.has(deps.tab.value)) {
      void deps.loadTab(deps.tab.value, true);
    }
  }

  function onTabChange(name: string | number) {
    deps.page.value = 1;
    const next = String(name);
    deps.tabGroup.value = deps.tabGroupFor(next);
    if (next !== 'transit') {
      deps.overdueOnly.value = false;
      deps.focusDeviceId.value = '';
    }
    syncRouteQuery(next);
    void deps.loadTab(next);
  }

  function reloadCurrent() {
    deps.loadedTabs.value.delete(deps.tab.value);
    void deps.loadTab(deps.tab.value, true);
  }

  let offPurchaseReviewed: (() => void) | undefined;

  onMounted(async () => {
    offPurchaseReviewed = onPurchaseOrderReviewed((updated) => {
      deps.patchPurchaseOrderRow(updated as WarehouseRouteRow);
      if (deps.tab.value === 'purchase') {
        deps.loadedTabs.value.delete('purchase');
        deps.loadTab('purchase', true).catch((err) => {
          adminDevWarn('[warehouse] 采购单更新后刷新列表失败', err);
        });
      }
    });
    applyTabFromQuery();
    await deps.loadTab(deps.tab.value, true);
  });

  onUnmounted(() => {
    offPurchaseReviewed?.();
  });

  onActivated(() => {
    applyTabFromQuery();
    void deps.loadTab(deps.tab.value, true);
  });

  watch(
    () =>
      [deps.route.query.tab, deps.route.query.overdue, deps.route.query.deviceId] as const,
    () => {
      applyTabFromQuery();
      void deps.loadTab(deps.tab.value, true);
    }
  );

  return {
    syncRouteQuery,
    applyTabFromQuery,
    onPagerChange,
    onPagerSizeChange,
    onTabChange,
    reloadCurrent
  };
}
