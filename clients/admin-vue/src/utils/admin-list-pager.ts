/** 运营后台列表分页：与 A-P1-002 pageSize 上限对齐，防止 1000+ 行卡顿回潮。 */
export const ADMIN_LIST_MAX_PAGE_SIZE = 50;

export const ADMIN_LIST_PAGE_SIZES = [10, 20, 50] as const;

export type AdminListPageSize = (typeof ADMIN_LIST_PAGE_SIZES)[number];

/** 将分页 size 钳到 [1, ADMIN_LIST_MAX_PAGE_SIZE]。 */
export function clampAdminPageSize(raw: unknown, fallback: AdminListPageSize = 20): number {
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 1) return fallback;
  return Math.min(Math.floor(n), ADMIN_LIST_MAX_PAGE_SIZE);
}
