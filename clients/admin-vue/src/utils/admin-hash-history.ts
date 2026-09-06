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

/**
 * 将 `/admin/index.html#/warehouse` → `/admin/warehouse`；
 * 裸 `/admin/index.html` → `/admin/`（避免登录 redirect=/index.html 落「页面不存在」）。
 */
export function normalizeAdminIndexHtmlHash(): boolean {
  if (typeof globalThis.location === 'undefined') return false;
  const { pathname, hash, search } = globalThis.location;
  if (!/\/index\.html$/i.test(pathname)) return false;
  const base = pathname.replace(/\/index\.html$/i, '');
  const baseSlash = base.endsWith('/') ? base : `${base}/`;
  const routePath = extractRouteFromIndexHtmlHash(hash);
  const target = routePath
    ? `${baseSlash}${routePath.replace(/^\//, '')}${search || ''}`
    : `${baseSlash}${search || ''}`;
  globalThis.location.replace(target);
  return true;
}

/** index.html 内联：须在 main 模块加载前执行（含裸 index.html → 目录入口）。 */
export const ADMIN_HASH_BOOTSTRAP =
  "(function(){var p=location.pathname||'';if(!/\\/index\\.html$/i.test(p))return;var b=p.replace(/\\/index\\.html$/i,'');if(b.slice(-1)!=='/')b+='/';var h=location.hash||'';var r='';if(h.indexOf('#/')===0){r=h.slice(1);if(!r||r.charAt(0)!=='/')r='';}location.replace(b+(r?r.replace(/^\\//,''):'')+(location.search||''));})();";
