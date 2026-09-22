/**
 * 高德 JS API 2.0 按需加载封装。
 *
 * key 来自 `VITE_AMAP_JS_KEY`（本机 `.env.development.local`，Web端(JS API) 类型），
 * 未配置时 `loadAmap()` 返回 null，调用方降级到 Leaflet 免 key 瓦片。安全密钥可选
 * （`VITE_AMAP_SECURITY_CODE`），有值时按官方要求注入 `window._AMapSecurityConfig`。
 *
 * 🔴 只放 development 档，**不要**用 `.env.local`：`.env.local` 在**所有 mode**下加载，
 * 会把 key 字面量内联进 `vite build`（production）的产物，而 CI 检出里没有该文件
 * ⇒ 同一提交在两处构建得到不同 chunk 哈希，`admin-artifacts` 逐字节比对永远对不上。
 * 现约定：dev server(:3000) 带 key 走高德；production 产物不带 key、降级 Leaflet，
 * 使「本机产物 / 入库产物 / CI 重建产物」三者一致。
 */

const JSAPI_KEY = import.meta.env.VITE_AMAP_JS_KEY?.trim() || '';
const SECURITY_CODE = import.meta.env.VITE_AMAP_SECURITY_CODE?.trim() || '';

export function isAmapConfigured(): boolean {
  return JSAPI_KEY.length > 0;
}

/** 最小能力面：只声明大屏用到的成员，避免引入官方 types 包。 */
export interface AmapNS {
  Map: new (el: HTMLElement, opts: Record<string, unknown>) => AmapMap;
  Marker: new (opts: Record<string, unknown>) => AmapMarkerLike;
  InfoWindow: new (opts: Record<string, unknown>) => AmapInfoWindow;
  LngLat: new (lng: number, lat: number) => unknown;
  Pixel: new (x: number, y: number) => unknown;
}
export interface AmapInfoWindow {
  open: (map: AmapMap, position: number[]) => void;
  close: () => void;
  setContent: (content: string | HTMLElement) => void;
}
export interface AmapMap {
  add: (overlay: AmapMarkerLike | AmapMarkerLike[]) => void;
  clearMap: () => void;
  setFitView: (overlays?: AmapMarkerLike[] | null, immediately?: boolean, avoid?: number[]) => void;
  destroy: () => void;
}
export interface AmapMarkerLike {
  getPosition: () => { getLng: () => number; getLat: () => number };
  on: (event: string, handler: (e: unknown) => void) => void;
}

declare global {
  interface Window {
    _AMapSecurityConfig?: { securityJsCode: string };
  }
}

let loaderPromise: Promise<AmapNS> | null = null;

export function loadAmap(): Promise<AmapNS> | null {
  if (!isAmapConfigured()) return null;
  if (loaderPromise) return loaderPromise;
  loaderPromise = (async () => {
    if (SECURITY_CODE) {
      window._AMapSecurityConfig = { securityJsCode: SECURITY_CODE };
    }
    await loadScript('https://webapi.amap.com/loader.js');
    const w = window as unknown as { AMapLoader?: AmapLoaderNS };
    if (!w.AMapLoader) throw new Error('高德 loader.js 加载失败');
    return (await w.AMapLoader.load({
      key: JSAPI_KEY,
      version: '2.0'
    })) as AmapNS;
  })();
  // 失败后允许重试（网络恢复 / key 修正）
  loaderPromise.catch(() => {
    loaderPromise = null;
  });
  return loaderPromise;
}

interface AmapLoaderNS {
  load: (opts: { key: string; version: string; plugins?: string[] }) => Promise<unknown>;
}

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const exist = document.querySelector(`script[src="${src}"]`);
    if (exist) {
      resolve();
      return;
    }
    const el = document.createElement('script');
    el.src = src;
    el.onload = () => resolve();
    el.onerror = () => reject(new Error(`脚本加载失败：${src}`));
    document.head.appendChild(el);
  });
}
