/**
 * 运营后台 history 路由：`/admin/index.html#/warehouse` 会被 Vue 当成 `/index.html`。
 * 在应用启动前将 hash 转为干净 path（IMP-001）。
 */

/** 从 `index.html#/foo` 提取 `/foo`（含 query 时 hash 内一般不携带，保留 location.search）。 */
export function extractRouteFromIndexHtmlHash(hash: string): string | null {
  const raw = String(hash || '').trim();
  if (!raw.startsWith('#/')) return null;
  const routePath = raw.slice(1);
  if (!routePath.startsWith('/')) return null;
  return routePath;
}

/** 将 `/admin/index.html#/warehouse` 重定向为 `/admin/warehouse`。 */
export function normalizeAdminIndexHtmlHash(): boolean {
  if (typeof globalThis.location === 'undefined') return false;
  const { pathname, hash, search } = globalThis.location;
  if (!/\/index\.html$/i.test(pathname)) return false;
  const routePath = extractRouteFromIndexHtmlHash(hash);
  if (!routePath) return false;
  const base = pathname.replace(/\/index\.html$/i, '');
  const baseSlash = base.endsWith('/') ? base : `${base}/`;
  const target = `${baseSlash}${routePath.replace(/^\//, '')}${search || ''}`;
  globalThis.location.replace(target);
  return true;
}

/** index.html 内联：须在 main 模块加载前执行。 */
export const ADMIN_HASH_BOOTSTRAP =
  "(function(){var p=location.pathname||'';if(!/\\/index\\.html$/i.test(p))return;var h=location.hash||'';if(h.indexOf('#/')!==0)return;var r=h.slice(1);if(!r||r.charAt(0)!=='/')return;var b=p.replace(/\\/index\\.html$/i,'');if(b.slice(-1)!=='/')b+='/';location.replace(b+r.replace(/^\\//,'')+(location.search||''));})();";
