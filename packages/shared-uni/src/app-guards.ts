/**
 * 小程序全局兜底（consumer-mp / merchant-mp 共用）：
 * 1. onError / onUnhandledRejection：JS 异常不再无声消失——至少落 console，
 *    开发者工具/真机调试面板可查；此前两个小程序都没有任何全局错误钩子；
 * 2. onPageNotFound：坏码 / 失效分享链接直接 reLaunch 首页，替代裸报错页。
 *
 * 🔴 钩子由调用方注入而非本模块直接 import '@dcloudio/uni-app'：
 *    shared-uni 只有 vue peer 依赖，直接 import 会在两端 vue-tsc 下报 TS2307
 *    （2026-10-14 实证）。App.vue 里 `import { onError, ... } from '@dcloudio/uni-app'`
 *    后传入本函数即可。
 *
 * 必须在 App.vue setup 体内同步调用（uni-app 应用级生命周期钩子在 setup 注册）。
 */
export interface AppGuardHooks {
  onError(cb: (err: unknown) => void): void;
  onUnhandledRejection(cb: (res: { reason?: unknown }) => void): void;
  onPageNotFound(
    cb: (res: { path?: unknown; query?: unknown; isEntryPage?: boolean }) => void
  ): void;
}

function reLaunchHome(homePath: string): void {
  const u = (globalThis as Record<string, unknown>).uni as {
    reLaunch?: (opts: { url: string }) => void;
  } | null;
  if (u && typeof u.reLaunch === 'function') {
    u.reLaunch({ url: homePath });
  }
}

export function installAppGuards(hooks: AppGuardHooks, opts: { homePath: string }): void {
  hooks.onError((err) => {
    console.error('[app] 全局 JS 错误：', err);
  });
  hooks.onUnhandledRejection((res) => {
    console.error('[app] 未处理的 Promise 拒绝：', res.reason);
  });
  hooks.onPageNotFound((res) => {
    console.warn('[app] 页面不存在，回首页：', res.path, res.query);
    reLaunchHome(opts.homePath);
  });
}
