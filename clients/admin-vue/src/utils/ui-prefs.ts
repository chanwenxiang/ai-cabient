/**
 * 后台「界面偏好」的按路由读写收口（列显示 / 列顺序 / 每页条数）。
 *
 * 为什么按**路由**隔离：不同列表页的列集合与自然页长不同，混存会互相污染 ——
 * 在 /orders 把「设备」列勾掉，不该让 /sessions 也跟着少一列。
 *
 * 为什么落 localStorage 不违反 A-P2-006：那条约束的是 **JWT 只许 sessionStorage**
 * （见 api/auth-storage.ts 的注释与迁移逻辑），界面偏好是纯本机体验、不含凭据；
 * 本仓既有约定亦然（stores/settings.ts 的主题/字号、OrderListView 的状态页签都写在 localStorage）。
 *
 * localStorage 不可用（隐私模式 / 配额满 / 被策略禁用）时**静默降级为「仅本次会话生效」**：
 * 记住不了一个界面偏好，不该让列表页报错或白屏 —— 故读写两侧都不抛。
 */

/** 偏好键：`admin.ui.<name>:<routePath>`。name 与路由都进键，互不覆盖。 */
export function uiPrefKey(routePath: string, name: string): string {
  return `admin.ui.${name}:${routePath}`;
}

/** 读一项偏好；缺失 / 解析失败 / 存储不可用一律回落 fallback。 */
export function readUiPref<T>(routePath: string, name: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(uiPrefKey(routePath, name));
    if (raw === null) return fallback;
    const parsed: unknown = JSON.parse(raw);
    return parsed === null || parsed === undefined ? fallback : (parsed as T);
  } catch {
    // JSON 坏了或存储不可用：当成「没记住」。这里绝不能抛 —— 调用方都在列表页 setup 里，抛了就是白屏。
    return fallback;
  }
}

/** 读字符串数组型偏好（列顺序 / 隐藏列）。非数组一律当空，避免脏数据把列全打乱。 */
export function readUiPrefList(routePath: string, name: string): string[] {
  const value = readUiPref<unknown>(routePath, name, []);
  return Array.isArray(value) ? value.map(String) : [];
}

/** 写一项偏好；存储不可用时静默忽略（只影响「下次还记不记得」）。 */
export function writeUiPref(routePath: string, name: string, value: unknown): void {
  try {
    window.localStorage.setItem(uiPrefKey(routePath, name), JSON.stringify(value));
  } catch {
    // 配额满 / 隐私模式：不给用户报错，也不打断本次操作
  }
}
