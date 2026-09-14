/**
 * A-P2-005 试点：运营后台高频端点集中定义。
 * 新调用优先从此处取路径，禁止业务层再散落同款裸字符串。
 * 与后端 `ApiVersions.PATH_PREFIX` / shared-api `API_PREFIX` 对齐（当前 v2）。
 */
export const API_PREFIX = '/api/v2' as const;

const ops = `${API_PREFIX}/ops/admin` as const;

/** 鉴权相关（与 AdminEndpoints 并列，供 client 与门禁使用）。 */
export const AuthEndpoints = {
  logout: `${API_PREFIX}/auth/logout`
} as const;

export const AdminEndpoints = {
  /** 工作台 KPI */
  stats: `${ops}/stats`,
  workbench: `${ops}/workbench`,
  workbenchBundle: `${ops}/workbench-bundle`,
  dataScope: `${ops}/data-scope`,
  sla: `${ops}/sla`,
  financeStats: `${ops}/finance/stats`,
  deviceAvailabilityKpi: `${ops}/device-availability-kpi`,
  deviceAvailabilityKpiOn: (date?: string) =>
    date
      ? `${ops}/device-availability-kpi?date=${encodeURIComponent(date)}`
      : `${ops}/device-availability-kpi`,
  /** 设备下拉参照 */
  devicesRef: `${ops}/devices/ref`,
  trend: (days: number) => `${ops}/trend?days=${days}`,
  trendOps: (days: number) => `${ops}/trend/ops?days=${days}`,
  trendChannels: (days: number) => `${ops}/trend/channels?days=${days}`
} as const;

/** 门禁扫描用：这些字面量不得再出现在 views/composables（endpoints.ts 除外）。 */
export const ADMIN_ENDPOINT_PILOT_LITERALS = [
  '/api/v2/ops/admin/stats',
  '/api/v2/ops/admin/workbench',
  '/api/v2/ops/admin/workbench-bundle',
  '/api/v2/ops/admin/data-scope',
  '/api/v2/ops/admin/sla',
  '/api/v2/ops/admin/finance/stats',
  '/api/v2/ops/admin/device-availability-kpi',
  '/api/v2/ops/admin/devices/ref'
] as const;
