/**
 * 高德 JS API 2.0 按需加载封装（大屏暗色底图）。
 *
 * key 解析优先级（前者优先；两者都是**运行时**求值，不再模块加载时固化）：
 *   1. `window.__APP_RUNTIME__.amapJsKey` —— **运行时配置**，由部署侧经
 *      `/admin/runtime-config.json` 注入（生成脚本见 `scripts/gen-admin-runtime-config.mjs`）。
 *      gateway 把 `static/admin` 直接 bind-mount 给 nginx，因此该文件可以「构建后」再放进去：
 *      产物本身仍与 CI 重建逐字节一致，密钥不参与 `admin-artifacts` 比对。
 *   2. `import.meta.env.VITE_AMAP_JS_KEY` —— **构建时**注入，只在本机 dev server(:3000) 走这条
 *      （值来自 `clients/admin-vue/.env.development.local`，仅 development 档加载）。
 *   3. 都没有 ⇒ `loadAmap()` 返回 null，调用方降级到 Leaflet 免 key 瓦片。
 * 安全密钥同理（`amapSecurityCode` / `VITE_AMAP_SECURITY_CODE`），有值时按官方要求注入
 * `window._AMapSecurityConfig`。
 *
 * 🔴 **禁止**把 JS key 放进 `.env.local`：它在**所有 mode** 下加载，会被内联进 `vite build`
 *    （production）产物；而 CI 检出里没有该文件 ⇒ 同一提交在两处构建得到不同 chunk 哈希，
 *    `admin-artifacts` 的逐字节比对永远对不上（2026-09-22 实测：`BigScreenView` chunk 由
 *    `BYBxZz4p`（含 key）变 `D4lgoeHc`（无 key））。
 * 🔴 **禁止**把 key 写进任何**入库**文件（含 nginx conf、compose、本文件注释）。
 *    部署侧真值源 = `infra/.env`（未入库，与 `AMAP_WEB_KEY` 等并列；注意那是后端 Web 服务
 *    key，**不是**这个前端 JS API key，两者在控制台是不同条目）。
 */

interface RuntimeConfig {
  amapJsKey?: string;
  amapSecurityCode?: string;
}

declare global {
  interface Window {
    _AMapSecurityConfig?: { securityJsCode: string };
    /** 运行时配置：由 `/admin/runtime-config.json` 注入，缺失时为 undefined。 */
    __APP_RUNTIME__?: RuntimeConfig;
  }
}

function runtime(): RuntimeConfig {
  return window.__APP_RUNTIME__ ?? {};
}

function resolveJsApiKey(): string {
  return (runtime().amapJsKey || import.meta.env.VITE_AMAP_JS_KEY || '').trim();
}

function resolveSecurityCode(): string {
  return (runtime().amapSecurityCode || import.meta.env.VITE_AMAP_SECURITY_CODE || '').trim();
}

/** 是否已配置：以**当前已解析**的值为准（调用方应先 await `loadAmap()`，由其内部拉取运行时配置）。 */
export function isAmapConfigured(): boolean {
  return resolveJsApiKey().length > 0;
}

/**
 * 拉取运行时配置（一次、失败静默）。
 *
 * 约定：本文件缺失（CI 检出、未注入的部署、dev server）⇒ 404 ⇒ 按「未配置」处理并降级
 * Leaflet，**不报错也不阻塞**。dev server 与 CI 都不依赖它。
 */
let runtimePromise: Promise<void> | null = null;
function ensureRuntimeConfig(): Promise<void> {
  if (runtimePromise) return runtimePromise;
  runtimePromise = (async () => {
    try {
      const res = await fetch(`${import.meta.env.BASE_URL}runtime-config.json`, {
        cache: 'no-store'
      });
      if (!res.ok) return;
      const json = (await res.json()) as RuntimeConfig;
      if (json && typeof json === 'object') {
        window.__APP_RUNTIME__ = { ...(window.__APP_RUNTIME__ ?? {}), ...json };
      }
    } catch {
      /* 静默降级：无运行时配置时走构建时 env，再退回 Leaflet */
    }
  })();
  return runtimePromise;
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

let loaderPromise: Promise<AmapNS> | null = null;

/**
 * 加载高德 JS API。
 *
 * - 返回 `null`：**未配置** key（调用方降级 Leaflet）
 * - 抛错：已配置但 loader / 脚本加载失败（调用方同样应降级）
 */
export async function loadAmap(): Promise<AmapNS | null> {
  await ensureRuntimeConfig();
  const jsApiKey = resolveJsApiKey();
  if (!jsApiKey) return null;
  if (!loaderPromise) {
    loaderPromise = (async () => {
      const securityCode = resolveSecurityCode();
      if (securityCode) {
        window._AMapSecurityConfig = { securityJsCode: securityCode };
      }
      await loadScript('https://webapi.amap.com/loader.js');
      const w = window as unknown as { AMapLoader?: AmapLoaderNS };
      if (!w.AMapLoader) throw new Error('高德 loader.js 加载失败');
      return (await w.AMapLoader.load({
        key: jsApiKey,
        version: '2.0'
      })) as AmapNS;
    })();
    // 失败后允许重试（网络恢复 / key 修正）
    loaderPromise.catch(() => {
      loaderPromise = null;
    });
  }
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
