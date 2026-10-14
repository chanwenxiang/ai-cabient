import { readonly, ref } from 'vue';

/**
 * 三端统一断网状态（consumer-mp / merchant-mp / admin-vue 共用）。
 *
 * 为什么放 shared-uni：三端都要「断网先知」，各写一份必然漂移。
 * - uni 运行时（微信小程序 + uni H5）：`uni.getNetworkType` + `uni.onNetworkStatusChange`；
 * - 纯 web 运行时（admin-vue）：`navigator.onLine` + window online/offline 事件。
 * 通过 globalThis 探测 uni，不直接引用 `uni` 标识符——admin-vue 没有 @dcloudio/types，
 * 直接写 `uni` 会在其 vue-tsc 下报 Cannot find name。
 *
 * 模块级单例 + 引用计数：多页/多组件共享同一份状态，最后一个观察者注销才摘监听。
 * 请求层已有失败文案兜底（shared-uni/request.ts），本模块负责「发请求之前就知道没网」。
 */

/** 与 UniApp.OnNetworkStatusChangeCallbackResult 结构对齐的最小面 */
interface NetStatusResult {
  isConnected: boolean;
  networkType: string;
}

interface UniNetApi {
  getNetworkType: (opts: { success: (res: { networkType: string }) => void }) => void;
  onNetworkStatusChange: (cb: (res: NetStatusResult) => void) => void;
  offNetworkStatusChange: (cb: (res: NetStatusResult) => void) => void;
}

const offline = ref(false);
let watcherCount = 0;
let uniHandler: ((res: NetStatusResult) => void) | null = null;
let winOnline: (() => void) | null = null;
let winOffline: (() => void) | null = null;

function getUniNetApi(): UniNetApi | null {
  const u = (globalThis as Record<string, unknown>).uni as UniNetApi | undefined;
  if (
    u &&
    typeof u.getNetworkType === 'function' &&
    typeof u.onNetworkStatusChange === 'function' &&
    typeof u.offNetworkStatusChange === 'function'
  ) {
    return u;
  }
  return null;
}

function applyWebState() {
  offline.value = typeof navigator !== 'undefined' ? navigator.onLine === false : false;
}

/** 开始监听（幂等，引用计数）。在页面 onLoad/onMounted 调用。 */
export function startNetworkWatch(): void {
  watcherCount++;
  if (watcherCount > 1) return;
  const u = getUniNetApi();
  if (u) {
    u.getNetworkType({
      success: (res) => {
        offline.value = res.networkType === 'none';
      }
    });
    uniHandler = (res) => {
      offline.value = !res.isConnected || res.networkType === 'none';
    };
    u.onNetworkStatusChange(uniHandler);
    return;
  }
  if (typeof window !== 'undefined') {
    applyWebState();
    winOnline = () => applyWebState();
    winOffline = () => applyWebState();
    window.addEventListener('online', winOnline);
    window.addEventListener('offline', winOffline);
  }
}

/** 停止监听（引用计数归零才真正摘除）。在页面 onUnload/onUnmounted 调用。 */
export function stopNetworkWatch(): void {
  if (watcherCount > 0) watcherCount--;
  if (watcherCount > 0) return;
  const u = getUniNetApi();
  if (uniHandler && u) {
    u.offNetworkStatusChange(uniHandler);
  }
  uniHandler = null;
  if (typeof window !== 'undefined' && winOnline && winOffline) {
    window.removeEventListener('online', winOnline);
    window.removeEventListener('offline', winOffline);
    winOnline = null;
    winOffline = null;
  }
}

/** 三端共用的断网状态（只读 ref）。 */
export function useNetworkStatus() {
  return {
    offline: readonly(offline),
    startNetworkWatch,
    stopNetworkWatch
  };
}

/** 同步快照：发请求前的守卫用（不依赖组件上下文）。 */
export function isNetworkOffline(): boolean {
  return offline.value;
}
