import { ElMessageBox } from 'element-plus';
import type { Router } from 'vue-router';

/**
 * 未保存离开守卫（2026-10-14 审计补齐）：此前无 beforeunload / 路由确认，
 * 大表单（品牌设置、价格配置等）填一半切菜单/关标签页全丢且无提示。
 *
 * 用法（页面 setup 内）：
 *   const destroy = registerUnsavedGuard(() => brandDirty.value);
 *   onUnmounted(destroy);
 * main.ts 需调用 installUnsavedRouteGuard(router)（beforeunload 已随之安装）。
 *
 * 设计：
 * - 中央登记表 + 各页面自报脏状态（isDirty 闭包），守卫汇总判断；
 * - 路由级：beforeEach 弹确认，「放弃更改并离开」放行一次后复位；
 * - 浏览器级：beforeunload 关标签/刷新兜底；
 * - isDirty 抛错视为不脏（脏检查故障不阻断导航）。
 */
interface UnsavedGuardEntry {
  isDirty: () => boolean;
}

const entries = new Set<UnsavedGuardEntry>();
let beforeUnloadInstalled = false;
let routeGuardInstalled = false;
let allowNextNavigation = false;

function anyDirty(): boolean {
  for (const entry of entries) {
    try {
      if (entry.isDirty()) return true;
    } catch {
      /* ignore：单个检查故障不阻断 */
    }
  }
  return false;
}

export function installUnsavedBeforeUnload(): void {
  if (beforeUnloadInstalled || typeof window === 'undefined') return;
  beforeUnloadInstalled = true;
  window.addEventListener('beforeunload', (e: BeforeUnloadEvent) => {
    if (!anyDirty()) return;
    e.preventDefault();
    e.returnValue = '';
  });
}

export function installUnsavedRouteGuard(router: Router): void {
  if (routeGuardInstalled) return;
  routeGuardInstalled = true;
  installUnsavedBeforeUnload();
  router.beforeEach(async (to, from) => {
    if (to.path === from.path || allowNextNavigation) return true;
    if (!anyDirty()) return true;
    try {
      await ElMessageBox.confirm('当前页面有未保存的更改，离开将丢失。', '未保存的更改', {
        confirmButtonText: '放弃更改并离开',
        cancelButtonText: '留在本页',
        type: 'warning'
      });
    } catch {
      return false; // 留在本页
    }
    allowNextNavigation = true; // 放行本次，afterEach 复位
    return true;
  });
  router.afterEach(() => {
    allowNextNavigation = false;
  });
}

/** 注册脏状态检查；返回注销函数（onUnmounted 调用）。 */
export function registerUnsavedGuard(isDirty: () => boolean): () => void {
  const entry: UnsavedGuardEntry = { isDirty };
  entries.add(entry);
  installUnsavedBeforeUnload();
  return () => {
    entries.delete(entry);
  };
}
