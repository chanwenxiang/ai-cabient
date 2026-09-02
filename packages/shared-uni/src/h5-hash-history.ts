/**
 * uni-app H5 history 模式下，旧书签/文档里的 `#/pages/...` 不会走路由，
 * 会落到 pages.json 首屏（商户/消费者多为登录页），表现为「掉登录」。
 */

const HASH_PAGE_PREFIX = '#/pages/';

/** 从 location.hash 提取 history 路径（含 query），如 `/pages/foo/bar?id=1`。 */
export function extractHistoryPathFromHash(hash: string): string | null {
  const raw = String(hash || '').trim();
  if (!raw.startsWith(HASH_PAGE_PREFIX)) return null;
  const pathWithQuery = raw.slice(1);
  if (!pathWithQuery.startsWith('/pages/')) return null;
  return pathWithQuery;
}

/**
 * 将 `#/pages/...` 转为 `/pages/...`（replaceState，不刷新）。
 * 须在 uni-app 路由初始化前调用；index.html 内联脚本 + main.ts 双保险。
 */
export function normalizeH5HashToHistory(): boolean {
  if (typeof globalThis.location === 'undefined' || typeof history === 'undefined') return false;
  const target = extractHistoryPathFromHash(globalThis.location.hash);
  if (!target) return false;
  history.replaceState(history.state, '', target);
  return true;
}

/** index.html 内联引导：在 main 模块加载前执行。 */
export const H5_HASH_HISTORY_BOOTSTRAP =
  "(function(){var h=location.hash||'';if(h.indexOf('#/pages/')===0){history.replaceState(null,'',h.slice(1));}})();";
