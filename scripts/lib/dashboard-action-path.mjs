/**
 * MA-279：运营台待办「判据路径」与「实际跳转」必须同源。
 *
 * 背景：DashboardView.vue 里 `canHandleAction()` 用 `actionTargetPath(row)` 查
 * `canAccessPath()`（判有没有权限、出不出按钮），而点击后 `goAction()` 走 `router.push`。
 * LOW_STOCK 曾判据指 `/replenishment`（ops:replenishment:list）、实际跳 `/stock-health`
 * （ops:stock-health:list）⇒ 有后者权限的人看不到按钮，有前者权限的人点进去被路由拦下。
 * 与 `check-approval-action-path-nav` 的「菜单登记同源」是同一族：只钉一侧必然漏。
 */
const CASE_RE = /case\s+'([A-Z_]+)'\s*:/g;

/** 取一个 switch 函数体里每个 case 对应的路由路径（连续 case 共享同一段时都映射到它）。 */
export function casePaths(src, fnName) {
  const start = src.indexOf(`function ${fnName}(`);
  if (start < 0) return null;
  const next = src.indexOf('\nfunction ', start + 10);
  const body = src.slice(start, next < 0 ? src.length : next);
  const parts = body.split(CASE_RE);
  // parts = [prefix, TYPE1, seg1, TYPE2, seg2, ...]
  const out = new Map();
  const pending = [];
  for (let i = 1; i + 1 < parts.length; i += 2) {
    const type = parts[i];
    const seg = parts[i + 1];
    const p = (seg.match(/['"](\/[A-Za-z0-9/_-]+)['"]/) || [])[1];
    if (p) {
      out.set(type, p);
      for (const t of pending) out.set(t, p);
      pending.length = 0;
    } else {
      pending.push(type);
    }
  }
  return out;
}

/** 返回不一致的 case 描述列表；空数组 = 通过。 */
export function mismatchedActionPaths(src) {
  const target = casePaths(src, 'actionTargetPath');
  const action = casePaths(src, 'goAction');
  if (!target || !action) return ['找不到 actionTargetPath / goAction 的函数体（判据失效）'];
  if (target.size === 0 || action.size === 0) return ['两个 switch 都没解析出 case（锚点被改坏）'];
  const bad = [];
  for (const [type, p] of target) {
    if (action.has(type) && action.get(type) !== p) {
      bad.push(`${type}: 判据=${p} 跳转=${action.get(type)}`);
    }
  }
  return bad;
}
