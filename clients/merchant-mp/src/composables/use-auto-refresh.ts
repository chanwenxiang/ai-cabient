import { onUnmounted } from 'vue';
import { onHide, onPageHide, onPageShow, onShow, onUnload } from '@dcloudio/uni-app';
import {
  createAutoRefresher,
  isOrderTerminal,
  ORDER_TERMINAL_STATUSES,
  type AutoRefreshHandle,
  type AutoRefreshOptions
} from '@aicabinet/shared-uni/auto-refresh';

/**
 * 把轮询器的**纯逻辑**（`@aicabinet/shared-uni/auto-refresh`）挂到小程序生命周期上。
 *
 * 为什么要分两层：共享包不能反向 import `@dcloudio/uni-app`（它的依赖里没有这个包，
 * 类型解析会直接失败），所以「什么时候启停」的宿主接线留在各端。
 *
 * 🔴 两套钩子都要注册，缺一会静默失效：
 * - 页面实例：`onShow` / `onHide` / `onUnload`；
 * - 组件实例：小程序端 uni 运行时把组件的 pageLifetimes 映射成 `onPageShow` / `onPageHide`，
 *   **组件里的 `onShow` 永远不会被调用**（实测：merchant-mp 的 WalletPage 曾把 `onShow(load)`
 *   写在组件里，进页面根本不加载）。
 * `start`/`stop` 幂等，两套同时触发无副作用。
 */
export function useAutoRefresh(options: AutoRefreshOptions): AutoRefreshHandle {
  const refresher = createAutoRefresher(options);

  onShow(refresher.start);
  onPageShow(refresher.start);
  onHide(refresher.stop);
  onPageHide(refresher.stop);
  onUnload(refresher.dispose);
  onUnmounted(refresher.dispose);

  return refresher;
}

export { createAutoRefresher, isOrderTerminal, ORDER_TERMINAL_STATUSES };
export type { AutoRefreshHandle, AutoRefreshOptions };
