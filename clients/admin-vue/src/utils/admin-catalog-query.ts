/**
 * 运营后台「下拉 / 选项」伪全量拉取约定（debt-tracker D7）。
 *
 * - 列表主表必须走真实分页，禁止用本模块的 size。
 * - 下拉缓存统一 500（目录）或 200（轻量选项）；禁止再散落魔法数。
 * - 若业务量可能超过上限：改远程搜索 / 懒加载，不要盲目加大 size。
 */

export const ADMIN_CATALOG_PAGE_SIZE = 500;
export const ADMIN_OPTIONS_PAGE_SIZE = 200;

type QueryValue = string | number | boolean | null | undefined;

function appendExtra(params: URLSearchParams, extra?: Record<string, QueryValue>) {
  if (!extra) return;
  for (const [key, value] of Object.entries(extra)) {
    if (value == null || value === '') continue;
    params.set(key, String(value));
  }
}

/** page=0&size=500，可附带过滤字段（如 status=ACTIVE） */
export function adminCatalogQuery(extra?: Record<string, QueryValue>): string {
  const params = new URLSearchParams();
  params.set('page', '0');
  params.set('size', String(ADMIN_CATALOG_PAGE_SIZE));
  appendExtra(params, extra);
  return params.toString();
}

/** page=0&size=200，轻量选项（设备多选等） */
export function adminOptionsQuery(extra?: Record<string, QueryValue>): string {
  const params = new URLSearchParams();
  params.set('page', '0');
  params.set('size', String(ADMIN_OPTIONS_PAGE_SIZE));
  appendExtra(params, extra);
  return params.toString();
}
