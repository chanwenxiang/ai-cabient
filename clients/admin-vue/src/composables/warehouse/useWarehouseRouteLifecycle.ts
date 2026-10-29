import { onActivated, onMounted, onUnmounted, watch, type Ref } from 'vue';
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router';
import { onPurchaseOrderReviewed } from '@/utils/purchase-order-sync';
import { adminDevWarn } from '@/utils/admin-dev-log';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

/** 仓储动态行（D15：禁止散落 Record<string, any>） */
export type WarehouseRouteRow = AdminDynamicRow;

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
  /** D28-B4：这个 tab 当前用户有没有**读**权限（判据照后端注解，见 WarehouseView 的 isWarehouseTabAllowed）。 */
  isTabAllowed: (name: string) => boolean;
  /** D28-B4：权限全挡／深链指向无权 tab 时的落点；返回空串表示一个都不可用。 */
  firstAllowedTab: () => string;
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
    const qDevice = typeof deps.route.query.deviceId === 'string' ? deps.route.query.deviceId : '';
    if (qDevice !== deps.focusDeviceId.value) {
      deps.focusDeviceId.value = qDevice;
    }
  }

  function applyTabFromQuery() {
    const qTab = typeof deps.route.query.tab === 'string' ? deps.route.query.tab : '';
    const qDevice = typeof deps.route.query.deviceId === 'string' ? deps.route.query.deviceId : '';
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
      'movements',
      'monthly',
      'channel-import'
    ];
    if (allowed.includes(qTab) && deps.isTabAllowed(qTab) && deps.tab.value !== qTab) {
      deps.tab.value = qTab;
    } else if (!qTab && qDevice) {
      // deviceId deep-link without tab → in-transit (replenishment / dashboard)
      if (deps.tab.value !== 'transit' && deps.isTabAllowed('transit')) deps.tab.value = 'transit';
    } else if (!qTab && deps.tab.value !== 'warehouses' && !qDevice) {
      // keep current tab when user switched locally; only reset when query fully cleared
    }
    // 深链指向无权 tab 时别留在那里（内容区会空白，且 onMounted 会先打一发必 403 的请求）
    if (!deps.isTabAllowed(deps.tab.value)) {
      const next = deps.firstAllowedTab();
      if (next) deps.tab.value = next;
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

  /**
   * D28-B4：本页一个 tab 都没权限时（如只有 `ops:warehouse:list` 的人打开采购子页），
   * 页签已经不渲染了，但挂载时若照旧 `loadTab` 就会先打一发必 403 的请求 —— 别发。
   */
  function loadCurrentIfAllowed(force = true) {
    if (!deps.isTabAllowed(deps.tab.value)) return Promise.resolve();
    return deps.loadTab(deps.tab.value, force);
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
    await loadCurrentIfAllowed();
  });

  onUnmounted(() => {
    offPurchaseReviewed?.();
  });

  onActivated(() => {
    applyTabFromQuery();
    void loadCurrentIfAllowed();
  });

  watch(
    () => [deps.route.query.tab, deps.route.query.overdue, deps.route.query.deviceId] as const,
    () => {
      applyTabFromQuery();
      void loadCurrentIfAllowed();
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
